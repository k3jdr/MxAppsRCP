@echo off
rem Runs MxNoteONE from its built product, building it first when it has not been built.
rem   run.bat [--build] [MxNoteONE arguments...]
rem --build rebuilds first; the arguments go to MxNoteONE (a copy that is already running takes them over).
call "%~dp0..\releng\tools\mxapp.cmd" run MxNoteONE mxnoteone 8006 %*
exit /b %ERRORLEVEL%
