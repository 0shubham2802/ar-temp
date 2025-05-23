package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.OnMapReadyCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced MapView that adds error handling capabilities
 */
class MapViewWrapper(context: Context) : SupportMapFragment() {
    companion object {
        private const val TAG = "MapViewWrapper"
        private const val MAP_LOAD_TIMEOUT_MS = 10000L // 10 seconds
    }
    
    private var onMapLoadErrorListener: (() -> Unit)? = null
    private val mapLoadingTimeoutHandler = Handler(Looper.getMainLooper())
    private val isMapLoaded = AtomicBoolean(false)
    
    init {
        val options = GoogleMapOptions()
            .compassEnabled(true)
            .zoomControlsEnabled(false)
        
        arguments = Bundle().apply {
            putParcelable("MapOptions", options)
        }
    }
    
    override fun getMapAsync(callback: OnMapReadyCallback) {
        isMapLoaded.set(false)
        
        // Start timeout timer
        mapLoadingTimeoutHandler.removeCallbacksAndMessages(null)
        mapLoadingTimeoutHandler.postDelayed({
            if (!isMapLoaded.get()) {
                Log.e(TAG, "Map loading timed out")
                onMapLoadErrorListener?.invoke()
            }
        }, MAP_LOAD_TIMEOUT_MS)
        
        // Wrap the original callback to detect successful loading
        super.getMapAsync { googleMap ->
            isMapLoaded.set(true)
            mapLoadingTimeoutHandler.removeCallbacksAndMessages(null)
            callback.onMapReady(googleMap)
        }
    }
    
    /**
     * Set a listener to be called when map loading fails
     */
    fun setOnMapLoadErrorListener(listener: () -> Unit) {
        this.onMapLoadErrorListener = listener
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mapLoadingTimeoutHandler.removeCallbacksAndMessages(null)
    }
} 