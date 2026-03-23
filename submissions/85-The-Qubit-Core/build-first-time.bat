@echo off
title DocuScan AI — Building Backend...
color 0B

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set MAVEN_HOME=C:\maven\apache-maven-3.9.14-bin\apache-maven-3.9.14
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

cd /d "%~dp0backend"

echo.
echo  ============================================================
echo   DocuScan AI - First Time Build
echo   Downloading dependencies and compiling...
echo   DO NOT CLOSE THIS WINDOW.
echo   This takes 3-5 minutes on first run only.
echo  ============================================================
echo.

mvn package -DskipTests --no-transfer-progress > build-log.txt 2>&1

echo.
if %errorlevel% equ 0 (
    color 0A
    echo  ============================================================
    echo   SUCCESS! Backend is ready.
    echo   You can now run start-all.bat to launch the app.
    echo  ============================================================
    echo.
    echo  Press any key to close this window...
    pause >nul
) else (
    color 0C
    echo  ============================================================
    echo   BUILD FAILED. Showing error details:
    echo  ============================================================
    echo.
    type build-log.txt
    echo.
    echo  Press any key to close...
    pause >nul
)
