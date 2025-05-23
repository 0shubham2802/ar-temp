/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.app.Activity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException


class ARCoreSessionLifecycleHelper(
  val activity: Activity,
  val features: Set<Session.Feature> = setOf()
) : DefaultLifecycleObserver {
  var installRequested = false
  var session: Session? = null
    private set


  var exceptionCallback: ((Exception) -> Unit)? = null


  var beforeSessionResume: ((Session) -> Unit)? = null


  private fun tryCreateSession(): Session? {
    // The app must have been given the CAMERA permission. If we don't have it yet, request it.
    if (!GeoPermissionsHelper.hasGeoPermissions(activity)) {
      GeoPermissionsHelper.requestPermissions(activity)
      return null
    }

    return try {
      // Request installation if necessary.
      when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)!!) {
        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
          installRequested = true
          // tryCreateSession will be called again, so we return null for now.
          return null
        }
        ArCoreApk.InstallStatus.INSTALLED -> {
          // Left empty; nothing needs to be done.
        }
      }

      // Create a session if Google Play Services for AR is installed and up to date.
      Session(activity, features)
    } catch (e: Exception) {
      exceptionCallback?.invoke(e)
      null
    }
  }

  // Non-lifecycle version of onResume
  fun onResume() {
    val session = this.session ?: tryCreateSession() ?: return
    try {
      beforeSessionResume?.invoke(session)
      session.resume()
      this.session = session
    } catch (e: CameraNotAvailableException) {
      exceptionCallback?.invoke(e)
    }
  }

  // Non-lifecycle version of onPause
  fun onPause() {
    session?.pause()
  }

  // Non-lifecycle version of onDestroy
  fun onDestroy() {
    session?.close()
    session = null
  }

  override fun onResume(owner: LifecycleOwner) {
    onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    onPause()
  }

  override fun onDestroy(owner: LifecycleOwner) {
    onDestroy()
  }
}
