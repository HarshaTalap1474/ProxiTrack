package com.HarshaTalap1474.proxitrack.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingNodeDao {

    // 1. PROVISIONING: Adds a new tag when scanned via NFC/QR.
    // If the MAC already exists, it replaces it (updates it).
    @Insert(onConflict = OnConflictStrategy.Companion.REPLACE)
    suspend fun insertNode(node: TrackingNode)

    // 2. DASHBOARD UI: Retrieves all tags to display on the screen.
    // 'Flow' means the UI will automatically react to any changes in the database.
    // Forces the database to always return the list in Alphabetical order
    @Query("SELECT * FROM tracking_nodes ORDER BY customName ASC")
    fun getAllNodes(): kotlinx.coroutines.flow.Flow<List<TrackingNode>>

    // 3. BLE SCANNER: Gets just the list of MAC addresses.
    // The Foreground Service uses this to know exactly which ESP32s to look for.
    @Query("SELECT macAddress FROM tracking_nodes")
    suspend fun getAllMacAddresses(): List<String>

    // 4. RSSI UPDATE: Updates the signal strength and timestamp when BLE is detected.
    @Query("UPDATE tracking_nodes SET lastRssi = :rssi, status = :status, lastSeenTimestamp = :timestamp WHERE macAddress = :mac")
    suspend fun updateRssiAndStatus(mac: String, rssi: Int, status: Int, timestamp: Long)

    // 5. TETHERED GPS LOGIC: Saves the location when a tag is officially "Lost".
    @Query("UPDATE tracking_nodes SET lastSeenLat = :lat, lastSeenLng = :lng, status = 2 WHERE macAddress = :mac")
    suspend fun updateLastSeenLocation(mac: String, lat: Double, lng: Double)

    // 6. DELETE: Allows the user to remove a tag from their app.
    @Delete
    suspend fun deleteNode(node: TrackingNode)

    @Query("SELECT * FROM tracking_nodes WHERE macAddress = :mac LIMIT 1")
    suspend fun getNodeByMac(mac: String): TrackingNode?

    // Gets real-time updates for a single tag (for the Details Page)
    @Query("SELECT * FROM tracking_nodes WHERE macAddress = :mac LIMIT 1")
    fun getNodeByMacFlow(mac: String): kotlinx.coroutines.flow.Flow<TrackingNode>

    // Deletes a tag by its MAC address
    @Query("DELETE FROM tracking_nodes WHERE macAddress = :mac")
    suspend fun deleteByMac(mac: String)

    // Gets the total number of paired devices (for the Profile Page)
    @Query("SELECT COUNT(*) FROM tracking_nodes")
    fun getTotalNodesCount(): kotlinx.coroutines.flow.Flow<Int>
}