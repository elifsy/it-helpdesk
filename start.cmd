@echo off
chcp 65001 >nul
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo   [ERROR] Node.js not found in PATH.
  echo   Install Node.js 18+ or add it to PATH, then run again.
  echo.
  pause
  exit /b 1
)

if not exist "logs" mkdir "logs"

node "scripts\start.mjs" %*
set EXITCODE=%errorlevel%

if not "%EXITCODE%"=="0" (
  echo.
  echo   Exit code: %EXITCODE%
  echo   See logs\server.log and logs\web.log for details.
  echo.
  pause
)
exit /b %EXITCODE%
