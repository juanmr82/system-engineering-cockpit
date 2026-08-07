<#
.SYNOPSIS
    Runs the Angular dev server on :4200, installs its dependencies, or runs its quality gate.

.DESCRIPTION
        scripts\win\sec-frontend.ps1 -Install    # npm ci (or npm install without a lockfile)
        scripts\win\sec-frontend.ps1             # ng serve
        scripts\win\sec-frontend.ps1 -Build      # ng build -> frontend\dist, for packaging
        scripts\win\sec-frontend.ps1 -Gate       # lint + test + build, the full gate

    Every npm command runs from frontend\, never from the repository root with --prefix:
    --prefix also changes where npm install writes, which silently produces
    frontend\frontend\node_modules and leaves package.json untouched.

.PARAMETER Install
    Install dependencies. Uses the registry in NPM_CONFIG_REGISTRY when sec-env.ps1 set one.

.PARAMETER Build
    Production build only. sec-package.ps1 runs this before folding dist into the backend jar.

.PARAMETER Gate
    Run lint, tests and a production build - what has to pass before calling work done.
#>
[CmdletBinding()]
param(
    [switch] $Install,
    [switch] $Build,
    [switch] $Gate
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$frontend = Join-Path $repo 'frontend'

Push-Location $frontend
try {
    if ($Install) {
        if ($env:NPM_CONFIG_REGISTRY) {
            Write-Host "  registry  $env:NPM_CONFIG_REGISTRY" -ForegroundColor Gray
        }
        # npm ci needs a lockfile and is the reproducible one; fall back rather than fail on
        # a fresh checkout that has not got one yet.
        if (Test-Path (Join-Path $frontend 'package-lock.json')) {
            npm ci
        } else {
            Write-Host '  No package-lock.json - falling back to npm install.' -ForegroundColor Yellow
            npm install
        }
        exit $LASTEXITCODE
    }

    if ($Build) {
        npm run build
        exit $LASTEXITCODE
    }

    if ($Gate) {
        npm run lint
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        npm test
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        npm run build
        exit $LASTEXITCODE
    }

    if (-not (Test-Path (Join-Path $frontend 'node_modules\@angular\core'))) {
        throw 'Dependencies are not installed. Run: scripts\win\sec-frontend.ps1 -Install'
    }

    Write-Host ''
    Write-Host '  Frontend  http://localhost:4200'
    Write-Host '  API       proxied to the backend on :8080 - start that too, or every view is empty.'
    Write-Host ''
    npm start
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
