@rem Lightweight Gradle Wrapper script for Windows (honors JAVA_HOME and gradle-wrapper.jar)
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set WRAPPER_JAR=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
    where gradle >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        call gradle wrapper --gradle-version 9.8.2 --no-daemon
    ) else (
        echo Error: gradle\wrapper\gradle-wrapper.jar is missing and 'gradle' is not on PATH. 1>&2
        exit /b 1
    )
)
set JAVA_EXE=java.exe
if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\bin\java.exe
"%JAVA_EXE%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
