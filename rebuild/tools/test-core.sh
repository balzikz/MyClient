#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p core/build/selftest
mapfile -t math_sources < <(rg --files core/src/main/java core/src/test/java -g '*.java')
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -Xlint:all -d core/build/selftest "${math_sources[@]}"
java -cp core/build/selftest com.balzikz.mathclient.foundation.core.CoreTests
