$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$repo = Split-Path $PSScriptRoot -Parent
$python = Join-Path $repo 'Python\.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) {
    throw 'Python environment is missing: Python/.venv. Restore the existing AI environment before running.'
}
$listener = Get-NetTCPConnection -LocalPort 8002 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:8002/quote-assistant/health' -TimeoutSec 5
        if ($health.status -eq 'ready') {
            Write-Host 'Quote AI is already running at http://127.0.0.1:8002.'
            return
        }
    } catch { }
    throw 'Port 8002 is occupied, but Quote AI is not ready. Check the existing Quote AI window and Ollama.'
}
Set-Location (Join-Path $repo 'Python\voice-order')
Write-Host 'Starting Quote AI at http://127.0.0.1:8002. Keep this window open. Stop with Ctrl+C.'
& $python -m uvicorn app:app --host 127.0.0.1 --port 8002
