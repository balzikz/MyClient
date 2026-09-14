#!/usr/bin/env bash
set -euo pipefail
mkdir -p smoke-results
adb install -r apk/MATH-Foundation-0.3.0-debug.apk
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


# Isolated host uses ONLY our own APK and asset as the CI fixture.
# No Minecraft files are fetched, uploaded, executed, or included in this build.
adb shell am force-stop com.balzikz.mathclient.foundation
adb logcat -c
adb shell am start -W -n com.balzikz.mathclient.foundation/.MainActivity --ez debug_host_self true
math_host_ok=false
for math_attempt in $(seq 1 90); do
  adb logcat -d -s MathFoundation:I '*:S' > smoke-results/host-logcat.txt
  if grep -q '"event":"HOST_ENVIRONMENT_READY"' smoke-results/host-logcat.txt; then math_host_ok=true; break; fi
  if grep -q '"event":"HOST_PREPARE_ERROR"' smoke-results/host-logcat.txt; then break; fi
  sleep 1
done
adb logcat -d > smoke-results/host-full-logcat.txt
adb exec-out screencap -p > smoke-results/host.png
test "$math_host_ok" = true
grep -q '"process":"com.balzikz.mathclient.foundation:game_host"' smoke-results/host-logcat.txt
# A host window does not load even our probe or assert a game frame.
if grep -Eq '"event":"(PROBE_JNI_ONLOAD_OK|PROBE_SWAP_OK|GAME_FIRST_FRAME|MINECRAFT_STARTED)"' smoke-results/host-logcat.txt; then exit 1; fi
adb exec-out 'run-as com.balzikz.mathclient.foundation sh -c "cat files/sessions/*/host-*.jsonl"' > smoke-results/self-host.jsonl
python3 - <<'PY'
import json
rows = [json.loads(line) for line in open('smoke-results/self-host.jsonl') if line.strip()]
assert any(r['kind'] == 'host_runtime' and r['selfTest'] for r in rows)
assert any(r['kind'] == 'host_native_library' and r['name'] == 'libmathprobe.so' and not r['loaded'] for r in rows)
assert any(r['kind'] == 'host_asset_sample' and r['matched'] for r in rows)
assert any(r['kind'] == 'host_dependency_plan' and not r['executable'] for r in rows)
assert any(r['kind'] == 'host_environment_complete' and r['prepared']
           and not r['minecraftLoaded'] and not r['gameFrame'] for r in rows)
assert not any(r.get('minecraftLoaded') or r.get('gameFrame') for r in rows)
print('Isolated host prepared own runtime and verified asset samples; game adapter remains pending.')
PY
adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell am start -W -n com.balzikz.mathclient.foundation/.MainActivity
sleep 1
adb logcat -d -s MathFoundation:I '*:S' > smoke-results/host-lifecycle-logcat.txt
grep -q '"event":"HOST_PAUSE"' smoke-results/host-lifecycle-logcat.txt

# Re-entry verifies cached bytes and must preserve the previous session report.
adb shell am force-stop com.balzikz.mathclient.foundation
adb logcat -c
adb shell am start -W -n com.balzikz.mathclient.foundation/.MainActivity --ez debug_host_self true
math_reuse_ok=false
for math_attempt in $(seq 1 90); do
  adb logcat -d -s MathFoundation:I '*:S' > smoke-results/host-reuse-logcat.txt
  if grep -q '"event":"HOST_ENVIRONMENT_READY"' smoke-results/host-reuse-logcat.txt; then math_reuse_ok=true; break; fi
  if grep -q '"event":"HOST_PREPARE_ERROR"' smoke-results/host-reuse-logcat.txt; then break; fi
  sleep 1
done
test "$math_reuse_ok" = true
grep -q 'reused=true' smoke-results/host-reuse-logcat.txt
adb exec-out 'run-as com.balzikz.mathclient.foundation sh -c "cat files/sessions/*/host-*.jsonl"' > smoke-results/self-host-reuse.jsonl
python3 - <<'PY'
import json
rows = [json.loads(line) for line in open('smoke-results/self-host-reuse.jsonl') if line.strip()]
complete = [r for r in rows if r['kind'] == 'host_environment_complete' and r['prepared']]
assert len(complete) >= 2
assert any(r['reused'] for r in complete) and any(not r['reused'] for r in complete)
print('Host re-entry revalidated the cached runtime and retained previous reports.')
PY
