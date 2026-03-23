@echo off
title DocuScan AI — Setup
color 0A
echo.
echo  ============================================================
echo   DocuScan AI — Enterprise OCR Engine — First-Time Setup
echo  ============================================================
echo.

REM ── Set JAVA_HOME and Maven paths ──────────────────────────────
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set MAVEN_HOME=C:\maven\apache-maven-3.9.14-bin\apache-maven-3.9.14
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

echo [1/4] Verifying Java...
java -version
if %errorlevel% neq 0 (
    echo ERROR: Java not found at %JAVA_HOME%
    pause & exit /b 1
)

echo.
echo [2/4] Verifying Maven...
mvn -version
if %errorlevel% neq 0 (
    echo ERROR: Maven not found at %MAVEN_HOME%
    pause & exit /b 1
)

echo.
echo [3/4] Downloading Tesseract language data (eng.traineddata ~12MB)...
set TESSDATA_DIR=%~dp0backend\tessdata
if not exist "%TESSDATA_DIR%" mkdir "%TESSDATA_DIR%"

if exist "%TESSDATA_DIR%\eng.traineddata" (
    echo     eng.traineddata already exists — skipping download.
) else (
    echo     Downloading from GitHub tessdata repo...
    curl -L "https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata" -o "%TESSDATA_DIR%\eng.traineddata"
    if %errorlevel% neq 0 (
        echo.
        echo  ERROR: Could not download tessdata automatically.
        echo  Please manually download eng.traineddata from:
        echo  https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata
        echo  And place it in: %TESSDATA_DIR%\
        pause & exit /b 1
    )
    echo     Downloaded successfully!
)

echo.
echo [4/4] Installing frontend dependencies...
cd /d "%~dp0frontend"
call npm install
if %errorlevel% neq 0 (
    echo ERROR: npm install failed
    pause & exit /b 1
)

echo.
echo  ============================================================
echo   Setup COMPLETE! You can now run start-all.bat
echo  ============================================================
echo.
pause
