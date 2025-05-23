package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.graphics.Color
import android.os.AsyncTask
import android.util.Log
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList

/**
 * Helper class for fetching directions from Google Maps Directions API
 * and drawing them on the map
 */
class DirectionsHelper(private val context: Context) {
    companion object {
        private const val TAG = "DirectionsHelper"
        private const val DIRECTIONS_API_URL = "https://maps.googleapis.com/maps/api/directions/json"
    }
    
    // Data class for direction steps with instructions
    data class DirectionStep(
        val startLocation: LatLng,
        val endLocation: LatLng,
        val instruction: String,
        val distance: Int, // in meters
        val points: List<LatLng> // polyline points for this step
    )
    
    interface DirectionsListener {
        fun onDirectionsReady(pathPoints: List<LatLng>)
        fun onDirectionsError(errorMessage: String)
    }
    
    // Enhanced interface with instructions
    interface DirectionsWithInstructionsListener {
        fun onDirectionsReady(pathPoints: List<LatLng>, instructions: List<String>, steps: List<DirectionStep>)
        fun onDirectionsError(errorMessage: String)
    }
    
    /**
     * Fetch directions between two points (basic)
     */
    fun getDirections(origin: LatLng, destination: LatLng, listener: DirectionsListener) {
        FetchDirectionsTask(object : DirectionsWithInstructionsListener {
            override fun onDirectionsReady(pathPoints: List<LatLng>, instructions: List<String>, steps: List<DirectionStep>) {
                listener.onDirectionsReady(pathPoints)
            }
            
            override fun onDirectionsError(errorMessage: String) {
                listener.onDirectionsError(errorMessage)
            }
        }).execute(origin, destination)
    }
    
    /**
     * Fetch directions with turn-by-turn instructions
     */
    fun getDirectionsWithInstructions(origin: LatLng, destination: LatLng, listener: DirectionsWithInstructionsListener) {
        FetchDirectionsTask(listener).execute(origin, destination)
    }
    
    /**
     * Draw the route on the map
     */
    fun drawRouteOnMap(map: GoogleMap, pathPoints: List<LatLng>) {
        try {
            // Clear previous polylines first
            map.clear()
            
            // Create polyline options
            val polylineOptions = PolylineOptions()
                .addAll(pathPoints)
                .width(10f)
                .color(Color.BLUE)
                
            // Add polyline to map
            map.addPolyline(polylineOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing route on map", e)
        }
    }
    
    /**
     * AsyncTask to fetch directions in background
     */
    private inner class FetchDirectionsTask(private val listener: DirectionsWithInstructionsListener) : 
            AsyncTask<LatLng, Void, String>() {
        
        private var errorMessage: String? = null
        
        override fun doInBackground(vararg params: LatLng): String {
            val origin = params[0]
            val destination = params[1]
            
            var result = ""
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            
            try {
                // Build the URL for Google Directions API
                val apiKey = getApiKey()
                val urlString = "$DIRECTIONS_API_URL?origin=${origin.latitude},${origin.longitude}" +
                        "&destination=${destination.latitude},${destination.longitude}" +
                        "&mode=walking" +
                        "&key=$apiKey"
                
                Log.d(TAG, "Making directions request to: ${urlString.replace(apiKey, "API_KEY_REDACTED")}")
                
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000 // 15 second timeout
                connection.readTimeout = 15000
                
                try {
                    connection.connect()
                } catch (e: IOException) {
                    errorMessage = "Network error: Please check your internet connection"
                    Log.e(TAG, "Failed to connect to Directions API", e)
                    return result
                }
                
                // If connection is successful
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream = connection.inputStream
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val stringBuilder = StringBuilder()
                    var line: String?
                    
                    // Read the response
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line)
                    }
                    
                    result = stringBuilder.toString()
                    
                    // Log response status only (not the full response for privacy/security)
                    try {
                        val jsonResponse = JSONObject(result)
                        val status = jsonResponse.optString("status", "UNKNOWN")
                        Log.d(TAG, "Directions API response status: $status")
                        
                        // Check for common error statuses
                        if (status == "REQUEST_DENIED") {
                            val errorMsg = jsonResponse.optString("error_message", "API request denied")
                            errorMessage = "API access error: $errorMsg (Check your API key configuration)"
                            Log.e(TAG, "Directions API error: $errorMsg")
                            result = ""
                        } else if (status == "ZERO_RESULTS") {
                            errorMessage = "No route found between these locations"
                            result = ""
                        } else if (status != "OK") {
                            errorMessage = "Directions API error: $status"
                            result = ""
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing response JSON status", e)
                    }
                } else if (connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN || 
                           connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    errorMessage = "API Key error: Your Google Maps API key may be invalid or missing required permissions"
                    Log.e(TAG, "API key error: HTTP ${connection.responseCode}")
                } else {
                    errorMessage = "Error connecting to Directions API: HTTP ${connection.responseCode} - ${connection.responseMessage}"
                    Log.e(TAG, errorMessage!!)
                }
            } catch (e: Exception) {
                errorMessage = when {
                    e is java.net.UnknownHostException -> "Network error: Unable to connect to Google Maps servers. Please check your internet connection."
                    e is java.net.SocketTimeoutException -> "Connection timed out. Please try again when you have a better connection."
                    e.message?.contains("API key") == true -> "Invalid API key. Please update the Google Maps API key in the application."
                    else -> "Error fetching directions: ${e.message}"
                }
                Log.e(TAG, "Error in directions request", e)
            } finally {
                // Close connections
                try {
                    inputStream?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing input stream", e)
                }
                connection?.disconnect()
            }
            
            return result
        }
        
        private fun parseErrorMessage(jsonResponse: String): String {
            try {
                val json = JSONObject(jsonResponse)
                if (json.has("error_message")) {
                    return json.getString("error_message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse error message", e)
            }
            return "Unknown API error"
        }
        
        override fun onPostExecute(result: String) {
            super.onPostExecute(result)
            
            if (result.isEmpty() || errorMessage != null) {
                listener.onDirectionsError(errorMessage ?: "Unknown error fetching directions")
                return
            }
            
            try {
                // Parse the JSON response
                val directionResult = parseDirectionsJsonWithInstructions(result)
                
                if (directionResult.first.isEmpty()) {
                    listener.onDirectionsError("No route found")
                } else {
                    // Pass path points and the textual instructions
                    listener.onDirectionsReady(directionResult.first, directionResult.second, directionResult.third)
                }
            } catch (e: Exception) {
                listener.onDirectionsError("Error parsing directions: ${e.message}")
                Log.e(TAG, "Error parsing directions", e)
            }
        }
        
        /**
         * Parse the Google Directions API JSON response with instructions
         * Returns a Triple of (pathPoints, textInstructions, directionSteps)
         */
        private fun parseDirectionsJsonWithInstructions(jsonData: String): Triple<List<LatLng>, List<String>, List<DirectionStep>> {
            val pathPoints = ArrayList<LatLng>()
            val instructions = ArrayList<String>()
            val steps = ArrayList<DirectionStep>()
            
            try {
                val jsonObject = JSONObject(jsonData)
                
                // Get the routes array
                val routes = jsonObject.getJSONArray("routes")
                
                if (routes.length() == 0) {
                    return Triple(pathPoints, instructions, steps)
                }
                
                // Get the first route
                val route = routes.getJSONObject(0)
                
                // Get the legs array (usually just one for direct routes)
                val legs = route.getJSONArray("legs")
                val leg = legs.getJSONObject(0)
                
                // Add summary instruction
                val startAddress = leg.getString("start_address")
                val endAddress = leg.getString("end_address")
                val totalDistance = leg.getJSONObject("distance").getString("text")
                val totalDuration = leg.getJSONObject("duration").getString("text")
                
                instructions.add("Navigate from $startAddress to $endAddress ($totalDistance, about $totalDuration)")
                
                // Process each step for detailed instructions
                val stepsJson = leg.getJSONArray("steps")
                for (i in 0 until stepsJson.length()) {
                    val step = stepsJson.getJSONObject(i)
                    
                    // Get the instructions (HTML, need to clean up)
                    var instruction = step.getString("html_instructions")
                    
                    // Clean up HTML tags
                    instruction = instruction.replace("<[^>]*>".toRegex(), " ")
                        .replace("\\s+".toRegex(), " ")
                        .trim()
                    
                    // Get step distance
                    val distance = step.getJSONObject("distance").getInt("value")
                    
                    // Get start and end location
                    val startLoc = step.getJSONObject("start_location")
                    val startLatLng = LatLng(startLoc.getDouble("lat"), startLoc.getDouble("lng"))
                    
                    val endLoc = step.getJSONObject("end_location")
                    val endLatLng = LatLng(endLoc.getDouble("lat"), endLoc.getDouble("lng"))
                    
                    // Get the encoded polyline for this step
                    val polyline = step.getJSONObject("polyline").getString("points")
                    val stepPoints = decodePolyline(polyline)
                    
                    // Add the step to our list
                    steps.add(DirectionStep(startLatLng, endLatLng, instruction, distance, stepPoints))
                    
                    // Format a user-friendly instruction with distance
                    val distanceText = step.getJSONObject("distance").getString("text")
                    instructions.add("$instruction ($distanceText)")
                    
                    // Add all polyline points to the main path
                    pathPoints.addAll(stepPoints)
                }
                
                // If there was an overview polyline but no step details
                if (pathPoints.isEmpty()) {
                    val overviewPolyline = route.getJSONObject("overview_polyline")
                    val encodedPolyline = overviewPolyline.getString("points")
                    pathPoints.addAll(decodePolyline(encodedPolyline))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing directions JSON", e)
                throw e
            }
            
            return Triple(pathPoints, instructions, steps)
        }
        
        /**
         * Parse the Google Directions API JSON response (simplified version)
         */
        private fun parseDirectionsJson(jsonData: String): List<LatLng> {
            val pathPoints = ArrayList<LatLng>()
            
            try {
                val jsonObject = JSONObject(jsonData)
                
                // Get the routes array
                val routes = jsonObject.getJSONArray("routes")
                
                if (routes.length() == 0) {
                    return pathPoints
                }
                
                // Get the first route
                val route = routes.getJSONObject(0)
                
                // Get the overview polyline
                val overviewPolyline = route.getJSONObject("overview_polyline")
                val encodedPolyline = overviewPolyline.getString("points")
                
                // Decode the polyline points
                pathPoints.addAll(decodePolyline(encodedPolyline))
                
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing directions JSON", e)
                throw e
            }
            
            return pathPoints
        }
        
        /**
         * Decode an encoded polyline string into a list of LatLng
         */
        private fun decodePolyline(encoded: String): List<LatLng> {
            val poly = ArrayList<LatLng>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lng = 0
            
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                
                // Decode latitude
                do {
                    b = encoded[index++].toInt() - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                
                val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lat += dlat
                
                // Decode longitude
                shift = 0
                result = 0
                do {
                    b = encoded[index++].toInt() - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                
                val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lng += dlng
                
                val position = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
                poly.add(position)
            }
            
            return poly
        }
    }
    
    /**
     * Get the Google Maps API key from the resources
     */
    private fun getApiKey(): String {
        val resourceId = context.resources.getIdentifier(
            "google_maps_key", 
            "string", 
            context.packageName
        )
        
        if (resourceId > 0) {
            val apiKey = context.getString(resourceId)
            if (apiKey == "YOUR_API_KEY_HERE") {
                Log.e(TAG, "Invalid Google Maps API key - default placeholder value detected")
                throw IllegalStateException("Google Maps API Key not properly configured. Please replace 'YOUR_API_KEY_HERE' with a valid key.")
            }
            return apiKey
        }
        
        // Try GoogleCloudApiKey as fallback
        val cloudKeyId = context.resources.getIdentifier(
            "GoogleCloudApiKey", 
            "string", 
            context.packageName
        )
        
        if (cloudKeyId > 0) {
            val cloudKey = context.getString(cloudKeyId)
            if (cloudKey != "REPLACE_WITH_REAL_KEY") {
                return cloudKey
            }
        }
        
        throw IllegalStateException("Google Maps API Key not found in resources. Please add 'google_maps_key' to your values/google_maps_api.xml file.")
    }
} 