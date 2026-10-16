package com.autel.sdksample;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.autel.common.CallbackWithNoParam;
import com.autel.common.SDKConfig;
import com.autel.common.error.AutelError;
import com.autel.internal.DeviceTypeManager;
import com.autel.internal.sdk.util.AutelDirPathUtils;
import com.autel.sdk.Autel;
import com.autel.sdk.AutelSdkConfig;
import com.autel.sdk.product.BaseProduct;
import com.autel.sdksample.evo.mission.util.AutelConfigManager;
import com.autel.util.log.AutelLog;
import com.taklite.util.AppLog;
//import com.autel.xlog.LogConfiguration;
//import com.autel.xlog.LogLevel;
//import com.autel.xlog.printer.AndroidPrinter;
//import com.autel.xlog.printer.XLogPrinter;
//import com.autel.xlog.printer.file.FilePrinter;
//import com.autel.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
//import com.autel.xlog.printer.file.naming.DateFileNameGenerator;
//import com.autel.xlog.printer.flattener.ClassicFlattener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TestApplication extends Application {
    private final String TAG = getClass().getSimpleName();
    private BaseProduct currentProduct;

    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 2; // two days
    private static  String LOG_PATH;
//    public static XLogPrinter globalFilePrinter;

    // App-wide Context accessor — DTED lookups (DtedIndex/TerrainAgl) need a Context but run from a background telemetry
    // tick with no Activity in hand.
    private static Application app;

    public static Application getInstance() {
        return app;
    }

    public void onCreate() {
        super.onCreate();
        app = this;
        Log.v("connectDebug", "TestApplication onCreate ");
        // ORDER MATTERS HERE. AppLog first so the suppression result is captured, then Explorer
        // suppression, then the heavier SDK log setup — NOT the other way round.
        AppLog.init(this);
        settleAppMode();
        // Flight path recording. Init only arms the worker thread; nothing is
        // written until the bridge reports the aircraft airborne.
        com.autel.sdksample.tak.FlightPathLogger.init(this);
        // Data Sync membership: restores the joined feed and resumes its poll. Without this call
        // nothing set the manager's context, so a join was never saved and was lost on every
        // restart. Touches no SDK.
        com.autel.sdksample.tak.TakMissionManager.INSTANCE.init(this);
        // The quick-marker name and the pins must be loaded before the Field Guide or Home reads
        // them, not only when the flight screen opens. Reads prefs only.
        com.autel.sdksample.tak.TakDropMarkers.INSTANCE.init(this);
        // The aim calibration applies from app start, not only once the flight screen opens:
        // the bridge publishes the SPI and the sensor cone before that.
        com.autel.sdksample.tak.ArSettings.INSTANCE.loadAimOffsets(this);

        // Start the Autel Explorer watchdog. Explorer is a system app that starts ITSELF and
        // seizes the aircraft USB link; it has killed a live flight with the pilot never
        // having opened it. The watchdog kills its background process on launch, on its USB
        // broadcasts, and on a low-frequency poll — a no-permanent-change mitigation, proven on
        // hardware. (This replaced the earlier device-owner design, which required a
        // forbidden permanent change to the controller.) See ExplorerWatchdog.
        com.autel.sdksample.tak.ExplorerWatchdog.INSTANCE.onAppStart(this);
        // The stream path this process will publish under, once. This is also where the
        // per-process token is made when the random path is on — see StreamPath.
        com.autel.sdksample.tak.StreamPath.INSTANCE.logSessionPath(this);
        // What the application does when an Emergency Broadcast starts, renews or ends — the
        // flight-record line. Here, not on a screen, so it runs whichever screen is open, or
        // none. See EmergencyBroadcastPolicy.
        com.autel.sdksample.tak.EmergencyBroadcastPolicy.install(this);

        initXlog();
        initAutelSdkLog();
        // Real client identity for CoT's <takv> block, so this app's own entry in a TAK
        // server's connected-users list says what it actually is, not the shared TakManager
        // core's generic placeholder. See TakManager.setClientIdentity's doc — deliberately
        // set from HERE (this app), not inside the shared core, which is shared code and must
        // not be hardcoded to any one app's name.
        com.taklite.client.tak.TakManager.getInstance().setClientIdentity(
                "TAKPilot2-Autel",
                android.os.Build.MODEL,
                "Android " + android.os.Build.VERSION.RELEASE,
                appVersionName(this));
        // Pull the downloaded FAA ceilings into memory on a background thread now, so a later
        // HUD/telemetry read never has to do a tens-of-thousands-of-rows read on the main
        // thread while video is running.
        com.autel.sdksample.tak.UasfmIndex.INSTANCE.preload(this);
        /**
         * 初始化SDK，通过网络验证APPKey的有效性
         */
        Thread.setDefaultUncaughtExceptionHandler(new EHandle(Thread.getDefaultUncaughtExceptionHandler()));
        Thread.setDefaultUncaughtExceptionHandler(
                new AppLogCrashHandler(Thread.getDefaultUncaughtExceptionHandler()));
        // From autel-key.properties at build time (see app/build.gradle).
        String appKey = BuildConfig.AUTEL_APP_KEY;
        AutelSdkConfig config = new AutelSdkConfig.AutelSdkConfigBuilder()
                .setAppKey(appKey)
                .setPostOnUi(true)
                .create();
        DeviceTypeManager.getInstance().setAdvance(true);
        SDKConfig.setDevice(SDKConfig.DEVICE_ADVANCE);
        Autel.init(this, config, new CallbackWithNoParam() {
            @Override
            public void onSuccess() {
                Log.v(TAG, "checkAppKeyValidate onSuccess");
            }

            @Override
            public void onFailure(AutelError error) {
                Log.v(TAG, "checkAppKeyValidate " + error.getDescription());
            }
        });

        AutelConfigManager.instance().init(this);
    }

    /**
     * Decides ELEVATED or BASIC before ANYTHING reads it — and re-asserts BASIC's invariants.
     *
     * ⚠ HERE, NOT IN PRE-FLIGHT. The first version ran the first-run
     * decision from Pre-Flight's onCreate. But the boot TAK dial (Home's onCreate), the flight
     * screen's channel unlock and the Field Guide are all reachable BEFORE Pre-Flight is ever
     * opened, and until the decision ran each of them read the default. The Application runs
     * before every Activity and every restarted Service, so nothing can read an unsettled mode.
     *
     * ⚠ THE ORDER INSIDE IS LOAD-BEARING, AND IT LIVES IN AppMode.settle — a function of the
     * preferences alone, so a test holds the order instead of this comment.
     *
     * ⚠ GUARDED — THIS RUNS ON EVERY PROCESS START. Android's SharedPreferences throws
     * ClassCastException when a key holds a different type than it is read as. Unguarded, a
     * drifted key would crash EVERY launch — Home and the flight screen included — for good. On
     * failure the mode stays unwritten and reads ENHANCED, the pre-mode-switch behaviour.
     */
    private void settleAppMode() {
        try {
            com.autel.sdksample.tak.AppMode.Companion.settle(
                    getSharedPreferences(com.autel.sdksample.tak.TakConnectActivity.PREFS,
                            MODE_PRIVATE),
                    com.autel.sdksample.tak.TakMissionManager.INSTANCE.hasElevatedEnrollment(this));
        } catch (Throwable t) {
            AppLog.e("TestApplication", "app mode not settled: " + t.getMessage(), t);
        }
    }

    /** Real app versionName for the <takv version="..."> attribute, read from the package rather
     *  than hand-maintained, so it can never go stale the way the shared core's old hardcoded
     *  "1.0" placeholder already had. "unknown" only if the package itself can't be read, which
     *  should not happen for a running app. */
    private static String appVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    public BaseProduct getCurrentProduct() {
        return currentProduct;
    }

    public void setCurrentProduct(BaseProduct baseProduct) {
        currentProduct = baseProduct;
    }

    /**
     * Runs before EHandle. Only writes to the AppLog file sink while debug logging is
     * toggled on (crash capture only while the toggle is ON), then always
     * chains to whatever handler was previously installed so existing behavior is unchanged.
     */
    private static class AppLogCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        AppLogCrashHandler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            if (AppLog.getEnabled()) {
                AppLog.writeCrash(thread, ex);
            }
            if (previous != null) {
                previous.uncaughtException(thread, ex);
            }
        }
    }

    public class EHandle implements Thread.UncaughtExceptionHandler {
        Thread.UncaughtExceptionHandler defaultExceptionHandler;

        public EHandle(Thread.UncaughtExceptionHandler defaultExceptionHandler) {
            this.defaultExceptionHandler = defaultExceptionHandler;
        }

        @Override
        public void uncaughtException(Thread thread, final Throwable ex) {
            new ExceptionWriter(ex, getApplicationContext()).saveStackTraceToSD();
            defaultExceptionHandler.uncaughtException(thread, ex);
        }
    }

    public static class ExceptionWriter {
        private Throwable exception;
        private Context mContext;

        public ExceptionWriter(Throwable ex, Context c) {
            this.exception = ex;
            this.mContext = c;
        }

        public void saveStackTraceToSD() {
            try {
                PackageManager packageManager = mContext.getPackageManager();
                String appId = null;
                String appVersion = null;
                try {
                    appId = mContext.getPackageName();
                    PackageInfo packageInfo = packageManager.getPackageInfo(appId, 0);
                    appVersion = packageInfo.versionName;
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }

                FileOutputStream excep = this.getExceptionFileStream();
                StringBuilder sb = new StringBuilder();
                sb.append("android sdk version = " + Build.VERSION.SDK_INT + "\n");
                sb.append("phoneType = " + Build.MODEL + "\n");
                sb.append(appId + " " + appVersion + "\n");
                sb.append("error occured Time = " + getTimeStamp() + "\n\n");
                StringWriter writer = new StringWriter();
                PrintWriter printWriter = new PrintWriter(writer);
                this.exception.printStackTrace(printWriter);

                for (Throwable cause = this.exception.getCause(); cause != null; cause = cause.getCause()) {
                    cause.printStackTrace(printWriter);
                }

                printWriter.close();
                String result = writer.toString();
                sb.append(result);

                try {
                    if (Environment.getExternalStorageState().equals("mounted")) {
                        excep.write(sb.toString().getBytes());
                        excep.close();
                    }
                } catch (Exception var8) {
                }
            } catch (Exception var9) {
                var9.printStackTrace();
            }

        }

        private FileOutputStream getExceptionFileStream() throws FileNotFoundException {
            File myDir = new File(AutelDirPathUtils.getLogCatPath());
            if (!myDir.exists()) {
                myDir.mkdirs();
            }

            myDir.mkdirs();
            File file = new File(myDir, getTimeStampForFileName() + ".txt");
            if (file.exists()) {
                file.delete();
            }

            FileOutputStream out = new FileOutputStream(file);
            return out;
        }
    }

    public static String getTimeStamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss", Locale.US);
        String timeStamp = sdf.format(new Date());
        return timeStamp;
    }

    public static String getTimeStampForFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
        String timeStamp = sdf.format(new Date());
        return timeStamp;
    }

    /**
     * Turns ON the Autel SDK's own internal connect trace.
     *
     * WHY THIS EXISTS: the SDK's most useful diagnostics — the camera enumeration handshake —
     * go through AutelLog.debug_i(), which forwards to com.autel.log.AutelLog.i(), which is a
     * NO-OP while its mLogger field is null. Nothing installs that logger by default, so lines
     * like "MessageDisPatcher: camera notifyConnected<bool>" and "ConnectDebug: camera connect
     * from X to Y" are silently dropped — upstream of logcat, not inside it.
     *
     * Passing debug=true selects DebugLog, which writes to a file AND mirrors to
     * android.util.Log — that mirror is the whole point. The alternative (debug=false) is
     * LogImpl, which routes to Tencent mars/xlog and produces NOTHING in logcat.
     *
     * NOTE the two AutelLog classes: com.autel.util.log.AutelLog (thin android.util.Log
     * wrapper, imported at the top of this file) is NOT this one. The commented-out
     * AutelLog.init in initXlog() below is that other class's XLog-era signature and would
     * not have helped.
     *
     * Diagnostic aid, not a feature. Cheap enough to leave on; drop it if log volume bites.
     */
    private void initAutelSdkLog() {
        try {
            File dir = getExternalFilesDir("autel-sdk-log");
            if (dir == null) dir = new File(getFilesDir(), "autel-sdk-log");   // no external vol
            com.autel.log.AutelLog.init(
                    true,                                  // debug -> DebugLog -> logcat mirror
                    dir.getAbsolutePath(),
                    getCacheDir().getAbsolutePath(),
                    0);                                    // level; ignored when debug==true
            Log.i(TAG, "Autel SDK logger installed -> " + dir.getAbsolutePath());
        } catch (Throwable t) {
            // Never let a diagnostic aid take the app down at startup.
            Log.w(TAG, "Autel SDK logger install failed: " + t);
        }
    }

    /**
     * Initialize XLog.
     */
    private void initXlog() {
        LOG_PATH = "sdcard/maxlink/";//AutelDirPathUtils.getLogCatPath();
//        LogConfiguration config = new LogConfiguration.Builder().logLevel(BuildConfig.DEBUG ? LogLevel.ALL             // Specify log level, logs below this level won't be printed, default: LogLevel.ALL
//                : LogLevel.NONE).tag(getString(R.string.app_name))                   // Specify TAG, default: "X-LOG"
//                .t()                                                // Enable thread info, disabled by default
//                .st(2)                                              // Enable stack trace info with depth 2, disabled by default
//                .b()                                                // Enable border, disabled by default
//        .addInterceptor(new BlacklistTagsFilterInterceptor(    // Add blacklist tags filter
//            "blacklist1", "blacklist2", "blacklist3"))
                // .addInterceptor(new WhitelistTagsFilterInterceptor( // Add whitelist tags filter
                //     "whitelist1", "whitelist2", "whitelist3"))
                // .addInterceptor(new MyInterceptor())                // Add a log interceptor
//                .build();

//        XLogPrinter androidPrinter = new AndroidPrinter();             // XLogPrinter that print the log using android.util.Log
//        XLogPrinter filePrinter = new FilePrinter                      // XLogPrinter that print the log to the file system
//                .Builder(new File(Environment.getExternalStorageDirectory(), LOG_PATH).getPath())       // Specify the path to save log file
//                .fileNameGenerator(new DateFileNameGenerator())        // Default: ChangelessFileNameGenerator("log")
//                // .backupStrategy(new MyBackupStrategy())             // Default: FileSizeBackupStrategy(1024 * 1024)
//                .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))     // Default: NeverCleanStrategy()
//                .flattener(new ClassicFlattener())                     // Default: DefaultFlattener
//                .build();

//        AutelLog.init(                                                 // Initialize XLog
//                config,                                                // Specify the log configuration, if not specified, will use new LogConfiguration.Builder().build()
//                androidPrinter,                                        // Specify printers, if no printer is specified, AndroidPrinter(for Android)/ConsolePrinter(for java) will be used.
//                filePrinter);

        // For future usage: partial usage in MainActivity.
//        globalFilePrinter = filePrinter;
    }
}
