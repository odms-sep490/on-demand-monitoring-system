function Initialize-LocalDemoConfig([string]$ProjectRoot) {
    $envFile = Join-Path $ProjectRoot ".env"
    $exampleFile = Join-Path $ProjectRoot ".env.example"

    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        if (-not (Test-Path -LiteralPath $exampleFile -PathType Leaf)) {
            throw "Local configuration template is missing: $exampleFile"
        }
        Copy-Item -LiteralPath $exampleFile -Destination $envFile
        Write-Host "Created local .env from .env.example. Personal values stay on this machine." -ForegroundColor Yellow
    }

    $lines = @(Get-Content -LiteralPath $envFile -ErrorAction Stop)
    $secretLineFound = $false
    $secretConfigured = $false
    foreach ($line in $lines) {
        if ($line -match '^\s*DRONE_TELEMETRY_SECRET\s*=(.*)$') {
            $secretLineFound = $true
            $secretConfigured = -not [string]::IsNullOrWhiteSpace($Matches[1].Trim().Trim('"').Trim("'"))
            break
        }
    }

    if (-not $secretConfigured) {
        $bytes = New-Object byte[] 32
        $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $generator.GetBytes($bytes)
        } finally {
            $generator.Dispose()
        }
        $secret = ([System.BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()

        if ($secretLineFound) {
            $lines = @($lines | ForEach-Object {
                if ($_ -match '^\s*DRONE_TELEMETRY_SECRET\s*=') { "DRONE_TELEMETRY_SECRET=$secret" } else { $_ }
            })
        } else {
            $lines += "DRONE_TELEMETRY_SECRET=$secret"
        }

        $encoding = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllLines($envFile, $lines, $encoding)
        Write-Host "Generated a private telemetry key in .env." -ForegroundColor Green
    }
}
