$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
function Find-LocalTool($name, $fallback) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    return $null
}
$uv = Find-LocalTool 'uv' (Join-Path $env:USERPROFILE '.local/bin/uv.exe')
if (-not $uv) {
    Write-Host '[Setup] Downloading uv to install isolated Python environments...'
    $installer = Join-Path $env:TEMP 'odm-install-uv.ps1'
    Invoke-WebRequest 'https://astral.sh/uv/install.ps1' -OutFile $installer -UseBasicParsing
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer
    if ($LASTEXITCODE) { throw 'uv installation failed.' }
    $uv = Find-LocalTool 'uv' (Join-Path $env:USERPROFILE '.local/bin/uv.exe')
    if (-not $uv) { throw 'Cannot find uv after installation.' }
}
$env:PATH = (Split-Path -Parent $uv) + ';' + $env:PATH
foreach ($entry in @(
    @{ Folder = 'Python/.venv'; Requirements = 'requirements-description.txt'; Probe = 'import fastapi, uvicorn, qwen_agent, dotenv' },
    @{ Folder = '.ai-runtime/vieneu-py311'; Requirements = 'requirements-zerotts.txt'; Probe = 'import zerotts, uvicorn' }
)) {
    $folder = Join-Path $repo $entry.Folder
    $python = Join-Path $folder 'Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        Write-Host "[Setup] Creating Python 3.11 environment: $($entry.Folder)"
        & $uv venv --python 3.11 $folder
        if ($LASTEXITCODE) { throw 'Python environment creation failed.' }
    }
    $ErrorActionPreference = 'Continue'
    & $python -c $entry.Probe 2>$null
    $missingDependencies = $LASTEXITCODE -ne 0
    $ErrorActionPreference = 'Stop'
    if ($missingDependencies) {
        Write-Host "[Setup] Installing dependencies: $($entry.Requirements)"
        & $uv pip install --python $python -r (Join-Path $repo "Python/voice-order/$($entry.Requirements)")
        if ($LASTEXITCODE) { throw 'AI dependencies installation failed.' }
        if ($entry.Requirements -eq 'requirements-zerotts.txt') {
            & $uv pip install --python $python fastapi uvicorn
            if ($LASTEXITCODE) { throw 'TTS web server dependencies installation failed.' }
        }
    }
}
$config = Join-Path $repo '.env'
if (-not (Test-Path -LiteralPath $config)) { New-Item -ItemType File -Path $config | Out-Null }
$models = & (Join-Path $PSScriptRoot 'ensure-ollama.ps1') -InstallIfMissing
try {
    & (Join-Path $PSScriptRoot 'prepare-checklist-vision.ps1') -ConfigPath $config -ModelCatalog $models
} catch {
    Write-Warning "[Checklist Vision] $($_.Exception.Message) Run scripts/prepare-checklist-vision.ps1 to retry."
}
Write-Host '[Setup] Checking local ZeroTTS files; downloading only missing files...'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'install-zerotts-tts.ps1')
if ($LASTEXITCODE) { throw 'TTS setup failed.' }
Write-Host '[Setup] Checking local speech recognition; downloading only missing files...'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'setup-order-asr.ps1')
if ($LASTEXITCODE) { throw 'Speech recognition setup failed.' }
