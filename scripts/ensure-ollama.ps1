$ErrorActionPreference = 'Stop'
$started = $false
$lastFailure = ''
for ($attempt = 1; $attempt -le 6; $attempt++) {
    try {
        $models = Invoke-RestMethod 'http://localhost:11434/api/tags' -TimeoutSec 15
        Write-Host '[Ollama] Ready at http://localhost:11434.'
        return $models
    } catch {
        $lastFailure = $_.Exception.Message
        if (-not $started) {
            $command = Get-Command ollama -ErrorAction SilentlyContinue
            $executable = if ($command) { $command.Source } else {
                Join-Path $env:LOCALAPPDATA 'Programs/Ollama/ollama.exe'
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
