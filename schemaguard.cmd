@echo off
setlocal

set "JAR_PATH=schemaguard-cli\target\schemaguard-cli-1.0-SNAPSHOT.jar"

if not exist "%JAR_PATH%" (
    echo SchemaGuard CLI jar not found. Building project...
    call mvn clean package -DskipTests
)

java -jar "%JAR_PATH%" %*