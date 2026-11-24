$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'setup-ai-local.ps1')
$jobs = @()
try {
    foreach ($scriptName in @('start-change-ai.ps1', 'start-description-ai.ps1')) {
        $scriptPath = Join-Path $PSScriptRoot $scriptName
        $jobs += Start-Job -Name $scriptName -ArgumentList $scriptPath -ScriptBlock {
            param($path)
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $path
            if ($LASTEXITCODE -ne 0) { throw "AI service exited with code $LASTEXITCODE" }
        }
    }
    Write-Host 'Starting ALL AI services:'
    Write-Host '  Image comparison: http://localhost:8000/ready'
    Write-Host '  Image quality assessment: http://localhost:8000/quality/ready (same image worker)'
    Write-Host '  Conversation, description and voice: http://localhost:8002/conversation/health'
    $lastProgress = Get-Date
    while ($true) {
        foreach ($job in $jobs) {
            # Native tools (uvicorn, pip, WSL) also write normal logs to stderr.
            # Report those logs without treating every stderr line as a launcher failure.
            Receive-Job -Job $job -ErrorAction Continue
            if ($job.State -eq 'Failed') { throw "AI service failed: $($job.Name)" }
        }
        if (@($jobs | Where-Object { $_.State -eq 'Running' }).Count -eq 0) { break }
        if (((Get-Date) - $lastProgress).TotalSeconds -ge 15) {
            foreach ($url in @('http://localhost:8000/quality/ready', 'http://localhost:8002/conversation/health')) {
                try {
                    $health = Invoke-RestMethod $url -TimeoutSec 2
                    Write-Host "[Status] $url : $($health.status)"
                } catch { Write-Host "[Status] $url : starting/installing; waiting for worker output..." }
            }
            $lastProgress = Get-Date
        }
        Start-Sleep -Milliseconds 500
    }
} finally {
    foreach ($job in $jobs) {
        Stop-Job -Job $job -ErrorAction SilentlyContinue
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
}
