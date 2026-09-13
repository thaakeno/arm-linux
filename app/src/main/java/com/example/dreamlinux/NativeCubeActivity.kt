package com.example.dreamlinux

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Compatibility entry point for old installs/bookmarks.
 *
 * The Vulkan Studio/game renderer was removed. Any stale intent that still
 * targets this activity now returns to Vessel's Linux desktop instead of
 * loading a demo/game native library.
 */
class NativeCubeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, VesselActivity::class.java).putExtra("openDesktop", true))
        finish()
    }
}
