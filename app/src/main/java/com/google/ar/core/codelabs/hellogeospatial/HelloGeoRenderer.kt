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
package com.google.ar.core.codelabs.hellogeospatial

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.opengl.Matrix
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.IOException
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt


class HelloGeoRenderer(val context: Context) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "HelloGeoRenderer"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
    
    // Constants for fallback detection - increasing values to give more time
    private const val MAX_EARTH_INIT_WAIT_TIME_MS = 120000L // 2 minutes to initialize Earth
    private const val MAX_FRAMES_WITHOUT_EARTH_TRACKING = 900 // ~30 seconds at 30fps
    
    // Track Earth quality
    private const val REQUIRED_TRACKING_CONFIDENCE = 0.7f // Min confidence to consider tracking reliable
  }

  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectTexture: Texture
  
  // Directional arrow for navigation
  lateinit var arrowMesh: Mesh
  lateinit var arrowShader: Shader
  lateinit var arrowTexture: Texture
  
  // Destination marker
  lateinit var destinationMesh: Mesh
  lateinit var destinationShader: Shader
  lateinit var destinationTexture: Texture

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  private var arSession: Session? = null
  
  // Store multiple anchors for navigation path
  private val anchors = mutableListOf<Anchor>()
  private var destinationAnchor: Anchor? = null
  private var isNavigating = false
  private var routePoints = listOf<LatLng>()

  // Initialize display rotation helper with context - it accepts Context instead of Activity now
  val displayRotationHelper = DisplayRotationHelper(context)
  
  // Initialize tracking state helper with AppCompatActivity if available, or null
  val trackingStateHelper = when (context) {
    is AppCompatActivity -> TrackingStateHelper(context)
    else -> null // For non-Activity contexts, we'll handle tracking state differently
  }
  
  private var lastEarthTrackingErrorTime = 0L
  private var earthInitializedTime = 0L
  private var framesWithoutEarthTracking = 0
  private var hasFallenBackToMapMode = false
  private var lastTrackingQualityWarningTime = 0L

  // Reference to the view - can be HelloGeoView or ARActivity's view
  private var helloGeoView: HelloGeoView? = null
  
  // Set view reference
  fun setView(view: HelloGeoView) {
    this.helloGeoView = view
  }

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      // Virtual object to render (Geospatial Marker)
      virtualObjectTexture =
        Texture.createFromAsset(
          render,
          "../../assets/models/spatial_marker_baked.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj")
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", virtualObjectTexture)
          
      // Load navigation arrow assets
      try {
        arrowTexture = Texture.createFromAsset(
          render,
          "../../assets/models/materials.mtl",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )
        arrowMesh = Mesh.createFromAsset(render, "models/navigation_arrow.obj")
        arrowShader = Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", arrowTexture)
        Log.d(TAG, "Successfully loaded navigation arrow assets")
      } catch (e: IOException) {
        Log.e(TAG, "Failed to read navigation arrow assets", e)
        // Fallback to using the same assets as the marker
        arrowTexture = virtualObjectTexture
        arrowMesh = virtualObjectMesh
        arrowShader = virtualObjectShader
      }
      
      // Load destination marker assets
      try {
        destinationTexture = Texture.createFromAsset(
          render,
          "../../assets/models/materials.mtl",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )
        destinationMesh = Mesh.createFromAsset(render, "models/destination_marker.obj")
        destinationShader = Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", destinationTexture)
        Log.d(TAG, "Successfully loaded destination marker assets")
      } catch (e: IOException) {
        Log.e(TAG, "Failed to read destination marker assets", e)
        // Fallback to using the same assets as the marker
        destinationTexture = virtualObjectTexture
        destinationMesh = virtualObjectMesh
        destinationShader = virtualObjectShader
      }

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, true) // Enable occlusion for better realism
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {
    try {
      val session = session ?: return

      try {
        //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
          session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
          hasSetTextureNames = true
          Log.d(TAG, "Camera texture names set")
        }

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session)

        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        val frame =
          try {
            session.update()
          } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            showError("Camera not available. Try restarting the app.")
            return
          }

        val camera = frame.camera

        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer.updateDisplayGeometry(frame)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        updateTrackingState(camera.trackingState)

        // -- Draw background
        if (frame.timestamp != 0L) {
          // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
          // drawing possible leftover data from previous sessions if the texture is reused.
          backgroundRenderer.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
          Log.d(TAG, "Camera tracking state is PAUSED, skipping frame rendering")
          return
        }

        // Get projection matrix.
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0)

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        //</editor-fold> 

        val earth = session.earth
        if (earth == null) {
          Log.d(TAG, "Earth is null, waiting for Earth to initialize... (${System.currentTimeMillis() - earthInitializedTime} ms elapsed)")
          // No need to show an error - just wait
          updateStatusText(null, null)
          
          // Track time waiting for Earth to initialize
          if (earthInitializedTime == 0L) {
            earthInitializedTime = System.currentTimeMillis()
          }
          
          // If we've been waiting too long, maybe we should fall back to map-only mode
          if (System.currentTimeMillis() - earthInitializedTime > MAX_EARTH_INIT_WAIT_TIME_MS) {
            Log.e(TAG, "Earth initialization timed out after ${MAX_EARTH_INIT_WAIT_TIME_MS}ms")
            showError("Earth initialization timed out. Please try again later.")
            fallbackToMapOnlyMode()
            return
          }
          return
        }
        
        // Now that Earth is available, create route anchors if needed
        createRouteAnchorsIfNeeded(earth)

        if (earth.trackingState == TrackingState.TRACKING) {
          // Reset tracking error counters
          framesWithoutEarthTracking = 0
          lastEarthTrackingErrorTime = 0
          
          // Get the current camera geospatial pose
          val cameraGeospatialPose = earth.cameraGeospatialPose
          
          // Check if navigation is in progress and we have enough anchors
          if (isNavigating && anchors.isNotEmpty()) {
            // Draw the navigation path
            drawNavigationPath(render, earth, cameraGeospatialPose)
          } else {
            // In non-navigation mode just draw any anchors we have
            drawAnchors(render, earth)
          }
          
          // Update status with coordinates
          updateStatusText(earth, cameraGeospatialPose)
          
        } else {
          framesWithoutEarthTracking++
          
          if (framesWithoutEarthTracking > MAX_FRAMES_WITHOUT_EARTH_TRACKING && !hasFallenBackToMapMode) {
            Log.e(TAG, "Earth tracking failed for too many frames (${framesWithoutEarthTracking})")
            
            if (lastEarthTrackingErrorTime == 0L) {
              lastEarthTrackingErrorTime = System.currentTimeMillis()
              showError("Earth tracking lost. Please ensure you're outdoors with a clear view of the sky.")
            } else if (System.currentTimeMillis() - lastEarthTrackingErrorTime > 10000) {
              // If tracking hasn't recovered after 10 seconds, offer to switch to map-only mode
              hasFallenBackToMapMode = true
              fallbackToMapOnlyMode()
            }
          }
          
          // Update the status text to show we're not tracking
          when (earth.trackingState) {
            TrackingState.PAUSED -> updateStatusText(earth, null, "PAUSED")
            TrackingState.STOPPED -> updateStatusText(earth, null, "STOPPED")
            else -> updateStatusText(earth, null, "Not tracking")
          }
        }

        // Compose the virtual scene with the background.
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
      } catch(e: Exception) {
        Log.e(TAG, "Exception on the OpenGL thread", e)
        showError("Error rendering AR view: ${e.message}")
      }
    } catch(e: Exception) {
      Log.e(TAG, "Exception on the OpenGL thread (outer)", e)
      showError("Error in AR rendering: ${e.message}")
    }
  }
  
  // Draw the navigation path connecting the anchors
  private fun drawNavigationPath(render: SampleRender, earth: Earth, cameraGeospatialPose: GeospatialPose) {
    try {
      // Determine how many anchors to draw based on tracking quality
      val trackingConfidence = calculateTrackingConfidence(cameraGeospatialPose)
      
      // Only draw if we have good enough tracking
      if (trackingConfidence > REQUIRED_TRACKING_CONFIDENCE) {
        // Get current position
        val currentPosition = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
        
        // Draw each anchor in the path
        for (i in anchors.indices) {
          val anchor = anchors[i]
          
          // Skip detached anchors
          if (anchor.trackingState == TrackingState.STOPPED) continue
          
          // Calculate the model matrix for this anchor
          anchor.getPose().toMatrix(modelMatrix, 0)
          
          // Calculate distance to this anchor point (for color-coding)
          val anchorData = anchorData[anchor] ?: AnchorType.WAYPOINT
          val isWithinVisibleRange = isAnchorInVisibleRange(anchor, cameraGeospatialPose)
          
          // Different rendering for different points in the path
          when (anchorData) {
            AnchorType.DESTINATION -> {
              // Draw destination marker
              Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
              Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
              
              // Set color for destination marker
              val pulseFreq = 0.003f
              val pulseValue = 0.7f + 0.3f * sin((pulseFreq * System.currentTimeMillis()).toDouble()).toFloat()
              destinationShader.setVec4("u_Color", floatArrayOf(1f, 0f, 0f, pulseValue))
              
              destinationShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
              render.draw(destinationMesh, destinationShader)
            }
            AnchorType.TURN -> {
              // Draw arrow for turns
              Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
              Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
              
              // Set color for turn arrows
              arrowShader.setVec4("u_Color", floatArrayOf(1f, 1f, 0f, 0.8f)) // Yellow
              
              arrowShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
              render.draw(arrowMesh, arrowShader)
            }
            else -> {
              // Draw regular waypoints with the default marker
              Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
              Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
              
              // Set color based on distance
              val distance = calculateDistance(
                currentPosition.latitude, currentPosition.longitude,
                cameraGeospatialPose.latitude, cameraGeospatialPose.longitude
              )
              
              val colorArray = when {
                distance < 5f -> floatArrayOf(0f, 1f, 0.5f, 0.8f) // Green when close
                distance < 20f -> floatArrayOf(0f, 0.7f, 1f, 0.8f) // Blue-green when medium
                else -> floatArrayOf(0f, 0.5f, 1f, 0.8f) // Blue when far
              }
              
              virtualObjectShader.setVec4("u_Color", colorArray)
              virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
              render.draw(virtualObjectMesh, virtualObjectShader)
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error drawing navigation path", e)
    }
  }
  
  // Check if an anchor is within visible range of the user
  private fun isAnchorInVisibleRange(anchor: Anchor, cameraGeospatialPose: GeospatialPose): Boolean {
    try {
      // Get current frame's camera
      val frame = session?.update()
      val camera = frame?.camera
      val cameraPose = camera?.pose
      
      if (cameraPose != null) {
        // Extract translations
        val cameraTranslation = cameraPose.getTranslation()
        val anchorTranslation = anchor.getPose().getTranslation()
        
        // Calculate approximate 3D distance
        val dx = (anchorTranslation[0] - cameraTranslation[0]).toFloat()
        val dy = (anchorTranslation[1] - cameraTranslation[1]).toFloat()
        val dz = (anchorTranslation[2] - cameraTranslation[2]).toFloat()
        
        val distanceSquared = dx * dx + dy * dy + dz * dz
        val distance = sqrt(distanceSquared)
        
        // Only show anchors within 100 meters (adjustable based on your needs)
        return distance < 100f
      }
      
      // Default to visible if we can't calculate
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Error calculating anchor visibility", e)
      return true // Default to visible in case of error
    }
  }
  
  // Draw an arrow pointing toward the next waypoint or destination
  private fun drawDirectionalIndicator(render: SampleRender, cameraGeospatialPose: GeospatialPose) {
    try {
      // Find the next important waypoint or destination
      val nextAnchor = findNextWaypoint(cameraGeospatialPose)
      
      if (nextAnchor != null) {
        // Get camera and anchor poses
        val frame = session?.update()
        val camera = frame?.camera
        val cameraPose = camera?.pose
        
        if (cameraPose == null) return
        
        // Get anchor pose
        val anchorPose = nextAnchor.getPose()
        
        // First we need to determine if the anchor is in front of the user
        // Get camera forward vector (negative z-axis in OpenGL convention)
        val zAxis = cameraPose.getZAxis()
        val cameraForward = floatArrayOf(
          -zAxis[0],
          -zAxis[1],
          -zAxis[2]
        )
        
        // Vector from camera to anchor
        val cameraTranslation = cameraPose.getTranslation()
        val anchorTranslation = anchorPose.getTranslation()
        
        val directionToAnchor = floatArrayOf(
          anchorTranslation[0] - cameraTranslation[0],
          anchorTranslation[1] - cameraTranslation[1],
          anchorTranslation[2] - cameraTranslation[2]
        )
        
        // Normalize direction vector
        val distanceSquared = (directionToAnchor[0] * directionToAnchor[0] + 
                              directionToAnchor[1] * directionToAnchor[1] + 
                              directionToAnchor[2] * directionToAnchor[2])
        val distance = sqrt(distanceSquared)
        
        if (distance > 0) {
          directionToAnchor[0] /= distance
          directionToAnchor[1] /= distance
          directionToAnchor[2] /= distance
          
          // Calculate dot product with camera forward vector to see if anchor is in front
          val dotProduct = 
            directionToAnchor[0] * cameraForward[0] + 
            directionToAnchor[1] * cameraForward[1] + 
            directionToAnchor[2] * cameraForward[2]
            
          // Always show navigation arrows for better guidance, regardless of if waypoint is visible
          // This enhances usability by consistently showing guidance arrows
          
          // Create a floating arrow pointing toward the next waypoint
          val arrowMatrix = FloatArray(16)
          Matrix.setIdentityM(arrowMatrix, 0)
          
          // Position the arrow at a fixed distance in front of the user
          // Place it in the lower portion of the view for better visibility
          Matrix.translateM(arrowMatrix, 0, 0f, -0.7f, -2f)
          
          // Scale the arrow appropriately
          Matrix.scaleM(arrowMatrix, 0, 0.4f, 0.4f, 0.6f)
          
          // Add a slight up/down rotation based on distance
          val pitchAngle = -10f * (1f - min(1f, distance / 30f))
          Matrix.rotateM(arrowMatrix, 0, pitchAngle, 1f, 0f, 0f)
          
          // Calculate the angle between forward direction and anchor direction in XZ plane
          val forwardXZ = floatArrayOf(cameraForward[0], 0f, cameraForward[2])
          val directionXZ = floatArrayOf(directionToAnchor[0], 0f, directionToAnchor[2])
          
          // Normalize XZ vectors
          val forwardXZLengthSquared = (forwardXZ[0] * forwardXZ[0] + forwardXZ[2] * forwardXZ[2])
          val directionXZLengthSquared = (directionXZ[0] * directionXZ[0] + directionXZ[2] * directionXZ[2])
          
          val forwardXZLength = sqrt(forwardXZLengthSquared)
          val directionXZLength = sqrt(directionXZLengthSquared)
          
          var currentRotationAngle = 0f
          
          if (forwardXZLength > 0 && directionXZLength > 0) {
            forwardXZ[0] /= forwardXZLength
            forwardXZ[2] /= forwardXZLength
            directionXZ[0] /= directionXZLength
            directionXZ[2] /= directionXZLength
            
            // Calculate dot product
            val dotProductXZ = forwardXZ[0] * directionXZ[0] + forwardXZ[2] * directionXZ[2]
            val angleRadians = acos(dotProductXZ.toDouble().coerceIn(-1.0, 1.0))
            val angleXZ = toDegrees(angleRadians).toFloat()
            
            // Determine if the anchor is to the left or right of the camera
            val crossProduct = forwardXZ[0] * directionXZ[2] - forwardXZ[2] * directionXZ[0]
            currentRotationAngle = if (crossProduct >= 0) -angleXZ else angleXZ
            
            // Rotate the arrow to point toward the anchor
            Matrix.rotateM(arrowMatrix, 0, currentRotationAngle, 0f, 1f, 0f)
          }
          
          // Draw the arrow
          Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, arrowMatrix, 0)
          Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
          
          // Set bright color for the arrow - based on distance and make it pulsate
          val currentTimeMillis = System.currentTimeMillis().toFloat()
          val freq = 0.002f
          val thetaRadians = currentTimeMillis * freq
          val sinResult = sin(thetaRadians.toDouble())
          val sinValue = sinResult.toFloat()
          val alpha = 0.85f + 0.15f * sinValue
          
          val anchorType = anchorData[nextAnchor]
          
          val colorArray = when {
            // If this is the destination anchor, use red pulsating color
            anchorType == AnchorType.DESTINATION -> {
              floatArrayOf(1f, 0.5f, 0f, alpha) // Orange for destination
            }
            // If this is a turn, use yellow
            anchorType == AnchorType.TURN -> {
              floatArrayOf(1f, 1f, 0f, alpha) // Yellow for turns
            }
            // For regular waypoints, use lighter blue
            distance < 5f -> floatArrayOf(0f, 1f, 0.5f, alpha) // Green when close
            distance < 20f -> floatArrayOf(0f, 0.7f, 1f, alpha) // Blue-green when medium
            else -> floatArrayOf(0f, 0.5f, 1f, alpha) // Blue when far
          }
          
          virtualObjectShader.setVec4("u_Color", colorArray)
          
          // Draw the mesh
          virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
          render.draw(virtualObjectMesh, virtualObjectShader)
          
          // For the destination anchor, add a hovering marker when close enough
          if (anchorType == AnchorType.DESTINATION && distance < 50) {
            drawHoveringDestinationMarker(render, anchorPose, distance, cameraGeospatialPose)
          }
          
          // Update direction instruction based on angle
          updateDirectionInstruction(currentRotationAngle, distance, anchorType == AnchorType.DESTINATION)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error drawing directional indicator", e)
    }
  }
  
  // Draw a special hovering marker for the destination point
  private fun drawHoveringDestinationMarker(render: SampleRender, anchorPose: com.google.ar.core.Pose, distance: Float, cameraGeospatialPose: GeospatialPose) {
    try {
      // Create matrix for the destination marker
      val markerMatrix = FloatArray(16)
      anchorPose.toMatrix(markerMatrix, 0)
      
      // Add a hovering effect - make it float up and down
      val currentTimeMillis = System.currentTimeMillis().toFloat()
      val hoverFreq = 0.001f
      val hoverHeight = 0.2f
      val hoverOffset = sin(currentTimeMillis * hoverFreq) * hoverHeight
      
      // Apply the hover and make it larger
      Matrix.translateM(markerMatrix, 0, 0f, hoverOffset, 0f)
      
      // Make the marker larger as you get closer
      val scaleMultiplier = 1.0f + 1.0f * (1.0f - min(1.0f, distance / 30f))
      Matrix.scaleM(markerMatrix, 0, scaleMultiplier, scaleMultiplier, scaleMultiplier)
      
      // Add a slow rotation
      val rotationAngle = (currentTimeMillis * 0.02f) % 360f
      Matrix.rotateM(markerMatrix, 0, rotationAngle, 0f, 1f, 0f)
      
      // Draw the marker with a special color
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, markerMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
      
      // Pulsating effect with red color for destination
      val pulseFreq = 0.003f
      val pulseValue = 0.7f + 0.3f * sin((pulseFreq * currentTimeMillis).toDouble()).toFloat()
      virtualObjectShader.setVec4("u_Color", floatArrayOf(1f, 0f, 0f, pulseValue))
      
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(virtualObjectMesh, virtualObjectShader)
      
      // Draw a circle around the destination when very close
      if (distance < 10f) {
        drawDestinationCircle(render, anchorPose, distance)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error drawing destination marker", e)
    }
  }
  
  // Draw a circle around the destination point when close
  private fun drawDestinationCircle(render: SampleRender, anchorPose: com.google.ar.core.Pose, distance: Float) {
    try {
      // Create multiple points in a circle around the destination
      val circlePoints = 12
      val circleRadius = 0.8f + (10f - min(10f, distance)) * 0.1f // Grows as you get closer
      
      for (i in 0 until circlePoints) {
        val angle = (i.toFloat() / circlePoints) * 2 * PI.toFloat()
        val circleMatrix = FloatArray(16)
        anchorPose.toMatrix(circleMatrix, 0)
        
        // Position around the circle
        Matrix.translateM(circleMatrix, 0, 
          circleRadius * sin(angle.toDouble()).toFloat(), 
          0.05f, 
          circleRadius * cos(angle.toDouble()).toFloat())
        
        // Make smaller dots
        Matrix.scaleM(circleMatrix, 0, 0.2f, 0.2f, 0.2f)
        
        // Calculate pulsing effect with phase shift based on position
        val currentTimeMillis = System.currentTimeMillis().toFloat()
        val pulseFreq = 0.002f
        val phaseShift = i.toFloat() / circlePoints * 2 * PI.toFloat()
        val pulseValue = 0.5f + 0.5f * sin((pulseFreq * currentTimeMillis + phaseShift).toDouble()).toFloat()
        
        // Draw the dot with a different color
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, circleMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
        
        // Alternate colors
        val dotColor = if (i % 2 == 0) {
          floatArrayOf(1f, 0.2f, 0.2f, pulseValue) // Red
        } else {
          floatArrayOf(1f, 1f, 0.2f, pulseValue) // Yellow
        }
        
        virtualObjectShader.setVec4("u_Color", dotColor)
        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        render.draw(virtualObjectMesh, virtualObjectShader)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error drawing destination circle", e)
    }
  }
  
  // Update directional text instruction based on the current navigation state
  private fun updateDirectionInstruction(angle: Float, distance: Float, isDestination: Boolean) {
    try {
      if (isDestination && distance < 5f) {
        setDirectionInstruction("You have arrived at your destination!")
      } else if (isDestination && distance < 15f) {
        setDirectionInstruction("Your destination is ${formatDistance(distance)} ahead")
      } else if (abs(angle) < 15f) {
        setDirectionInstruction("Continue straight ahead for ${formatDistance(distance)}")
      } else if (abs(angle) < 60f) {
        val direction = if (angle < 0) "right" else "left" 
        setDirectionInstruction("Turn slightly $direction and go ${formatDistance(distance)}")
      } else if (abs(angle) < 120f) {
        val direction = if (angle < 0) "right" else "left"
        setDirectionInstruction("Take a $direction turn for ${formatDistance(distance)}")
      } else {
        setDirectionInstruction("Turn around and go back ${formatDistance(distance)}")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error updating direction instruction", e)
    }
  }
  
  // Format distance as text
  private fun formatDistance(meters: Float): String {
    return when {
      meters < 1000f -> "${meters.toInt()} meters"
      else -> String.format("%.1f km", meters / 1000f)
    }
  }
  
  // Set instruction text on the view - access via view.updateDirectionText instead of directly accessing textViews
  private fun setDirectionInstruction(instruction: String) {
    // Only attempt to update text if we're in an activity context
    if (context is HelloGeoActivity) {
      (context as HelloGeoActivity).runOnUiThread {
        try {
          // Update the direction text through the view
          if (helloGeoView != null) {
            helloGeoView?.setDirectionText(instruction)
          } else if (context is HelloGeoActivity) {
            (context as HelloGeoActivity).view.setDirectionText(instruction)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error setting direction instruction", e)
        }
      }
    }
  }
  
  // Find the next waypoint the user should head toward
  private fun findNextWaypoint(cameraGeospatialPose: GeospatialPose): Anchor? {
    // Get current position
    val currentLat = cameraGeospatialPose.latitude
    val currentLng = cameraGeospatialPose.longitude
    
    // Find the closest anchor/waypoint that's ahead of the user
    var closestAnchor: Anchor? = null
    var closestDistance = Double.MAX_VALUE
    
    // Get current camera pose in world space
    val camera = session?.update()?.camera
    val cameraPose = camera?.pose
    
    for (anchor in anchors) {
      if (anchor.trackingState != TrackingState.TRACKING) continue
      
      val anchorType = anchorData[anchor] ?: AnchorType.WAYPOINT
      
      // Skip start points
      if (anchorType == AnchorType.START) continue
      
      // Calculate distance - use a simpler approach
      try {
        // Get the anchor's pose in world space
        val anchorPose = anchor.getPose()
        
        // Check if we have the camera pose
        if (cameraPose != null) {
          // Extract translations
          val cameraTranslation = cameraPose.getTranslation()
          val anchorTranslation = anchorPose.getTranslation()
          
          // Calculate distance in 3D space
          val dx = (anchorTranslation[0] - cameraTranslation[0]).toFloat()
          val dy = (anchorTranslation[1] - cameraTranslation[1]).toFloat()
          val dz = (anchorTranslation[2] - cameraTranslation[2]).toFloat()
          
          val distance = sqrt(dx * dx + dy * dy + dz * dz).toDouble()
          
          if (distance < closestDistance) {
            closestDistance = distance
            closestAnchor = anchor
          }
        } else {
          // Fallback to using latitude/longitude when camera pose is not available
          val earth = session?.earth
          if (earth?.trackingState == TrackingState.TRACKING) {
            // Create a rough approximation using lat/lng
            val anchorLatLng = approximateAnchorLatLng(earth, anchor)
            
            if (anchorLatLng != null) {
              val distance = calculateDistance(
                currentLat, currentLng,
                anchorLatLng.latitude, anchorLatLng.longitude
              )
              
              if (distance < closestDistance) {
                closestDistance = distance
                closestAnchor = anchor
              }
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error calculating distance to anchor", e)
      }
    }
    
    return closestAnchor
  }
  
  // Approximate LatLng for an anchor - this is not precise but works for our needs
  private fun approximateAnchorLatLng(earth: Earth, anchor: Anchor): LatLng? {
    try {
      // Get camera geospatial pose
      val cameraGeo = earth.cameraGeospatialPose
      val camera = session?.update()?.camera
      val cameraPose = camera?.pose
      
      if (cameraPose != null) {
        // Extract translations
        val cameraTranslation = cameraPose.getTranslation()
        val anchorTranslation = anchor.getPose().getTranslation()
        
        // Calculate relative position (very rough approximation)
        val dx = (anchorTranslation[0] - cameraTranslation[0]).toFloat()
        val dy = (anchorTranslation[1] - cameraTranslation[1]).toFloat()
        
        // Convert to approximate lat/lng difference (very simplistic approach)
        // This assumes a flat Earth which is not accurate for long distances
        val metersPerDegreeLatitude = 111320.0 // approximate
        val metersPerDegreeLongitude = 111320.0 * cos(toRadians(cameraGeo.latitude))
        
        val latDiff = dy.toDouble() / metersPerDegreeLatitude
        val lngDiff = dx.toDouble() / metersPerDegreeLongitude
        
        val newLat = cameraGeo.latitude + latDiff
        val newLng = cameraGeo.longitude + lngDiff
        return LatLng(newLat, newLng)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error approximating anchor position", e)
    }
    
    return null
  }

  // Enum to store anchor types
  enum class AnchorType {
    START, WAYPOINT, TURN, DESTINATION
  }
  
  // Store anchor data - type information
  private val anchorData = mutableMapOf<Anchor, AnchorType>()
  
  // Draw a standard anchor with specified color
  private fun drawAnchorWithColor(
    render: SampleRender, 
    modelMatrix: FloatArray,
    red: Float,
    green: Float,
    blue: Float,
    alpha: Float
  ) {
    try {
      // Calculate the model-view and model-view-projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
      
      // Set custom color for the shader - create a float array for the color values
      val colorArray = floatArrayOf(red, green, blue, alpha)
      virtualObjectShader.setVec4("u_Color", colorArray)
      
      // Draw the mesh with the shader
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      render.draw(virtualObjectMesh, virtualObjectShader)
    } catch (e: Exception) {
      Log.e(TAG, "Error drawing anchor with color", e)
    }
  }
  
  // Draw all anchors with different colors based on type
  private fun drawAnchors(render: SampleRender, earth: Earth) {
    try {
      // First draw the destination anchor if it exists
      destinationAnchor?.let { anchor ->
        if (anchor.trackingState == TrackingState.TRACKING) {
          anchor.getPose().toMatrix(modelMatrix, 0)
          
          // Make destination anchor larger
          Matrix.scaleM(modelMatrix, 0, 0.7f, 0.7f, 0.7f)
          
          // Draw with red color
          drawAnchorWithColor(render, modelMatrix, 1.0f, 0.0f, 0.0f, 1.0f)
        }
      }
      
      // Draw regular path anchors with colors based on type
      for (anchor in anchors) {
        if (anchor.trackingState != TrackingState.TRACKING) continue
        
        anchor.getPose().toMatrix(modelMatrix, 0)
        
        // Get anchor type
        val anchorType = anchorData[anchor] ?: AnchorType.WAYPOINT
        
        // Set color and scale based on anchor type
        when (anchorType) {
          AnchorType.START -> {
            // Green for start
            Matrix.scaleM(modelMatrix, 0, 0.6f, 0.6f, 0.6f)
            drawAnchorWithColor(render, modelMatrix, 0.0f, 0.8f, 0.0f, 1.0f)
          }
          AnchorType.TURN -> {
            // Yellow for turns - make them larger
            Matrix.scaleM(modelMatrix, 0, 0.5f, 0.5f, 0.5f)
            drawAnchorWithColor(render, modelMatrix, 1.0f, 0.84f, 0.0f, 1.0f)
          }
          AnchorType.WAYPOINT -> {
            // Blue for regular waypoints - make them smaller
            Matrix.scaleM(modelMatrix, 0, 0.3f, 0.3f, 0.3f)
            drawAnchorWithColor(render, modelMatrix, 0.0f, 0.5f, 1.0f, 0.8f)
          }
          else -> {
            // Default - white
            Matrix.scaleM(modelMatrix, 0, 0.4f, 0.4f, 0.4f)
            drawAnchorWithColor(render, modelMatrix, 1.0f, 1.0f, 1.0f, 0.7f)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error drawing anchors", e)
    }
  }
  
  // Calculate a confidence score for the AR tracking quality
  private fun calculateTrackingConfidence(pose: GeospatialPose): Float {
    // Higher is better, 1.0 is perfect
    val horizontalConfidence = when {
      pose.horizontalAccuracy <= 1.0 -> 1.0f
      pose.horizontalAccuracy <= 3.0 -> 0.7f
      pose.horizontalAccuracy <= 5.0 -> 0.5f
      pose.horizontalAccuracy <= 10.0 -> 0.3f
      else -> 0.1f
    }
    
    val bearingConfidence = when {
      pose.headingAccuracy <= 5.0 -> 1.0f
      pose.headingAccuracy <= 10.0 -> 0.7f
      pose.headingAccuracy <= 15.0 -> 0.5f
      pose.headingAccuracy <= 20.0 -> 0.3f
      else -> 0.1f
    }
    
    // Combine confidences (weigh horizontal accuracy more)
    return (horizontalConfidence * 0.7f) + (bearingConfidence * 0.3f)
  }
  
  private fun updateNavigationAnchors(earth: Earth, cameraGeospatialPose: GeospatialPose) {
    // Update existing anchors or create new ones based on the current path
    // This would be called during navigation to update directional arrows
    
    // For simplicity, we're just going to keep the existing anchors
    // A full implementation would update the path based on current position
  }
  
  fun createAnchorAtLocation(latitude: Double, longitude: Double): Anchor? {
    val earth = session?.earth ?: run {
      Log.e(TAG, "Cannot create anchor: Earth is null")
      return null
    }
    
    if (earth.trackingState != TrackingState.TRACKING) {
      Log.e(TAG, "Cannot create anchor: Earth is not tracking. Current state: ${earth.trackingState}")
      return null
    }
    
    val altitude = earth.cameraGeospatialPose.altitude - 1.0
    Log.d(TAG, "Creating anchor at: lat=$latitude, lng=$longitude, altitude=$altitude")
    
    try {
      val anchor = earth.createAnchor(
        latitude,
        longitude,
        altitude,
        0f,
        0f,
        0f,
        1f
      )
      
      // Store as destination anchor
      destinationAnchor = anchor
      
      // Update map marker
      updateMapMarker(LatLng(latitude, longitude))
      
      Log.d(TAG, "Anchor created successfully: $anchor")
      return anchor
    } catch (e: Exception) {
      Log.e(TAG, "Error creating anchor", e)
      return null
    }
  }
  
  fun createDirectionalAnchor(startLat: Double, startLng: Double, endLat: Double, endLng: Double): Anchor? {
    val earth = session?.earth ?: return null
    if (earth.trackingState != TrackingState.TRACKING) {
      return null
    }
    
    // Calculate bearing between points
    val bearing = calculateBearing(startLat, startLng, endLat, endLng)
    
    // Create anchor at midpoint with proper orientation
    val midLat = startLat + (endLat - startLat) / 2.0
    val midLng = startLng + (endLng - startLng) / 2.0
    val altitude = earth.cameraGeospatialPose.altitude - 1.0
    
    // Convert bearing to quaternion (rotate arrow to point in right direction)
    val radians = toRadians(bearing)
    val halfRadians = radians / 2.0
    val qx = 0f
    val qy = sin(halfRadians).toFloat()
    val qz = 0f
    val qw = cos(halfRadians).toFloat()
    
    val anchor = earth.createAnchor(
      midLat, 
      midLng,
      altitude,
      qx, qy, qz, qw
    )
    
    anchors.add(anchor)
    return anchor
  }
  
  private fun calculateBearing(startLat: Double, startLng: Double, endLat: Double, endLng: Double): Double {
    val startLatRad = toRadians(startLat)
    val startLngRad = toRadians(startLng)
    val endLatRad = toRadians(endLat)
    val endLngRad = toRadians(endLng)
    
    val dLng = endLngRad - startLngRad
    
    val sinDLng = sin(dLng)
    val cosEndLatRad = cos(endLatRad)
    val cosStartLatRad = cos(startLatRad)
    val sinEndLatRad = sin(endLatRad)
    val sinStartLatRad = sin(startLatRad)
    val cosDLng = cos(dLng)
    
    val y = sinDLng * cosEndLatRad
    val x = cosStartLatRad * sinEndLatRad -
            sinStartLatRad * cosEndLatRad * cosDLng
    
    var bearing = atan2(y, x)
    if (bearing < 0) {
      bearing = bearing + (2.0 * PI)
    }
    
    return toDegrees(bearing)
  }
  
  fun createPathAnchors(path: List<LatLng>) {
    clearAnchors() // Remove existing anchors
    
    try {
      // Create destination anchor at the end of the path
      if (path.isNotEmpty()) {
        Log.d(TAG, "Creating path anchors for ${path.size} points")
        // Add start point
        val start = path.first()
        val startAnchor = createAnchorAtLocation(start.latitude, start.longitude)
        startAnchor?.let {
          anchors.add(it)
          anchorData[it] = AnchorType.START
          Log.d(TAG, "Created START anchor at ${start.latitude}, ${start.longitude}")
        }
        
        // Add destination anchor at the end of the path
        val destination = path.last()
        val destAnchor = createAnchorAtLocation(destination.latitude, destination.longitude)
        destAnchor?.let {
          destinationAnchor = it
          anchorData[it] = AnchorType.DESTINATION
          Log.d(TAG, "Created DESTINATION anchor at ${destination.latitude}, ${destination.longitude}")
        }
        
        // Create path waypoints with smarter spacing
        if (path.size > 2) {
          // Analyze path for turns and significant points
          val significantPoints = findSignificantPathPoints(path)
          
          Log.d(TAG, "Path has ${significantPoints.size} significant points")
          
          // Create anchors with more density for better visual guidance
          val maxSpacing = 25.0 // Maximum 25 meters between anchors for better visibility
          
          // Track last anchor position to ensure proper spacing
          var lastAnchorPos = start
          
          for (i in 0 until significantPoints.size) {
            val point = significantPoints[i]
            val pointPos = point.position
            
            // Calculate distance from last anchor
            val distanceFromLast = calculateDistance(
              lastAnchorPos.latitude, lastAnchorPos.longitude,
              pointPos.latitude, pointPos.longitude
            )
            
            // If this point is too far from the last anchor, add intermediate anchors
            if (distanceFromLast > maxSpacing) {
              val segmentCount = ceil(distanceFromLast / maxSpacing).toInt()
              
              for (j in 1 until segmentCount) {
                val fraction = j.toDouble() / segmentCount
                
                // Linear interpolation between points
                val intermediateLat = lastAnchorPos.latitude + (pointPos.latitude - lastAnchorPos.latitude) * fraction
                val intermediateLng = lastAnchorPos.longitude + (pointPos.longitude - lastAnchorPos.longitude) * fraction
                
                val intermediateAnchor = createAnchorAtLocation(intermediateLat, intermediateLng)
                intermediateAnchor?.let {
                  anchors.add(it)
                  anchorData[it] = AnchorType.WAYPOINT
                  Log.d(TAG, "Created intermediate WAYPOINT anchor at $intermediateLat, $intermediateLng")
                }
              }
            }
            
            // Now add the actual point
            val anchor = createAnchorAtLocation(pointPos.latitude, pointPos.longitude)
            anchor?.let {
              anchors.add(it)
              anchorData[it] = if (point.isTurn) AnchorType.TURN else AnchorType.WAYPOINT
              
              // If it's a turn, log the angle for debugging
              if (point.isTurn) {
                Log.d(TAG, "Created TURN anchor at ${pointPos.latitude}, ${pointPos.longitude} with angle: ${point.angle} degrees")
              } else {
                Log.d(TAG, "Created WAYPOINT anchor at ${pointPos.latitude}, ${pointPos.longitude}")
              }
            }
            
            // Update last anchor position
            lastAnchorPos = pointPos
          }
        }
        
        isNavigating = true
        Log.d(TAG, "Created ${anchors.size} path anchors and 1 destination anchor")
        
        // When done creating anchors, print the list of all anchors created
        if (anchors.size > 0) {
          Log.d(TAG, "Anchor summary:")
          var turnCount = 0
          var waypointCount = 0
          var startCount = 0
          var destCount = 0
          
          for (anchor in anchors) {
            when (anchorData[anchor]) {
              AnchorType.START -> startCount++
              AnchorType.TURN -> turnCount++
              AnchorType.WAYPOINT -> waypointCount++
              AnchorType.DESTINATION -> destCount++
              null -> Log.d(TAG, "Anchor with no type data")
            }
          }
          
          Log.d(TAG, "- $startCount start anchor(s)")
          Log.d(TAG, "- $turnCount turn anchors")
          Log.d(TAG, "- $waypointCount waypoint anchors") 
          Log.d(TAG, "- $destCount destination anchor(s)")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error creating path anchors", e)
    }
  }
  
  // Path point with turn information
  data class PathPoint(val position: LatLng, val isTurn: Boolean, val angle: Float = 0f)
  
  // Analyze the path to find significant points like turns
  private fun findSignificantPathPoints(path: List<LatLng>): List<PathPoint> {
    val significantPoints = mutableListOf<PathPoint>()
    
    if (path.size < 3) return significantPoints
    
    // Set minimum distance between points (in meters)
    val minPointDistance = 10.0 // meters
    
    // Set minimum angle change to be considered a turn (in degrees)
    val minTurnAngle = 25.0 // degrees
    
    var lastAddedPoint = path[0]
    
    // Analyze each segment of the path for turns
    for (i in 1 until path.size - 1) {
      val prev = path[i-1]
      val current = path[i]
      val next = path[i+1]
      
      // Calculate bearing change to detect turns
      val bearing1 = calculateBearing(prev.latitude, prev.longitude, current.latitude, current.longitude)
      val bearing2 = calculateBearing(current.latitude, current.longitude, next.latitude, next.longitude)
      
      var bearingChange = abs(bearing2 - bearing1)
      if (bearingChange > 180) bearingChange = 360 - bearingChange
      
      // Calculate distance from last added point
      val distance = calculateDistance(
        lastAddedPoint.latitude, lastAddedPoint.longitude,
        current.latitude, current.longitude
      )
      
      val isTurn = bearingChange > minTurnAngle
      
      // Add point if it's a turn or if we've gone far enough since the last point
      if (isTurn || distance > minPointDistance) {
        significantPoints.add(PathPoint(current, isTurn, bearingChange.toFloat()))
        lastAddedPoint = current
      }
    }
    
    return significantPoints
  }
  
  // Calculate distance between two points in meters
  private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadius = 6371000.0 // meters
    
    val dLat = toRadians(lat2 - lat1)
    val dLng = toRadians(lng2 - lng1)
    
    val sinHalfDLat = sin(dLat / 2)
    val sinHalfDLng = sin(dLng / 2)
    
    val lat1Rad = toRadians(lat1)
    val lat2Rad = toRadians(lat2)
    val cosLat1 = cos(lat1Rad)
    val cosLat2 = cos(lat2Rad)
    
    val a = sinHalfDLat * sinHalfDLat + 
            cosLat1 * cosLat2 * sinHalfDLng * sinHalfDLng
    
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    
    return earthRadius * c // Distance in meters
  }
  
  fun clearAnchors() {
    try {
      // Detach all anchors
      for (anchor in anchors) {
        anchor.detach()
      }
      anchors.clear()
      
      destinationAnchor?.detach()
      destinationAnchor = null
      
      isNavigating = false
      Log.d(TAG, "Cleared all anchors")
    } catch (e: Exception) {
      Log.e(TAG, "Error clearing anchors", e)
    }
  }

  var earthAnchor: Anchor? = null

  fun onMapClick(latLng: LatLng) {
    val earth = session?.earth ?: return
    if(earth.trackingState != TrackingState.TRACKING){
      return
    }
    earthAnchor?.detach()
    earthAnchor = earth.createAnchor(
      latLng.latitude,
      latLng.longitude,
      earth.cameraGeospatialPose.altitude - 1.3,
      0f,
      0f,
      0f,
      1f
    )

    updateMapMarker(latLng)
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor) {
    try {
      // Get the current pose of the Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      anchor.getPose().toMatrix(modelMatrix, 0)

      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
    } catch (e: Exception) {
      Log.e(TAG, "Error rendering compass at anchor", e)
    }
  }
  
  private fun SampleRender.renderObject(anchor: Anchor, mesh: Mesh, shader: Shader) {
    try {
      // Get the current pose of the Anchor in world space
      anchor.getPose().toMatrix(modelMatrix, 0)

      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

      // Update shader properties and draw
      shader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
      draw(mesh, shader, virtualSceneFramebuffer)
    } catch (e: Exception) {
      Log.e(TAG, "Error rendering object at anchor", e)
    }
  }

  private fun showError(errorMessage: String) {
    when (context) {
      is HelloGeoActivity -> {
        (context as HelloGeoActivity).runOnUiThread {
          // Provide a more descriptive error message if it's null or empty
          val displayMessage = when {
            errorMessage.isNullOrEmpty() -> "Unknown error occurred in AR rendering"
            errorMessage.contains("null") -> "AR initialization error - please check location permissions and internet connection"
            else -> errorMessage
          }
          (context as HelloGeoActivity).view.snackbarHelper.showError(context, displayMessage)
        }
      }
      is ARActivity -> {
        (context as ARActivity).runOnUiThread {
          val displayMessage = when {
            errorMessage.isNullOrEmpty() -> "Unknown error occurred in AR rendering"
            errorMessage.contains("null") -> "AR initialization error - please check location permissions and internet connection"
            else -> errorMessage
          }
          Toast.makeText(context, displayMessage, Toast.LENGTH_LONG).show()
        }
      }
      is Activity -> {
        (context as Activity).runOnUiThread {
          val displayMessage = when {
            errorMessage.isNullOrEmpty() -> "Unknown error occurred in AR rendering"
            errorMessage.contains("null") -> "AR initialization error - please check location permissions and internet connection"
            else -> errorMessage
          }
          Toast.makeText(context, displayMessage, Toast.LENGTH_LONG).show()
        }
      }
      else -> {
        Log.e(TAG, "Error: $errorMessage")
      }
    }
  }

  private fun handlePersistentEarthFailure(reason: String) {
    if (hasFallenBackToMapMode) return // Prevent multiple fallbacks
    
    hasFallenBackToMapMode = true
    Log.e(TAG, "Falling back to map mode: $reason")
    
    when (context) {
      is HelloGeoActivity -> {
        val activity = context as HelloGeoActivity
        activity.runOnUiThread {
          // Show a toast explaining the issue
          Toast.makeText(
            activity,
            "AR features unavailable: $reason. Switching to map-only mode.",
            Toast.LENGTH_LONG
          ).show()
          
          // Start the fallback activity
          try {
            activity.startActivity(Intent(activity, FallbackActivity::class.java))
            activity.finish()
          } catch (e: Exception) {
            Log.e(TAG, "Error launching FallbackActivity", e)
            // Call the public method
            activity.showFallbackUserInterface()
          }
        }
      }
      is ARActivity -> {
        val activity = context as ARActivity
        activity.runOnUiThread {
          Toast.makeText(activity, "AR features unavailable: $reason. Switching to map-only mode.", Toast.LENGTH_LONG).show()
          activity.returnToMapMode()
        }
      }
      is Activity -> {
        // For other activity types
        val activity = context as Activity
        activity.runOnUiThread {
          Toast.makeText(activity, "AR features unavailable: $reason", Toast.LENGTH_LONG).show()
        }
      }
      else -> {
        Log.e(TAG, "AR features unavailable: $reason")
      }
    }
  }

  fun setSession(session: Session) {
    this.arSession = session
  }

  val session: Session?
    get() = arSession

  private fun updateMapPosition(latitude: Double, longitude: Double, heading: Double) {
    if (context is HelloGeoActivity) {
      context.view.mapView?.updateMapPosition(latitude, longitude, heading)
    }
    // In ARActivity we don't have a mapView to update
  }

  private fun updateStatusText(earth: Earth?, geospatialPose: GeospatialPose?, status: String = "") {
    if (context is HelloGeoActivity) {
      if (status.isNotEmpty()) {
        // If we have a status string, let's just use a simpler approach
        val statusMessage = if (earth == null) {
          "Earth: NULL - $status"
        } else {
          "Earth: ${earth.trackingState} - $status"
        }
        
        // Show the status directly using a Toast for critical status updates
        if (status != "PAUSED" && status != "Not tracking") { // Don't show too many toasts
          (context as HelloGeoActivity).runOnUiThread {
            Log.d(TAG, "Status update: $statusMessage")
          }
        }
      }
      
      // Always update the regular status display with Earth and pose data
      (context as HelloGeoActivity).view.updateStatusText(earth, geospatialPose, status)
    }
    // ARActivity has its own status indicator
  }

  private fun updateTrackingState(trackingState: TrackingState) {
    trackingStateHelper?.updateKeepScreenOnFlag(trackingState)
  }

  private fun updateMapMarker(latLng: LatLng) {
    helloGeoView?.mapView?.let { mapView ->
      if (mapView.googleMap != null) {
        if (mapView.earthMarker != null) {
          mapView.earthMarker?.position = latLng
          mapView.earthMarker?.isVisible = true
        } else {
          // Create marker if it doesn't exist
          mapView.createEarthMarker(latLng)
        }
      }
    }
  }

  // Update AR path with new route points
  fun updatePathAnchors(newRoutePoints: List<LatLng>) {
    Log.d(TAG, "Updating path anchors with ${newRoutePoints.size} points")
    
    // Store the route points
    routePoints = newRoutePoints
    
    // Mark as navigating
    isNavigating = true
    
    // Clear existing anchors on update
    clearAnchors()
    
    // We'll create the anchors when Earth is tracking
  }
  
  // Creates anchors for the current route when Earth is tracking
  private fun createRouteAnchorsIfNeeded(earth: Earth) {
    if (!isNavigating || routePoints.isEmpty() || earth.trackingState != TrackingState.TRACKING) {
      return
    }
    
    try {
      if (anchors.isEmpty()) {
        Log.d(TAG, "Creating anchors for route with ${routePoints.size} points")
        
        // For resource usage reasons, we'll only create anchors for a subset of points
        // for long routes
        val maxAnchors = 30
        
        if (routePoints.size <= maxAnchors) {
          // If route is short enough, use all points
          createAnchorsForRoutePoints(earth, routePoints)
        } else {
          // For longer routes, downsample while keeping significant points (turns)
          
          // First, identify turns and significant points by angle changes
          val significantPoints = findSignificantPathPoints(routePoints)
          
          // Always include start and end points
          val startPoint = routePoints.first()
          val endPoint = routePoints.last()
          
          // Combine significant points, ensuring we don't exceed maxAnchors
          val finalPoints = mutableListOf<LatLng>()
          finalPoints.add(startPoint)
          
          // Add significant turn points (limited to maxAnchors - 2 to account for start/end)
          val maxTurnPoints = maxAnchors - 2
          if (significantPoints.size <= maxTurnPoints) {
            // Add all significant points
            significantPoints.forEach { finalPoints.add(it.position) }
          } else {
            // Add only turns (not regular waypoints)
            val turnPoints = significantPoints.filter { it.isTurn }
            
            if (turnPoints.size <= maxTurnPoints) {
              // Add all turns
              turnPoints.forEach { finalPoints.add(it.position) }
            } else {
              // Even the turns are too many, sample them
              for (i in 0 until maxTurnPoints) {
                val index = (i * turnPoints.size) / maxTurnPoints
                finalPoints.add(turnPoints[index].position)
              }
            }
          }
          
          // Make sure the end point is included
          if (finalPoints.last() != endPoint) {
            finalPoints.add(endPoint)
          }
          
          createAnchorsForRoutePoints(earth, finalPoints)
        }
        
        Log.d(TAG, "Created ${anchors.size} anchors for navigation")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error creating route anchors", e)
    }
  }
  
  // Helper method to create anchors for a list of route points
  private fun createAnchorsForRoutePoints(earth: Earth, points: List<LatLng>) {
    try {
      // Add start point
      if (points.isNotEmpty()) {
        val start = points.first()
        val startAnchor = earth.createAnchor(
          start.latitude,
          start.longitude,
          earth.cameraGeospatialPose.altitude - 1.5, // Place slightly below camera
          0f, 0f, 0f, 1f
        )
        anchors.add(startAnchor)
        anchorData[startAnchor] = AnchorType.START
        
        // Add intermediate waypoints
        for (i in 1 until points.size - 1) {
          val point = points[i]
          
          // Identify if it's a turn by checking angle with neighbors
          val isTurn = if (i > 0 && i < points.size - 1) {
            val prev = points[i-1]
            val current = point
            val next = points[i+1]
            
            val bearing1 = calculateBearing(
              prev.latitude, prev.longitude,
              current.latitude, current.longitude
            )
            val bearing2 = calculateBearing(
              current.latitude, current.longitude,
              next.latitude, next.longitude
            )
            
            var bearingChange = abs(bearing2 - bearing1)
            if (bearingChange > 180) bearingChange = 360 - bearingChange
            
            // Consider it a turn if angle changes by more than 25 degrees
            bearingChange > 25.0
          } else {
            false
          }
          
          val anchor = earth.createAnchor(
            point.latitude,
            point.longitude,
            earth.cameraGeospatialPose.altitude - 1.5,
            0f, 0f, 0f, 1f
          )
          
          anchors.add(anchor)
          anchorData[anchor] = if (isTurn) AnchorType.TURN else AnchorType.WAYPOINT
        }
        
        // Add destination
        if (points.size > 1) {
          val destination = points.last()
          val destAnchor = earth.createAnchor(
            destination.latitude,
            destination.longitude,
            earth.cameraGeospatialPose.altitude - 1.5,
            0f, 0f, 0f, 1f
          )
          
          anchors.add(destAnchor)
          anchorData[destAnchor] = AnchorType.DESTINATION
          destinationAnchor = destAnchor
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error creating route anchors from points", e)
    }
  }

  private fun fallbackToMapOnlyMode() {
    // Implementation of fallbackToMapOnlyMode method
  }

  // Utility functions for angle conversion
  private fun toRadians(degrees: Double): Double {
    return degrees * PI / 180.0
  }
  
  private fun toDegrees(radians: Double): Double {
    return radians * 180.0 / PI
  }
}
