package com.HarshaTalap1474.proxitrack

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.HarshaTalap1474.proxitrack.data.AppDatabase
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val tvDeviceCount = findViewById<TextView>(R.id.tvDeviceCount)
        val btnLogout = findViewById<MaterialButton>(R.id.btnLogout)
        val dao = AppDatabase.getDatabase(this).trackingNodeDao()

        // Fetch total devices
        lifecycleScope.launch {
            dao.getTotalNodesCount().collectLatest { count ->
                tvDeviceCount.text = count.toString()
            }
        }

        // Handle Logout
        btnLogout.setOnClickListener {
            // 1. Erase login state
            val sharedPrefs = getSharedPreferences("ProxiTrackPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putBoolean("IS_LOGGED_IN", false).apply()

            // 2. Stop the background tracking engine to save battery
            stopService(Intent(this, ProxiTrackService::class.java))

            // 3. Route to login and clear app history stack
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }
}