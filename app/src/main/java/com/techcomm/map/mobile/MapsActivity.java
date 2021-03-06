package com.techcomm.map.mobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.places.Place;
import com.google.android.gms.location.places.Places;
import com.google.android.gms.location.places.ui.PlaceAutocompleteFragment;
import com.google.android.gms.location.places.ui.PlaceSelectionListener;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.clustering.ClusterManager;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.techcomm.map.mobile.data.EventData;
import com.techcomm.map.mobile.render.ClusterRenderer;
import com.techcomm.map.mobile.render.EventMarker;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.realm.Realm;

/**
 * The main activity for the Tech Comm on a Map app.
 * Displays a map, with markers indicating the community events and other items
 * of interest to technical communicators.
 */
public class MapsActivity extends AppCompatActivity implements
        OnMapReadyCallback, ConnectionCallbacks, OnConnectionFailedListener {

    private static final String KEY_CAMERA_POSITION = "camera_position";
    private static final String KEY_CURRENT_EVENT_ID = "current_event_id";
    private static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    private static final String TECHCOMM_MAP_URL = "http://sarahmaddox.github.io/techcomm-map/";
    private boolean locationPermissionGranted;

    // The entry point to Google Play services.
    protected GoogleApiClient mGoogleApiClient;

    // The geographical location where the device is currently located.
    protected Location mCurrentLocation;

    // Data passed via the intent.
    LatLng mIntentLocation;
    String mIntentZoom;
    private Uri mShareUrl;

    // A cluster manager for the event markers.
    private ClusterManager<EventMarker> mClusterManager;
    private ClickListener clickListener;

    // Realm is a mobile database - a replacement for SQLite. See realm.io.
    private Realm realm;

    private static final String TAG = MapsActivity.class.getSimpleName();

    // Variables to manage the behaviour of the map.
    private final Set<String> enabledTypes = new HashSet<>();
    private boolean initializedTypes = false;
    private boolean isZoomedIn = false;

    // An array of technical communication events and other items of interest.
    private final List<EventData> events = new ArrayList<>();

    // A panel that displays details of a selected event or other item on the map.
    private SlidingUpPanelLayout slidingLayout;

    private volatile GoogleMap map;
    @Nullable private Integer currentSelectedEventId;
    private Bundle mSavedInstanceState;

    /**
     * A listener for click events on marker clusters.
     * Markers on the map are grouped into clusters, for easier viewing when
     * there are many markers in a small geographical area.
     */
    class ClickListener implements ClusterManager.OnClusterItemClickListener<EventMarker> {
        @Override
        public boolean onClusterItemClick(EventMarker eventMarker) {
            if (realm == null) {
                Log.d(TAG, "Database not initialized at time of marker click");
                return false;
            }
            EventData event = realm
                    .where(EventData.class)
                    .equalTo("localId", eventMarker.getLocalId())
                    .findFirst();
            currentSelectedEventId = event.getLocalId();

            updateMarkers();
            updatePanelState();
            return true;
        }
    }

    /**
     * A listener for the event panel that slides up from the bottom of the screen,
     * displaying event information.
     */
    class SlideListener implements SlidingUpPanelLayout.PanelSlideListener {
        private LatLng previousLatLng;
        private float previousZoomLevel;

        @Override
        public void onPanelSlide(View view, float v) {
        }

        /**
         * Resets the zoom level and position of the camera on the map when the
         * sliding panel closes.
         */
        @Override
        public void onPanelCollapsed(View view) {
            if (previousLatLng == null) {
                // Panel has not been expanded yet. Return.
                return;
            }
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(previousLatLng, previousZoomLevel));
            map.setPadding(0, 0, 0, 0);
            map.getUiSettings().setAllGesturesEnabled(true);
            previousLatLng = null;
            isZoomedIn = false;
        }

        /**
         * Gets the event data and sets the zoom and camera position to focus on
         * the selected event when the sliding panel opens.
         */
        @Override
        public void onPanelExpanded(View view) {
            if (isZoomedIn) {
                // Already zoomed in. Return.
                return;
            }
            if (currentSelectedEventId == null) {
                Log.d(TAG, "Event ID was null when we expanded the panel");
                return;
            }
            EventData event = realm
                    .where(EventData.class)
                    .equalTo("localId", currentSelectedEventId)
                    .findFirst();
            if (event == null) {
                Log.d(TAG, "No focused event when we expanded the panel");
                return;
            }

            int padding = findViewById(R.id.map).getBottom() - view.getTop();
            Log.d(TAG, Float.toString(padding));
            previousZoomLevel = map.getCameraPosition().zoom;
            previousLatLng = map.getCameraPosition().target;
            map.getUiSettings().setAllGesturesEnabled(false);
            map.setPadding(0, findViewById(R.id.toolbar).getBottom(), 0, padding);
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(event.getLatitude(), event.getLongitude()),
                    15));
            isZoomedIn = true;
        }

        @Override
        public void onPanelAnchored(View view) {

        }

        @Override
        public void onPanelHidden(View view) {

        }
    }

    /**
     * Performs initialization tasks when the activity is created.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSavedInstanceState = savedInstanceState;

        if (mSavedInstanceState != null && mSavedInstanceState.containsKey(KEY_CURRENT_EVENT_ID)) {
            currentSelectedEventId = mSavedInstanceState.getInt(KEY_CURRENT_EVENT_ID);
        }

        // Build the Play services client for use by the Fused Location Provider.
        buildGoogleApiClient();

        // Fetch the data from disk.
        refreshEventsFromDatabase();

        // Fetch the data from the network in the background, then refresh.
        refreshData();

        // Retrieve the content view that renders the map.
        setContentView(R.layout.activity_maps);

        // Retrieve the autocomplete fragment from the layout, to display place predictions.
        PlaceAutocompleteFragment autocompleteFragment = (PlaceAutocompleteFragment)
                getFragmentManager().findFragmentById(R.id.place_autocomplete_fragment);

        // Register a listener that receives callbacks when the user selects a place from
        // the autocomplete search bar.
        autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener() {
            @Override
            public void onPlaceSelected(Place place) {
                // Close the place information panel, because it's showing the previous place.
                slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.HIDDEN);
                // Get the latitude and longitude of the selected place and pan the map
                // to that location.
                LatLng mLatLng = place.getLatLng();
                Log.d(TAG, "Place: " + place.getName() + " at location: " + place.getLatLng());
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(mLatLng, 14));
            }

            @Override
            public void onError(Status status) {
                Log.d(TAG, "An error occurred in Place Autocomplete: " + status);
            }
        });

        // Set up the panel that slides up from the bottom of the screen to show
        // information about a selected event.
        slidingLayout = (SlidingUpPanelLayout) findViewById(R.id.sliding_layout);
        SlideListener slideListener = new SlideListener();
        slidingLayout.setPanelSlideListener(slideListener);

        /**
         * When the user clicks the share button, creates an intent and sends it to
         * a chooser to that the user can choose how to share the event.
         */
        (findViewById(R.id.share_button)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Shorten the URL for the event that the user is sharing, then
                // share the short URL.
                new GetShortUrlTask().execute();
            }
        });

        // Add the toolbar at the top of the screen.
        android.support.v7.widget.Toolbar toolbar = (android.support.v7.widget.Toolbar)(findViewById(R.id.toolbar));
        setSupportActionBar(toolbar);

        mGoogleApiClient.connect();

        // Handle the case where the activity was called by an intent.
        // The intent data should contain the latitude and longitude for centering the map
        // and a zoom factor, formatted as URL parameters.
        Log.d(TAG, "Intent action: " + getIntent().getAction() + " Type: " + getIntent().getType() + " Scheme: " + getIntent().getScheme());
        Uri mUri = getIntent().getData();
        if (mUri != null && mUri.isHierarchical()) {
            mIntentLocation = new LatLng(Double.parseDouble(mUri.getQueryParameter("lat")),
                    Double.parseDouble(mUri.getQueryParameter("lng")));
            mIntentZoom = mUri.getQueryParameter("zoom");
            Log.d(TAG, "Intent URL found: " + mUri.toString());
        } else {
            if (mUri != null) {
                Log.d(TAG, "Intent URL is not hierarchical: " +  mUri.toString());
            } else {
                Log.d(TAG, "Intent URL is null.");
            }
        }
    }

    /**
     * Collapses the sliding panel when the user clicks the back button.
     */
    @Override
    public void onBackPressed() {
        if (isZoomedIn) {
            SlidingUpPanelLayout layout = (SlidingUpPanelLayout) findViewById(R.id.sliding_layout);
            layout.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Saves the state of the map and the selected event when the activity is paused.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentSelectedEventId != null) {
            outState.putInt(KEY_CURRENT_EVENT_ID, currentSelectedEventId);
        }
        if (map != null) {
            outState.putParcelable(KEY_CAMERA_POSITION, map.getCameraPosition());
            Log.d(TAG, "Saving position: " + map.getCameraPosition());
        }
    }

    /**
     * Builds a GoogleApiClient.
     * Uses the addApi() method to request the LocationServices API and the Google Places API.
     */
    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .addApi(Places.GEO_DATA_API)
                .build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            if (map != null) {
                map.setMyLocationEnabled(true);
            }
        }
        updateMarkers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mClusterManager != null) {
            mClusterManager.clearItems();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    /**
     * Runs when a GoogleApiClient object is successfully connected.
     */
    @Override
    public void onConnected(Bundle connectionHint) {
        // Request location permissions, so that we can get the location of the
        // device. The result of the permissions request is handled by a callback,
        // onRequestPermissionsResult.
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true;
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }
        // Get the best and most recent location of the device, which may be null
        // in rare cases when a location is not available.
        if (locationPermissionGranted) {
            mCurrentLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        }
        if (mCurrentLocation == null) {
            Log.d(TAG, "Current location null. Setting to default.");
        }

        // Build the map.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    /**
     * Handles failure to connect to the Google Play services client.
     */
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult result) {
        // Refer to the reference doc for ConnectionResult to see what error codes might
        // be returned in onConnectionFailed.
        Log.d(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + result.getErrorCode());
    }

    /**
     * Handles suspension of the connection to the Google Play services client.
     */
    @Override
    public void onConnectionSuspended(int cause) {
        // The connection to Google Play services was lost for some reason. We call connect() to
        // attempt to re-establish the connection.
        Log.d(TAG, "Connection suspended");
        mGoogleApiClient.connect();
    }

    /**
     * Runs when the map is successfully initialized.
     */
    @Override
    public void onMapReady(GoogleMap map) {
        this.map = map;
        // Turn off map toolbar, because it doesn't play nicely with marker clustering.
        map.getUiSettings().setMapToolbarEnabled(false);
        // Request location permissions, so that we can get the location of the
        // device. The result of the permissions request is handled by a callback,
        // onRequestPermissionsResult.
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true;
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }

        if (locationPermissionGranted) {
            map.setMyLocationEnabled(true);
        }
        // Manages groups of markers in clusters, for easier viewing when there are many
        // markers in a small geographical area.
        mClusterManager = new ClusterManager<EventMarker>(this, map);
        mClusterManager.setRenderer(new ClusterRenderer(
                getApplicationContext(),
                this.map,
                mClusterManager));
        map.setOnCameraIdleListener(mClusterManager);
        map.setOnMarkerClickListener(mClusterManager);
        clickListener = new ClickListener();
        mClusterManager.setOnClusterItemClickListener(clickListener);

        // Set the camera position. If the previous state was saved, set the position to
        // the saved state. Otherwise, if the app was opened via an intent, use the location and
        // zoom pass with the intent. Otherwise set the position to the device's current location.
        // If the current location is unknown, use a default position (Sydney, Australia) and zoom.
        if (mSavedInstanceState != null && mSavedInstanceState.containsKey(KEY_CAMERA_POSITION)) {
            CameraPosition cameraPosition = mSavedInstanceState
                    .getParcelable(KEY_CAMERA_POSITION);
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
        } else if (mIntentLocation != null && mIntentZoom != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(mIntentLocation,
                    Float.parseFloat(mIntentZoom)));
        } else if (mCurrentLocation != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(mCurrentLocation.getLatitude(),
                            mCurrentLocation.getLongitude()), 10));
        }
        else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(-34, 150), 10));
        }
        updateMarkers();
        updatePanelState();
    }

    /**
     * Handles the result of the request for location permissions.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        locationPermissionGranted = false;
        switch (requestCode) {
            case PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    locationPermissionGranted = true;
                }
            }
        }
    }

    /**
     * Initializes the options menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.actions, menu);
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Handles the selection of an item in the options menu.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_refresh:
                slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.HIDDEN);
                refreshData();
                return true;
            case R.id.action_settings:
                slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.HIDDEN);
                openLayersDialog();
                return true;
            case R.id.action_add_event:
                slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.HIDDEN);
                openEventForm();
                return true;
            case R.id.action_about:
                openAboutScreen();
                return true;
            default:
                return false;
        }
    }

    /**
     * Creates an intent to the input form for adding events to the map.
     */
    public void openEventForm() {
        Intent intent = new Intent(this, AddEventActivity.class);
        startActivity(intent);
    }

    /**
     * Displays the app's about screen.
     */
    public void openAboutScreen() {
        Intent intent = new Intent(this, AboutActivity.class);
        startActivity(intent);
    }
    
    /**
     * Displays a form allowing the user to select the types of events to be shown on the map.
     */
    public void openLayersDialog() {
        // Get the list of available event types.
        Set<String> layerTypeSet = new HashSet<>();
        for (EventData eventData : events) {
            layerTypeSet.add(eventData.getType());
        }
        final String[] layerTypes = layerTypeSet.toArray(new String[] {});
        // Display a check mark for the event types that are currently enabled for display.
        boolean[] checkedItems = new boolean[layerTypes.length];
        for (int i = 0; i < layerTypes.length; i++) {
            String layerType = layerTypes[i];
            checkedItems[i] = enabledTypes.contains(layerType);
        }

        // Enable or disable the event types for display, based on the user's selection.
        DialogInterface.OnMultiChoiceClickListener listener =
                new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                if (isChecked) {
                    enabledTypes.add(layerTypes[which]);
                } else {
                    enabledTypes.remove(layerTypes[which]);
                }
                updateMarkers();
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.layers_dialog)
                .setMultiChoiceItems(layerTypes, checkedItems, listener)
                .show();

    }

    public boolean refreshData(View view) {
        refreshData();
        return true;
    }

    /**
     * Calls methods to get the event data from the database and update the markers on the map.
     */
    private void refreshData() {
        new DataFetcherTask(this, new Runnable() {
            @Override
            public void run() {
                refreshEventsFromDatabase();
                updateMarkers();
            }
        }).execute();
    }

    /**
     * Gets the event data from the on-device database and builds the in-memory
     * array of community events.
     */
    private void refreshEventsFromDatabase() {
        realm = Realm.getInstance(this);
        events.clear();
        for (EventData event : realm.allObjects(EventData.class)) {
            events.add(cloneEventInMemory(event));
        }
    }

    /**
     * Creates a community event with all its data fields from the database.
     */
    private EventData cloneEventInMemory(EventData event) {
        EventData clone = new EventData();
        clone.setLocalId(event.getLocalId());
        clone.setType(event.getType());
        clone.setName(event.getName());
        clone.setDescription(event.getDescription());
        clone.setWebsite(event.getWebsite());
        clone.setStartDate(event.getStartDate());
        clone.setEndDate(event.getEndDate());
        clone.setAddress(event.getAddress());
        clone.setLatitude(event.getLatitude());
        clone.setLongitude(event.getLongitude());
        return clone;
    }

    /**
     * Updates the markers on the map based on the event data.
     */
    private void updateMarkers() {
        if (map == null || events.isEmpty()) {
            return;
        }

        if (!initializedTypes) {
            // Make all the possible types visible by default.
            for (EventData event : events) {
                enabledTypes.add(event.getType());
            }
            initializedTypes = true;
        }

        mClusterManager.clearItems();
        for (EventData event : events) {
            // Check whether this event type should be displayed.
            if (enabledTypes.contains(event.getType())) {
                boolean highlight = (currentSelectedEventId != null &&
                        currentSelectedEventId.intValue() == event.getLocalId());
                mClusterManager.addItem(new EventMarker(event, highlight));
            }
        }
        mClusterManager.cluster();
    }

    /**
     * Updates the fields on the sliding panel with data from the currently-selected event.
     */
    private void updatePanelState() {
        EventData currentEvent = null;
        if (currentSelectedEventId != null) {
            for (EventData event : events) {
                if (event.getLocalId() == currentSelectedEventId.intValue()) {
                    currentEvent = event;
                    break;
                }
            }
        }

        if (currentEvent != null) {
            ((TextView) findViewById(R.id.event_name)).setText(currentEvent.getName());
            ((TextView) findViewById(R.id.event_type)).setText(currentEvent.getType());
            ((TextView) findViewById(R.id.event_website)).setText(currentEvent.getWebsite());
            ((TextView) findViewById(R.id.event_description))
                    .setText(currentEvent.getDescription());
            ((TextView) findViewById(R.id.event_address)).setText(currentEvent.getAddress());
            ((TextView) findViewById(R.id.event_dates))
                    .setText(currentEvent.getStartDate() + " - " + currentEvent.getEndDate());
            // Build the URL for event sharing.
            mShareUrl = Uri.parse(TECHCOMM_MAP_URL + "?lat=" + currentEvent.getLatitude() +
                    "&lng=" + currentEvent.getLongitude() + "&zoom=14");
            slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        } else {
            slidingLayout.setPanelState(SlidingUpPanelLayout.PanelState.HIDDEN);
        }
    }
    /*
    * Gets a shortened URL, using the Google URL shortening API.
     */
    private class GetShortUrlTask extends AsyncTask<Void,Void,String> {

        @Override
        protected void onPostExecute(String response) {
            super.onPostExecute(response);
            Log.d(TAG, "Get short URL - JSON response:" + response);
            String shortUrl;
            try {
                JSONObject jsonObject = new JSONObject(response);
                shortUrl = jsonObject.getString("id");
                Log.d(TAG, "Short URL: " + shortUrl);
                // Create the intent and start the chooser.
                Log.d(TAG, "Creating intent to share event.");
                Intent sendIntent = new Intent();
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_TEXT, getResources().getText(R.string.share_text) +
                        " " + shortUrl);
                sendIntent.setType("text/plain");
                startActivity(Intent.createChooser(sendIntent,
                        getResources().getText(R.string.share_message)));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        protected String doInBackground(Void... params) {
            BufferedReader reader;
            StringBuffer buffer;
            String response = null;
            String json = "{\"longUrl\": \"" + mShareUrl.toString() + "\"}";
            try {
                // Build the URL for the shortening service and add the API key.
                URL url = new URL("https://www.googleapis.com/urlshortener/v1/url?key=" +
                        getResources().getText(R.string.google_webservices_key));
                // Call the shortening service.
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setReadTimeout(40000);
                con.setConnectTimeout(40000);
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "application/json");
                OutputStream os = con.getOutputStream();
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(os, "UTF-8"));

                writer.write(json);
                writer.flush();
                writer.close();
                os.close();

                int status=con.getResponseCode();
                InputStream inputStream;
                if(status == HttpURLConnection.HTTP_OK)
                    inputStream = con.getInputStream();
                else
                    inputStream = con.getErrorStream();

                reader = new BufferedReader(new InputStreamReader(inputStream));

                buffer = new StringBuffer();

                String line = "";
                while ((line = reader.readLine()) != null)
                {
                    buffer.append(line);
                }

                response = buffer.toString();

            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return response;

        }
    }
}
