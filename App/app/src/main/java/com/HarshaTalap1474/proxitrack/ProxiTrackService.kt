package com.HarshaTalap1474.proxitrack

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val lastDbUpdate = mutableMapOf<String, Long>()
    private val lastLocUpdate = mutableMapOf<String, Long>()

    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isMonitorRunning = false

    private val rssiBuffers = mutableMapOf<String, MutableList<Int>>()
    private val missedCycles = mutableMapOf<String, Int>()

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

        startBleScanning()

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
            if (isScanning) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
                Log.d("PIPELINE", "Stopping old scan to load new tags...")
                delay(200)
            }

            val activeMacs = trackingDao.getAllMacAddresses().map { it.uppercase() }

            if (activeMacs.isEmpty()) {
                Log.w("PIPELINE", "Database is empty. Scanner going to sleep.")
                return@launch
            }

            Log.d("PIPELINE", "STEP 2: Starting scan with filters for: $activeMacs")

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

            bleScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address.uppercase()
            val rawRssi = result.rssi
            val currentTime = System.currentTimeMillis()

            serviceScope.launch {
                val activeMacs = trackingDao.getAllMacAddresses().map { it.uppercase() }

                if (activeMacs.contains(mac)) {
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

                    if (currentTime - lastDbUpdate.getOrDefault(mac, 0L) > 1000) {
                        lastDbUpdate[mac] = currentTime
                        trackingDao.updateRssiAndStatus(mac, smoothedRssi, status, currentTime)
                    }

                    if (currentTime - lastLocUpdate.getOrDefault(mac, 0L) > 15000) {
                        lastLocUpdate[mac] = currentTime

                        @SuppressLint("MissingPermission")
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            null
                        ).addOnSuccessListener { location ->
                            if (location != null) {
                                if (location.accuracy <= 20.0f) {
                                    serviceScope.launch {
                                        trackingDao.updateLastSeenLocation(mac, location.latitude, location.longitude)
                                        Log.w("PIPELINE", "📍 HIGH ACCURACY BREADCRUMB DROPPED: ${location.latitude}, ${location.longitude} (Accuracy: ${location.accuracy}m)")
                                    }
                                } else {
                                    Log.w("PIPELINE", "⚠️ Ignored low-accuracy GPS fix (${location.accuracy}m drift)")
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

    // --- UPDATED 3-CYCLE MONITOR ---
    private fun startCycleMonitor() {
        serviceScope.launch {
            while (true) {
                delay(10000)

                val activeMacs = trackingDao.getAllMacAddresses()
                for (mac in activeMacs) {
                    val missed = missedCycles.getOrDefault(mac, 0) + 1
                    missedCycles[mac] = missed

                    // THE 3-CYCLE RULE: If missing for ~30 seconds
                    if (missed == 3) {
                        Log.w("ProxiTrack", "WARNING: Tag $mac lost! Triggering Tethered GPS.")

                        // 1. Drop the map pin
                        triggerTetheredGps(mac)

                        // 2. FIRE THE NOTIFICATION
                        val node = trackingDao.getNodeByMac(mac)
                        val tagName = node?.customName ?: "Tag"
                        sendLostTagNotification(tagName)
                    }
                }
            }
        }
    }

    // --- NEW NOTIFICATION BUILDER ---
    private fun sendLostTagNotification(customName: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // This makes the notification clickable (opens your app to the map)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "ProxiTrack_Alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Tag Left Behind!")
            .setContentText("You just lost connection to your $customName. Tap to view its last known location.")
            .setPriority(NotificationCompat.PRIORITY_MAX) // Heads-up display
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Trigger default sound/vibrate
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        // Using a unique ID so if you lose 2 tags, you get 2 separate notifications
        notificationManager.notify(customName.hashCode(), builder.build())
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

    // --- UPDATED CHANNEL CREATION ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. The Silent Background Channel (Already exists)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "ProxiTrack Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(serviceChannel)

            // 2. NEW: The High-Priority Alert Channel
            val alertChannel = NotificationChannel(
                "ProxiTrack_Alerts",
                "Lost Tag Alerts",
                NotificationManager.IMPORTANCE_HIGH // Forces sound, vibration, and pop-up
            ).apply {
                description = "Rings and vibrates when a tag is left behind"
            }
            manager.createNotificationChannel(alertChannel)
        }
    }
}