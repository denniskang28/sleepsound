package com.miansheng.sleep.domain

import com.miansheng.sleep.data.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SoundLevelsTest {
    private fun tone(frequency: Double, amplitude: Double=.1, offset: Int=0) = ShortArray(LEVEL_WINDOW) {
        (sin((it+offset)*2*PI*frequency/LEVEL_RATE)*amplitude*32768).roundToInt().toShort()
    }
    private fun steady(frequency: Double, amplitude: Double=.1): LevelReading {
        val meter = SoundLevelMeter()
        assertNull(meter.accept(tone(frequency,amplitude)))
        return meter.accept(tone(frequency,amplitude,LEVEL_WINDOW))!!
    }
    @Test fun knownRmsAndOneKilohertzNormalization() {
        val reading = steady(1000.0)
        assertEquals(-23.0103,reading.dbfs!!,.01)
        assertEquals(reading.dbfs!!,reading.aDbfs!!,.03)
        assertEquals(6.0206,steady(1000.0,.2).dbfs!!-reading.dbfs!!,.02)
    }
    @Test fun aWeightingAttenuatesLowFrequenciesAndIsContinuousAcrossWindows() {
        val low = steady(125.0)
        assertEquals(-16.1,low.aDbfs!!-low.dbfs!!,.3)
        val mid = steady(500.0)
        assertEquals(-3.2,mid.aDbfs!!-mid.dbfs!!,.3)
        val meter=SoundLevelMeter()
        val levels=(0..10).mapNotNull { meter.accept(tone(1000.0,offset=it*LEVEL_WINDOW))?.aDbfs }
        assertTrue(levels.max()-levels.min()<.01)
    }
    @Test fun silenceAndClippingNeverBecomePlausibleSoundLevels() {
        val meter=SoundLevelMeter()
        meter.accept(ShortArray(LEVEL_WINDOW))
        val silence=meter.accept(ShortArray(LEVEL_WINDOW))!!
        assertEquals("floor",silence.status);assertNull(silence.dbfs)
        val clipped=meter.accept(ShortArray(LEVEL_WINDOW){Short.MAX_VALUE})!!
        assertEquals("clipped",clipped.status);assertNull(clipped.aDbfs)
    }
    @Test fun energyMeanAndDurationWeightedP95DoNotAverageDecibels() {
        val stats=levelStats(listOf(WeightedLevel(40.0,9500),WeightedLevel(60.0,500)))!!
        assertEquals(47.745,stats.mean,.01)
        assertEquals(40.0,stats.p95,0.0)
        assertEquals(60.0,stats.maximum,0.0)
        assertEquals(60.0,levelStats(listOf(WeightedLevel(40.0,9400),WeightedLevel(60.0,600)))!!.p95,0.0)
        assertNull(levelStats(listOf(WeightedLevel(Double.NaN,1000),WeightedLevel(50.0,0))))
    }
    private fun point(ref:Double,offset:Double=90.0,spread:Double=1.0) = CalibrationPoint(ref,ref-offset,spread,30225)
    @Test fun threeLevelCalibrationRejectsNonlinearUnstableAndNarrowMeasurements() {
        val fit=fitCalibration(listOf(point(35.0),point(45.0),point(55.0)))
        assertEquals(90.0,fit.offset,0.0);assertEquals(35.0,fit.minimum,0.0);assertEquals(55.0,fit.maximum,0.0)
        assertThrows(IllegalArgumentException::class.java){fitCalibration(listOf(point(35.0),point(45.0)))}
        assertThrows(IllegalArgumentException::class.java){fitCalibration(listOf(point(35.0),point(40.0),point(45.0)))}
        assertThrows(IllegalArgumentException::class.java){fitCalibration(listOf(point(35.0),point(45.0),point(55.0,offset=96.0)))}
        assertThrows(IllegalArgumentException::class.java){fitCalibration(listOf(point(35.0,spread=4.0),point(45.0),point(55.0)))}
        assertThrows(IllegalArgumentException::class.java){fitCalibration(listOf(point(Double.NaN),point(45.0),point(55.0)))}
    }
    private val profile=SoundCalibration(id="cal",configKey="route",deviceLabel="test",referenceMeter="reference",
        offset=90.0,minimum=30.0,maximum=60.0,maxError=0.0,pointsJson="[]")
    private fun frame(time:Long,cal:String?="cal",db:Double=-45.0,status:String="valid") =
        SoundLevelFrame("night",time,dbfs=db,aDbfs=db-5,status=status,calibrationId=cal)
    @Test fun historicalCalibrationIsFrozenAndMixedSourcesFallBackToDbfs() {
        val frames=listOf(frame(0),frame(975))
        assertEquals(40.0,LevelSeries(frames,listOf(profile)).stats!!.mean,.001)
        val newer=profile.copy(id="new",offset=100.0)
        assertEquals(40.0,LevelSeries(frames,listOf(newer,profile)).stats!!.mean,.001)
        assertNull(LevelSeries(listOf(frame(0),frame(975,null)),listOf(profile)).calibration)
        assertEquals(-45.0,LevelSeries(frames,listOf(profile),true).stats!!.mean,.001)
    }
    @Test fun missingAndInvalidFramesAreExcludedWithoutInventingCoverage() {
        val series=LevelSeries(listOf(frame(0),frame(10000,status="clipped"),frame(20000)),listOf(profile))
        assertEquals(1950L,series.stats!!.durationMs)
        assertNull(series.value(frame(10000,status="clipped")))
        val outside=LevelSeries(listOf(frame(0,db=-70.0)),listOf(profile))
        assertTrue(outside.outsideRange)
        assertEquals("< 30.0",outside.number(outside.stats!!.mean))
        assertNull(LevelSeries(emptyList(),emptyList()).stats)
    }
    @Test fun liveHistogramIsBoundedAndAgreesWithEnergyMean() {
        val live=LiveLevelAccumulator()
        live.add(-40.0,9500);live.add(-20.0,500)
        val exact=levelStats(listOf(WeightedLevel(-40.0,9500),WeightedLevel(-20.0,500)))!!
        assertEquals(exact.mean,live.stats()!!.mean,.0001)
        assertEquals(exact.p95,live.stats()!!.p95,.1)
    }
}
