<#
.SYNOPSIS
    Starts Neo4j Community in the console, in the foreground.

.DESCRIPTION
    This machine runs Neo4j from the console rather than as a Windows service, so this
    window IS the database: closing it, or pressing Ctrl+C, stops Neo4j. Leave it open and
    use another terminal for everything else.

    Neo4j 2026.x needs JDK 21 or newer and reads JAVA_HOME. Dot-source sec-env.ps1 first, or
    pass -JavaHome.

.PARAMETER JavaHome
    Use this JDK instead of the one already in the environment.

.PARAMETER Status
    Print whether something is already listening on 7687, and exit without starting anything.
#>
[CmdletBinding()]
param(
    [string] $JavaHome,
    [switch] $Status
)

$ErrorActionPreference = 'Stop'

# Probes IPv4 and IPv6. Neo4j binds 127.0.0.1 by default, so IPv4 alone happens to work
# today - but a conf that moves it to IPv6 would make this script cheerfully start a second
# instance on a bound port. See sec-ports.ps1.
. "$PSScriptRoot\sec-ports.ps1"

function Test-BoltPort {
    return Test-SecPort -Port 7687
}

if ($Status) {
    if (Test-BoltPort) {
        Write-Host 'Neo4j is listening on 7687.' -ForegroundColor Green
    } else {
        Write-Host 'Nothing is listening on 7687.' -ForegroundColor Yellow
    }
    return
}

if ($JavaHome) { $env:JAVA_HOME = $JavaHome }

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. Dot-source scripts\win\sec-env.ps1 first, or pass -JavaHome.'
}
if (-not $env:NEO4J_HOME) {
    throw 'NEO4J_HOME is not set. Dot-source scripts\win\sec-env.ps1 first, or set $SecNeo4jHome in sec-env.local.ps1.'
}

$neo4jBat = Join-Path $env:NEO4J_HOME 'bin\neo4j.bat'
if (-not (Test-Path $neo4jBat)) {
    throw "No neo4j.bat under $env:NEO4J_HOME. Is NEO4J_HOME pointing at the install directory?"
}

# Starting a second instance fails with a port-binding error several screens into the log,
# which reads as a broken installation rather than as "it is already running".
if (Test-BoltPort) {
    Write-Host ''
    Write-Host '  Something is already listening on 7687 - Neo4j is very likely already up.' -ForegroundColor Yellow
    Write-Host '  Starting a second instance will fail on a port bind deep in the log. Stop the' -ForegroundColor Yellow
    Write-Host '  other console window first if you really do want to restart it.' -ForegroundColor Yellow
    Write-Host ''
    return
}

Write-Host ''
Write-Host "  Neo4j     $env:NEO4J_HOME"
Write-Host "  JDK       $env:JAVA_HOME"
Write-Host '  Bolt      neo4j://localhost:7687     Browser  http://localhost:7474'
Write-Host ''
Write-Host '  This window is the database. Ctrl+C stops it.' -ForegroundColor Cyan
Write-Host ''

& $neo4jBat console
exit $LASTEXITCODE
