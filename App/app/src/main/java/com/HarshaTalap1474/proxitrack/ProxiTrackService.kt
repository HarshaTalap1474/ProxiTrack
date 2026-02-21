package com.HarshaTalap1474.proxitrack

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.HarshaTalap1474.proxitrack.data.AppDatabase
import com.HarshaTalap1474.proxitrack.data.TrackingNodeDao
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProxiTrackService : Service() {

    private val CHANNEL_ID = "ProxiTrack_BLE_Channel"

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var trackingDao: TrackingNodeDao

    // --- ADDED VARIABLES ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val lastDbUpdate = mutableMapOf<String, Long>()
    private val lastLocUpdate = mutableMapOf<String, Long>()

    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isMonitorRunning = false // FIX: Memory leak prevention

    private val rssiBuffers = mutableMapOf<String, MutableList<Int>>()
    private val missedCycles = mutableMapOf<String, Int>()

    // --- UPDATED ONCREATE ---
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        trackingDao = AppDatabase.getDatabase(applicationContext).trackingNodeDao()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleScanner = bluetoothManager.adapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ProxiTrack is Active")
            .setContentText("Monitoring your tags in the background...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        // Wake up the engine (will handle its own restart logic)
        startBleScanning()

        // FIX: Ensure we only ever have ONE monitor running in the background
        if (!isMonitorRunning) {
            startCycleMonitor()
            isMonitorRunning = true
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanning() {
        if (bleScanner == null) {
            Log.e("PIPELINE", "STEP 2 FAILED: Bluetooth is turned off or unsupported!")
            return
        }

        serviceScope.launch {
            // 1. HARDWARE RACE CONDITION FIX: Stop scan, wait for chip to clear cache
            if (isScanning) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
                Log.d("PIPELINE", "Stopping old scan to load new tags...")
                delay(200) // Give the physical antenna 200ms to reset
            }

            // 2. Fetch the fresh MACs
            val activeMacs = trackingDao.getAllMacAddresses().map { it.uppercase() }

            if (activeMacs.isEmpty()) {
                Log.w("PIPELINE", "Database is empty. Scanner going to sleep.")
                return@launch
            }

            Log.d("PIPELINE", "STEP 2: Starting scan with filters for: $activeMacs")

            // 3. Build Hardware Filters
            val filters = activeMacs.map { mac ->
                ScanFilter.Builder().setDeviceAddress(mac).build()
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0)
                .build()

            // 4. Start the fresh scan
            bleScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
        }
    }

    // --- UPGRADED SCAN CALLBACK ---
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            val rawRssi = result.rssi
            val currentTime = System.currentTimeMillis()

            serviceScope.launch {
                val activeMacs = trackingDao.getAllMacAddresses().map { it.uppercase() }

                if (activeMacs.contains(mac)) {
                    // Reset the "lost" timer because we see the tag!
                    missedCycles[mac] = 0

                    val buffer = rssiBuffers.getOrPut(mac) { mutableListOf() }
                    buffer.add(rawRssi)
                    if (buffer.size > 5) buffer.removeAt(0)
                    val smoothedRssi = buffer.average().toInt()

                    val status = when {
                        smoothedRssi > -65 -> 0 // Near
                        smoothedRssi > -95 -> 1 // Searching
                        else -> 2               // Lost
                    }

                    // 1. UPDATE RSSI (Throttled to once per second to prevent UI flickering)
                    if (currentTime - lastDbUpdate.getOrDefault(mac, 0L) > 1000) {
                        lastDbUpdate[mac] = currentTime
                        trackingDao.updateRssiAndStatus(mac, smoothedRssi, status, currentTime)
                    }

                    // 2. CONTINUOUS TETHERING: Copy Phone's GPS to Tag (Every 15 seconds)
                    if (currentTime - lastLocUpdate.getOrDefault(mac, 0L) > 15000) {
                        lastLocUpdate[mac] = currentTime

                        @SuppressLint("MissingPermission")
                        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                            if (location != null) {
                                serviceScope.launch {
                                    // Continuously drop breadcrumbs in the background
                                    trackingDao.updateLastSeenLocation(mac, location.latitude, location.longitude)
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("PIPELINE", "🚨 SCAN FAILED! Error Code: $errorCode")
        }
    }

    private fun startCycleMonitor() {
        serviceScope.launch {
            while (true) {
                delay(10000)

                val activeMacs = trackingDao.getAllMacAddresses()
                for (mac in activeMacs) {
                    val missed = missedCycles.getOrDefault(mac, 0) + 1
                    missedCycles[mac] = missed

                    if (missed == 3) {
                        Log.w("PIPELINE", "WARNING: Tag $mac silent for 30s! Triggering GPS.")
                        triggerTetheredGps(mac)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerTetheredGps(macAddress: String) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@ProxiTrackService)
        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            if (location != null) {
                Log.w("PIPELINE", "📍 GPS PIN DROPPED for $macAddress: ${location.latitude}, ${location.longitude}")
                serviceScope.launch {
                    trackingDao.updateLastSeenLocation(macAddress, location.latitude, location.longitude)
                }
            }
        }.addOnFailureListener { e ->
            Log.e("PIPELINE", "GPS Failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        @SuppressLint("MissingPermission")
        bleScanner?.stopScan(scanCallback)
        isScanning = false
        isMonitorRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "ProxiTrack Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }
}