# Foundation 0.3 — runtime and isolated host preparation

Continues PR #26, branch rebuild/diagnostic-foundation, from
962a13af3306c56ef43c18deb1063991768e5154.

## Evidence boundary

The Foundation 0.2 archive
MATH-1789354536924-35af2076bc00-2368749603245912.zip (51,014 bytes)
was located, but its contents could not be read in this development environment.
It has **not** been analyzed. No Minecraft JNI declaration, GameActivity revision,
native entrypoint, dependency list or phone runtime result has been inferred from it.

DEVICE_BASELINE.md remains the verified Foundation 0.1 baseline. The 0.3 code
implements prerequisites independent of the missing contract. An exact game
adapter, native load, lifecycle bridge and first game frame remain outstanding.

## Implemented boundary

The launcher opens a non-exported GameHostActivity in :game_host. It owns:

1. Installed package identity (version, update time, signing certificate digests,
   base and every split), process ABI and private runtime preparation.
2. Streaming native extraction with per-library 600 MiB, aggregate 1 GiB and
   256 unique-library limits. Duplicate libraries require equal SHA-256.
3. Dependency metadata using packaged names and SONAME aliases. External
   dependencies are explicitly unverified. Cycles clear the proposed order.
4. Public PackageManager resources for the installed game. A unique nonempty
   asset of at most 1 MiB is SHA-compared with the AssetManager for every APK
   containing assets. This is a sampled access check, not full resource parity.
5. Activity/surface lifecycle and cancellation. Readiness requires prepared
   files, asset samples, resumed Activity and a live positive-size surface.

Snapshots reside in the app's no-backup directory, outside diagnostic sessions
and FileProvider paths. No game files are included in APKs or exported reports.
A lock serializes preparation across Activity instances and processes.
Content and package identity determine the snapshot key. Ready snapshots are
reused only after checking every cached library; all source APK hashes are
checked again before publication. An atomic directory move publishes new images.
Only owned incomplete staging directories are cleaned automatically. Complete
snapshots are retained. Corrupted ready snapshots fail explicitly; automatic
replacement/eviction of potentially in-use complete images is not implemented.

The host does not load game Java classes or call System.load/dlopen. It does not
register guessed JNI stubs, call JNI_OnLoad manually, require android_main,
override license checks, or add android.app.lib_name metadata without a native
Activity implementation. Game Resources remain separate from Foundation UI
Resources; the future verified adapter must receive them explicitly.

## Diagnostics

- HOST_PREPARE_BEGIN / HOST_RUNTIME_READY: preparation, not native execution.
- host-*.jsonl: package, APK and native hashes, dependency plan and asset samples.
- HOST_SURFACE_CREATED / CHANGED / DESTROYED: actual SurfaceHolder callbacks.
- HOST_RESUME / PAUSE / DESTROY / WINDOW_FOCUS: host lifecycle.
- HOST_ENVIRONMENT_READY: local prerequisites reached; minecraftLoaded=false,
  gameFrame=false. The state is WAITING_FOR_ADAPTER.
- HOST_PREPARE_ERROR / CANCELLED: failure remains in the session.

## Validation

Core HostTests cover dependency aliases/cycles, lifecycle ordering, arbitrary
split names, cache tampering, conflicting duplicates, ZIP traversal, staging
rollback, APK changes during copying, invalid ELF, cancellation, stale staging
and symlink rejection. They run from the existing core regression gate.

Android CI additionally uses the Foundation APK and its own small asset, checks
the :game_host process, verifies the surface/resources boundary, tests reuse,
and confirms previous reports survive re-entry. It must not emit probe load or
game-frame events in this path. These fixtures do not establish phone compatibility.

## Exact next input

Read the existing Foundation 0.2 contract-*.jsonl plus contract-result-*.txt.
Validate MainActivity ancestry, GameActivity native descriptors, startup call
references, package signatures and ELF exports for this installed version.
Only then select the load owner/class loader, implement the matching adapter,
and validate real JNI/bootstrap and the first game frame on the phone.

Public API references:
- https://developer.android.com/reference/android/content/pm/PackageManager#getResourcesForApplication(android.content.pm.ApplicationInfo)
- https://developer.android.com/reference/android/view/SurfaceHolder.Callback
