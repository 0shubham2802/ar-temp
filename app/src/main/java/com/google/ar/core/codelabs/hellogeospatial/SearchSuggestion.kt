package com.google.ar.core.codelabs.hellogeospatial

import android.location.Address
import com.google.android.gms.maps.model.LatLng

/**
 * Data class representing a search suggestion with all relevant information
 */
data class SearchSuggestion(
    val title: String,
    val address: String,
    val latLng: LatLng,
    val originalAddress: Address? = null
) 