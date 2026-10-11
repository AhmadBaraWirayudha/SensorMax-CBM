package com.research.sensormax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object CameraAcquisitionPermissions {
    fun requiredPermission(): String = Manifest.permission.CAMERA

    fun hasRequiredPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
