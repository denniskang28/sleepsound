package com.miansheng.sleep

import android.app.Application
import androidx.work.*
import com.miansheng.sleep.data.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class SleepApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { SleepRepository(this, SleepDatabase.open(this)) }
    lateinit var ready: Deferred<Unit>; private set
    override fun onCreate() {
        super.onCreate()
        ready = scope.async {
            repository.recoverInterruptedSessions()
            repository.markMissingClips()
            repository.cleanExpired()
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("recording-retention", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build())
    }
}

class RetentionWorker(context: android.content.Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as SleepApplication
        app.ready.await()
        app.repository.cleanExpired()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
