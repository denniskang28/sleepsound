package com.miansheng.sleep.domain

import com.miansheng.sleep.data.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisTest {
    private fun event(kind: String, duration: Long, correction: String? = null, clipState: String = "available") =
        SoundEvent(sessionId = "night", startedAt = 0, endedAt = duration, kind = kind, detectedMs = duration,
            confidence = .8f, rms = .03f, correction = correction, clipState = clipState)

    @Test fun statisticsUseEffectiveTimeAndSurviveFileDeletion() {
        val session = SleepSession(id = "night", effectiveMs = 7 * 3600000L)
        val events = listOf(event("snore", 42 * 60000L, clipState = "deleted"), event("talk", 5000), event("snore", 8 * 60000L, "电视或音乐"))
        val stats = nightStats(session, events)
        assertEquals(42 * 60000L, stats.snoreMs)
        assertEquals(1, stats.talkCount)
        assertEquals(.1, stats.ratio!!, .00001)
        assertEquals(0L, nightStats(session, events.map { it.copy(correction = "其他声音") }).snoreMs)
    }

    @Test fun insufficientDataNeverProducesRatio() {
        assertNull(nightStats(SleepSession(id = "night", effectiveMs = 0), emptyList()).ratio)
        assertNull(nightStats(SleepSession(id = "night", effectiveMs = 3 * 3600000L - 1), listOf(event("snore", 60000))).ratio)
        assertNotNull(nightStats(SleepSession(id = "night", effectiveMs = 3 * 3600000L), emptyList()).ratio)
    }

    @Test fun mergerExcludesSilenceAndClipPaddingFromDetectedDuration() {
        val merger = EventAssembler(sampleRate = 10, gapSeconds = 3)
        merger.accept(Prediction("snore", .8f, .02f), 0, 10)
        merger.accept(Prediction("snore", .8f, .02f), 10, 10)
        merger.accept(Prediction(null, .1f, .0f), 20, 10)
        merger.accept(Prediction("snore", .8f, .02f), 30, 10)
        val event = merger.flush()!!
        assertEquals(40L, event.endSample)
        assertEquals(30L, event.detectedSamples)
        assertNull(merger.flush())
    }

    @Test fun changingTypeFlushesAndDoesNotDoubleCount() {
        val merger = EventAssembler(sampleRate = 10)
        merger.accept(Prediction("snore", .8f, .02f), 0, 10)
        merger.accept(Prediction("snore", .8f, .02f), 10, 10)
        val snore = merger.accept(Prediction("talk", .8f, .02f), 20, 10).single()
        merger.accept(Prediction("talk", .8f, .02f), 30, 10)
        val talk = merger.flush()!!
        assertEquals(20L, snore.detectedSamples)
        assertEquals(20L, talk.detectedSamples)
        assertEquals(snore.endSample, talk.startSample)
    }

    @Test fun isolatedFalsePositiveIsDiscardedAndLongClipsAreBounded() {
        val merger = EventAssembler(sampleRate = 10, maxSeconds = 4)
        merger.accept(Prediction("snore", .8f, .02f), 0, 10)
        assertNull(merger.flush())
        repeat(4) { merger.accept(Prediction("snore", .8f, .02f), it * 10L, 10) }
        val complete = merger.accept(Prediction("snore", .8f, .02f), 40, 10).single()
        assertEquals(40L, complete.endSample - complete.startSample)
    }

    @Test fun ringWrapsWithoutKeepingAnEntireNight() {
        val ring = AudioRing(5)
        ring.append(shortArrayOf(1,2,3))
        ring.append(shortArrayOf(4,5,6,7))
        assertArrayEquals(shortArrayOf(3,4,5,6,7), ring.slice(2,7))
        assertThrows(IllegalArgumentException::class.java) { ring.slice(1,5) }
    }
}
