@echo off
rem Runs MxDataHUB for debugging: its log in this console and a Java debugger (JDWP) on localhost:8002.
rem   debug.bat [--build] [--port N] [--suspend] [--osgi-console] [MxDataHUB arguments...]
rem Attach from Eclipse: Run > Debug Configurations > Remote Java Application, Host localhost, Port 8002.
rem --suspend waits for the debugger before MxDataHUB starts; --osgi-console adds the OSGi console here.
call "%~dp0..\releng\tools\mxapp.cmd" debug MxDataHUB mxdatahub 8002 %*
exit /b %ERRORLEVEL%
