@echo off
REM ============================================================
REM  mycode launcher
REM
REM  Usage:
REM    mycode                  interactive mode
REM    mycode "question"       one-shot mode
REM    mycode --demo           offline demo (fake model, no cost)
REM    mycode --help           full help
REM
REM  NOTE: comments here are ASCII on purpose. cmd.exe mis-parses
REM  batch files containing UTF-8 Chinese text; all Chinese output
REM  comes from the Java program instead.
REM ============================================================

chcp 65001 >nul
setlocal

REM %~dp0 always ends with a backslash. Strip it, otherwise the
REM trailing \" would escape the closing quote in -Dmycode.home.
set "APP_DIR=%~dp0"
if "%APP_DIR:~-1%"=="\" set "APP_DIR=%APP_DIR:~0,-1%"
set "JAR=%APP_DIR%\target\mycode.jar"

REM Build on first run so the command works right after cloning
if not exist "%JAR%" (
    echo [mycode] first run, building jar...
    call mvn -q -f "%APP_DIR%\pom.xml" package -DskipTests
    if errorlevel 1 (
        echo [mycode] build failed. Run "mvn package" to see details.
        pause
        exit /b 1
    )
)

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dmycode.home="%APP_DIR%" -jar "%JAR%" %*

endlocal
