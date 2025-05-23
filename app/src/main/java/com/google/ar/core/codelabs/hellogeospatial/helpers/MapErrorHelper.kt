package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class to diagnose map loading issues and provide solutions
 */
class MapErrorHelper(private val context: Context) {
    companion object {
        private const val TAG = "MapErrorHelper"
        private const val PLAY_SERVICES_RESOLUTION_REQUEST = 9000
    }
    
    private val isCheckingConnection = AtomicBoolean(false)
    
    /**
     * Check for common map loading issues and provide solutions
     * @return true if a potential solution was found and applied
     */
    fun diagnoseMapsIssue(): Boolean {
        if (isCheckingConnection.getAndSet(true)) {
            Log.d(TAG, "Already diagnosing issues, skipping duplicate check")
            return false
        }
        
        try {
            // Check internet connection
            if (!isNetworkAvailable()) {
                Toast.makeText(context, 
                    "No internet connection. Please enable WiFi or mobile data.", 
                    Toast.LENGTH_LONG).show()
                // Open network settings
                try {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open network settings", e)
                }
                return false
            }
            
            // Check Google Play Services availability
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
            
            if (resultCode != ConnectionResult.SUCCESS) {
                if (googleApiAvailability.isUserResolvableError(resultCode)) {
                    Toast.makeText(context, 
                        "Google Play Services update required for Maps functionality", 
                        Toast.LENGTH_LONG).show()
                    return true
                } else {
                    Log.e(TAG, "Google Play Services not available and not fixable")
                    Toast.makeText(context, 
                        "This device is not supported for Google Maps functionality", 
                        Toast.LENGTH_LONG).show()
                    return false
                }
            }
            
            // Check API key configuration
            try {
                val resourceId = context.resources.getIdentifier(
                    "google_maps_key", 
                    "string", 
                    context.packageName
                )
                
                if (resourceId <= 0) {
                    Log.e(TAG, "Google Maps API key resource not found")
                    Toast.makeText(context, 
                        "Maps API key not configured correctly in the app", 
                        Toast.LENGTH_LONG).show()
                    return false
                }
                
                val apiKey = context.getString(resourceId)
                if (apiKey == "YOUR_API_KEY_HERE") {
                    Log.e(TAG, "Default API key placeholder found")
                    Toast.makeText(context, 
                        "Maps API key not set - using placeholder value", 
                        Toast.LENGTH_LONG).show()
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking API key", e)
            }
            
            // If we get here, basic requirements are met but map is still not loading
            // Could be a temporary network issue or server problem
            Toast.makeText(context, 
                "Map service temporarily unavailable. Please try again later.", 
                Toast.LENGTH_LONG).show()
            
            return false
            
        } finally {
            isCheckingConnection.set(false)
        }
    }
    
    /**
     * Check if the device has an active internet connection
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }
    }
} 