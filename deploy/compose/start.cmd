@echo off
rem Back Office in Docker, one step (ADR 0046): database, object storage, api, worker, web and the runner fleet.
rem Double-click this file or run it from any directory. The first start builds the images (several minutes).
setlocal
cd /d "%~dp0"

docker info >nul 2>&1
if errorlevel 1 (
  echo Docker is not running. Start Docker Desktop and run this again.
  pause
  exit /b 1
)

docker compose -f docker-compose.yml --profile full up -d --build
if errorlevel 1 (
  echo Starting the stack failed; see the messages above.
  pause
  exit /b 1
)

echo.
echo Waiting for Back Office on http://localhost:8080 ...
set /a tries=0
:wait
set /a tries+=1
curl -s -o nul -w "%%{http_code}" http://localhost:8080/api/v1/auth/config 2>nul | findstr /r "^200$" >nul
if not errorlevel 1 goto ready
if %tries% geq 120 goto slow
timeout /t 5 /nobreak >nul
goto wait

:slow
echo Back Office did not answer within 10 minutes; check: docker compose -f "%~dp0docker-compose.yml" logs api
pause
exit /b 1

:ready
echo.
echo Back Office runs on http://localhost:8080
echo Sign in with admin@example.com / ChangeMe-Local-123 (you set your own password at the first sign-in).
echo Stop it with stop.cmd next to this file.
start "" http://localhost:8080
endlocal
