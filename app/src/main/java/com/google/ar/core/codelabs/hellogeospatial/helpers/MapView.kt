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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.util.Log
import androidx.annotation.ColorInt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.R

class MapView(val activity: HelloGeoActivity, val googleMap: GoogleMap) {
  private val CAMERA_MARKER_COLOR: Int = Color.argb(255, 0, 255, 0)
  private val EARTH_MARKER_COLOR: Int = Color.argb(255, 125, 125, 125)
  private val SEARCH_MARKER_COLOR: Int = Color.argb(255, 255, 0, 0)
  private val USER_MARKER_COLOR: Int = Color.argb(255, 0, 0, 255)

  var setInitialCameraPosition = false
  var cameraMarker: Marker? = createMarker(CAMERA_MARKER_COLOR)
  var cameraIdle = true

  var earthMarker: Marker? = createMarker(EARTH_MARKER_COLOR)
  var searchMarker: Marker? = null
  var userLocationMarker: Marker? = null

  init {
    googleMap.uiSettings.apply {
      isMapToolbarEnabled = false
      isIndoorLevelPickerEnabled = false
      isZoomControlsEnabled = false
      isTiltGesturesEnabled = false
      isScrollGesturesEnabled = false
    }

    googleMap.setOnMarkerClickListener { unused -> false }

    // Add listeners to keep track of when the GoogleMap camera is moving.
    googleMap.setOnCameraMoveListener { cameraIdle = false }
    googleMap.setOnCameraIdleListener { cameraIdle = true }
  }

  fun updateMapPosition(latitude: Double, longitude: Double, heading: Double) {
    val position = LatLng(latitude, longitude)
    activity.runOnUiThread {
      // If the map is already in the process of a camera update, then don't move it.
      if (!cameraIdle) {
        return@runOnUiThread
      }
      cameraMarker?.isVisible = true
      cameraMarker?.position = position
      cameraMarker?.rotation = heading.toFloat()

      val cameraPositionBuilder: CameraPosition.Builder = if (!setInitialCameraPosition) {
        // Set the camera position with an initial default zoom level.
        setInitialCameraPosition = true
        CameraPosition.Builder().zoom(21f).target(position)
      } else {
        // Set the camera position and keep the same zoom level.
        CameraPosition.Builder()
          .zoom(googleMap.cameraPosition.zoom)
          .target(position)
      }
      googleMap.moveCamera(
        CameraUpdateFactory.newCameraPosition(cameraPositionBuilder.build()))
    }
  }
  
  fun navigateToSearchLocation(latLng: LatLng, locationName: String) {
    activity.runOnUiThread {
      // Remove existing search marker if it exists
      searchMarker?.remove()
      
      // Create a new marker for the search location
      val markerOptions = MarkerOptions()
        .position(latLng)
        .title(locationName)
        .icon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(SEARCH_MARKER_COLOR)))
      
      searchMarker = googleMap.addMarker(markerOptions)
      
      // Move camera to the search location with animation
      val cameraUpdate = CameraUpdateFactory.newLatLngZoom(latLng, 18f)
      googleMap.animateCamera(cameraUpdate)
    }
  }

  /** Creates and adds a 2D anchor marker on the 2D map view.  */
  private fun createMarker(
    color: Int,
  ): Marker? {
    val markersOptions = MarkerOptions()
      .position(LatLng(0.0,0.0))
      .draggable(false)
      .anchor(0.5f, 0.5f)
      .flat(true)
      .visible(false)
      .icon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(color)))
    return googleMap.addMarker(markersOptions)
  }

  private fun createColoredMarkerBitmap(@ColorInt color: Int): Bitmap {
    val opt = BitmapFactory.Options()
    opt.inMutable = true
    val navigationIcon =
      BitmapFactory.decodeResource(activity.resources, R.drawable.ic_navigation_white_48dp, opt)
    val p = Paint()
    p.colorFilter = LightingColorFilter(color,  /* add= */1)
    val canvas = Canvas(navigationIcon)
    canvas.drawBitmap(navigationIcon,  /* left= */0f,  /* top= */0f, p)
    return navigationIcon
  }

  fun createEarthMarker(latLng: LatLng) {
    googleMap?.let { map ->
      // Remove existing marker if any
      earthMarker?.remove()
      
      // Create new marker
      earthMarker = map.addMarker(
        MarkerOptions()
          .position(latLng)
          .title("AR Location")
          .draggable(false)
      )
    }
  }
  
  fun createUserLocationMarker(latLng: LatLng) {
    googleMap?.let { map ->
      userLocationMarker?.remove()
      userLocationMarker = map.addMarker(
        MarkerOptions()
          .position(latLng)
          .title("Your Location")
          .icon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(USER_MARKER_COLOR)))
          .anchor(0.5f, 0.5f)
      )
    }
  }
}