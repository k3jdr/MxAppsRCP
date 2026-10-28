@echo off
rem Runs MxNoteONE for debugging: its log in this console and a Java debugger (JDWP) on localhost:8006.
rem   debug.bat [--build] [--port N] [--suspend] [--osgi-console] [MxNoteONE arguments...]
rem Attach from Eclipse: Run > Debug Configurations > Remote Java Application, Host localhost, Port 8006.
rem --suspend waits for the debugger before MxNoteONE starts; --osgi-console adds the OSGi console here.
call "%~dp0..\releng\tools\mxapp.cmd" debug MxNoteONE mxnoteone 8006 %*
exit /b %ERRORLEVEL%
