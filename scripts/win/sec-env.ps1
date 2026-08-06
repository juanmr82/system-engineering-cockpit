<#
.SYNOPSIS
    Prepares one PowerShell session to build and run the System Engineering Cockpit.

.DESCRIPTION
    Dot-source this once per session, from anywhere:

        . C:\path\to\system-engineering-cockpit\scripts\win\sec-env.ps1

    It resolves JAVA_HOME and NEO4J_HOME by looking in the usual places, puts the JDK on
    PATH, exports the Neo4j credentials the backend reads from application.yaml, and turns a
    corporate proxy into the settings Gradle, npm and pip each expect in their own way.

    Nothing here is site-specific. Values that ARE site-specific - where your JDK lives, the
    proxy URL, the pip mirror, the database password - go in sec-env.local.ps1 next to this
    file, which is git-ignored. Copy sec-env.local.ps1.example to start.

    -Persist writes JAVA_HOME and NEO4J_HOME to your Windows user environment, so new
    sessions have them without dot-sourcing anything. Run it once; it is the answer to
    "JAVA_HOME may need to be set every time I log in".

.PARAMETER Persist
    Also write the resolved JAVA_HOME and NEO4J_HOME to the user environment, permanently.

.PARAMETER Quiet
    Resolve everything but print nothing.
#>
[CmdletBinding()]
param(
    [switch] $Persist,
    [switch] $Quiet
)

# ---------------------------------------------------------------------------------------
# Where we are. scripts\win\ -> scripts\ -> repository root.
# ---------------------------------------------------------------------------------------
$SecRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$env:SEC_REPO = $SecRepo

# Site-specific overrides. Sourced first so everything below can honour them.
$localOverrides = Join-Path $PSScriptRoot 'sec-env.local.ps1'
if (Test-Path $localOverrides) {
    . $localOverrides
}

function Write-SecLine {
    param([string] $Label, [string] $Value, [string] $Colour = 'Gray')
    if ($Quiet) { return }
    Write-Host ("  {0,-14} " -f $Label) -NoNewline
    Write-Host $Value -ForegroundColor $Colour
}

# ---------------------------------------------------------------------------------------
# JDK. The build needs 21 or newer: the Gradle toolchain compiles to 21 and Neo4j 2026
# refuses to start on anything older.
#
# The version is read from the JDK's own `release` file rather than by running `java
# -version`. Redirecting a native executable's stderr in Windows PowerShell 5.1 wraps every
# line in an ErrorRecord and sets $? to false even on success, which makes the obvious
# implementation of this check report a working JDK as broken.
# ---------------------------------------------------------------------------------------
function Get-JdkMajor {
    # Not -Home: $Home is a read-only automatic variable, and a parameter of that name fails
    # to bind at every call site with "Cannot overwrite variable Home".
    param([string] $JdkHome)

    $releaseFile = Join-Path $JdkHome 'release'
    if (-not (Test-Path $releaseFile)) { return 0 }

    $line = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="?([0-9]+)' -ErrorAction SilentlyContinue
    if (-not $line) { return 0 }
    return [int] $line.Matches[0].Groups[1].Value
}

function Resolve-Jdk {
    # An explicit choice is honoured and never second-guessed: if you set $SecJavaHome, or
    # already have a working JAVA_HOME, that is the JDK.
    foreach ($explicit in @($SecJavaHome, $env:JAVA_HOME)) {
        if (-not $explicit) { continue }
        if (-not (Test-Path (Join-Path $explicit 'bin\java.exe'))) { continue }
        $major = Get-JdkMajor -JdkHome $explicit
        if ($major -ge 21) {
            return [pscustomobject]@{ Path = $explicit; Major = $major }
        }
    }

    $candidates = New-Object System.Collections.Generic.List[string]

    # The vendors a locked-down Windows workstation actually ships, plus JetBrains' bundled
    # runtime, which is a real JDK and is present on any machine with IntelliJ installed.
    $roots = @(
        "$env:ProgramFiles\Eclipse Adoptium"
        "$env:ProgramFiles\Java"
        "$env:ProgramFiles\Microsoft"
        "$env:ProgramFiles\Amazon Corretto"
        "$env:ProgramFiles\BellSoft"
        "$env:ProgramFiles\Zulu"
        "$env:ProgramFiles\JetBrains"
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
        "$env:LOCALAPPDATA\Programs\Microsoft"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
            ForEach-Object { $candidates.Add($_.FullName) }
        # IntelliJ nests its runtime one level deeper.
        Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'jbr' } |
            Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } |
            ForEach-Object { $candidates.Add($_) }
    }

    # Preference order among *discovered* JDKs, and it is deliberately NOT "newest wins".
    #
    # backend/build.gradle.kts pins jvmToolchain(21), so Gradle needs a JDK 21 to be
    # installed whatever it is itself running on. On a machine with 21 and 25 present,
    # picking 25 leaves the toolchain unresolvable and the build fails asking for a JDK that
    # is sitting right there. So: 21 first, then the lowest thing above it, and a standalone
    # JDK ahead of an IDE's bundled runtime.
    $found = New-Object System.Collections.Generic.List[object]
    $seen = New-Object System.Collections.Generic.HashSet[string]

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        if (-not (Test-Path (Join-Path $candidate 'bin\java.exe'))) { continue }
        if (-not $seen.Add($candidate.ToLowerInvariant())) { continue }

        $major = Get-JdkMajor -JdkHome $candidate
        if ($major -lt 21) { continue }

        $found.Add([pscustomobject]@{
            Path       = $candidate
            Major      = $major
            IsPinned   = if ($major -eq 21) { 0 } else { 1 }
            IsBundled  = if ($candidate -like '*JetBrains*') { 1 } else { 0 }
            Order      = $found.Count
        })
    }

    if ($found.Count -eq 0) { return $null }

    return $found |
        Sort-Object IsPinned, IsBundled, Major, Order |
        Select-Object -First 1
}

$jdk = Resolve-Jdk
if ($jdk) {
    $env:JAVA_HOME = $jdk.Path
    $jdkBin = Join-Path $jdk.Path 'bin'
    if ($env:PATH -notlike "*$jdkBin*") {
        $env:PATH = "$jdkBin;$env:PATH"
    }
    Write-SecLine 'JAVA_HOME' "$($jdk.Path)  (Java $($jdk.Major))" 'Green'
} else {
    Write-SecLine 'JAVA_HOME' 'NOT FOUND - set $SecJavaHome in sec-env.local.ps1' 'Red'
}

# ---------------------------------------------------------------------------------------
# Neo4j. Runs from the console on this machine, not as a service, so all we need is the
# install directory - sec-neo4j.ps1 starts it.
# ---------------------------------------------------------------------------------------
function Resolve-Neo4jHome {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($SecNeo4jHome)   { $candidates.Add($SecNeo4jHome) }
    if ($env:NEO4J_HOME) { $candidates.Add($env:NEO4J_HOME) }

    $roots = @("$env:USERPROFILE\neo4j", "$env:ProgramFiles\neo4j", "C:\neo4j", "C:\tools\neo4j")
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        if (Test-Path (Join-Path $root 'bin\neo4j.bat')) { $candidates.Add($root) }
        Get-ChildItem -Path $root -Directory -Filter 'neo4j-community-*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate 'bin\neo4j.bat')) { return $candidate }
    }
    return $null
}

$neo4jHome = Resolve-Neo4jHome
if ($neo4jHome) {
    $env:NEO4J_HOME = $neo4jHome
    Write-SecLine 'NEO4J_HOME' $neo4jHome 'Green'
} else {
    Write-SecLine 'NEO4J_HOME' 'NOT FOUND - set $SecNeo4jHome in sec-env.local.ps1' 'Yellow'
}

# ---------------------------------------------------------------------------------------
# Backend credentials. application.yaml resolves "$SEC_NEO4J_USER" / "$SEC_NEO4J_PASSWORD"
# from the environment and fails at startup if either is unset - deliberately, so that a
# password is never committed and config has exactly one source.
# ---------------------------------------------------------------------------------------
if ($SecNeo4jUser)     { $env:SEC_NEO4J_USER = $SecNeo4jUser }
if ($SecNeo4jPassword) { $env:SEC_NEO4J_PASSWORD = $SecNeo4jPassword }

# The importer reads its own variables. Same credentials, different names, because it is a
# separate program that must also run on a machine where the backend was never installed.
if ($env:SEC_NEO4J_USER)     { $env:NEO4J_USER = $env:SEC_NEO4J_USER }
if ($env:SEC_NEO4J_PASSWORD) { $env:NEO4J_PASSWORD = $env:SEC_NEO4J_PASSWORD }
if (-not $env:NEO4J_URI)     { $env:NEO4J_URI = 'neo4j://localhost:7687' }

if ($env:SEC_NEO4J_USER -and $env:SEC_NEO4J_PASSWORD) {
    Write-SecLine 'Neo4j login' "$env:SEC_NEO4J_USER (password set)" 'Green'
} else {
    Write-SecLine 'Neo4j login' 'NOT SET - the backend will refuse to start' 'Red'
}

# ---------------------------------------------------------------------------------------
# Proxy. Three toolchains, three ways of being told the same thing.
#
#   Gradle  - JVM system properties, passed through GRADLE_OPTS
#   npm/pip - the lowercase http_proxy / https_proxy convention
#
# localhost is always excluded: Neo4j on 7687 and the dev server on 4200 must never be
# routed through a proxy that will not answer for them.
# ---------------------------------------------------------------------------------------
$proxy = $null
if ($SecProxy)            { $proxy = $SecProxy }
elseif ($env:HTTPS_PROXY) { $proxy = $env:HTTPS_PROXY }
elseif ($env:HTTP_PROXY)  { $proxy = $env:HTTP_PROXY }

if ($proxy) {
    $env:HTTP_PROXY  = $proxy
    $env:HTTPS_PROXY = $proxy
    $env:http_proxy  = $proxy
    $env:https_proxy = $proxy

    $noProxy = 'localhost,127.0.0.1,::1'
    if ($SecNoProxy) { $noProxy = "$noProxy,$SecNoProxy" }
    $env:NO_PROXY = $noProxy
    $env:no_proxy = $noProxy

    $uri = [System.Uri] $proxy
    $proxyHost = $uri.Host
    $proxyPort = $uri.Port
    $nonProxyHosts = ($noProxy -replace ',', '|')

    $gradleProxy = "-Dhttp.proxyHost=$proxyHost -Dhttp.proxyPort=$proxyPort " +
                   "-Dhttps.proxyHost=$proxyHost -Dhttps.proxyPort=$proxyPort " +
                   "-Dhttp.nonProxyHosts=`"$nonProxyHosts`""
    $env:GRADLE_OPTS = $gradleProxy

    Write-SecLine 'Proxy' "$proxyHost`:$proxyPort  (Gradle, npm, pip)" 'Green'
} else {
    Write-SecLine 'Proxy' 'none configured' 'Gray'
}

# pip's mirror. Exported rather than written to pip.ini so it stays visible in one place
# and so a machine that later gets direct access needs no file deleted.
if ($SecPipIndexUrl) {
    $env:PIP_INDEX_URL = $SecPipIndexUrl
    if (-not $SecPipTrustedHost) {
        $SecPipTrustedHost = ([System.Uri] $SecPipIndexUrl).Host
    }
    $env:PIP_TRUSTED_HOST = $SecPipTrustedHost
    Write-SecLine 'pip index' $SecPipIndexUrl 'Green'
} elseif ($env:PIP_INDEX_URL) {
    Write-SecLine 'pip index' $env:PIP_INDEX_URL 'Green'
} else {
    Write-SecLine 'pip index' 'default (pypi.org) - set $SecPipIndexUrl for the company mirror' 'Yellow'
}

if ($SecNpmRegistry) {
    $env:NPM_CONFIG_REGISTRY = $SecNpmRegistry
    Write-SecLine 'npm registry' $SecNpmRegistry 'Green'
}

# UTF-8 for the importers. DOORS attribute names contain umlauts and the default Windows
# codepage corrupts them silently.
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'

# ---------------------------------------------------------------------------------------
# -Persist: make the two path variables survive a logout.
# ---------------------------------------------------------------------------------------
if ($Persist) {
    if ($env:JAVA_HOME) {
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $env:JAVA_HOME, 'User')
        Write-SecLine 'Persisted' "JAVA_HOME -> user environment" 'Cyan'
    }
    if ($env:NEO4J_HOME) {
        [Environment]::SetEnvironmentVariable('NEO4J_HOME', $env:NEO4J_HOME, 'User')
        Write-SecLine 'Persisted' "NEO4J_HOME -> user environment" 'Cyan'
    }
    if (-not $Quiet) {
        Write-Host ''
        Write-Host '  Persisted to the user environment. New terminals will have these; this one'
        Write-Host '  already does. The credentials and proxy are deliberately NOT persisted -'
        Write-Host '  dot-source this script for those, or set them yourself if you prefer.'
    }
}

if (-not $Quiet) {
    Write-Host ''
    Write-Host '  Repository    ' -NoNewline; Write-Host $SecRepo -ForegroundColor Gray
    Write-Host ''
    Write-Host '  Next: ' -NoNewline
    Write-Host 'scripts\win\sec-doctor.ps1' -ForegroundColor White -NoNewline
    Write-Host ' checks everything this session needs.'
}
