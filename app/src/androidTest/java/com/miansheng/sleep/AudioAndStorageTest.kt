package com.miansheng.sleep

import android.content.Context
import android.content.ContextWrapper
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.miansheng.sleep.audio.*
import com.miansheng.sleep.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioAndStorageTest {
    private lateinit var database: SleepDatabase
    private lateinit var repo: SleepRepository
    private lateinit var directory: File
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = UUID.randomUUID().toString()
        directory = File(context.cacheDir,"tests-$prefix").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$prefix-$name",mode)
        }
        database = Room.inMemoryDatabaseBuilder(context,SleepDatabase::class.java).build()
        repo = SleepRepository(isolated,database)
    }
    @After fun cleanup() { database.close(); directory.deleteRecursively() }

    @Test fun bundledModelRunsOnDeviceAndSilenceIsNotAnEvent() {
        SoundClassifier(InstrumentationRegistry.getInstrumentation().targetContext).use { classifier ->
            assertEquals(15600,classifier.windowSize)
            val prediction=classifier.classify(ShortArray(classifier.windowSize))
            assertNull(prediction.kind)
            assertTrue(prediction.confidence.isFinite())
        }
    }

    @Test fun clipsEncodeToPlayableAacWithActualWaveform() {
        val pcm=ShortArray(16000*2) { (sin(it*2*Math.PI*180/16000)*8000).toInt().toShort() }
        val file=File(directory,"clip.m4a")
        ClipWriter.write(file,pcm)
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format=extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm",format.getString(MediaFormat.KEY_MIME))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 1900000..2300000)
            assertTrue(ClipWriter.waveform(pcm).all { it in 0f..1f })
        } finally { extractor.release() }
    }

    @Test fun expirationProtectsFavoritesAndPreservesEvents() = runBlocking {
        val now=System.currentTimeMillis()
        val session=SleepSession(startedAt=now-10*86400000L,endedAt=now-10*86400000L+60000,state=SessionState.FINISHED)
        repo.dao.insertSession(session)
        fun event(favorite:Boolean):SoundEvent {
            val file=File(repo.recordingsDir,UUID.randomUUID().toString()+".m4a").apply { writeBytes(byteArrayOf(1,2,3)) }
            return SoundEvent(sessionId=session.id,startedAt=session.startedAt,endedAt=session.startedAt+1000,kind="snore",detectedMs=1000,confidence=.8f,rms=.02f,clipPath=file.absolutePath,favorite=favorite)
        }
        val favorite=event(true);val expired=event(false)
        repo.dao.insertEvent(favorite);repo.dao.insertEvent(expired)
        repo.cleanExpired(now)
        assertTrue(File(favorite.clipPath!!).exists())
        assertFalse(File(expired.clipPath!!).exists())
        assertEquals("expired",repo.dao.event(expired.id)!!.clipState)
        assertEquals(1000L,repo.dao.event(expired.id)!!.detectedMs)
        repo.clearUnfavorited()
        assertTrue(File(favorite.clipPath).exists())
    }

    @Test fun recoveryStopsAtLastHeartbeatAndNeverInventsCoverage() = runBlocking {
        val now=System.currentTimeMillis()
        val session=SleepSession(startedAt=now-60000,heartbeatAt=now-30000,effectiveMs=25000)
        repo.dao.insertSession(session)
        repo.dao.insertSegment(CaptureSegment(sessionId=session.id,startedAt=session.startedAt,kind="active"))
        repo.dao.insertLevels(listOf(SoundLevelFrame(session.id,session.startedAt,dbfs=-40.0,aDbfs=-45.0),
            SoundLevelFrame(session.id,now-10000,dbfs=-30.0,aDbfs=-35.0)))
        repo.recoverInterruptedSessions(now)
        val restored=repo.dao.session(session.id)!!
        assertEquals(session.heartbeatAt,restored.endedAt)
        assertEquals(25000L,restored.effectiveMs)
        assertTrue(restored.recovered)
        assertEquals(session.heartbeatAt,repo.dao.segments(session.id).single().endedAt)
        assertEquals(1,repo.dao.levels(session.id).size)
    }

    @Test fun captureHeartbeatNeverOverwritesTagsAndCorrectionsPersist() = runBlocking {
        val session=SleepSession()
        repo.dao.insertSession(session)
        repo.annotate(session.id,listOf("侧睡"),"测试备注")
        repo.dao.updateCapture(session.id,SessionState.RUNNING,5000,System.currentTimeMillis())
        assertEquals(listOf("侧睡"),repo.dao.session(session.id)!!.tags)
        assertEquals("测试备注",repo.dao.session(session.id)!!.note)
    }
}
