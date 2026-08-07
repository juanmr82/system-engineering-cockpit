<#
.SYNOPSIS
    Builds the deployable artifact: one jar carrying the API and the user interface.

.DESCRIPTION
        scripts\win\sec-package.ps1            # Angular build, then the jar around it
        scripts\win\sec-package.ps1 -NoUi      # API only, no Angular build
        scripts\win\sec-package.ps1 -SkipTests # faster, and worth less

    Two steps, in this order, because the second copies what the first produced:

        1. npm run build          -> frontend\dist\frontend\browser
        2. mvn -Pui clean package -> backend\target\backend-<version>-all.jar

    Maven copies the Angular output into the jar under static\; it does not run npm. Running npm
    from Maven would mean the frontend-maven-plugin, which downloads its own Node - the one thing
    a proxy-only workstation cannot be asked to do, and a second Node beside the installed one.

    What comes out needs a JDK 21 and a reachable Neo4j. No Maven, no Node, no sources, no IDE:

        java -jar backend-0.1.0-all.jar

.PARAMETER NoUi
    Skip the Angular build and leave the UI out of the jar. The API still works; :8080 serves no
    pages.

.PARAMETER SkipTests
    Package without running the tests.
#>
[CmdletBinding()]
param(
    [switch] $NoUi,
    [switch] $SkipTests
)

$ErrorActionPreference = 'Stop'
$scripts = $PSScriptRoot
$repo = Split-Path -Parent (Split-Path -Parent $scripts)

. "$scripts\sec-env.ps1" -Quiet

if (-not $env:JAVA_HOME) {
    throw 'No JDK 21+. Set $SecJavaHome in sec-env.local.ps1 - see docs\RUNNING.md section 1.2.'
}

$mvn = $env:SEC_MVN
if (-not $mvn -or -not (Test-Path $mvn)) {
    $mvn = Join-Path $repo 'mvnw.cmd'
    if (-not (Test-Path $mvn)) {
        throw 'No Maven and no mvnw.cmd. See docs\RUNNING.md section 2.6.'
    }
}

$distDir = Join-Path $repo 'frontend\dist\frontend\browser'

# --- 1. the user interface ----------------------------------------------------------------
if (-not $NoUi) {
    Write-Host ''
    Write-Host '  [1/2] Building the user interface' -ForegroundColor White
    Write-Host ''

    & "$scripts\sec-frontend.ps1" -Build
    if ($LASTEXITCODE -ne 0) {
        throw "The Angular build failed with exit code $LASTEXITCODE. The jar was not built."
    }

    # Checked rather than assumed: Maven's copy step warns and carries on when the directory is
    # missing, which would produce a jar that looks right and serves no pages.
    if (-not (Test-Path (Join-Path $distDir 'index.html'))) {
        throw "The Angular build reported success but produced no index.html in $distDir. Check angular.json's outputPath - this script and backend\pom.xml both expect frontend\dist\frontend\browser."
    }
}

# --- 2. the jar ---------------------------------------------------------------------------
Write-Host ''
Write-Host '  [2/2] Building the jar' -ForegroundColor White
Write-Host ''

$mvnArgs = New-Object System.Collections.Generic.List[string]
$mvnArgs.Add('-B')
if ($env:SEC_MVN_SETTINGS) { $mvnArgs.Add('-s'); $mvnArgs.Add($env:SEC_MVN_SETTINGS) }
if (-not $NoUi) { $mvnArgs.Add('-Pui') }
if ($SkipTests) { $mvnArgs.Add('-DskipTests') }
$mvnArgs.Add('clean'); $mvnArgs.Add('package')

Push-Location $repo
try {
    & $mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}

# --- what came out --------------------------------------------------------------------------
$jarFile = Get-ChildItem -Path (Join-Path $repo 'backend\target') -Filter '*-all.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jarFile) {
    throw 'Maven reported success but produced no *-all.jar. Check the shade plugin in backend\pom.xml.'
}

# Prove the UI really is inside, rather than trusting that the copy happened. A jar that is
# missing its pages is indistinguishable from a working one until someone opens a browser.
$uiInJar = $false
if (-not $NoUi) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jarFile.FullName)
    try {
        $uiInJar = $null -ne ($archive.Entries | Where-Object { $_.FullName -eq 'static/index.html' })
    } finally {
        $archive.Dispose()
    }
    if (-not $uiInJar) {
        throw "The jar was built but does not contain static/index.html. The -Pui copy step did not run - check that $distDir existed when Maven ran."
    }
}

Write-Host ''
Write-Host '  Built' -ForegroundColor Green
Write-Host "    $($jarFile.FullName)"
Write-Host ("    {0} MB{1}" -f [int] ($jarFile.Length / 1MB), $(if ($uiInJar) { ', user interface included' } else { ', API only' }))
Write-Host ''
Write-Host '  Run it:' -ForegroundColor White
Write-Host '    scripts\win\sec-up.ps1 -Jar        Neo4j + the jar, one command'
Write-Host '    scripts\win\sec-backend.ps1 -Jar   the jar alone'
Write-Host ''
Write-Host '  Deploy it: copy the jar to a machine with a JDK 21 and a reachable Neo4j, set'
Write-Host '  SEC_NEO4J_USER / SEC_NEO4J_PASSWORD, and run: java -jar ' -NoNewline
Write-Host $jarFile.Name
Write-Host ''
