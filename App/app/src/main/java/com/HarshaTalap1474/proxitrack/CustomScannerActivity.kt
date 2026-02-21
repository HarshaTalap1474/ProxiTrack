package com.HarshaTalap1474.proxitrack

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class CustomScannerActivity : AppCompatActivity() {
    private lateinit var captureManager: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_scanner)

        barcodeScannerView = findViewById(R.id.zxing_barcode_scanner)

        // CaptureManager handles all the camera lifecycle events automatically
        captureManager = CaptureManager(this, barcodeScannerView)
        captureManager.initializeFromIntent(intent, savedInstanceState)
        captureManager.decode() // Starts looking for QR codes
    }

    override fun onResume() {
        super.onResume()
        captureManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        captureManager.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureManager.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureManager.onSaveInstanceState(outState)
    }
}