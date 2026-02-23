package com.HarshaTalap1474.proxitrack

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private var currentLat: Double? = null
    private var currentLng: Double? = null
    private var customName: String = "Tracker"

    // The MAC address passed from the Dashboard
    private lateinit var macAddress: String

    // The Hardware Contract UUIDs
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val AUTH_CHAR_UUID = UUID.fromString("8d8218b6-97bc-4527-a8db-130940ddb633")
    private val BUZZER_CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    // BLE Variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var tagSecretPin: Int = 1234 // Default fallback PIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag_details)

        macAddress = intent.getStringExtra("MAC_ADDRESS") ?: return

        val tvName = findViewById<TextView>(R.id.tvDetailName)
        val tvMac = findViewById<TextView>(R.id.tvDetailMac)
        val btnOpenMaps = findViewById<MaterialButton>(R.id.btnOpenMaps)
        val btnUnpair = findViewById<MaterialButton>(R.id.btnUnpair)
        val btnRingDevice = findViewById<MaterialButton>(R.id.btnRingDevice)

        tvMac.text = "MAC: $macAddress"
        val dao = AppDatabase.getDatabase(this).trackingNodeDao()

        // 1. Observe Tag Data
        lifecycleScope.launch {
            dao.getNodeByMacFlow(macAddress).collectLatest { node ->
                if (node != null) {
                    tvName.text = node.customName
                    customName = node.customName
                    currentLat = node.lastSeenLat
                    currentLng = node.lastSeenLng

                    // NOTE: If you add 'secretPin' to your TrackingNode data class later,
                    // you can uncomment the line below to load it dynamically:
                    // tagSecretPin = node.secretPin
                }
            }
        }

        // 2. Ring Device (GATT Connection)
        btnRingDevice.setOnClickListener {
            btnRingDevice.text = "Connecting..."
            btnRingDevice.isEnabled = false
            triggerBuzzerOnTag()
        }

        // 3. Google Maps Routing
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

        // 4. Unpair
        btnUnpair.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unpair $customName?")
                .setMessage("This will remove the tag from your cloud registry.")
                .setPositiveButton("Unpair") { _, _ ->
                    lifecycleScope.launch {
                        dao.deleteByMac(macAddress)
                        Toast.makeText(this@TagDetailsActivity, "Device Unpaired", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerBuzzerOnTag() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_SHORT).show()
            resetRingButton()
            return
        }

        try {
            val device = adapter.getRemoteDevice(macAddress)
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            Log.e("GATT", "Error connecting to device", e)
            resetRingButton()
        }
    }

    // --- UPGRADED TWO-STEP GATT CALLBACK ---
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GATT", "Connected. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GATT", "Disconnected.")
                gatt.close()
                bluetoothGatt = null
                resetRingButton()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val authCharacteristic = service?.getCharacteristic(AUTH_CHAR_UUID)

                if (authCharacteristic != null) {
                    Log.d("GATT", "Step 1: Found Auth Char. Writing PIN: $tagSecretPin...")

                    // Convert our 4-digit PIN to a string payload
                    val payload = tagSecretPin.toString().toByteArray()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(authCharacteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        authCharacteristic.value = payload
                        gatt.writeCharacteristic(authCharacteristic)
                    }
                } else {
                    Log.e("GATT", "Auth Characteristic missing on ESP32!")
                    gatt.disconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                // STEP 2: Did we just successfully write the Auth PIN?
                if (characteristic.uuid == AUTH_CHAR_UUID) {
                    Log.d("GATT", "Step 2: Auth PIN accepted! Now sending Buzzer command...")

                    val service = gatt.getService(SERVICE_UUID)
                    val buzzerChar = service?.getCharacteristic(BUZZER_CHAR_UUID)

                    if (buzzerChar != null) {
                        val payload = "1".toByteArray()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(buzzerChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        } else {
                            buzzerChar.value = payload
                            gatt.writeCharacteristic(buzzerChar)
                        }
                    }
                }
                // STEP 3: Did we just successfully write the Buzzer command?
                else if (characteristic.uuid == BUZZER_CHAR_UUID) {
                    Log.d("GATT", "Step 3: Buzzer triggered!")
                    runOnUiThread {
                        Toast.makeText(this@TagDetailsActivity, "Tag is Ringing!", Toast.LENGTH_SHORT).show()
                    }
                    gatt.disconnect() // Hang up the phone
                }

            } else {
                Log.e("GATT", "Failed to write characteristic. Status: $status")
                runOnUiThread {
                    Toast.makeText(this@TagDetailsActivity, "Auth Failed! Wrong PIN?", Toast.LENGTH_SHORT).show()
                }
                gatt.disconnect()
            }
        }
    }

    private fun resetRingButton() {
        runOnUiThread {
            val btnRingDevice = findViewById<MaterialButton>(R.id.btnRingDevice)
            btnRingDevice.text = "Ring Tag (Buzzer)"
            btnRingDevice.isEnabled = true
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
    }
}