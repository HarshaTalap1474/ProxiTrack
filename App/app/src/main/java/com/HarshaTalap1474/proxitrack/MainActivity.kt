package com.HarshaTalap1474.proxitrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.HarshaTalap1474.proxitrack.data.AppDatabase
import com.HarshaTalap1474.proxitrack.data.TrackingNode
import com.HarshaTalap1474.proxitrack.data.TrackingNodeDao
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var adapter: TagAdapter
    private lateinit var trackingDao: TrackingNodeDao
    private lateinit var myLocationOverlay: MyLocationNewOverlay

    // Store markers so we can clear them without deleting the user's Blue Dot
    private val tagMarkers = mutableListOf<Marker>()

    // -----------------------------------------
    // Permission Handler
    // -----------------------------------------
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startTrackingEngine()
        } else {
            Toast.makeText(this, "Permissions required for tracking!", Toast.LENGTH_LONG).show()
        }
    }

    // -----------------------------------------
    // Lifecycle
    // -----------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        trackingDao = AppDatabase.getDatabase(this).trackingNodeDao()
        mapView = findViewById(R.id.mapView)

        setupMap()
        setupRecyclerView()
        observeDatabase()
        handleNfcIntent(intent)
        checkPermissionsAndStart()

        findViewById<FloatingActionButton>(R.id.fabAddTag).setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java))
        }

        // NEW: Link the Profile FAB
        findViewById<FloatingActionButton>(R.id.fabProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (::myLocationOverlay.isInitialized) myLocationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (::myLocationOverlay.isInitialized) myLocationOverlay.disableMyLocation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    // -----------------------------------------
    // Map Setup
    // -----------------------------------------
    private fun setupMap() {
        mapView.setMultiTouchControls(true)
        val controller = mapView.controller
        controller.setZoom(18.0)

        val locationProvider = GpsMyLocationProvider(this)
        myLocationOverlay = MyLocationNewOverlay(locationProvider, mapView)

        val blueDotDrawable = ContextCompat.getDrawable(this, R.drawable.ic_blue_dot)
        val blueDotBitmap = blueDotDrawable?.toBitmap(60, 60)

        blueDotBitmap?.let {
            myLocationOverlay.setPersonIcon(it)
            myLocationOverlay.setDirectionArrow(it, it)
            // Fixes the off-center visual bug!
            myLocationOverlay.setPersonAnchor(0.5f, 0.5f)
            myLocationOverlay.setDirectionAnchor(0.5f, 0.5f)
        }

        myLocationOverlay.enableMyLocation()
        myLocationOverlay.enableFollowLocation()
        mapView.overlays.add(myLocationOverlay)

        val compassOverlay = CompassOverlay(this, InternalCompassOrientationProvider(this), mapView)
        compassOverlay.enableCompass()
        mapView.overlays.add(compassOverlay)

        val scaleBarOverlay = ScaleBarOverlay(mapView)
        scaleBarOverlay.setCentred(true)
        scaleBarOverlay.setScaleBarOffset(resources.displayMetrics.widthPixels / 2, 20)
        mapView.overlays.add(scaleBarOverlay)

        findViewById<FloatingActionButton>(R.id.fabMyLocation).setOnClickListener {
            val myLocation = myLocationOverlay.myLocation
            if (myLocation != null) {
                controller.animateTo(myLocation)
                controller.setZoom(18.0)
            } else {
                Toast.makeText(this, "Searching GPS...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -----------------------------------------
    // Map Marker Updater
    // -----------------------------------------
    private fun updateMapMarkers(nodes: List<TrackingNode>) {
        // Remove old tag markers safely
        tagMarkers.forEach { mapView.overlays.remove(it) }
        tagMarkers.clear()

        // Add fresh markers for lost/tracked tags
        nodes.forEach { node ->
            val lat = node.lastSeenLat
            val lng = node.lastSeenLng

            // Check if coordinates exist and aren't default 0.0
            if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                val marker = Marker(mapView)
                marker.position = GeoPoint(lat, lng)
                marker.title = node.customName
                marker.snippet = if (node.status == 0) "Currently Near You" else "Last Seen Here"

                // Pin points exactly to the coordinate
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                //marker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)

                mapView.overlays.add(marker)
                tagMarkers.add(marker)
            }
        }
        mapView.invalidate() // Redraw map
    }

    // -----------------------------------------
    // RecyclerView Setup
    // -----------------------------------------
    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.tagsRecyclerView)
        adapter = TagAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Swipe handler has been successfully removed!
    }

    // -----------------------------------------
    // Database Observer
    // -----------------------------------------
    private fun observeDatabase() {
        lifecycleScope.launch {
            trackingDao.getAllNodes().collectLatest { nodes ->
                adapter.submitList(nodes)
                // Real-time map pin updates!
                updateMapMarkers(nodes)
            }
        }
    }

    // -----------------------------------------
    // Permissions & Service
    // -----------------------------------------
    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startTrackingEngine()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startTrackingEngine() {
        val intent = Intent(this, ProxiTrackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // -----------------------------------------
    // Hardware Provisioning (NFC)
    // -----------------------------------------
    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return

        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return
        val message = rawMessages[0] as NdefMessage
        val payload = String(message.records[0].payload)

        if (payload.startsWith("PROXITRACK:NODE:")) {
            val mac = payload.removePrefix("PROXITRACK:NODE:").trim().uppercase()
            showRenameDialog(mac)
        }
    }

    private fun showRenameDialog(macAddress: String) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_tag, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etTagName)

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("Save Device") { _, _ ->
                val name = etName.text.toString().ifEmpty { "My Tracker" }

                lifecycleScope.launch {
                    trackingDao.insertNode(
                        TrackingNode(
                            macAddress = macAddress,
                            customName = name,
                            iconId = android.R.drawable.ic_secure,
                            status = 0,
                            lastRssi = -50
                        )
                    )
                    Toast.makeText(this@MainActivity, "$name Added!", Toast.LENGTH_SHORT).show()

                    // --- THE CRITICAL FIX: WAKE UP THE BACKGROUND ENGINE! ---
                    startTrackingEngine()
                }
            }
            .show()
    }
}