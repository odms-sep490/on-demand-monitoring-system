param([string]$UbuntuDistro = 'Ubuntu-24.04')
$ErrorActionPreference = 'Stop'
try {
    $ready = Invoke-RestMethod 'http://127.0.0.1:8000/ready' -TimeoutSec 3
    if ($ready.status -eq 'READY') {
        Write-Host 'AI worker is already READY at http://127.0.0.1:8000. Reusing the running worker.' -ForegroundColor Green
        exit 0
    }
} catch { }
$root = Split-Path -Parent $PSScriptRoot
$wslRoot = (& wsl.exe -d $UbuntuDistro -- wslpath -a ($root.Replace('\', '/'))).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot locate project in WSL.' }
& wsl.exe -d $UbuntuDistro -- bash "$wslRoot/scripts/wsl-change-ai.sh" $wslRoot
exit $LASTEXITCODE
