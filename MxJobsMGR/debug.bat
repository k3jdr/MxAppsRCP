@echo off
rem Runs MxJobsMGR for debugging: its log in this console and a Java debugger (JDWP) on localhost:8004.
rem   debug.bat [--build] [--port N] [--suspend] [--osgi-console] [MxJobsMGR arguments...]
rem Attach from Eclipse: Run > Debug Configurations > Remote Java Application, Host localhost, Port 8004.
rem --suspend waits for the debugger before MxJobsMGR starts; --osgi-console adds the OSGi console here.
call "%~dp0..\releng\tools\mxapp.cmd" debug MxJobsMGR mxjobsmgr 8004 %*
exit /b %ERRORLEVEL%
