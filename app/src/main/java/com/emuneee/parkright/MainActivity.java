package com.emuneee.parkright;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;

import rx.AsyncEmitter;
import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import timber.log.Timber;

public class MainActivity extends Activity {

    private static final int RANGE_STOP = 30;
    private static final int RANGE_SLOW = 45;
    private static final int RANGE_PROCEED = 60;

    private static final String PIN_TRIGGER = "BCM21";
    private static final String PIN_ECHO = "BCM20";
    private static final String PIN_ALERT = "BCM4";
    private static final String PIN_SLOW = "BCM19";
    private static final String PIN_PROCEED = "BCM17";

    private static final int REFRESH_DURATION_MS = 200;

    private static final int START_DURATION_NS = 10_000;
    private static final int INIT_DURATION_NS = 2_000;

    private static final double MAX_DISTANCE_CM = 500.0;

    private Gpio echoPin;
    private Gpio triggerPin;
    private Gpio alertPin;
    private Gpio slowPin;
    private Gpio proceedPin;

    private Subscription rangeSubscription;
    private PeripheralManagerService pioService;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Timber.plant(new Timber.DebugTree());
        Timber.d("Android API Level: %d", Build.VERSION.SDK_INT);
        pioService = new PeripheralManagerService();
        configurePins();
        handler = new Handler();

        rangeSubscription = initiateDetection()
                .subscribeOn(Schedulers.computation())
                .flatMap(this::calculateDistance)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(distance -> {

                    if (distance > 0 && distance <= MAX_DISTANCE_CM) {
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
            slowPin = pioService.openGpio(PIN_SLOW);
            slowPin.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            proceedPin = pioService.openGpio(PIN_PROCEED);
            proceedPin.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);

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

            if (distance < RANGE_STOP) {
                alertPin.setValue(true);
                slowPin.setValue(false);
                proceedPin.setValue(false);
            } else if (distance >= RANGE_STOP && distance < RANGE_SLOW) {
                alertPin.setValue(false);
                slowPin.setValue(true);
                proceedPin.setValue(false);
            } else if (distance >= RANGE_PROCEED){
                alertPin.setValue(false);
                slowPin.setValue(false);
                proceedPin.setValue(true);
            }
        } catch (IOException e) {
            Timber.w(e, "Error in setAlert");
        }
    }

    /**
     * Initiates range detection, emits the detected pulse width
     * @return
     */
    private Observable<Double> initiateDetection() {

        return Observable.fromAsync(emitter -> {

            GpioCallback echoCallback = new GpioCallback() {

                private long echoStart;
                private long echoEnd;

                @Override
                public boolean onGpioEdge(final Gpio gpio) {

                    try {
                        if (gpio.getValue()) {
                            // edge is hi
                            echoStart = System.nanoTime();
                        } else {
                            echoEnd = System.nanoTime();
                            double pulseWidth = (echoEnd - echoStart) / 1000.0;
                            Timber.d("Pulse width (uS) %f", pulseWidth);
                            //Timber.d("Start %d, End %d, Pulse width (uS) %f", echoStart, echoEnd, pulseWidth);
                            emitter.onNext(pulseWidth);
                        }
                    } catch (IOException e) {
                        Timber.w(e, "Error in onGpioEdge");
                        emitter.onError(e);
                    }
                    return true;
                }

                @Override
                public void onGpioError(final Gpio gpio, final int error) {
                    Timber.w("Gpio Error %d", error);
                }
            };

            emitter.setCancellation(() -> echoPin.unregisterGpioCallback(echoCallback));

            try {
                echoPin.registerGpioCallback(echoCallback, handler);

                while (true) {
                    // initialize the trigger pins
                    triggerPin.setValue(false);
                    sleepInNs(INIT_DURATION_NS);

                    // start detection by raising the trigger signal for 10uS
                    Timber.d("Triggering detection");
                    triggerPin.setValue(true);
                    sleepInNs(START_DURATION_NS);
                    triggerPin.setValue(false);
                    sleepInMs(REFRESH_DURATION_MS);
                }
            } catch (Exception e) {
                Timber.w(e, "Error in startDetection");
                emitter.onError(e);
            }

        }, AsyncEmitter.BackpressureMode.BUFFER);
    }

    /**
     * Returns a distance in centimeters, based on the pulse width
     * @param pulseWidthUs duration of the pulse in microseconds
     * @return
     */
    private Observable<Double> calculateDistance(double pulseWidthUs) {
        double distance = pulseWidthUs / 58.23;
        return Observable.just(distance);
    }

    private void sleepInNs(int timeInNs) throws InterruptedException {
        Thread.sleep(0, timeInNs);
    }

    private void sleepInMs(int timeInMs) throws InterruptedException {
        Thread.sleep(timeInMs);
    }
}
