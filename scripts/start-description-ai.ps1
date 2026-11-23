$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$repo = Split-Path $PSScriptRoot -Parent
$config = Join-Path $repo '.env'
$desiredLine = if (Test-Path -LiteralPath $config) { Get-Content -LiteralPath $config | Where-Object { $_ -match '^ORDER_AGENT_MODEL=' } | Select-Object -Last 1 }
$desiredModel = if ($desiredLine) { ($desiredLine -split '=',2)[1].Trim().Trim('"') } else { 'odm-order-qwen:3b' }
try {
    $ready = Invoke-RestMethod 'http://localhost:8002/description/health' -TimeoutSec 3
    $conversation = Invoke-RestMethod 'http://localhost:8002/conversation/health' -TimeoutSec 3
    if ($ready.status -eq 'ready' -and $conversation.protocolVersion -eq 1 -and $conversation.model -eq $desiredModel) { Write-Host ('Description and conversation worker is already running at http://localhost:8002. ASR configured: '+$conversation.asrConfigured); exit 0 }
} catch { }
$python = Join-Path $repo 'Python/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Python/.venv is missing. Restore the project Python environment first.' }
$config = Join-Path $repo '.env'
if (-not (Select-String -LiteralPath $config -Pattern '^DESCRIPTION_AI_TOKEN=.+$' -Quiet)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    Add-Content -LiteralPath $config -Value ("`nDESCRIPTION_AI_TOKEN=" + [Convert]::ToBase64String($bytes))
}
if (-not (Select-String -LiteralPath $config -Pattern '^DESCRIPTION_LLM_MODEL=' -Quiet)) {
    Add-Content -LiteralPath $config -Value 'DESCRIPTION_LLM_MODEL=qwen3:1.7b'
}
if (-not (Select-String -LiteralPath $config -Pattern '^ORDER_AGENT_MODEL=' -Quiet)) {
    Add-Content -LiteralPath $config -Value 'ORDER_AGENT_MODEL=odm-order-qwen:3b'
}
Set-Location (Join-Path $repo 'Python/voice-order')
$modelLine = Get-Content -LiteralPath $config | Where-Object { $_ -match '^DESCRIPTION_LLM_MODEL=' } | Select-Object -Last 1
$model = ($modelLine -split '=',2)[1].Trim().Trim('"')
try { $models = Invoke-RestMethod 'http://localhost:11434/api/tags' -TimeoutSec 5 }
catch { throw 'Ollama is not running. Start Ollama, then run Start Description AI again.' }
$agentLine = Get-Content -LiteralPath $config | Where-Object { $_ -match '^ORDER_AGENT_MODEL=' } | Select-Object -Last 1
$agentModel = if ($agentLine) { ($agentLine -split '=',2)[1].Trim().Trim('"') } else { $model }
foreach ($neededModel in @($model, $agentModel) | Select-Object -Unique) {
    if (-not ($models.models | Where-Object { $_.name -eq $neededModel })) {
        if ($neededModel -eq 'odm-order-qwen:3b') {
            if (-not ($models.models | Where-Object { $_.name -eq 'qwen2.5:3b' })) {
                Write-Host 'Downloading Qwen2.5:3b once for the order consultant.'
                Invoke-RestMethod 'http://localhost:11434/api/pull' -Method Post -ContentType 'application/json' -Body '{"model":"qwen2.5:3b","stream":false}' -TimeoutSec 3600 | Out-Null
            }
            Invoke-RestMethod 'http://localhost:11434/api/create' -Method Post -ContentType 'application/json' -Body '{"model":"odm-order-qwen:3b","from":"qwen2.5:3b","parameters":{"num_ctx":8192,"num_predict":1000},"stream":false}' -TimeoutSec 60 | Out-Null
            continue
        }
        Write-Host "Downloading $neededModel. The first run can take several minutes."
        Invoke-RestMethod 'http://localhost:11434/api/pull' -Method Post -ContentType 'application/json' -Body (@{model=$neededModel;stream=$false} | ConvertTo-Json) -TimeoutSec 3600 | Out-Null
    }
}
Write-Host "Starting Description AI ($model) and Order Consultant ($agentModel) at http://localhost:8002."
& $python -m uvicorn description_agent:app --host 127.0.0.1 --port 8002 --env-file $config
exit $LASTEXITCODE
