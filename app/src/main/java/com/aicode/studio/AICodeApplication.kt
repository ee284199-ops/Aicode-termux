package com.aicode.studio

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AICodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // AndroidManifest에서 androidx.startup InitializationProvider를 제거했기 때문에
        // WorkManager 자동 초기화가 비활성화됨 → 수동으로 초기화
        WorkManager.initialize(this, Configuration.Builder().build())
    }
}
