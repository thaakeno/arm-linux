/*
 * Copyright 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 */
package android.crosvm;

import android.crosvm.DisplayConfig;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface ICrosvmAndroidDisplayService {
    void setSurface(in Surface surface, boolean forCursor);
    void setCursorStream(in ParcelFileDescriptor stream);
    void removeSurface(boolean forCursor);
    void saveFrameForSurface(boolean forCursor);
    void drawSavedFrameForSurface(boolean forCursor);
    DisplayConfig getDisplayConfig();
}
