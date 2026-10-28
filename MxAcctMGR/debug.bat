@echo off
rem Runs MxAcctMGR for debugging: its log in this console and a Java debugger (JDWP) on localhost:8005.
rem   debug.bat [--build] [--port N] [--suspend] [--osgi-console] [MxAcctMGR arguments...]
rem Attach from Eclipse: Run > Debug Configurations > Remote Java Application, Host localhost, Port 8005.
rem --suspend waits for the debugger before MxAcctMGR starts; --osgi-console adds the OSGi console here.
call "%~dp0..\releng\tools\mxapp.cmd" debug MxAcctMGR mxacctmgr 8005 %*
exit /b %ERRORLEVEL%
