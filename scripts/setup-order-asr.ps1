$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $repo '.ai-runtime'
$model = Join-Path $runtime 'models/Qwen3-ASR-0.6B'
$python = Join-Path $runtime 'asr-env/Scripts/python.exe'
$uv = Get-Command uv -ErrorAction SilentlyContinue
if (-not $uv) { throw 'Install uv from https://docs.astral.sh/uv/getting-started/installation/, then run this setup again.' }
if (-not (Test-Path -LiteralPath $python)) {
    & $uv.Source venv --python 3.11 (Join-Path $runtime 'asr-env')
    if ($LASTEXITCODE) { throw 'Could not create isolated ASR environment.' }
}
& $python -c "import importlib.util,sys; sys.exit(0 if importlib.util.find_spec('torch') and importlib.util.find_spec('qwen_asr') else 1)"
if ($LASTEXITCODE) {
    $gpu = Get-Command nvidia-smi -ErrorAction SilentlyContinue
    $index = if ($gpu) { 'https://download.pytorch.org/whl/cu128' } else { 'https://download.pytorch.org/whl/cpu' }
    & $uv.Source pip install --python $python 'torch==2.10.0' --index-url $index
    if ($LASTEXITCODE) { throw 'PyTorch installation failed.' }
    & $uv.Source pip install --python $python 'qwen-asr==0.0.6'
    if ($LASTEXITCODE) { throw 'Qwen3-ASR installation failed.' }
}
New-Item -ItemType Directory -Path $model -Force | Out-Null
$weights = Join-Path $model 'model.safetensors'
$sha = '79d6cbd4c98c7bbffe9db2edac07f56cd6637d0d5944b27f6c2b8353840323ea'
$valid = (Test-Path -LiteralPath $weights) -and ((Get-FileHash -LiteralPath $weights -Algorithm SHA256).Hash.ToLower() -eq $sha)
if ((Test-Path -LiteralPath $weights) -and -not $valid -and (Get-Item -LiteralPath $weights).Length -ge 1876091704) {
    Move-Item -LiteralPath $weights -Destination ($weights+'.invalid-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))
}
foreach ($name in @('config.json','generation_config.json','chat_template.json','preprocessor_config.json','tokenizer_config.json','vocab.json','merges.txt','model.safetensors')) {
    $target = Join-Path $model $name
    if (($name -eq 'model.safetensors' -and $valid) -or ($name -ne 'model.safetensors' -and (Test-Path -LiteralPath $target))) { continue }
    Write-Host "Downloading Qwen3-ASR: $name"
    & curl.exe --fail --location --retry 3 --retry-all-errors --continue-at - --output $target ('https://huggingface.co/Qwen/Qwen3-ASR-0.6B/resolve/main/'+$name)
    if ($LASTEXITCODE) { throw "Download failed: $name" }
}
if ((Get-FileHash -LiteralPath $weights -Algorithm SHA256).Hash.ToLower() -ne $sha) { throw 'ASR checkpoint checksum mismatch.' }
$config = Join-Path $repo '.env'
$lines = if (Test-Path -LiteralPath $config) { @(Get-Content -LiteralPath $config) } else { @() }
foreach ($entry in @{ORDER_ASR_MODEL_PATH=$model;ORDER_ASR_PYTHON=$python}.GetEnumerator()) {
    $lines = @($lines | Where-Object { $_ -notmatch ('^'+$entry.Key+'=') })
    $lines += ($entry.Key+'='+$entry.Value)
}
[IO.File]::WriteAllLines($config,$lines,[Text.UTF8Encoding]::new($false))
& $python -c "import torch; from qwen_asr import Qwen3ASRModel; print('ASR imports verified. CUDA:',torch.cuda.is_available())"
if ($LASTEXITCODE) { throw 'ASR dependencies are present but cannot load. Check the Python environment.' }
Write-Host 'Local Qwen3-ASR is ready. Existing verified downloads were reused. Continuing AI startup.'
