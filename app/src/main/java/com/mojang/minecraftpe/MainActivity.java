package com.mojang.minecraftpe;

import android.view.KeyEvent;
import android.view.View;

import com.google.androidgamesdk.GameActivity;

/**
 * Minimal declaration-only shell for the isolated JNI registration lab.
 * It is never declared as an Android component and is never instantiated.
 */
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
    private CrashManager() {
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
