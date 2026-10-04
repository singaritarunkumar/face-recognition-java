#!/usr/bin/env bash
set -e
mvn -q -DskipTests package
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
exec java -cp "target/classes:$(cat cp.txt)" app.Server "${1:-8080}"
