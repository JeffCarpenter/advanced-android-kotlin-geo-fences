/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.treasureHunt

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import com.example.android.treasureHunt.databinding.ActivityHuntMainBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.material.snackbar.Snackbar


//private const val REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE = 33
//private const val REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE = 34
//private const val REQUEST_TURN_DEVICE_LOCATION_ON = 29

/**
 * The Treasure Hunt app is a single-player game based on geofences.
 *
 * This app demonstrates how to create and remove geofences using the GeofencingApi. Uses an
 * BroadcastReceiver to monitor geofence transitions and creates notification and finishes the game
 * when the user enters the final geofence (destination).
 *
 * This app requires a device's Location settings to be turned on. It also requires
 * the ACCESS_FINE_LOCATION permission and user consent. For geofences to work
 * in Android Q, app also needs the ACCESS_BACKGROUND_LOCATION permission and user consent.
 */

class HuntMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHuntMainBinding
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var viewModel: GeofenceViewModel

    private val runningQOrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // A PendingIntent for the Broadcast Receiver that handles geofence transitions.
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        intent.action = ACTION_GEOFENCE_EVENT
        // Use FLAG_UPDATE_CURRENT so that you get the same pending intent back when calling
        // addGeofences() and removeGeofences().
        var intentFlagTypeUpdateCurrent = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intentFlagTypeUpdateCurrent = PendingIntent.FLAG_IMMUTABLE
        }
        PendingIntent.getBroadcast(this, 0, intent, intentFlagTypeUpdateCurrent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_hunt_main)
        val savedStateFactory = SavedStateViewModelFactory(this.application, this)
        viewModel = ViewModelProvider(this, savedStateFactory)[GeofenceViewModel::class.java]
        binding.viewmodel = viewModel
        binding.lifecycleOwner = this
        geofencingClient = LocationServices.getGeofencingClient(this)

        // Create channel for notifications
        createChannel(this)
    }

    override fun onStart() {
        super.onStart()
        checkPermissionsAndStartGeofencing()
    }

    /*
 *  When we get the result from asking the user to turn on device location, we call
 *  checkDeviceLocationSettingsAndStartGeofence again to make sure it's actually on, but
 *  we don't resolve the check to keep the user from seeing an endless loop.
 */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TURN_DEVICE_LOCATION_ON) {
            // We don't rely on the result code, but just check the location setting again
            checkDeviceLocationSettingsAndStartGeofence(false)
        }
    }

    /*
	 *  When the user clicks on the notification, this method will be called, letting us know that
	 *  the geofence has been triggered, and it's time to move to the next one in the treasure
	 *  hunt.
	 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val extras = intent?.extras
        if (extras != null) {
            if (extras.containsKey(GeofencingConstants.EXTRA_GEOFENCE_INDEX)) {
                viewModel.updateHint(extras.getInt(GeofencingConstants.EXTRA_GEOFENCE_INDEX))
                checkPermissionsAndStartGeofencing()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionResult")

        if (requestCode == REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE) {
            handlePermissionResult(permissions, grantResults)
        } else {
            Log.w(TAG, "Unexpected requestCode: $requestCode")
        }
    }

    private fun handlePermissionResult(permissions: Array<String>, grantResults: IntArray) {
        val locationGranted = checkPermissionGranted(permissions, grantResults, Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundLocationGranted = checkPermissionGranted(permissions, grantResults, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        if (locationGranted && backgroundLocationGranted) {
            checkDeviceLocationSettingsAndStartGeofence()
        } else {
            showPermissionDeniedSnackbar()
        }
    }

    private fun checkPermissionGranted(permissions: Array<String>, grantResults: IntArray, permission: String): Boolean {
        val permissionIndex = permissions.indexOf(permission)
        return permissionIndex != -1 &&
                permissionIndex < grantResults.size &&
                grantResults[permissionIndex] == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionDeniedSnackbar() {
        showSnackbar(
            binding.activityMapsMain,
            R.string.permission_denied_explanation,
            R.string.settings,
            ::openSettings
        )
    }

    /**
     * This will also destroy any saved state in the associated ViewModel, so we remove the
     * geofences here.
     */
    override fun onDestroy() {
        super.onDestroy()
        removeGeofences()
    }

    /**
     * Starts the permission check and Geofence process only if the Geofence associated with the
     * current hint isn't yet active.
     */
    private fun checkPermissionsAndStartGeofencing() {
        if (viewModel.geofenceIsActive()) return
        if (foregroundAndBackgroundLocationPermissionApproved()) {
            checkDeviceLocationSettingsAndStartGeofence()
        } else {
            requestForegroundAndBackgroundLocationPermissions()
        }
    }

    /*
	 *  Uses the Location Client to check the current state of location settings, and gives the user
	 *  the opportunity to turn on location services within our app.
	 */
    @Suppress("DEPRECATION")
    private fun checkDeviceLocationSettingsAndStartGeofence(resolve: Boolean = true) {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_LOW_POWER
        }
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)

        val settingsClient = LocationServices.getSettingsClient(this)
        val locationSettingsResponseTask =
            settingsClient.checkLocationSettings(builder.build())

        locationSettingsResponseTask.addOnFailureListener { exception ->
            if (exception is ResolvableApiException && resolve) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    exception.startResolutionForResult(
                        this@HuntMainActivity,
                        REQUEST_TURN_DEVICE_LOCATION_ON
                    )
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.d(TAG, "Error geting location settings resolution: " + sendEx.message)
                }
            } else {
                showSnackbar(
                    binding.activityMapsMain,
                    R.string.location_required_error,
                    android.R.string.ok,
                    ::checkDeviceLocationSettingsAndStartGeofence
                )

                /*
				Snackbar.make(
					binding.activityMapsMain,
					R.string.location_required_error, Snackbar.LENGTH_INDEFINITE
				).setAction(android.R.string.ok) {
					checkDeviceLocationSettingsAndStartGeofence()
				}.show()
				 */
            }
        }
        locationSettingsResponseTask.addOnCompleteListener {
            if (it.isSuccessful) {
                addGeofenceForClue()
            }
        }
    }

    /*
	 *  Determines whether the app has the appropriate permissions across Android 10+ and all other
	 *  Android versions.
	 */
    @TargetApi(29)
    private fun foregroundAndBackgroundLocationPermissionApproved(): Boolean {
        val foregroundLocationApproved = permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundPermissionApproved =
            if (runningQOrLater) {
                permissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                true
            }
        return foregroundLocationApproved && backgroundPermissionApproved
    }

    /*
	 *  Requests ACCESS_FINE_LOCATION and (on Android 10+ (Q) ACCESS_BACKGROUND_LOCATION.
	 */
    @TargetApi(29)
    private fun requestForegroundAndBackgroundLocationPermissions() {
        if (foregroundAndBackgroundLocationPermissionApproved())
            return

        // Else request the permission
        // this provides the result[LOCATION_PERMISSION_INDEX]
        var permissionsArray = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        val resultCode = when {
            runningQOrLater -> {
                // this provides the result[BACKGROUND_LOCATION_PERMISSION_INDEX]
                permissionsArray += Manifest.permission.ACCESS_BACKGROUND_LOCATION
                REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE
            }
            else -> REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
        }

        Log.d(TAG, "Request foreground only location permission")
        ActivityCompat.requestPermissions(this@HuntMainActivity, permissionsArray, resultCode)
    }

    /*
	 * Adds a Geofence for the current clue if needed, and removes any existing Geofence. This
	 * method should be called after the user has granted the location permission.  If there are
	 * no more geofences, we remove the geofence and let the viewmodel know that the ending hint
	 * is now "active."
	 */
    @SuppressLint("MissingPermission")
    private fun addGeofenceForClue() {
        if (viewModel.geofenceIsActive()) return
        val currentGeofenceIndex = viewModel.nextGeofenceIndex()
        if (currentGeofenceIndex >= GeofencingConstants.NUM_LANDMARKS) {
            removeGeofences()
            viewModel.geofenceActivated()
            return
        }
        val currentGeofenceData = GeofencingConstants.LANDMARK_DATA[currentGeofenceIndex]

        // Build the Geofence Object
        val geofence = buildGeofence(currentGeofenceData)
        /*
		val geofence = Geofence.Builder()
			// Set the request ID, string to identify the geofence.
			.setRequestId(currentGeofenceData.id)
			// Set the circular region of this geofence.
			.setCircularRegion(
				currentGeofenceData.latLong.latitude,
				currentGeofenceData.latLong.longitude,
				GeofencingConstants.GEOFENCE_RADIUS_IN_METERS
			)
			// Set the expiration duration of the geofence. This geofence gets
			// automatically removed after this period of time.
			.setExpirationDuration(GeofencingConstants.GEOFENCE_EXPIRATION_IN_MILLISECONDS)
			// Set the transition types of interest. Alerts are only generated for these
			// transition. We track entry and exit transitions in this sample.
			.setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
			.build()
		 */

        // Build the geofence request
        val geofencingRequest = GeofencingRequest.Builder()
            // The INITIAL_TRIGGER_ENTER flag indicates that geofencing service should trigger a
            // GEOFENCE_TRANSITION_ENTER notification when the geofence is added and if the device
            // is already inside that geofence.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            // Add the geofences to be monitored by geofencing service.
            .addGeofence(geofence)
            .build()

        // First, remove any existing geofences that use our pending intent
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            // Regardless of success/failure of the removal, add the new geofence
            addOnCompleteListener {
                // Add the new geofence request with the new geofence
                if (permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    accessFineLocation(geofencingRequest, geofence)
                }
            }
        }
    }

    /**
     * Removes geofences. This method should be called after the user has granted the location
     * permission.
     */
    private fun removeGeofences() {
        if (!foregroundAndBackgroundLocationPermissionApproved()) {
            return
        }
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnSuccessListener {
                // Geofences removed
                Log.d(TAG, getString(R.string.geofences_removed))
                showToast(R.string.geofences_added)
            }
            addOnFailureListener {
                // Failed to remove geofences
                Log.d(TAG, getString(R.string.geofences_not_removed))
            }
        }
    }

    private fun buildGeofence(currentGeofenceData: LandmarkDataObject) : Geofence {
        return Geofence.Builder()
            // Set the request ID, string to identify the geofence.
            .setRequestId(currentGeofenceData.id)
            // Set the circular region of this geofence.
            .setCircularRegion(
                currentGeofenceData.latLong.latitude,
                currentGeofenceData.latLong.longitude,
                GeofencingConstants.GEOFENCE_RADIUS_IN_METERS
            )
            // Set the expiration duration of the geofence. This geofence gets
            // automatically removed after this period of time.
            .setExpirationDuration(GeofencingConstants.GEOFENCE_EXPIRATION_IN_MILLISECONDS)
            // Set the transition types of interest. Alerts are only generated for these
            // transition. We track entry and exit transitions in this sample.
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun accessFineLocation(geofencingRequest: GeofencingRequest, geofence: Geofence) {
        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                // Geofences added.
                showToast(R.string.geofences_added)
                Log.e("Add Geofence", geofence.requestId)
                // Tell the viewmodel that we've reached the end of the game and
                // activated the last "geofence" --- by removing the Geofence.
                viewModel.geofenceActivated()
            }
            addOnFailureListener {
                // Failed to add geofences.
                showToast(R.string.geofences_not_added)
                if ((it.message != null)) {
                    Log.w(TAG, it.message!!)
                }
            }
        }
    }

    private fun permissionGranted(permission: String): Boolean {
        return (ActivityCompat.checkSelfPermission(this, permission
        ) == PackageManager.PERMISSION_GRANTED)
    }

    // Displays App settings screen.
    private fun openSettings() {
        startActivity(Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            val uri: Uri = Uri.fromParts("package", packageName, null)
            data = uri
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun showSnackbar(
        view: android.view.View,
        messageResId: Int,
        actionResId: Int,
        action: () -> Unit
    ) {
        Snackbar.make(view, messageResId, Snackbar.LENGTH_INDEFINITE)
            .setAction(actionResId) { action() }
            .show()
    }

    private fun showToast(message: Int) {
        Toast.makeText(
            this@HuntMainActivity, message, Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        internal const val ACTION_GEOFENCE_EVENT =
            "HuntMainActivity.treasureHunt.action.ACTION_GEOFENCE_EVENT"
    }
}

private const val REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE = 33
private const val REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE = 34
private const val REQUEST_TURN_DEVICE_LOCATION_ON = 29
private const val TAG = "HuntMainActivity"
private const val LOCATION_PERMISSION_INDEX = 0
private const val BACKGROUND_LOCATION_PERMISSION_INDEX = 1