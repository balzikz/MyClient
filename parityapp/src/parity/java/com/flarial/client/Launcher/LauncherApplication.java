package com.flarial.client.Launcher;

import android.app.Application;

import com.balzikz.mathclient.HostJournal;

public final class LauncherApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        String process = Application.getProcessName();
        if (process == null || process.equals(getPackageName())) {
            HostJournal.reset(this);
        }
        HostJournal.write(this, "APPLICATION_CREATE", "process=" + process);
    }
}
