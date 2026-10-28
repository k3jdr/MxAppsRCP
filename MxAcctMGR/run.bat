@echo off
rem Runs MxAcctMGR from its built product, building it first when it has not been built.
rem   run.bat [--build] [MxAcctMGR arguments...]
rem --build rebuilds first; the arguments go to MxAcctMGR (a copy that is already running takes them over).
call "%~dp0..\releng\tools\mxapp.cmd" run MxAcctMGR mxacctmgr 8005 %*
exit /b %ERRORLEVEL%
