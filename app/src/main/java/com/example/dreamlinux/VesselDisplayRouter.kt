package com.example.dreamlinux

import android.content.Context
import android.view.Surface

/** Routes the one Android display surface to the selected Linux backend. */
object VesselDisplayRouter {
    fun attach(context: Context, surface: Surface) {
        if (VesselExperimentConfig.runtimeBackend(context) == "gunyah") {
            VesselGunyahDisplayBridge.attach(context, surface)
        } else {
            VesselWaylandPresenter.attach(surface)
        }
    }

    fun surfaceChanged(context: Context, width: Int, height: Int) {
        if (VesselExperimentConfig.runtimeBackend(context) == "gunyah") {
            VesselGunyahDisplayBridge.surfaceChanged(width, height)
        } else {
            VesselWaylandPresenter.surfaceChanged(width, height)
        }
    }

    fun detach(context: Context) {
        if (VesselExperimentConfig.runtimeBackend(context) == "gunyah") {
            VesselGunyahDisplayBridge.detach()
        } else {
            VesselWaylandPresenter.detach()
        }
    }

    fun status(context: Context): String =
        if (VesselExperimentConfig.runtimeBackend(context) == "gunyah") {
            VesselGunyahDisplayBridge.status()
        } else {
            VesselWaylandPresenter.status()
        }

    fun guestWidth(context: Context): Int =
        if (VesselExperimentConfig.runtimeBackend(context) == "gunyah") {
            VesselGunyahDisplayBridge.guestWidth()
        } else {
            VesselWaylandPresenter.guestWidth()
        }

    fun guestHeight(context: Context): Int =
        if (VesselExperimentConfig.runtimeBackend(context) == "gunyah") {
            VesselGunyahDisplayBridge.guestHeight()
        } else {
            VesselWaylandPresenter.guestHeight()
        }

    fun shutdown(context: Context) {
        if (VesselExperimentConfig.runtimeBackend(context) == "gunyah") {
            VesselGunyahDisplayBridge.shutdown()
        } else {
            VesselWaylandPresenter.shutdown()
        }
    }
}
