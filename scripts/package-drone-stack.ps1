param(
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Copy-PackageDirectory([string]$Source, [string]$Destination) {
    $excludeDirs = @(
        ".git",
        ".idea",
        "node_modules",
        "target",
        "dist",
        ".vite",
        ".venv",
        "venv",
        ".pytest_cache",
        "__pycache__",
        (Join-Path $Source "untitled1"),
        (Join-Path $Source "drone\Blender"),
        (Join-Path $Source "drone\Forest3D")
    )

    $droneRoot = Join-Path $Source "drone"
    if (Test-Path $droneRoot) {
        $generatedDroneDirs = Get-ChildItem -LiteralPath $droneRoot -Directory -Force |
            Where-Object { $_.Name -like "C*UsersACER" }
        foreach ($dir in $generatedDroneDirs) {
            $excludeDirs += $dir.FullName
        }
    }

    robocopy $Source $Destination /E /XD $excludeDirs /XF "*.log" ".env" ".env.local" "tsconfig.tsbuildinfo" | Out-Host
    if ($LASTEXITCODE -gt 7) {
        throw "Copy failed from $Source to $Destination."
    }
}

function Assert-PackageAssets([string]$Root) {
    $forest3DPath = Join-Path $Root "Forest3D"
    $worldFile = Join-Path $forest3DPath "worlds\forest_monitoring_compact.sdf"
    $fastDemoWorldFile = Join-Path $forest3DPath "worlds\forest_monitoring_compact_fast_demo.sdf"
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

    if (-not (Test-Path $worldFile)) {
        throw "Cannot package drone stack because compact world is missing: $worldFile"
    }
    if (-not (Test-Path $fastDemoWorldFile)) {
        throw "Cannot package drone stack because compact fast demo world is missing: $fastDemoWorldFile"
    }

    foreach ($model in $requiredModels) {
        $modelDir = Join-Path $forest3DPath "models\$model"
        $config = Join-Path $modelDir "model.config"
        $sdf = Join-Path $modelDir "model.sdf"
        if (-not (Test-Path $config)) {
            throw "Cannot package drone stack because Gazebo model config is missing: $config"
        }
        if (-not (Test-Path $sdf)) {
            throw "Cannot package drone stack because Gazebo model SDF is missing: $sdf"
        }
    }
}

$systemRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$workspaceRoot = (Resolve-Path (Join-Path $systemRoot "..")).Path
$webRoot = Join-Path $workspaceRoot "ondemand-monitoring-web"

if (-not (Test-Path $webRoot)) {
    throw "Frontend folder not found beside this package: $webRoot"
}

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $workspaceRoot "dist"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stageRoot = Join-Path $env:TEMP "ondemand-drone-stack-$stamp"
$zipPath = Join-Path $OutputDirectory "ondemand-drone-stack-$stamp.zip"

Write-Step "Preparing package folder"
Assert-PackageAssets $systemRoot
New-Item -ItemType Directory -Path $stageRoot -Force | Out-Null
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

try {
    Copy-PackageDirectory $systemRoot (Join-Path $stageRoot "on-demand-monitoring-system")
    Copy-PackageDirectory $webRoot (Join-Path $stageRoot "ondemand-monitoring-web")

    $runConfigSource = Join-Path $systemRoot ".idea\runConfigurations\Start_Drone_Stack.xml"
    if (Test-Path $runConfigSource) {
        $runConfigDestination = Join-Path $stageRoot "on-demand-monitoring-system\.idea\runConfigurations"
        New-Item -ItemType Directory -Path $runConfigDestination -Force | Out-Null
        Copy-Item -LiteralPath $runConfigSource -Destination $runConfigDestination -Force
    }

    Write-Step "Creating zip package"
    if (Test-Path $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    Compress-Archive -Path (Join-Path $stageRoot "*") -DestinationPath $zipPath -Force

    Write-Host ""
    Write-Host "Package is ready:" -ForegroundColor Green
    Write-Host $zipPath
    Write-Host ""
    Write-Host "Receiver: unzip, open on-demand-monitoring-system, then run .\scripts\RUN_DRONE_STACK.cmd."
    Write-Host "Simulator only: run .\scripts\RUN_DRONE_SIM_ONLY.cmd from the repository folder."
} finally {
    if (Test-Path $stageRoot) {
        Remove-Item -LiteralPath $stageRoot -Recurse -Force
    }
}
