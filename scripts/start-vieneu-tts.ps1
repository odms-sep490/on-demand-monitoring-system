$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
foreach ($line in Get-Content (Join-Path $repo '.env')) {
    if ($line -match '^DESCRIPTION_AI_TOKEN=(.*)$') { $env:DESCRIPTION_AI_TOKEN = $Matches[1].Trim().Trim('"') }
}
try {
    $health = Invoke-RestMethod 'http://localhost:8001/health' -TimeoutSec 3
    if ($health.status -eq 'ready') { return }
} catch { }
$python = Join-Path $repo '.ai-runtime/vieneu-py311/Scripts/python.exe'
if (!(Test-Path $python)) { throw 'Missing local Vietnamese voice environment.' }
Start-Process -FilePath $python -ArgumentList @('-m','uvicorn','vieneu_tts_worker:app','--host','localhost','--port','8001') -WorkingDirectory (Join-Path $repo 'Python/voice-order') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $repo '.ai-runtime/vieneu-tts-worker.log') -RedirectStandardError (Join-Path $repo '.ai-runtime/vieneu-tts-worker-error.log')
