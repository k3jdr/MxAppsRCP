@echo off
rem Runs MxDataHUB from its built product, building it first when it has not been built.
rem   run.bat [--build] [MxDataHUB arguments...]
rem --build rebuilds first; the arguments go to MxDataHUB (a copy that is already running takes them over).
call "%~dp0..\releng\tools\mxapp.cmd" run MxDataHUB mxdatahub 8002 %*
exit /b %ERRORLEVEL%
