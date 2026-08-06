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

.PARAMETER Smoke
    Parse and derive the bundled fixture with --dry-run. Touches no database. This is the
    fastest way to prove Python, the package layout and the parser are all working.

.PARAMETER Test
    Run the importer test suite (needs pytest, so install without -NoDev).
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch] $Smoke,
    [switch] $Test,
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
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
