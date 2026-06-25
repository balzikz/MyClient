package com.mojang.minecraftpe;

import android.content.SharedPreferences;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.View;

import com.balzikz.mathclient.HostJournal;
import com.google.androidgamesdk.GameActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

public class MainActivity extends GameActivity
        implements FilePickerManagerHandler, View.OnKeyListener {

    native boolean isAndroidAmazon();
    native boolean isAndroidChromebook();
    native boolean isAndroidTrial();
    native boolean isBrazeEnabled();
    native boolean isPreview();
    native boolean isTestInfrastructureDisabled();
    native boolean nativeKeyHandler(int action, int keyCode, int unicodeChar);
    native String nativeGetDeviceId();
    native void nativeBackPressed();
    native void nativeBackSpacePressed();
    native void nativeClearAButtonState();
    native void nativeDeviceCorrelation(long firstId, String firstValue, long secondId, String secondValue);
    native void nativeOnDestroy();
    native void nativeOnPickFileCanceled();
    native void nativeOnPickFileSuccess(String path);
    native void nativeOnPickImageCanceled(long requestId);
    native void nativeOnPickImageSuccess(long requestId, String path);
    native void nativeProcessIntentUriQuery(String key, String value);
    native void nativeResize(int width, int height);
    native void nativeReturnKeyPressed();
    native void nativeRunNativeCallbackOnUiThread(long callback);
    native void nativeSetHeadphonesConnected(boolean connected);
    native void nativeSetIntegrityToken(String token);
    native void nativeSetIntegrityTokenErrorMessage(String message);
    native void nativeSetTextboxText(String text, int selectionStart, int selectionEnd);
    native void nativeShiftKeyPressed(int state);
    native void nativeShutdown();
    native void nativeStopThis();
    native void nativeStoragePermissionRequestResult(boolean granted, int requestCode);
    native void nativeSuspend();
    native void nativeSuspendGameplayUpdates(boolean suspended);
    native void onSoftKeyboardClosed();
    public native boolean isEduMode();
    public native boolean isPublishBuild();
    public static native void nativeWaitCrashManagementSetupComplete();

    private CrashManager mCrashManager;

    /**
     * Mirrors Minecraft 1.26.23.1 MainActivity.getExternalStoragePath().
     * This is the app-specific external files directory, not shared storage root.
     */
    public String getExternalStoragePath() {
        File directory = getExternalFilesDir(null);
        String path = directory == null ? "" : directory.getAbsolutePath();
        HostJournal.write(this, "JAVA_GET_EXTERNAL_STORAGE_PATH", path);
        return path;
    }

    /** Mirrors Minecraft 1.26.23.1 MainActivity.getInternalStoragePath(). */
    public String getInternalStoragePath() {
        String path = getDataDir().getAbsolutePath();
        HostJournal.write(this, "JAVA_GET_INTERNAL_STORAGE_PATH", path);
        return path;
    }

    /**
     * Mirrors Minecraft 1.26.23.1 legacy-storage probe. On modern scoped-storage
     * devices this normally returns an empty string because the shared root is not writable.
     */
    public String getLegacyExternalStoragePath(String gameFolder) {
        String path = "";
        String resultDetail;
        try {
            File root = Environment.getExternalStorageDirectory();
            File gameDirectory = new File(root, gameFolder);
            File probe = new File(gameDirectory, "test");
            try (FileOutputStream output = new FileOutputStream(probe)) {
                output.flush();
            }
            path = root.getAbsolutePath();
            resultDetail = "gameFolder=" + gameFolder + " result=" + path;
        } catch (Throwable error) {
            resultDetail = "gameFolder=" + gameFolder + " result= reason="
                    + error.getClass().getName() + ": " + error.getMessage();
        }
        HostJournal.write(this, "JAVA_GET_LEGACY_EXTERNAL_STORAGE_PATH", resultDetail);
        return path;
    }

    /** Mirrors Minecraft 1.26.23.1 preference-backed legacy device ID lookup. */
    public String getLegacyDeviceID() {
        String value = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("snooperId", "");
        if (value == null) value = "";
        HostJournal.write(this, "JAVA_GET_LEGACY_DEVICE_ID", describeIdentifier(value));
        return value;
    }

    /** Mirrors Minecraft 1.26.23.1 preference-backed client ID lookup. */
    public String getClientId() {
        String value = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("clientId", "");
        if (value == null) value = "";
        HostJournal.write(this, "JAVA_GET_CLIENT_ID", describeIdentifier(value));
        return value;
    }

    /** Mirrors Minecraft's preference-backed cached device ID setter. */
    public void setCachedDeviceId(String deviceId) {
        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this)
                .edit();
        editor.putString("deviceId", deviceId == null ? "" : deviceId);
        editor.apply();
        HostJournal.write(this, "JAVA_SET_CACHED_DEVICE_ID",
                describeIdentifier(deviceId == null ? "" : deviceId));
    }

    /** Mirrors Minecraft 1.26.23.1 MainActivity.createUUID(). */
    public String createUUID() {
        String value = UUID.randomUUID().toString().replaceAll("-", "");
        HostJournal.write(this, "JAVA_CREATE_UUID", "GENERATED length=" + value.length());
        return value;
    }

    /**
     * JNI-visible bootstrap contract confirmed from the exact 1.26.31.1 binary.
     * The original method selects a Sentry endpoint, creates CrashManager, installs
     * its global Java exception handler, stores it, and returns it. Network upload
     * machinery is intentionally omitted; the lifecycle and JNI object contract are preserved.
     */
    public CrashManager initializeCrashManager(String crashDumpFolder, String currentSessionId) {
        CrashManager manager = new CrashManager(
                this,
                crashDumpFolder == null ? "" : crashDumpFolder,
                currentSessionId == null ? "" : currentSessionId);
        manager.installGlobalExceptionHandler();
        mCrashManager = manager;
        HostJournal.write(this, "JAVA_INITIALIZE_CRASH_MANAGER",
                "folder=" + describeIdentifier(manager.getCrashDumpFolder())
                        + " session=" + describeIdentifier(manager.getCurrentSessionId())
                        + " handler=INSTALLED");
        return manager;
    }

    private static String describeIdentifier(String value) {
        return value.isEmpty() ? "EMPTY" : "PRESENT length=" + value.length();
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        return false;
    }
}

final class AppExitInfoHelper {
    private AppExitInfoHelper() {
    }

    public static native void nativeSendApplicationExitInfo(
            String description,
            int reason,
            int status,
            int importance,
            long timestamp,
            long pss,
            String trace,
            boolean lowMemory);
}

final class BatteryMonitor {
    private BatteryMonitor() {
    }

    public static native void nativeUpdateBatteryStatus(int status, int level, int scale);
    public static native void nativeUpdateBatteryThermalStatus(int status);
}

final class CrashManager {
    private final MainActivity owner;
    private final String crashDumpFolder;
    private final String currentSessionId;
    private Thread.UncaughtExceptionHandler previousHandler;

    CrashManager(MainActivity owner, String crashDumpFolder, String currentSessionId) {
        this.owner = owner;
        this.crashDumpFolder = crashDumpFolder;
        this.currentSessionId = currentSessionId;
    }

    String getCrashDumpFolder() {
        return crashDumpFolder;
    }

    String getCurrentSessionId() {
        return currentSessionId;
    }

    void installGlobalExceptionHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            HostJournal.write(owner, "JAVA_CRASH_MANAGER_UNCAUGHT",
                    "thread=" + thread.getName()
                            + " error=" + error.getClass().getName()
                            + ": " + error.getMessage());
            try {
                nativeNotifyUncaughtException();
            } catch (Throwable notifyError) {
                HostJournal.write(owner, "JAVA_CRASH_MANAGER_NOTIFY_FAIL",
                        notifyError.getClass().getName() + ": " + notifyError.getMessage());
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error);
            }
        });
    }

    public static native String nativeNotifyUncaughtException();
}

final class FilePickerManager {
    private FilePickerManager() {
    }

    public static native void nativeDirectoryPickResult(String uri, String displayName);
}

final class NetworkMonitor {
    private native void nativeUpdateNetworkStatus(boolean connected, boolean wifi, boolean cellular);
}

class NotificationListenerService {
    native void nativePushNotificationReceived(
            int notificationId,
            String title,
            String body,
            String payload);
}

final class ThermalMonitor {
    private ThermalMonitor() {
    }

    public static native void nativeUpdateLowPowerModeStatus(boolean enabled);
}

final class WorldRecovery {
    private WorldRecovery() {
    }

    public static native void nativeComplete();
    public static native void nativeError(String message, long current, long total);
    public static native void nativeUpdate(String worldName, int step, int steps, long current, long total);
}
