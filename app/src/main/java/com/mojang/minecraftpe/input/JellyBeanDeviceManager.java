package com.mojang.minecraftpe.input;

public class JellyBeanDeviceManager {
    native void onInputDeviceAddedNative(int deviceId);
    native void onInputDeviceChangedNative(int deviceId);
    native void onInputDeviceRemovedNative(int deviceId);
    native void setControllerDetailsNative(
            int deviceId,
            boolean hasLeftTrigger,
            boolean hasRightTrigger);
    native void setDoubleTriggersSupportedNative(boolean supported);
    native void setFoundDualsenseControllerNative(boolean found);
    native void setFoundPlaystationControllerNative(boolean found);
    native void setFoundXboxControllerNative(boolean found);
}
