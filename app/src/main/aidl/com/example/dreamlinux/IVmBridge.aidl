package com.example.dreamlinux;

import android.view.Surface;

interface IVmBridge {
    String startVm() = 0;
    String stopVm() = 1;
    String status() = 2;
    String guestShell(String command) = 3;
    String installDebian() = 4;
    String startDebian(int width, int height, int dpi, int refreshRate) = 5;
    String inspectCapabilities() = 6;
    void setDisplaySurface(in Surface surface) = 7;
    void clearDisplaySurface() = 8;
    boolean sendKey(int action, int keyCode, int metaState) = 9;
    boolean sendTouch(int action, float x, float y, int pointerId) = 10;
    String installKde() = 11;
    String debianConsole(String command) = 12;
    void destroy() = 16777114;
}
