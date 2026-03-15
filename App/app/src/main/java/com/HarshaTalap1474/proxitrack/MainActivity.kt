package com.HarshaTalap1474.proxitrack

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.HarshaTalap1474.proxitrack.data.AppDatabase
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class TagDetailsActivity : AppCompatActivity() {

    // ---------------- UI ----------------
    private lateinit var tvName: TextView
    private lateinit var tvMac: TextView
    private lateinit var tvBatteryDetails: TextView
    private lateinit var btnOpenMaps: MaterialButton
    private lateinit var btnUnpair: MaterialButton
    private lateinit var btnRingDevice: MaterialButton

    // ---------------- Data ----------------
    private var currentLat: Double? = null
    private var currentLng: Double? = null
    private var customName: String = "Tracker"
    private lateinit var macAddress: String

    // ---------------- BLE UUIDs ----------------
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val AUTH_CHAR_UUID = UUID.fromString("8d8218b6-97bc-4527-a8db-130940ddb633")
    private val BUZZER_CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    // V9.4 Battery Service UUIDs
    private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var tagSecretPin: Int = 1234

    // State Tracking
    private var isUnpairMode = false
    private var isPollingRssi = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val dao by lazy {
        AppDatabase.getDatabase(this).trackingNodeDao()
    }

    // --- TIMERS & LOOPS ---
    private val rssiRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (isPollingRssi && bluetoothGatt != null) {
                bluetoothGatt?.readRemoteRssi()
                mainHandler.postDelayed(this, 1500)
            }
        }
    }

    // 5-Second Timeout for Path B (Force Unpair)
    @SuppressLint("MissingPermission")
    private val unpairTimeoutRunnable = Runnable {
        if (isUnpairMode) {
            Log.w("BLE_GATT", "Unpair connection timed out. Tag is unreachable.")
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            showForceUnpairDialog()
        }
    }

    // ----------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag_details)

        macAddress = intent.getStringExtra("MAC_ADDRESS") ?: return

        initViews()
        observeDatabase()
        setupClickListeners()
    }

    private fun initViews() {
        tvName = findViewById(R.id.tvDetailName)
        tvMac = findViewById(R.id.tvDetailMac)
        tvBatteryDetails = findViewById(R.id.tvBatteryDetails)
        btnOpenMaps = findViewById(R.id.btnOpenMaps)
        btnUnpair = findViewById(R.id.btnUnpair)
        btnRingDevice = findViewById(R.id.btnRingDevice)

        tvMac.text = "MAC: $macAddress"
    }

    private fun observeDatabase() {
        lifecycleScope.launch {
            dao.getNodeByMacFlow(macAddress).collectLatest { node ->
                node ?: return@collectLatest
                tvName.text = node.customName
                customName = node.customName
                currentLat = node.lastSeenLat
                currentLng = node.lastSeenLng
                tagSecretPin = node.secretPin // Ensure we have the correct PIN to authenticate

                updateBatteryUI(node.batteryLevel)
            }
        }
    }

    private fun updateBatteryUI(batteryLevel: Int) {
        if (batteryLevel >= 0) {
            tvBatteryDetails.text = "🔋 Battery: $batteryLevel%"
        } else {
            tvBatteryDetails.text = "🔋 Battery: --"
        }

        when {
            batteryLevel >= 60 -> tvBatteryDetails.setTextColor(Color.parseColor("#2E7D32"))
            batteryLevel in 20..59 -> tvBatteryDetails.setTextColor(Color.parseColor("#F9A825"))
            batteryLevel in 0..19 -> tvBatteryDetails.setTextColor(Color.parseColor("#C62828"))
            else -> tvBatteryDetails.setTextColor(Color.GRAY)
        }
    }

    private fun setupClickListeners() {
        // Ring Tag
        btnRingDevice.setOnClickListener {
            isUnpairMode = false
            btnRingDevice.text = "Connecting..."
            btnRingDevice.isEnabled = false
            btnUnpair.isEnabled = false
            connectToTag()
        }

        // Open Maps
        btnOpenMaps.setOnClickListener {
            if (currentLat != null && currentLng != null && currentLat != 0.0) {
                val uri = Uri.parse("geo:0,0?q=$currentLat,$currentLng($customName)")
                val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                mapIntent.setPackage("com.google.android.apps.maps")
                startActivity(mapIntent)
            } else {
                Toast.makeText(this, "No location data available yet.", Toast.LENGTH_SHORT).show()
            }
        }

        // V9.5 Unpair Logic
        btnUnpair.setOnClickListener {
            isUnpairMode = true
            btnUnpair.text = "Attempting Safe Unpair..."
            btnUnpair.isEnabled = false
            btnRingDevice.isEnabled = false

            // Start the 5-second timeout for Path B
            mainHandler.postDelayed(unpairTimeoutRunnable, 5000)
            connectToTag()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToTag() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            if (isUnpairMode) {
                mainHandler.removeCallbacks(unpairTimeoutRunnable)
                showForceUnpairDialog()
            } else {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_SHORT).show()
                resetButtons()
            }
            return
        }

        try {
            val device = adapter.getRemoteDevice(macAddress)
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            Log.e("GATT", "Connection error", e)
            if (isUnpairMode) {
                mainHandler.removeCallbacks(unpairTimeoutRunnable)
                showForceUnpairDialog()
            } else {
                resetButtons()
            }
        }
    }

    // ----------------------------------------------------
    // V9.5 GATT CALLBACK MANAGER
    // ----------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()

                // If ringing the buzzer, start the V9.4 RSSI Polling
                if (!isUnpairMode) {
                    isPollingRssi = true
                    mainHandler.post(rssiRunnable)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isPollingRssi = false
                mainHandler.removeCallbacks(rssiRunnable)
                gatt.close()
                bluetoothGatt = null

                // If it disconnected while trying to unpair, Path A failed. Trigger Path B.
                if (isUnpairMode) {
                    mainHandler.removeCallbacks(unpairTimeoutRunnable)
                    runOnUiThread { showForceUnpairDialog() }
                } else {
                    resetButtons()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // V9.4 Live Battery Subscription (Only when ringing)
            if (!isUnpairMode) {
                val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
                if (batteryChar != null) {
                    gatt.setCharacteristicNotification(batteryChar, true)
                    val descriptor = batteryChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }

            // Authentication (Required for both Buzzer and Unpair)
            val service = gatt.getService(SERVICE_UUID)
            val authChar = service?.getCharacteristic(AUTH_CHAR_UUID) ?: run {
                gatt.disconnect()
                return
            }

            val payload = tagSecretPin.toString().toByteArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(authChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                authChar.value = payload
                gatt.writeCharacteristic(authChar)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (isUnpairMode) {
                    mainHandler.removeCallbacks(unpairTimeoutRunnable)
                    runOnUiThread { showForceUnpairDialog() }
                } else {
                    runOnUiThread { Toast.makeText(this@TagDetailsActivity, "Auth Failed!", Toast.LENGTH_SHORT).show() }
                }
                gatt.disconnect()
                return
            }

            // Auth successful -> Send specific command
            if (characteristic.uuid == AUTH_CHAR_UUID) {
                val service = gatt.getService(SERVICE_UUID)
                val cmdChar = service?.getCharacteristic(BUZZER_CHAR_UUID) ?: return

                // V9.5: "2" for Safe Unpair, "1" for Buzzer
                val payload = if (isUnpairMode) "2".toByteArray() else "1".toByteArray()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(cmdChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    cmdChar.value = payload
                    gatt.writeCharacteristic(cmdChar)
                }

            } else if (characteristic.uuid == BUZZER_CHAR_UUID) {
                // Command successfully received by ESP32!
                if (isUnpairMode) {
                    // PATH A SUCCESS
                    mainHandler.removeCallbacks(unpairTimeoutRunnable)
                    runOnUiThread {
                        Toast.makeText(this@TagDetailsActivity, "Safe Unpair Successful! Tag Memory Wiped.", Toast.LENGTH_LONG).show()
                    }
                    lifecycleScope.launch {
                        dao.markForWipe(macAddress)
                        runOnUiThread { finish() }
                    }
                } else {
                    // BUZZER SUCCESS
                    runOnUiThread { Toast.makeText(this@TagDetailsActivity, "Tag is Ringing!", Toast.LENGTH_SHORT).show() }
                }
                gatt.disconnect()
            }
        }

        // V9.4 Live RSSI Callback
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_GATT", "Live RSSI: $rssi")
            }
        }

        // V9.4 Live Battery Notification Callback (Android 13+)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && value.isNotEmpty()) {
                val liveBattery = value[0].toInt() and 0xFF
                runOnUiThread { updateBatteryUI(liveBattery) }
            }
        }

        // V9.4 Live Battery Notification Callback (Android 12 and below)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                val liveBattery = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                runOnUiThread { updateBatteryUI(liveBattery) }
            }
        }
    }

    // ----------------------------------------------------
    // PATH B: FORCE UNPAIR DIALOG
    // ----------------------------------------------------
    private fun showForceUnpairDialog() {
        mainHandler.removeCallbacks(unpairTimeoutRunnable)
        isUnpairMode = false
        resetButtons()

        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Tag Unreachable")
            .setMessage("Forcing this unpair will delete it from your app, but the physical tag will remain locked. If you find this tag again, you MUST hold the physical button on the tag for 5 seconds to factory reset it before it can be paired to a new phone.")
            .setPositiveButton("Force Unpair") { _, _ ->
                lifecycleScope.launch {
                    dao.markForWipe(macAddress)
                    Toast.makeText(this@TagDetailsActivity, "Tag Force Unpaired", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetButtons() {
        runOnUiThread {
            btnRingDevice.text = "Ring Tag (Buzzer)"
            btnRingDevice.isEnabled = true

            btnUnpair.text = "Unpair"
            btnUnpair.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        @SuppressLint("MissingPermission")
        bluetoothGatt?.disconnect()
        @SuppressLint("MissingPermission")
        bluetoothGatt?.close()
        mainHandler.removeCallbacksAndMessages(null)
    }
}