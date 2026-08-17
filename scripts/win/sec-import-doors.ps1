<#
.SYNOPSIS
    Runs the DOORS importer, its tests, or a parse-only smoke test.

.DESCRIPTION
        scripts\win\sec-import-doors.ps1 -Smoke
        scripts\win\sec-import-doors.ps1 -Test
        scripts\win\sec-import-doors.ps1 import C:\exports\SRD_000969a2_current.json --report run.json
        scripts\win\sec-import-doors.ps1 import <file> --dry-run
        scripts\win\sec-import-doors.ps1 init-schema
        scripts\win\sec-import-doors.ps1 validate

    Anything that is not -Smoke or -Test is passed through to the importer's own CLI
    untouched, so every flag it grows works here without this script changing.

    It picks importers\.venv when that exists and falls back to the Python on PATH, with
    importers\src on PYTHONPATH either way - so the importer runs whether or not pip was
    ever able to install it.

    Credentials come from NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD, which sec-env.ps1 sets
    from the same values the backend uses. Without them the CLI prompts.

    A real `import` run (not --dry-run) calls the backend's POST /access/reconcile afterwards
    (docs/features/access-control.md §8.3), scoped to `doors`, so a module's category reaches
    its objects without waiting for the backend's own startup pass. This runs out-of-process on
    a box that may not have the backend running at all, so a failure here is a warning, not an
    error (§8.3's own words) - the objects stay invisible, which is safe, and the next backend
    startup or a manual reconcile catches them.

.PARAMETER Smoke
    Parse and derive the bundled fixture with --dry-run. Touches no database. This is the
    fastest way to prove Python, the package layout and the parser are all working.

.PARAMETER Test
    Run the importer test suite (needs pytest, so install without -NoDev).

.PARAMETER BackendUrl
    Where the backend's POST /access/reconcile lives. Defaults to SEC_BACKEND_URL, or the
    dev default sec-backend.ps1 starts on. Only used after a real import run.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch] $Smoke,
    [switch] $Test,
    [string] $BackendUrl = $(if ($env:SEC_BACKEND_URL) { $env:SEC_BACKEND_URL } else { 'http://localhost:8080' }),
    # Not $Args: that shadows PowerShell's own automatic variable, which is legal, confusing,
    # and the kind of thing that breaks silently when someone edits this later.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Passthrough
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$importers = Join-Path $repo 'importers'

$python = Join-Path $importers '.venv\Scripts\python.exe'
if (-not (Test-Path $python)) {
    $python = 'python'
    Write-Host '  No .venv - using the Python on PATH.' -ForegroundColor Yellow
}

# DOORS attribute names contain umlauts, and the default Windows codepage corrupts them on
# the way in and on the way out. Both variables, every time.
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'

# Works installed or not: an editable install already puts src on the path, and prepending
# it again is harmless.
$srcPath = Join-Path $importers 'src'
if ($env:PYTHONPATH) {
    $env:PYTHONPATH = "$srcPath;$env:PYTHONPATH"
} else {
    $env:PYTHONPATH = $srcPath
}

# access-control.md §8.3: "a failure there is a warning, not an error" - this call reaches a
# backend that may not be running at all on this box, and it is not this script's job to demand
# one. Never touches $LASTEXITCODE, which is what makes it safe to call after every real import
# without changing what a caller (or CI) sees as the run's own outcome.
function Invoke-DoorsAccessReconcile {
    param([string] $BackendUrl)

    $uri = "$BackendUrl/api/v1/access/reconcile?scope=source&source=doors"
    try {
        $response = Invoke-RestMethod -Method Post -Uri $uri -TimeoutSec 30
        $doors = $response.sources | Where-Object { $_.sourceId -eq 'doors' }
        Write-Host "  Access categories reconciled: +$($doors.propagated) propagated, -$($doors.retracted) retracted, $($doors.seeded) seeded" -ForegroundColor Cyan
    } catch {
        Write-Host "  Could not reconcile access categories ($BackendUrl): $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host '  Objects imported this run stay invisible until the backend reconciles them - at its own startup, or via a manual call.' -ForegroundColor Yellow
    }
}

Push-Location $importers
try {
    if ($Test) {
        & $python -m pytest -q
        exit $LASTEXITCODE
    }

    if ($Smoke) {
        $fixture = Join-Path $importers 'tests\fixtures\smoke_module_current.json'
        Write-Host ''
        Write-Host '  Parsing the bundled fixture with --dry-run. Nothing is written to any database.' -ForegroundColor Cyan
        Write-Host ''
        & $python -m sec_import.doors.cli import $fixture --dry-run
        exit $LASTEXITCODE
    }

    if (-not $Passthrough -or $Passthrough.Count -eq 0) {
        Write-Host ''
        Write-Host '  Nothing to do. Try -Smoke, -Test, or a CLI command:' -ForegroundColor Yellow
        Write-Host ''
        & $python -m sec_import.doors.cli --help
        exit 1
    }

    # The importer logs to stderr, as logging.basicConfig does. Do not pipe this through
    # `2>&1`: Windows PowerShell 5.1 wraps every native stderr line in an ErrorRecord and
    # reports a successful run as a NativeCommandError failure.
    & $python -m sec_import.doors.cli @Passthrough
    $exitCode = $LASTEXITCODE

    # Only a real import wrote anything a category could apply to - not --dry-run, not
    # init-schema, not validate, and not a failed run.
    $isRealImport = ($Passthrough[0] -eq 'import') -and ($Passthrough -notcontains '--dry-run')
    if ($exitCode -eq 0 -and $isRealImport) {
        Invoke-DoorsAccessReconcile -BackendUrl $BackendUrl
    }

    exit $exitCode
} finally {
    Pop-Location
}
