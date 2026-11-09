param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $scriptDir "start-ui-camera-stack.ps1"

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $target @Arguments
exit $LASTEXITCODE
