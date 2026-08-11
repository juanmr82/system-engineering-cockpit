<#
.SYNOPSIS
    Prepares one PowerShell session to build and run the System Engineering Cockpit.

.DESCRIPTION
    Dot-source this once per session, from anywhere:

        . C:\path\to\system-engineering-cockpit\scripts\win\sec-env.ps1

    It resolves JAVA_HOME and NEO4J_HOME by looking in the usual places, puts the JDK on
    PATH, exports the Neo4j credentials the backend reads from application.yaml, and turns a
    corporate proxy into the settings Maven, npm and pip each expect in their own way.

    Nothing here is site-specific. Values that ARE site-specific - where your JDK lives, the
    proxy URL, the pip mirror, the database password - go in sec-env.local.ps1 next to this
    file, which is git-ignored. Copy sec-env.local.ps1 to start.

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
# JDK. The build needs 21 or newer: the Kotlin compiler targets 21 and Neo4j 2026 refuses
# to start on anything older.
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
    #
    # The %ProgramFiles% roots only ever match a JDK somebody with administrator rights put
    # there. Without those rights a JDK arrives as an unzipped directory under the user
    # profile instead - IntelliJ's own downloads land in ~\.jdks, scoop in ~\scoop\apps - so
    # those roots are searched too. See docs\RUNNING.md §1.
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
        "$env:LOCALAPPDATA\Programs"
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps"
        "$env:USERPROFILE\.jdks"
        "$env:USERPROFILE\scoop\apps"
        "$env:USERPROFILE\tools"
    )
    # Layouts that put the JDK one directory below the entry the search finds: IntelliJ's
    # bundled runtime, and scoop's version-independent symlink.
    $nested = @('jbr', 'current')

    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $children = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue
        foreach ($child in $children) {
            if (Test-Path (Join-Path $child.FullName 'bin\java.exe')) {
                $candidates.Add($child.FullName)
            }
            foreach ($sub in $nested) {
                $nestedPath = Join-Path $child.FullName $sub
                if (Test-Path (Join-Path $nestedPath 'bin\java.exe')) {
                    $candidates.Add($nestedPath)
                }
            }
        }
    }

    # Preference order among *discovered* JDKs, and it is deliberately NOT "newest wins".
    #
    # The root pom sets maven.compiler.release and the Kotlin plugin's jvmTarget to 21, and
    # Maven compiles with whatever JDK it is itself running on - so the JDK picked here IS the
    # one the build uses. 21 first keeps that honest; a newer JDK would compile against a newer
    # class file version than the target claims. Then the lowest thing above it, and a
    # standalone JDK ahead of an IDE's bundled runtime.
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

    # The user-profile roots come first on purpose: unzipping the Neo4j tarball under the
    # user profile is the install that needs no administrator rights, and it is also the one
    # whose data\, logs\ and conf\ stay writable afterwards.
    $roots = @(
        "$env:USERPROFILE\neo4j"
        "$env:USERPROFILE\tools\neo4j"
        "$env:LOCALAPPDATA\neo4j"
        "$env:ProgramFiles\neo4j"
        "C:\neo4j"
        "C:\tools\neo4j"
    )
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
# Maven. A real install is preferred over the wrapper, because the wrapper's first act is to
# download a distribution and that is the step a locked-down network stops. Maven is a 9 MB
# zip with no installer, so "a real install" here means "somebody unzipped it" - see
# docs\RUNNING.md section 1.2.
#
# Nothing is fatal: with no Maven anywhere, sec-backend.ps1 falls back to mvnw.cmd.
# ---------------------------------------------------------------------------------------
function Resolve-MavenHome {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($SecMavenHome)   { $candidates.Add($SecMavenHome) }
    if ($env:MAVEN_HOME) { $candidates.Add($env:MAVEN_HOME) }
    if ($env:M2_HOME)    { $candidates.Add($env:M2_HOME) }

    # An mvn already on PATH: walk back from bin\mvn.cmd to the install root.
    $onPath = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if (-not $onPath) { $onPath = Get-Command 'mvn' -ErrorAction SilentlyContinue }
    if ($onPath -and $onPath.Source) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $onPath.Source)))
    }

    # The places an unzipped Maven lands on a machine with no administrator rights, plus the
    # copy IntelliJ bundles - which is a complete Maven and is already on the disk of anyone
    # who opens this project in the IDE.
    $roots = @(
        "$env:USERPROFILE\tools"
        "$env:USERPROFILE\scoop\apps\maven"
        "$env:LOCALAPPDATA\Programs"
        "$env:ProgramFiles"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem -Path $root -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
        Get-ChildItem -Path $root -Directory -Filter 'maven*' -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName); $candidates.Add((Join-Path $_.FullName 'current')) }
    }
    foreach ($ide in @("$env:LOCALAPPDATA\JetBrains", "$env:ProgramFiles\JetBrains")) {
        if (-not (Test-Path $ide)) { continue }
        Get-ChildItem -Path $ide -Directory -Recurse -Depth 2 -Filter 'maven3' -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        if (Test-Path (Join-Path $candidate 'bin\mvn.cmd')) { return $candidate }
    }
    return $null
}

$mavenHome = Resolve-MavenHome
if ($mavenHome) {
    $env:MAVEN_HOME = $mavenHome
    $mavenBin = Join-Path $mavenHome 'bin'
    if ($env:PATH -notlike "*$mavenBin*") {
        $env:PATH = "$mavenBin;$env:PATH"
    }
    $env:SEC_MVN = Join-Path $mavenBin 'mvn.cmd'
    Write-SecLine 'Maven' $mavenHome 'Green'
} else {
    $env:SEC_MVN = ''
    Write-SecLine 'Maven' 'not found - sec-backend.ps1 will use .\mvnw.cmd (needs one download)' 'Yellow'
}

# A settings.xml carrying the company mirror, the proxy and any repository credentials. Maven
# reads %USERPROFILE%\.m2\settings.xml by itself; this is for keeping one beside the repo
# instead, which is what a machine with several projects and one awkward network tends to want.
if ($SecMavenSettings) {
    if (Test-Path $SecMavenSettings) {
        $env:SEC_MVN_SETTINGS = $SecMavenSettings
        Write-SecLine 'Maven settings' $SecMavenSettings 'Green'
    } else {
        Write-SecLine 'Maven settings' "NOT FOUND at $SecMavenSettings" 'Red'
    }
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
# JIRA. Unlike Neo4j, all four are OPTIONAL: application.yaml defaults them to empty, an
# empty host means "not configured on this deployment", and the backend starts anyway with
# /api/v1/jira/** answering 503 and saying so. A cockpit has four other sources.
#
# SEC_JIRA_AUTH picks how the credential is sent, and the two products disagree:
#   bearer  Data Center / Server - a personal access token. The default.
#   basic   Cloud (*.atlassian.net) - an API token as base64(email:token). Needs SEC_JIRA_EMAIL.
# Cloud answers a Bearer PAT with 403, so this is configuration, never a retry after a refusal.
#
# SEC_JIRA_DEPLOYMENT picks how ISSUES ARE PAGED, which is a separate fact:
#   datacenter  /search, startAt + total. The default.
#   cloud       /search/jql, opaque cursor, no total. Cloud answers /search with 410 Gone.
# Set BOTH for a Cloud instance. They usually covary and they are not the same question - Data
# Center accepts basic auth too - so neither is derived from the other.
# ---------------------------------------------------------------------------------------
if ($SecJiraHost)       { $env:SEC_JIRA_HOST       = $SecJiraHost }
if ($SecJiraToken)      { $env:SEC_JIRA_TOKEN      = $SecJiraToken }
if ($SecJiraAuth)       { $env:SEC_JIRA_AUTH       = $SecJiraAuth }
if ($SecJiraEmail)      { $env:SEC_JIRA_EMAIL      = $SecJiraEmail }
if ($SecJiraDeployment) { $env:SEC_JIRA_DEPLOYMENT = $SecJiraDeployment }

if ($env:SEC_JIRA_HOST -and $env:SEC_JIRA_TOKEN) {
    $scheme = if ($env:SEC_JIRA_AUTH) { $env:SEC_JIRA_AUTH } else { 'bearer' }
    $product = if ($env:SEC_JIRA_DEPLOYMENT) { $env:SEC_JIRA_DEPLOYMENT } else { 'datacenter' }
    Write-SecLine 'JIRA' "$env:SEC_JIRA_HOST ($product, $scheme, token set)" 'Green'
} else {
    Write-SecLine 'JIRA' 'not configured - /api/v1/jira/** will report so' 'Yellow'
}

# ---------------------------------------------------------------------------------------
# Proxy. Three toolchains, three ways of being told the same thing.
#
#   Maven   - settings.xml <proxies>, which is the ONLY mechanism its resolver reads reliably.
#             The JVM properties below are set as well, because plugins that open their own
#             connections do honour them, but a proxy configured ONLY here and not in
#             settings.xml will still fail to resolve dependencies. See docs\RUNNING.md 2.6.
#   npm/pip - the lowercase http_proxy / https_proxy convention
#
# localhost is always excluded: Neo4j on 7687 and the dev server on 4200 must never be
# routed through a proxy that will not answer for them.
# ---------------------------------------------------------------------------------------
$proxy = $null
if ($SecProxy)            { $proxy = $SecProxy }
elseif ($env:HTTPS_PROXY) { $proxy = $env:HTTPS_PROXY }
elseif ($env:HTTP_PROXY)  { $proxy = $env:HTTP_PROXY }

# MAVEN_OPTS is ACCUMULATED, never assigned over. sec-env.local.ps1 is sourced before this
# point and is where a site adds its own JVM flags - a trust store for an inspecting proxy,
# most often - and assigning here would silently discard them. $SecMavenOpts is the
# supported way to add flags; anything already in the environment is kept too.
$mavenOpts = New-Object System.Collections.Generic.List[string]
if ($env:MAVEN_OPTS) { $mavenOpts.Add($env:MAVEN_OPTS) }
if ($SecMavenOpts)   { $mavenOpts.Add($SecMavenOpts) }

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

    $mavenOpts.Add("-Dhttp.proxyHost=$proxyHost -Dhttp.proxyPort=$proxyPort " +
                   "-Dhttps.proxyHost=$proxyHost -Dhttps.proxyPort=$proxyPort " +
                   "-Dhttp.nonProxyHosts=`"$nonProxyHosts`"")

    # Proxy credentials, when the proxy demands them. Two JVM properties do the obvious job -
    # and a third undoes a default that otherwise makes them useless: since 8u111 the JVM
    # refuses Basic authentication to a proxy on an HTTPS tunnel, which is exactly the case
    # here, and it fails with a bare 407 that names no cause. Clearing disabledSchemes is what
    # every corporate JVM setup ends up doing.
    #
    # Every value is quoted. MAVEN_OPTS is expanded by cmd.exe and split on whitespace, so an
    # unquoted password containing a space becomes two arguments and is silently truncated at
    # the space - which authenticates with the wrong password and reports only a bare 407.
    # (A password containing a double quote cannot survive this and needs changing.)
    if ($SecProxyUser) {
        $mavenOpts.Add("-Dhttp.proxyUser=`"$SecProxyUser`" -Dhttp.proxyPassword=`"$SecProxyPassword`" " +
                       "-Dhttps.proxyUser=`"$SecProxyUser`" -Dhttps.proxyPassword=`"$SecProxyPassword`" " +
                       '-Djdk.http.auth.tunneling.disabledSchemes="" ' +
                       '-Djdk.http.auth.proxying.disabledSchemes=""')

        # npm and pip take the credentials inside the URL instead. Only rewrite when the URL
        # does not already carry them.
        if ($proxy -notmatch '@') {
            $encodedUser = [System.Uri]::EscapeDataString($SecProxyUser)
            $encodedPass = [System.Uri]::EscapeDataString([string] $SecProxyPassword)
            $authProxy = $proxy -replace '^(https?://)', "`${1}${encodedUser}:${encodedPass}@"
            $env:HTTP_PROXY = $authProxy; $env:http_proxy = $authProxy
            $env:HTTPS_PROXY = $authProxy; $env:https_proxy = $authProxy
        }
    }

    $proxyNote = if ($SecProxyUser) { "  (npm, pip - as $SecProxyUser; Maven needs settings.xml)" } else { '  (npm, pip; Maven needs settings.xml)' }
    Write-SecLine 'Proxy' "$proxyHost`:$proxyPort$proxyNote" 'Green'
} else {
    Write-SecLine 'Proxy' 'none configured' 'Gray'
}

if ($mavenOpts.Count -gt 0) {
    # A password in MAVEN_OPTS is visible to anything that can list this process's
    # environment. On a single-user workstation that is an acceptable trade for a build that
    # works; it is not a pattern to carry to a shared or a build machine. settings.xml with a
    # <server> entry is the better home for a credential that outlives the session.
    $env:MAVEN_OPTS = ($mavenOpts -join ' ')
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
