@echo off
chcp 65001 >nul
echo.
echo   Stopping services on ports 8080 / 5173 ...
echo.

for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
  taskkill /pid %%p /T /F >nul 2>nul
  echo   killed pid %%p
)
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":5173" ^| findstr "LISTENING"') do (
  taskkill /pid %%p /T /F >nul 2>nul
  echo   killed pid %%p
)

echo.
echo   Done. You can also just press Ctrl+C in the start window.
echo.
pause
