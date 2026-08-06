<#
.SYNOPSIS
    Creates importers\.venv and installs the importer package into it.

.DESCRIPTION
        scripts\win\sec-importers-setup.ps1            # venv + editable install + dev tools
        scripts\win\sec-importers-setup.ps1 -NoDev     # runtime only, no pytest
        scripts\win\sec-importers-setup.ps1 -Recreate   # delete the venv and start again

    pip is pointed at PIP_INDEX_URL when sec-env.ps1 set one, so this works on a machine
    whose only package source is the company mirror.

    If the mirror cannot serve setuptools and the editable install fails, you are not stuck:
    the importer needs exactly one third-party package, the neo4j driver, and sec-import-doors.ps1
    falls back to running it straight from importers\src. See docs\RUNNING.md, "If pip cannot
    install the package".

.PARAMETER NoDev
    Skip pytest and pytest-cov. Choose this if the mirror does not carry them; the importer
    itself runs fine without them, you just cannot run its tests.

.PARAMETER Recreate
    Delete importers\.venv first.
#>
[CmdletBinding()]
param(
    [switch] $NoDev,
    [switch] $Recreate
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$importers = Join-Path $repo 'importers'
$venv = Join-Path $importers '.venv'
$venvPython = Join-Path $venv 'Scripts\python.exe'

if ($Recreate -and (Test-Path $venv)) {
    Write-Host "  Removing $venv" -ForegroundColor Yellow
    Remove-Item -Recurse -Force $venv
}

if (-not (Test-Path $venvPython)) {
    Write-Host '  Creating the virtual environment (no network needed for this step) ...'
    python -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw 'python -m venv failed. Is Python 3.11+ on PATH?' }
}

# Index arguments, built once and reused. --trusted-host is only added when it was asked
# for: adding it unconditionally would quietly disable certificate checking.
$indexArgs = New-Object System.Collections.Generic.List[string]
if ($env:PIP_INDEX_URL) {
    $indexArgs.Add('--index-url'); $indexArgs.Add($env:PIP_INDEX_URL)
    Write-Host "  index     $env:PIP_INDEX_URL" -ForegroundColor Gray
}
if ($env:PIP_TRUSTED_HOST) {
    $indexArgs.Add('--trusted-host'); $indexArgs.Add($env:PIP_TRUSTED_HOST)
}

$target = '.'
if (-not $NoDev) { $target = '.[dev]' }

Push-Location $importers
try {
    Write-Host "  Installing $target ..."
    & $venvPython -m pip install --disable-pip-version-check @indexArgs -e $target
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host '  The editable install failed.' -ForegroundColor Red
        Write-Host '  This is usually the mirror refusing setuptools or a build backend, not your code.' -ForegroundColor Yellow
        Write-Host '  Try: sec-importers-setup.ps1 -NoDev, and if that also fails, see' -ForegroundColor Yellow
        Write-Host '  docs\RUNNING.md - "If pip cannot install the package". The importer runs from' -ForegroundColor Yellow
        Write-Host '  source with only the neo4j driver installed.' -ForegroundColor Yellow
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '  Installed. Checking it imports ...' -ForegroundColor Gray
& $venvPython -c "import sec_import.doors.cli; import neo4j; print('  sec_import ok, neo4j driver', neo4j.__version__)"
if ($LASTEXITCODE -ne 0) { throw 'The package installed but does not import. That is a real problem - do not ignore it.' }

Write-Host ''
Write-Host '  Next:' -ForegroundColor White
Write-Host '    scripts\win\sec-import-doors.ps1 -Test        run the importer test suite'
Write-Host '    scripts\win\sec-import-doors.ps1 -Smoke       parse the bundled fixture, write nothing'
Write-Host '    scripts\win\sec-import-doors.ps1 import <file.json>'
Write-Host ''
