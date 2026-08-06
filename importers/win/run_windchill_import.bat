@echo off
rem Thin wrapper: resolve the interpreter, force UTF-8, call the module, propagate the exit
rem code. No business logic here, ever (CLAUDE.md section 10). Same shape as
rem run_doors_import.bat -- see the comment there for why the paths are script-relative.

setlocal
chcp 65001 >nul
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8

set "IMPORTERS=%~dp0.."
set "PYTHONPATH=%IMPORTERS%\src;%PYTHONPATH%"

set "PY=%IMPORTERS%\.venv\Scripts\python.exe"
if not exist "%PY%" set "PY=python"

"%PY%" -m sec_import.windchill.cli %*
exit /b %ERRORLEVEL%
