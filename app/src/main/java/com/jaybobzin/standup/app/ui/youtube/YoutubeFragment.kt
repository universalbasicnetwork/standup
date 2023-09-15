package com.jaybobzin.standup.app.ui.youtube

import android.Manifest
import android.accounts.AccountManager
import android.app.ProgressDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.os.AsyncTask
import android.os.Bundle
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.BundleCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.google.api.services.youtube.model.Channel
import com.jaybobzin.standup.app.MainActivity
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import java.io.IOException
import java.util.Arrays

const val REQUEST_ACCOUNT_PICKER = 1000
const val REQUEST_AUTHORIZATION = 1001
const val REQUEST_GOOGLE_PLAY_SERVICES = 1002
const val REQUEST_PERMISSION_GET_ACCOUNTS = 1003

class YoutubeFragment : Fragment() {

    private val BUTTON_TEXT = "Call YouTube Data API"
    private val PREF_ACCOUNT_NAME = "accountName"
    private val SCOPES = arrayOf(YouTubeScopes.YOUTUBE_READONLY)

    var mCredential: GoogleAccountCredential? = null


    private var mOutputText: TextView? = null
    private var mCallApiButton: Button? = null
    var mProgress: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize credentials and service object.
        // Initialize credentials and service object.
        mCredential = GoogleAccountCredential.usingOAuth2(
            context, SCOPES.toList()
        )
            .setBackOff(ExponentialBackOff())
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val activityLayout = LinearLayout(context)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        activityLayout.layoutParams = lp
        activityLayout.orientation = LinearLayout.VERTICAL
        activityLayout.setPadding(16, 16, 16, 16)

        val tlp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val mCallApiButton = Button(context)
        mCallApiButton.setText(BUTTON_TEXT)
        mCallApiButton.setOnClickListener(View.OnClickListener {
            this.mCallApiButton?.setEnabled(false)
            this.mOutputText?.setText("")
            getResultsFromApi()
            this.mCallApiButton?.setEnabled(true)
        })
        activityLayout.addView(mCallApiButton)
        this.mCallApiButton = mCallApiButton

        val mOutputText = TextView(context)
        mOutputText.setLayoutParams(tlp)
        mOutputText.setPadding(16, 16, 16, 16)
        mOutputText.setVerticalScrollBarEnabled(true)
        mOutputText.setMovementMethod(ScrollingMovementMethod())
        mOutputText.setText(
            "Click the \'$BUTTON_TEXT\' button to test the API."
        )
        this.mOutputText = mOutputText
        activityLayout.addView(mOutputText)

        val mProgress = ProgressDialog(context)
        mProgress.setMessage("Calling YouTube Data API ...")
        this.mProgress = mProgress

        return activityLayout


    }

    /**
     * Attempt to call the API, after verifying that all the preconditions are
     * satisfied. The preconditions are: Google Play Services installed, an
     * account was selected and the device currently has online access. If any
     * of the preconditions are not satisfied, the app will prompt the user as
     * appropriate.
     */
    private fun getResultsFromApi() {
        val cred = mCredential
        if (!isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices()
        } else if (cred == null || cred.selectedAccountName == null) {
            chooseAccount()
        } else if (!isDeviceOnline()) {
            mOutputText?.setText("No network connection available.")
        } else {
            MakeRequestTask(cred, this).execute()
        }
    }

    /**
     * Attempts to set the account used with the API credentials. If an account
     * name was previously saved it will use that one; otherwise an account
     * picker dialog will be shown to the user. Note that the setting the
     * account to use with the credentials object requires the app to have the
     * GET_ACCOUNTS permission, which is requested here if it is not already
     * present. The AfterPermissionGranted annotation indicates that this
     * function will be rerun automatically whenever the GET_ACCOUNTS permission
     * is granted.
     */
    @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
    private fun chooseAccount() {
        if (EasyPermissions.hasPermissions(
                requireContext(),
                Manifest.permission.GET_ACCOUNTS
            )
        ) {
            val accountName = context?.getSharedPreferences("prefs",AppCompatActivity.MODE_PRIVATE)
                ?.getString(PREF_ACCOUNT_NAME, null)
            if (accountName != null) {
                mCredential!!.selectedAccountName = accountName
                getResultsFromApi()
            } else {
                // Start a dialog from which the user can choose an account
                startActivityForResult(
                    mCredential!!.newChooseAccountIntent(),
                    REQUEST_ACCOUNT_PICKER
                )
            }
        } else {
            // Request the GET_ACCOUNTS permission via a user dialog
            EasyPermissions.requestPermissions(
                this,
                "This app needs to access your Google account (via Contacts).",
                REQUEST_PERMISSION_GET_ACCOUNTS,
                Manifest.permission.GET_ACCOUNTS
            )
        }
    }

    /**
     * Called when an activity launched here (specifically, AccountPicker
     * and authorization) exits, giving you the requestCode you started it with,
     * the resultCode it returned, and any additional data from it.
     * @param requestCode code indicating which activity result is incoming.
     * @param resultCode code indicating the result of the incoming
     * activity result.
     * @param data Intent (containing result data) returned by incoming
     * activity result.
     */
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_GOOGLE_PLAY_SERVICES -> if (resultCode != AppCompatActivity.RESULT_OK) {
                mOutputText?.setText(
                    "This app requires Google Play Services. Please install " +
                            "Google Play Services on your device and relaunch this app."
                )
            } else {
                getResultsFromApi()
            }

            REQUEST_ACCOUNT_PICKER -> if (resultCode == AppCompatActivity.RESULT_OK && data != null && data.extras != null) {
                val accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (accountName != null) {
                    val settings = requireContext().getSharedPreferences("prefs",AppCompatActivity.MODE_PRIVATE)
                    val editor = settings.edit()
                    editor.putString(PREF_ACCOUNT_NAME, accountName)
                    editor.apply()
                    mCredential!!.selectedAccountName = accountName
                    getResultsFromApi()
                }
            }

            REQUEST_AUTHORIZATION -> if (resultCode == AppCompatActivity.RESULT_OK) {
                getResultsFromApi()
            }
        }
    }

    /**
     * Respond to requests for permissions at runtime for API 23 and above.
     * @param requestCode The request code passed in
     *     requestPermissions(android.app.Activity, String, int, String[])
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *     which is either PERMISSION_GRANTED or PERMISSION_DENIED. Never null.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }


    /**
     * Checks whether the device currently has a network connection.
     * @return true if the device has a network connection, false otherwise.
     */
    private fun isDeviceOnline(): Boolean {
        val connMgr = context?.getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkInfo = connMgr?.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }

    /**
     * Check that Google Play services APK is installed and up to date.
     * @return true if Google Play Services is available and up to
     * date on this device; false otherwise.
     */
    private fun isGooglePlayServicesAvailable(): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(requireContext())
        return connectionStatusCode == ConnectionResult.SUCCESS
    }

    /**
     * Attempt to resolve a missing, out-of-date, invalid or disabled Google
     * Play Services installation via a user dialog, if possible.
     */
    private fun acquireGooglePlayServices() {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(requireContext())
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode)
        }
    }


    /**
     * Display an error dialog showing that Google Play Services is missing
     * or out of date.
     * @param connectionStatusCode code describing the presence (or lack of)
     * Google Play Services on this device.
     */
    fun showGooglePlayServicesAvailabilityErrorDialog(
        connectionStatusCode: Int
    ) {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val dialog = apiAvailability.getErrorDialog(
            this@YoutubeFragment,
            connectionStatusCode,
            REQUEST_GOOGLE_PLAY_SERVICES
        )
        dialog!!.show()
    }

    /**
     * An asynchronous task that handles the YouTube Data API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private class MakeRequestTask constructor(credential: GoogleAccountCredential?, val fragment: YoutubeFragment) :
        AsyncTask<Void?, Void?, List<String?>?>() {
        private var mService: YouTube? = null
        private var mLastError: Exception? = null

        init {
            val transport = AndroidHttp.newCompatibleTransport()
            val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
            mService = YouTube.Builder(
                transport, jsonFactory, credential
            )
                .setApplicationName("YouTube Data API Android Quickstart")
                .build()
        }

        /**
         * Background task to call YouTube Data API.
         * @param params no parameters needed for this task.
         */
        override fun doInBackground(vararg p0: Void?): List<String?>? {
            try {
                return dataFromApi
            } catch (e: Exception) {
                mLastError = e
                cancel(true)
                return null
            }
        }

        @get:Throws(IOException::class)
        private val dataFromApi: List<String?>
            /**
             * Fetch information about the "GoogleDevelopers" YouTube channel.
             * @return List of Strings containing information about the channel.
             * @throws IOException
             */
            private get() {
                // Get a list of up to 10 files.
                val channelInfo: MutableList<String?> = ArrayList()
                val result = mService!!.channels().list("snippet,contentDetails,statistics")
                    .setForUsername("GoogleDevelopers")
                    .execute()
                val channels: List<Channel>? = result.items
                if (channels != null) {
                    val channel: Channel = channels[0]
                    channelInfo.add(
                        ((("This channel's ID is " + channel.getId()).toString() + ". " +
                                "Its title is '" + channel.getSnippet()
                            .getTitle()).toString() + ", " +
                                "and it has " + channel.getStatistics()
                            .getViewCount()).toString() + " views."
                    )
                }
                return channelInfo
            }


        override fun onPreExecute() {
            fragment.mOutputText?.setText("")
            fragment.mProgress?.show()
        }

        override fun onPostExecute(output: List<String?>?) {
            fragment?.mProgress?.hide()
            if (output == null || output.size == 0) {
                fragment?.mOutputText?.setText("No results returned.")
            } else {
                val mutable = output.toMutableList()
                mutable.add(0, "Data retrieved using the YouTube Data API:")
                fragment?.mOutputText?.setText(TextUtils.join("\n", mutable))
            }
        }

        override fun onCancelled() {
            fragment?.mProgress?.hide()
            if (mLastError != null) {
                if (mLastError is GooglePlayServicesAvailabilityIOException) {
                    fragment?.showGooglePlayServicesAvailabilityErrorDialog(
                        (mLastError as GooglePlayServicesAvailabilityIOException)
                            .connectionStatusCode
                    )
                } else if (mLastError is UserRecoverableAuthIOException) {
                    ActivityCompat.startActivityForResult(fragment.requireActivity(),
                        (mLastError as UserRecoverableAuthIOException).intent,
                        REQUEST_AUTHORIZATION, Bundle.EMPTY
                    )
                } else {
                    fragment.mOutputText?.setText(
                        ("The following error occurred:\n"
                                + mLastError!!.message)
                    )
                }
            } else {
                fragment?.mOutputText?.setText("Request cancelled.")
            }
        }
    }
}