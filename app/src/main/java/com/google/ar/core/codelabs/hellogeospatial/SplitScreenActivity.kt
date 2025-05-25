package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.ARActivity
import com.google.ar.core.codelabs.hellogeospatial.FallbackActivity
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoRenderer
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.DirectionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.Locale
import android.text.Editable
import android.text.TextWatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.graphics.Color

/**
 * Split-screen activity showing both AR and Map views simultaneously
 */
class SplitScreenActivity : AppCompatActivity(), OnMapReadyCallback {
    companion object {
        private const val TAG = "SplitScreenActivity"
        private const val LOCATION_PERMISSION_CODE = 100
        private const val CAMERA_PERMISSION_CODE = 101
    }

    // Map components
    private lateinit var mapFragment: SupportMapFragment
    private var googleMap: GoogleMap? = null
    private var mapPolyline: Polyline? = null
    
    // Location components
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: LatLng? = null
    
    // AR components
    private lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private lateinit var view: HelloGeoView
    private lateinit var renderer: HelloGeoRenderer
    private lateinit var surfaceView: GLSurfaceView
    
    // Navigation components
    private var destinationLatLng: LatLng? = null
    private var isNavigating = false
    private var trackingQualityIndicator: TextView? = null
    private lateinit var directionsHelper: DirectionsHelper
    private var routePoints: List<LatLng>? = null
    
    // Search suggestion components
    private lateinit var suggestionProvider: SearchSuggestionProvider
    private lateinit var placesAdapter: PlacesAdapter
    private lateinit var suggestionsList: RecyclerView
    private var searchQueryHandler = Handler(Looper.getMainLooper())
    private var lastSearchRunnable: Runnable? = null
    private lateinit var recentPlacesManager: RecentPlacesManager
    
    private var navigationInstructions = mutableListOf<String>()
    private var navigationSteps = mutableListOf<DirectionsHelper.DirectionStep>()
    private var currentStepIndex = 0
    private var navigationUpdateHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set error handler
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            runOnUiThread {
                Toast.makeText(this, "Error: ${throwable.message}", Toast.LENGTH_LONG).show()
            }
        }
        
        try {
            Log.d(TAG, "Starting SplitScreenActivity initialization")
            
            // Check camera permission first before anything else
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting camera permission")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_CODE
                )
                return
            }
            
            // Check if ARCore is supported
            if (!checkARCoreSupport()) {
                Log.e(TAG, "ARCore not supported on this device")
                // If not supported, redirect to map-only view
                Toast.makeText(this, "AR not supported on this device. Redirecting to map view.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, FallbackActivity::class.java))
                finish()
                return
            }
            
            Log.d(TAG, "ARCore is supported, setting up activity")
            
            // Set the content view
            setContentView(R.layout.activity_split_screen)
            
            // Initialize the location provider
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            // Initialize the directions helper
            directionsHelper = DirectionsHelper(this)
            
            // Initialize search suggestion provider
            suggestionProvider = SearchSuggestionProvider(this)
            
            // Initialize recent places manager
            recentPlacesManager = RecentPlacesManager(this)
            
            // Initialize AR components
            initializeAR()
            
            // Set up map fragment
            setupMapFragment()
            
            // Set up UI components
            setupUI()
            
            Log.d(TAG, "Activity initialization completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
            fallbackToMapOnlyMode()
        }
    }
    
    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    currentLocation = LatLng(it.latitude, it.longitude)
                    Log.d(TAG, "Current location: ${it.latitude}, ${it.longitude}")
                    
                    // If we already have a destination, we can calculate the route
                    destinationLatLng?.let { destination ->
                        if (isNavigating) {
                            fetchAndDisplayDirections(currentLocation!!, destination)
                        }
                    }
                }
            }
        }
    }
    
    private fun checkARCoreSupport(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        return when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> true
            else -> false
        }
    }
    
    private fun initializeMap() {
        try {
            mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
            
            // Set a timeout for map loading
            val mapLoadingTimeout = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.e(TAG, "Map loading timed out")
                findViewById<LinearLayout>(R.id.map_loading_container)?.visibility = View.GONE
                Toast.makeText(this, "Map loading timed out. Please check your internet connection.", Toast.LENGTH_LONG).show()
            }
            
            mapLoadingTimeout.postDelayed(timeoutRunnable, 20000)
            
            // Initialize the map asynchronously
            mapFragment.getMapAsync(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing map", e)
            Toast.makeText(this, "Error initializing map", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun initializeAR() {
        try {
            // Double check camera permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted")
                throw SecurityException("Camera permission not granted")
            }

            Log.d(TAG, "Initializing AR components")

            // Get the tracking quality indicator
            trackingQualityIndicator = findViewById(R.id.tracking_quality)
            
            // Get and configure the AR surface view
            setupSurfaceView()
            
            // Create and initialize HelloGeoView with SplitScreenActivity
            view = HelloGeoView(this)
            
            // Need to set the surface view
            try {
                val field = HelloGeoView::class.java.getDeclaredField("surfaceView")
                field.isAccessible = true
                field.set(view, surfaceView)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting surface view", e)
                throw e
            }
            
            // Create and initialize the renderer
            renderer = HelloGeoRenderer(this)
            renderer.setView(view)
            
            // Create and initialize ARCore session
            arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
            
            // Register error handler with more detailed logging
            arCoreSessionHelper.exceptionCallback = { exception ->
                val message = when (exception) {
                    is CameraNotAvailableException -> {
                        Log.e(TAG, "Camera not available", exception)
                        "Camera not available. Please check camera permissions and restart the app."
                    }
                    else -> {
                        Log.e(TAG, "AR Error: ${exception.javaClass.simpleName}", exception)
                        "AR Error: ${exception.message}"
                    }
                }
                runOnUiThread {
                    trackingQualityIndicator?.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            
            // Configure the session
            arCoreSessionHelper.beforeSessionResume = ::configureSession
            
            // Initialize the session
            arCoreSessionHelper.onResume()
            
            // Get the session
            val session = arCoreSessionHelper.session
            if (session == null) {
                val error = "Failed to create AR session"
                Log.e(TAG, error)
                trackingQualityIndicator?.text = error
                throw Exception(error)
            }
            
            // Set up session in view and renderer
            view.setupSession(session)
            renderer.setSession(session)
            
            // Set up the renderer
            SampleRender(surfaceView, renderer, assets)
            
            // Start tracking quality updates
            startTrackingQualityUpdates()
            
            Log.d(TAG, "AR initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AR", e)
            trackingQualityIndicator?.text = "AR Error: ${e.message}"
            // Fall back to map-only mode if AR fails
            fallbackToMapOnlyMode()
            throw e // Re-throw to be caught by onCreate
        }
    }
    
    private fun configureSession(session: Session) {
        try {
            Log.d(TAG, "Configuring AR session")
            
            // Check if session is valid using try-catch instead of isDestroyed
            try {
                session.configure(
                    session.config.apply {
                        // Enable geospatial mode
                        geospatialMode = Config.GeospatialMode.ENABLED
                        
                        // Basic settings for navigation
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                        
                        // Important: Set to LATEST_CAMERA_IMAGE to ensure smooth tracking
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        
                        // Set focus mode to AUTO for better tracking
                        focusMode = Config.FocusMode.AUTO
                        
                        // Try to enable depth for better occlusion
                        try {
                            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                depthMode = Config.DepthMode.AUTOMATIC
                                Log.d(TAG, "Depth mode enabled for better AR experience")
                            } else {
                                depthMode = Config.DepthMode.DISABLED
                                Log.d(TAG, "Depth mode not supported on this device")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking depth support", e)
                            depthMode = Config.DepthMode.DISABLED
                        }
                        
                        // Enable cloud anchors for possible sharing features
                        cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                    }
                )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Session configuration failed - session may be invalid", e)
                throw e
            }
            
            // Verify configuration was successful
            if (session.config.geospatialMode != Config.GeospatialMode.ENABLED) {
                throw IllegalStateException("Failed to enable geospatial mode")
            }
            
            Log.d(TAG, "AR session configured successfully with enhanced settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AR session", e)
            Toast.makeText(this, "AR configuration error: ${e.message}", Toast.LENGTH_SHORT).show()
            throw e // Re-throw to be handled by caller
        }
    }
    
    private fun startTrackingQualityUpdates() {
        // Update tracking quality status every second
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                updateTrackingQualityIndicator()
                handler.postDelayed(this, 1000)
            }
        })
    }
    
    private fun updateTrackingQualityIndicator() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            if (earth.trackingState != TrackingState.TRACKING) {
                trackingQualityIndicator?.text = "Tracking: INITIALIZING"
                trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_orange_light)
                return
            }
            
            val pose = earth.cameraGeospatialPose
            val horizontalAccuracy = pose.horizontalAccuracy
            
            val qualityText = when {
                horizontalAccuracy <= 1.0 -> "HIGH"
                horizontalAccuracy <= 3.0 -> "MEDIUM"
                else -> "LOW"
            }
            
            trackingQualityIndicator?.text = "Tracking: $qualityText (±${horizontalAccuracy.toInt()}m)"
            
            val colorRes = when {
                horizontalAccuracy <= 1.0 -> android.R.color.holo_green_light
                horizontalAccuracy <= 3.0 -> android.R.color.holo_orange_light
                else -> android.R.color.holo_red_light
            }
            
            trackingQualityIndicator?.setBackgroundResource(colorRes)
            
            // Update current location from AR pose
            updateLocationFromARPose(pose.latitude, pose.longitude)
            
        } catch (e: Exception) {
            trackingQualityIndicator?.text = "Tracking: ERROR"
            trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_red_light)
        }
    }
    
    private fun updateLocationFromARPose(latitude: Double, longitude: Double) {
        // Update current location from AR
        currentLocation = LatLng(latitude, longitude)
        
        // If navigating, update AR anchors with the new route points
        if (isNavigating && routePoints != null && routePoints!!.isNotEmpty()) {
            // Update AR view with the route
            renderer.updatePathAnchors(routePoints!!)
        }
    }
    
    private fun setupUIControls() {
        // Get the search bar
        val searchBar = findViewById<EditText>(R.id.searchBar)
        
        // Set up search bar
        searchBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = v.text.toString()
                if (query.isNotBlank()) {
                    hideSuggestions()
                    searchLocation(query)
                    hideKeyboard()
                }
                true
            } else {
                false
            }
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
                    showSuggestionsLoading()
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
        
        // Set up navigate button
        val navigateButton = findViewById<Button>(R.id.navigateButton)
        navigateButton.setOnClickListener {
            startNavigation()
        }
        
        // Set up stop navigate button
        val stopNavigateButton = findViewById<Button>(R.id.stopNavigateButton)
        stopNavigateButton.setOnClickListener {
            stopNavigation()
        }
        
        // Set up AR mode button
        val arModeButton = findViewById<Button>(R.id.ar_mode_button)
        arModeButton.setOnClickListener {
            launchAROnlyMode()
        }
        
        // Set up map mode button
        val mapModeButton = findViewById<Button>(R.id.map_mode_button)
        mapModeButton.setOnClickListener {
            launchMapOnlyMode()
        }
        
        // Set up periodic AR status updates
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                updateARStatus()
                handler.postDelayed(this, 1000)
            }
        })
    }
    
    private fun setupSearchSuggestions() {
        // Get the suggestions RecyclerView
        suggestionsList = findViewById(R.id.suggestionsList)
        
        // Set up the adapter
        placesAdapter = PlacesAdapter(
            onItemClickListener = { suggestion ->
                // Handle item click
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
            layoutManager = LinearLayoutManager(this@SplitScreenActivity)
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
        
        // Only load recent places when showing suggestions
        refreshRecentPlaces()
        
        suggestionsList.visibility = View.VISIBLE
    }
    
    private fun showRecentPlacesOnly() {
        placesAdapter.updateSuggestions(emptyList())
        refreshRecentPlaces()
        suggestionsList.visibility = View.VISIBLE
    }
    
    private fun showSuggestionsLoading() {
        // If we want to show a loading state for suggestions
        // We could implement this with a ProgressBar in the suggestions list
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
    
    private fun updateARStatus() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            // Update tracking quality indicator based on Earth state
            val trackingState = earth.trackingState
            val earthState = earth.earthState
            
            if (trackingState == TrackingState.TRACKING) {
                val pose = earth.cameraGeospatialPose
                val horizontalAccuracy = pose.horizontalAccuracy
                
                val qualityText = when {
                    horizontalAccuracy <= 1.0 -> "HIGH"
                    horizontalAccuracy <= 3.0 -> "MEDIUM"
                    else -> "LOW"
                }
                
                trackingQualityIndicator?.text = "Tracking: $qualityText (±${horizontalAccuracy.toInt()}m)"
                
                val backgroundColor = when {
                    horizontalAccuracy <= 1.0 -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    horizontalAccuracy <= 3.0 -> ContextCompat.getColor(this, android.R.color.holo_orange_light)
                    else -> ContextCompat.getColor(this, android.R.color.holo_red_light)
                }
                
                trackingQualityIndicator?.setBackgroundColor(backgroundColor)
                
                // If we're navigating, ensure route is showing in AR
                if (isNavigating && routePoints != null && routePoints!!.isNotEmpty()) {
                    renderer.createPathAnchors(routePoints!!)
                }
            } else {
                // Not tracking
                trackingQualityIndicator?.text = "Tracking: ${trackingState.name} / ${earthState.name}"
                trackingQualityIndicator?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                
                // Check for common issues if not tracking
                if (trackingState != TrackingState.TRACKING) {
                    val issues = checkARIssues()
                    if (issues.isNotEmpty()) {
                        Log.w(TAG, "AR issues detected: ${issues.joinToString(", ")}")
                        // Update status with issues
                        val issuesText = if (issues.size > 2) {
                            issues.take(2).joinToString(", ") + "..."
                        } else {
                            issues.joinToString(", ")
                        }
                        trackingQualityIndicator?.text = "Issues: $issuesText"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating AR status", e)
            trackingQualityIndicator?.text = "AR Status Error"
        }
    }
    
    /**
     * Check for common issues that might prevent AR from working correctly
     */
    private fun checkARIssues(): List<String> {
        val issues = mutableListOf<String>()
        
        // Check location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            issues.add("Location permission denied")
        }
        
        // Check if GPS is enabled
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            issues.add("GPS disabled")
        }
        
        // Check if we're indoors (harder to get GPS)
        val sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        val lightSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            // Just add a general warning about being indoors
            issues.add("May be indoors")
        }
        
        // Check if we have a current location
        if (currentLocation == null) {
            issues.add("No location fix")
        }
        
        return issues
    }
    
    private fun searchLocation(query: String) {
        try {
            // Show loading indicator
            findViewById<View>(R.id.map_loading_container)?.visibility = View.VISIBLE
            
            // Hide any suggestions
            hideSuggestions()
            
            val geocoder = Geocoder(this, Locale.getDefault())
            
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                
                // Hide loading indicator
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)
                    
                    // Create a suggestion from the address
                    val mainText = if (!address.featureName.isNullOrBlank()) address.featureName else query
                    val secondaryText = address.getAddressLine(0) ?: ""
                    
                    val suggestion = SearchSuggestion(
                        title = mainText,
                        address = secondaryText,
                        latLng = latLng,
                        originalAddress = address
                    )
                    
                    // Update map
                    googleMap?.apply {
                        clear()
                        addMarker(MarkerOptions().position(latLng).title(suggestion.title))
                        animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    }
                    
                    // Store as destination
                    destinationLatLng = latLng
                    
                    // Show navigation button
                    findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                    
                    // Add to recent places
                    recentPlacesManager.addRecentPlace(suggestion)
                } else {
                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                Toast.makeText(this, "Error searching: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startNavigation() {
        if (currentLocation == null || destinationLatLng == null) {
            Toast.makeText(this, "Cannot start navigation: location or destination not set", Toast.LENGTH_SHORT).show()
            return
        }
        
        isNavigating = true
        
        // Update UI
        findViewById<Button>(R.id.navigateButton)?.visibility = View.GONE
        findViewById<Button>(R.id.stopNavigateButton)?.visibility = View.VISIBLE
        
        // Fetch and display directions
        fetchAndDisplayDirections(currentLocation!!, destinationLatLng!!)
        
        // Start AR mode
        try {
            val session = arCoreSessionHelper.session
            val earth = session?.earth
            
            if (earth?.trackingState == TrackingState.TRACKING) {
                trackingQualityIndicator?.text = "AR navigation started"
                trackingQualityIndicator?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            } else {
                Log.d(TAG, "Earth not yet tracking, navigation will start when tracking begins")
                trackingQualityIndicator?.text = "Waiting for AR tracking..."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AR navigation", e)
        }
    }
    
    private fun startNavigationUpdates() {
        // Cancel any existing handler
        navigationUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Create new handler
        navigationUpdateHandler = Handler(Looper.getMainLooper())
        
        // Create runnable that updates the current instruction based on user's location
        val navigationUpdateRunnable = object : Runnable {
            override fun run() {
                updateNavigationInstruction()
                navigationUpdateHandler?.postDelayed(this, 3000) // Update every 3 seconds
            }
        }
        
        // Start the updates
        navigationUpdateHandler?.post(navigationUpdateRunnable)
    }
    
    private fun updateNavigationInstruction() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            if (earth.trackingState != TrackingState.TRACKING) return
            if (navigationSteps.isEmpty()) return
            
            // Get current position
            val pose = earth.cameraGeospatialPose
            val currentLatLng = LatLng(pose.latitude, pose.longitude)
            
            // Find the closest step
            var closestStepIndex = 0
            var minDistance = Double.MAX_VALUE
            
            for (i in navigationSteps.indices) {
                val step = navigationSteps[i]
                val stepStart = step.startLocation
                
                // Calculate distance to step start
                val distance = calculateDistance(
                    currentLatLng.latitude, currentLatLng.longitude,
                    stepStart.latitude, stepStart.longitude
                )
                
                if (distance < minDistance) {
                    minDistance = distance
                    closestStepIndex = i
                }
            }
            
            // If we've moved to a new step, update the instruction
            if (closestStepIndex != currentStepIndex && closestStepIndex < navigationInstructions.size) {
                currentStepIndex = closestStepIndex
                
                runOnUiThread {
                    // Animate text change
                    findViewById<TextView>(R.id.directionsText)?.animate()
                        ?.alpha(0f)
                        ?.setDuration(150)
                        ?.withEndAction {
                            findViewById<TextView>(R.id.directionsText)?.apply {
                                text = navigationInstructions[currentStepIndex]
                                animate()
                                    ?.alpha(1f)
                                    ?.setDuration(150)
                                    ?.start()
                            }
                        }
                        ?.start()
                }
            }
            
            // If we're close to destination, show arrival message
            val destination = destinationLatLng ?: return
            val distanceToDestination = calculateDistance(
                currentLatLng.latitude, currentLatLng.longitude,
                destination.latitude, destination.longitude
            )
            
            if (distanceToDestination < 20 && navigationInstructions.isNotEmpty()) { // Within 20 meters
                runOnUiThread {
                    findViewById<TextView>(R.id.directionsText)?.apply {
                        text = "You have arrived at your destination"
                        setBackgroundColor(Color.parseColor("#AA006400")) // Dark green
                    }
                    
                    // Stop updating
                    navigationUpdateHandler?.removeCallbacksAndMessages(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating navigation instruction", e)
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth's radius in meters
        val φ1 = lat1 * Math.PI / 180
        val φ2 = lat2 * Math.PI / 180
        val Δφ = (lat2 - lat1) * Math.PI / 180
        val Δλ = (lon2 - lon1) * Math.PI / 180

        val a = Math.sin(Δφ/2) * Math.sin(Δφ/2) +
                Math.cos(φ1) * Math.cos(φ2) *
                Math.sin(Δλ/2) * Math.sin(Δλ/2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))

        return r * c
    }
    
    private fun fetchAndDisplayDirections(origin: LatLng, destination: LatLng) {
        try {
            // Show loading indicator
            val loadingIndicator = findViewById<TextView>(R.id.directionsText)
            loadingIndicator?.text = "Loading directions..."
            loadingIndicator?.visibility = View.VISIBLE
            
            // Get directions with turn-by-turn instructions
            directionsHelper.getDirectionsWithInstructions(
                origin, 
                destination,
                object : DirectionsHelper.DirectionsWithInstructionsListener {
                    override fun onDirectionsReady(
                        pathPoints: List<LatLng>,
                        instructions: List<String>,
                        steps: List<DirectionsHelper.DirectionStep>
                    ) {
                        runOnUiThread {
                            loadingIndicator?.visibility = View.GONE
                            
                            // Store the route points for AR
                            routePoints = pathPoints
                            
                            // Update AR view with the path
                            updateARPathVisualization(pathPoints)
                            
                            // Draw route on the map
                            drawRouteOnMap(pathPoints)
                            
                            // Display the first instruction
                            if (instructions.isNotEmpty()) {
                                val directionsTextView = findViewById<TextView>(R.id.directionsText)
                                directionsTextView?.apply {
                                    text = instructions[0]
                                    visibility = View.VISIBLE
                                }
                            }
                            
                            // Store navigation instructions and steps
                            navigationInstructions = instructions.toMutableList()
                            navigationSteps = steps.toMutableList()
                            
                            // Start navigation updates
                            startNavigationUpdates()
                            
                            Toast.makeText(this@SplitScreenActivity, "Navigation started", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    override fun onDirectionsError(errorMessage: String) {
                        runOnUiThread {
                            loadingIndicator?.visibility = View.GONE
                            
                            Log.e(TAG, "Error getting directions: $errorMessage")
                            Toast.makeText(this@SplitScreenActivity, "Error getting directions: $errorMessage", Toast.LENGTH_SHORT).show()
                            
                            // Fallback to direct line
                            val simplePath = listOf(origin, destination)
                            
                            // Store the route points for AR
                            routePoints = simplePath
                            
                            // Update AR with direct path
                            updateARPathVisualization(simplePath)
                            
                            // Draw direct line on map
                            drawRouteOnMap(simplePath)
                            
                            // Hide directions text
                            findViewById<TextView>(R.id.directionsText)?.visibility = View.GONE
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching directions", e)
            Toast.makeText(this, "Error fetching directions: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateARPathVisualization(pathPoints: List<LatLng>) {
        try {
            val session = arCoreSessionHelper.session
            val earth = session?.earth
            
            if (earth?.trackingState == TrackingState.TRACKING) {
                // If Earth is tracking, create anchors immediately
                renderer.createPathAnchors(pathPoints)
            } else {
                // If not tracking yet, store the path - we'll create anchors when Earth starts tracking
                // This is handled in the updateARStatus method
                Log.d(TAG, "Earth not tracking yet, will create path anchors when tracking begins")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating AR path visualization", e)
        }
    }
    
    private fun drawRouteOnMap(pathPoints: List<LatLng>) {
        googleMap?.let { map ->
            map.clear()
            
            // Add polyline - using the available method
            val options = com.google.android.gms.maps.model.PolylineOptions()
                .addAll(pathPoints)
                .width(5f)
                .color(android.graphics.Color.BLUE)
                
            map.addPolyline(options)
        }
    }
    
    private fun launchAROnlyMode() {
        val intent = Intent(this, ARActivity::class.java)
        
        // Pass destination data if we have it
        destinationLatLng?.let {
            intent.putExtra("DESTINATION_LAT", it.latitude)
            intent.putExtra("DESTINATION_LNG", it.longitude)
        }
        
        startActivity(intent)
        finish()
    }
    
    private fun launchMapOnlyMode() {
        fallbackToMapOnlyMode()
    }
    
    private fun fallbackToMapOnlyMode() {
        try {
            Log.d(TAG, "Falling back to map-only mode")
            
            // Clean up AR resources
            arCoreSessionHelper.session?.close()
            surfaceView.visibility = View.GONE
            
            // Show map
            findViewById<View>(R.id.map)?.visibility = View.VISIBLE
            
            // Update UI to indicate map-only mode
            trackingQualityIndicator?.text = "AR Unavailable - Using Map Only"
            trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_red_light)
            
            Toast.makeText(this, "Switched to map-only mode", Toast.LENGTH_LONG).show()
            
            Log.d(TAG, "Successfully switched to map-only mode")
        } catch (e: Exception) {
            Log.e(TAG, "Error switching to map-only mode", e)
            // If we can't even fall back gracefully, just finish the activity
            finish()
        }
    }
    
    private fun checkAndRequestPermissions() {
        // Check for location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
        }
        
        // Check for camera permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, enable my location on the map
                    try {
                        googleMap?.isMyLocationEnabled = true
                        
                        // Also get current location
                        getCurrentLocation()
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not enable my location", e)
                    }
                } else {
                    Toast.makeText(this, "Location permission required for navigation", Toast.LENGTH_LONG).show()
                }
            }
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Camera permission granted, initializing AR")
                    // Camera permission granted, try to initialize AR again
                    try {
                        initializeAR()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize AR after permission grant", e)
                        Toast.makeText(this, "Failed to initialize AR: ${e.message}", Toast.LENGTH_LONG).show()
                        fallbackToMapOnlyMode()
                    }
                } else {
                    Log.e(TAG, "Camera permission denied")
                    Toast.makeText(this, "Camera permission required for AR", Toast.LENGTH_LONG).show()
                    // Switch to map-only mode if camera permission denied
                    fallbackToMapOnlyMode()
                }
            }
        }
    }
    
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Hide the loading indicator
        findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
        
        try {
            // Configure map settings
            map.uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isMyLocationButtonEnabled = true
            }
            
            // Enable my location if we have permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                    == PackageManager.PERMISSION_GRANTED) {
                map.isMyLocationEnabled = true
            }
            
            // Set click listener for selecting location
            map.setOnMapClickListener { latLng ->
                map.clear()
                map.addMarker(MarkerOptions().position(latLng).title("Selected Location"))
                
                // Store as destination
                destinationLatLng = latLng
                
                // Show navigation button
                findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
            }
            
            // Set initial position
            currentLocation?.let {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
            } ?: run {
                // Default to San Francisco if no location
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.7749, -122.4194), 10f))
            }
            
            // If we already have a destination, show it
            destinationLatLng?.let { destination ->
                map.addMarker(MarkerOptions().position(destination).title("Destination"))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(destination, 15f))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring map", e)
            Toast.makeText(this, "Error configuring map: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupSurfaceView() {
        try {
            Log.d(TAG, "Setting up AR surface view")
            
            surfaceView = findViewById(R.id.ar_surface_view)
            if (surfaceView == null) {
                throw IllegalStateException("AR surface view not found in layout")
            }
            
            // Configure GL settings
            surfaceView.preserveEGLContextOnPause = true
            surfaceView.setEGLContextClientVersion(2)
            surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            
            // Create a single SampleRender instance
            val sampleRender = SampleRender(surfaceView, renderer, assets)
            
            // Create a wrapper renderer that implements GLSurfaceView.Renderer
            val wrapperRenderer = object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                    renderer.onSurfaceCreated(sampleRender)
                }
                
                override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
                    renderer.onSurfaceChanged(sampleRender, width, height)
                }
                
                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                    renderer.onDrawFrame(sampleRender)
                }
            }
            
            // Set the wrapper renderer
            surfaceView.setRenderer(wrapperRenderer)
            surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            
            Log.d(TAG, "AR surface view configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up surface view", e)
            throw e
        }
    }
    
    override fun onResume() {
        super.onResume()
        try {
            Log.d(TAG, "Activity resuming")
            
            // Resume AR session
            arCoreSessionHelper.onResume()
            
            // Resume surface view
            surfaceView.onResume()
            
            // Start tracking updates
            startTrackingQualityUpdates()
            
            Log.d(TAG, "Activity resumed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
            Toast.makeText(this, "Error resuming AR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            Log.d(TAG, "Activity pausing")
            
            // Pause AR session
            arCoreSessionHelper.onPause()
            
            // Pause surface view
            surfaceView.onPause()
            
            // Stop tracking updates
            stopTrackingQualityUpdates()
            
            Log.d(TAG, "Activity paused successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            Log.d(TAG, "Activity being destroyed")
            
            // Clean up AR session
            arCoreSessionHelper.onDestroy()
            
            // Clean up renderer resources
            renderer.clearAnchors()
            
            Log.d(TAG, "Activity destroyed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }
    
    private fun stopNavigation() {
        isNavigating = false
        
        // Stop navigation updates
        navigationUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Update UI
        findViewById<Button>(R.id.stopNavigateButton)?.visibility = View.GONE
        findViewById<Button>(R.id.navigateButton)?.visibility = View.VISIBLE
        
        // Clear route visualization
        googleMap?.clear()
        
        // Reset AR visualization
        renderer.clearAnchors()
        
        // Hide directions text
        findViewById<TextView>(R.id.directionsText)?.visibility = View.GONE
        
        // Clear navigation data
        navigationInstructions.clear()
        navigationSteps.clear()
        currentStepIndex = 0
        
        Toast.makeText(this, "Navigation stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopTrackingQualityUpdates() {
        try {
            navigationUpdateHandler?.removeCallbacksAndMessages(null)
            navigationUpdateHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tracking updates", e)
        }
    }
    
    override fun onStop() {
        super.onStop()
        try {
            Log.d(TAG, "Activity stopping")
            
            // Stop any ongoing AR operations
            stopTrackingQualityUpdates()
            
            // Release camera resources
            arCoreSessionHelper.session?.close()
            
            Log.d(TAG, "Activity stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStop", e)
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            // Save any necessary state
            destinationLatLng?.let { destination ->
                outState.putDouble("DESTINATION_LAT", destination.latitude)
                outState.putDouble("DESTINATION_LNG", destination.longitude)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving instance state", e)
        }
    }
    
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        try {
            // Restore any saved state
            if (savedInstanceState.containsKey("DESTINATION_LAT") && 
                savedInstanceState.containsKey("DESTINATION_LNG")) {
                val lat = savedInstanceState.getDouble("DESTINATION_LAT")
                val lng = savedInstanceState.getDouble("DESTINATION_LNG")
                destinationLatLng = LatLng(lat, lng)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring instance state", e)
        }
    }

    private fun setupMapFragment() {
        try {
            Log.d(TAG, "Setting up map fragment")
            
            // Initialize map fragment
            mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
            
            // Set a timeout for map loading
            val mapLoadingTimeout = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.e(TAG, "Map loading timed out")
                findViewById<LinearLayout>(R.id.map_loading_container)?.visibility = View.GONE
                Toast.makeText(this, "Map loading timed out. Please check your internet connection.", Toast.LENGTH_LONG).show()
            }
            
            mapLoadingTimeout.postDelayed(timeoutRunnable, 20000)
            
            // Initialize the map asynchronously
            mapFragment.getMapAsync(this)
            
            Log.d(TAG, "Map fragment setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up map fragment", e)
            Toast.makeText(this, "Error initializing map", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupUI() {
        try {
            Log.d(TAG, "Setting up UI components")
            
            // Set up search suggestions
            setupSearchSuggestions()
            
            // Set up UI controls
            setupUIControls()
            
            // Get destination from intent if available
            if (intent.hasExtra("DESTINATION_LAT") && intent.hasExtra("DESTINATION_LNG")) {
                val lat = intent.getDoubleExtra("DESTINATION_LAT", 0.0)
                val lng = intent.getDoubleExtra("DESTINATION_LNG", 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    Log.d(TAG, "Setting destination from intent: $lat, $lng")
                    destinationLatLng = LatLng(lat, lng)
                }
            }
            
            // Get current location
            getCurrentLocation()
            
            Log.d(TAG, "UI setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI", e)
            Toast.makeText(this, "Error setting up UI: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
} 