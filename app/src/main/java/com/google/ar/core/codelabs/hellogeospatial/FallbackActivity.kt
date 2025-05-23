package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.ar.core.ArCoreApk
import com.google.ar.core.codelabs.hellogeospatial.helpers.MapErrorHelper
import java.util.Locale
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A map-based activity with optional AR capabilities
 */
class FallbackActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mapFragment: SupportMapFragment
    private var googleMap: GoogleMap? = null
    private var destinationLatLng: LatLng? = null
    private var arModeButton: Button? = null
    private var mapLoadingTimeout: Handler? = null
    private var timeoutRunnable: Runnable? = null
    private var connectionCheckHandler: Handler? = null
    private var connectionCheckRunnable: Runnable? = null
    private var mapIsReady = false
    private var mapRetryCount = 0
    private var mapRetryHandler: Handler? = null
    private var mapRetryRunnable: Runnable? = null
    
    // Search suggestion components
    private lateinit var suggestionProvider: SearchSuggestionProvider
    private lateinit var placesAdapter: PlacesAdapter
    private lateinit var suggestionsList: RecyclerView
    private var searchQueryHandler = Handler(Looper.getMainLooper())
    private var lastSearchRunnable: Runnable? = null
    private lateinit var recentPlacesManager: RecentPlacesManager
    
    // Location and map components
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    companion object {
        private const val TAG = "FallbackActivity"
        private const val LOCATION_PERMISSION_CODE = 100
        private const val CAMERA_PERMISSION_CODE = 101
        private const val AR_MODE_REQUEST_CODE = 200
        private const val MAP_TIMEOUT_MS = 40000 // Increased to 40 seconds for very slow connections
        private const val CONNECTION_CHECK_INTERVAL_MS = 30000 // Check connection every 30 seconds
        private const val MAP_RETRY_INTERVAL_MS = 5000 // Retry every 5 seconds
        private const val MAX_MAP_RETRIES = 3 // Maximum number of automatic retries
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set a default uncaught exception handler to catch and log any unexpected errors
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            runOnUiThread {
                Toast.makeText(this, "Error: ${throwable.message}", Toast.LENGTH_LONG).show()
                showMapErrorUI("Application error: ${throwable.message}")
            }
        }
        
        try {
            // Set content view from layout XML first, to avoid issues with findViewById later
            setContentView(R.layout.activity_fallback)
            
            // Set up toolbar
            val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(true)
            
            // Perform Google Play Services check first
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
            
            if (resultCode != ConnectionResult.SUCCESS) {
                Log.e(TAG, "Google Play Services unavailable: ${resultCode}")
                if (googleApiAvailability.isUserResolvableError(resultCode)) {
                    Log.i(TAG, "Showing Google Play Services resolution dialog")
                    googleApiAvailability.getErrorDialog(this, resultCode, 1001)?.show()
                    return
                } else {
                    showMapErrorUI("Google Play Services unavailable and cannot be resolved")
                    return
                }
            }
            
            // Diagnose potential map issues
            val mapErrorHelper = MapErrorHelper(this)
            val mapIssuesFound = mapErrorHelper.diagnoseMapsIssue()
            if (mapIssuesFound) {
                Log.w(TAG, "Map issues detected and handled by MapErrorHelper")
            }
            
            // Initialize components
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            // Initialize search suggestion provider
            suggestionProvider = SearchSuggestionProvider(this)
            
            // Initialize recent places manager
            recentPlacesManager = RecentPlacesManager(this)
            
            // Check for location permission
            checkLocationPermission()
            
            // Set up the map
            mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
            mapFragment.getMapAsync(this)
            
            // Set up map loading timeout
            setupMapLoadingTimeout()
            
            // Set up UI elements
            setupUIControls()
            
            // Set up search suggestions
            setupSearchSuggestions()
            
            // Setup AR mode button
            try {
                val arModeButton = findViewById<Button>(R.id.arModeButton)
                arModeButton?.setOnClickListener {
                    launchARMode()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up AR mode button", e)
            }
            
            // Setup Split Screen button
            try {
                val splitScreenButton = findViewById<Button>(R.id.splitScreenButton)
                splitScreenButton?.setOnClickListener {
                    launchSplitScreenMode()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up split screen button", e)
            }
            
            // Setup navigation button
            try {
                val navigateButton = findViewById<Button>(R.id.navigateButton)
                navigateButton?.setOnClickListener {
                    destinationLatLng?.let { destination ->
                        openGoogleMapsNavigation(destination)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up navigation button", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing map view: ${e.message}", Toast.LENGTH_LONG).show()
            
            // Create a simple fallback for the fallback
            showMapErrorUI("Error initializing map view: ${e.message}")
        }
    }
    
    private fun setupUIControls() {
        // Set up search bar
        val searchBar = findViewById<EditText>(R.id.searchBar)
        searchBar.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString()
                if (query.isNotBlank()) {
                    hideSuggestions()
                    searchLocation(query)
                    hideKeyboard()
                    return@setOnEditorActionListener true
                }
            }
            return@setOnEditorActionListener false
        }
        
        // Set up text change listener for search suggestions
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Cancel any pending search
                lastSearchRunnable?.let { searchQueryHandler.removeCallbacks(it) }
                
                val query = s?.toString() ?: ""
                
                if (query.length < 3) {
                    // Show recent places if search field has focus
                    if (searchBar.hasFocus()) {
                        showRecentPlacesOnly()
                    } else {
                        hideSuggestions()
                    }
                    return
                }
                
                // Delay the search to avoid too many requests while typing
                val searchRunnable = Runnable {
                    suggestionProvider.getSuggestions(query, object : SearchSuggestionProvider.SuggestionListener {
                        override fun onSuggestionsReady(suggestions: List<SearchSuggestion>) {
                            if (suggestions.isEmpty()) {
                                showRecentPlacesOnly()
                            } else {
                                showSuggestions(suggestions)
                            }
                        }
                        
                        override fun onError(message: String) {
                            showRecentPlacesOnly()
                            Log.e(TAG, "Error getting suggestions: $message")
                        }
                    })
                }
                
                lastSearchRunnable = searchRunnable
                searchQueryHandler.postDelayed(searchRunnable, 300) // 300ms delay
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Set up focus change listener
        searchBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // If query already has content, show suggestions
                val query = searchBar.text.toString()
                if (query.length >= 3) {
                    // Trigger the text changed listener
                    searchBar.setText(query)
                } else {
                    // Show recent places if no query
                    showRecentPlacesOnly()
                }
            } else {
                // Hide suggestions when focus is lost
                hideSuggestions()
            }
        }
        
        // Setup AR mode button
        try {
            val arModeButton = findViewById<Button>(R.id.arModeButton)
            arModeButton?.setOnClickListener {
                launchARMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AR mode button", e)
        }
        
        // Setup Split Screen button
        try {
            val splitScreenButton = findViewById<Button>(R.id.splitScreenButton)
            splitScreenButton?.setOnClickListener {
                launchSplitScreenMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up split screen button", e)
        }
        
        // Setup navigation button
        try {
            val navigateButton = findViewById<Button>(R.id.navigateButton)
            navigateButton?.setOnClickListener {
                destinationLatLng?.let { destination ->
                    openGoogleMapsNavigation(destination)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up navigation button", e)
        }
    }
    
    private fun setupMapLoadingTimeout() {
        mapLoadingTimeout = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            if (!mapIsReady) {
                Log.e(TAG, "Map loading timed out")
                runOnUiThread {
                    // Check network connectivity
                    val isConnected = isNetworkAvailable()
                    val errorMessage = if (isConnected) {
                        "Map loading timed out. Possible API key issue or service unavailable."
                    } else {
                        "Map loading timed out - check internet connection"
                    }
                    
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                    
                    // Try to reload map automatically instead of showing error UI immediately
                    tryMapRetry(errorMessage)
                }
            }
        }
        
        // Set a longer timeout for map loading on slow connections
        mapLoadingTimeout?.postDelayed(timeoutRunnable!!, MAP_TIMEOUT_MS.toLong())
    }
    
    private fun setupPeriodicConnectionCheck() {
        connectionCheckHandler = Handler(Looper.getMainLooper())
        connectionCheckRunnable = Runnable {
            if (mapIsReady && !isNetworkAvailable()) {
                // We lost network connection - inform user
                Toast.makeText(this, "Network connection lost. Map functionality may be limited.", Toast.LENGTH_LONG).show()
                
                // Add a refresh button to the map if it's not already there
                addRefreshButtonToMap()
            }
            
            // Schedule the next check
            connectionCheckHandler?.postDelayed(connectionCheckRunnable!!, CONNECTION_CHECK_INTERVAL_MS.toLong())
        }
        
        // Start checking connectivity periodically
        connectionCheckHandler?.postDelayed(connectionCheckRunnable!!, CONNECTION_CHECK_INTERVAL_MS.toLong())
    }
    
    private fun addRefreshButtonToMap() {
        // Check if we already have a refresh button
        if (findViewById<Button>(R.id.map_refresh_button) == null) {
            val container = findViewById<LinearLayout>(R.id.container)
            if (container != null) {
                val refreshButton = Button(this).apply {
                    id = R.id.map_refresh_button
                    text = "Refresh Map"
                    setBackgroundColor(ContextCompat.getColor(this@FallbackActivity, android.R.color.holo_orange_light))
                    setTextColor(Color.WHITE)
                    
                    val layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(16, 0, 16, 16)
                    }
                    
                    setOnClickListener {
                        if (isNetworkAvailable()) {
                            Toast.makeText(this@FallbackActivity, "Refreshing map...", Toast.LENGTH_SHORT).show()
                            recreateMapIfNeeded()
                            this.visibility = View.GONE
                        } else {
                            Toast.makeText(this@FallbackActivity, "Still no network connection available", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                
                try {
                    container.addView(refreshButton, 0) // Add at the top
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding refresh button", e)
                }
            }
        }
    }
    
    private fun recreateMapIfNeeded() {
        try {
            // Try to reload the map if it's not working
            mapFragment.getMapAsync { map ->
                googleMap = map
                setupMap(findViewById(R.id.navigateButton))
                
                // Restore destination if we had one
                destinationLatLng?.let { dest ->
                    googleMap?.apply {
                        clear()
                        addMarker(MarkerOptions().position(dest).title("Destination"))
                        animateCamera(CameraUpdateFactory.newLatLngZoom(dest, 15f))
                    }
                    findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing map", e)
            Toast.makeText(this, "Error refreshing map: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // This is the OnMapReadyCallback implementation
    override fun onMapReady(map: GoogleMap) {
        try {
            // Cancel the timeout since map loaded successfully
            mapLoadingTimeout?.removeCallbacks(timeoutRunnable!!)
            
            Log.d(TAG, "Google Map is ready")
            googleMap = map
            mapIsReady = true
            mapRetryCount = 0 // Reset retry count on successful load
            
            // Apply lower resource usage settings when possible
            try {
                // Load map style from a string instead of a resource file
                val success = map.setMapStyle(
                    MapStyleOptions("""
                    [
                      {
                        "featureType": "poi",
                        "elementType": "all",
                        "stylers": [
                          {
                            "visibility": "off"
                          }
                        ]
                      },
                      {
                        "featureType": "transit",
                        "elementType": "all",
                        "stylers": [
                          {
                            "visibility": "simplified"
                          }
                        ]
                      },
                      {
                        "featureType": "road",
                        "elementType": "labels",
                        "stylers": [
                          {
                            "visibility": "simplified"
                          }
                        ]
                      }
                    ]
                    """.trimIndent())
                )
                if (!success) {
                    Log.e(TAG, "Map style parsing failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply map style", e)
                // Continue without custom style
            }
            
            // Hide the loading indicator
            findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
            
            // Hide any error messages that might be showing
            findViewById<View>(R.id.map_error_container)?.visibility = View.GONE
            
            // Setup the map with controls and current location
            setupMap(findViewById(R.id.navigateButton))
            
            // Setup periodic connection checking
            setupPeriodicConnectionCheck()
            
            // Check if map tiles loaded properly
            map.setOnMapLoadedCallback {
                Log.d(TAG, "Map tiles loaded successfully")
                // Additional check after map is fully loaded to verify there are no rendering issues
                if (findViewById<View>(R.id.map) != null && 
                    findViewById<View>(R.id.map).visibility == View.VISIBLE) {
                    // Map is visible and loaded successfully
                } else {
                    Log.e(TAG, "Map view is not visible after load")
                    tryMapRetry("Map view is not visible after load")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onMapReady", e)
            Toast.makeText(this, "Error initializing map: ${e.message}", Toast.LENGTH_LONG).show()
            tryMapRetry("Error in onMapReady: ${e.message}")
        }
    }
    
    private fun isARCorePotentiallySupported(): Boolean {
        return try {
            // Check if ARCore is installed
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            availability == ArCoreApk.Availability.SUPPORTED_INSTALLED || 
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ARCore availability", e)
            false
        }
    }
    
    private fun launchARMode() {
        // First check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
            return
        }
        
        try {
            // Check if ARActivity exists
            val arActivityClass = try {
                Class.forName("com.google.ar.core.codelabs.hellogeospatial.ARActivity")
                true
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "ARActivity class not found", e)
                false
            }
            
            if (!arActivityClass) {
                Toast.makeText(
                    this,
                    "AR mode is not available in this version of the app", 
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            
            // Launch the AR activity but keep this one in background
            val arIntent = Intent(this, ARActivity::class.java)
            
            // Pass destination data if we have it
            destinationLatLng?.let {
                arIntent.putExtra("DESTINATION_LAT", it.latitude)
                arIntent.putExtra("DESTINATION_LNG", it.longitude)
            }
            
            startActivityForResult(arIntent, AR_MODE_REQUEST_CODE)
            
            Toast.makeText(this, "Starting AR Mode - please wait", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch AR mode", e)
            Toast.makeText(this, "Failed to launch AR mode: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == AR_MODE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // AR worked successfully, potentially with a new destination
                data?.let {
                    if (it.hasExtra("DESTINATION_LAT") && it.hasExtra("DESTINATION_LNG")) {
                        val lat = it.getDoubleExtra("DESTINATION_LAT", 0.0)
                        val lng = it.getDoubleExtra("DESTINATION_LNG", 0.0)
                        
                        if (lat != 0.0 && lng != 0.0) {
                            // Update the map with the new destination
                            val newDest = LatLng(lat, lng)
                            destinationLatLng = newDest
                            
                            googleMap?.apply {
                                clear()
                                addMarker(MarkerOptions().position(newDest).title("AR Destination"))
                                animateCamera(CameraUpdateFactory.newLatLngZoom(newDest, 15f))
                            }
                            
                            // Show navigation button
                            findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                        }
                    }
                }
            } else if (resultCode == RESULT_CANCELED) {
                // AR mode was cancelled or failed
                Toast.makeText(this, "Returned to map mode", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupMap(navigateButton: Button) {
        try {
            googleMap?.apply {
                // Set a lower max zoom level to reduce memory usage
                setMinZoomPreference(5f) // Don't allow extreme zoom out (world view)
                setMaxZoomPreference(18f) // Limit maximum zoom to reduce memory usage
                
                uiSettings.apply {
                    isZoomControlsEnabled = true
                    isCompassEnabled = true
                    isMyLocationButtonEnabled = true
                    // Disable memory-intensive features
                    isIndoorLevelPickerEnabled = false
                    isMapToolbarEnabled = false
                    // Use less precise but more stable tile rendering
                    isRotateGesturesEnabled = false // Disable rotation to save memory
                }
                
                // Enable my location layer if we have permission
                if (hasLocationPermission()) {
                    try {
                        isMyLocationEnabled = true
                        
                        // Get current location and move camera to it
                        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                        // Try to get last known location from multiple providers
                        val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) 
                                       ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                       ?: locationManager.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
                        
                        if (location != null) {
                            // Move camera to user's location
                            val currentLatLng = LatLng(location.latitude, location.longitude)
                            moveCamera(CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(currentLatLng)
                                    .zoom(15f)
                                    .tilt(0f) // No tilt = less rendering complexity
                                    .bearing(0f) // No rotation = less rendering complexity
                                    .build()
                            ))
                        } else {
                            // Fallback to default location if can't get current location
                            Log.d(TAG, "Could not get current location, using default")
                            moveCamera(CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(37.7749, -122.4194)) // Default: San Francisco
                                    .zoom(12f)
                                    .tilt(0f) // No tilt = less rendering complexity
                                    .bearing(0f) // No rotation = less rendering complexity
                                    .build()
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not enable my location or get position", e)
                        // Fallback to default location
                        moveCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(37.7749, -122.4194)) // Default: San Francisco
                                .zoom(12f)
                                .tilt(0f) // No tilt = less rendering complexity
                                .bearing(0f) // No rotation = less rendering complexity
                                .build()
                        ))
                    }
                } else {
                    // No location permission, use default location
                    moveCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(37.7749, -122.4194)) // Default: San Francisco
                            .zoom(12f)
                            .tilt(0f) // No tilt = less rendering complexity
                            .bearing(0f) // No rotation = less rendering complexity
                            .build()
                    ))
                }
                
                // Add click listener to allow selecting a point on the map
                try {
                    setOnMapClickListener { latLng ->
                        // Clear previous markers
                        clear()
                        
                        // Add new marker
                        addMarker(MarkerOptions()
                            .position(latLng)
                            .title("Selected Location"))
                        
                        // Store as destination
                        destinationLatLng = latLng
                        
                        // Show navigation button
                        navigateButton.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not set map click listener", e)
                }
                
                // Add a camera idle listener to monitor for stuck rendering
                setOnCameraIdleListener {
                    // Reset any stuck rendering issues when camera stops moving
                    resetMapIfStuck()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up map", e)
            Toast.makeText(this, "Error with map controls: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private var lastMapReset = 0L
    
    private fun resetMapIfStuck() {
        val currentTime = System.currentTimeMillis()
        
        // Only reset once every 5 minutes at most to avoid infinite loops
        if (currentTime - lastMapReset > 5 * 60 * 1000) {
            if (!isMapResponsive()) {
                Log.d(TAG, "Map appears to be stuck, attempting reset")
                // Force a small camera movement to refresh the view
                googleMap?.moveCamera(CameraUpdateFactory.scrollBy(1f, 1f))
                lastMapReset = currentTime
            }
        }
    }
    
    private fun isMapResponsive(): Boolean {
        // Just a placeholder - in a real app, we'd check for actual rendering issues
        // This is difficult to detect, but we can assume if the device is low on memory
        // the map might be unresponsive
        val runtime = Runtime.getRuntime()
        val usedMemoryPercentage = (runtime.totalMemory() - runtime.freeMemory()) * 100 / runtime.maxMemory()
        
        return usedMemoryPercentage < 80 // If more than 80% of memory is used, map might be struggling
    }
    
    private fun searchLocation(query: String) {
        try {
            // Show loading indicator when searching
            findViewById<View>(R.id.map_loading_container)?.visibility = View.VISIBLE
            findViewById<TextView>(R.id.map_loading_text)?.text = "Searching for location..."
            
            // Hide any suggestions
            hideSuggestions()
            
            // First check network connectivity
            if (!isNetworkAvailable()) {
                // Hide loading indicator
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                Toast.makeText(this, "Network unavailable. Please check your internet connection.", Toast.LENGTH_LONG).show()
                return
            }
            
            // Limit search query length to avoid issues
            val sanitizedQuery = if (query.length > 100) query.substring(0, 100) else query
            
            // Use a background thread for geocoding to avoid ANRs
            Thread {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    var addresses = emptyList<android.location.Address>()
                    
                    try {
                        // Use the appropriate geocoding method depending on Android version
                        @Suppress("DEPRECATION")
                        addresses = geocoder.getFromLocationName(sanitizedQuery, 1) ?: emptyList()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error with geocoder", e)
                        runOnUiThread {
                            // Hide loading indicator
                            findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                            Toast.makeText(this, "Error looking up location: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    
                    runOnUiThread {
                        // Hide loading indicator
                        findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                        
                        if (addresses.isNotEmpty()) {
                            val address = addresses[0]
                            val latLng = LatLng(address.latitude, address.longitude)
                            
                            try {
                                // Create a suggestion from the address
                                val mainText = if (!address.featureName.isNullOrBlank()) address.featureName else query
                                val secondaryText = address.getAddressLine(0) ?: ""
                                
                                val suggestion = SearchSuggestion(
                                    title = mainText,
                                    address = secondaryText,
                                    latLng = latLng,
                                    originalAddress = address
                                )
                                
                                googleMap?.apply {
                                    clear()
                                    addMarker(MarkerOptions().position(latLng).title(suggestion.title))
                                    animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                                }
                                
                                // Store as destination
                                destinationLatLng = latLng
                                
                                // Show the navigation button
                                findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                                
                                // Add to recent places
                                recentPlacesManager.addRecentPlace(suggestion)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating map with search result", e)
                                Toast.makeText(this, "Found location but couldn't display on map", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this, "Location not found. Try a more specific search.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in geocoding thread", e)
                    runOnUiThread {
                        // Hide loading indicator
                        findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                        Toast.makeText(this, "Error searching for location", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } catch (e: Exception) {
            // Hide loading indicator if an error occurs
            findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
            
            Log.e(TAG, "Error in searchLocation", e)
            Toast.makeText(this, "Error searching for location", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openGoogleMapsNavigation(destination: LatLng) {
        val uri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)
        mapIntent.setPackage("com.google.android.apps.maps")
        
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            Toast.makeText(this, "Google Maps app is not installed", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun checkLocationPermission() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
        }
    }
    
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, enable my location on the map
                    try {
                        googleMap?.isMyLocationEnabled = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not enable my location", e)
                    }
                } else {
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Camera permission granted, now launch AR mode
                    launchARMode()
                } else {
                    Toast.makeText(this, "Camera permission is required for AR mode", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showMapErrorUI(errorMessage: String) {
        try {
            Log.e(TAG, "Showing map error UI: $errorMessage")
            
            // Create a completely new layout to avoid any issues with existing views
            val mainLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.WHITE)
            }
            
            // Add title
            val titleText = TextView(this).apply {
                text = "AR Navigation (Map Mode)"
                gravity = Gravity.CENTER
                textSize = 20f
                setTextColor(Color.BLACK)
                setPadding(16, 16, 16, 16)
            }
            mainLayout.addView(titleText)
            
            // Add subtitle
            val subtitleText = TextView(this).apply {
                text = "Your device doesn't fully support AR features. Using map-only mode."
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(16, 0, 16, 32)
            }
            mainLayout.addView(subtitleText)
            
            // Add error text
            val errorText = TextView(this).apply {
                text = "Error loading map interface. Please restart the app.\n\n$errorMessage"
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(Color.BLACK)
                setPadding(32, 32, 32, 32)
            }
            mainLayout.addView(errorText)
            
            // Create a button container for multiple options
            val buttonContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            // Add retry button
            val retryButton = Button(this).apply {
                text = "RETRY"
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 8, 16, 8)
                }
                
                setOnClickListener {
                    // Restart the activity
                    val intent = intent
                    finish()
                    startActivity(intent)
                }
            }
            buttonContainer.addView(retryButton)
            
            // Add option to use maps app directly
            val openMapsButton = Button(this).apply {
                text = "OPEN GOOGLE MAPS APP"
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 8, 16, 8)
                }
                
                setOnClickListener {
                    // Open Google Maps app
                    val gmmIntentUri = Uri.parse("geo:0,0?q=restaurants")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    if (mapIntent.resolveActivity(packageManager) != null) {
                        startActivity(mapIntent)
                    } else {
                        Toast.makeText(context, "Google Maps app not installed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            buttonContainer.addView(openMapsButton)
            
            // Add option to use browser maps
            val openBrowserButton = Button(this).apply {
                text = "OPEN MAPS IN BROWSER"
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 8, 16, 24)
                }
                
                setOnClickListener {
                    // Open Google Maps in browser
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com"))
                    startActivity(browserIntent)
                }
            }
            buttonContainer.addView(openBrowserButton)
            
            mainLayout.addView(buttonContainer)
            
            // Set the completely new content view
            setContentView(mainLayout)
            
            // Log the error for debugging
            Log.e(TAG, "Map error: $errorMessage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show map error UI", e)
            
            // Last resort - create a minimal UI with just an error message and retry button
            try {
                val simpleLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.WHITE)
                }
                
                val simpleText = TextView(this).apply {
                    text = "Error loading map interface. Please restart the app or check your internet connection."
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTextColor(Color.BLACK)
                    setPadding(32, 32, 32, 32)
                }
                
                val simpleButton = Button(this).apply {
                    text = "RETRY"
                    setBackgroundColor(Color.BLUE)
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        recreate()
                    }
                }
                
                simpleLayout.addView(simpleText)
                simpleLayout.addView(simpleButton)
                setContentView(simpleLayout)
            } catch (t: Throwable) {
                // At this point, there's not much else we can do
                Log.e(TAG, "Fatal error creating UI", t)
            }
        }
    }
    
    private fun launchSplitScreenMode() {
        // First check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
            return
        }
        
        try {
            val splitScreenIntent = Intent(this, SplitScreenActivity::class.java)
            
            // Pass destination data if we have it
            destinationLatLng?.let {
                splitScreenIntent.putExtra("DESTINATION_LAT", it.latitude)
                splitScreenIntent.putExtra("DESTINATION_LNG", it.longitude)
            }
            
            startActivity(splitScreenIntent)
            Toast.makeText(this, "Loading split screen mode - please wait", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch split screen mode", e)
            Toast.makeText(this, "Failed to launch split screen mode: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                       capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                // Legacy method for older Android versions
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                return networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network state", e)
            return false
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Clean up handlers to prevent memory leaks
        connectionCheckHandler?.removeCallbacks(connectionCheckRunnable!!)
    }
    
    override fun onResume() {
        super.onResume()
        
        // Resume connection checking
        if (mapIsReady) {
            connectionCheckHandler?.postDelayed(connectionCheckRunnable!!, CONNECTION_CHECK_INTERVAL_MS.toLong())
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up all handlers
        mapLoadingTimeout?.removeCallbacks(timeoutRunnable!!)
        connectionCheckHandler?.removeCallbacks(connectionCheckRunnable!!)
        mapRetryHandler?.removeCallbacks(mapRetryRunnable!!)
        
        // Clear map reference to prevent memory leaks
        googleMap = null
    }
    
    private fun tryMapRetry(error: String) {
        // Only retry a certain number of times
        if (mapRetryCount < MAX_MAP_RETRIES) {
            mapRetryCount++
            
            // Show retry message
            Toast.makeText(
                this, 
                "Map service temporarily unavailable. Retrying (${mapRetryCount}/${MAX_MAP_RETRIES})...", 
                Toast.LENGTH_SHORT
            ).show()
            
            // Create a clear visual indicator that we're retrying
            val mapContainer = findViewById<FrameLayout>(R.id.map_container)
            
            var errorContainer = findViewById<LinearLayout>(R.id.map_error_container)
            if (errorContainer == null) {
                errorContainer = LinearLayout(this).apply {
                    id = R.id.map_error_container
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.argb(200, 0, 0, 0))
                    
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.BOTTOM
                    }
                }
                
                // Add to map container if it exists
                mapContainer?.addView(errorContainer)
            }
            
            // Add or update the error message
            var errorText = errorContainer.findViewById<TextView>(R.id.map_error_text)
            if (errorText == null) {
                errorText = TextView(this).apply {
                    id = R.id.map_error_text
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(16, 16, 16, 16)
                }
                errorContainer.addView(errorText)
            }
            
            errorText.text = "Map service temporarily unavailable. Retrying..."
            errorContainer.visibility = View.VISIBLE
            
            // Schedule retry after delay
            mapRetryHandler = Handler(Looper.getMainLooper())
            mapRetryRunnable = Runnable {
                Log.d(TAG, "Retrying map load (attempt $mapRetryCount)")
                
                // Reload the map fragment
                recreateMapIfNeeded()
            }
            
            mapRetryHandler?.postDelayed(mapRetryRunnable!!, MAP_RETRY_INTERVAL_MS.toLong())
        } else {
            // If we've exceeded retries, show the full error UI
            showMapErrorUI(error)
        }
    }
    
    private fun setupSearchSuggestions() {
        // Get the suggestions RecyclerView
        suggestionsList = findViewById(R.id.suggestionsList)
        
        // Set up the adapter
        placesAdapter = PlacesAdapter(
            onItemClickListener = { suggestion ->
                // Handle suggestion click
                hideSuggestions()
                hideKeyboard()
                handleSelectedSuggestion(suggestion)
            },
            onClearRecentPlacesListener = {
                // Clear recent places
                recentPlacesManager.clearRecentPlaces()
                refreshRecentPlaces()
            }
        )
        
        // Set up the RecyclerView
        suggestionsList.apply {
            layoutManager = LinearLayoutManager(this@FallbackActivity)
            adapter = placesAdapter
            setHasFixedSize(true)
        }
        
        // Initially hide suggestions
        hideSuggestions()
        
        // Set up touch listener to dismiss suggestions when clicking outside
        findViewById<View>(android.R.id.content).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && suggestionsList.visibility == View.VISIBLE) {
                // Check if touch is outside suggestions
                val location = IntArray(2)
                suggestionsList.getLocationOnScreen(location)
                val x = event.rawX
                val y = event.rawY
                
                if (x < location[0] || x > location[0] + suggestionsList.width ||
                    y < location[1] || y > location[1] + suggestionsList.height) {
                    hideSuggestions()
                    hideKeyboard()
                }
            }
            false
        }
    }
    
    private fun refreshRecentPlaces() {
        // Get recent places and update adapter
        val recentPlaces = recentPlacesManager.getRecentPlaces()
        placesAdapter.setRecentPlaces(recentPlaces)
    }
    
    private fun showSuggestions(suggestions: List<SearchSuggestion>) {
        placesAdapter.updateSuggestions(suggestions)
        
        // Load recent places when showing suggestions
        refreshRecentPlaces()
        
        suggestionsList.visibility = View.VISIBLE
    }
    
    private fun showRecentPlacesOnly() {
        placesAdapter.updateSuggestions(emptyList())
        refreshRecentPlaces()
        suggestionsList.visibility = View.VISIBLE
    }
    
    private fun hideSuggestions() {
        suggestionsList.visibility = View.GONE
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }
    
    private fun handleSelectedSuggestion(suggestion: SearchSuggestion) {
        // Update search bar with selected suggestion
        findViewById<EditText>(R.id.searchBar).setText(suggestion.title)
        
        // Update map with the selected location
        googleMap?.apply {
            clear()
            addMarker(MarkerOptions().position(suggestion.latLng).title(suggestion.title))
            animateCamera(CameraUpdateFactory.newLatLngZoom(suggestion.latLng, 15f))
        }
        
        // Store as destination
        destinationLatLng = suggestion.latLng
        
        // Show navigation button
        findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
        
        // Add to recent places
        recentPlacesManager.addRecentPlace(suggestion)
    }
} 