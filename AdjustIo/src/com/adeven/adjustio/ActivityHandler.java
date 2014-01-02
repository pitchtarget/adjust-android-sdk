//
//  ActivityHandler.java
//  AdjustIo
//
//  Created by Christian Wellenbrock on 2013-06-25.
//  Copyright (c) 2013 adeven. All rights reserved.
//  See the file MIT-LICENSE for copying permission.
//

package com.adeven.adjustio;

import static com.adeven.adjustio.Constants.LOGTAG;
import static com.adeven.adjustio.Constants.ONE_MINUTE;
import static com.adeven.adjustio.Constants.ONE_SECOND;
import static com.adeven.adjustio.Constants.SESSION_STATE_FILENAME;
import static com.adeven.adjustio.Constants.THIRTY_SECONDS;
import static com.adeven.adjustio.Constants.UNKNOWN;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;

public class ActivityHandler extends HandlerThread {

    private static final long   TIMER_INTERVAL      = ONE_MINUTE;
    private static final long   SESSION_INTERVAL    = THIRTY_SECONDS;
    private static final long   SUBSESSION_INTERVAL = ONE_SECOND;
    private static final String TIME_TRAVEL         = "Time travel!";

    private final  SessionHandler           sessionHandler;
    private        PackageHandler           packageHandler;
    private        ActivityState            activityState;
    private static ScheduledExecutorService timer;
    private final  Context                  context;
    private        String                   environment;
    private        String                   defaultTracker;
    private        boolean                  eventBuffering;

    private String appToken;
    private String macSha1;
    private String macShortMd5;
    private String androidId;       // everything else here could be persisted
    private String fbAttributionId;
    private String userAgent;       // changes, should be updated periodically
    private String clientSdk;
    private Map<String, String> deviceData;

    protected ActivityHandler(Activity activity) {
        super(LOGTAG, MIN_PRIORITY);
        setDaemon(true);
        start();
        sessionHandler = new SessionHandler(getLooper(), this);
        context = activity.getApplicationContext();
        clientSdk = Constants.CLIENT_SDK;

        Message message = Message.obtain();
        message.arg1 = SessionHandler.INIT_BUNDLE;
        sessionHandler.sendMessage(message);
    }

    protected ActivityHandler(Activity activity, String appToken, String environment, boolean eventBuffering) {
        super(LOGTAG, MIN_PRIORITY);
        setDaemon(true);
        start();
        sessionHandler = new SessionHandler(getLooper(), this);
        context = activity.getApplicationContext();
        clientSdk = Constants.CLIENT_SDK;

        this.appToken = appToken;
        this.environment = environment;
        this.eventBuffering = eventBuffering;

        Message message = Message.obtain();
        message.arg1 = SessionHandler.INIT_PRESET;
        sessionHandler.sendMessage(message);
    }

    protected void setSdkPrefix(String sdkPrefx) {
        clientSdk = String.format("%s@%s", sdkPrefx, clientSdk);
    }

    protected void trackSubsessionStart() {
        Message message = Message.obtain();
        message.arg1 = SessionHandler.START;
        sessionHandler.sendMessage(message);
    }

    protected void trackSubsessionEnd() {
        Message message = Message.obtain();
        message.arg1 = SessionHandler.END;
        sessionHandler.sendMessage(message);
    }

    protected void trackEvent(String eventToken, Map<String, String> parameters) {
        PackageBuilder builder = new PackageBuilder();
        builder.setEventToken(eventToken);
        builder.setCallbackParameters(parameters);

        Message message = Message.obtain();
        message.arg1 = SessionHandler.EVENT;
        message.obj = builder;
        sessionHandler.sendMessage(message);
    }

    protected void trackRevenue(double amountInCents, String eventToken, Map<String, String> parameters) {
        PackageBuilder builder = new PackageBuilder();
        builder.setAmountInCents(amountInCents);
        builder.setEventToken(eventToken);
        builder.setCallbackParameters(parameters);

        Message message = Message.obtain();
        message.arg1 = SessionHandler.REVENUE;
        message.obj = builder;
        sessionHandler.sendMessage(message);
    }

    private static final class SessionHandler extends Handler {
        private static final int INIT_BUNDLE = 72630;
        private static final int INIT_PRESET = 72633;
        private static final int START       = 72640;
        private static final int END         = 72650;
        private static final int EVENT       = 72660;
        private static final int REVENUE     = 72670;

        private final WeakReference<ActivityHandler> sessionHandlerReference;

        protected SessionHandler(Looper looper, ActivityHandler sessionHandler) {
            super(looper);
            this.sessionHandlerReference = new WeakReference<ActivityHandler>(sessionHandler);
        }

        @Override
        public void handleMessage(Message message) {
            super.handleMessage(message);

            ActivityHandler sessionHandler = sessionHandlerReference.get();
            if (sessionHandler == null) {
                return;
            }

            switch (message.arg1) {
                case INIT_BUNDLE:
                    sessionHandler.initInternal(true);
                    break;
                case INIT_PRESET:
                    sessionHandler.initInternal(false);
                    break;
                case START:
                    sessionHandler.startInternal();
                    break;
                case END:
                    sessionHandler.endInternal();
                    break;
                case EVENT:
                    PackageBuilder eventBuilder = (PackageBuilder) message.obj;
                    sessionHandler.trackEventInternal(eventBuilder);
                    break;
                case REVENUE:
                    PackageBuilder revenueBuilder = (PackageBuilder) message.obj;
                    sessionHandler.trackRevenueInternal(revenueBuilder);
                    break;
            }
        }
    }

    private void initInternal(boolean fromBundle) {
        if (fromBundle) {
            processApplicationBundle();
        } else {
            setEnvironment(environment);
            setEventBuffering(eventBuffering);
        }

        if (!canInit()) {
            return;
        }

        String macAddress = Util.getMacAddress(context);
        String macShort = macAddress.replaceAll(":", "");

        macSha1 = Util.sha1(macAddress);
        macShortMd5 = Util.md5(macShort);
        androidId = Util.getAndroidId(context);
        fbAttributionId = Util.getAttributionId(context);
        deviceData = Util.getDeviceData(context);
        userAgent = Util.getUserAgent(context);

        packageHandler = new PackageHandler(context);
        readActivityState();
    }

    private boolean canInit() {
        return checkAppTokenNotNull(appToken)
            && checkAppTokenLength(appToken)
            && checkContext(context)
            && checkPermissions(context);
    }

    private void startInternal() {
        if (!checkAppTokenNotNull(appToken)) {
            return;
        }

        packageHandler.resumeSending();
        startTimer();

        long now = System.currentTimeMillis();

        // very first session
        if (null == activityState) {
            activityState = new ActivityState();
            activityState.sessionCount = 1; // this is the first session
            activityState.createdAt = now;  // starting now

            transferSessionPackage();
            activityState.resetSessionAttributes(now);
            writeActivityState();
            Logger.info("First session");
            return;
        }

        long lastInterval = now - activityState.lastActivity;
        if (lastInterval < 0) {
            Logger.error(TIME_TRAVEL);
            activityState.lastActivity = now;
            writeActivityState();
            return;
        }

        // new session
        if (lastInterval > SESSION_INTERVAL) {
            activityState.sessionCount++;
            activityState.createdAt = now;
            activityState.lastInterval = lastInterval;

            transferSessionPackage();
            activityState.resetSessionAttributes(now);
            writeActivityState();
            Logger.debug(String.format(Locale.US,
                                       "Session %d", activityState.sessionCount));
            return;
        }

        // new subsession
        if (lastInterval > SUBSESSION_INTERVAL) {
            activityState.subsessionCount++;
            Logger.info(String.format(Locale.US,
                                      "Started subsession %d of session %d",
                                      activityState.subsessionCount,
                                      activityState.sessionCount));
        }
        activityState.sessionLength += lastInterval;
        activityState.lastActivity = now;
        writeActivityState();
    }

    private void endInternal() {
        if (!checkAppTokenNotNull(appToken)) {
            return;
        }

        packageHandler.pauseSending();
        stopTimer();
        updateActivityState();
        writeActivityState();
    }

    private void trackEventInternal(PackageBuilder eventBuilder) {
        if (!canTrackEvent(eventBuilder)) {
            return;
        }

        activityState.createdAt = System.currentTimeMillis();
        activityState.eventCount++;
        updateActivityState();

        injectGeneralAttributes(eventBuilder);
        activityState.injectEventAttributes(eventBuilder);
        ActivityPackage eventPackage = eventBuilder.buildEventPackage();
        packageHandler.addPackage(eventPackage);

        if (eventBuffering) {
            Logger.info(String.format("Buffered event %s", eventPackage.getSuffix()));
        } else {
            packageHandler.sendFirstPackage();
        }

        writeActivityState();
        Logger.debug(String.format(Locale.US, "Event %d", activityState.eventCount));
    }


    private void trackRevenueInternal(PackageBuilder revenueBuilder) {
        if (!canTrackRevenue(revenueBuilder)) {
            return;
        }

        activityState.createdAt = System.currentTimeMillis();
        activityState.eventCount++;
        updateActivityState();

        injectGeneralAttributes(revenueBuilder);
        activityState.injectEventAttributes(revenueBuilder);
        ActivityPackage eventPackage = revenueBuilder.buildRevenuePackage();
        packageHandler.addPackage(eventPackage);

        if (eventBuffering) {
            Logger.info(String.format("Buffered revenue %s", eventPackage.getSuffix()));
        } else {
            packageHandler.sendFirstPackage();
        }

        writeActivityState();
        Logger.debug(String.format(Locale.US, "Event %d (revenue)", activityState.eventCount));
    }

    private boolean canTrackEvent(PackageBuilder revenueBuilder) {
        return checkAppTokenNotNull(appToken)
            && checkActivityState(activityState)
            && revenueBuilder.isValidForEvent();
    }

    private boolean canTrackRevenue(PackageBuilder revenueBuilder) {
        return checkAppTokenNotNull(appToken)
            && checkActivityState(activityState)
            && revenueBuilder.isValidForRevenue();
    }

    private void updateActivityState() {
        if (!checkActivityState(activityState)) {
            return;
        }

        long now = System.currentTimeMillis();
        long lastInterval = now - activityState.lastActivity;
        if (lastInterval < 0) {
            Logger.error(TIME_TRAVEL);
            activityState.lastActivity = now;
            return;
        }

        // ignore late updates
        if (lastInterval > SESSION_INTERVAL) {
            return;
        }

        activityState.sessionLength += lastInterval;
        activityState.timeSpent += lastInterval;
        activityState.lastActivity = now;
    }

    private void readActivityState() {
        try {
            FileInputStream inputStream = context.openFileInput(SESSION_STATE_FILENAME);
            BufferedInputStream bufferedStream = new BufferedInputStream(inputStream);
            ObjectInputStream objectStream = new ObjectInputStream(bufferedStream);

            try {
                activityState = (ActivityState) objectStream.readObject();
                Logger.debug(String.format("Read activity state: %s", activityState));
                return;
            } catch (ClassNotFoundException e) {
                Logger.error("Failed to find activity state class");
            } catch (OptionalDataException e) {
                /* no-op */
            } catch (IOException e) {
                Logger.error("Failed to read activity states object");
            } catch (ClassCastException e) {
                Logger.error("Failed to cast activity state object");
            } finally {
                objectStream.close();
            }

        } catch (FileNotFoundException e) {
            Logger.verbose("Activity state file not found");
        } catch (Exception e) {
            Logger.error(String.format("Failed to open activity state file for reading (%s)", e));
        }

        // start with a fresh activity state in case of any exception
        activityState = null;
    }

    private void writeActivityState() {
        try {
            FileOutputStream outputStream = context.openFileOutput(SESSION_STATE_FILENAME, Context.MODE_PRIVATE);
            BufferedOutputStream bufferedStream = new BufferedOutputStream(outputStream);
            ObjectOutputStream objectStream = new ObjectOutputStream(bufferedStream);

            try {
                objectStream.writeObject(activityState);
                Logger.verbose(String.format("Wrote activity state: %s", activityState));
            } catch (NotSerializableException e) {
                Logger.error("Failed to serialize activity state");
            } finally {
                objectStream.close();
            }

        } catch (Exception e) {
            Logger.error(String.format("Failed to open activity state for writing (%s)", e));
        }
    }

    private void transferSessionPackage() {
        PackageBuilder builder = new PackageBuilder();
        injectGeneralAttributes(builder);
        injectReferrer(builder);
        activityState.injectSessionAttributes(builder);
        ActivityPackage sessionPackage = builder.buildSessionPackage();
        packageHandler.addPackage(sessionPackage);
        packageHandler.sendFirstPackage();
    }

    private void injectGeneralAttributes(PackageBuilder builder) {
        builder.setAppToken(appToken);
        builder.setMacShortMd5(macShortMd5);
        builder.setMacSha1(macSha1);
        builder.setAndroidId(androidId);
        builder.setFbAttributionId(fbAttributionId);
        builder.setUserAgent(userAgent);
        builder.setClientSdk(clientSdk);
        builder.setEnvironment(environment);
        builder.setDefaultTracker(defaultTracker);
    }

    private void injectReferrer(PackageBuilder builder) {
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            builder.setReferrer(preferences.getString(ReferrerReceiver.REFERRER_KEY, null));
        }
        catch (Exception e) {
            Logger.error(String.format("Failed to inject referrer (%s)", e));
        }
    }

    private void startTimer() {
        if (timer != null) {
            stopTimer();
        }
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                timerFired();
            }
        }, 1000, TIMER_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void stopTimer() {
        try {
            timer.shutdown();
        } catch (NullPointerException e) {
            Logger.error("No timer found");
        }
    }

    private void timerFired() {
        packageHandler.sendFirstPackage();

        updateActivityState();
        writeActivityState();
    }

    private static boolean checkPermissions(Context context) {
        boolean result = true;

        if (!checkPermission(context, android.Manifest.permission.INTERNET)) {
            Logger.error("Missing permission: INTERNET");
            result = false;
        }
        if (!checkPermission(context, android.Manifest.permission.ACCESS_WIFI_STATE)) {
            Logger.warn("Missing permission: ACCESS_WIFI_STATE");
        }

        return result;
    }

    private void processApplicationBundle() {
        Bundle bundle = getApplicationBundle();
        if (null == bundle) {
            return;
        }

        appToken = bundle.getString("AdjustIoAppToken");
        setEnvironment(bundle.getString("AdjustIoEnvironment"));
        setDefaultTracker(bundle.getString("AdjustIoDefaultTracker"));
        setEventBuffering(bundle.getBoolean("AdjustIoEventBuffering"));
        Logger.setLogLevelString(bundle.getString("AdjustIoLogLevel"));
    }

    private void setEnvironment(String environment) {
        if (null == environment) {
            Logger.Assert("Missing environment");
            Logger.setLogLevel(Logger.LogLevel.ASSERT);
            environment = UNKNOWN;
        } else if ("sandbox".equalsIgnoreCase(environment)) {
            Logger.Assert(
              "SANDBOX: AdjustIo is running in Sandbox mode. Use this setting for testing. Don't forget to set the environment to `production` before publishing!");
        } else if ("production".equalsIgnoreCase(environment)) {
            Logger.Assert(
              "PRODUCTION: AdjustIo is running in Production mode. Use this setting only for the build that you want to publish. Set the environment to `sandbox` if you want to test your app!");
            Logger.setLogLevel(Logger.LogLevel.ASSERT);
        } else {
            Logger.Assert(String.format("Malformed environment '%s'", environment));
            Logger.setLogLevel(Logger.LogLevel.ASSERT);
            environment = Constants.MALFORMED;
        }
    }

    private void setEventBuffering(boolean eventBuffering) {
        if (eventBuffering) {
            Logger.info("Event buffering is enabled");
        }
    }

    private void setDefaultTracker(String defaultTracker) {
        if (defaultTracker != null) {
            Logger.info(String.format("Default tracker: '%s'", defaultTracker));
        }
    }

    private Bundle getApplicationBundle() {
        final ApplicationInfo applicationInfo;
        try {
            String packageName = context.getPackageName();
            applicationInfo = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return applicationInfo.metaData;
        } catch (NameNotFoundException e) {
            Logger.error("ApplicationInfo not found");
        } catch (Exception e) {
            Logger.error(String.format("Failed to get ApplicationBundle (%s)", e));
        }
        return null;
    }

    private static boolean checkContext(Context context) {
        if (null == context) {
            Logger.error("Missing context");
            return false;
        }
        return true;
    }

    private static boolean checkPermission(Context context, String permission) {
        int result = context.checkCallingOrSelfPermission(permission);
        return result == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean checkActivityState(ActivityState activityState) {
        if (null == activityState) {
            Logger.error("Missing activity state.");
            return false;
        }
        return true;
    }

    private static boolean checkAppTokenNotNull(String appToken) {
        if (null == appToken) {
            Logger.error("Missing App Token.");
            return false;
        }
        return true;
    }

    private static boolean checkAppTokenLength(String appToken) {
//        if (12 != appToken.length()) {
//            Logger.error(String.format("Malformed App Token '%s'", appToken));
//            return false;
//        }
        return true;
    }
}
