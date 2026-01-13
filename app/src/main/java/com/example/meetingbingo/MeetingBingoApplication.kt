package com.example.meetingbingo

import android.app.Application
import android.util.Log
import no.neat.devkit.NeatDevKit
import no.neat.devkit.NeatDevKitFactory

class MeetingBingoApplication : Application() {
    companion object {
        private const val TAG = "MeetingBingoApp"
        var neatDevKit: NeatDevKit? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "Application onCreate called")
        try {
            neatDevKit = NeatDevKitFactory.create(applicationContext)
            Log.e(TAG, "NeatDevKit created: ${neatDevKit != null}")
            if (neatDevKit != null) {
                val audio = neatDevKit?.audio()
                Log.e(TAG, "NeatDevKit audio: $audio")
                Log.e(TAG, "Audio recording supported: ${audio?.audioRecordingIsSupported()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create NeatDevKit", e)
        }
    }
}
