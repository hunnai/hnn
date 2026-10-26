@echo off
setlocal EnableExtensions DisableDelayedExpansion
title SoinuBider XL - Instalar archivos completos
set "PAQUETE=%~dp0"
echo.
echo Cierra Android Studio antes de continuar.
echo Este programa guarda una copia antes de sustituir los archivos.
echo.
set "PROYECTO=%~1"
if not defined PROYECTO set /p "PROYECTO=Ruta de tu proyecto SoinuBiderXL: "
if not defined PROYECTO goto ruta_error
set "PROYECTO=%PROYECTO:"=%"
for %%I in ("%PROYECTO%") do set "PROYECTO=%%~fI"
if not exist "%PROYECTO%\gradlew.bat" goto ruta_error
if not exist "%PROYECTO%\app\build.gradle.kts" goto ruta_error
if not exist "%PAQUETE%app\src\main\cpp\SBAudioAndroid.cpp" goto paquete_error
if not exist "%PAQUETE%app\src\main\java\com\example\soinubiderxl\MainActivity.kt" goto paquete_error
if not exist "%PAQUETE%app\src\main\res\drawable-nodpi\soinu_launcher_face.png" goto paquete_error
set "BACKUP=%PROYECTO%-copia-soinu-%RANDOM%-%RANDOM%"
if exist "%BACKUP%" goto copia_error
mkdir "%BACKUP%" >nul 2>&1
if errorlevel 1 goto copia_error
call :copia_carpeta "%PROYECTO%\app\src\main\java\com\example\soinubiderxl" "%BACKUP%\app\src\main\java\com\example\soinubiderxl"
if errorlevel 1 goto copia_error
call :copia_carpeta "%PROYECTO%\app\src\main\cpp" "%BACKUP%\app\src\main\cpp"
if errorlevel 1 goto copia_error
call :copia_archivo "%PROYECTO%\app\src\main\AndroidManifest.xml" "%BACKUP%\app\src\main\AndroidManifest.xml"
if errorlevel 1 goto copia_error
call :copia_archivo "%PROYECTO%\app\build.gradle.kts" "%BACKUP%\app\build.gradle.kts"
if errorlevel 1 goto copia_error

call :copia_carpeta "%PROYECTO%\app\src\main\res" "%BACKUP%\app\src\main\res"
if errorlevel 1 goto copia_error

echo Copia de seguridad verificada. Instalando...
if exist "%PROYECTO%\app\src\main\java\com\example\soinubiderxl" rmdir /s /q "%PROYECTO%\app\src\main\java\com\example\soinubiderxl"
if exist "%PROYECTO%\app\src\main\java\com\example\soinubiderxl" goto instalacion_error
if exist "%PROYECTO%\app\src\main\cpp" rmdir /s /q "%PROYECTO%\app\src\main\cpp"
if exist "%PROYECTO%\app\src\main\cpp" goto instalacion_error
call :copia_carpeta "%PAQUETE%app\src\main\java\com\example\soinubiderxl" "%PROYECTO%\app\src\main\java\com\example\soinubiderxl"
if errorlevel 1 goto instalacion_error
call :copia_carpeta "%PAQUETE%app\src\main\cpp" "%PROYECTO%\app\src\main\cpp"
if errorlevel 1 goto instalacion_error
call :copia_archivo "%PAQUETE%app\src\main\AndroidManifest.xml" "%PROYECTO%\app\src\main\AndroidManifest.xml"
if errorlevel 1 goto instalacion_error
call :copia_archivo "%PAQUETE%app\build.gradle.kts" "%PROYECTO%\app\build.gradle.kts"
if errorlevel 1 goto instalacion_error
echo.
call :mezcla_recursos "%PAQUETE%app\src\main\res" "%PROYECTO%\app\src\main\res"
if errorlevel 1 goto instalacion_error
echo Archivos instalados correctamente.
echo Copia anterior: "%BACKUP%"
echo Abre tu proyecto habitual en Android Studio, sincroniza y ejecuta Build.
echo Despues pulsa Run. No abras este paquete como proyecto nuevo.
pause
exit /b 0

:copia_carpeta
if not exist "%~1" exit /b 0
robocopy "%~1" "%~2" /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS >nul
if errorlevel 8 exit /b 1
robocopy "%~1" "%~2" /E /L /R:0 /W:0 /NFL /NDL /NJH /NJS >nul
if errorlevel 1 exit /b 1
exit /b 0

:mezcla_recursos
if not exist "%~1" exit /b 1
robocopy "%~1" "%~2" /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS >nul
if errorlevel 8 exit /b 1
robocopy "%~1" "%~2" /E /L /XX /R:0 /W:0 /NFL /NDL /NJH /NJS >nul
if errorlevel 1 exit /b 1
exit /b 0

:copia_archivo
if not exist "%~1" exit /b 0
for %%I in ("%~2") do if not exist "%%~dpI" mkdir "%%~dpI" >nul 2>&1
copy /b /y "%~1" "%~2" >nul
if errorlevel 1 exit /b 1
fc /b "%~1" "%~2" >nul
if errorlevel 1 exit /b 1
exit /b 0

:ruta_error
echo No se encuentra el proyecto. Indica la carpeta que contiene gradlew.bat y app.
pause
exit /b 1
:paquete_error
echo Extrae TODO el ZIP antes de ejecutar este programa.
pause
exit /b 1
:copia_error
echo No se pudo completar la copia. No se han sustituido los originales.
echo Comprueba permisos, espacio disponible y que Android Studio este cerrado.
pause
exit /b 1
:instalacion_error
echo No se pudo terminar la instalacion.
echo Los archivos originales estan conservados en "%BACKUP%".
echo Sigue PARA DESHACER LA INSTALACION en LEEME.txt antes de abrir el proyecto.
pause
exit /b 1
