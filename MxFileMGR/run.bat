@echo off
rem Runs MxFileMGR from its built product, building it first when it has not been built.
rem   run.bat [--build] [MxFileMGR arguments...]
rem --build rebuilds first; the arguments go to MxFileMGR (a copy that is already running takes them over).
call "%~dp0..\releng\tools\mxapp.cmd" run MxFileMGR mxfilemgr 8003 %*
exit /b %ERRORLEVEL%
