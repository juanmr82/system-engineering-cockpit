@echo off
rem Thin wrapper: resolve the interpreter, force UTF-8, call the module, propagate the exit
rem code. No business logic here, ever (CLAUDE.md section 10).
rem
rem Works from any working directory, and whether or not the package was ever pip-installed:
rem %~dp0 is this file's own directory, so importers\src is found relative to the script
rem rather than relative to wherever the user happens to be standing.

setlocal
chcp 65001 >nul
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8

set "IMPORTERS=%~dp0.."
set "PYTHONPATH=%IMPORTERS%\src;%PYTHONPATH%"

rem Prefer the project virtual environment; fall back to the Python on PATH.
set "PY=%IMPORTERS%\.venv\Scripts\python.exe"
if not exist "%PY%" set "PY=python"

"%PY%" -m sec_import.doors.cli %*
exit /b %ERRORLEVEL%
