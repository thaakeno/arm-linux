package com.example.dreamlinux;

import android.view.Surface;

interface IVmBridge {
    String startVm() = 0;
    String stopVm() = 1;
    String status() = 2;
    String guestShell(String command) = 3;
    String inspectCapabilities() = 4;
    String installDebian() = 5;
    String startDebian(int width, int height, int dpi, int refreshRate) = 6;
    String debianConsole(String command) = 7;
    void setDisplaySurface(in Surface surface) = 8;
    void clearDisplaySurface() = 9;
    boolean sendKey(int action, int keyCode, int scanCode, int metaState) = 10;
    boolean sendTouch(int action, float x, float y, int pointerId) = 11;
    String installKde() = 12;
    void destroy() = 16777114;
}
