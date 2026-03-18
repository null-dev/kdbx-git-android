package ax.nd.kdbxgit.android

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import ax.nd.kdbxgit.android.sync.SyncLogDao
import ax.nd.kdbxgit.android.sync.SyncLogEntry
import ax.nd.kdbxgit.android.sync.SyncOutcome
import ax.nd.kdbxgit.android.sync.SyncTrigger
import ax.nd.kdbxgit.android.sync.SyncType

class SyncConverters {
    @TypeConverter fun triggerToString(v: SyncTrigger): String  = v.name
    @TypeConverter fun stringToTrigger(s: String): SyncTrigger  = SyncTrigger.valueOf(s)
    @TypeConverter fun typeToString(v: SyncType): String        = v.name
    @TypeConverter fun stringToType(s: String): SyncType        = SyncType.valueOf(s)
    @TypeConverter fun outcomeToString(v: SyncOutcome): String  = v.name
    @TypeConverter fun stringToOutcome(s: String): SyncOutcome  = SyncOutcome.valueOf(s)
}

@Database(entities = [SyncLogEntry::class], version = 1, exportSchema = false)
@TypeConverters(SyncConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kdbx_git.db",
                ).build().also { instance = it }
            }
    }
}
