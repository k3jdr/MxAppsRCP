@echo off
rem Runs MxDataSQL for debugging: its log in this console and a Java debugger (JDWP) on localhost:8001.
rem   debug.bat [--build] [--port N] [--suspend] [--osgi-console] [MxDataSQL arguments...]
rem Attach from Eclipse: Run > Debug Configurations > Remote Java Application, Host localhost, Port 8001.
rem --suspend waits for the debugger before MxDataSQL starts; --osgi-console adds the OSGi console here.
call "%~dp0..\releng\tools\mxapp.cmd" debug MxDataSQL mxdatasql 8001 %*
exit /b %ERRORLEVEL%
