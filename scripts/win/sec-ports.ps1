<#
.SYNOPSIS
    Is something listening on a local port? Dot-source this; it defines one function.

.DESCRIPTION
    Every script here needs to answer "is Neo4j up", "is the backend up", "is the dev server
    up", and getting it wrong in the same way three times is what this file exists to stop.

    THE TRAP: a service that listens on localhost may be bound to IPv4, to IPv6, or to both,
    and which one is not ours to choose. Measured on a running stack:

        Neo4j        127.0.0.1   IPv4 only
        the backend  ::          dual-stack, so IPv4 works
        ng serve     ::1         IPv6 ONLY

    A probe that only connects to 127.0.0.1 therefore reports a perfectly healthy Angular dev
    server as down. That is not hypothetical - it is why sec-doctor.ps1 used to print
    "Frontend running: Not running" while the site was open in a browser, and it made
    sec-up.ps1 start a second dev server that could not bind the port it wanted.

    So: try both families, and report up if either answers.
#>

function Test-SecPort {
    param(
        [Parameter(Mandatory = $true)][int] $Port,
        [int] $TimeoutMs = 700
    )

    foreach ($literal in @('127.0.0.1', '::1')) {
        $address = [System.Net.IPAddress]::Parse($literal)
        # The client has to be created for the right address family: a default TcpClient is
        # IPv4 and throws when handed an IPv6 address rather than simply failing to connect.
        $client = New-Object System.Net.Sockets.TcpClient($address.AddressFamily)
        try {
            $async = $client.BeginConnect($address, $Port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne($TimeoutMs)) {
                $client.EndConnect($async)
                return $true
            }
        } catch {
            # Refused, unreachable, or no IPv6 stack at all. Try the other family.
        } finally {
            $client.Close()
        }
    }
    return $false
}
