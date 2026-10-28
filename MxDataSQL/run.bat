@echo off
rem Runs MxDataSQL from its built product, building it first when it has not been built.
rem   run.bat [--build] [MxDataSQL arguments...]
rem --build rebuilds first; the arguments go to MxDataSQL (a copy that is already running takes them over).
call "%~dp0..\releng\tools\mxapp.cmd" run MxDataSQL mxdatasql 8001 %*
exit /b %ERRORLEVEL%
