<#
.SYNOPSIS
    Runs the Ktor backend on :8080, or builds and tests it.

.DESCRIPTION
    Dot-source sec-env.ps1 first: this needs JAVA_HOME, and the backend refuses to start
    without SEC_NEO4J_USER and SEC_NEO4J_PASSWORD.

        scripts\win\sec-backend.ps1              # run it, foreground, Ctrl+C to stop
        scripts\win\sec-backend.ps1 -Check       # ./gradlew check
        scripts\win\sec-backend.ps1 -Offline     # run without contacting any repository

    A running backend serves the code it started with. Restart it after any backend change -
    there is no reload.

.PARAMETER Check
    Run `gradlew check` instead of starting the server. Container tests are excluded from
    `check` by design, so this passes on a machine without Docker.

.PARAMETER Offline
    Add --offline. Useful once the dependency cache is warm and the proxy is being slow, and
    the only way to build if the mirror is down.
#>
[CmdletBinding()]
param(
    [switch] $Check,
    [switch] $Offline
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. Dot-source scripts\win\sec-env.ps1 first.'
}

$gradleArgs = New-Object System.Collections.Generic.List[string]

if ($Check) {
    $gradleArgs.Add('check')
} else {
    if (-not $env:SEC_NEO4J_USER -or -not $env:SEC_NEO4J_PASSWORD) {
        throw 'SEC_NEO4J_USER / SEC_NEO4J_PASSWORD are not set. application.yaml resolves them from the environment and startup fails without them. Set them in scripts\win\sec-env.local.ps1.'
    }
    $gradleArgs.Add(':backend:run')
}

if ($Offline) { $gradleArgs.Add('--offline') }

Push-Location $repo
try {
    if (-not $Check) {
        Write-Host ''
        Write-Host '  Backend   http://localhost:8080'
        Write-Host '  Health    http://localhost:8080/api/v1/health   (liveness, touches no database)'
        Write-Host '  Ready     http://localhost:8080/api/v1/ready    (pings the graph)'
        Write-Host ''
        Write-Host '  Ctrl+C stops it. Restart after any backend change - it serves the code it started with.' -ForegroundColor Cyan
        Write-Host ''
    }
    & (Join-Path $repo 'gradlew.bat') @gradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
