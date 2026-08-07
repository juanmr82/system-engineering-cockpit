<#
.SYNOPSIS
    Runs the Ktor backend on :8080, or builds and tests it.

.DESCRIPTION
    Dot-source sec-env.ps1 first: this needs JAVA_HOME, and the backend refuses to start
    without SEC_NEO4J_USER and SEC_NEO4J_PASSWORD.

        scripts\win\sec-backend.ps1              # run it, foreground, Ctrl+C to stop
        scripts\win\sec-backend.ps1 -Check       # mvn verify - compile, unit tests, package
        scripts\win\sec-backend.ps1 -Docker      # the container tests, which need Docker
        scripts\win\sec-backend.ps1 -Offline     # build without contacting any repository

    A running backend serves the code it started with. Restart it after any backend change -
    there is no reload.

.PARAMETER Check
    Run `mvn verify` instead of starting the server. Container tests are excluded by design,
    so this passes on a machine without Docker.

.PARAMETER Docker
    Run only the Testcontainers tests (`mvn -Pdocker test`). Needs a working Docker daemon.

.PARAMETER Offline
    Add --offline. Useful once the local repository is warm and the proxy is being slow, and
    the only way to build if the mirror is down.
#>
[CmdletBinding()]
param(
    [switch] $Check,
    [switch] $Docker,
    [switch] $Jar,
    [switch] $Offline
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. Dot-source scripts\win\sec-env.ps1 first.'
}

# -Jar: run the packaged artifact instead of building from source. This is the deployment shape -
# one file, a JDK, and nothing else. No Maven, no Node, no sources. When the jar was built with
# -Pui it serves the Angular application too, on the same port, so :8080 is the whole product.
if ($Jar) {
    if (-not $env:SEC_NEO4J_USER -or -not $env:SEC_NEO4J_PASSWORD) {
        throw 'SEC_NEO4J_USER / SEC_NEO4J_PASSWORD are not set. application.yaml resolves them from the environment and startup fails without them.'
    }

    $jarFile = Get-ChildItem -Path (Join-Path $repo 'backend\target') -Filter '*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jarFile) {
        throw 'No packaged jar in backend\target. Build one: scripts\win\sec-package.ps1'
    }

    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    Write-Host ''
    Write-Host "  Jar       $($jarFile.Name)  ($([int] ($jarFile.Length / 1MB)) MB, built $($jarFile.LastWriteTime))"
    Write-Host '  Serving   http://localhost:8080'
    Write-Host ''
    Write-Host '  Ctrl+C stops it. This is the built artifact - rebuild with sec-package.ps1 after a change.' -ForegroundColor Cyan
    Write-Host ''

    & $java '-jar' $jarFile.FullName
    exit $LASTEXITCODE
}

# Which Maven. sec-env.ps1 sets SEC_MVN when it finds a real install, and a real install is
# preferred precisely because the wrapper's first act is to download a distribution - the step
# a locked-down network stops. The wrapper is the fallback, not the default.
$mvn = $env:SEC_MVN
if (-not $mvn -or -not (Test-Path $mvn)) {
    $mvn = Join-Path $repo 'mvnw.cmd'
    if (-not (Test-Path $mvn)) {
        throw @"
No Maven found, and no mvnw.cmd in the repository.
Either unzip Apache Maven anywhere you can write and set `$SecMavenHome in
scripts\win\sec-env.local.ps1, or restore the wrapper with: git checkout -- mvnw.cmd .mvn
See docs\RUNNING.md section 1.2.
"@
    }
    Write-Host '  Using .\mvnw.cmd - the first run downloads Maven itself.' -ForegroundColor Yellow
}

$mvnArgs = New-Object System.Collections.Generic.List[string]
$mvnArgs.Add('-B')

# A settings.xml holding the company mirror, proxy and repository credentials. Maven finds
# %USERPROFILE%\.m2\settings.xml on its own; this is only for one kept beside the repository.
if ($env:SEC_MVN_SETTINGS) {
    $mvnArgs.Add('-s'); $mvnArgs.Add($env:SEC_MVN_SETTINGS)
}
if ($Offline) { $mvnArgs.Add('--offline') }

if ($Check) {
    $mvnArgs.Add('verify')
} elseif ($Docker) {
    $mvnArgs.Add('-Pdocker'); $mvnArgs.Add('test')
} else {
    if (-not $env:SEC_NEO4J_USER -or -not $env:SEC_NEO4J_PASSWORD) {
        throw 'SEC_NEO4J_USER / SEC_NEO4J_PASSWORD are not set. application.yaml resolves them from the environment and startup fails without them. Set them in scripts\win\sec-env.local.ps1.'
    }
    # exec:java runs the app in Maven's own JVM against the runtime classpath. compile first,
    # so that a source change is actually picked up - exec:java on its own happily runs a stale
    # target\classes and looks like the edit did nothing.
    $mvnArgs.Add('-pl'); $mvnArgs.Add('backend')
    $mvnArgs.Add('compile'); $mvnArgs.Add('exec:java')
}

Push-Location $repo
try {
    if (-not $Check -and -not $Docker) {
        Write-Host ''
        Write-Host '  Backend   http://localhost:8080'
        Write-Host '  Health    http://localhost:8080/api/v1/health   (liveness, touches no database)'
        Write-Host '  Ready     http://localhost:8080/api/v1/ready    (pings the graph)'
        Write-Host ''
        Write-Host '  Ctrl+C stops it. Restart after any backend change - it serves the code it started with.' -ForegroundColor Cyan
        Write-Host ''
    }
    & $mvn @mvnArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
