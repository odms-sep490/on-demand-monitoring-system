param([switch]$InstallIfMissing)

$ErrorActionPreference = 'Stop'
$ollamaBaseUrl = 'http://127.0.0.1:11434'
$started = $false
$lastFailure = ''
for ($attempt = 1; $attempt -le 6; $attempt++) {
    try {
        $models = Invoke-RestMethod "$ollamaBaseUrl/api/tags" -TimeoutSec 15
        $state = if ($started) { 'Ready' } else { 'Reusing server' }
        Write-Host "[Ollama] $state at $ollamaBaseUrl."
        return $models
    } catch {
        $lastFailure = $_.Exception.Message
        if (-not $started) {
            $command = Get-Command ollama -ErrorAction SilentlyContinue
            $executable = if ($command) { $command.Source } else {
                Join-Path $env:LOCALAPPDATA 'Programs/Ollama/ollama.exe'
            }
            if (-not (Test-Path -LiteralPath $executable) -and $InstallIfMissing) {
                Write-Host '[Ollama] Installing local server...'
                & winget.exe install --id Ollama.Ollama --exact --accept-source-agreements --accept-package-agreements | Out-Host
                if ($LASTEXITCODE) { throw 'Ollama installation failed. Install it and retry.' }
                $command = Get-Command ollama -ErrorAction SilentlyContinue
                if ($command) { $executable = $command.Source }
            }
            if (-not (Test-Path -LiteralPath $executable)) {
                throw 'Ollama is missing. Run Start AI Services to install it first.'
            }
            $running = Get-Process ollama -ErrorAction SilentlyContinue
            if (-not $running) {
                Write-Host '[Ollama] Starting local server...'
                Start-Process -FilePath $executable -ArgumentList 'serve' -WindowStyle Hidden | Out-Null
            }
            $started = $true
        }
        Write-Host "[Ollama] Waiting for server ($attempt/6)..."
        Start-Sleep -Seconds 2
    }
}
throw "Ollama did not respond after retries: $lastFailure"
