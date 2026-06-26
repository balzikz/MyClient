# Stage 5.0.0 — JVM-owned Bedrock load baseline

This stage intentionally changes only the ownership of `libminecraftpe.so` loading.

## Sequence

1. Enter the dedicated `:game_host` process.
2. Preload the eight copied Bedrock dependencies.
3. Load and configure `libmathshim.so` as the GameActivity host library.
4. Load the copied `libminecraftpe.so` exactly once with `System.load(fullPath)`.
5. Let the Android Runtime invoke `JNI_OnLoad` and associate the library with the app ClassLoader.
6. Refresh native signal mappings after the load returns.
7. Keep the existing MATH GameActivity lifecycle loop unchanged.

## Explicitly not included

- no manual `dlsym("JNI_OnLoad")` call;
- no native `dlopen(libminecraftpe.so)` before `System.load`;
- no forwarding of a `GameActivity*` into Bedrock;
- no `android_main` or `ANativeActivity_onCreate` forwarding yet;
- no claim of renderer or first-frame success.

## Device success criteria

The journal must contain, in one stable PID:

- `BEDROCK_SYSTEM_LOAD_START`
- `BEDROCK_SYSTEM_LOAD_RETURN` with `jvmOwned=YES`
- `SIGNAL_TRACE_REFRESH_AFTER_SYSTEM_LOAD`
- `HOST_RUNTIME_READY`

It must not contain:

- `BEDROCK_JNI_ONLOAD_START` from the old manual path;
- `BEDROCK_BIND_START` before the JVM load;
- `SIGABRT` or a restart loop.
