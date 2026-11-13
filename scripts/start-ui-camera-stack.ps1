param(
    [string]$UbuntuDistro = "Ubuntu-24.04",
    [switch]$SkipBootstrap,
    [switch]$SkipBuild,
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipDrone,
    [switch]$WithSimulation,
    [switch]$RealFlight,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"

# The full simulator is the default again. Keep -WithSimulation accepted for
# compatibility with existing shortcuts; use -SkipDrone for an intentional
# UI-only launch.

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function ConvertTo-WslPath([string]$WindowsPath) {
    $fullPath = (Resolve-Path $WindowsPath).Path
    if ($fullPath -notmatch "^([A-Za-z]):\\(.*)$") {
        throw "Cannot convert path to WSL format: $fullPath"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $rest = $Matches[2] -replace "\\", "/"
    return "/mnt/$drive/$rest"
}

function Write-Utf8NoBomLines([string]$Path, [string[]]$Lines) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $Lines, $encoding)
}

function Set-EnvValue([string]$Path, [string]$Name, [string]$Value) {
    if (-not (Test-Path $Path)) {
        New-Item -ItemType File -Path $Path -Force | Out-Null
    }

    $lines = @(Get-Content -Path $Path -ErrorAction SilentlyContinue)
    $entry = "$Name=$Value"
    $found = $false
    $nextLines = foreach ($line in $lines) {
        if ($line -match "^\s*#") {
            $line
        } elseif ($line -match "^\s*$([regex]::Escape($Name))\s*=") {
            $found = $true
            $entry
        } else {
            $line
        }
    }
    if (-not $found) {
        $nextLines += $entry
    }
    Write-Utf8NoBomLines $Path $nextLines
}

function Get-EnvValue([string]$Path, [string]$Name, [string]$Fallback) {
    if (-not (Test-Path $Path)) { return $Fallback }
    foreach ($line in Get-Content -Path $Path -ErrorAction SilentlyContinue) {
        if ($line -match "^\s*$([regex]::Escape($Name))\s*=\s*(?<value>.*)\s*$") {
            $value = $Matches["value"].Trim().Trim('"').Trim("'")
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                return $value
            }
        }
    }
    return $Fallback
}

function Start-TerminalTab([string]$Title, [string]$Command, [string]$WorkingDirectory) {
    $escapedCommand = $Command.Replace('"', '\"')
    $escapedDirectory = $WorkingDirectory.Replace('"', '\"')

    if (Get-Command wt.exe -ErrorAction SilentlyContinue) {
        $terminalArguments = @(
            "new-tab", "--title", $Title, "--startingDirectory", $WorkingDirectory,
            "powershell.exe", "-NoExit", "-ExecutionPolicy", "Bypass",
            "-Command", "Set-Location `"$escapedDirectory`"; $escapedCommand"
        )
        & wt.exe @terminalArguments | Out-Null
        return
    }

    Start-Process powershell.exe -ArgumentList @(
        "-NoExit", "-ExecutionPolicy", "Bypass",
        "-Command", "Set-Location `"$escapedDirectory`"; $escapedCommand"
    )
}

function Start-WslTab([string]$Title, [string]$Command) {
    if (Get-Command wt.exe -ErrorAction SilentlyContinue) {
        $terminalArguments = @(
            "new-tab", "--title", $Title,
            "wsl.exe", "-d", $UbuntuDistro, "--", "bash", "-lc", $Command
        )
        & wt.exe @terminalArguments | Out-Null
        return
    }

    Start-Process wsl.exe -ArgumentList @("-d", $UbuntuDistro, "--", "bash", "-lc", $Command)
}

function Start-WslWindow([string]$Title, [string]$Command) {
    $escapedCommand = $Command.Replace('"', '\"')
    $cmdLine = "title $Title && wsl.exe -d $UbuntuDistro -- bash -lc `"$escapedCommand`""
    Start-Process cmd.exe -ArgumentList @("/k", $cmdLine) -WindowStyle Normal
}

function Wait-HttpOk([string]$Url, [int]$TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return $true
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

function Test-CommandExists([string]$Name) {
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Ensure-Postgres([string]$BackendRoot, [string]$EnvFile) {
    $url = Get-EnvValue $EnvFile "SPRING_DATASOURCE_URL" ""
    $match = [regex]::Match($url, '^jdbc:postgresql://(?<host>[^/:?]+)(:(?<port>\d+))?/')
    if (-not $match.Success) {
        throw "SPRING_DATASOURCE_URL in .env must point to a PostgreSQL server."
    }

    $hostName = $match.Groups["host"].Value
    $port = if ($match.Groups["port"].Success) { [int]$match.Groups["port"].Value } else { 5432 }
    if (Test-TcpPort $hostName $port) { return }

    if ($hostName -notin @("localhost", "127.0.0.1", "::1") -or $port -ne 5433) {
        throw "PostgreSQL is not reachable at ${hostName}:$port. Start the database configured in .env, or use the local Compose defaults on port 5433."
    }
    if (-not (Test-CommandExists "docker.exe")) {
        throw "PostgreSQL is not running at localhost:5433 and Docker Desktop is unavailable. Install/start Docker Desktop, or point SPRING_DATASOURCE_URL at an existing PostgreSQL server."
    }

    Write-Step "Starting the local PostgreSQL database"
    Push-Location $BackendRoot
    try {
        & docker.exe compose --env-file $EnvFile up --detach --wait --wait-timeout 120 postgres
        if ($LASTEXITCODE -ne 0) {
            throw "Docker Compose could not start PostgreSQL. Make sure Docker Desktop is running."
        }
    } finally {
        Pop-Location
    }
    if (-not (Test-TcpPort $hostName $port)) {
        throw "PostgreSQL did not become reachable at ${hostName}:$port after Docker Compose started it."
    }
}

function Get-JavaMajorVersion {
    if (-not (Test-CommandExists "java.exe")) { return 0 }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $versionOutput = & java.exe -version 2>&1 | ForEach-Object { $_.ToString() } | Select-Object -First 1
        if ($versionOutput -match '"(?<version>[0-9]+)(\.|")') {
            return [int]$Matches["version"]
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return 0
}

function Install-WithWinget([string]$PackageId, [string]$DisplayName) {
    if (-not (Test-CommandExists "winget.exe")) {
        throw "$DisplayName is missing and winget is not available. Install $DisplayName, then run RUN_DRONE_STACK.cmd again."
    }

    Write-Step "Installing $DisplayName"
    & winget.exe install --id $PackageId --exact --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) {
        throw "Could not install $DisplayName automatically. Install it manually, then run RUN_DRONE_STACK.cmd again."
    }

    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machinePath;$userPath"
}

function Ensure-WindowsBuildTools {
    if ((Get-JavaMajorVersion) -lt 21) {
        Install-WithWinget "EclipseAdoptium.Temurin.21.JDK" "Java 21 JDK"
    }

    if (-not (Test-CommandExists "node.exe")) {
        Install-WithWinget "OpenJS.NodeJS.LTS" "Node.js LTS"
    }

    if (-not (Test-CommandExists "npm.cmd")) {
        throw "npm is still unavailable after checking Node.js. Reopen the terminal or restart Windows, then run RUN_DRONE_STACK.cmd again."
    }

    if ((Get-JavaMajorVersion) -lt 21) {
        throw "Java 21 is still unavailable. Reopen the terminal or restart Windows, then run RUN_DRONE_STACK.cmd again."
    }
}

function Invoke-Checked([string]$Label, [string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory) {
    Write-Step $Label
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Label failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

function Update-Forest3DAssetsFromGit([string]$Root) {
    $gitDir = Join-Path $Root ".git"
    if (-not (Test-Path $gitDir)) { return }
    if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) { return }

    Write-Host "Forest3D compact assets are missing. Trying to download latest Git assets..." -ForegroundColor Yellow
    Push-Location $Root
    try {
        & git.exe pull --ff-only
        if ($LASTEXITCODE -ne 0) {
            Write-Host "git pull did not complete. Continuing with local files." -ForegroundColor Yellow
            return
        }

        & git.exe lfs version *> $null
        if ($LASTEXITCODE -eq 0) {
            & git.exe lfs pull
        }
    } finally {
        Pop-Location
    }
}

function Resolve-Forest3DPath([string]$Root) {
    $rootParent = Split-Path -Parent $Root
    $candidates = @(
        (Join-Path $Root "Forest3D"),
        (Join-Path $Root "drone\Forest3D"),
        (Join-Path $Root "on-demand-monitoring-system\Forest3D"),
        (Join-Path $rootParent "Forest3D"),
        (Join-Path $rootParent "on-demand-monitoring-system\Forest3D")
    ) | Select-Object -Unique

    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate "models\compact_terrain\model.config")) {
            return (Resolve-Path $candidate).Path
        }
    }

    Update-Forest3DAssetsFromGit $Root
    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate "models\compact_terrain\model.config")) {
            return (Resolve-Path $candidate).Path
        }
    }

    $checked = ($candidates | ForEach-Object { "  - $_" }) -join [Environment]::NewLine
    throw "Cannot find Forest3D compact Gazebo assets. Make sure the package includes Forest3D\models\compact_terrain\model.config. Checked:$([Environment]::NewLine)$checked"
}

function Assert-CompactMapAssets([string]$Root, [string]$Forest3DPath) {
    $worldFile = Join-Path $Forest3DPath "worlds\forest_monitoring_compact.sdf"
    $fastDemoWorldFile = Join-Path $Forest3DPath "worlds\forest_monitoring_compact_fast_demo.sdf"
    $mapImage = Join-Path $Root "ondemandmonitoring\src\main\resources\static\simulation-viewer\simulation_map_top.png"
    $mapMeta = Join-Path $Root "ondemandmonitoring\src\main\resources\static\simulation-viewer\simulation-map.json"
    $modelRoot = Join-Path $Forest3DPath "models"
    $requiredModels = @(
        "compact_terrain",
        "compact_terrain_fast_demo",
        "compact_water",
        "compact_roads",
        "compact_bridges",
        "compact_home",
        "compact_highrise",
        "compact_zones",
        "compact_forest",
        "compact_thermal_sources",
        "compact_environment_props",
        "compact_mountains",
        "compact_airport",
        "x500_mono_cam_down"
    )

    if (-not (Test-Path $worldFile)) { throw "Full compact world is missing: $worldFile" }
    if (-not (Test-Path $fastDemoWorldFile)) { throw "Compact fast demo world is missing: $fastDemoWorldFile" }
    if (-not (Test-Path $mapImage)) { throw "Full simulation map image is missing: $mapImage" }
    if (-not (Test-Path $mapMeta)) { throw "Simulation map metadata is missing: $mapMeta" }
    foreach ($model in $requiredModels) {
        $config = Join-Path $modelRoot "$model\model.config"
        $sdf = Join-Path $modelRoot "$model\model.sdf"
        if (-not (Test-Path $config)) { throw "Packaged Gazebo model config is missing: $config" }
        if (-not (Test-Path $sdf)) { throw "Packaged Gazebo model SDF is missing: $sdf" }
    }

    $meta = Get-Content -Raw -Path $mapMeta | ConvertFrom-Json
    if ($meta.worldName -ne "forest_monitoring_compact") {
        throw "Simulation map metadata is not compact world. Expected forest_monitoring_compact, got $($meta.worldName)."
    }
}

$systemRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot "initialize-local-demo-config.ps1")
Initialize-LocalDemoConfig -ProjectRoot $systemRoot
$workspaceRoot = (Resolve-Path (Join-Path $systemRoot "..")).Path
$backendRoot = Join-Path $systemRoot "ondemandmonitoring"
$webRoot = Join-Path $workspaceRoot "ondemand-monitoring-web"
$packagedWorld = "compact"
$packagedWorldName = "Forest3D compact world"
$fastDemoMode = if ($RealFlight) { "0" } else { "1" }
$realisticFlight = if ($RealFlight) { "1" } else { "0" }
$controlMoveSpeed = if ($RealFlight) { "12" } else { "20" }
$controlVerticalSpeed = if ($RealFlight) { "2" } else { "10" }
$px4SpeedLimit = if ($RealFlight) { "18" } else { "20" }
$gpsRouteExpectedSpeed = if ($RealFlight) { "10" } else { "220" }
$autoPlanReachedRadius = if ($RealFlight) { "5" } else { "12" }
$autoPlanMaxSpeed = if ($RealFlight) { "8" } else { "20" }
$autoPlanMinSpeed = if ($RealFlight) { "1.2" } else { "10" }
$autoPlanSlowdownRadius = if ($RealFlight) { "35" } else { "80" }
$autoPlanAltitudeTolerance = if ($RealFlight) { "4" } else { "2" }
$autoPlanVerticalMaxSpeed = if ($RealFlight) { "0.5" } else { "10" }
$autoPlanVerticalGain = if ($RealFlight) { "0.08" } else { "2" }
$autoPlanSmoothing = "0.35"
$autoPlanAltitudeRamp = if ($RealFlight) { "1" } else { "80" }
$autoPlanHorizontalAccel = "2.5"
$autoPlanVerticalAccel = if ($RealFlight) { "2" } else { "6" }
$demoLandSpeed = if ($RealFlight) { "0.7" } else { "3" }
$autoPlanMediaSettle = if ($RealFlight) { "1.4" } else { "0.6" }
# Realtime simulation keeps control and camera timing consistent across machines.
$px4SimSpeedFactor = "1"
$downTopic = "/world/forest_monitoring_compact/model/x500_mono_cam_down_0/link/camera_link/sensor/camera_down/image"
$frontTopic = "/world/forest_monitoring_compact/model/x500_mono_cam_down_0/link/camera_link/sensor/camera_front/image"

if (-not (Test-Path $backendRoot)) {
    Write-Host "Backend folder not found, skipping backend startup: $backendRoot" -ForegroundColor Yellow
    $SkipBackend = $true
}
if (-not (Test-Path $webRoot)) {
    Write-Host "Frontend folder not found, skipping frontend startup: $webRoot" -ForegroundColor Yellow
    $SkipFrontend = $true
}
$forest3DPath = $null
if (-not $SkipDrone) {
    $forest3DPath = Resolve-Forest3DPath $systemRoot
    Assert-CompactMapAssets $systemRoot $forest3DPath
}

$backendEnvFile = Join-Path $systemRoot ".env"
$rootEnvFile = $backendEnvFile
if (-not (Test-Path -LiteralPath $backendEnvFile -PathType Leaf)) {
    throw "Missing shared backend and drone configuration: $backendEnvFile"
}
if (-not $SkipBackend) {
    $missingCognito = @("AWS_REGION", "AWS_COGNITO_REGION", "AWS_COGNITO_USER_POOL_ID", "AWS_COGNITO_CLIENT_ID", "COGNITO_ISSUER_URI") |
        Where-Object { [string]::IsNullOrWhiteSpace((Get-EnvValue $backendEnvFile $_ "")) }
    if ($missingCognito.Count -gt 0) {
        throw "The authenticated web stack needs AWS/Cognito settings in .env: $($missingCognito -join ', '). Set them from your development Cognito pool, or use scripts\RUN_DRONE_SIM_ONLY.cmd for simulator-only mode."
    }
    Ensure-Postgres $backendRoot $backendEnvFile
}
if (-not $SkipDrone) {
    Set-EnvValue $backendEnvFile "SIM_WORLD" $packagedWorld
    Set-EnvValue $backendEnvFile "FOREST3D_WEB_ONLY" "1"
    Set-EnvValue $backendEnvFile "FAST_DEMO_MODE" $fastDemoMode
    Set-EnvValue $backendEnvFile "REALISTIC_FLIGHT" $realisticFlight
    Set-EnvValue $backendEnvFile "FAST_DEMO_LIDAR_ENABLED" "1"
    Set-EnvValue $backendEnvFile "CONTROL_MOVE_SPEED_M_S" $controlMoveSpeed
    Set-EnvValue $backendEnvFile "CONTROL_VERTICAL_SPEED_M_S" $controlVerticalSpeed
    Set-EnvValue $backendEnvFile "PX4_SPEED_LIMIT_M_S" $px4SpeedLimit
    Set-EnvValue $backendEnvFile "AUTO_PLAN_REACHED_RADIUS_M" $autoPlanReachedRadius
    Set-EnvValue $backendEnvFile "AUTO_PLAN_MAX_SPEED_M_S" $autoPlanMaxSpeed
    Set-EnvValue $backendEnvFile "AUTO_PLAN_MIN_SPEED_M_S" $autoPlanMinSpeed
    Set-EnvValue $backendEnvFile "AUTO_PLAN_SLOWDOWN_RADIUS_M" $autoPlanSlowdownRadius
    Set-EnvValue $backendEnvFile "AUTO_PLAN_ALTITUDE_TOLERANCE_M" $autoPlanAltitudeTolerance
    Set-EnvValue $backendEnvFile "AUTO_PLAN_VERTICAL_MAX_SPEED_M_S" $autoPlanVerticalMaxSpeed
    Set-EnvValue $backendEnvFile "AUTO_PLAN_VERTICAL_GAIN" $autoPlanVerticalGain
    Set-EnvValue $backendEnvFile "AUTO_PLAN_SETPOINT_SMOOTHING" $autoPlanSmoothing
    Set-EnvValue $backendEnvFile "AUTO_PLAN_ALTITUDE_RAMP_M" $autoPlanAltitudeRamp
    Set-EnvValue $backendEnvFile "AUTO_PLAN_HORIZONTAL_ACCEL_M_S2" $autoPlanHorizontalAccel
    Set-EnvValue $backendEnvFile "AUTO_PLAN_VERTICAL_ACCEL_M_S2" $autoPlanVerticalAccel
    Set-EnvValue $backendEnvFile "DEMO_LAND_SPEED_M_S" $demoLandSpeed
    Set-EnvValue $backendEnvFile "AUTO_PLAN_MEDIA_SETTLE_S" $autoPlanMediaSettle
    Set-EnvValue $backendEnvFile "PX4_SIM_SPEED_FACTOR" $px4SimSpeedFactor
    Set-EnvValue $backendEnvFile "GPS_ROUTE_EXPECTED_SPEED_M_S" $gpsRouteExpectedSpeed
    Set-EnvValue $backendEnvFile "GAZEBO_CAMERA_TOPIC" $downTopic
    Set-EnvValue $backendEnvFile "GAZEBO_CAMERA_DOWN_TOPIC" $downTopic
    Set-EnvValue $backendEnvFile "GAZEBO_CAMERA_FRONT_TOPIC" $frontTopic
    Set-EnvValue $backendEnvFile "CAMERA_DEFAULT_VIEW" "FRONT"
}

Write-Host "========================================" -ForegroundColor Green
Write-Host " OMSS UI Camera Stack" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host "Backend : http://localhost:8080"
Write-Host "Frontend: http://localhost:5173/#portal/drone-operator"
if (-not $SkipDrone) {
    Write-Host "Control : http://localhost:8090/stream.mjpg"
    Write-Host "World   : $packagedWorldName"
    Write-Host "Mode    : $(if ($RealFlight) { 'Real PX4 takeoff + mapping flight' } else { 'Fast demo in the Forest3D world' })"
} else {
    Write-Host "Mode    : UI only; PX4/Gazebo is not started"
}

Ensure-WindowsBuildTools

if (-not $SkipBuild) {
    if (-not $SkipBackend) {
        Invoke-Checked `
            -Label "Building Backend API" `
            -FilePath (Join-Path $backendRoot "mvnw.cmd") `
            -Arguments @("-Dmaven.test.skip=true", "package") `
            -WorkingDirectory $backendRoot
    }

    if (-not $SkipFrontend) {
        Invoke-Checked `
            -Label "Installing Frontend packages" `
            -FilePath "npm.cmd" `
            -Arguments @("install") `
            -WorkingDirectory $webRoot

        Invoke-Checked `
            -Label "Building Frontend UI" `
            -FilePath "npm.cmd" `
            -Arguments @("run", "build") `
            -WorkingDirectory $webRoot
    }
}

if (-not $SkipBackend) {
    Write-Step "Starting Backend API"
    Start-TerminalTab -Title "BE - OMSS API" -WorkingDirectory $backendRoot -Command ".\mvnw.cmd spring-boot:run"
}

if (-not $SkipFrontend) {
    Write-Step "Preparing and starting Frontend UI"
    $envFile = Join-Path $webRoot ".env.local"
    Write-Utf8NoBomLines $envFile @(
        "VITE_API_BASE_URL=$(Get-EnvValue $rootEnvFile 'VITE_API_BASE_URL' 'http://localhost:8080')",
        "VITE_FLIGHT_CONTROL_API_URL=$(Get-EnvValue $rootEnvFile 'VITE_FLIGHT_CONTROL_API_URL' 'http://localhost:8090')",
        "VITE_AWS_COGNITO_DOMAIN=$(Get-EnvValue $rootEnvFile 'AWS_COGNITO_DOMAIN' '')",
        "VITE_AWS_COGNITO_CLIENT_ID=$(Get-EnvValue $rootEnvFile 'AWS_COGNITO_CLIENT_ID' '')",
        "VITE_COGNITO_REDIRECT_SIGN_IN=$(Get-EnvValue $rootEnvFile 'VITE_COGNITO_REDIRECT_SIGN_IN' 'http://localhost:5173/social/callback')",
        "VITE_USE_MOCK_API=false",
        "VITE_FLIGHT_MAP_ONLY=false"
    )
    Start-TerminalTab -Title "FE - OMSS UI" -WorkingDirectory $webRoot -Command "if (-not (Test-Path node_modules)) { npm install }; npm run dev -- --host 0.0.0.0 --port 5173"
}

if ((-not $SkipDrone) -and (-not $SkipBootstrap)) {
    Write-Step "Checking WSL/PX4/Gazebo dependencies"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "bootstrap-drone-stack.ps1") -UbuntuDistro $UbuntuDistro
    if ($LASTEXITCODE -ne 0) { throw "Drone dependency bootstrap failed." }
}

$systemRootWsl = ConvertTo-WslPath $systemRoot
$forest3DPathWsl = if ($forest3DPath) { ConvertTo-WslPath $forest3DPath } else { "" }
$scriptRootWsl = "$systemRootWsl/scripts"

if ($SkipDrone) {
    if ((-not $NoBrowser) -and (-not $SkipFrontend)) {
        Write-Step "Waiting for UI, then opening Mission Control"
        if (Wait-HttpOk "http://localhost:5173" 90) {
            Start-Process "http://localhost:5173/#portal/drone-operator"
        } else {
            Write-Host "Frontend did not answer yet. Open manually: http://localhost:5173/#portal/drone-operator" -ForegroundColor Yellow
        }
    }

    Write-Step "UI started; PX4/Gazebo simulation was not started"
    Write-Host "Connect a live flight-control service at http://localhost:8090 to enable live telemetry and commands." -ForegroundColor Yellow
    exit 0
}

Write-Step "Cleaning old PX4/Gazebo/MAVSDK processes"
& wsl.exe -d $UbuntuDistro -- bash -lc "PROJECT_PATH='$systemRootWsl' FAST_DEMO_MODE=$fastDemoMode exec '$scriptRootWsl/wsl-clean-drone-stack.sh'" | Out-Null

Write-Step "Starting PX4/Gazebo Forest3D world and Flight Control"
$baseWslEnv = "PROJECT_PATH='$systemRootWsl' FAST_DEMO_MODE=$fastDemoMode REALISTIC_FLIGHT=$realisticFlight FAST_DEMO_LIDAR_ENABLED=1 CONTROL_MOVE_SPEED_M_S=$controlMoveSpeed CONTROL_VERTICAL_SPEED_M_S=$controlVerticalSpeed PX4_SPEED_LIMIT_M_S=$px4SpeedLimit GPS_ROUTE_EXPECTED_SPEED_M_S=$gpsRouteExpectedSpeed"
if ($forest3DPathWsl) { $baseWslEnv += " FOREST3D_PATH='$forest3DPathWsl'" }
$simCommand = "$baseWslEnv FOREST3D_WEB_ONLY=1 SIM_WORLD=$packagedWorld exec '$scriptRootWsl/wsl-sim-pane.sh' '$packagedWorld'"
$controlCommand = "$baseWslEnv FOREST3D_WEB_ONLY=1 SIM_WORLD=$packagedWorld exec '$scriptRootWsl/wsl-control.sh'"
$telemetryCommand = "$baseWslEnv exec '$scriptRootWsl/wsl-telemetry.sh'"

Start-WslTab -Title "SIM - PX4 + Gazebo Server" -Command $simCommand
Start-Sleep -Seconds 3
Start-WslTab -Title "$(if ($RealFlight) { 'CTRL - Real Flight Control' } else { 'CTRL - Fast Flight Control' })" -Command $controlCommand
Start-Sleep -Seconds 2
Start-WslTab -Title "TEL - Backend Telemetry" -Command $telemetryCommand

if ((-not $NoBrowser) -and (-not $SkipFrontend)) {
    Write-Step "Waiting for UI, then opening Mission Control"
    if (Wait-HttpOk "http://localhost:5173" 90) {
        Start-Process "http://localhost:5173/#portal/drone-operator"
    } else {
        Write-Host "Frontend did not answer yet. Open manually: http://localhost:5173/#portal/drone-operator" -ForegroundColor Yellow
    }
}

Write-Step "Started"
if ($RealFlight) {
    Write-Host "Real flight mode is active: use the UI GPS target/mapping action to arm, take off, and fly the PX4 drone." -ForegroundColor Yellow
} else {
    Write-Host "Fast demo mode is active in the Forest3D compact world. Use the web UI to view and control the drone." -ForegroundColor Yellow
}
