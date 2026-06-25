package com.mojang.minecraftpe.Webview;

/** Declaration-only JNI shell for Stage 3.8. */
public class MinecraftWebview {
    private native void nativeOnWebError(int requestId, int errorCode, String description);
    private native void nativeSendToHost(
            int requestId,
            String channel,
            String event,
            String payload);
}
