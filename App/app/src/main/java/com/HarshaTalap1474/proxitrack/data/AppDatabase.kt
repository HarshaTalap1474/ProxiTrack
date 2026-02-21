package com.HarshaTalap1474.proxitrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// We declare the Entities (tables) and the current version of the schema
@Database(entities = [TrackingNode::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // This exposes the DAO so the rest of the app can execute SQL queries
    abstract fun trackingNodeDao(): TrackingNodeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // If INSTANCE is not null, return it. Otherwise, create the database.
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "proxitrack_database"
                )
                    // Wipes and rebuilds the database if you change the TrackingNode file
                    // (Perfect for fast prototyping before the Feb 20th deadline)
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}