#!/usr/bin/env sh
set -eu
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec java -jar "$BASE_DIR/target/mcp-debugger-tools-java-0.1.0-SNAPSHOT.jar"
