$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$qa=Get-Content (Join-Path $repo '.ai-runtime/deepeval/environment.json') | ConvertFrom-Json
Set-Location (Join-Path $repo 'ondemandmonitoring')
$env:SPRING_DATASOURCE_URL=$qa.jdbcUrl
$env:SPRING_PROFILES_ACTIVE='deepeval-local'
$env:SERVER_PORT='8081'
$env:CORS_ADDRESS='http://localhost:5174'
Get-Content (Join-Path $repo '.env') | ForEach-Object {
 if($_ -match '^(VNPAY_[A-Z0-9_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim().Trim('"'),'Process')}
}
$env:MAVEN_OPTS='-Xmx384m'
$jvm='-Xmx512m -Dspring.devtools.restart.enabled=false -Dspring.jpa.properties.hibernate.default_schema='+$qa.schema
& ./mvnw.cmd -f .description-check.pom.xml -q '-Dmaven.test.skip=true' spring-boot:run ('-Dspring-boot.run.jvmArguments='+$jvm)
exit $LASTEXITCODE
