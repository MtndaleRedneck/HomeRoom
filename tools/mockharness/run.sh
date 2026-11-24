#!/bin/bash
# Build + run the mock-HTTP contract harness. Requires: kotlinc, java, and the
# jars listed below (paths overridable via JARS_DIR).
set -e
JARS_DIR=${JARS_DIR:-~/b/jars}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/../../app/src/main/java/app/homeroom
CP=$JARS_DIR/okhttp.jar:$JARS_DIR/okio.jar:$JARS_DIR/coroutines.jar:$JARS_DIR/json.jar:$JARS_DIR/stdlib.jar:$JARS_DIR/annotations.jar
kotlinc $SRC/ApiClient.kt $SRC/Models.kt $HERE/SessionStub.kt $HERE/AvatarUploadStub.kt $HERE/UpdateCheckStub.kt $HERE/MockMain.kt -cp $CP -d /tmp/harness.jar
python3 $HERE/../response_check.py || exit 1
python3 $HERE/mock_server.py ${PORT:-8471} &
SERVER_PID=$!
sleep 1
java -cp /tmp/harness.jar:$CP app.homeroom.MockMainKt http://127.0.0.1:${PORT:-8471}
STATUS=$?
kill $SERVER_PID 2>/dev/null || true
exit $STATUS
