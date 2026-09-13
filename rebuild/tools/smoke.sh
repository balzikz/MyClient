#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke-results
adb install -r apk/MATH-Foundation-0.1.0-debug.apk
adb logcat -c
adb shell am start -W -n com.balzikz.mathclient.foundation/.MainActivity --ez debug_probe true
math_ok=false
for math_attempt in $(seq 1 30); do
  adb logcat -d -s MathFoundation:I '*:S' > smoke-results/logcat.txt
  if grep -q '"event":"PROBE_SWAP_OK"' smoke-results/logcat.txt; then math_ok=true; break; fi
  sleep 1
done
adb exec-out screencap -p > smoke-results/probe.png
adb shell input keyevent KEYCODE_BACK
sleep 1
adb exec-out screencap -p > smoke-results/launcher.png
adb logcat -d > smoke-results/full-logcat.txt
test "$math_ok" = true
grep -q 'PROBE_JNI_ONLOAD_OK' smoke-results/logcat.txt
grep -q '"process":"com.balzikz.mathclient.foundation:probe"' smoke-results/logcat.txt
echo 'Own JNI and EGL smoke test passed. Minecraft was not loaded.'
