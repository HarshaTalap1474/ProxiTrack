package com.HarshaTalap1474.proxitrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracking_nodes")
data class TrackingNode(
    // The MAC Address of the ESP32 is the perfect Primary Key because it is universally unique.
    @PrimaryKey(autoGenerate = false)
    val macAddress: String,

    // User-defined name (e.g., "Dad's Keys", "College Backpack")
    val customName: String,

    // An integer referencing a drawable resource (e.g., R.drawable.ic_wallet)
    val iconId: Int,

    // The "Tethered GPS" Last Seen coordinates
    var lastSeenLat: Double? = null,
    var lastSeenLng: Double? = null,

    // Current connection state: 0 = Near(Green), 1 = Searching(Yellow), 2 = Lost(Red)
    var status: Int = 0,

    // The last recorded signal strength (used for your UI distance indicator)
    var lastRssi: Int = -100,

    // A timestamp of when the app last successfully "saw" the BLE signal
    var lastSeenTimestamp: Long = System.currentTimeMillis()
)