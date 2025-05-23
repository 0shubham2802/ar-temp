/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.codelabs.hellogeospatial.ARActivity
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.codelabs.hellogeospatial.SplitScreenActivity
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import java.io.IOException
import java.util.Locale
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.PI

/** Contains UI elements for Hello Geo. */
class HelloGeoView : DefaultLifecycleObserver {
  companion object {
    private const val TAG = "HelloGeoView"
  }
  
  // Main context and activity references
  private val context: Context
  private val appCompatActivity: AppCompatActivity?
  private val helloGeoActivity: HelloGeoActivity?
  private val arActivity: ARActivity?
  private val splitScreenActivity: SplitScreenActivity?
  
  // Map-related variables
  private var googleMap: GoogleMap? = null
  private var isMapInitialized = false
  private val onMapReadyListeners = mutableListOf<(GoogleMap) -> Unit>()
  
  // Root view containing all UI elements
  val root: View
  val surfaceView: GLSurfaceView
  val searchView: SearchView?
  
  // Add button container for navigation controls
  private val buttonContainer: LinearLayout
  
  // Map of action buttons by ID
  private val actionButtons = mutableMapOf<String, Button>()
  
  // Store text views separately to avoid casting issues
  private val textViews = mutableMapOf<String, TextView>()
  
  // Navigation related variables
  private var destinationSelectedListener: ((LatLng, String) -> Unit)? = null
  private var routePolyline: com.google.android.gms.maps.model.Polyline? = null
  private var isNavigationMode = false
  
  // Store navigation instructions
  private var navigationInstructions = mutableListOf<String>()
  private var currentInstructionIndex = 0

  val snackbarHelper = SnackbarHelper()

  var mapView: MapView? = null
  val mapTouchWrapper: MapTouchWrapper?
  val mapFragment: SupportMapFragment?
  val statusText: TextView?
  
  // Add MapErrorHelper at the class level
  private val mapErrorHelper: MapErrorHelper
  private var mapLoadAttempts = 0
  private val MAX_MAP_LOAD_ATTEMPTS = 3

  // Constructor for HelloGeoActivity
  constructor(activity: HelloGeoActivity) {
    this.context = activity
    this.appCompatActivity = activity
    this.helloGeoActivity = activity
    this.arActivity = null
    this.splitScreenActivity = null
    
    // Initialize error helper
    this.mapErrorHelper = MapErrorHelper(activity)
    
    // Initialize UI from activity_main layout
    root = View.inflate(activity, R.layout.activity_main, null)
    surfaceView = root.findViewById(R.id.surfaceview)
    searchView = root.findViewById(R.id.searchView)
    statusText = root.findViewById(R.id.statusText)
    
    // Initialize button container
    buttonContainer = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.BOTTOM or Gravity.END
      setPadding(0, 0, 32, 32)
    }
    
    // Set up map touch interaction
    mapTouchWrapper = root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
      setup { screenLocation ->
        val latLng: LatLng =
          mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
        helloGeoActivity.renderer.onMapClick(latLng)
      }
    }
    
    // Set up map fragment
    mapFragment = (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
      try {
        it.getMapAsync { googleMap -> 
          try {
            Log.d("HelloGeoView", "Map loaded successfully")
            mapView = MapView(activity, googleMap) 
          } catch (e: Exception) {
            Log.e("HelloGeoView", "Error initializing MapView: ${e.message}", e)
            showMapError(activity, "Error initializing map view: ${e.message}")
          }
        }
      } catch (e: Exception) {
        Log.e("HelloGeoView", "Error loading Google Maps: ${e.message}", e)
        showMapError(activity, "Error loading Google Maps. Please check your internet connection and API key.")
      }
    }
    
    // Initialize UI elements
    setupSearchView()
    setupButtonContainer()
  }
  
  // Constructor for ARActivity (simplified view with just AR elements)
  constructor(activity: ARActivity) {
    this.context = activity
    this.appCompatActivity = activity
    this.helloGeoActivity = null
    this.arActivity = activity
    this.splitScreenActivity = null
    
    // Initialize error helper
    this.mapErrorHelper = MapErrorHelper(activity)
    
    // For AR activity, we don't inflate a layout - we just need a reference to hold the surfaceView
    // The surfaceView itself is created and managed by the ARActivity
    root = LinearLayout(activity) // Dummy root view - not actually used
    surfaceView = GLSurfaceView(activity) // This will be replaced by ARActivity
    
    // These elements don't exist in AR mode
    searchView = null
    statusText = null
    mapTouchWrapper = null
    mapFragment = null
    
    // Initialize button container for possible AR controls
    buttonContainer = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.BOTTOM or Gravity.END
      setPadding(0, 0, 32, 32)
    }
    
    // No need for search view or map initialization in AR mode
  }
  
  // Constructor for SplitScreenActivity (similar to ARActivity constructor)
  constructor(activity: SplitScreenActivity) {
    this.context = activity
    this.appCompatActivity = activity
    this.helloGeoActivity = null
    this.arActivity = null
    this.splitScreenActivity = activity
    
    // Initialize error helper
    this.mapErrorHelper = MapErrorHelper(activity)
    
    // For split screen mode, we also just need a reference to the surfaceView
    // The actual surfaceView is managed by the SplitScreenActivity
    root = LinearLayout(activity) // Dummy root view - not actually used
    surfaceView = GLSurfaceView(activity) // This will be replaced by SplitScreenActivity
    
    // These elements don't exist in split screen mode as they're handled by the activity
    searchView = null
    statusText = null
    mapTouchWrapper = null
    mapFragment = null
    
    // Initialize button container for AR controls
    buttonContainer = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.BOTTOM or Gravity.END
      setPadding(0, 0, 32, 32)
    }
  }
  
  private fun setupButtonContainer() {
    if (root is FrameLayout) {
      val layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        bottomMargin = 320 // Position above the map
        rightMargin = 16
      }
      (root as FrameLayout).addView(buttonContainer, layoutParams)
    }
  }
  
  fun addActionButton(button: Button, id: String) {
    button.apply {
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        setMargins(0, 0, 0, 8)
      }
      
      setPadding(16, 8, 16, 8)
      
      // Apply styling
      setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
      setTextColor(Color.WHITE)
    }
    
    buttonContainer.addView(button)
    actionButtons[id] = button
  }
  
  fun getActionButton(id: String): Button? {
    return actionButtons[id]
  }
  
  fun setOnDestinationSelectedListener(listener: (LatLng, String) -> Unit) {
    this.destinationSelectedListener = listener
  }
  
  private fun setupSearchView() {
    // Only set up search view if it exists (in HelloGeoActivity mode)
    searchView?.apply {
      queryHint = "Search for a destination"
      
      // Set text color to black
      val searchText = this.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
      searchText?.setTextColor(Color.BLACK)
      searchText?.setHintTextColor(Color.GRAY)
      
      // Focus handling for better UX
      setOnQueryTextFocusChangeListener { _, hasFocus ->
        if (hasFocus) {
          // When search gets focus - clear any previous query
          if (query.isNotBlank()) {
            setQuery("", false)
          }
          
          // Move the map down slightly to provide focus on search
          mapTouchWrapper?.animate()
            ?.translationY(50f)
            ?.setDuration(200)
            ?.start()
        } else {
          // When search loses focus - restore map position
          mapTouchWrapper?.animate()
            ?.translationY(0f)
            ?.setDuration(200)
            ?.start()
        }
      }
      
      // Handle search submission
      setOnQueryTextListener(object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String?): Boolean {
          query?.let {
            searchLocation(it)
            clearFocus() // Hide keyboard after search
          }
          return true
        }

        override fun onQueryTextChange(newText: String?): Boolean {
          return false
        }
      })
    }
  }
  
  // Add method to set up the AR session
  fun setupSession(session: Session) {
    // Set the session for the GLSurfaceView
    surfaceView.preserveEGLContextOnPause = true
    surfaceView.setEGLContextClientVersion(2)
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
  }

  private fun searchLocation(locationName: String) {
    val geocoder = Geocoder(context, Locale.getDefault())
    
    try {
      // For compatibility, use methods safe for API level 30 or lower
      @Suppress("DEPRECATION")
      val addressList = geocoder.getFromLocationName(locationName, 1)
      
      if (addressList != null && addressList.isNotEmpty()) {
        val address = addressList[0]
        handleFoundLocation(address, locationName)
      } else {
        Toast.makeText(context, "Location not found", Toast.LENGTH_SHORT).show()
      }
    } catch (e: IOException) {
      Log.e("HelloGeoView", "Error in geocoding", e)
      Toast.makeText(context, "Error searching for location", Toast.LENGTH_SHORT).show()
    }
  }
  
  private fun handleFoundLocation(address: Address, locationName: String) {
    val latLng = LatLng(address.latitude, address.longitude)
    
    // Use the MapView's navigation method to handle the search location
    mapView?.navigateToSearchLocation(latLng, locationName)
    
    // Notify destination selected listener
    destinationSelectedListener?.invoke(latLng, locationName)
  }
  
  fun startNavigationMode(destination: LatLng) {
    isNavigationMode = true
    
    // Only create UI elements if we're in HelloGeoActivity mode with a FrameLayout root
    if (root is FrameLayout) {
      // Show distance to destination
      val distanceText = TextView(context).apply {
        text = "Preparing navigation..."
        setBackgroundColor(Color.argb(180, 0, 0, 0))
        setTextColor(Color.WHITE)
        setPadding(16, 8, 16, 8)
        gravity = Gravity.CENTER
      }
      
      val layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.TOP
        topMargin = 80 // Below the search bar
      }
      
      (root as FrameLayout).addView(distanceText, layoutParams)
      textViews["distance_text"] = distanceText
      
      // Add turn-by-turn direction indicator
      val directionText = TextView(context).apply {
        text = "Follow the blue path"
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        setTextColor(Color.WHITE)
        setPadding(16, 12, 16, 12)
        gravity = Gravity.CENTER
        textSize = 16f
      }
      
      val directionLayoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.BOTTOM
        bottomMargin = 150 // Above the navigation buttons
      }
      
      (root as FrameLayout).addView(directionText, directionLayoutParams)
      textViews["direction_text"] = directionText
    }
    
    // Add Google Maps option button - works in both modes
    helloGeoActivity?.let { activity ->
      val gmapsButton = Button(context).apply {
        text = "Open in Google Maps"
        setOnClickListener {
          activity.openGoogleMapsNavigation(destination)
        }
      }
      addActionButton(gmapsButton, "gmaps_button")
    }
    
    // Reset navigation instructions
    navigationInstructions.clear()
    currentInstructionIndex = 0
  }
  
  fun stopNavigationMode() {
    isNavigationMode = false
    
    try {
      // Remove navigation UI elements if they exist
      if (root is FrameLayout) {
        textViews["distance_text"]?.let {
          (root as FrameLayout).removeView(it)
          textViews.remove("distance_text")
        }
        
        textViews["direction_text"]?.let {
          (root as FrameLayout).removeView(it)
          textViews.remove("direction_text")
        }
      }
      
      // Remove Google Maps button
      actionButtons["gmaps_button"]?.let {
        buttonContainer.removeView(it)
        actionButtons.remove("gmaps_button")
      }
      
      // Clear route from map
      routePolyline?.remove()
      routePolyline = null
      
      // Clear navigation instructions
      navigationInstructions.clear()
      currentInstructionIndex = 0
    } catch (e: Exception) {
      Log.e("HelloGeoView", "Error stopping navigation mode", e)
    }
  }
  
  fun showRouteOnMap(origin: LatLng, destination: LatLng) {
    // Remove any existing route
    routePolyline?.remove()
    
    // Only draw on map if we have a map in this mode
    mapView?.googleMap?.let { googleMap ->
      // Add markers for start and end if not already there
      googleMap.clear() // Clear existing markers
      
      // Add start marker (blue)
      googleMap.addMarker(
        MarkerOptions()
          .position(origin)
          .title("Start")
          .snippet("Your current location")
      )
      
      // Add destination marker (red)
      mapView?.searchMarker = googleMap.addMarker(
        MarkerOptions()
          .position(destination)
          .title("Destination")
      )
      
      // Get directions 
      val directionsHelper = DirectionsHelper(context)
      
      directionsHelper.getDirections(origin, destination, object : DirectionsHelper.DirectionsListener {
        override fun onDirectionsReady(pathPoints: List<LatLng>) {
          appCompatActivity?.runOnUiThread {
            // Create polyline options with the route points
            val polylineOptions = PolylineOptions()
              .addAll(pathPoints)
              .width(8f)
              .color(Color.BLUE)
              .geodesic(true)
            
            // Add the polyline to the map
            routePolyline = googleMap.addPolyline(polylineOptions)
            
            // Zoom to show the whole route
            val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
            for (point in pathPoints) {
              boundsBuilder.include(point)
            }
            val bounds = boundsBuilder.build()
            
            // Add padding to the bounds
            val padding = 100 // pixels
            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding)
            googleMap.animateCamera(cameraUpdate)
          }
        }
        
        override fun onDirectionsError(errorMessage: String) {
          Log.e("HelloGeoView", "Error getting directions: $errorMessage")
          
          appCompatActivity?.runOnUiThread {
            // Show a dialog with the error message and options
            val context = appCompatActivity ?: this@HelloGeoView.context
            val alertDialog = AlertDialog.Builder(context)
              .setTitle("Error fetching directions")
              .setMessage("$errorMessage\n\nWould you like to retry or use a direct line?")
              .setPositiveButton("Retry") { _, _ ->
                // Retry getting directions
                directionsHelper.getDirections(origin, destination, this)
              }
              .setNegativeButton("Use Direct Line") { _, _ ->
                // Fallback to a simple straight line
                createFallbackRoute(origin, destination)
              }
              .setCancelable(false)
            
            try {
              alertDialog.show()
            } catch (e: Exception) {
              // If showing dialog fails, fallback silently to direct line
              Log.e("HelloGeoView", "Could not show error dialog, using fallback route", e)
              createFallbackRoute(origin, destination)
            }
          }
        }
        
        // Helper method to create a fallback direct route
        private fun createFallbackRoute(origin: LatLng, destination: LatLng) {
          val polylineOptions = PolylineOptions()
            .add(origin, destination)
            .width(8f)
            .color(Color.RED) // Use red to indicate fallback route
            .geodesic(true)
          
          // Add the polyline to the map
          routePolyline = googleMap.addPolyline(polylineOptions)
          
          // Zoom to show the whole route
          val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
          boundsBuilder.include(origin)
          boundsBuilder.include(destination)
          val bounds = boundsBuilder.build()
          
          // Add padding to the bounds
          val padding = 100 // pixels
          val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding)
          googleMap.animateCamera(cameraUpdate)
          
          // Show toast for direct route
          Toast.makeText(context, "Using direct route (as the crow flies)", Toast.LENGTH_SHORT).show()
        }
      })
    }
  }

  fun updateStatusText(earth: Earth?, cameraGeospatialPose: GeospatialPose?, status: String = "") {
    // Only update status if we have the statusText view (in HelloGeoActivity mode)
    statusText?.let { statusTextView ->
      appCompatActivity?.runOnUiThread {
        val poseText = if (cameraGeospatialPose == null) "" else
          context.getString(R.string.geospatial_pose,
                         cameraGeospatialPose.latitude,
                         cameraGeospatialPose.longitude,
                         cameraGeospatialPose.horizontalAccuracy,
                         cameraGeospatialPose.altitude,
                         cameraGeospatialPose.verticalAccuracy,
                         cameraGeospatialPose.heading,
                         cameraGeospatialPose.headingAccuracy)
                         
        if (earth == null) {
          val baseText = "Waiting for Earth to initialize..."
          statusTextView.text = if (status.isNotEmpty()) "$baseText ($status)" else baseText
        } else {
          val baseText = context.resources.getString(R.string.earth_state,
                                                  earth.earthState.toString(),
                                                  earth.trackingState.toString(),
                                                  poseText)
          statusTextView.text = if (status.isNotEmpty()) "$baseText\nStatus: $status" else baseText
        }
        
        // If in navigation mode, update distance to destination
        if (isNavigationMode && cameraGeospatialPose != null) {
          updateNavigationInfo(cameraGeospatialPose)
        }
      }
    }
  }
  
  private fun updateNavigationInfo(currentPose: GeospatialPose) {
    // Only update navigation info in HelloGeoActivity mode
    if (helloGeoActivity == null) return
    
    // Calculate distance to destination
    mapView?.searchMarker?.position?.let { destination ->
      val currentLatLng = LatLng(currentPose.latitude, currentPose.longitude)
      val distance = calculateDistance(
        currentLatLng.latitude, currentLatLng.longitude,
        destination.latitude, destination.longitude
      )
      
      // Update distance text
      textViews["distance_text"]?.let {
        val formattedDistance = if (distance < 1000) {
          "${distance.toInt()} meters"
        } else {
          String.format("%.1f km", distance / 1000)
        }
        it.text = "Distance to destination: $formattedDistance"
      }
      
      // Show arrival message if we're close to destination
      if (distance < 20 && navigationInstructions.isNotEmpty()) { // Within 20 meters
        textViews["direction_text"]?.let { textView ->
          appCompatActivity?.runOnUiThread {
            textView.text = "You have arrived at your destination"
            textView.setBackgroundColor(Color.argb(200, 0, 100, 0)) // Green background
          }
        }
      }
    }
  }
  
  private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // Earth radius in meters
    
    val latDistance = toRadians(lat2 - lat1)
    val lonDistance = toRadians(lon2 - lon1)
    
    val a = sin(latDistance / 2) * sin(latDistance / 2) +
            cos(toRadians(lat1)) * cos(toRadians(lat2)) *
            sin(lonDistance / 2) * sin(lonDistance / 2)
    
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    
    return R * c // Distance in meters
  }
  
  // Utility function for angle conversion
  private fun toRadians(degrees: Double): Double {
    return degrees * PI / 180.0
  }

  // Add a method to update tracking quality indicators
  fun updateTrackingQuality(quality: String, confidence: Double) {
    appCompatActivity?.runOnUiThread {
      // Only create indicators if we have a proper root view
      if (root is FrameLayout) {
        // Create or update the quality indicator
        var qualityText = textViews["quality_indicator"]
        
        if (qualityText == null) {
          // Create quality indicator if it doesn't exist
          qualityText = TextView(context).apply {
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            
            // Add shadow for better visibility
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            
            // Set rounded background
            background = ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame)
            
            // Position at top-right
            val layoutParams = FrameLayout.LayoutParams(
              FrameLayout.LayoutParams.WRAP_CONTENT,
              FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
              gravity = Gravity.TOP or Gravity.END
              topMargin = 80
              rightMargin = 16
            }
            
            (root as FrameLayout).addView(this, layoutParams)
            textViews["quality_indicator"] = this
          }
        }
        
        // Set appropriate background color based on quality
        val backgroundColor = when (quality) {
          "Excellent" -> Color.parseColor("#4CAF50") // Green
          "Good" -> Color.parseColor("#8BC34A") // Light Green
          "Fair" -> Color.parseColor("#FFC107") // Amber
          else -> Color.parseColor("#F44336") // Red
        }
        
        // Set text and background
        qualityText.apply {
          text = "Tracking: $quality"
          setBackgroundColor(backgroundColor)
        }
      }
    }
  }

  // Add alternate onResume and onPause methods without the owner parameter
  fun onResume() {
    try {
      surfaceView.onResume()
    } catch (e: Exception) {
      Log.e("HelloGeoView", "Error in onResume", e)
    }
  }

  fun onPause() {
    try {
      surfaceView.onPause()
    } catch (e: Exception) {
      Log.e("HelloGeoView", "Error in onPause", e)
    }
  }
  
  // Original lifecycle methods still needed for lifecycle observer pattern
  override fun onResume(owner: LifecycleOwner) {
    try {
      surfaceView.onResume()
    } catch (e: Exception) {
      Log.e("HelloGeoView", "Error in onResume", e)
    }
  }

  override fun onPause(owner: LifecycleOwner) {
    try {
      surfaceView.onPause()
    } catch (e: Exception) {
      Log.e("HelloGeoView", "Error in onPause", e)
    }
  }

  private fun updateMapMarker(latLng: LatLng) {
    try {
      mapView?.let { mapView ->
        mapView.googleMap?.let { googleMap ->
          if (mapView.earthMarker != null) {
            mapView.earthMarker?.position = latLng
            mapView.earthMarker?.isVisible = true
          } else {
            // Create marker if it doesn't exist
            mapView.createEarthMarker(latLng)
          }
        }
      }
    } catch (e: Exception) {
      Log.e("HelloGeoView", "Error updating map marker", e)
    }
  }

  // Helper method to safely update map UI
  fun updateMapUI(action: (mapView: MapView) -> Unit) {
    mapView?.let { mapViewInstance ->
      appCompatActivity?.runOnUiThread {
        try {
          action(mapViewInstance)
        } catch (e: Exception) {
          Log.e("HelloGeoView", "Error updating map UI", e)
        }
      }
    }
  }
  
  // Method to handle marker visibility
  fun setMarkerVisibility(markerId: String, isVisible: Boolean) {
    updateMapUI { mapView ->
      when (markerId) {
        "user" -> mapView.userLocationMarker?.isVisible = isVisible
        "earth" -> mapView.earthMarker?.isVisible = isVisible
        "search" -> mapView.searchMarker?.isVisible = isVisible
        else -> Log.d("HelloGeoView", "Unknown marker ID: $markerId")
      }
    }
  }

  private fun showMapError(activity: AppCompatActivity, errorMessage: String) {
    activity.runOnUiThread {
      try {
        // Create an error message
        val errorText = TextView(activity).apply {
          text = "Error loading map interface. Please restart the app."
          textSize = 18f
          gravity = Gravity.CENTER
          setTextColor(Color.BLACK)
        }
        
        // Find the map wrapper
        val mapWrapper = activity.findViewById<FrameLayout>(R.id.map_wrapper)
        mapWrapper?.removeAllViews()
        mapWrapper?.addView(errorText)
        
        // Log the detailed error
        Log.e("HelloGeoView", errorMessage)
        
        // Show the error as a toast
        Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show()
      } catch (e: Exception) {
        Log.e("HelloGeoView", "Error showing map error UI", e)
      }
    }
  }

  // Update the navigation instructions for turn-by-turn directions
  fun updateNavigationInstructions(instructions: List<String>) {
    navigationInstructions.clear()
    navigationInstructions.addAll(instructions)
    currentInstructionIndex = 0
    
    // Update the direction text if it exists
    textViews["direction_text"]?.let { textView ->
      if (navigationInstructions.isNotEmpty()) {
        appCompatActivity?.runOnUiThread {
          textView.text = navigationInstructions[0]
        }
      }
    }
  }
  
  // Set the current active instruction based on progress
  fun setCurrentNavigationInstruction(index: Int) {
    if (index >= 0 && index < navigationInstructions.size && index != currentInstructionIndex) {
      currentInstructionIndex = index
      
      // Update the direction text if it exists
      textViews["direction_text"]?.let { textView ->
        appCompatActivity?.runOnUiThread {
          textView.text = navigationInstructions[index]
          
          // Flash animation to draw attention to the new instruction
          textView.animate()
            .alpha(0.5f)
            .setDuration(200)
            .withEndAction {
              textView.animate()
                .alpha(1.0f)
                .setDuration(200)
                .start()
            }
            .start()
        }
      }
    }
  }

  // Set direction text - public accessor method for updating the navigation instructions
  fun setDirectionText(text: String) {
    appCompatActivity?.runOnUiThread {
      try {
        // Find or create the direction text view
        var directionTextView = textViews["direction_text"]
        
        if (directionTextView == null && root is FrameLayout) {
          // Create the text view if it doesn't exist yet
          directionTextView = TextView(context).apply {
            setText(text)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER
            textSize = 16f
            
            val layoutParams = FrameLayout.LayoutParams(
              FrameLayout.LayoutParams.MATCH_PARENT,
              FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
              gravity = Gravity.BOTTOM
              bottomMargin = 150
            }
            
            (root as FrameLayout).addView(this, layoutParams)
            textViews["direction_text"] = this
          }
        } else {
          // Update the existing text view
          directionTextView?.text = text
          
          // Flash animation to draw attention to the new instruction
          directionTextView?.let { view ->
            if (view.text != text) {
              view.text = text
              view.alpha = 1.0f
              view.animate()
                .alpha(0.7f)
                .setDuration(100)
                .withEndAction {
                  view.animate()
                    .alpha(1.0f)
                    .setDuration(100)
                    .start()
                }
                .start()
            }
          }
        }
      } catch (e: Exception) {
        Log.e("HelloGeoView", "Error setting direction text", e)
      }
    }
  }

  /**
   * Set up location tracking for the map view
   */
  private fun setupLocationTracking() {
    // This is a placeholder method that would normally handle location tracking
    // For now, we're just logging that it was called
    try {
      Log.d(TAG, "Setting up location tracking")
      // In a real implementation, this would initialize location services
      // and possibly show the user's current location on the map
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up location tracking", e)
    }
  }
}
