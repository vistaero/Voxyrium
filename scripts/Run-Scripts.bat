@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Voxy - Scripts

:menu
cls
echo ========================================
echo          Compatibility Scripts
echo ========================================
echo.

set "count=0"
for %%F in ("%~dp0*.ps1") do (
    set /a count+=1
    set "script[!count!]=%%~nxF"
    echo [!count!] %%~nxF
)

if !count! EQU 0 (
    echo No PowerShell scripts found in this folder.
    echo.
    pause
    exit /b 1
)

echo.
echo [Q] Quit
echo.
set "choice="
set /p "choice=Select a script: "

if /I "!choice!"=="Q" exit /b 0
for /f "delims=0123456789" %%A in ("!choice!") do goto invalid
if "!choice!"=="" goto invalid
if !choice! LSS 1 goto invalid
if !choice! GTR !count! goto invalid

for %%N in (!choice!) do set "selected=!script[%%N]!"
echo.
echo Running !selected!...
echo.
pushd "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0!selected!"
set "result=!errorlevel!"
popd
echo.
if not "!result!"=="0" echo The script ended with exit code !result!.
pause
goto menu

:invalid
echo.
echo Invalid selection.
pause
goto menu
