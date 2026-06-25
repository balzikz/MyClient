package com.mojang.minecraftpe.Webview;

public class MinecraftWebview {
    private native void nativeOnWebError(int requestId, int errorCode, String description);
    private native void nativeSendToHost(
            int requestId,
            String channel,
            String event,
            String payload);
}
