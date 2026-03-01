package com.HarshaTalap1474.proxitrack.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingNodeDao {

    // --------------------------------------------------
    // 1. PROVISIONING (Add / Replace Tag)
    // --------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: TrackingNode)

    // --------------------------------------------------
    // 2. DASHBOARD UI (Ignore pending wipe tags)
    // --------------------------------------------------
    @Query("""
        SELECT * FROM tracking_nodes 
        WHERE pendingWipe = 0 
        ORDER BY customName ASC
    """)
    fun getAllNodes(): Flow<List<TrackingNode>>

    // --------------------------------------------------
    // 3. BLE SCANNER (MAC list only)
    // --------------------------------------------------
    @Query("""
        SELECT macAddress FROM tracking_nodes 
        WHERE pendingWipe = 0
    """)
    suspend fun getAllMacAddresses(): List<String>

    // --------------------------------------------------
    // 4. RSSI + STATUS UPDATE
    // --------------------------------------------------
    @Query("""
        UPDATE tracking_nodes 
        SET lastRssi = :rssi, 
            status = :status, 
            lastSeenTimestamp = :timestamp 
        WHERE macAddress = :mac
    """)
    suspend fun updateRssiAndStatus(
        mac: String,
        rssi: Int,
        status: Int,
        timestamp: Long
    )

    // --------------------------------------------------
    // 5. LOST MODE LOCATION SAVE
    // --------------------------------------------------
    @Query("""
        UPDATE tracking_nodes 
        SET lastSeenLat = :lat, 
            lastSeenLng = :lng, 
            status = 2 
        WHERE macAddress = :mac
    """)
    suspend fun updateLastSeenLocation(
        mac: String,
        lat: Double,
        lng: Double
    )

    // --------------------------------------------------
    // 6. FULL STATUS UPDATE (RSSI + BATTERY)
    // --------------------------------------------------
    @Query("""
        UPDATE tracking_nodes 
        SET lastRssi = :rssi,
            status = :status,
            lastSeenTimestamp = :timestamp,
            batteryLevel = :battery
        WHERE macAddress = :mac
    """)
    suspend fun updateNodeStatus(
        mac: String,
        rssi: Int,
        status: Int,
        timestamp: Long,
        battery: Int
    )

    // --------------------------------------------------
    // 7. SINGLE NODE (One-time fetch)
    // --------------------------------------------------
    @Query("""
        SELECT * FROM tracking_nodes 
        WHERE macAddress = :mac 
        LIMIT 1
    """)
    suspend fun getNodeByMac(mac: String): TrackingNode?

    // --------------------------------------------------
    // 8. SINGLE NODE (Live updates for Details screen)
    // --------------------------------------------------
    @Query("""
        SELECT * FROM tracking_nodes 
        WHERE macAddress = :mac 
        LIMIT 1
    """)
    fun getNodeByMacFlow(mac: String): Flow<TrackingNode?>

    // --------------------------------------------------
    // 9. SECURE WIPE FLAG
    // --------------------------------------------------
    @Query("""
        UPDATE tracking_nodes 
        SET pendingWipe = 1 
        WHERE macAddress = :mac
    """)
    suspend fun markForWipe(mac: String)

    // --------------------------------------------------
    // 10. HARD DELETE (Optional admin cleanup)
    // --------------------------------------------------
    @Query("""
        DELETE FROM tracking_nodes 
        WHERE macAddress = :mac
    """)
    suspend fun deleteByMac(mac: String)

    // --------------------------------------------------
    // 11. TOTAL ACTIVE TAG COUNT
    // --------------------------------------------------
    @Query("""
        SELECT COUNT(*) FROM tracking_nodes 
        WHERE pendingWipe = 0
    """)
    fun getTotalNodesCount(): Flow<Int>
}