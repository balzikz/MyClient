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
    public static final String CXX_SHARED_SHA256 =
            "4397241b4bd20a8e579bfb41d21107857e12985f6a01ca0c2a5f83380d1270b4";
    public static final String FMOD_SHA256 =
            "5f9d8a0829463013265b10c18610911f5f53fe6df46ab9976d8e3c5bb87a4968";
    public static final String HTTP_CLIENT_SHA256 =
            "836d493adad561a8b587b29b0c8dd1924069582b76cb702c5ef42bcd817a8b85";

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
                + "Profile action: " + (exactMatch ? "ALLOW" : "BLOCK UNKNOWN BUILD");
    }
}
