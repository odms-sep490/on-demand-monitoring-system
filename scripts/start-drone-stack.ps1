param(
    [ValidateSet("legacy", "compact", "light")]
    [string]$SimWorld = "compact",
    [switch]$ShowGazeboGui,
    [switch]$WithTelemetry,
    [switch]$NoTelemetry,
    [switch]$WithCamera,
    [switch]$WithSensors,
    [switch]$WithWeather,
    [ValidateRange(0.1, 180.0)]
    [double]$YawStepDeg = 5.0,
    [double]$HomeLatitude = 10.7769,
    [double]$HomeLongitude = 106.7009,
    [double]$HomeAltitude = 0.0,
    [switch]$SkipBootstrap
)

$ErrorActionPreference = "Stop"

# Backend telemetry (GPS/battery -> backend DB) is required for Mapillary reference capture, so it is on by default.
if (-not $NoTelemetry) { $WithTelemetry = [switch]::new($true) }

$hasHomeLatitude = $PSBoundParameters.ContainsKey("HomeLatitude")
$hasHomeLongitude = $PSBoundParameters.ContainsKey("HomeLongitude")
if ($hasHomeLatitude -ne $hasHomeLongitude) {
    throw "Set both HomeLatitude and HomeLongitude for the PX4 SITL launch point."
}
if ($hasHomeLatitude -and (
    [double]::IsNaN($HomeLatitude) -or [double]::IsInfinity($HomeLatitude) -or
    [double]::IsNaN($HomeLongitude) -or [double]::IsInfinity($HomeLongitude) -or
    [Math]::Abs($HomeLatitude) -gt 90 -or [Math]::Abs($HomeLongitude) -gt 180)) {
    throw "HOME must be valid WGS84 latitude and longitude."
}
if ([double]::IsNaN($HomeAltitude) -or [double]::IsInfinity($HomeAltitude)) {
    throw "HomeAltitude must be a finite number of metres above sea level."
}
if ($SimWorld -eq "light" -and ($WithCamera -or $WithSensors -or $WithWeather)) {
    throw "Light mode has no camera, LiDAR, or weather world. Use compact mode for those sensors."
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

function Convert-ProjectShellScriptsToLf([string]$Root) {
    $scriptRoot = Join-Path $Root "scripts"
    $paths = @()
    $paths += Get-ChildItem -LiteralPath $scriptRoot -Filter "*.sh" -File -Recurse -ErrorAction SilentlyContinue
    $paths += Get-ChildItem -LiteralPath $scriptRoot -Filter "*.bash" -File -Recurse -ErrorAction SilentlyContinue

    $wslBin = Join-Path $scriptRoot "wsl-bin"
    if (Test-Path -LiteralPath $wslBin -PathType Container) {
        $paths += Get-ChildItem -LiteralPath $wslBin -File -Recurse -ErrorAction SilentlyContinue
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $normalizedCount = 0
    foreach ($path in ($paths | Sort-Object -Property FullName -Unique)) {
        $content = [System.IO.File]::ReadAllText($path.FullName)
        $normalized = $content -replace "`r`n", "`n"
        $normalized = $normalized -replace "`r", "`n"
        if ($normalized -ne $content) {
            [System.IO.File]::WriteAllText($path.FullName, $normalized, $utf8NoBom)
            $normalizedCount++
        }
    }

    if ($normalizedCount -gt 0) {
        Write-Host "[CHECK] Shell scripts: LF (normalized $normalizedCount file(s))" -ForegroundColor Yellow
    } else {
        Write-Host "[CHECK] Shell scripts: LF" -ForegroundColor Green
    }
}

function Start-WslWindow([string]$Title, [string]$Command) {
    $escapedCommand = $Command.Replace('"', '\"')
    $cmdLine = "title $Title && wsl.exe -d $ubuntuDistro -- bash -lc `"$escapedCommand`""
    Start-Process cmd.exe -ArgumentList @("/k", $cmdLine) -WindowStyle Normal
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

function Assert-DronePackage([string]$Forest3DPath, [string]$World) {
    if ($World -ne "compact") { return }

    $worldFile = Join-Path $Forest3DPath "worlds\forest_monitoring_compact.sdf"
    $fastDemoWorldFile = Join-Path $Forest3DPath "worlds\forest_monitoring_compact_fast_demo.sdf"
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

    if (-not (Test-Path $worldFile)) { throw "Compact world is missing: $worldFile" }
    if (-not (Test-Path $fastDemoWorldFile)) { throw "Compact fast demo world is missing: $fastDemoWorldFile" }
    foreach ($model in $requiredModels) {
        $config = Join-Path $modelRoot "$model\model.config"
        $sdf = Join-Path $modelRoot "$model\model.sdf"
        if (-not (Test-Path $config)) { throw "Packaged Gazebo model config is missing: $config" }
        if (-not (Test-Path $sdf)) { throw "Packaged Gazebo model SDF is missing: $sdf" }
    }
}

$ubuntuDistro = "Ubuntu-24.04"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot "initialize-local-demo-config.ps1")
Initialize-LocalDemoConfig -ProjectRoot $repoRoot
Convert-ProjectShellScriptsToLf $repoRoot
$forest3DPath = if ($SimWorld -eq "light") { $null } else { Resolve-Forest3DPath $repoRoot }
if ($forest3DPath) { Assert-DronePackage $forest3DPath $SimWorld }
$backendEnvFile = Join-Path $repoRoot ".env"
if (-not (Test-Path -LiteralPath $backendEnvFile -PathType Leaf)) {
    throw "Missing shared backend and drone configuration: $backendEnvFile"
}

if (-not $SkipBootstrap) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "bootstrap-drone-stack.ps1") -UbuntuDistro $ubuntuDistro
}

& wsl.exe -d $ubuntuDistro -- bash -lc "command -v bash >/dev/null && test -r /proc/version"
if ($LASTEXITCODE -ne 0) {
    throw "WSL environment check failed for $ubuntuDistro. Run: wsl.exe -d $ubuntuDistro -- bash -lc 'uname -a'"
}
Write-Host "[CHECK] WSL environment: OK" -ForegroundColor Green

$repoRootWsl = ConvertTo-WslPath $repoRoot
$forest3DPathWsl = if ($forest3DPath) { ConvertTo-WslPath $forest3DPath } else { "" }
$scriptRoot = "$repoRootWsl/scripts"
$simArg = $SimWorld
$webOnly = "1"
$baseWslEnv = "PROJECT_PATH='$repoRootWsl' CONTROL_YAW_STEP_DEG='$YawStepDeg' FAST_DEMO_MODE='1' FAST_DEMO_LIDAR_ENABLED='1' GPS_TARGET_REAL_FLIGHT='1' CONTROL_MOVE_SPEED_M_S='220' CONTROL_VERTICAL_SPEED_M_S='80' PX4_SPEED_LIMIT_M_S='220' GPS_ROUTE_EXPECTED_SPEED_M_S='220'"
if ($forest3DPathWsl) { $baseWslEnv += " FOREST3D_PATH='$forest3DPathWsl'" }
if ($hasHomeLatitude -or (-not $PSBoundParameters.ContainsKey("HomeLatitude") -and -not $PSBoundParameters.ContainsKey("HomeLongitude"))) {
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    $baseWslEnv += " PX4_HOME_LAT='$($HomeLatitude.ToString($culture))' PX4_HOME_LON='$($HomeLongitude.ToString($culture))' PX4_HOME_ALT='$($HomeAltitude.ToString($culture))'"
}
$simCommand = "$baseWslEnv FOREST3D_WEB_ONLY=${webOnly} SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-sim-pane.sh ${simArg}"
$gazeboGuiConfig = if ($forest3DPathWsl) { "$forest3DPathWsl/gui/forest_monitoring_gui.config" } else { "" }
$gazeboGuiCommand = "$baseWslEnv FOREST3D_WEB_ONLY=0 FOREST3D_GZ_GUI_CONFIG='$gazeboGuiConfig' SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-gazebo-gui.sh ${simArg}"

wsl.exe -d $ubuntuDistro -- bash -lc "$baseWslEnv exec ${scriptRoot}/wsl-clean-drone-stack.sh" | Out-Null

if (Get-Command wt.exe -ErrorAction SilentlyContinue) {
    $wtArgs = @(
        "new-tab", "--title", "RIGHT - Gazebo + PX4",
        "wsl.exe", "-d", $ubuntuDistro, "--", "bash", "-lc", $simCommand,
        ";", "split-pane", "--horizontal", "--size", "0.66", "--title", "LEFT - Flight Control",
        "wsl.exe", "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv FOREST3D_WEB_ONLY=${webOnly} SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-control.sh"
    )
    if ($WithTelemetry) {
        $wtArgs += @(
            ";", "split-pane", "--vertical", "--size", "0.50", "--title", "MIDDLE - Telemetry BE",
            "wsl.exe", "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv exec ${scriptRoot}/wsl-telemetry.sh"
        )
    }
    if ($ShowGazeboGui) {
        $wtArgs += @(
            ";", "new-tab", "--title", "GAZEBO - GUI",
            "wsl.exe", "-d", $ubuntuDistro, "--", "bash", "-lc", $gazeboGuiCommand
        )
    }
    if ($WithCamera) {
        $wtArgs += @(
            ";", "new-tab", "--title", "CAMERA - Downward View",
            "wsl.exe", "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-camera-view.sh"
        )
    }
    if ($WithSensors) {
        $wtArgs += @(
            ";", "new-tab", "--title", "SENSOR - LiDAR 2D + 3D",
            "wsl.exe", "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-sensor-monitor.sh"
        )
    }
    & wt.exe @wtArgs
    if ($WithWeather) {
        Start-WslWindow -Title "WEATHER - Controls" -Command "$baseWslEnv SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-weather-control.sh"
    }
    exit 0
}

Start-Process wsl.exe -ArgumentList "-d", $ubuntuDistro, "--", "bash", "-lc", $simCommand
Start-Sleep -Seconds 2
Start-Process wsl.exe -ArgumentList "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv FOREST3D_WEB_ONLY=${webOnly} SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-control.sh"
if ($ShowGazeboGui) {
    Start-WslWindow -Title "GAZEBO - GUI" -Command $gazeboGuiCommand
}
if ($WithTelemetry) {
    Start-Process wsl.exe -ArgumentList "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv exec ${scriptRoot}/wsl-telemetry.sh"
}
if ($WithCamera) {
    Start-Process wsl.exe -ArgumentList "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-camera-view.sh"
}
if ($WithSensors) {
    Start-Process wsl.exe -ArgumentList "-d", $ubuntuDistro, "--", "bash", "-lc", "$baseWslEnv SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-sensor-monitor.sh"
}
if ($WithWeather) {
    Start-WslWindow -Title "WEATHER - Controls" -Command "$baseWslEnv SIM_WORLD=${simArg} exec ${scriptRoot}/wsl-weather-control.sh"
}
