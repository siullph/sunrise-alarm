package com.siullph.sunrisealarm

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/** The rear flashlight, with brightness levels where the phone supports them. */
class Torch(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    val cameraId: String? = try {
        val withFlash = cameraManager.cameraIdList.filter {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        withFlash.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    } catch (e: Exception) {
        null
    }

    val available: Boolean get() = cameraId != null

    /** 1 means the flash can only be switched on or off. */
    val maxLevel: Int = run {
        val id = cameraId
        if (id == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@run 1
        try {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } catch (e: Exception) {
            1
        }
    }

    val dimmable: Boolean get() = maxLevel > 1

    /** Sets brightness: 0 is off, [maxLevel] is full. */
    fun setLevel(level: Int) {
        val id = cameraId ?: return
        try {
            when {
                level <= 0 -> cameraManager.setTorchMode(id, false)
                dimmable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    cameraManager.turnOnTorchWithStrengthLevel(id, level.coerceIn(1, maxLevel))
                else -> cameraManager.setTorchMode(id, true)
            }
        } catch (e: Exception) {
            // The camera may be in use by another app; try again on the next tick.
            Log.w("SunriseAlarm", "Torch unavailable", e)
        }
    }
}
