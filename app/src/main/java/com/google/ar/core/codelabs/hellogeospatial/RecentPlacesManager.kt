package com.google.ar.core.codelabs.hellogeospatial

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.LinkedList

/**
 * Manager class to handle storage and retrieval of recently visited places
 */
class RecentPlacesManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "RecentPlacesPrefs"
        private const val KEY_RECENT_PLACES = "recentPlaces"
        private const val MAX_RECENT_PLACES = 10
    }
    
    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Add a place to the recent places list
     */
    fun addRecentPlace(place: SearchSuggestion) {
        val recentPlaces = getRecentPlaces().toMutableList()
        
        // Remove the place if it already exists to avoid duplicates
        recentPlaces.removeAll { it.title == place.title && 
                                 it.latLng.latitude == place.latLng.latitude && 
                                 it.latLng.longitude == place.latLng.longitude }
        
        // Add the new place at the beginning of the list
        recentPlaces.add(0, place)
        
        // Keep only the most recent MAX_RECENT_PLACES
        val trimmedList = recentPlaces.take(MAX_RECENT_PLACES)
        
        // Save to SharedPreferences
        val json = gson.toJson(trimmedList)
        prefs.edit().putString(KEY_RECENT_PLACES, json).apply()
    }
    
    /**
     * Get the list of recent places
     */
    fun getRecentPlaces(): List<SearchSuggestion> {
        val json = prefs.getString(KEY_RECENT_PLACES, null) ?: return emptyList()
        
        val type = object : TypeToken<List<SearchSuggestion>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Clear all recent places
     */
    fun clearRecentPlaces() {
        prefs.edit().remove(KEY_RECENT_PLACES).apply()
    }
} 