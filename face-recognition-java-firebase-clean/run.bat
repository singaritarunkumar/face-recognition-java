@echo off
mvn -q -DskipTests package
if errorlevel 1 exit /b 1
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
if errorlevel 1 exit /b 1
for /f "usebackq delims=" %%i in ("cp.txt") do set CP=%%i
java -cp "target/classes;%CP%" app.Server %1
