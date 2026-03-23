@echo off
title DocuScan AI — Rebuilding...
color 0E

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set PATH=%JAVA_HOME%\bin;C:\maven\apache-maven-3.9.14-bin\apache-maven-3.9.14\bin;%PATH%

cd /d "%~dp0backend"

echo Building with text formatting fixes...
echo.
mvn package -DskipTests --no-transfer-progress > rebuild-log.txt 2>&1

if %errorlevel% equ 0 (
    color 0A
    echo  ==============================================
    echo   BUILD SUCCESS!
    echo   1. Close the blue backend window (Ctrl+C, Y)
    echo   2. Double-click start-backend.bat to restart
    echo   Frontend already updated automatically.
    echo  ==============================================
) else (
    color 0C
    echo  BUILD FAILED - errors below:
    echo.
    findstr /i "ERROR" rebuild-log.txt
)
echo.
pause
