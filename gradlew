#!/bin/sh
# Lightweight Gradle Wrapper script (honors JAVA_HOME and gradle-wrapper.jar)
APP_HOME=$( cd "${0%/*}" && pwd -P ) || exit
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    if command -v gradle >/dev/null 2>&1; then
        gradle wrapper --gradle-version 9.8.2 --no-daemon
    else
        echo "Error: gradle/wrapper/gradle-wrapper.jar is missing and 'gradle' is not on PATH." >&2
        exit 1
    fi
fi
JAVACMD="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname="gradlew" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
