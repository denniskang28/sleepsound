package com.miansheng.sleep

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.miansheng.sleep.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LevelStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun v1MigrationPreservesExistingReportsAndAddsLevelTables()=runBlocking {
        val name="migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val asset=InstrumentationRegistry.getInstrumentation().context.assets.open("com.miansheng.sleep.data.SleepDatabase/1.json")
        val schema=asset.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities=schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                val indices=entity.optJSONArray("indices")
                repeat(indices?.length() ?: 0) { j -> old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName"))) }
            }
            old.execSQL("INSERT INTO sessions(id,startedAt,endedAt,zoneId,state,effectiveMs,heartbeatAt,recovered,tags,note,feeling) VALUES('legacy',1000,10000,'Asia/Shanghai','finished',9000,10000,0,'[]','旧记录',NULL)")
            old.version=1
        }
        val migrated=Room.databaseBuilder(context,SleepDatabase::class.java,name).addMigrations(SleepDatabase.MIGRATION_1_2).build()
        try {
            val dao=migrated.dao()
            assertEquals("旧记录",dao.session("legacy")!!.note)
            assertTrue(dao.levels("legacy").isEmpty())
            dao.insertLevels(listOf(SoundLevelFrame("legacy",1000,dbfs=-40.0,aDbfs=-45.0)))
            assertEquals(-40.0,dao.levels("legacy").single().dbfs!!,0.0)
        } finally { migrated.close();context.deleteDatabase(name) }
    }
    @Test fun deletingSessionCascadesLevelsButRetainsCalibrationHistory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,SleepDatabase::class.java).build()
        try {
            val dao=db.dao();val session=SleepSession()
            val calibration=SoundCalibration(configKey="fixed",deviceLabel="fixture",referenceMeter="reference",offset=90.0,
                minimum=30.0,maximum=60.0,maxError=0.0,pointsJson="[]")
            dao.insertCalibration(calibration);dao.insertSession(session)
            dao.insertLevels(listOf(SoundLevelFrame(session.id,session.startedAt,dbfs=-40.0,aDbfs=-45.0,calibrationId=calibration.id)))
            dao.deleteSession(session.id)
            assertTrue(dao.levels(session.id).isEmpty())
            assertNotNull(dao.calibration(calibration.id))
        } finally { db.close() }
    }
}
