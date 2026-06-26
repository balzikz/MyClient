package com.flarial.client.Launcher;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * Present because the original Flarial shim registers JNI methods on this
 * exact class during JNI_OnLoad. The parity build does not start the service.
 */
public final class MinecraftPreloadService extends Service {
    private native void nativeConfigureShimLogger(String path);
    private native void nativeOnLauncherLoaded(String minecraftPath);

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
