@echo off
REM ============================================================
REM  mini-agent launcher
REM
REM  NOTE: comments in this file are ASCII on purpose.
REM  cmd.exe mis-parses batch files containing UTF-8 Chinese text,
REM  so all Chinese help text is printed by the Java program:
REM      run.bat --help
REM ============================================================

chcp 65001 >nul
set MAVEN_OPTS=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8

setlocal
cd /d "%~dp0"

REM No argument -> offline demo (no network, no cost)
if "%~1"=="" (
    set ARGS=--mock
) else (
    set ARGS=%*
)

echo Running: mvn exec:java -Dexec.args="%ARGS%"
call mvn -q exec:java -Dexec.args="%ARGS%"
if errorlevel 1 (
    echo.
    echo Failed. Try "mvn compile" first to see the real error.
    pause
)
endlocal
