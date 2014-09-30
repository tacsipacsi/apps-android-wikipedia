package org.wikipedia.nearby;

import org.wikipedia.PageTitle;
import org.wikipedia.R;
import org.wikipedia.Site;
import org.wikipedia.ThemedActionBarActivity;
import org.wikipedia.Utils;
import org.wikipedia.WikipediaApp;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.page.PageActivity;
import org.mediawiki.api.json.ApiException;
import com.squareup.picasso.Picasso;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Displays a list of nearby pages.
 */
public class NearbyActivity extends ThemedActionBarActivity implements SensorEventListener {
    public static final int ACTIVITY_RESULT_NEARBY_SELECT = 1;
    private static final String PREF_KEY_UNITS = "nearbyUnits";
    private static final String NEARBY_LAST_RESULT = "lastRes";
    private static final String NEARBY_LAST_LOCATION = "lastLoc";
    private static final String NEARBY_NEXT_LOCATION = "curLoc";
    private static final int MIN_TIME_MILLIS = 5000;
    private static final int MIN_DISTANCE_METERS = 2;
    private static final int ONE_THOUSAND = 1000;
    private static final double ONE_THOUSAND_D = 1000.0d;
    private static final double METER_TO_FEET = 3.280839895;
    private static final int ONE_MILE = 5280;

    private ListView nearbyList;
    private View nearbyLoadingContainer;
    private View nearbyEmptyContainer;
    private NearbyAdapter adapter;

    private WikipediaApp app;
    private Site site;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean refreshing;
    private Location lastLocation;
    private Location nextLocation;
    private NearbyResult lastResult;

    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private Sensor mMagnetometer;

    //this holds the actual data from the accelerometer and magnetometer, and automatically
    //maintains a moving average (low-pass filter) to reduce jitter.
    private MovingAverageArray accelData;
    private MovingAverageArray magneticData;

    //The size with which we'll initialize our low-pass filters. This size seems like
    //a good balance between effectively removing jitter, and good response speed.
    //(Mimics a physical compass needle)
    private static final int MOVING_AVERAGE_SIZE = 8;

    //geomagnetic field data, to be updated whenever we update location.
    //(will provide us with declination from true north)
    private GeomagneticField geomagneticField;

    //we'll maintain a list of CompassViews that are currently being displayed, and update them
    //whenever we receive updates from sensors.
    private List<NearbyCompassView> compassViews;

    //whether to display distances in imperial units (feet/miles) instead of metric
    private boolean showImperial = false;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (WikipediaApp) getApplicationContext();
        site = app.getPrimarySite();

        setContentView(R.layout.activity_nearby);
        nearbyList = (ListView) findViewById(R.id.nearby_list);
        nearbyLoadingContainer = findViewById(R.id.nearby_loading_container);
        nearbyEmptyContainer = findViewById(R.id.nearby_empty_container);

        nearbyEmptyContainer.setVisibility(View.GONE);

        adapter = new NearbyAdapter(this, new ArrayList<NearbyPage>());
        nearbyList.setAdapter(adapter);
        nearbyList.setEmptyView(nearbyLoadingContainer);

        nearbyList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                NearbyPage nearbyPage = adapter.getItem(position);
                PageTitle title = new PageTitle(nearbyPage.getTitle(), site, nearbyPage.getThumblUrl());
                HistoryEntry newEntry = new HistoryEntry(title, HistoryEntry.SOURCE_NEARBY);

                Intent intent = new Intent();
                intent.setClass(NearbyActivity.this, PageActivity.class);
                intent.setAction(PageActivity.ACTION_PAGE_FOR_TITLE);
                intent.putExtra(PageActivity.EXTRA_PAGETITLE, title);
                intent.putExtra(PageActivity.EXTRA_HISTORYENTRY, newEntry);
                setResult(ACTIVITY_RESULT_NEARBY_SELECT, intent);
                finish();
            }
        });

        // Acquire a reference to the system Location Manager
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        // Define a listener that responds to location updates
        locationListener = new LocationListener() {
            public void onLocationChanged(Location location) {
                // Called when a new location is found by the network location provider.
                makeUseOfNewLocation(location);
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
                Log.d("Wikipedia", "onStatusChanged " + provider);
            }

            public void onProviderEnabled(String provider) {
                Log.d("Wikipedia", "onProviderEnabled " + provider);
            }

            public void onProviderDisabled(String provider) {
                Log.d("Wikipedia", "onProviderDisabled " + provider);
            }
        };

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        compassViews = new ArrayList<NearbyCompassView>();

        if (savedInstanceState != null) {
            lastLocation = savedInstanceState.getParcelable(NEARBY_LAST_LOCATION);
            nextLocation = savedInstanceState.getParcelable(NEARBY_NEXT_LOCATION);
            lastResult = savedInstanceState.getParcelable(NEARBY_LAST_RESULT);
            setupGeomagneticField();
            showNearbyPages(lastResult);
        } else {
            setRefreshingState(true);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //do we already have a preference for metric/imperial units?
        if (prefs.contains(PREF_KEY_UNITS)) {
            setImperialUnits(prefs.getBoolean(PREF_KEY_UNITS, false));
        } else {
            //if our locale is set to US, then use imperial units by default.
            if (Locale.getDefault().getISO3Country().equalsIgnoreCase(Locale.US.getISO3Country())) {
                setImperialUnits(true);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (lastResult != null) {
            outState.putParcelable(NEARBY_LAST_LOCATION, lastLocation);
            outState.putParcelable(NEARBY_NEXT_LOCATION, nextLocation);
            outState.putParcelable(NEARBY_LAST_RESULT, lastResult);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, mMagnetometer, SensorManager.SENSOR_DELAY_UI);
        requestLocationUpdates();
    }

    @Override
    public void onPause() {
        stopLocationUpdates();
        mSensorManager.unregisterListener(this);
        compassViews.clear();
        super.onPause();
    }

    private void stopLocationUpdates() {
        setRefreshingState(false);
        locationManager.removeUpdates(locationListener);
    }

    private void requestLocationUpdates() {
        boolean atLeastOneEnabled = false;
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            requestLocation(LocationManager.NETWORK_PROVIDER);
            atLeastOneEnabled = true;
        }
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            requestLocation(LocationManager.GPS_PROVIDER);
            atLeastOneEnabled = true;
        }
        // if neither of the location providers are enabled, then give the user the option
        // to go to Settings, so that they enable Location in the actual OS.
        if (!atLeastOneEnabled) {
            showDialogForSettings();
        }
    }

    private void requestLocation(String provider) {
        locationManager.requestLocationUpdates(provider, MIN_TIME_MILLIS, MIN_DISTANCE_METERS, locationListener);
    }

    private void showDialogForSettings() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setMessage(R.string.nearby_dialog_goto_settings);
        alert.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                Intent myIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(myIntent);
            }
        });
        alert.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        });
        alert.setCancelable(false);
        AlertDialog ad = alert.create();
        ad.show();
    }

    private void makeUseOfNewLocation(Location location) {
        if (!isBetterLocation(location, lastLocation)) {
            return;
        }
        nextLocation = location;
        setupGeomagneticField();
        if (lastLocation == null || (refreshing && getDistance(lastLocation) >= MIN_DISTANCE_METERS)) {

            new NearbyFetchTask(NearbyActivity.this, site, location) {
                @Override
                public void onFinish(NearbyResult result) {
                    lastResult = result;
                    showNearbyPages(result);
                }

                @Override
                public void onCatch(Throwable caught) {
                    if (caught instanceof ApiException && caught.getCause() instanceof UnknownHostException) {
                        Crouton.makeText(NearbyActivity.this, R.string.nearby_no_network, Style.ALERT).show();
                    } else if (caught instanceof NearbyFetchException) {
                        Log.e("Wikipedia", "Could not get list of nearby places: " + caught.toString());
                        Crouton.makeText(NearbyActivity.this, R.string.nearby_server_error, Style.ALERT).show();
                    } else {
                        super.onCatch(caught);
                    }
                    setRefreshingState(false);
                }
            }.execute();
        } else {
            updateDistances();
        }
    }

    /** Updates geomagnetic field data, to give us our precise declination from true north. */
    private void setupGeomagneticField() {
        geomagneticField = new GeomagneticField((float)nextLocation.getLatitude(), (float)nextLocation.getLongitude(), 0, (new Date()).getTime());
    }

    /** Determines whether one Location reading is better than the current Location fix.
     * lifted from http://developer.android.com/guide/topics/location/strategies.html
     * @param location  The new Location that you want to evaluate
     * @param currentBestLocation  The current Location fix, to which you want to compare the new one
     */
    protected boolean isBetterLocation(Location location, Location currentBestLocation) {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }

        // Check whether the new location fix is newer or older
        final int twoMinutes = 1000 * 60 * 2;
        final int accuracyThreshold = 200;
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > twoMinutes;
        boolean isSignificantlyOlder = timeDelta < -twoMinutes;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
            // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > accuracyThreshold;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                                                    currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    /** Checks whether two providers are the same */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    private void showNearbyPages(NearbyResult result) {
        nearbyList.setEmptyView(nearbyEmptyContainer);
        lastLocation = nextLocation;
        sortByDistance(result.getList());
        adapter.clear();
        addResultsToAdapter(result.getList());
        compassViews.clear();

        setRefreshingState(false);
    }

    private void addResultsToAdapter(List<NearbyPage> result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            adapter.addAll(result);
        } else {
            for (NearbyPage page : result) {
                adapter.add(page);
            }
        }
    }

    private void setRefreshingState(boolean newState) {
        refreshing = newState;
        if (refreshing) {
            nearbyLoadingContainer.setVisibility(View.VISIBLE);
        } else {
            nearbyLoadingContainer.setVisibility(View.GONE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            invalidateOptionsMenu();
        }
    }

    private void sortByDistance(List<NearbyPage> nearbyPages) {
        Collections.sort(nearbyPages, new Comparator<NearbyPage>() {
            public int compare(NearbyPage a, NearbyPage b) {
                if (a.getLocation() == null) {
                    if (b.getLocation() == null) {
                        return 0;
                    } else {
                        return 1;
                    }
                } else if (b.getLocation() == null) {
                    return -1;
                } else {
                    return getDistance(a.getLocation()) - getDistance(b.getLocation());
                }
            }
        });
    }

    private int getDistance(Location otherLocation) {
        return (int) nextLocation.distanceTo(otherLocation);
    }

    private String getDistanceLabel(Location otherLocation) {
        final int meters = getDistance(otherLocation);
        if (showImperial) {
            final double feet = meters * METER_TO_FEET;
            if (feet < ONE_THOUSAND) {
                return getString(R.string.nearby_distance_in_feet, (int)feet);
            } else {
                return getString(R.string.nearby_distance_in_miles, feet / ONE_MILE);
            }
        } else {
            if (meters < ONE_THOUSAND) {
                return getString(R.string.nearby_distance_in_meters, meters);
            } else {
                return getString(R.string.nearby_distance_in_kilometers, meters / ONE_THOUSAND_D);
            }
        }
    }

    private void updateDistances() {
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_nearby, menu);
        app.adjustDrawableToTheme(menu.findItem(R.id.menu_refresh_nearby).getIcon());
        menu.findItem(R.id.menu_metric_imperial).setTitle(showImperial
                ? getString(R.string.nearby_set_metric)
                : getString(R.string.nearby_set_imperial));
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_refresh_nearby).setEnabled(!refreshing);
        menu.findItem(R.id.menu_metric_imperial).setTitle(showImperial
                ? getString(R.string.nearby_set_metric)
                : getString(R.string.nearby_set_imperial));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case R.id.menu_refresh_nearby:
                setRefreshingState(true);
                requestLocationUpdates();
                return true;
            case R.id.menu_metric_imperial:
                setImperialUnits(!showImperial);
                adapter.notifyDataSetInvalidated();
                return true;
            default:
                throw new RuntimeException("Unknown menu item clicked!");
        }
    }

    private void setImperialUnits(boolean imperial) {
        showImperial = imperial;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean(PREF_KEY_UNITS, showImperial).commit();
        this.supportInvalidateOptionsMenu();
    }


    private class NearbyAdapter extends ArrayAdapter<NearbyPage> {
        private static final int LAYOUT_ID = R.layout.item_nearby_entry;

        public NearbyAdapter(Context context, ArrayList<NearbyPage> pages) {
            super(context, LAYOUT_ID, pages);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            NearbyPage nearbyPage = getItem(position);
            ViewHolder viewHolder;
            if (convertView == null) {
                viewHolder = new ViewHolder();
                LayoutInflater inflater = LayoutInflater.from(getContext());
                convertView = inflater.inflate(LAYOUT_ID, parent, false);
                viewHolder.thumbnail = (NearbyCompassView) convertView.findViewById(R.id.nearby_thumbnail);
                viewHolder.title = (TextView) convertView.findViewById(R.id.nearby_title);
                viewHolder.distance = (TextView) convertView.findViewById(R.id.nearby_distance);
                convertView.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) convertView.getTag();
            }
            viewHolder.title.setText(nearbyPage.getTitle());

            if (nearbyPage.getLocation() != null) {
                // set the calculated angle as the base angle for our compass view
                viewHolder.thumbnail.setAngle((float) calculateAngle(nearbyPage.getLocation()));
                viewHolder.thumbnail.setMaskColor(getResources().getColor(Utils.getThemedAttributeId(NearbyActivity.this, R.attr.page_background_color)));
                viewHolder.thumbnail.setTickColor(getResources().getColor(R.color.button_light));
                if (!compassViews.contains(viewHolder.thumbnail)) {
                    compassViews.add(viewHolder.thumbnail);
                }

                viewHolder.distance.setText(getDistanceLabel(nearbyPage.getLocation()));
                viewHolder.distance.setVisibility(View.VISIBLE);
                viewHolder.thumbnail.setEnabled(true);
            } else {
                // Strangely, we don't know the full coordinates of this nearby place.
                // Something in the DB must have gotten out of sync; may happen intermittently.
                viewHolder.distance.setVisibility(View.INVISIBLE); // don't affect the layout measurements
                viewHolder.thumbnail.setEnabled(false);
            }

            Picasso.with(NearbyActivity.this)
                    .load(nearbyPage.getThumblUrl())
                    .placeholder(R.drawable.ic_pageimage_placeholder)
                    .error(R.drawable.ic_pageimage_placeholder)
                    .into(viewHolder.thumbnail);
            return convertView;
        }

        private double calculateAngle(Location otherLocation) {
            // simplified angle between two vectors...
            // vector pointing towards north from our location = [0, 1]
            // vector pointing towards destination from our location = [a1, a2]
            double a1 = otherLocation.getLongitude() - nextLocation.getLongitude();
            double a2 = otherLocation.getLatitude() - nextLocation.getLatitude();
            // cos θ = (v1*a1 + v2*a2) / (√(v1²+v2²) * √(a1²+a2²))
            double angle = Math.toDegrees(Math.acos(a2 / Math.sqrt(a1 * a1 + a2 * a2)));
            // since the acos function only goes between 0 to 180 degrees, we'll manually
            // negate the angle if the destination's longitude is on the opposite side.
            if (a1 < 0f) {
                angle = -angle;
            }
            return angle;
        }


        private class ViewHolder {
            private NearbyCompassView thumbnail;
            private TextView title;
            private TextView distance;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) { }

    @Override
    public void onSensorChanged(SensorEvent event) {
        //acquire raw data from sensors...
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (accelData == null) {
                accelData = new MovingAverageArray(event.values.length, MOVING_AVERAGE_SIZE);
            }
            accelData.addData(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            if (magneticData == null) {
                magneticData = new MovingAverageArray(event.values.length, MOVING_AVERAGE_SIZE);
            }
            magneticData.addData(event.values);
        }
        if (accelData == null || magneticData == null) {
            return;
        }

        final int matrixSize = 9;
        final int orientationSize = 3;
        final int quarterTurn = 90;
        float[] mR = new float[matrixSize];
        //get the device's rotation matrix with respect to world coordinates, based on the sensor data
        if (!SensorManager.getRotationMatrix(mR, null, accelData.getData(), magneticData.getData())) {
            Log.e("NearbyActivity", "getRotationMatrix failed.");
            return;
        }

        //get device's orientation with respect to world coordinates, based on the
        //rotation matrix acquired above.
        float[] orientation = new float[orientationSize];
        SensorManager.getOrientation(mR, orientation);
        // orientation[0] = azimuth
        // orientation[1] = pitch
        // orientation[2] = roll
        float azimuth = (float) Math.toDegrees(orientation[0]);

        //adjust for declination from magnetic north...
        float declination = 0f;
        if (geomagneticField != null) {
            declination = geomagneticField.getDeclination();
        }
        azimuth += declination;

        //adjust for device screen rotation
        int rotation = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                azimuth += quarterTurn;
                break;
            case Surface.ROTATION_180:
                azimuth += quarterTurn * 2;
                break;
            case Surface.ROTATION_270:
                azimuth -= quarterTurn;
                break;
            default:
                break;
        }

        //update views!
        for (NearbyCompassView view : compassViews) {
            view.setAzimuth(-azimuth);
        }
    }
}
