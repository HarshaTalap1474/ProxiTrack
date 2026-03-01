package com.HarshaTalap1474.proxitrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// We declare the Entities (tables) and the current version of the schema
@Database(entities = [TrackingNode::class], version = 2, exportSchema = false) // <-- Changed to 2
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackingNodeDao(): TrackingNodeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "proxitrack_database"
                )
                    .fallbackToDestructiveMigration() // <-- ADD THIS LINE
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}