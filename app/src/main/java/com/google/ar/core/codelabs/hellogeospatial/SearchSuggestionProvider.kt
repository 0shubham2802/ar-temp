package com.google.ar.core.codelabs.hellogeospatial

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Provider for search suggestions using Geocoder
 */
class SearchSuggestionProvider(private val context: Context) {

    companion object {
        private const val TAG = "SearchSuggestionProvider"
        private const val MAX_RESULTS = 5
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    /**
     * Interface for receiving search suggestions
     */
    interface SuggestionListener {
        fun onSuggestionsReady(suggestions: List<SearchSuggestion>)
        fun onError(message: String)
    }

    /**
     * Get suggestions for the given query
     * @param query The search query
     * @param listener The listener to receive suggestions
     */
    fun getSuggestions(query: String, listener: SuggestionListener) {
        // Don't search for very short queries
        if (query.length < 3) {
            listener.onSuggestionsReady(emptyList())
            return
        }

        executor.execute {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                
                // Get suggestions from Geocoder
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, MAX_RESULTS)
                
                // Process results
                val suggestions = processAddresses(addresses, query)
                
                // Deliver results on main thread
                mainThreadHandler.post {
                    listener.onSuggestionsReady(suggestions)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting search suggestions", e)
                mainThreadHandler.post {
                    listener.onError("Error getting suggestions: ${e.message}")
                }
            }
        }
    }

    /**
     * Process addresses from Geocoder into SearchSuggestion objects
     */
    private fun processAddresses(addresses: List<Address>?, query: String): List<SearchSuggestion> {
        if (addresses.isNullOrEmpty()) {
            return emptyList()
        }

        return addresses.mapNotNull { address ->
            try {
                // Skip addresses without lat/lng
                if (address.latitude == 0.0 && address.longitude == 0.0) {
                    return@mapNotNull null
                }

                // Create a meaningful title from the address
                val title = getAddressMainText(address, query)
                
                // Get a secondary text for the address
                val secondaryText = getAddressSecondaryText(address)
                
                // Create LatLng for the address
                val latLng = LatLng(address.latitude, address.longitude)
                
                // Create and return the suggestion
                SearchSuggestion(
                    title = title,
                    address = secondaryText,
                    latLng = latLng,
                    originalAddress = address
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing address", e)
                null
            }
        }
    }

    /**
     * Get the main text to display for an address
     */
    private fun getAddressMainText(address: Address, query: String): String {
        // First try to use the feature name (like a POI name)
        if (!address.featureName.isNullOrBlank() && address.featureName != address.thoroughfare) {
            return address.featureName
        }

        // Next try to use thoroughfare (street) + street number
        val thoroughfare = address.thoroughfare
        val streetNumber = address.subThoroughfare
        
        if (!thoroughfare.isNullOrBlank()) {
            if (!streetNumber.isNullOrBlank()) {
                return "$streetNumber $thoroughfare"
            }
            return thoroughfare
        }

        // Fall back to locality (city)
        if (!address.locality.isNullOrBlank()) {
            return address.locality
        }

        // Last resort: use the query
        return query
    }

    /**
     * Get the secondary text to display for an address
     */
    private fun getAddressSecondaryText(address: Address): String {
        val parts = mutableListOf<String>()
        
        // Add locality (city) if not already in main text
        if (!address.locality.isNullOrBlank() && 
            address.featureName != address.locality) {
            parts.add(address.locality)
        }
        
        // Add admin area (state/province)
        if (!address.adminArea.isNullOrBlank()) {
            parts.add(address.adminArea)
        }
        
        // Add country
        if (!address.countryName.isNullOrBlank()) {
            parts.add(address.countryName)
        }
        
        return parts.joinToString(", ")
    }
} 