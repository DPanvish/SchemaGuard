#!/usr/bin/env bash
set -e

JAR_PATH="schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "SchemaGuard CLI jar not found. Building project..."
    mvn clean package -DskipTests
fi

java -jar "$JAR_PATH" "$@"