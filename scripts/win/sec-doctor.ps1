<#
.SYNOPSIS
    Checks that this machine can build, run and import for the System Engineering Cockpit.

.DESCRIPTION
    Reports one line per prerequisite and tells you what to do about each failure, rather
    than failing later inside a build log. Run it after dot-sourcing sec-env.ps1:

        . scripts\win\sec-env.ps1
        scripts\win\sec-doctor.ps1

    It reads state and opens two TCP connections. It changes nothing.

    Docker is deliberately not checked. It is not needed: the container tests are excluded
    from `check` by design and Neo4j runs from the console here.
#>
[CmdletBinding()]
param()

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:Failures = 0
$script:Warnings = 0

function Test-Sec {
    param(
        [string] $Name,
        [scriptblock] $Check,   # returns a string on success, $null on failure
        [string] $Remedy,
        [switch] $WarnOnly
    )

    $result = $null
    try { $result = & $Check } catch { $result = $null }

    if ($result) {
        Write-Host ('  [ ok ] ' + $Name.PadRight(22)) -NoNewline
        Write-Host $result -ForegroundColor Green
        return
    }

    if ($WarnOnly) {
        $script:Warnings++
        Write-Host ('  [warn] ' + $Name.PadRight(22)) -NoNewline
        Write-Host $Remedy -ForegroundColor Yellow
    } else {
        $script:Failures++
        Write-Host ('  [FAIL] ' + $Name.PadRight(22)) -NoNewline
        Write-Host $Remedy -ForegroundColor Red
    }
}

# Test-SecPort probes IPv4 and IPv6. That matters here: ng serve binds ::1 only, and an
# IPv4-only probe reported a running dev server as "Not running". See sec-ports.ps1.
. "$PSScriptRoot\sec-ports.ps1"

function Test-Port {
    param([int] $Port)
    return Test-SecPort -Port $Port
}

function Get-CommandVersion {
    param([string] $Exe, [string] $VersionArg = '--version')
    $cmd = Get-Command $Exe -ErrorAction SilentlyContinue
    if (-not $cmd) { return $null }
    $out = & $Exe $VersionArg
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($out | Select-Object -First 1)
}

Write-Host ''
Write-Host '  System Engineering Cockpit - environment check' -ForegroundColor White
Write-Host ''

# --- Build toolchain ---------------------------------------------------------------------

Test-Sec 'JDK 21+' {
    if (-not $env:JAVA_HOME) { return $null }
    $releaseFile = Join-Path $env:JAVA_HOME 'release'
    if (-not (Test-Path $releaseFile)) { return $null }
    $line = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="?([0-9][0-9.]*)' -ErrorAction SilentlyContinue
    if (-not $line) { return $null }
    $full = $line.Matches[0].Groups[1].Value
    $major = [int] ($full -split '\.')[0]
    if ($major -lt 21) { return $null }
    return "$full at $env:JAVA_HOME"
} 'JAVA_HOME unset or below 21. Dot-source sec-env.ps1, or set $SecJavaHome in sec-env.local.ps1.'

Test-Sec 'Maven' {
    # A real Maven beats the wrapper here, because the wrapper's first act is to download a
    # distribution and that is exactly the step this network stops. Either is fine; say which.
    if ($env:SEC_MVN -and (Test-Path $env:SEC_MVN)) {
        # Not $home: it is a read-only automatic variable and assigning to it throws.
        $mavenRoot = Split-Path -Parent (Split-Path -Parent $env:SEC_MVN)
        return "installed at $mavenRoot"
    }
    if (Test-Path (Join-Path $repo 'mvnw.cmd')) {
        return 'mvnw.cmd only - the first build downloads Maven (~9 MB)'
    }
    return $null
} 'No Maven and no mvnw.cmd. Unzip Apache Maven anywhere you can write and set $SecMavenHome in sec-env.local.ps1 - see docs\RUNNING.md section 1.2.'

Test-Sec 'Project model' {
    # Both poms, and the root one has to list the backend module. A checkout that lost either
    # fails much later, inside a build log, saying something about a missing parent.
    $rootPom = Join-Path $repo 'pom.xml'
    $backendPom = Join-Path $repo 'backend\pom.xml'
    if (-not (Test-Path $rootPom)) { return $null }
    if (-not (Test-Path $backendPom)) { return $null }
    if (-not (Select-String -Path $rootPom -Pattern '<module>backend</module>' -Quiet)) { return $null }
    return 'pom.xml + backend\pom.xml'
} 'pom.xml or backend\pom.xml is missing. Both are committed: git checkout -- pom.xml backend/pom.xml'

Test-Sec 'Local repository' {
    # Absent is not broken - it is a machine that has not built yet. Report the size, because
    # "the build downloads everything again every time" is usually this being somewhere else.
    $m2 = $env:MAVEN_REPO_LOCAL
    if (-not $m2) { $m2 = Join-Path $env:USERPROFILE '.m2\repository' }
    if (-not (Test-Path $m2)) { return 'empty - the first build populates it' }
    $count = @(Get-ChildItem -Path $m2 -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue).Count
    return "$count jars cached"
} 'Could not read the local repository.' -WarnOnly

Test-Sec 'Node 22+' {
    $version = Get-CommandVersion -Exe 'node'
    if (-not $version) { return $null }
    $major = [int] (($version -replace '^v', '') -split '\.')[0]
    if ($major -lt 22) { return $null }
    return $version
} 'Node 22 or newer is required by Angular 22. Install it, or add it to PATH.'

Test-Sec 'npm' {
    Get-CommandVersion -Exe 'npm'
} 'npm not on PATH.'

Test-Sec 'Python 3.11+' {
    $version = Get-CommandVersion -Exe 'python'
    if (-not $version) { return $null }
    $parts = ($version -replace 'Python\s+', '') -split '\.'
    if ([int] $parts[0] -lt 3) { return $null }
    if ([int] $parts[0] -eq 3 -and [int] $parts[1] -lt 11) { return $null }
    return $version
} 'Python 3.11+ not on PATH. The importers need it; the backend and frontend do not.'

# --- Dependencies actually installed -------------------------------------------------------

Test-Sec 'Frontend deps' {
    if (Test-Path (Join-Path $repo 'frontend\node_modules\@angular\core')) { return 'node_modules present' }
    return $null
} 'Not installed. Run: scripts\win\sec-frontend.ps1 -Install' -WarnOnly

Test-Sec 'Importer venv' {
    $python = Join-Path $repo 'importers\.venv\Scripts\python.exe'
    if (Test-Path $python) { return '.venv present' }
    return $null
} 'Not created. Run: scripts\win\sec-importers-setup.ps1' -WarnOnly

Test-Sec 'neo4j driver (py)' {
    $python = Join-Path $repo 'importers\.venv\Scripts\python.exe'
    if (-not (Test-Path $python)) { $python = 'python' }
    $out = & $python -c "import neo4j; print(neo4j.__version__)"
    if ($LASTEXITCODE -ne 0) { return $null }
    return "neo4j $out"
} 'The Python neo4j driver is not importable. Run: scripts\win\sec-importers-setup.ps1' -WarnOnly

# --- Neo4j ---------------------------------------------------------------------------------

Test-Sec 'Neo4j install' {
    if ($env:NEO4J_HOME -and (Test-Path (Join-Path $env:NEO4J_HOME 'bin\neo4j.bat'))) {
        return $env:NEO4J_HOME
    }
    return $null
} 'NEO4J_HOME unset or wrong. Set $SecNeo4jHome in sec-env.local.ps1.'

Test-Sec 'Neo4j running' {
    if (Test-Port -Port 7687) { return 'bolt on 7687' }
    return $null
} 'Not listening on 7687. Start it: scripts\win\sec-neo4j.ps1 (leave that window open).' -WarnOnly

Test-Sec 'Neo4j credentials' {
    if ($env:SEC_NEO4J_USER -and $env:SEC_NEO4J_PASSWORD) { return "user $env:SEC_NEO4J_USER" }
    return $null
} 'SEC_NEO4J_USER / SEC_NEO4J_PASSWORD unset. The backend refuses to start without them.'

# --- The application ------------------------------------------------------------------------

Test-Sec 'Backend running' {
    if (Test-Port -Port 8080) { return 'listening on 8080' }
    return $null
} 'Not running. Start it: scripts\win\sec-backend.ps1' -WarnOnly

Test-Sec 'Frontend running' {
    if (Test-Port -Port 4200) { return 'listening on 4200' }
    return $null
} 'Not running. Start it: scripts\win\sec-frontend.ps1' -WarnOnly

# --- Network ---------------------------------------------------------------------------------

Test-Sec 'Proxy' {
    if ($env:HTTPS_PROXY) { return $env:HTTPS_PROXY }
    return $null
} 'No proxy set. Fine if this machine has direct access; otherwise set $SecProxy.' -WarnOnly

Test-Sec 'pip index' {
    if ($env:PIP_INDEX_URL) { return $env:PIP_INDEX_URL }
    return $null
} 'Using pypi.org. Set $SecPipIndexUrl to the company mirror if pypi.org is unreachable.' -WarnOnly

Write-Host ''
if ($script:Failures -eq 0 -and $script:Warnings -eq 0) {
    Write-Host '  Everything checks out.' -ForegroundColor Green
} elseif ($script:Failures -eq 0) {
    Write-Host "  Usable. $($script:Warnings) warning(s) above - each one is something not started or not installed yet, not something broken." -ForegroundColor Yellow
} else {
    Write-Host "  $($script:Failures) failure(s) and $($script:Warnings) warning(s). The failures have to be fixed first." -ForegroundColor Red
}
Write-Host ''
