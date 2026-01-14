package com.laundr.droid

import android.app.Application
import android.util.Log
import com.laundr.droid.ble.LaundryRoomManager

class LaunDRoidApp : Application() {

    companion object {
        const val TAG = "LaunDRoid"
        lateinit var instance: LaunDRoidApp
            private set
    }

    // Singleton LaundryRoomManager - shared across all screens
    val laundryRoomManager: LaundryRoomManager by lazy {
        LaundryRoomManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "LaunDRoid v1.7.0 - BLE Security Audit Tool")
    }
}
