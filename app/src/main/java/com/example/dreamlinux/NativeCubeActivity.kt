package com.example.dreamlinux

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Compatibility trampoline for the old Vessel native-preview entry points. */
class NativeCubeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, BounceQuestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
        finish()
    }
}
