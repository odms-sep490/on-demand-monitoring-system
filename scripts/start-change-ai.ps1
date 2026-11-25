param([string]$UbuntuDistro = 'Ubuntu-24.04')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw 'WSL is required. Enable Windows Subsystem for Linux, restart Windows, then run Start AI Worker again.'
}
$distros = @(& wsl.exe --list --quiet 2>$null) | ForEach-Object { ($_ -replace "\x00", '').Trim() }
if ($distros -notcontains $UbuntuDistro) {
    Write-Host "Installing $UbuntuDistro for AI Worker. Windows may request administrator approval or a restart."
    & wsl.exe --install -d $UbuntuDistro
    if ($LASTEXITCODE -ne 0) { throw "Unable to install $UbuntuDistro. Complete the Windows WSL installation and run again." }
    $distros = @(& wsl.exe --list --quiet 2>$null) | ForEach-Object { ($_ -replace "\x00", '').Trim() }
    if ($distros -notcontains $UbuntuDistro) { throw 'Restart Windows and finish Ubuntu setup, then run Start AI Worker again.' }
}
$envPath = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envPath)) { New-Item -ItemType File -Path $envPath | Out-Null }
$envText = [IO.File]::ReadAllText($envPath)
foreach ($key in @('OPENCD_TOKEN', 'IMAGE_QUALITY_INTERNAL_TOKEN')) {
    if ($envText -notmatch ('(?m)^' + $key + '=\S+')) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $value = [Convert]::ToBase64String($bytes)
        $envText = [regex]::Replace($envText, '(?m)^' + $key + '=.*\r?\n?', '')
        $envText += [Environment]::NewLine + $key + '=' + $value + [Environment]::NewLine
    }
}
[IO.File]::WriteAllText($envPath, $envText, (New-Object Text.UTF8Encoding($false)))
try {
    $ready = Invoke-RestMethod 'http://127.0.0.1:8000/ready' -TimeoutSec 3
    $qualityReady = Invoke-RestMethod 'http://127.0.0.1:8000/quality/ready' -TimeoutSec 3
    if ($ready.status -eq 'READY' -and $qualityReady.status -eq 'READY') {
        Write-Host 'Image comparison and image quality workers are READY at http://127.0.0.1:8000. Reusing the running worker.' -ForegroundColor Green
        exit 0
    }
} catch { }
$wslRoot = (& wsl.exe -d $UbuntuDistro -- wslpath -a ($root.Replace('\', '/'))).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot locate project in WSL.' }
& wsl.exe -d $UbuntuDistro -- bash "$wslRoot/scripts/wsl-change-ai.sh" $wslRoot
exit $LASTEXITCODE
