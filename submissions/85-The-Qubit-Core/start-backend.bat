@echo off
title DocuScan AI — Backend (Spring Boot)
color 0B

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

echo.
echo  ============================================================
echo   DocuScan AI — Backend starting on http://localhost:8080
echo   DO NOT close this window while using the app.
echo  ============================================================
echo.

cd /d "%~dp0backend"
java -jar target\ocr-engine-1.0.0.jar --ocr.tessdata.path=tessdata

echo.
echo  Server stopped. Press any key to close.
pause >nul
