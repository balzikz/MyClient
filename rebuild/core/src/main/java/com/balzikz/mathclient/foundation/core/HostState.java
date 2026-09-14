package com.balzikz.mathclient.foundation.core;

/** Main-thread host prerequisites. None of these states asserts native load or a game frame. */
public final class HostState {
    private boolean prepared, assets, resumed, surface, failed, destroyed;
    public void prepared(boolean assetSamplesVerified) { prepared = true; assets = assetSamplesVerified; }
    public void resumed(boolean value) { resumed = value; }
    public void surface(boolean valid, int width, int height) { surface = valid && width > 0 && height > 0; }
    public void fail() { failed = true; }
    public void destroy() { destroyed = true; surface = false; }
    public String phase() {
        if (destroyed) return "DESTROYED";
        if (failed) return "FAILED";
        if (!prepared) return "PREPARING_RUNTIME";
        if (!assets) return "WAITING_FOR_ASSETS";
        if (!resumed) return "WAITING_FOR_RESUME";
        if (!surface) return "WAITING_FOR_SURFACE";
        return "WAITING_FOR_ADAPTER";
    }
    public boolean environmentReady() { return phase().equals("WAITING_FOR_ADAPTER"); }
}
