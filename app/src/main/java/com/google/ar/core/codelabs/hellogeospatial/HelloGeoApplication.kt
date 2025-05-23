package com.google.ar.core.codelabs.hellogeospatial

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.google.ar.core.codelabs.hellogeospatial.helpers.GoogleApiKeyValidator

/**
 * Application class for global error handling
 */
class HelloGeoApplication : Application() {

    companion object {
        private const val TAG = "HelloGeoApplication"
        
        // Static instance for global access
        private lateinit var instance: HelloGeoApplication
        
        fun getInstance(): HelloGeoApplication {
            return instance
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Log device information for debugging
        logDeviceInfo()
        
        // Validate API keys early
        GoogleApiKeyValidator.validateApiKey(this)
        
        // Setup global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            try {
                Toast.makeText(applicationContext, 
                    "Application error: ${throwable.message}", 
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing toast for uncaught exception", e)
            }
            
            // Let the default handler handle it too
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }
        
        Log.d(TAG, "Application initialized")
    }
    
    /**
     * Logs device information for debugging API/device issues
     */
    private fun logDeviceInfo() {
        try {
            Log.d(TAG, "=== Device Information ===")
            Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            Log.d(TAG, "Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            Log.d(TAG, "Build: ${Build.DISPLAY}")
            
            // Check app version
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                Log.d(TAG, "App Version: ${packageInfo.versionName} (${packageInfo.versionCode})")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting package info", e)
            }
            
            // Log available features
            val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
            val hasLocation = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
            val hasGPS = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
            
            Log.d(TAG, "Camera available: $hasCamera")
            Log.d(TAG, "Location available: $hasLocation")
            Log.d(TAG, "GPS available: $hasGPS")
            
            // Check Google Play Services
            val googlePlayServicesInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
            Log.d(TAG, "Google Play Services version: ${googlePlayServicesInfo.versionName}")
            
            Log.d(TAG, "=========================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging device info", e)
        }
    }
} 