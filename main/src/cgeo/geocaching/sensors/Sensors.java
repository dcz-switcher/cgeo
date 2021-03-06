package cgeo.geocaching.sensors;

import cgeo.geocaching.CgeoApplication;
import cgeo.geocaching.playservices.LocationProvider;
import cgeo.geocaching.sensors.GnssStatusProvider.Status;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.utils.AngleUtils;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.RxUtils;

import android.app.Application;
import android.content.Context;
import android.support.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Observable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;

public class Sensors {

    private Observable<GeoData> geoDataObservable;
    private Observable<GeoData> geoDataObservableLowPower;
    private Observable<Float> directionObservable;
    private final Observable<Status> gpsStatusObservable;
    @NonNull private volatile GeoData currentGeo = GeoData.DUMMY_LOCATION;
    private volatile float currentDirection = 0.0f;
    private final boolean hasCompassCapabilities;

    private static class InstanceHolder {
        static final Sensors INSTANCE = new Sensors();
    }

    private final Consumer<GeoData> rememberGeodataAction = new Consumer<GeoData>() {
        @Override
        public void accept(final GeoData geoData) {
            currentGeo = geoData;
        }
    };

    private final Consumer<Float> onNextrememberDirectionAction = new Consumer<Float>() {
        @Override
        public void accept(final Float direction) {
            currentDirection = direction;
        }
    };

    private Sensors() {
        final Application application = CgeoApplication.getInstance();
        gpsStatusObservable = GnssStatusProvider.create(application).replay(1).refCount();
        final Context context = application.getApplicationContext();
        hasCompassCapabilities = RotationProvider.hasRotationSensor(context) ||
                OrientationProvider.hasOrientationSensor(context) ||
                MagnetometerAndAccelerometerProvider.hasMagnetometerAndAccelerometerSensors(context);
    }

    public static Sensors getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private final Function<Throwable, Observable<GeoData>> fallbackToGeodataProvider = new Function<Throwable, Observable<GeoData>>() {
        @Override
        public Observable<GeoData> apply(final Throwable throwable) {
            Log.e("Cannot use Play Services location provider, falling back to GeoDataProvider", throwable);
            Settings.setUseGooglePlayServices(false);
            return GeoDataProvider.create(CgeoApplication.getInstance());
        }
    };

    public void setupGeoDataObservables(final boolean useGooglePlayServices, final boolean useLowPowerLocation) {
        if (geoDataObservable != null) {
            return;
        }
        final Application application = CgeoApplication.getInstance();
        if (useGooglePlayServices) {
            geoDataObservable = LocationProvider.getMostPrecise(application).onErrorResumeNext(fallbackToGeodataProvider).doOnNext(rememberGeodataAction);
            if (useLowPowerLocation) {
                geoDataObservableLowPower = LocationProvider.getLowPower(application).doOnNext(rememberGeodataAction).onErrorResumeNext(geoDataObservable);
            } else {
                geoDataObservableLowPower = geoDataObservable;
            }
        } else {
            geoDataObservable = RxUtils.rememberLast(GeoDataProvider.create(application).doOnNext(rememberGeodataAction), null);
            geoDataObservableLowPower = geoDataObservable;
        }
    }

    private static final Function<GeoData, Float> GPS_TO_DIRECTION = new Function<GeoData, Float>() {
        @Override
        public Float apply(final GeoData geoData) {
            return AngleUtils.reverseDirectionNow(geoData.getBearing());
        }
    };

    public void setupDirectionObservable() {
        if (directionObservable != null) {
            return;
        }
        // If we have no magnetic sensor, there is no point in trying to setup any, we will always get the direction from the GPS.
        if (!hasCompassCapabilities) {
            Log.i("No compass capabilities, using only the GPS for the orientation");
            directionObservable = RxUtils.rememberLast(geoDataObservableLowPower.map(GPS_TO_DIRECTION).doOnNext(onNextrememberDirectionAction), 0f);
            return;
        }

        // Combine the magnetic direction observable with the GPS when compass is disabled or speed is high enough.
        final AtomicBoolean useDirectionFromGps = new AtomicBoolean(false);

        // On some devices, the orientation sensor (Xperia and S4 running Lollipop) seems to have been deprecated for real.
        // Use the rotation sensor if it is available unless the orientatation sensor is forced by the user.
        // After updating Moto G there is no rotation sensor anymore. Use magnetic field and accelerometer instead.
        final Observable<Float> sensorDirectionObservable;
        final Application application = CgeoApplication.getInstance();
        if (Settings.useOrientationSensor(application)) {
            sensorDirectionObservable = OrientationProvider.create(application);
        } else if (RotationProvider.hasRotationSensor(application)) {
            sensorDirectionObservable = RotationProvider.create(application);
        } else {
            sensorDirectionObservable = MagnetometerAndAccelerometerProvider.create(application);
        }

        final Observable<Float> magneticDirectionObservable = sensorDirectionObservable.onErrorResumeNext(new Function<Throwable, Observable<Float>>() {
            @Override
            public Observable<Float> apply(final Throwable throwable) {
                Log.e("Device orientation is not available due to sensors error, disabling compass", throwable);
                Settings.setUseCompass(false);
                return Observable.<Float>never().startWith(0.0f);
            }
        }).filter(new Predicate<Float>() {
            @Override
            public boolean test(final Float aFloat) {
                return Settings.isUseCompass() && !useDirectionFromGps.get();
            }
        });

        final Observable<Float> directionFromGpsObservable = geoDataObservableLowPower.filter(new Predicate<GeoData>() {
            @Override
            public boolean test(final GeoData geoData) {
                final boolean useGps = geoData.getSpeed() > 5.0f;
                useDirectionFromGps.set(useGps);
                return useGps || !Settings.isUseCompass();
            }
        }).map(GPS_TO_DIRECTION);

        directionObservable = RxUtils.rememberLast(Observable.merge(magneticDirectionObservable, directionFromGpsObservable).doOnNext(onNextrememberDirectionAction), 0f);
    }

    public Observable<GeoData> geoDataObservable(final boolean lowPower) {
        return lowPower ? geoDataObservableLowPower : geoDataObservable;
    }

    public Observable<Float> directionObservable() {
        return directionObservable;
    }

    public Observable<Status> gpsStatusObservable() {
        return gpsStatusObservable;
    }

    @NonNull
    public GeoData currentGeo() {
        return currentGeo;
    }

    public float currentDirection() {
        return currentDirection;
    }

    public boolean hasCompassCapabilities() {
        return hasCompassCapabilities;
    }

}
