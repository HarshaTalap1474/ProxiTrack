package com.HarshaTalap1474.proxitrack

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

    private var bluetoothGatt: BluetoothGatt? = null
    private var tagSecretPin: Int = 1234

    private val dao by lazy {
        AppDatabase.getDatabase(this).trackingNodeDao()
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

    // ----------------------------------------------------
    private fun initViews() {
        tvName = findViewById(R.id.tvDetailName)
        tvMac = findViewById(R.id.tvDetailMac)
        tvBatteryDetails = findViewById(R.id.tvBatteryDetails)
        btnOpenMaps = findViewById(R.id.btnOpenMaps)
        btnUnpair = findViewById(R.id.btnUnpair)
        btnRingDevice = findViewById(R.id.btnRingDevice)

        tvMac.text = "MAC: $macAddress"
    }

    // ----------------------------------------------------
    private fun observeDatabase() {
        lifecycleScope.launch {
            dao.getNodeByMacFlow(macAddress).collectLatest { node ->
                node ?: return@collectLatest

                tvName.text = node.customName
                customName = node.customName
                currentLat = node.lastSeenLat
                currentLng = node.lastSeenLng

                updateBatteryUI(node.batteryLevel)
            }
        }
    }

    // ----------------------------------------------------
    private fun updateBatteryUI(batteryLevel: Int) {

        if (batteryLevel >= 0) {
            tvBatteryDetails.text = "🔋 Battery: $batteryLevel%"
        } else {
            tvBatteryDetails.text = "🔋 Battery: --"
        }

        when {
            batteryLevel >= 60 ->
                tvBatteryDetails.setTextColor(Color.parseColor("#2E7D32")) // Green

            batteryLevel in 20..59 ->
                tvBatteryDetails.setTextColor(Color.parseColor("#F9A825")) // Yellow

            batteryLevel in 0..19 ->
                tvBatteryDetails.setTextColor(Color.parseColor("#C62828")) // Red

            else ->
                tvBatteryDetails.setTextColor(Color.GRAY)
        }
    }

    // ----------------------------------------------------
    private fun setupClickListeners() {

        // Ring Tag
        btnRingDevice.setOnClickListener {
            btnRingDevice.text = "Connecting..."
            btnRingDevice.isEnabled = false
            triggerBuzzerOnTag()
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

        // ✅ Updated Unpair Logic (markForWipe)
        btnUnpair.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unpair $customName?")
                .setMessage("This will mark the tag for secure wipe and remove it from your account.")
                .setPositiveButton("Unpair") { _, _ ->

                    lifecycleScope.launch {
                        dao.markForWipe(macAddress)
                        Toast.makeText(
                            this@TagDetailsActivity,
                            "Tag marked for wipe",
                            Toast.LENGTH_SHORT
                        ).show()

                        finish() // Return to dashboard
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ----------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun triggerBuzzerOnTag() {

        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

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
            Log.e("GATT", "Connection error", e)
            resetRingButton()
        }
    }

    // ----------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                bluetoothGatt = null
                resetRingButton()
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(SERVICE_UUID)
            val authChar = service?.getCharacteristic(AUTH_CHAR_UUID) ?: run {
                gatt.disconnect()
                return
            }

            val payload = tagSecretPin.toString().toByteArray()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    authChar,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                authChar.value = payload
                gatt.writeCharacteristic(authChar)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    Toast.makeText(
                        this@TagDetailsActivity,
                        "Auth Failed!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                gatt.disconnect()
                return
            }

            if (characteristic.uuid == AUTH_CHAR_UUID) {

                val service = gatt.getService(SERVICE_UUID)
                val buzzerChar =
                    service?.getCharacteristic(BUZZER_CHAR_UUID) ?: return

                val payload = "1".toByteArray()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        buzzerChar,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                } else {
                    buzzerChar.value = payload
                    gatt.writeCharacteristic(buzzerChar)
                }

            } else if (characteristic.uuid == BUZZER_CHAR_UUID) {

                runOnUiThread {
                    Toast.makeText(
                        this@TagDetailsActivity,
                        "Tag is Ringing!",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                gatt.disconnect()
            }
        }
    }

    // ----------------------------------------------------
    private fun resetRingButton() {
        runOnUiThread {
            btnRingDevice.text = "Ring Tag (Buzzer)"
            btnRingDevice.isEnabled = true
        }
    }

    // ----------------------------------------------------
    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
    }
}