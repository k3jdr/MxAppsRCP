@echo off
rem MxDataHUB's Data Refresh on a schedule (Windows Task Scheduler), on the PC that hosts the security master.
rem   srm-refresh.cmd status                     the task, the last refresh, which secrets are saved
rem   srm-refresh.cmd install [--time 18:30] [--days mon-fri]
rem   srm-refresh.cmd run [--dry-run] [--all] [--categories a,b]
rem   srm-refresh.cmd uninstall
rem Needs Python 3.9+ with psycopg2 (the SRM loaders' own needs). Details: srm_refresh.py and ..\README.md.
where python >nul 2>nul || (echo Python 3 is not on the PATH. 1>&2 & exit /b 1)
python "%~dp0srm_refresh.py" %*
exit /b %ERRORLEVEL%
