package com.HarshaTalap1474.proxitrack

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Check if user is already logged in
        val sharedPrefs = getSharedPreferences("ProxiTrackPrefs", Context.MODE_PRIVATE)
        val isLoggedIn = sharedPrefs.getBoolean("IS_LOGGED_IN", false)

        if (isLoggedIn) {
            // Bypass login and go straight to Dashboard
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // 2. If not logged in, show the beautiful login screen
        setContentView(R.layout.activity_login)

        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val etEmail = findViewById<TextInputEditText>(R.id.etEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                // Simulate Cloud Auth Success
                sharedPrefs.edit().putBoolean("IS_LOGGED_IN", true).apply()

                Toast.makeText(this, "Authenticated Successfully", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish() // Destroy login activity so back button doesn't return here
            } else {
                Toast.makeText(this, "Please enter credentials", Toast.LENGTH_SHORT).show()
            }
        }
    }
}