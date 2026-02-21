package com.HarshaTalap1474.proxitrack

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.HarshaTalap1474.proxitrack.data.AppDatabase
import com.HarshaTalap1474.proxitrack.data.TrackingNode
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class AddDeviceActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null

    // The QR Scanner Launcher
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            handleScannedPayload(result.contents)
        } else {
            Toast.makeText(this, "QR Scan Cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_device)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val btnScanQr = findViewById<MaterialButton>(R.id.btnScanQr)
        btnScanQr.setOnClickListener { launchQrScanner() }

        // Core Requirement: Check for NFC Support
        if (nfcAdapter == null) {
            // Device does not have NFC hardware
            MaterialAlertDialogBuilder(this)
                .setTitle("NFC Not Supported")
                .setMessage("Your device doesn't support NFC. We will open the camera to scan the tag's QR code instead.")
                .setPositiveButton("OK") { _, _ -> launchQrScanner() }
                .setCancelable(false)
                .show()
        } else if (nfcAdapter?.isEnabled == false) {
            Toast.makeText(this, "Please enable NFC in your phone settings.", Toast.LENGTH_LONG).show()
        }
    }

    // --- 📡 NFC HANDLING (Foreground Dispatch) ---
    override fun onResume() {
        super.onResume()
        // Tells Android to route NFC taps directly to THIS activity when it is open
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMessages != null) {
                val message = rawMessages[0] as NdefMessage
                val payload = String(message.records[0].payload)
                handleScannedPayload(payload)
            }
        }
    }

    // --- 📷 QR SCANNER HANDLING ---
    private fun launchQrScanner() {
        val options = ScanOptions()
        // 1. Tell it to use our brand new Activity
        options.setCaptureActivity(CustomScannerActivity::class.java)

        // 2. Lock the orientation to follow what we set in the Manifest (Portrait)
        options.setOrientationLocked(true)

        // 3. Optimize for QR Codes only (makes scanning faster)
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)

        // 4. Turn off the beep (Google Lens doesn't beep, it just vibrates and closes)
        options.setBeepEnabled(false)

        barcodeLauncher.launch(options)
    }

    // --- 🧠 PAYLOAD PARSER & RENAMING DIALOG ---
    private fun handleScannedPayload(payload: String) {
        if (payload.startsWith("PROXITRACK:NODE:")) {
            // --- THE FIX ---
            val macAddress = payload.removePrefix("PROXITRACK:NODE:").trim().uppercase()
            Log.d("ProxiTrack", "Successfully scanned MAC: $macAddress")

            showRenameDialog(macAddress)
        } else {
            Toast.makeText(this, "Invalid ProxiTrack Tag!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(macAddress: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_tag, null)
        val etTagName = dialogView.findViewById<TextInputEditText>(R.id.etTagName)

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialAlertDialog_Centered)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Save Device") { _, _ ->
                val customName = etTagName.text.toString().trim()
                val finalName = if (customName.isNotEmpty()) customName else "My Tracker"

                // Save the new device to the Room Database
                val trackingDao = AppDatabase.getDatabase(this).trackingNodeDao()
                lifecycleScope.launch {
                    val newNode = TrackingNode(
                        macAddress = macAddress,
                        customName = finalName,
                        iconId = android.R.drawable.ic_secure,
                        status = 0, // Assume it's near when pairing
                        lastRssi = -50
                    )
                    trackingDao.insertNode(newNode)
                    Toast.makeText(this@AddDeviceActivity, "$finalName Paired Successfully!", Toast.LENGTH_SHORT).show()

                    // Close this page and return to the Dashboard
                    finish()
                }
            }
            .show()
    }
}