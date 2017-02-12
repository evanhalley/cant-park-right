package com.emuneee.parkright;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import timber.log.Timber;

public class MainActivity extends Activity {

    private static final String PIN_TRIGGER = "BCM21";
    private static final String PIN_ECHO = "BCM20";
    private static final String PIN_ALERT = "BCM4";

    private static final int TIMEOUT_MS = 2_000;
    private static final int START_DURATION_NS = 10000;
    private static final int INIT_DURATION_NS = 5000;
    private static final int REFRESH_DURATION_MS = 1000;
    private static final double MAX_DISTANCE_CM = 500.0;

    private Gpio echoPin;
    private Gpio triggerPin;
    private Gpio alertPin;

    private Subscription rangeSubscription;
    PeripheralManagerService pioService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Timber.plant(new Timber.DebugTree());
        Timber.d("Android API Level: %d", Build.VERSION.SDK_INT);
        pioService = new PeripheralManagerService();
        configurePins();

        rangeSubscription = initiateDetection()
                .flatMap(this::calculateDistance)
                .timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS, Observable.just(MAX_DISTANCE_CM + 1))
                .repeatWhen(c -> c.delay(REFRESH_DURATION_MS, TimeUnit.MILLISECONDS))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(distance -> {

                    if (distance <= MAX_DISTANCE_CM) {
                        Timber.d("Distance (cm): %f", distance);
                        setAlert(distance);
                    }
                }, error -> {
                    Timber.w(error, "Error");
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (rangeSubscription != null && !rangeSubscription.isUnsubscribed()) {
            rangeSubscription.unsubscribe();
        }
    }

    private void configurePins() {

        Timber.d("Available pins %s", pioService.getGpioList());

        try {
            alertPin = pioService.openGpio(PIN_ALERT);
            alertPin.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);

            triggerPin = pioService.openGpio(PIN_TRIGGER);
            triggerPin.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);

            echoPin = pioService.openGpio(PIN_ECHO);
            echoPin.setDirection(Gpio.DIRECTION_IN);
            echoPin.setEdgeTriggerType(Gpio.EDGE_BOTH);
            echoPin.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            Timber.w(e, "Error in configurePins");
        }
        Timber.d("Pins configured");
    }

    private void setAlert(double distance) {

        try {
            alertPin.setValue(distance < 30);
        } catch (IOException e) {
            Timber.w(e, "Error in setAlert");
        }

    }

    /**
     * Initiates range detection, emits the detected pulse width
     * @return
     */
    private Observable<Long> initiateDetection() {

        return Observable.create(subscriber -> {
            int keepBusy = 0;
            long echoStart;
            long echoEnd;

            try {
                // initialize the trigger pins
                triggerPin.setValue(false);
                sleep(INIT_DURATION_NS);

                // start detection
                Timber.d("Triggering detection");
                triggerPin.setValue(true);
                sleep(START_DURATION_NS);
                triggerPin.setValue(false);

                //// THIS BLOCK NEEDS TO BE AS QUICK AS POSSIBLE
                // wait while we wait for the starting edge of the echo
                while (!echoPin.getValue()) {
                    keepBusy = 1;
                }
                echoStart = System.nanoTime();

                Timber.d("Edge hi!");

                // wait while we wait for the ending edge of the echo
                while (echoPin.getValue()) {
                    keepBusy = 2;
                }

                echoEnd = System.nanoTime();
                //// END BLOCK

                long pulseWidth = echoEnd - echoStart;
                Timber.d("Pulse width (ns) %d", pulseWidth);
                subscriber.onNext(pulseWidth);
                subscriber.onCompleted();

            } catch (Exception e) {
                Timber.w(e, "Error in startDetection");
                subscriber.onError(e);
            }
        });
    }

    /**
     * Returns a distance in centimeters, based on the pulse width
     * @param pulseWidth
     * @return
     */
    private Observable<Double> calculateDistance(long pulseWidth) {
        double distance = (pulseWidth / 1000.0) / 58.23;
        return Observable.just(distance);
    }

    private void sleep(int timeInNs) throws InterruptedException {
        Thread.sleep(0, timeInNs);
    }
}
