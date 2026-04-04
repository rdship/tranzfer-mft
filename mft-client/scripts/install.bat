@echo off
REM =============================================================================
REM TranzFer MFT Client — Installer (Windows)
REM =============================================================================
echo.
echo   TranzFer MFT Client Installer
echo   ===============================
echo.

REM Check Java
java -version >/dev/null 2>&1
if %errorlevel% neq 0 (
    echo   ERROR: Java not found. Install Java 21+ from:
    echo   https://adoptium.net/temurin/releases/?version=21
    pause
    exit /b 1
)
echo   Java found.

REM Set install directory
set "INSTALL_DIR=%USERPROFILE%\mft-client"
echo   Installing to: %INSTALL_DIR%

mkdir "%INSTALL_DIR%" 2>/dev/null
mkdir "%INSTALL_DIR%\outbox" 2>/dev/null
mkdir "%INSTALL_DIR%\inbox" 2>/dev/null
mkdir "%INSTALL_DIR%\sent" 2>/dev/null
mkdir "%INSTALL_DIR%\failed" 2>/dev/null

REM Copy JAR
if exist "target\mft-client-1.0.0-SNAPSHOT.jar" (
    copy "target\mft-client-1.0.0-SNAPSHOT.jar" "%INSTALL_DIR%\mft-client.jar" >/dev/null
) else if exist "mft-client.jar" (
    copy "mft-client.jar" "%INSTALL_DIR%\mft-client.jar" >/dev/null
) else (
    echo   ERROR: Cannot find mft-client.jar. Build first with Maven.
    pause
    exit /b 1
)

REM Create launcher
echo @echo off > "%INSTALL_DIR%\mft-client.bat"
echo cd "%%~dp0" >> "%INSTALL_DIR%\mft-client.bat"
echo java -jar "%%~dp0mft-client.jar" %%* >> "%INSTALL_DIR%\mft-client.bat"

REM Create default config if missing
if not exist "%INSTALL_DIR%\mft-client.yml" (
    echo server: > "%INSTALL_DIR%\mft-client.yml"
    echo   protocol: SFTP >> "%INSTALL_DIR%\mft-client.yml"
    echo   host: YOUR_SERVER_HOST >> "%INSTALL_DIR%\mft-client.yml"
    echo   port: 2222 >> "%INSTALL_DIR%\mft-client.yml"
    echo   username: YOUR_USERNAME >> "%INSTALL_DIR%\mft-client.yml"
    echo   password: YOUR_PASSWORD >> "%INSTALL_DIR%\mft-client.yml"
    echo   timeoutSeconds: 30 >> "%INSTALL_DIR%\mft-client.yml"
    echo   autoRetry: true >> "%INSTALL_DIR%\mft-client.yml"
    echo   maxRetries: 3 >> "%INSTALL_DIR%\mft-client.yml"
    echo folders: >> "%INSTALL_DIR%\mft-client.yml"
    echo   outbox: ./outbox >> "%INSTALL_DIR%\mft-client.yml"
    echo   inbox: ./inbox >> "%INSTALL_DIR%\mft-client.yml"
    echo   sent: ./sent >> "%INSTALL_DIR%\mft-client.yml"
    echo   failed: ./failed >> "%INSTALL_DIR%\mft-client.yml"
    echo   remoteInbox: /inbox >> "%INSTALL_DIR%\mft-client.yml"
    echo   remoteOutbox: /outbox >> "%INSTALL_DIR%\mft-client.yml"
    echo sync: >> "%INSTALL_DIR%\mft-client.yml"
    echo   watchOutbox: true >> "%INSTALL_DIR%\mft-client.yml"
    echo   pollInbox: true >> "%INSTALL_DIR%\mft-client.yml"
    echo   pollIntervalSeconds: 30 >> "%INSTALL_DIR%\mft-client.yml"
    echo   deleteAfterDownload: true >> "%INSTALL_DIR%\mft-client.yml"
    echo clientName: mft-client >> "%INSTALL_DIR%\mft-client.yml"
    echo logLevel: INFO >> "%INSTALL_DIR%\mft-client.yml"
)

echo.
echo   Installation complete!
echo.
echo   Next steps:
echo     1. Open %INSTALL_DIR%
echo     2. Edit mft-client.yml with your server details
echo     3. Double-click mft-client.bat to start
echo     4. Drop files into outbox\ to transfer
echo.

REM Optional: create Windows service
echo   To run as a Windows service, use NSSM:
echo     nssm install MFT-Client java -jar "%INSTALL_DIR%\mft-client.jar"
echo.
pause
