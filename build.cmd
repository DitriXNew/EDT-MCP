@echo off
setlocal enabledelayedexpansion

rem EDT MCP Server Plugin build script
rem Usage: build.cmd [EDT_INSTALL_DIR]
rem Example: build.cmd "C:\Program Files\1C\1CE\components\1c-edt-2025.1.5+34-x86_64"
rem
rem If EDT_INSTALL_DIR is not passed, the script tries to auto-detect it.

set "TARGET_FILE=%~dp0mcp\targets\default\default.target"
set "BACKUP_FILE=%~dp0mcp\targets\default\default.target.bak"
set "EDT_DIR=%~1"

rem Auto-detect EDT if not specified
if "%EDT_DIR%"=="" (
    for /d %%d in ("C:\Program Files\1C\1CE\components\1c-edt-*") do (
        set "EDT_DIR=%%d"
    )
)

if "%EDT_DIR%"=="" (
    echo ERROR: EDT installation not found.
    echo Usage: build.cmd "C:\path\to\1c-edt-directory"
    exit /b 1
)

if not exist "%EDT_DIR%\plugins" (
    echo ERROR: Invalid EDT directory: %EDT_DIR%
    echo The directory must contain a "plugins" subdirectory.
    exit /b 1
)

echo Using EDT: %EDT_DIR%

rem Backup original target
copy /y "%TARGET_FILE%" "%BACKUP_FILE%" >nul

rem Insert Directory location before </locations>
powershell -Command "(Get-Content '%TARGET_FILE%' -Raw) -replace '</locations>', ('<!-- EDT Runtime (auto-added by build script) -->' + [Environment]::NewLine + '<location path=\"%EDT_DIR%\" type=\"Directory\"/>' + [Environment]::NewLine + [Environment]::NewLine + '</locations>') | Set-Content '%TARGET_FILE%' -NoNewline"

echo Building...
cd /d "%~dp0mcp"
call mvn clean verify
set "BUILD_RESULT=%ERRORLEVEL%"

rem Restore original target
copy /y "%BACKUP_FILE%" "%TARGET_FILE%" >nul
del "%BACKUP_FILE%" >nul 2>&1

if %BUILD_RESULT%==0 (
    echo.
    echo BUILD SUCCESS
    echo P2 repository: mcp\repositories\com.ditrix.edt.mcp.server.repository\target\repository\
    echo ZIP archive:    mcp\repositories\com.ditrix.edt.mcp.server.repository\target\*.zip
) else (
    echo.
    echo BUILD FAILED
)

exit /b %BUILD_RESULT%
