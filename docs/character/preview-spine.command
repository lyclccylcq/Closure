#!/bin/bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
if [[ "$(uname)" == "Darwin" ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
./mvnw -B -DskipTests compile
task_repository="${CLOSURE_MAVEN_REPOSITORY:-${MAVEN_USER_HOME:-$HOME/.m2}/repository}"
task_classpath="$task_root/target/classes:$task_repository/com/esotericsoftware/spine/spine-libgdx/3.8.99.1/spine-libgdx-3.8.99.1.jar:$task_repository/com/badlogicgames/gdx/gdx/1.10.0/gdx-1.10.0.jar"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$task_java" -cp "$task_classpath" app.character.SpineEvaluation "$@"
