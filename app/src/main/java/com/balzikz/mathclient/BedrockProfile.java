package com.balzikz.mathclient;

public final class BedrockProfile {

    public static final String ID = "bedrock-1.26.31.1-arm64-gles";
    public static final String VERSION_NAME = "1.26.31.1";
    public static final long VERSION_CODE = 972603101L;
    public static final String ABI = "arm64-v8a";
    public static final String GRAPHICS_BACKEND = "OPENGL_ES";
    public static final String ELF_BUILD_ID =
            "05118921400461470fd9a5597c5d0580f7779741";
    public static final String ELF_SHA256 =
            "3e7fe54c09bcc6ab3389ca7e25330fb7fe3fe69967703214b8e033fd1baee089";

    private BedrockProfile() {
    }

    public static boolean matches(String versionName,
                                  long versionCode,
                                  String buildId,
                                  String elfSha256) {
        return VERSION_NAME.equals(versionName)
                && VERSION_CODE == versionCode
                && ELF_BUILD_ID.equalsIgnoreCase(buildId)
                && ELF_SHA256.equalsIgnoreCase(elfSha256);
    }

    public static String summary(boolean exactMatch) {
        return "Profile: " + ID + "\n"
                + "ABI: " + ABI + "\n"
                + "Graphics backend: " + GRAPHICS_BACKEND + "\n"
                + "Exact fingerprint match: " + exactMatch + "\n"
                + "Hook policy: " + (exactMatch ? "ALLOW PROFILE" : "BLOCK UNKNOWN BUILD");
    }
}
