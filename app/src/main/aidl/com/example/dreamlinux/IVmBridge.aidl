package com.example.dreamlinux;
interface IVmBridge {
    String startVm() = 0;
    String stopVm() = 1;
    String status() = 2;
    String guestShell(String command) = 3;
    void destroy() = 16777114;
}
