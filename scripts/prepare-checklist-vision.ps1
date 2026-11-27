param(
    [string]$ConfigPath = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'),
    [object]$ModelCatalog
)

$ErrorActionPreference = 'Stop'
$ollamaBaseUrl = 'http://127.0.0.1:11434'
$settings = @{}
if (Test-Path -LiteralPath $ConfigPath) {
    foreach ($line in Get-Content -LiteralPath $ConfigPath) {
        if ($line -match '^\s*(AI_CHECKLIST_VISION_(ENABLED|PROVIDER|MODEL|BASE_URL))\s*=\s*(.*)$') {
            $settings[$Matches[1]] = $Matches[3].Trim().Trim('"', "'")
        }
    }
}

function Get-VisionSetting([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($null -ne $value) { return $value.Trim() }
    return $settings[$Name]
}

$enabled = Get-VisionSetting 'AI_CHECKLIST_VISION_ENABLED'
if ($enabled -eq 'false') {
    Write-Host '[Checklist Vision] Disabled in configuration.'
    return
}
if ($null -ne $enabled -and $enabled -ne 'true') {
    throw 'Set AI_CHECKLIST_VISION_ENABLED to true or false.'
}

$model = Get-VisionSetting 'AI_CHECKLIST_VISION_MODEL'
$provider = Get-VisionSetting 'AI_CHECKLIST_VISION_PROVIDER'
# Older configurations used a namespaced model ID with the default OpenRouter provider.
if ($provider -eq 'OPENROUTER' -or ($null -eq $provider -and $model -like '*/*')) {
    Write-Host '[Checklist Vision] OpenRouter configured; skipping local model setup.'
    return
}
if ($null -ne $provider -and $provider -ne 'OLLAMA') {
    throw 'Set AI_CHECKLIST_VISION_PROVIDER to OLLAMA or OPENROUTER.'
}
if ($null -eq $model) { $model = 'qwen3-vl:2b-instruct' }
if ($model.Length -gt 128 -or $model -cnotmatch '^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+$' -or $model -match '[:-]cloud$') {
    throw 'Set AI_CHECKLIST_VISION_MODEL to a local tag such as qwen3-vl:2b-instruct.'
}

$baseUrl = Get-VisionSetting 'AI_CHECKLIST_VISION_BASE_URL'
if ($baseUrl -and $baseUrl.TrimEnd('/') -ne "$ollamaBaseUrl/v1") {
    throw "To share Ollama with the order assistants, set AI_CHECKLIST_VISION_BASE_URL to $ollamaBaseUrl/v1 or leave it empty."
}
if ($null -eq $ModelCatalog) {
    $ModelCatalog = & (Join-Path $PSScriptRoot 'ensure-ollama.ps1') -InstallIfMissing
}

$body = @{ model = $model } | ConvertTo-Json -Compress
$alreadyInstalled = $model -cin @($ModelCatalog.models.name)
if (-not $alreadyInstalled) {
    Write-Host "[Checklist Vision] Downloading $model. The first run can take several minutes."
    $pullBody = @{ model = $model; stream = $false } | ConvertTo-Json -Compress
    $pull = Invoke-RestMethod "$ollamaBaseUrl/api/pull" -Method Post -ContentType 'application/json' -Body $pullBody -TimeoutSec 3600
    if ($pull.status -ne 'success') { throw "Ollama did not finish downloading $model." }
}
$details = Invoke-RestMethod "$ollamaBaseUrl/api/show" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 15
if ($details.capabilities -notcontains 'vision') { throw "Ollama model $model must support vision." }

$defaults = @()
if ($null -eq $provider) { $defaults += 'AI_CHECKLIST_VISION_PROVIDER=OLLAMA' }
if ($null -eq $enabled) { $defaults += 'AI_CHECKLIST_VISION_ENABLED=true' }
if ($null -eq (Get-VisionSetting 'AI_CHECKLIST_VISION_MODEL')) { $defaults += "AI_CHECKLIST_VISION_MODEL=$model" }
if ($defaults.Count -gt 0) {
    Add-Content -LiteralPath $ConfigPath -Encoding UTF8 -Value (@('') + $defaults)
}

$action = if ($alreadyInstalled) { 'Reused' } else { 'Installed' }
Write-Host "[Checklist Vision] $action $model on shared Ollama ($ollamaBaseUrl). The Java backend serves image assessments."
