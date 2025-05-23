package com.google.ar.core.codelabs.hellogeospatial

import android.opengl.GLSurfaceView
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleRegistry
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.DirectionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.GeoPermissionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.android.gms.maps.model.LatLng
import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.app.ActivityCompat

class ARActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ARActivity"
        private const val AR_INITIALIZATION_TIMEOUT = 15000L // 15 seconds
    }

    private lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private lateinit var view: HelloGeoView
    private lateinit var renderer: HelloGeoRenderer
    private var destinationLatLng: LatLng? = null
    private var arInitializationTimeoutHandler = Handler(Looper.getMainLooper())
    private var trackingQualityIndicator: TextView? = null
    private var directionTextView: TextView? = null
    private var distanceIndicator: TextView? = null
    private var isNavigating = false
    private var arStatusMessage: String? = null
    private val lifecycleRegistry = LifecycleRegistry(this)
    private lateinit var surfaceView: GLSurfaceView
    
    // Navigation-related variables
    private var navigationInstructions = mutableListOf<String>()
    private var navigationSteps = mutableListOf<DirectionsHelper.DirectionStep>()
    private var currentStepIndex = 0
    private var navigationUpdateHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure we're using NoActionBar theme
        setTheme(R.style.Theme_AppCompat_NoActionBar)

        // Set a default uncaught exception handler to prevent crashes
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            runOnUiThread {
                Toast.makeText(this, "AR Error: ${throwable.message}", Toast.LENGTH_LONG).show()
                returnToMapMode()
            }
        }

        try {
            // Get destination from intent if available
            if (intent.hasExtra("DESTINATION_LAT") && intent.hasExtra("DESTINATION_LNG")) {
                val lat = intent.getDoubleExtra("DESTINATION_LAT", 0.0)
                val lng = intent.getDoubleExtra("DESTINATION_LNG", 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    destinationLatLng = LatLng(lat, lng)
                    Log.d(TAG, "Destination received: $lat, $lng")
                }
            }

            // Set the content view
            setContentView(R.layout.activity_ar)
            
            // Add tracking quality indicator
            trackingQualityIndicator = findViewById(R.id.tracking_quality)
            
            // Add directions text view
            directionTextView = findViewById(R.id.direction_text)
            directionTextView?.visibility = View.VISIBLE
            directionTextView?.text = "Preparing navigation..."
            
            // Add distance indicator
            distanceIndicator = findViewById(R.id.distance_indicator)
            distanceIndicator?.visibility = View.VISIBLE
            
            // Add button to return to map view
            findViewById<Button>(R.id.return_to_map_button).setOnClickListener {
                returnToMapMode()
            }
            
            // Add help button to show navigation tips
            findViewById<Button>(R.id.help_button)?.setOnClickListener {
                showNavigationHelp()
            }
            
            // Get the surface view
            surfaceView = findViewById(R.id.ar_surface_view)
            
            // Initialize our AR view - create a new instance with this activity
            view = HelloGeoView(this)
            
            // Set the surfaceView in our custom view
            // Need to access the view's surfaceView property to set it
            val field = HelloGeoView::class.java.getDeclaredField("surfaceView")
            field.isAccessible = true
            field.set(view, surfaceView)
            
            // Initialize renderer
            renderer = HelloGeoRenderer(this)
            
            // Set the renderer's view reference
            renderer.setView(view)
            
            // Create and initialize ARCore session
            arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
            
            // Register the renderer as a lifecycle observer
            lifecycle.addObserver(renderer)
            
            arCoreSessionHelper.exceptionCallback = { exception ->
                val message = when (exception) {
                    is UnavailableUserDeclinedInstallationException -> "Please install ARCore"
                    is UnavailableApkTooOldException -> "Please update ARCore"
                    is UnavailableSdkTooOldException -> "Please update this app"
                    is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                    is CameraNotAvailableException -> "Camera is not available"
                    else -> "Failed to initialize AR: $exception"
                }
                Log.e(TAG, "ARCore threw an exception", exception)
                arStatusMessage = message
                
                // Return to map mode with error
                returnToMapMode()
            }
            
            // Set up the session
            arCoreSessionHelper.beforeSessionResume = ::configureSession
            
            // Need to call onResume to potentially create the session
            arCoreSessionHelper.onResume()
            
            // Now try to get the session - it might be available after onResume
            val session = arCoreSessionHelper.session
            
            if (session != null) {
                Log.d(TAG, "Session created successfully")
                // We have a session, set it up in the view and renderer
                view.setupSession(session)
                renderer.setSession(session)
            } else {
                Log.e(TAG, "Failed to create ARCore session")
                Toast.makeText(this, "Could not initialize AR - please try again", Toast.LENGTH_SHORT).show()
                returnToMapMode()
                return
            }
            
            // Set up SampleRender to draw the AR scene
            SampleRender(surfaceView, renderer, assets)
            
            // Set timeout for AR initialization
            startARInitializationTimeout()
            
            // Attempt to set up AR destination if available
            destinationLatLng?.let { destination ->
                setARDestination(destination)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AR", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            returnToMapMode()
        }
    }
    
    private fun startARInitializationTimeout() {
        arInitializationTimeoutHandler.postDelayed({
            if (!isEarthTrackingStable()) {
                // Clear existing timeout handler
                arInitializationTimeoutHandler.removeCallbacksAndMessages(null)
                
                // Check for common issues
                val issues = checkARIssues()
                val issueMessage = if (issues.isNotEmpty()) {
                    "Issues detected: ${issues.joinToString(", ")}"
                } else {
                    "AR tracking cannot initialize in this environment"
                }
                
                // Show dialog to user with more detailed information
                AlertDialog.Builder(this)
                    .setTitle("AR Initialization Issue")
                    .setMessage("Could not initialize AR tracking. $issueMessage\n\nWould you like to continue waiting or return to map mode?")
                    .setPositiveButton("Wait Longer") { _, _ ->
                        // Give more time
                        startARInitializationTimeout()
                    }
                    .setNegativeButton("Return to Map") { _, _ ->
                        returnToMapMode()
                    }
                    .setCancelable(false)
                    .show()
            }
        }, AR_INITIALIZATION_TIMEOUT)
    }
    
    /**
     * Check for common AR issues that might prevent initialization
     */
    private fun checkARIssues(): List<String> {
        val issues = mutableListOf<String>()
        
        // Check if location permissions are granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            issues.add("Location permission not granted")
            // Request permission
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 
                100
            )
        }
        
        // Check if GPS is enabled
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            issues.add("GPS is disabled")
            
            // Prompt user to enable GPS
            AlertDialog.Builder(this)
                .setTitle("GPS Required")
                .setMessage("AR navigation requires GPS to be enabled. Would you like to enable it now?")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
        }
        
        // Check network connectivity
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (networkCapabilities == null || 
            (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && 
             !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) {
            issues.add("No internet connection")
        }
        
        // Check if device is likely indoors (harder to get GPS signal)
        // This is just a heuristic based on sensor readings
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            // Check if light sensor readings are low (suggesting indoors)
            // This would require registering a sensor listener, which is complex
            // for this example we'll just add a general note
            issues.add("You may be indoors where GPS signal is weak")
        }
        
        return issues
    }

    private fun configureSession(session: Session) {
        try {
            Log.d(TAG, "Configuring AR session with enhanced settings")
            session.configure(
                session.config.apply {
                    // Enable geospatial mode
                    geospatialMode = Config.GeospatialMode.ENABLED
                    
                    // Basic features for navigation
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    
                    // Try adding some additional settings that might help with initialization
                    try {
                        // Check if the device supports depth
                        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                            Log.d(TAG, "Enabled depth mode for better tracking")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking depth support", e)
                    }
                    
                    // Enable cloud anchors for possible network-assisted positioning
                    cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                }
            )
            
            Log.d(TAG, "AR session configured successfully with enhanced settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring session", e)
            Toast.makeText(this, "Error configuring AR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun isEarthTrackingStable(): Boolean {
        try {
            val session = arCoreSessionHelper.session ?: return false
            val earth = session.earth ?: return false
            
            // Get more details about tracking state
            val trackingState = earth.trackingState
            val earthState = earth.earthState
            
            Log.d(TAG, "Earth tracking state: $trackingState, Earth state: $earthState")
            
            // Only consider it stable if Earth is in TRACKING state
            if (trackingState == TrackingState.TRACKING) {
                // Also check geospatial pose accuracy
                val pose = earth.cameraGeospatialPose
                val horizontalAccuracy = pose.horizontalAccuracy
                val headingAccuracy = pose.headingAccuracy
                
                Log.d(TAG, "Geospatial pose - horizontal accuracy: $horizontalAccuracy m, heading accuracy: $headingAccuracy degrees")
                
                // Consider it stable only if accuracy is reasonable
                val isAccuracyGood = horizontalAccuracy < 20 && headingAccuracy < 25
                return isAccuracyGood
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Earth tracking", e)
            return false
        }
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
            trackingQualityIndicator?.visibility = View.VISIBLE
            
            // Update distance indicator if destination is set
            updateDistanceIndicator(pose)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tracking quality", e)
            trackingQualityIndicator?.text = "Tracking: ERROR"
            trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_red_light)
        }
    }
    
    private fun updateDistanceIndicator(pose: GeospatialPose) {
        try {
            destinationLatLng?.let { destination ->
                val currentLocation = LatLng(pose.latitude, pose.longitude)
                val distance = calculateDistance(
                    currentLocation.latitude, currentLocation.longitude,
                    destination.latitude, destination.longitude
                )
                
                val distanceText = when {
                    distance < 1000 -> "${distance.toInt()} meters"
                    else -> String.format("%.1f km", distance / 1000)
                }
                
                val heading = pose.heading
                val bearing = calculateBearing(
                    currentLocation.latitude, currentLocation.longitude,
                    destination.latitude, destination.longitude
                )
                
                // Calculate the angle between heading and bearing (relative direction)
                var angle = bearing - heading
                if (angle < 0) angle += 360.0
                if (angle > 180) angle = 360.0 - angle
                
                val directionSymbol = getDirectionSymbol(bearing, heading)
                
                // Create and set the formatted text with distance and direction
                val distanceString = "Destination: $distanceText $directionSymbol"
                distanceIndicator?.text = distanceString
                
                // Change color based on distance
                val backgroundColor = when {
                    distance < 50 -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    distance < 200 -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    else -> ContextCompat.getColor(this, android.R.color.darker_gray)
                }
                
                distanceIndicator?.setBackgroundColor(ColorUtils.setAlphaComponent(backgroundColor, 200))
            } ?: run {
                distanceIndicator?.text = "No destination set"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating distance indicator", e)
            distanceIndicator?.text = "Distance: Unknown"
        }
    }
    
    private fun getDirectionSymbol(bearing: Double, heading: Double): String {
        // Calculate relative angle
        var angle = bearing - heading
        // Normalize to 0-360
        while (angle < 0) angle += 360.0
        while (angle >= 360) angle -= 360.0
        
        // Choose appropriate arrow symbol based on angle
        return when {
            angle >= 337.5 || angle < 22.5 -> "↑" // North/Forward
            angle >= 22.5 && angle < 67.5 -> "↗" // Northeast
            angle >= 67.5 && angle < 112.5 -> "→" // East/Right
            angle >= 112.5 && angle < 157.5 -> "↘" // Southeast
            angle >= 157.5 && angle < 202.5 -> "↓" // South/Back
            angle >= 202.5 && angle < 247.5 -> "↙" // Southwest
            angle >= 247.5 && angle < 292.5 -> "←" // West/Left
            angle >= 292.5 && angle < 337.5 -> "↖" // Northwest
            else -> "?" // Should never happen
        }
    }
    
    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val lngDiffRad = Math.toRadians(lng2 - lng1)
        
        val y = Math.sin(lngDiffRad) * Math.cos(lat2Rad)
        val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) -
                Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(lngDiffRad)
        
        var bearing = Math.toDegrees(Math.atan2(y, x))
        if (bearing < 0) bearing += 360.0
        
        return bearing
    }
    
    private fun setARDestination(destination: LatLng) {
        try {
            isNavigating = true
            
            // Wait for Earth to start tracking
            val earthTrackingHandler = Handler(Looper.getMainLooper())
            val earthTrackingRunnable = object : Runnable {
                override fun run() {
                    val session = arCoreSessionHelper.session
                    val earth = session?.earth
                    
                    if (earth?.trackingState == TrackingState.TRACKING) {
                        // Earth is tracking, create anchor
                        val cameraGeospatialPose = earth.cameraGeospatialPose
                        val currentLatLng = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
                        
                        // Use DirectionsHelper to get real directions instead of just a direct line
                        val directionsHelper = DirectionsHelper(this@ARActivity)
                        
                        // Show loading indicator
                        runOnUiThread {
                            trackingQualityIndicator?.text = "Getting directions..."
                            directionTextView?.visibility = View.VISIBLE
                            directionTextView?.text = "Getting directions..."
                        }
                        
                        directionsHelper.getDirectionsWithInstructions(
                            currentLatLng, 
                            destination,
                            object : DirectionsHelper.DirectionsWithInstructionsListener {
                                override fun onDirectionsReady(
                                    pathPoints: List<LatLng>,
                                    instructions: List<String>,
                                    steps: List<DirectionsHelper.DirectionStep>
                                ) {
                                    // Create anchors for the real path
                                    renderer.createPathAnchors(pathPoints)
                                    
                                    // Store navigation data
                                    navigationInstructions.clear()
                                    navigationInstructions.addAll(instructions)
                                    navigationSteps.clear()
                                    navigationSteps.addAll(steps)
                                    currentStepIndex = 0
                                    
                                    // Display the first instruction
                                    runOnUiThread {
                                        if (instructions.isNotEmpty()) {
                                            directionTextView?.text = instructions[0]
                                            directionTextView?.visibility = View.VISIBLE
                                        }
                                        Toast.makeText(this@ARActivity, "AR navigation started with turn-by-turn directions", Toast.LENGTH_SHORT).show()
                                        
                                        // Start navigation updates
                                        startNavigationUpdates()
                                    }
                                }
                                
                                override fun onDirectionsError(errorMessage: String) {
                                    Log.e(TAG, "Directions error: $errorMessage")
                                    
                                    // Fall back to direct path
                                    val simplePath = listOf(currentLatLng, destination)
                                    renderer.createPathAnchors(simplePath)
                                    
                                    // Create simple instruction
                                    navigationInstructions.clear()
                                    navigationInstructions.add("Follow the direct path to destination")
                                    
                                    runOnUiThread {
                                        Toast.makeText(this@ARActivity, "Using direct route: $errorMessage", Toast.LENGTH_SHORT).show()
                                        directionTextView?.text = "Follow the direct path to destination"
                                        directionTextView?.visibility = View.VISIBLE
                                    }
                                }
                            }
                        )
                    } else {
                        // Not tracking yet, check again in a second
                        earthTrackingHandler.postDelayed(this, 1000)
                    }
                }
            }
            
            // Start checking for Earth tracking
            earthTrackingHandler.post(earthTrackingRunnable)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting AR destination", e)
            Toast.makeText(this, "Error starting AR navigation: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    directionTextView?.animate()
                        ?.alpha(0f)
                        ?.setDuration(150)
                        ?.withEndAction {
                            directionTextView?.text = navigationInstructions[currentStepIndex]
                            directionTextView?.animate()
                                ?.alpha(1f)
                                ?.setDuration(150)
                                ?.start()
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
                    directionTextView?.text = "You have arrived at your destination"
                    directionTextView?.setBackgroundColor(Color.parseColor("#AA006400")) // Dark green
                    
                    // Stop updating
                    navigationUpdateHandler?.removeCallbacksAndMessages(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating navigation instruction", e)
        }
    }
    
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0 // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return earthRadius * c // Distance in meters
    }
    
    override fun onResume() {
        super.onResume()
        
        try {
            // Ensure the AR session is resumed properly
            arCoreSessionHelper.onResume()
            view.onResume()
            
            // Start updating tracking quality indicator
            val handler = Handler(Looper.getMainLooper())
            handler.post(object : Runnable {
                override fun run() {
                    updateTrackingQualityIndicator()
                    handler.postDelayed(this, 1000)
                }
            })
            
            // Force a quick check for any issues that might be preventing AR
            Handler(Looper.getMainLooper()).postDelayed({
                val issues = checkARIssues()
                if (issues.isNotEmpty()) {
                    Log.w(TAG, "AR issues detected: ${issues.joinToString(", ")}")
                    directionTextView?.text = "AR issues: ${issues.joinToString(", ")}"
                    directionTextView?.visibility = View.VISIBLE
                }
            }, 2000) // Check after 2 seconds
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        try {
            view.onPause()
            arCoreSessionHelper.onPause()
            
            // Stop navigation updates
            navigationUpdateHandler?.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            arCoreSessionHelper.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
    
    override fun onBackPressed() {
        returnToMapMode()
    }
    
    fun returnToMapMode() {
        try {
            // If we had any destination, pass it back
            val resultIntent = Intent()
            destinationLatLng?.let { dest ->
                resultIntent.putExtra("DESTINATION_LAT", dest.latitude)
                resultIntent.putExtra("DESTINATION_LNG", dest.longitude)
            }
            
            // Pass back error message if there was one
            arStatusMessage?.let {
                resultIntent.putExtra("AR_ERROR", it)
            }
            
            setResult(RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error returning to map mode", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    // Show helpful guidance for using AR navigation
    private fun showNavigationHelp() {
        val helpText = """
            AR Navigation Tips:
            
            • Follow the colored arrows to reach your destination
            • Blue arrows: Regular path segments
            • Yellow arrows: Turn points
            • Orange/Red: Approaching destination
            • A red marker will appear at your destination
            
            • For best results, hold your phone up as if taking a photo
            • Stay outdoors with clear view of the sky
            • Move slowly and steadily
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("How to Use AR Navigation")
            .setMessage(helpText)
            .setPositiveButton("Got it") { dialog, _ -> dialog.dismiss() }
            .show()
    }
} 