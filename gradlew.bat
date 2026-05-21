@echo off
rem Minimal Gradle wrapper launcher placeholder for Windows.
set DIR=%~dp0
if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  echo [quickauth-sdk-android] gradle-wrapper.jar missing. Run: gradle wrapper --gradle-version 8.5
  exit /b 1
)
java -jar "%DIR%gradle\wrapper\gradle-wrapper.jar" %*
