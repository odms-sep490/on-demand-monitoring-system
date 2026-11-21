param([string]$PostgresImage = 'ondemandmonitoring-postgres:latest', [string]$Pom, [string[]]$Test)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (!$Pom) { $Pom = Join-Path $repo 'ondemandmonitoring/pom.xml' }
$container = 'odms-workflow-' + [Guid]::NewGuid().ToString('N')
$database = 'odms_contract_verification'
$password = [Guid]::NewGuid().ToString('N')
$tests = @(
    'OrderContractPostgresTest', 'OrderContractControllerTest', 'ContractCancellationServiceTest',
    'ContractSubmissionCleanupServiceTest', 'RefundServiceRequestTest', 'RefundReceiptValidatorTest',
    'MissionCreationHttpTest', 'MissionExecutionActivationServiceTest',
    'InspectionCorrectionPostgresTest', 'InspectionCorrectionControllerTest', 'InspectionResultGateTest',
    'SupplementAutomaticDeliveryTest', 'MissionReworkPlanFinalizeTest', 'ReworkRecoveryTest',
    'DeliveryWorkflowServiceTest', 'ResultRevisionIdempotencyTest', 'ResultRevisionCaseEvidenceTest',
    'ReworkDeliveryEvidencePolicyTest', 'MissionMediaUploadServiceTest', 'MediaUploadWorkflowTest',
    'ManualUploadRecoveryWorkflowTest', 'ManualUploadEscalationPolicyTest'
)
if ($Test) { $tests = $Test }
$settings = @{}
try {
    docker run --detach --name $container --tmpfs /var/lib/postgresql/data --publish 127.0.0.1::5432 --env "POSTGRES_DB=$database" --env POSTGRES_USER=odms_verify --env "POSTGRES_PASSWORD=$password" $PostgresImage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to start the PostgreSQL test container' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        docker exec $container pg_isready -h 127.0.0.1 -U odms_verify -d $database | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'PostgreSQL test database did not become ready' }
    $binding = docker port $container 5432
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Expected a loopback PostgreSQL binding' }
    $values = @{
        ODMS_CONTRACT_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$($Matches[1])/$database"
        ODMS_CONTRACT_TEST_DB_USER = 'odms_verify'
        ODMS_CONTRACT_TEST_DB_PASSWORD = $password
    }
    foreach ($key in $values.Keys) {
        $settings[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
    & (Join-Path $repo 'ondemandmonitoring/mvnw.cmd') -f $Pom "-Dtest=$($tests -join ',')" test
    if ($LASTEXITCODE -ne 0) { throw "Workflow checks failed (Maven exit $LASTEXITCODE)" }
} finally {
    foreach ($key in $settings.Keys) {
        [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
    docker rm --force $container 2>$null | Out-Null
}
