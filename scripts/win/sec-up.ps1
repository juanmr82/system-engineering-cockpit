<#
.SYNOPSIS
    Starts the whole cockpit - Neo4j, the backend and the frontend - from one command.

.DESCRIPTION
        scripts\win\sec-up.ps1              # start everything from source, open the browser
        scripts\win\sec-up.ps1 -Jar         # run the packaged jar instead - one process
        scripts\win\sec-up.ps1 -Status      # what is up, what is not
        scripts\win\sec-up.ps1 -Stop        # stop everything this started
        scripts\win\sec-up.ps1 -NoFrontend  # database + API only
        scripts\win\sec-up.ps1 -NoBrowser

    Each of the three still runs in its own window, because each one IS its process: its log
    is in that window and Ctrl+C there stops it. What this script removes is having to open
    three terminals, dot-source the environment three times, and know the order.

    It dot-sources sec-env.ps1 itself, checks everything it needs BEFORE opening any window -
    three windows that each die instantly is the worst way to learn a password is missing -
    and waits for each service to answer before starting the next.

    Anything already listening on its port is left strictly alone. Starting a second Neo4j on
    a bound port fails several screens into a log and reads as a broken installation.

.PARAMETER Status
    Report what is listening and exit. Changes nothing.

.PARAMETER Stop
    Stop the three services and close the windows this script opened.

.PARAMETER Jar
    Run backend\target\*-all.jar instead of building from source, and serve the UI from the jar
    rather than from a dev server. Two processes instead of three, and the shape a deployed
    machine runs. Build it first with scripts\win\sec-package.ps1.

.PARAMETER NoFrontend
    Skip the dev server. For backend work, where ng serve is just a slow window. Implied by
    -Jar, which serves the UI from the jar itself.

.PARAMETER NoBrowser
    Do not open http://localhost:4200 at the end.
#>
[CmdletBinding()]
param(
    [switch] $Status,
    [switch] $Stop,
    [switch] $Jar,
    [switch] $NoFrontend,
    [switch] $NoBrowser
)

$ErrorActionPreference = 'Stop'
$scripts = $PSScriptRoot
$repo = Split-Path -Parent (Split-Path -Parent $scripts)

# Window titles are the handle -Stop uses to close what it opened, so they are declared once.
$TitlePrefix = 'SEC'
$Services = @(
    [pscustomobject]@{ Key = 'neo4j';    Name = 'Neo4j';    Port = 7687; Title = "$TitlePrefix - Neo4j" }
    [pscustomobject]@{ Key = 'backend';  Name = 'Backend';  Port = 8080; Title = "$TitlePrefix - Backend" }
    [pscustomobject]@{ Key = 'frontend'; Name = 'Frontend'; Port = 4200; Title = "$TitlePrefix - Frontend" }
)

. "$scripts\sec-ports.ps1"   # Test-SecPort, which probes IPv4 and IPv6 - see that file

# The backend answers /health before it is useful and before the graph is reachable - that is
# what liveness means - so this is a "the process is serving" check, not "the app works".
# Proxy is nulled explicitly: a corporate proxy in the environment must never be consulted for
# localhost, and PowerShell 5.1 will happily do so.
function Test-SecHttp {
    param([string] $Url)
    try {
        $request = [System.Net.HttpWebRequest]::Create($Url)
        $request.Proxy = $null
        $request.Timeout = 3000
        $response = $request.GetResponse()
        $code = [int] $response.StatusCode
        $response.Close()
        return ($code -ge 200 -and $code -lt 500)
    } catch {
        return $false
    }
}

function Wait-SecService {
    param(
        [string] $Name,
        [int] $Port,
        [int] $TimeoutSeconds,
        [string] $HealthUrl
    )
    Write-Host ("  waiting for {0} " -f $Name) -NoNewline
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $up = if ($HealthUrl) { Test-SecHttp -Url $HealthUrl } else { Test-SecPort -Port $Port }
        if ($up) {
            Write-Host ' ok' -ForegroundColor Green
            return $true
        }
        Write-Host '.' -NoNewline
        Start-Sleep -Seconds 2
    }
    Write-Host ' timed out' -ForegroundColor Red
    return $false
}

function Get-SecListener {
    param([int] $Port)
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $conn) { return $null }
    return Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
}

# The console window hosting a service, found by walking up from the listening process.
#
# Window TITLES cannot do this job: a console started by Start-Process reports an empty
# MainWindowTitle, so matching on one silently finds nothing and -Stop leaves every window
# open. The parent chain is real. The command-line test is what keeps this honest - only a
# powershell.exe that is demonstrably running one of our service scripts is a candidate, so
# -Stop can never close a terminal somebody was using for something else.
#
# Call this BEFORE stopping the listener, while the chain still exists.
function Get-SecHostWindow {
    param([int] $ListenerPid)

    $current = $ListenerPid
    for ($hop = 0; $hop -lt 6; $hop++) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$current" -ErrorAction SilentlyContinue
        if (-not $proc) { return $null }
        if ($proc.Name -eq 'powershell.exe' -and $proc.CommandLine -match 'sec-(neo4j|backend|frontend)\.ps1') {
            return [int] $proc.ProcessId
        }
        if (-not $proc.ParentProcessId -or $proc.ParentProcessId -eq 0) { return $null }
        $current = [int] $proc.ParentProcessId
    }
    return $null
}

function Start-SecWindow {
    param([string] $Title, [string] $ScriptPath, [string[]] $ScriptArgs = @())

    # Each window sets up its own environment. Dot-sourcing here would not reach a child
    # process, and passing the resolved values as arguments would mean this script owning a
    # copy of what sec-env.ps1 already decides.
    $argText = ($ScriptArgs | ForEach-Object { " '$_'" }) -join ''
    $command = "`$host.UI.RawUI.WindowTitle = '$Title'; " +
               ". '$scripts\sec-env.ps1' -Quiet; " +
               "& '$ScriptPath'$argText"

    # -NoExit so a process that dies on startup leaves its error on screen instead of a window
    # that blinks and is gone.
    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $command
    ) | Out-Null
}

# ---------------------------------------------------------------------------------------
# -Status
# ---------------------------------------------------------------------------------------
if ($Status) {
    Write-Host ''
    Write-Host '  System Engineering Cockpit - what is running' -ForegroundColor White
    Write-Host ''
    foreach ($svc in $Services) {
        $proc = Get-SecListener -Port $svc.Port
        Write-Host ('  {0,-10} :{1,-6} ' -f $svc.Name, $svc.Port) -NoNewline
        if ($proc) {
            Write-Host ("up   pid {0} ({1})" -f $proc.Id, $proc.ProcessName) -ForegroundColor Green
        } else {
            Write-Host 'down' -ForegroundColor Yellow
        }
    }
    Write-Host ''
    return
}

# ---------------------------------------------------------------------------------------
# -Stop
# ---------------------------------------------------------------------------------------
if ($Stop) {
    Write-Host ''
    $stopped = 0
    $closed = 0
    # Reverse order: the frontend and backend should go before the database they talk to.
    foreach ($svc in ($Services | Sort-Object { $_.Port } -Descending)) {
        $proc = Get-SecListener -Port $svc.Port
        if (-not $proc) {
            Write-Host ('  {0,-10} already down' -f $svc.Name) -ForegroundColor Gray
            continue
        }

        # Find the window first: stopping the listener breaks the chain that identifies it.
        $window = Get-SecHostWindow -ListenerPid $proc.Id

        Write-Host ('  stopping {0,-10} pid {1}' -f $svc.Name, $proc.Id) -ForegroundColor Yellow
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        $stopped++

        # The window is held open by -NoExit, so it would otherwise linger empty.
        if ($window) {
            Stop-Process -Id $window -Force -ErrorAction SilentlyContinue
            $closed++
        }
    }

    if ($closed -gt 0) {
        Write-Host ("  closed {0} window(s)" -f $closed) -ForegroundColor Gray
    }

    Write-Host ''
    if ($stopped -eq 0) { Write-Host '  Nothing was running.' -ForegroundColor Gray }
    else { Write-Host "  Stopped $stopped service(s)." -ForegroundColor Green }
    Write-Host ''
    return
}

# ---------------------------------------------------------------------------------------
# Start. Everything is checked before anything is launched.
# ---------------------------------------------------------------------------------------
. "$scripts\sec-env.ps1" -Quiet

# The jar serves the UI itself, so there is no dev server in this mode.
if ($Jar) { $NoFrontend = $true }

$problems = New-Object System.Collections.Generic.List[string]

if (-not $env:JAVA_HOME) {
    $problems.Add('No JDK 21+. Set $SecJavaHome in sec-env.local.ps1 - see docs\RUNNING.md section 1.2.')
}
if (-not $env:SEC_NEO4J_USER -or -not $env:SEC_NEO4J_PASSWORD) {
    $problems.Add('SEC_NEO4J_USER / SEC_NEO4J_PASSWORD are not set. Copy sec-env.local.ps1 to sec-env.local.ps1 and fill them in - the backend refuses to start without them.')
}
if (-not (Test-SecPort -Port 7687) -and -not $env:NEO4J_HOME) {
    $problems.Add('Neo4j is not running and NEO4J_HOME is not set. Set $SecNeo4jHome in sec-env.local.ps1.')
}
if (-not $env:SEC_MVN -and -not (Test-Path (Join-Path $repo 'mvnw.cmd'))) {
    $problems.Add('No Maven and no mvnw.cmd. See docs\RUNNING.md section 2.6.')
}
if (-not $NoFrontend -and -not (Test-Path (Join-Path $repo 'frontend\node_modules\@angular\core'))) {
    $problems.Add('Frontend dependencies are not installed. Run: scripts\win\sec-frontend.ps1 -Install')
}
if ($Jar) {
    $packaged = @(Get-ChildItem -Path (Join-Path $repo 'backend\target') -Filter '*-all.jar' -ErrorAction SilentlyContinue)
    if ($packaged.Count -eq 0) {
        $problems.Add('No packaged jar in backend\target. Build one: scripts\win\sec-package.ps1')
    }
}

if ($problems.Count -gt 0) {
    Write-Host ''
    Write-Host '  Cannot start. Nothing was launched.' -ForegroundColor Red
    Write-Host ''
    foreach ($problem in $problems) { Write-Host "    - $problem" -ForegroundColor Yellow }
    Write-Host ''
    exit 1
}

Write-Host ''
Write-Host '  Starting the System Engineering Cockpit' -ForegroundColor White
Write-Host ''

# --- Neo4j -------------------------------------------------------------------------------
if (Test-SecPort -Port 7687) {
    Write-Host '  Neo4j      already listening on 7687, left alone' -ForegroundColor Gray
} else {
    Write-Host '  Neo4j      starting in its own window'
    Start-SecWindow -Title ($Services | Where-Object Key -eq 'neo4j').Title -ScriptPath "$scripts\sec-neo4j.ps1"
    if (-not (Wait-SecService -Name 'Neo4j' -Port 7687 -TimeoutSeconds 120)) {
        Write-Host ''
        Write-Host '  Neo4j did not come up. Its window has the log - it is usually the JDK version' -ForegroundColor Red
        Write-Host '  or a stale lock in the data directory.' -ForegroundColor Red
        Write-Host ''
        exit 1
    }
}

# --- Backend -----------------------------------------------------------------------------
if (Test-SecPort -Port 8080) {
    Write-Host '  Backend    already listening on 8080, left alone' -ForegroundColor Gray
    Write-Host '             (it serves the code it started with - stop it first if you changed any)' -ForegroundColor Gray
} else {
    if ($Jar) {
        Write-Host '  Backend    starting the packaged jar in its own window (UI included)'
        Start-SecWindow -Title ($Services | Where-Object Key -eq 'backend').Title -ScriptPath "$scripts\sec-backend.ps1" -ScriptArgs @('-Jar')
    } else {
        Write-Host '  Backend    starting in its own window'
        Start-SecWindow -Title ($Services | Where-Object Key -eq 'backend').Title -ScriptPath "$scripts\sec-backend.ps1"
    }
    # Generous: a cold local repository means Maven resolves dependencies before compiling.
    if (-not (Wait-SecService -Name 'Backend' -Port 8080 -TimeoutSeconds 300 -HealthUrl 'http://localhost:8080/api/v1/health')) {
        Write-Host ''
        Write-Host '  The backend did not answer /health. Its window has the log - a wrong Neo4j' -ForegroundColor Red
        Write-Host '  password fails there, at startup, by design.' -ForegroundColor Red
        Write-Host ''
        exit 1
    }
}

# --- Frontend ----------------------------------------------------------------------------
if (-not $NoFrontend) {
    if (Test-SecPort -Port 4200) {
        Write-Host '  Frontend   already listening on 4200, left alone' -ForegroundColor Gray
    } else {
        Write-Host '  Frontend   starting in its own window'
        Start-SecWindow -Title ($Services | Where-Object Key -eq 'frontend').Title -ScriptPath "$scripts\sec-frontend.ps1"
        # ng serve's first build is the slowest thing here by a wide margin.
        if (-not (Wait-SecService -Name 'Frontend' -Port 4200 -TimeoutSeconds 300)) {
            Write-Host ''
            Write-Host '  The dev server did not come up. Its window has the log.' -ForegroundColor Red
            Write-Host ''
            exit 1
        }
    }
}

# --- Summary -----------------------------------------------------------------------------
Write-Host ''
Write-Host '  Up:' -ForegroundColor Green
Write-Host '    Neo4j     neo4j://localhost:7687     browser  http://localhost:7474'
if ($Jar) {
    Write-Host '    Backend   http://localhost:8080          the UI is served from here too'
} else {
    Write-Host '    Backend   http://localhost:8080/api/v1/health'
}
if (-not $NoFrontend) {
    Write-Host '    Frontend  http://localhost:4200'
}
Write-Host ''
Write-Host '  Each runs in its own window. Ctrl+C there stops one; sec-up.ps1 -Stop stops all.'
Write-Host '  The backend serves the code it started with - restart it after any backend change.' -ForegroundColor Cyan
Write-Host ''

if (-not $NoBrowser) {
    # In -Jar mode the whole product is on 8080; otherwise the dev server is the front door.
    if ($Jar) { Start-Process 'http://localhost:8080' }
    elseif (-not $NoFrontend) { Start-Process 'http://localhost:4200' }
}
