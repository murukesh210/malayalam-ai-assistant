#!/bin/sh
# Lightweight Gradle Wrapper script (auto-bootstraps gradle-wrapper.jar if missing)
APP_HOME=$( cd "${0%/*}" && pwd -P ) || exit
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    mkdir -p "$APP_HOME/gradle/wrapper"
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$WRAPPER_URL" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR"
    fi
fi
JAVACMD="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname="gradlew" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
