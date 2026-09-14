#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke-results
adb install -r apk/MATH-Foundation-0.2.0-debug.apk
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

# Exercise the scanner on our own installed package, without distributing a game APK.
adb shell am force-stop com.balzikz.mathclient.foundation
adb logcat -c
adb shell am start -W -n com.balzikz.mathclient.foundation/.MainActivity --ez debug_contract_self true
math_contract_ok=false
for math_attempt in $(seq 1 60); do
  adb logcat -d -s MathFoundation:I '*:S' > smoke-results/contract-logcat.txt
  if grep -q '"event":"CONTRACT_COMPLETE"' smoke-results/contract-logcat.txt; then math_contract_ok=true; break; fi
  if grep -q '"event":"OPERATION_ERROR"' smoke-results/contract-logcat.txt; then break; fi
  sleep 1
done
adb exec-out screencap -p > smoke-results/contract.png
adb logcat -d > smoke-results/contract-full-logcat.txt
test "$math_contract_ok" = true
adb exec-out 'run-as com.balzikz.mathclient.foundation sh -c "cat files/sessions/*/contract-*.jsonl"' > smoke-results/self-contract.jsonl
python3 - <<'PY'
import json
rows = [json.loads(line) for line in open('smoke-results/self-contract.jsonl') if line.strip()]
assert not [r for r in rows if r['kind'] == 'contract_error']
assert any(r['kind'] == 'dex_summary' and r['classes'] > 0 for r in rows)
assert any(r['kind'] == 'java_method' and r['owner'].endswith('/NativeProbe;') and r['native'] for r in rows)
assert any(r['kind'] == 'elf_contract' and r['name'] == 'libmathprobe.so'
           and r['symbolsComplete'] and 'JNI_OnLoad' in r['exports'] for r in rows)
assert any(r['kind'] == 'contract_complete' and not r['minecraftLoaded']
           and 'MINECRAFT_ELF_NOT_READ' in r['blockers'] for r in rows)
print('Own APK parsed on Android; absent game correctly reported. No game launch asserted.')
PY
