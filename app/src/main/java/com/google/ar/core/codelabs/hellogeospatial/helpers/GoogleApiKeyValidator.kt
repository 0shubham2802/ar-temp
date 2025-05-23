package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Utility class to help debug Google Maps API key issues
 */
object GoogleApiKeyValidator {
    private const val TAG = "GoogleApiKeyValidator"
    
    /**
     * Validates and logs information about the Google Maps API key
     */
    fun validateApiKey(context: Context) {
        try {
            // Get the API key from the manifest
            val applicationInfo = context.packageManager.getApplicationInfo(
                context.packageName, 
                PackageManager.GET_META_DATA
            )
            
            val mapsApiKey = applicationInfo.metaData?.getString("com.google.android.geo.API_KEY")
            val arApiKey = applicationInfo.metaData?.getString("com.google.android.ar.API_KEY")
            
            // Log the keys (masked for security)
            if (mapsApiKey != null) {
                val maskedKey = maskApiKey(mapsApiKey)
                Log.d(TAG, "Google Maps API key found: $maskedKey")
                
                if (mapsApiKey.startsWith("AIza")) {
                    Log.d(TAG, "Google Maps API key format appears valid")
                } else {
                    Log.e(TAG, "Google Maps API key format does not appear valid")
                }
            } else {
                Log.e(TAG, "Google Maps API key not found in AndroidManifest.xml")
            }
            
            if (arApiKey != null) {
                val maskedKey = maskApiKey(arApiKey)
                Log.d(TAG, "AR API key found: $maskedKey")
            } else {
                Log.e(TAG, "AR API key not found in AndroidManifest.xml")
            }
            
            // Additional check - are the keys the same?
            if (mapsApiKey != null && arApiKey != null && mapsApiKey == arApiKey) {
                Log.d(TAG, "Maps and AR API keys are the same")
            } else if (mapsApiKey != null && arApiKey != null) {
                Log.w(TAG, "Maps and AR API keys are different")
            }
            
            // Log package information for API key configuration
            Log.d(TAG, "Package name: ${context.packageName}")
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                Log.d(TAG, "App version: ${packageInfo.versionName} (${packageInfo.versionCode})")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting package info", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error validating Google API keys", e)
        }
    }
    
    /**
     * Masks an API key for secure logging
     */
    private fun maskApiKey(apiKey: String): String {
        if (apiKey.length <= 8) return "***"
        
        val firstFour = apiKey.substring(0, 4)
        val lastFour = apiKey.substring(apiKey.length - 4)
        return "$firstFour....$lastFour"
    }
} 