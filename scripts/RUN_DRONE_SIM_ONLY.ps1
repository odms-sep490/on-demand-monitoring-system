param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $scriptDir "start-drone-stack.ps1"

if (-not (Test-Path $target)) {
    throw "Cannot find start-drone-stack.ps1 next to this launcher."
}

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $target -SimWorld compact -SkipBootstrap @Arguments
exit $LASTEXITCODE
