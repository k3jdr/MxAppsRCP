@echo off
rem Runs MxFileMGR for debugging: its log in this console and a Java debugger (JDWP) on localhost:8003.
rem   debug.bat [--build] [--port N] [--suspend] [--osgi-console] [MxFileMGR arguments...]
rem Attach from Eclipse: Run > Debug Configurations > Remote Java Application, Host localhost, Port 8003.
rem --suspend waits for the debugger before MxFileMGR starts; --osgi-console adds the OSGi console here.
call "%~dp0..\releng\tools\mxapp.cmd" debug MxFileMGR mxfilemgr 8003 %*
exit /b %ERRORLEVEL%
