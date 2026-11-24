$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$config = Join-Path $repo '.env'
$allowedKeys = @(
    'DESCRIPTION_AI_TOKEN', 'TTS_PROVIDER', 'TTS_VOICE', 'TTS_DEFAULT_VOICE',
    'TTS_MODEL_ID', 'TTS_MODEL_REVISION', 'TTS_MODEL_PATH', 'TTS_ZERO_CFG_SCALE',
    'TTS_ZERO_TEXT_TEMPERATURE', 'TTS_ZERO_AUDIO_TEMPERATURE',
    'TTS_ZERO_AUDIO_TOPK', 'TTS_ZERO_AUDIO_TOPP',
    'TTS_ZERO_AUDIO_REPETITION_PENALTY'
)
$settings = @{}
if (Test-Path -LiteralPath $config) {
    foreach ($line in Get-Content -LiteralPath $config) {
        if ($line -match '^([A-Z0-9_]+)=(.*)$' -and $allowedKeys -contains $Matches[1]) {
            $settings[$Matches[1]] = $Matches[2].Trim().Trim('"')
        }
    }
}

if ($settings.ContainsKey('DESCRIPTION_AI_TOKEN')) {
    $env:DESCRIPTION_AI_TOKEN = $settings['DESCRIPTION_AI_TOKEN']
}
$provider = if ($settings.ContainsKey('TTS_PROVIDER')) { $settings['TTS_PROVIDER'].ToLowerInvariant() } else { 'zerotts' }
if ($provider -notin @('zerotts', 'vieneu')) {
    throw 'TTS_PROVIDER must be zerotts or vieneu.'
}
$voice = if ($settings.ContainsKey('TTS_VOICE')) { $settings['TTS_VOICE'] }
    elseif ($provider -eq 'zerotts') { 'maichi' }
    elseif ($settings.ContainsKey('TTS_DEFAULT_VOICE')) { $settings['TTS_DEFAULT_VOICE'] }
    else { 'Mai Anh' }

$env:TTS_PROVIDER = $provider
$env:TTS_VOICE = $voice
$env:TTS_DEFAULT_VOICE = if ($settings.ContainsKey('TTS_DEFAULT_VOICE')) { $settings['TTS_DEFAULT_VOICE'] } else { 'Mai Anh' }
$env:HF_HUB_DISABLE_XET = '1'
foreach ($key in $settings.Keys) {
    if ($key -like 'TTS_ZERO_*' -or $key -in @('TTS_MODEL_ID', 'TTS_MODEL_REVISION', 'TTS_MODEL_PATH')) {
        [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
}

$health = $null
try { $health = Invoke-RestMethod 'http://localhost:8001/health' -TimeoutSec 3 } catch { }
if ($health) {
    if ($health.status -eq 'ready' -and $health.provider -eq $provider -and $health.voice -eq $voice) {
        Write-Host "Local TTS worker is ready: $provider / $voice."
        return
    }
    throw "Port 8001 already has a TTS worker ($($health.provider) / $($health.voice)). Stop that worker in the IDE, then run this script again for $provider / $voice. No process was stopped."
}
if (Get-NetTCPConnection -LocalPort 8001 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8001 is already in use by a service that did not answer the TTS health check. Close that service, then try again.'
}

$python = Join-Path $repo '.ai-runtime/vieneu-py311/Scripts/python.exe'
if (!(Test-Path -LiteralPath $python)) {
    throw 'The local Python 3.11 TTS environment is missing. Run scripts/install-zerotts-tts.ps1 after restoring it.'
}
if ($provider -eq 'zerotts') {
    & $python -c 'import zerotts'
    if ($LASTEXITCODE -ne 0) {
        throw 'ZeroTTS is not installed in the local TTS environment. Run scripts/install-zerotts-tts.ps1.'
    }
}

$workerDir = Join-Path $repo 'Python/voice-order'
Start-Process -FilePath $python `
    -ArgumentList @('-m', 'uvicorn', 'vieneu_tts_worker:app', '--host', 'localhost', '--port', '8001') `
    -WorkingDirectory $workerDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $repo '.ai-runtime/vieneu-tts-worker.log') `
    -RedirectStandardError (Join-Path $repo '.ai-runtime/vieneu-tts-worker-error.log') | Out-Null

for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Seconds 1
    try {
        $health = Invoke-RestMethod 'http://localhost:8001/health' -TimeoutSec 2
        if ($health.status -eq 'ready' -and $health.provider -eq $provider -and $health.voice -eq $voice) {
            Write-Host "Local TTS worker is ready: $provider / $voice. The model loads on its first speech request."
            return
        }
    } catch { }
}
throw 'The TTS worker did not start in time. Check .ai-runtime/vieneu-tts-worker-error.log.'
