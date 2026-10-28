@echo off
rem ---------------------------------------------------------------------------------------------------------------
rem  MxAppsRCP: build, run or debug one app's Windows product. Each app folder's run.bat and debug.bat call this.
rem
rem    mxapp.cmd run   <App> <id> <port> [--build] [app arguments...]
rem    mxapp.cmd debug <App> <id> <port> [--build] [--port N] [--suspend] [--osgi-console] [app arguments...]
rem
rem  run    starts <App>.exe (a copy that is already running takes the arguments over, as on macOS).
rem  debug  starts <App>c.exe in this console with the log shown here (-consoleLog) and a Java debugger (JDWP)
rem         listening on localhost:<port>. Attach from Eclipse (Run > Debug Configurations > Remote Java
rem         Application), IntelliJ IDEA (Remote JVM Debug) or VS Code (Java: attach).
rem
rem  --build         rebuild the app first (mvn verify, offline when it can); it is built anyway when missing
rem  --port N        debug on port N instead of the app's own
rem  --suspend       wait for the debugger before starting (to debug start-up)
rem  --osgi-console  also open the OSGi (Gogo) console in this window
rem ---------------------------------------------------------------------------------------------------------------
setlocal EnableExtensions

rem The repository root, from this script's folder (before SHIFT, which moves %0 too).
for %%I in ("%~dp0..\..") do set "ROOT=%%~fI"

set "MODE=%~1"
set "APP=%~2"
set "ID=%~3"
set "PORT=%~4"
if "%APP%"=="" goto usage
shift & shift & shift & shift

set "APPDIR=%ROOT%\%APP%"
set "PRODUCT=%APPDIR%\com.k3jdr.%ID%.product\target\products\com.k3jdr.%ID%.product\win32\win32\x86_64\%APP%"
set "EXE=%PRODUCT%\%APP%.exe"
set "CONSOLE_EXE=%PRODUCT%\%APP%c.exe"

set "BUILD=0"
set "SUSPEND=n"
set "OSGI="
set "ARGS="

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--build" goto opt_build
if /i "%~1"=="--port" goto opt_port
if /i "%~1"=="--suspend" goto opt_suspend
if /i "%~1"=="--osgi-console" goto opt_osgi
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h" goto usage
set ARGS=%ARGS% %1
shift
goto parse
:opt_build
set "BUILD=1"
shift
goto parse
:opt_port
set "PORT=%~2"
shift
shift
goto parse
:opt_suspend
set "SUSPEND=y"
shift
goto parse
:opt_osgi
set "OSGI=-console"
shift
goto parse
:parsed

rem ---- Java: the launcher uses JAVA_HOME's JVM when it is set, else the first Java 21 on the PATH ------------------
set "VM="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\server\jvm.dll" set VM=-vm "%JAVA_HOME%\bin\server\jvm.dll"
if not defined VM (
    where java >nul 2>nul || (
        echo %APP% needs Java 21: install a JDK or JRE 21 and put it on the PATH, or set JAVA_HOME.
        exit /b 1
    )
)

rem ---- Build when asked or when there is no product yet ---------------------------------------------------------
if "%BUILD%"=="1" goto build
if not exist "%EXE%" goto build
goto launch

:build
where mvn >nul 2>nul || (
    echo Maven was not found on the PATH. Install Maven 3.9 to build %APP%, or build it in Eclipse.
    exit /b 1
)
echo Building %APP% ...
call mvn -B -q -o -f "%APPDIR%\pom.xml" clean verify
if not errorlevel 1 goto launch
echo The offline build failed; installing the target platform and Shared, then building %APP% online ...
call mvn -B -q -f "%ROOT%\pom.xml" install -pl releng/com.k3jdr.mx.target,Shared,Shared/com.k3jdr.mx.core,Shared/com.k3jdr.mx.shared.feature
if errorlevel 1 goto build_failed
call mvn -B -q -f "%APPDIR%\pom.xml" clean verify
if errorlevel 1 goto build_failed
goto launch

:build_failed
echo.
echo %APP% did not build. Run   mvn -f "%APPDIR%\pom.xml" clean verify   to see why.
exit /b 1

rem ---- Launch -----------------------------------------------------------------------------------------------------
:launch
if not exist "%EXE%" (
    echo %APP% is not built: %EXE% is missing.
    exit /b 1
)
if /i "%MODE%"=="run" goto run
if /i "%MODE%"=="debug" goto debug
goto usage

:run
start "" "%EXE%" %VM% %ARGS%
exit /b 0

:debug
tasklist /fi "imagename eq %APP%.exe" 2>nul | find /i "%APP%.exe" >nul && (
    echo %APP% is already running. Close it first: a second copy would hand its arguments to that one and exit,
    echo and the two would share one settings folder.
    exit /b 1
)
echo Debugging %APP%: Java debugger on localhost:%PORT% (suspend=%SUSPEND%), log below. Close the window or press Ctrl+C to stop.
echo Attach: Eclipse  Run ^> Debug Configurations ^> Remote Java Application ^> Host localhost, Port %PORT%
echo.
"%CONSOLE_EXE%" %VM% --launcher.appendVmargs -consoleLog %OSGI% %ARGS% -vmargs -agentlib:jdwp=transport=dt_socket,server=y,suspend=%SUSPEND%,address=localhost:%PORT%
exit /b %ERRORLEVEL%

:usage
echo usage: run.bat   [--build] [app arguments...]
echo        debug.bat [--build] [--port N] [--suspend] [--osgi-console] [app arguments...]
exit /b 2
