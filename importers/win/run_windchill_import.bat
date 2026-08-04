@echo off
setlocal
chcp 65001 >nul
set PYTHONIOENCODING=utf-8
python -m sec_import.windchill.cli %*
exit /b %ERRORLEVEL%
