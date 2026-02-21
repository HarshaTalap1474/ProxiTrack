package com.HarshaTalap1474.proxitrack

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.HarshaTalap1474.proxitrack.data.AppDatabase
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TagDetailsActivity : AppCompatActivity() {

    private var currentLat: Double? = null
    private var currentLng: Double? = null
    private var customName: String = "Tracker"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag_details)

        val macAddress = intent.getStringExtra("MAC_ADDRESS")
        if (macAddress == null) {
            finish()
            return
        }

        val tvName = findViewById<TextView>(R.id.tvDetailName)
        val tvMac = findViewById<TextView>(R.id.tvDetailMac)
        val btnOpenMaps = findViewById<MaterialButton>(R.id.btnOpenMaps)
        val btnUnpair = findViewById<MaterialButton>(R.id.btnUnpair)

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
                }
            }
        }

        // 2. Google Maps Routing
        btnOpenMaps.setOnClickListener {
            if (currentLat != null && currentLng != null && currentLat != 0.0) {
                // Creates a URI that opens Google Maps and drops a pin with the Tag's Name
                val uri = Uri.parse("geo:0,0?q=$currentLat,$currentLng($customName)")
                val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                mapIntent.setPackage("com.google.android.apps.maps")
                startActivity(mapIntent)
            } else {
                Toast.makeText(this, "No location data available yet.", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Professional Unpair Confirmation
        btnUnpair.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unpair $customName?")
                .setMessage("This will remove the tag from your cloud registry. Another user will be able to pair with it.")
                .setPositiveButton("Unpair") { _, _ ->
                    lifecycleScope.launch {
                        dao.deleteByMac(macAddress)
                        Toast.makeText(this@TagDetailsActivity, "Device Unpaired", Toast.LENGTH_SHORT).show()
                        finish() // Go back to dashboard
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}