@echo off
title DocuScan AI — Launcher
color 0A

echo.
echo  ============================================================
echo   DocuScan AI — Enterprise OCR Engine
echo   Starting Backend + Frontend simultaneously...
echo  ============================================================
echo.

REM Check tessdata exists
if not exist "%~dp0backend\tessdata\eng.traineddata" (
    echo  [WARNING] eng.traineddata not found!
    echo  Running setup first...
    echo.
    call "%~dp0setup.bat"
)

echo  Launching Spring Boot backend in a new window...
start "DocuScan - Backend (port 8080)" cmd /k "%~dp0start-backend.bat"

timeout /t 3 /nobreak >nul

echo  Launching React frontend in a new window...
start "DocuScan - Frontend (port 5173)" cmd /k "%~dp0start-frontend.bat"

echo.
echo  ============================================================
echo   Both servers are starting up!
echo.
echo   Backend  : http://localhost:8080/api/v1/health
echo   Frontend : http://localhost:5173
echo.
echo   Wait ~30 seconds for Spring Boot to fully start,
echo   then open http://localhost:5173 in your browser.
echo  ============================================================
echo.

timeout /t 8 /nobreak >nul

REM Open browser after a short delay
start "" "http://localhost:5173"

pause
