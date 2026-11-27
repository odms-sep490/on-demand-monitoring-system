$ErrorActionPreference = 'Stop'
Write-Host '[Launcher v3] AI Services - port conflict guard, readiness checks, and de-duplicated status logs.'
& (Join-Path $PSScriptRoot 'setup-ai-local.ps1')

function Test-PortListening {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        return ($null -ne $conn -and @($conn).Count -gt 0)
    } catch {
        return $false
    }
}

$services = @(
    @{ Name = 'start-change-ai.ps1'; Port = 8000; HealthUrl = 'http://127.0.0.1:8000/quality/ready' },
    @{ Name = 'start-description-ai.ps1'; Port = 8002; HealthUrl = 'http://127.0.0.1:8002/conversation/health' }
)

$jobs = @()
$lastState = @{}

try {
    foreach ($svc in $services) {
        if (Test-PortListening -Port $svc.Port) {
            Write-Host "[Guard] Port $($svc.Port) already in use - skipping duplicate startup for $($svc.Name)."
            continue
        }
        $scriptPath = Join-Path $PSScriptRoot $svc.Name
        $jobs += Start-Job -Name $svc.Name -ArgumentList $scriptPath -ScriptBlock {
            param($path)
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $path 2>&1 | ForEach-Object { $_.ToString() }
            if ($LASTEXITCODE -ne 0) { throw "AI service exited with code $LASTEXITCODE" }
        }
    }
    Write-Host 'Monitoring AI services:'
    Write-Host '  Image comparison & quality: http://127.0.0.1:8000/quality/ready'
    Write-Host '  Conversation, description, voice & report: http://127.0.0.1:8002/conversation/health'
    Write-Host '  Ollama local runtime: http://127.0.0.1:11434/api/tags'
    Write-Host '  Checklist image assessment: served by the Java backend (see model setup above)'

    $lastProgress = (Get-Date).AddSeconds(-20)
    while ($true) {
        foreach ($job in $jobs) {
            Receive-Job -Job $job -ErrorAction Continue
            if ($job.State -eq 'Failed') { throw "AI service failed: $($job.Name)" }
        }
        if ($jobs.Count -gt 0 -and @($jobs | Where-Object { $_.State -eq 'Running' }).Count -eq 0) { break }
        if (((Get-Date) - $lastProgress).TotalSeconds -ge 15) {
            foreach ($url in @('http://127.0.0.1:8000/quality/ready', 'http://127.0.0.1:8002/description/health', 'http://127.0.0.1:8002/conversation/health', 'http://127.0.0.1:11434/api/tags')) {
                try {
                    $health = Invoke-RestMethod $url -TimeoutSec 5
                    $status = if ($health.models) {
                        "MODEL_LOADED ($(@($health.models).Count) models)"
                    } elseif ($health.status) {
                        "READINESS: $($health.status)"
                    } elseif ($health.protocolVersion -eq 1 -and $health.model) {
                        "READINESS: READY ($($health.model))"
                    } else {
                        'DEGRADED (unexpected payload)'
                    }
                } catch {
                    $status = "OFFLINE ($($_.Exception.Message))"
                }
                if ($lastState[$url] -ne $status) {
                    Write-Host "[Status] $url -> $status"
                    $lastState[$url] = $status
                }
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
