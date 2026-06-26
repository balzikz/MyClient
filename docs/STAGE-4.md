# MATH Client Stage 4

Stage 4 replaces the direct GameActivity-to-Bedrock connection with a controlled native shim.

## Architecture

```text
Bootstrap / diagnostics UI
        |
        v
Bedrock runtime preparation
        |
        v
GameActivity
        |
        v
libmathshim.so
        |
        v
libminecraftpe.so (future forwarding stage)
```

## Stage 4.0.0 contract

Stage 4.0.0 intentionally does not call into `libminecraftpe.so`.

It proves that:

1. Android resolves `android.app.lib_name=mathshim`.
2. `game-activity_static` creates the native GameActivity handle.
3. `android_main(android_app*)` enters and receives lifecycle commands.
4. Java and native code append to the same Stage 4 journal.
5. The existing Bedrock runtime and dependency preload remain reusable.

Expected journal milestones:

```text
MATH_SHIM_READY
BEFORE_SHIM_GAMEACTIVITY_ONCREATE
SHIM_ANDROID_MAIN_ENTER
SHIM_APP_COMMAND
AFTER_SHIM_GAMEACTIVITY_ONCREATE
AFTER_ONSTART
AFTER_ONRESUME
SURFACE_CREATED
SURFACE_CHANGED
```

## Next milestones

### 4.1 Bedrock symbol binding

Load the user-owned runtime copy of `libminecraftpe.so` inside the shim and resolve the required GameActivity entry points without forwarding calls yet.

### 4.2 Controlled forwarding

Forward GameActivity creation and lifecycle only after all required symbols and package resources have passed preflight checks.

### 4.3 Bedrock package environment

Build a combined asset environment from the installed Minecraft base APK and split APKs. Preserve the versioned native runtime cache.

### 4.4 Java compatibility surface

Introduce the Mojang Java classes required before the first rendered frame as a reviewed group instead of adding missing methods one crash at a time.

## Clean-room boundary

The project may study observable architecture, public Android APIs, exported symbols, package structure, and runtime behavior from other launchers. It does not copy or redistribute their native libraries, protected source code, or Minecraft binaries.
