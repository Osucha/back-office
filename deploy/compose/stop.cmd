@echo off
rem Stops the Back Office containers; data stays in the Docker volumes (start.cmd brings everything back).
cd /d "%~dp0"
docker compose -f docker-compose.yml --profile full stop
pause
