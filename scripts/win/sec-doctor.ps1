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

function Test-Port {
    param([int] $Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(700)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
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

Test-Sec 'Gradle wrapper' {
    $wrapper = Join-Path $repo 'gradlew.bat'
    if (Test-Path $wrapper) { return 'gradlew.bat' }
    return $null
} 'gradlew.bat missing from the repository root.'

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
