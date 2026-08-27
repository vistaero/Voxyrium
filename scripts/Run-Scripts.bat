@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Voxy - Scripts

:menu
cls
echo ========================================
echo          Scripts de compatibilidad
echo ========================================
echo.

set "count=0"
for %%F in ("%~dp0*.ps1") do (
    set /a count+=1
    set "script[!count!]=%%~nxF"
    echo [!count!] %%~nxF
)

if !count! EQU 0 (
    echo No hay scripts PowerShell en esta carpeta.
    echo.
    pause
    exit /b 1
)

echo.
echo [Q] Salir
echo.
set "choice="
set /p "choice=Selecciona un script: "

if /I "!choice!"=="Q" exit /b 0
for /f "delims=0123456789" %%A in ("!choice!") do goto invalid
if "!choice!"=="" goto invalid
if !choice! LSS 1 goto invalid
if !choice! GTR !count! goto invalid

for %%N in (!choice!) do set "selected=!script[%%N]!"
echo.
echo Ejecutando !selected!...
echo.
pushd "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0!selected!"
set "result=!errorlevel!"
popd
echo.
if not "!result!"=="0" echo El script termino con codigo !result!.
pause
goto menu

:invalid
echo.
echo Seleccion no valida.
pause
goto menu
