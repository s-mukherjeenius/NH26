@echo off
title DocuScan AI — Frontend (React)
color 0D

echo.
echo  ============================================================
echo   DocuScan AI — Frontend starting on http://localhost:5173
echo   DO NOT close this window while using the app.
echo  ============================================================
echo.

cd /d "%~dp0frontend"
npm run dev

echo.
echo  Frontend stopped. Press any key to close.
pause >nul
