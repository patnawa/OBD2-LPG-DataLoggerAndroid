package com.alpha.obd2logger;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity implements LoggerService.LoggerCallback {

    // --- UI: Tab bar ---
    // Status strip (bottom bar — replaces old BottomNavigationView)
    private View statusStrip;
    private View statusDot;
    private TextView statusDeviceText;
    private TextView stripRpm, stripSpeed, stripVoltage;
    private TextView stripDtcBadge;
    private View panelHome, panelDashboard, panelGauges, panelDtc, panelLogs, panelSettings;
    private View panelBattery;
    private com.google.android.material.appbar.MaterialToolbar topHeader;
    private androidx.activity.OnBackPressedCallback onBackPressedCallback;
    private boolean isTabChanging = false;
    // --- UI: Header ---
    private TextView headerStatus, headerVin, headerFuelMode, headerApiStatus;
    private TextView txtHomeVin, txtHomeVoltage, txtHomeAdapter, txtHomeProtocol, txtHomeRpm, txtHomeSpeed, txtHomeCoolant;
    private TextView txtHomeFuelEconomy, txtHomeBoost, txtHomeDpf, txtHomeDtc;
    private TextView stripBoost, stripFuel;
    private View headerStatusDot, headerApiDivider;
    private android.widget.ImageButton btnSettings;
    private android.widget.ImageButton btnGoHome;

    // --- UI: Settings ---
    private Spinner languageSpinner, themeSpinner, transportSpinner, fuelSpinner, obdProtocolSpinner, bluetoothDeviceSpinner;
    private EditText wifiIpInput, wifiPortInput, baudInput, intervalInput;
    private TextView bluetoothHintText;
    private CheckBox lpgOnlyCheckbox, backgroundLoggingCheckbox, keepScreenOnCheckbox, apiServerCheckbox, fordMsCanCheckbox;
    private CheckBox turboBoostCheckbox, fuelEconomyCheckbox, dpfMonitorCheckbox, customPidCheckbox;
    private CheckBox airDensityCheckbox;
    private TextView apiServerIpText;
    private TextView customLogFolderText;
    private Button btnSelectLogFolder;

    // --- UI: Dashboard ---
    private TextView statusText, countText;
    private TextView dashTitle1, dashValue1, dashTitle2, dashValue2, dashTitle3, dashValue3, dashTitle4, dashValue4;
    private GaugeView gauge1, gauge2, gauge3, gauge4;
    private TextView tuningStatusText;
    private TextView mapEctText, mapLoopStatusText;
    private TextView txtTuningStft, txtTuningLtft, txtTuningStatus, txtTuningAdvice;
    private TextView dashTuningStatus, dashEctText;
    private com.google.android.material.progressindicator.LinearProgressIndicator dashWarmupProgress;

    // --- UI: Gauges tab ---
    private GraphView graph1, graph2, graph3, graph4, graph5;
    private LinearLayout gaugeReadingsContainer;

    // --- UI: DTC tab ---
    private Button btnReadDtc, btnClearDtc, btnReadVin, btnReadiness;
    private TextView dtcStatusText;
    private LinearLayout dtcListContainer, readinessContainer;

    // --- UI: Battery tab ---
    private BatteryTestView batteryTestView;
    private TextView batteryVoltageText, batteryStatusText, socValueText, socVoltageText;
    private TextView batteryVoltageValueText, batteryVoltageStatusBadge;
    private TextView sohValueText, sohGradeText, batteryGradeText, batterySummaryText, batteryLifeText;
    private com.google.android.material.card.MaterialCardView batteryScoreCard;
    private com.google.android.material.button.MaterialButton btnBatteryResting, btnBatteryAlternator;
    private com.google.android.material.button.MaterialButton btnBatteryLoad, btnBatteryCrank;
    private com.google.android.material.button.MaterialButton btnBatteryRipple, btnBatteryFull;
    private AutoCompleteTextView batteryTypeSpinner;
    private com.google.android.material.textfield.TextInputEditText batteryAgeInput;
    private volatile double lastBatteryVoltage = -1;
    private volatile double restingVoltage = -1;
    private volatile double runningVoltage = -1;
    private volatile double crankMinVoltage = -1;
    private volatile double noLoadVoltage = -1;
    private volatile double fullLoadVoltage = -1;
    private volatile double highRpmVoltage = -1;
    private final java.util.List<Double> rippleSamples = new java.util.ArrayList<>();
    private volatile double preLoadVoltage = -1;
    private volatile double postLoadVoltage = -1;
    private volatile double recoveredVoltage = -1;
    private volatile double recoveryDelta = -1;

    // --- UI: Logs tab ---
    private TextView txtSessionDuration, txtSessionRecords, txtSessionRate, sessionStatusText;
    private com.google.android.material.card.MaterialCardView sessionStatusDotCard;
    private android.view.animation.Animation pulseAnimation;
    private long loggingStartTime = 0;
    private final android.os.Handler watchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable connectionWatchdog = new Runnable() {
        @Override
        public void run() {
            if (running || isConnecting) {
                isConnecting = false;
                stopLogging();
                setFabState(false);
                setConfigUiEnabled(true);
                updateStatusStripConnection(0, "Connection timed out");
                if (headerStatus != null) {
                    headerStatus.setText("Connection timed out");
                }
                setStatus("Connection timed out. If using background service, check autostart/battery settings.", R.color.danger);
            }
        }
    };
    private LinearLayout readingsContainer;
    private com.google.android.material.button.MaterialButton btnFilterPids;
    private TextView txtFilterStatus;
    // PID filter for Live Readings tab — null = show all, non-empty = show only listed keys
    private java.util.Set<String> visiblePidsFilter = null;
    // Default: hide derived sensors (derived_*) in live readings
    private boolean hideDerivedSensors = true;

    private com.google.android.material.button.MaterialButton fabLog;
    private FuelMapView fuelMapView;
    private AirDensityMonitor airDensityMonitor;
    // --- UI: Air Density Panel ---
    private View airDensityCard;
    private TextView txtAAD, txtMAD, txtBAD, txtDensityPct, txtDensityAlt, txtSAECF;
    private TextView txtOMD, txtCompEff, txtICEff, txtVE, txtPDI, txtGrains, txtAirDensityWeather;

    // --- UI: History tab ---
    private View panelHistory;
    private android.widget.ListView historyListViewPetrol;
    private android.widget.ListView historyListViewLpg;
    private TextView historyFolderText;
    private com.google.android.material.button.MaterialButton btnCompareLogs;
    private TextView compareHintText;
    private android.widget.ListView historyListViewFolders;
    private com.google.android.material.button.MaterialButton btnBackToFolders;
    private TextView vinFoldersHeader;
    private View compareBarLayout;
    private TextView petrolLogsHeader;
    private TextView lpgLogsHeader;
    private String selectedVinFolder = null;
    // Compare mode: when true the history list shows checkboxes so the user can
    // pick up to 2 logs (e.g. a Petrol log + an LPG log) and plot both onto one map.
    private boolean compareMode = false;
    private final java.util.LinkedHashSet<Uri> compareSelection = new java.util.LinkedHashSet<>();
    private java.util.List<HistoryLogFile> currentLogFiles = new ArrayList<>();

    // --- State ---
    private static MainActivity activeInstance;
    private static volatile ExecutorService executor;
    // Separate executor for DTC/VIN/readiness operations so that stopping the
    // logging executor (shutdownNow) doesn't kill pending diagnostic reads.
    private volatile ExecutorService dtcExecutor;
    private final List<DtcCode> lastStoredDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<DtcCode> lastPendingDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<DtcCode> lastPermanentDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile FreezeFrameData lastFreezeFrame = null;
    private DtcHistoryDb dtcHistoryDb;
    private static volatile boolean running;
    private static DataWriter currentWriter;
    private static volatile BaseDriver currentDriver;
    private static volatile LoggerConfig activeInProcessConfig;
    private File currentDownloadFolder;
    private static Uri currentCsvUri, currentJsonlUri;
    private static int sessionRecordCount = 0;
    private static final java.util.Map<String, Double> pidMinValues = new java.util.HashMap<>();
    private static final java.util.Map<String, Double> pidMaxValues = new java.util.HashMap<>();
    private static final java.util.Map<String, Double> pidSumValues = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> pidCountValues = new java.util.HashMap<>();
    private static final java.util.Map<String, FuelMapView.TrimData> sessionPetrolData = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, FuelMapView.TrimData> sessionLpgData = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<BluetoothDevice> bluetoothDevices = new ArrayList<>();
    private long lastSampleTimeMs = 0;

    private BaseDriver getActiveDriver() {
        BaseDriver cd = currentDriver;
        if (cd != null && cd.isConnected()) {
            return cd;
        }
        LoggerService s = LoggerService.getInstance();
        if (s != null) {
            BaseDriver sd = s.getDriver();
            if (sd != null && sd.isConnected()) {
                return sd;
            }
        }
        return null;
    }
    private int currentTabIndex = 0;

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_CODE = 1002;
    private LoggerConfig pendingBackgroundConfig = null;

    private androidx.activity.result.ActivityResultLauncher<Intent> folderPickerLauncher;
    private boolean pendingStartLoggingAfterFolderSelect = false;
    private static volatile boolean isConnecting = false;
    private static volatile boolean pendingStartLoggingAfterPermission = false;
    public static volatile boolean isPaused = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        activeInstance = this;
        try {
            DtcDatabase.init(this);
            DtcEnrichment.init(this);
            android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
            int currentTheme = prefs.getInt("app_theme", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(currentTheme);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            LoggerService.dtcClearTrigger = () -> {
                runOnUiThread(() -> clearDtcs());
                return true;
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                String lang = LocaleHelper.getLanguage(this);
                java.util.Locale locale;
                if (LocaleHelper.LANG_SYSTEM.equals(lang)) {
                    locale = android.content.res.Resources.getSystem().getConfiguration().locale;
                } else {
                    locale = new java.util.Locale(lang);
                }
                int direction = android.text.TextUtils.getLayoutDirectionFromLocale(locale);
                getWindow().getDecorView().setLayoutDirection(direction);
            }

            folderPickerLauncher = registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                                getContentResolver().takePersistableUriPermission(uri, takeFlags);
                                getSharedPreferences("OBD2Prefs", MODE_PRIVATE).edit().putString("custom_log_folder_uri", uri.toString()).apply();
                                updateCustomFolderText(uri);
                                if (pendingStartLoggingAfterFolderSelect) {
                                    pendingStartLoggingAfterFolderSelect = false;
                                    if (startLogging()) {
                                        setFabState(true);
                                    }
                                }
                            }
                        } else {
                            pendingStartLoggingAfterFolderSelect = false;
                        }
                    }
            );



            DriverFactory.setAppContext(this.getApplicationContext());
            LoggerService.setCallback(this);

            initViews();
            setupTabs();
            setupDashboard();
            setupGauges();
            setupGraphs();
            setupListeners();
            if (hasBluetoothPermissions()) {
                refreshBluetoothDevices();
            }
            requestNotificationPermission();

            bluetoothDeviceSpinner.setEnabled(false);
            bluetoothHintText.setEnabled(false);
            
            setupHomeMenu();
            onBackPressedCallback = new androidx.activity.OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    showTab(6);
                }
            };
            getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);

            if (savedInstanceState != null) {
                currentTabIndex = savedInstanceState.getInt("current_tab", 6);
                showTab(currentTabIndex);
            } else {
                showTab(6);
            }
            syncLoggerState();
        } catch (Throwable t) {
            try {
                File logFile = new File(getExternalFilesDir(null), "crash_log.txt");
                java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(logFile));
                t.printStackTrace(pw);
                pw.flush();
                pw.close();
                
                String errorMsg = t.toString() + (t.getCause() != null ? "\nCause: " + t.getCause().toString() : "");
                android.widget.Toast.makeText(this, "CRASH: " + errorMsg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception e) {}
            throw t;
        }
    }

    private static void runOnActiveActivity(Runnable action) {
        MainActivity active = activeInstance;
        if (active != null) {
            active.runOnUiThread(action);
        }
    }

    private void syncLoggerState() {
        if (!LoggerService.isLoggingActive() && (executor == null || executor.isShutdown())) {
            running = false;
        }
        if (LoggerService.isLoggingActive()) {
            running = true;
            LoggerService.setCallback(this);
            
            if (fabLog != null) {
                setFabState(true);
            }
            
            LoggerService service = LoggerService.getInstance();
            if (service != null) {
                LoggerConfig activeConfig = service.getConfig();
                if (activeConfig != null) {
                    lpgOnlyCheckbox.setChecked(activeConfig.lpgOnlyMode);
                    backgroundLoggingCheckbox.setChecked(true);
                    apiServerCheckbox.setChecked(activeConfig.enableApiServer);
                    if (fuelSpinner != null) {
                        fuelSpinner.setSelection(fuelModeToPosition(activeConfig.fuelMode));
                    }
                    if (transportSpinner != null) {
                        transportSpinner.setSelection(transportSpinnerToPosition(activeConfig.transportMode));
                    }
                    if (fuelMapView != null) {
                        fuelMapView.setMapMode(activeConfig.fuelMode.isGaseous()
                                ? FuelMapView.MapMode.LPG : FuelMapView.MapMode.PETROL);
                        com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
                        if (mapModeToggle != null) {
                            mapModeToggle.check(activeConfig.fuelMode.isGaseous() ? R.id.btnMapLpg : R.id.btnMapPetrol);
                        }
                    }
                }
                
                int count = service.getRecordCount();
                countText.setText("Records: " + count);
                setStatus("Background logging active...", R.color.accent);
                if (activeConfig != null) updateStatusStripConnection(2, "Connected " + activeConfig.transportMode.getValue());
                headerStatus.setText("Logging...");
            }

            if (fuelMapView != null) {
                fuelMapView.setPetrolData(sessionPetrolData);
                fuelMapView.setLpgData(sessionLpgData);
            }
            setConfigUiEnabled(false);
        } else if (running) {
            if (fabLog != null) {
                setFabState(true);
            }
            setStatus("Logging active...", R.color.accent);
            headerStatus.setText("Logging...");
            if (activeInProcessConfig != null) updateStatusStripConnection(2, "Connected " + activeInProcessConfig.transportMode.getValue());
            
            if (activeInProcessConfig != null) {
                lpgOnlyCheckbox.setChecked(activeInProcessConfig.lpgOnlyMode);
                backgroundLoggingCheckbox.setChecked(false);
                apiServerCheckbox.setChecked(activeInProcessConfig.enableApiServer);
                if (fuelSpinner != null) {
                    fuelSpinner.setSelection(fuelModeToPosition(activeInProcessConfig.fuelMode));
                }
                if (transportSpinner != null) {
                    transportSpinner.setSelection(transportSpinnerToPosition(activeInProcessConfig.transportMode));
                }
                if (fuelMapView != null) {
                    fuelMapView.setMapMode(activeInProcessConfig.fuelMode.isGaseous()
                            ? FuelMapView.MapMode.LPG : FuelMapView.MapMode.PETROL);
                    com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
                    if (mapModeToggle != null) {
                        mapModeToggle.check(activeInProcessConfig.fuelMode.isGaseous() ? R.id.btnMapLpg : R.id.btnMapPetrol);
                    }
                }
            }
            
            countText.setText("Records: " + sessionRecordCount);

            if (fuelMapView != null) {
                fuelMapView.setPetrolData(sessionPetrolData);
                fuelMapView.setLpgData(sessionLpgData);
            }
            setConfigUiEnabled(false);
        } else {
            setConfigUiEnabled(true);
        }
    }

    private int transportSpinnerToPosition(TransportMode mode) {
        if (mode == null) return 0;
        switch (mode) {
            case WIFI: return 1;
            case SERIAL: return 2;
            case BLE: return 3;
            case USB: return 4;
            case AUTO: return 5;
            case SIM:
            default: return 0;
        }
    }

    private void initViews() {
        // Header
        headerStatus = findViewById(R.id.headerStatus);
        headerVin = findViewById(R.id.headerVin);
        headerFuelMode = findViewById(R.id.headerFuelMode);
        headerApiStatus = findViewById(R.id.headerApiStatus);
        headerApiDivider = findViewById(R.id.headerApiDivider);
        headerStatusDot = findViewById(R.id.headerStatusDot);
        btnSettings = findViewById(R.id.btnSettings);
        btnGoHome = findViewById(R.id.btnGoHome);
        historyListViewPetrol = findViewById(R.id.historyListViewPetrol);
        historyListViewLpg = findViewById(R.id.historyListViewLpg);
        historyFolderText = findViewById(R.id.historyFolderText);
        
        historyListViewFolders = findViewById(R.id.historyListViewFolders);
        btnBackToFolders = findViewById(R.id.btnBackToFolders);
        vinFoldersHeader = findViewById(R.id.vinFoldersHeader);
        compareBarLayout = findViewById(R.id.compareBarLayout);
        petrolLogsHeader = findViewById(R.id.petrolLogsHeader);
        lpgLogsHeader = findViewById(R.id.lpgLogsHeader);

        if (btnBackToFolders != null) {
            btnBackToFolders.setOnClickListener(v -> {
                selectedVinFolder = null;
                compareMode = false;
                compareSelection.clear();
                loadHistoryFiles();
            });
        }
        
        // Status strip
        statusStrip = findViewById(R.id.statusStrip);
        statusDot = findViewById(R.id.statusDot);
        statusDeviceText = findViewById(R.id.statusDeviceText);
        stripRpm = findViewById(R.id.stripRpm);
        stripSpeed = findViewById(R.id.stripSpeed);
        stripVoltage = findViewById(R.id.stripVoltage);
        stripDtcBadge = findViewById(R.id.stripDtcBadge);
        // Status strip: tap to go to dashboard
        if (statusStrip != null) {
            statusStrip.setOnClickListener(v -> showTab(0));
        }
        // DTC badge: tap to go to DTC tab
        if (stripDtcBadge != null) {
            stripDtcBadge.setOnClickListener(v -> showTab(3));
        }
        topHeader = findViewById(R.id.topHeader);
        panelHome = findViewById(R.id.panelHome);
        txtHomeVin = findViewById(R.id.txtHomeVin);
        txtHomeVoltage = findViewById(R.id.txtHomeVoltage);
        txtHomeAdapter = findViewById(R.id.txtHomeAdapter);
        txtHomeProtocol = findViewById(R.id.txtHomeProtocol);
        txtHomeRpm = findViewById(R.id.txtHomeRpm);
        txtHomeSpeed = findViewById(R.id.txtHomeSpeed);
        txtHomeCoolant = findViewById(R.id.txtHomeCoolant);
        // Derived sensor displays
        txtHomeFuelEconomy = findViewById(R.id.txtHomeFuelEconomy);
        txtHomeBoost = findViewById(R.id.txtHomeBoost);
        txtHomeDpf = findViewById(R.id.txtHomeDpf);
        txtHomeDtc = findViewById(R.id.txtHomeDtc);
        stripBoost = findViewById(R.id.stripBoost);
        stripFuel = findViewById(R.id.stripFuel);

        com.google.android.material.button.MaterialButton btnHomeConnect = findViewById(R.id.btnHomeConnect);
        if (btnHomeConnect != null) {
            btnHomeConnect.setOnClickListener(v -> {
                if (running) {
                    stopLogging();
                    setFabState(false);
                } else {
                    if (isConnecting) return;
                    if (!ensureLogFolderSelected(true)) {
                        return;
                    }
                    if (startLogging()) {
                        setFabState(true);
                    }
                }
            });
        }
        panelDashboard = findViewById(R.id.panelDashboard);
        panelGauges = findViewById(R.id.panelGauges);
        View panelFuelMap = findViewById(R.id.panelFuelMap);
        panelDtc = findViewById(R.id.panelDtc);
        panelLogs = findViewById(R.id.panelLogs);
        panelSettings = findViewById(R.id.panelSettings);
        panelBattery = findViewById(R.id.panelBattery);

        // Battery tab
        batteryTestView = findViewById(R.id.batteryTestView);
        batteryVoltageText = findViewById(R.id.batteryVoltageText);
        batteryVoltageValueText = findViewById(R.id.batteryVoltageValueText);
        batteryVoltageStatusBadge = findViewById(R.id.batteryVoltageStatusBadge);
        batteryStatusText = findViewById(R.id.batteryStatusText);
        socValueText = findViewById(R.id.socValueText);
        socVoltageText = findViewById(R.id.socVoltageText);
        sohValueText = findViewById(R.id.sohValueText);
        sohGradeText = findViewById(R.id.sohGradeText);
        batteryGradeText = findViewById(R.id.batteryGradeText);
        batterySummaryText = findViewById(R.id.batterySummaryText);
        batteryLifeText = findViewById(R.id.batteryLifeText);
        batteryScoreCard = findViewById(R.id.batteryScoreCard);
        btnBatteryResting = findViewById(R.id.btnBatteryResting);
        btnBatteryAlternator = findViewById(R.id.btnBatteryAlternator);
        btnBatteryLoad = findViewById(R.id.btnBatteryLoad);
        btnBatteryCrank = findViewById(R.id.btnBatteryCrank);
        btnBatteryRipple = findViewById(R.id.btnBatteryRipple);
        btnBatteryFull = findViewById(R.id.btnBatteryFull);
        batteryTypeSpinner = findViewById(R.id.batteryTypeSpinner);
        batteryAgeInput = findViewById(R.id.batteryAgeInput);
        // Populate battery type dropdown from localized resources
        String[] batteryTypes = getResources().getStringArray(R.array.battery_chemistry_types);
        java.util.List<String> typeList = new java.util.ArrayList<>(java.util.Arrays.asList(batteryTypes));
        batteryTypeSpinner.setAdapter(new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, typeList));
        int savedChemIndex = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getInt("selected_battery_chemistry_index", 0);
        if (savedChemIndex >= 0 && savedChemIndex < batteryTypes.length) {
            batteryTypeSpinner.setText(batteryTypes[savedChemIndex], false);
        } else {
            batteryTypeSpinner.setText(batteryTypes[0], false);
        }
        batteryTypeSpinner.setOnItemClickListener((parent, view, position, id) -> {
            getSharedPreferences("OBD2Prefs", MODE_PRIVATE).edit().putInt("selected_battery_chemistry_index", position).apply();
            if (lastBatteryVoltage > 0) {
                updateBatteryMonitor(lastBatteryVoltage);
            }
            if (batteryScoreCard != null && batteryScoreCard.getVisibility() == View.VISIBLE) {
                runFullBatteryDiagnostic();
            }
        });

        // Settings
        languageSpinner = findViewById(R.id.languageSpinner);
        themeSpinner = findViewById(R.id.themeSpinner);
        transportSpinner = findViewById(R.id.transportSpinner);
        fuelSpinner = findViewById(R.id.fuelSpinner);
        obdProtocolSpinner = findViewById(R.id.obdProtocolSpinner);
        wifiIpInput = findViewById(R.id.wifiIpInput);
        wifiPortInput = findViewById(R.id.wifiPortInput);
        baudInput = findViewById(R.id.baudInput);
        intervalInput = findViewById(R.id.intervalInput);
        bluetoothDeviceSpinner = findViewById(R.id.bluetoothDeviceSpinner);
        bluetoothHintText = findViewById(R.id.bluetoothHintText);
        lpgOnlyCheckbox = findViewById(R.id.lpgOnlyCheckbox);
        backgroundLoggingCheckbox = findViewById(R.id.backgroundLoggingCheckbox);
        keepScreenOnCheckbox = findViewById(R.id.keepScreenOnCheckbox);
        apiServerCheckbox = findViewById(R.id.apiServerCheckbox);
        apiServerIpText = findViewById(R.id.apiServerIpText);
        fordMsCanCheckbox = findViewById(R.id.fordMsCanCheckbox);
        customLogFolderText = findViewById(R.id.customLogFolderText);

        // Feature toggle checkboxes
        turboBoostCheckbox = findViewById(R.id.turboBoostCheckbox);
        fuelEconomyCheckbox = findViewById(R.id.fuelEconomyCheckbox);
        dpfMonitorCheckbox = findViewById(R.id.dpfMonitorCheckbox);
        customPidCheckbox = findViewById(R.id.customPidCheckbox);
        airDensityCheckbox = findViewById(R.id.airDensityCheckbox);

        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);

        // ── Instant-save on toggle (no need to wait for onPause) ──
        if (turboBoostCheckbox != null) {
            turboBoostCheckbox.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("pref_turbo_boost", checked).apply());
        }
        if (fuelEconomyCheckbox != null) {
            fuelEconomyCheckbox.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("pref_fuel_economy", checked).apply());
        }
        if (dpfMonitorCheckbox != null) {
            dpfMonitorCheckbox.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("pref_dpf_monitor", checked).apply());
        }
        if (customPidCheckbox != null) {
            customPidCheckbox.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("pref_custom_pid", checked).apply());
        }
        if (fordMsCanCheckbox != null) {
            fordMsCanCheckbox.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("pref_ford_ms_can", checked).apply());
        }
        if (backgroundLoggingCheckbox != null) {
            backgroundLoggingCheckbox.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    requestBatteryOptimizationExemption();
                }
            });
        }
        boolean isApiServerEnabled = prefs.getBoolean("apiServerEnabled", false);
        apiServerCheckbox.setChecked(isApiServerEnabled);
        
        apiServerCheckbox.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("apiServerEnabled", isChecked).apply();
            updateApiServerIpText(isChecked);
        });
        updateApiServerIpText(isApiServerEnabled);
        
        btnSelectLogFolder = findViewById(R.id.btnSelectLogFolder);
        TextView appVersionText = findViewById(R.id.appVersionText);
        TextView homeVersionText = findViewById(R.id.homeVersionText);
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            String versionString = "Version " + versionName;
            if (appVersionText != null) {
                appVersionText.setText(versionString);
            }
            if (homeVersionText != null) {
                homeVersionText.setText(versionString);
            }
        } catch (Exception e) {
            if (appVersionText != null) {
                appVersionText.setText("Version 3.2.1");
            }
            if (homeVersionText != null) {
                homeVersionText.setText("Version 3.2.1");
            }
        }

        // Dashboard
        fabLog = findViewById(R.id.fabLog);
        fabLog.setOnClickListener(v -> {
            if (running) {
                stopLogging();
                setFabState(false);
            } else {
                if (isConnecting) return;
                if (!ensureLogFolderSelected(true)) {
                    return;
                }
                if (startLogging()) {
                    setFabState(true);
                }
            }
        });
        
        dashTuningStatus = findViewById(R.id.dashTuningStatus);
        dashEctText = findViewById(R.id.dashEctText);
        dashWarmupProgress = findViewById(R.id.dashWarmupProgress);

        // Air Density Panel
        airDensityCard = findViewById(R.id.airDensityCard);
        txtAAD = findViewById(R.id.txtAAD);
        txtMAD = findViewById(R.id.txtMAD);
        txtBAD = findViewById(R.id.txtBAD);
        txtDensityPct = findViewById(R.id.txtDensityPct);
        txtDensityAlt = findViewById(R.id.txtDensityAlt);
        txtSAECF = findViewById(R.id.txtSAECF);
        txtOMD = findViewById(R.id.txtOMD);
        txtCompEff = findViewById(R.id.txtCompEff);
        txtICEff = findViewById(R.id.txtICEff);
        txtVE = findViewById(R.id.txtVE);
        txtPDI = findViewById(R.id.txtPDI);
        txtGrains = findViewById(R.id.txtGrains);
        txtAirDensityWeather = findViewById(R.id.txtAirDensityWeather);

        fuelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FuelMode mode = fuelPositionToMode(position);
                applyFuelTheme(mode);
                // Update header fuel mode badge
                if (headerFuelMode != null) {
                    headerFuelMode.setText(mode.name());
                }
                getSharedPreferences("OBD2Prefs", MODE_PRIVATE).edit().putInt("pref_fuel_position", position).apply();
                // Propagate fuel mode change to the running logger so
                // subsequent records route to the correct map layer.
                LoggerService svc = LoggerService.getInstance();
                if (svc != null) {
                    LoggerConfig cfg = svc.getConfig();
                    if (cfg != null) cfg.fuelMode = mode;
                }
                if (activeInProcessConfig != null) {
                    activeInProcessConfig.fuelMode = mode;
                }
                // Update fuel map mode to match
                if (fuelMapView != null) {
                    fuelMapView.setMapMode(mode.isGaseous()
                            ? FuelMapView.MapMode.LPG : FuelMapView.MapMode.PETROL);
                    com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
                    if (mapModeToggle != null) {
                        mapModeToggle.check(mode.isGaseous() ? R.id.btnMapLpg : R.id.btnMapPetrol);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        fuelSpinner.setSelection(2); // Default to Petrol (position 2) on startup

        // ── Live PID Filter button ──
        if (btnFilterPids != null) {
            btnFilterPids.setOnClickListener(v -> showPidFilterDialog());
        }

        statusText = findViewById(R.id.statusText);
        countText = findViewById(R.id.countText);
        
        dashTitle1 = findViewById(R.id.dashTitle1);
        dashValue1 = findViewById(R.id.dashValue1);
        dashTitle2 = findViewById(R.id.dashTitle2);
        dashValue2 = findViewById(R.id.dashValue2);
        dashTitle3 = findViewById(R.id.dashTitle3);
        dashValue3 = findViewById(R.id.dashValue3);
        dashTitle4 = findViewById(R.id.dashTitle4);
        dashValue4 = findViewById(R.id.dashValue4);
        
        gauge1 = findViewById(R.id.gauge1);
        gauge2 = findViewById(R.id.gauge2);
        gauge3 = findViewById(R.id.gauge3);
        gauge4 = findViewById(R.id.gauge4);
        
        tuningStatusText = findViewById(R.id.tuningStatusText);
        mapEctText = findViewById(R.id.mapEctText);
        mapLoopStatusText = findViewById(R.id.mapLoopStatusText);
        txtTuningStft = findViewById(R.id.txtTuningStft);
        txtTuningLtft = findViewById(R.id.txtTuningLtft);
        txtTuningStatus = findViewById(R.id.txtTuningStatus);
        txtTuningAdvice = findViewById(R.id.txtTuningAdvice);

        // Gauges tab
        graph1 = findViewById(R.id.graph1);
        graph2 = findViewById(R.id.graph2);
        graph3 = findViewById(R.id.graph3);
        graph4 = findViewById(R.id.graph4);
        graph5 = findViewById(R.id.graph5);
        gaugeReadingsContainer = findViewById(R.id.gaugeReadingsContainer);

        // DTC tab
        btnReadDtc = findViewById(R.id.btnReadDtc);
        btnClearDtc = findViewById(R.id.btnClearDtc);
        btnReadVin = findViewById(R.id.btnReadVin);
        btnReadiness = findViewById(R.id.btnReadiness);
        dtcStatusText = findViewById(R.id.dtcStatusText);
        dtcListContainer = findViewById(R.id.dtcListContainer);
        readinessContainer = findViewById(R.id.readinessContainer);

        // Logs tab
        txtSessionDuration = findViewById(R.id.txtSessionDuration);
        txtSessionRecords = findViewById(R.id.txtSessionRecords);
        txtSessionRate = findViewById(R.id.txtSessionRate);
        sessionStatusText = findViewById(R.id.sessionStatusText);
        sessionStatusDotCard = findViewById(R.id.sessionStatusDotCard);
        readingsContainer = findViewById(R.id.readingsContainer);
        btnFilterPids = findViewById(R.id.btnFilterPids);
        txtFilterStatus = findViewById(R.id.txtFilterStatus);
        
        fuelMapView = findViewById(R.id.fuelMapView);
        
        com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
        View btnExportCsv = findViewById(R.id.btnExportCsv);
        mapModeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked && fuelMapView != null) {
                if (checkedId == R.id.btnMapPetrol) fuelMapView.setMapMode(FuelMapView.MapMode.PETROL);
                else if (checkedId == R.id.btnMapLpg) fuelMapView.setMapMode(FuelMapView.MapMode.LPG);
                else if (checkedId == R.id.btnMapDiff) fuelMapView.setMapMode(FuelMapView.MapMode.DEVIATION);
                else if (checkedId == R.id.btnMapCorrection) fuelMapView.setMapMode(FuelMapView.MapMode.CORRECTION);
                
                if (btnExportCsv != null) {
                    btnExportCsv.setVisibility(checkedId == R.id.btnMapCorrection ? View.VISIBLE : View.GONE);
                }
            }
        });
        
        if (btnExportCsv != null) {
            btnExportCsv.setOnClickListener(v -> exportCorrectionCsv());
        }
        
        View btnMapInfo = findViewById(R.id.btnMapInfo);
        if (btnMapInfo != null) {
            btnMapInfo.setOnClickListener(v -> showMapInfoDialog());
            // Long-press resets the ENTIRE fuel map (both Petrol + LPG) to start a
            // fresh Tune Assist comparison. Normal Start only clears the fuel being
            // logged, so this is the only way to wipe both at once.
            btnMapInfo.setOnLongClickListener(v -> {
                if (fuelMapView == null) return false;
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Reset Fuel Map")
                    .setMessage("Clear ALL Petrol and LPG map data? This starts a fresh Tune Assist comparison and cannot be undone.")
                    .setPositiveButton("Clear All", (d, w) -> {
                        fuelMapView.clearData();
                        setStatus("Fuel map cleared.", R.color.muted);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            });
        }
        


        btnCompareLogs = findViewById(R.id.btnCompareLogs);
        compareHintText = findViewById(R.id.compareHintText);
        if (btnCompareLogs != null) {
            btnCompareLogs.setOnClickListener(v -> toggleCompareMode());
        }
        
        setupDynamicPids();
    }
    

    
    private void showMapInfoDialog() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_map_info, null);
        View closeBtn = view.findViewById(R.id.btnMapInfoClose);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.setContentView(view);
        dialog.show();
    }
    
    private void applyFuelTheme(FuelMode mode) {
        // Multi-fuel color theme: each fuel type has its own color
        int primaryColor;
        int accentColor;
        String label;
        switch (mode) {
            case LPG:
                primaryColor = 0xFFF59E0B; accentColor = 0xFFD97706; label = "LPG";
                break;
            case NGV:
                primaryColor = 0xFF10B981; accentColor = 0xFF059669; label = "NGV";
                break;
            case E20:
                primaryColor = 0xFF8B5CF6; accentColor = 0xFF7C3AED; label = "E20";
                break;
            case E85:
                primaryColor = 0xFFEC4899; accentColor = 0xFFDB2777; label = "E85";
                break;
            case DIESEL:
                primaryColor = 0xFF6B7280; accentColor = 0xFF4B5563; label = "Diesel";
                break;
            case B20:
                primaryColor = 0xFF059669; accentColor = 0xFF047857; label = "B20";
                break;
            case PETROL_91:
                primaryColor = 0xFF6366F1; accentColor = 0xFF4F46E5; label = "G91";
                break;
            case PETROL:
            default:
                primaryColor = 0xFF38BDF8; accentColor = 0xFF0284C7; label = "G95";
                break;
        }

        if (headerFuelMode != null) {
            headerFuelMode.setText(label);
            headerFuelMode.setTextColor(primaryColor);
            headerFuelMode.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primaryColor & 0x20FFFFFF));
        }

        if (fabLog != null) {
            setFabState(running);
        }
        
        // Update graphs to match theme
        GraphView[] graphs = {graph1, graph2, graph3, graph4, graph5};
        for (GraphView graph : graphs) {
            if (graph != null) {
                graph.setLineColor(primaryColor);
            }
        }
    }

    private String[] prefGaugePids = new String[]{"01_0C", "01_0D", "01_05", "01_04"};
    private String[] prefDashPids = new String[]{"01_06", "01_14", "01_0E", "01_0F"};
    private String[] prefGraphPids = new String[]{"01_0C", "01_0D", "01_06", "01_05", "01_04"};

    /** Map spinner position → FuelMode (must match arrays.xml fuel_modes order) */
    private static FuelMode fuelPositionToMode(int position) {
        switch (position) {
            case 0:  return FuelMode.LPG;
            case 1:  return FuelMode.NGV;
            case 2:  return FuelMode.PETROL;      // Petrol 95
            case 3:  return FuelMode.PETROL_91;   // Gasohol 91
            case 4:  return FuelMode.E20;
            case 5:  return FuelMode.E85;
            case 6:  return FuelMode.DIESEL;      // Diesel B7
            case 7:  return FuelMode.B20;
            default: return FuelMode.PETROL;
        }
    }

    /** Map FuelMode → spinner position (inverse of fuelPositionToMode) */
    private static int fuelModeToPosition(FuelMode mode) {
        if (mode == null) return 2; // default petrol
        switch (mode) {
            case LPG:       return 0;
            case NGV:       return 1;
            case PETROL:    return 2;
            case PETROL_91: return 3;
            case E20:       return 4;
            case E85:       return 5;
            case DIESEL:    return 6;
            case B20:       return 7;
            default:        return 2;
        }
    }

    private void setupDynamicPids() {
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        
        for (int i=0; i<4; i++) prefGaugePids[i] = prefs.getString("gauge_" + i, prefGaugePids[i]);
        for (int i=0; i<4; i++) prefDashPids[i] = prefs.getString("dash_" + i, prefDashPids[i]);
        for (int i=0; i<5; i++) prefGraphPids[i] = prefs.getString("graph_" + i, prefGraphPids[i]);
        // Load PID filter for Live Readings
        hideDerivedSensors = prefs.getBoolean("pref_hide_derived", true);
        java.util.Set<String> savedFilter = prefs.getStringSet("pref_pid_filter", null);
        if (savedFilter != null && !savedFilter.isEmpty()) {
            visiblePidsFilter = new java.util.HashSet<>(savedFilter);
        } else {
            visiblePidsFilter = new java.util.HashSet<>();
        }
        updateFilterStatusText();
        
        // Setup Long Click Listeners
        View[] gaugeCards = {findViewById(R.id.gaugeCard1), findViewById(R.id.gaugeCard2), findViewById(R.id.gaugeCard3), findViewById(R.id.gaugeCard4)};
        View[] dashCards = {findViewById(R.id.dashCard1), findViewById(R.id.dashCard2), findViewById(R.id.dashCard3), findViewById(R.id.dashCard4)};
        View[] graphCards = {findViewById(R.id.graphCard1), findViewById(R.id.graphCard2), findViewById(R.id.graphCard3), findViewById(R.id.graphCard4), findViewById(R.id.graphCard5)};
        
        for (int i=0; i<4; i++) {
            final int idx = i;
            if (gaugeCards[i] != null) {
                gaugeCards[i].setOnClickListener(v -> showPidSelectionDialog("gauge_" + idx, prefGaugePids, idx, this::setupGauges));
                gaugeCards[i].setOnLongClickListener(v -> { showPidSelectionDialog("gauge_" + idx, prefGaugePids, idx, this::setupGauges); return true; });
            }
            if (dashCards[i] != null) {
                dashCards[i].setOnClickListener(v -> showPidSelectionDialog("dash_" + idx, prefDashPids, idx, this::setupDashboard));
                dashCards[i].setOnLongClickListener(v -> { showPidSelectionDialog("dash_" + idx, prefDashPids, idx, this::setupDashboard); return true; });
            }
        }
        for (int i=0; i<5; i++) {
            final int idx = i;
            if (graphCards[i] != null) {
                graphCards[i].setOnClickListener(v -> showPidSelectionDialog("graph_" + idx, prefGraphPids, idx, this::setupGraphs));
                graphCards[i].setOnLongClickListener(v -> { showPidSelectionDialog("graph_" + idx, prefGraphPids, idx, this::setupGraphs); return true; });
            }
        }
    }

    private void showPidSelectionDialog(String prefKey, String[] array, int index, Runnable onUpdated) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = 
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        
        // Root Layout
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.surface));
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        
        // Header Text
        android.widget.TextView titleText = new android.widget.TextView(this);
        titleText.setText("เลือก OBD2 Parameter");
        titleText.setTextColor(getColorCompat(R.color.text));
        titleText.setTextSize(18);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleText.setPadding(0, 0, 0, (int)(4 * getResources().getDisplayMetrics().density));
        root.addView(titleText);

        android.widget.TextView subTitleText = new android.widget.TextView(this);
        subTitleText.setText("แตะเพื่อแสดงผลในสล็อตที่ " + (index + 1) + " หรือเลือกซ่อนตัวเลือก");
        subTitleText.setTextColor(getColorCompat(R.color.muted));
        subTitleText.setTextSize(12);
        subTitleText.setPadding(0, 0, 0, (int)(12 * getResources().getDisplayMetrics().density));
        root.addView(subTitleText);

        // Search EditText
        android.widget.EditText searchBar = new android.widget.EditText(this);
        searchBar.setHint("ค้นหา Parameter... (Search)");
        searchBar.setHintTextColor(getColorCompat(R.color.muted));
        searchBar.setTextColor(getColorCompat(R.color.text));
        
        android.graphics.drawable.GradientDrawable searchGd = new android.graphics.drawable.GradientDrawable();
        searchGd.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        searchGd.setColor(getColorCompat(R.color.surface3));
        searchBar.setBackground(searchGd);
        
        searchBar.setTextSize(14);
        searchBar.setPadding(padding, padding / 2, padding, padding / 2);
        searchBar.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams searchParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(0, 0, 0, (int)(12 * getResources().getDisplayMetrics().density));
        searchBar.setLayoutParams(searchParams);
        root.addView(searchBar);

        // List Container
        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(android.R.color.transparent);
        android.widget.LinearLayout.LayoutParams listParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (350 * getResources().getDisplayMetrics().density));
        listView.setLayoutParams(listParams);
        root.addView(listView);

        // Populate items
        java.util.List<PIDDefinition> allPids = PIDCatalogue.getAll();
        java.util.List<Object> items = new java.util.ArrayList<>();
        items.add("none");
        items.addAll(allPids);

        // Custom Adapter with filter
        class PidAdapter extends android.widget.BaseAdapter {
            private java.util.List<Object> filteredItems = new java.util.ArrayList<>(items);
            
            public void filter(String text) {
                filteredItems.clear();
                if (text.isEmpty()) {
                    filteredItems.addAll(items);
                } else {
                    String query = text.toLowerCase(java.util.Locale.getDefault());
                    filteredItems.add("none");
                    for (PIDDefinition p : allPids) {
                        if (p.getName().toLowerCase(java.util.Locale.getDefault()).contains(query) ||
                            p.key().toLowerCase(java.util.Locale.getDefault()).contains(query)) {
                            filteredItems.add(p);
                        }
                    }
                }
                notifyDataSetChanged();
            }

            @Override
            public int getCount() { return filteredItems.size(); }
            @Override
            public Object getItem(int pos) { return filteredItems.get(pos); }
            @Override
            public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, android.view.ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    android.widget.LinearLayout row = new android.widget.LinearLayout(MainActivity.this);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    int itemPadding = (int) (10 * getResources().getDisplayMetrics().density);
                    row.setPadding(itemPadding, itemPadding, itemPadding, itemPadding);
                    
                    android.util.TypedValue outValue = new android.util.TypedValue();
                    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                    row.setBackgroundResource(outValue.resourceId);
                    
                    android.widget.TextView badge = new android.widget.TextView(MainActivity.this);
                    badge.setId(View.generateViewId());
                    badge.setTextSize(10);
                    badge.setTypeface(null, android.graphics.Typeface.BOLD);
                    badge.setGravity(android.view.Gravity.CENTER);
                    badge.setTextColor(0xFFFFFFFF);
                    android.widget.LinearLayout.LayoutParams badgeParams = new android.widget.LinearLayout.LayoutParams(
                            (int)(36 * getResources().getDisplayMetrics().density), 
                            (int)(36 * getResources().getDisplayMetrics().density));
                    badgeParams.setMargins(0, 0, (int)(12 * getResources().getDisplayMetrics().density), 0);
                    badge.setLayoutParams(badgeParams);
                    row.addView(badge);

                    android.widget.LinearLayout texts = new android.widget.LinearLayout(MainActivity.this);
                    texts.setOrientation(android.widget.LinearLayout.VERTICAL);
                    texts.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                    
                    android.widget.TextView mainText = new android.widget.TextView(MainActivity.this);
                    mainText.setId(View.generateViewId());
                    mainText.setTextColor(getColorCompat(R.color.text));
                    mainText.setTextSize(14);
                    mainText.setTypeface(null, android.graphics.Typeface.BOLD);
                    texts.addView(mainText);

                    android.widget.TextView subText = new android.widget.TextView(MainActivity.this);
                    subText.setId(View.generateViewId());
                    subText.setTextColor(getColorCompat(R.color.muted));
                    subText.setTextSize(11);
                    texts.addView(subText);

                    row.addView(texts);
                    view = row;
                }

                Object item = filteredItems.get(pos);
                android.widget.TextView badge = (android.widget.TextView) ((android.view.ViewGroup)view).getChildAt(0);
                android.view.ViewGroup textContainer = (android.view.ViewGroup) ((android.view.ViewGroup)view).getChildAt(1);
                android.widget.TextView mainText = (android.widget.TextView) textContainer.getChildAt(0);
                android.widget.TextView subText = (android.widget.TextView) textContainer.getChildAt(1);

                float density = getResources().getDisplayMetrics().density;
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setCornerRadius(18 * density); // circular shape

                if (item instanceof String && "none".equals(item)) {
                    mainText.setText("ซ่อนฟิลด์นี้ (Hide / Disable)");
                    subText.setText("ไม่ต้องการแสดงข้อมูลในสล็อตนี้");
                    badge.setText("OFF");
                    gd.setColor(0xFFEF4444);
                } else {
                    PIDDefinition p = (PIDDefinition) item;
                    mainText.setText(p.getName());
                    subText.setText("PID: " + p.key() + "  •  Unit: " + (p.getUnit().isEmpty() ? "None" : p.getUnit()));
                    
                    String name = p.getName().toUpperCase();
                    String badgeStr = name.length() > 3 ? name.substring(0, 3) : name;
                    badge.setText(badgeStr);
                    
                    if (p.key().contains("0C") || p.key().contains("0D")) {
                        gd.setColor(0xFF38BDF8); // Blue
                    } else if (p.key().contains("05") || p.key().contains("0F")) {
                        gd.setColor(0xFFF59E0B); // Amber
                    } else if (p.key().contains("06") || p.key().contains("07")) {
                        gd.setColor(0xFF22C55E); // Green
                    } else {
                        gd.setColor(0xFF8B5CF6); // Purple
                    }
                }
                badge.setBackground(gd);
                return view;
            }
        }

        PidAdapter adapter = new PidAdapter();
        listView.setAdapter(adapter);

        searchBar.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            Object item = adapter.filteredItems.get(pos);
            String selectedKey;
            if (item instanceof String && "none".equals(item)) {
                selectedKey = "none";
            } else {
                selectedKey = ((PIDDefinition) item).key();
            }
            array[index] = selectedKey;
            getSharedPreferences("OBD2Prefs", MODE_PRIVATE).edit().putString(prefKey, selectedKey).apply();
            onUpdated.run();
            dialog.dismiss();
        });

        dialog.setContentView(root);
        dialog.show();
    }
    
    private void exportCorrectionCsv() {
        if (fuelMapView == null) return;
        
        if (!fuelMapView.hasAnyCorrection()) {
            android.widget.Toast.makeText(this, "No correction data yet. Need both Petrol & LPG data in the same RPM/MAP cells.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        
        String csvData = fuelMapView.exportCorrectionMapCsv();
        
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "Tuning");
            if (!dir.exists()) dir.mkdirs();
            
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
            java.io.File csvFile = new java.io.File(dir, "CorrectionMap_" + timestamp + ".csv");
            
            try (java.io.FileWriter writer = new java.io.FileWriter(csvFile)) {
                writer.write(csvData);
            }
            
            android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".fileprovider", csvFile);
            
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(android.content.Intent.createChooser(shareIntent, "Share Correction CSV"));
            
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to export CSV", e);
            android.widget.Toast.makeText(this, "Failed to export CSV: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void setupDashboard() {
        TextView[] titles = {dashTitle1, dashTitle2, dashTitle3, dashTitle4};
        TextView[] values = {dashValue1, dashValue2, dashValue3, dashValue4};
        for (int i=0; i<4; i++) {
            if (titles[i] == null || values[i] == null) continue;
            String pidKey = prefDashPids[i];
            if ("none".equalsIgnoreCase(pidKey)) {
                titles[i].setText("แตะเพื่อเพิ่ม (Tap to Add)");
                titles[i].setTextColor(getColorCompat(R.color.muted));
                values[i].setText("+");
                values[i].setTextColor(getColorCompat(R.color.muted));
            } else {
                PIDDefinition pid = PIDDefinition.findByKey(pidKey);
                if (pid != null) {
                    titles[i].setText(pid.getName());
                    titles[i].setTextColor(getColorCompat(R.color.text));
                    values[i].setText("—");
                    values[i].setTextColor(getColorCompat(R.color.text));
                }
            }
        }
    }

    private void updateApiServerIpText(boolean isEnabled) {
        if (headerApiStatus != null) {
            if (!isEnabled) {
                headerApiStatus.setVisibility(View.GONE);
                if (headerApiDivider != null) headerApiDivider.setVisibility(View.GONE);
            } else {
                headerApiStatus.setVisibility(View.VISIBLE);
                if (headerApiDivider != null) headerApiDivider.setVisibility(View.VISIBLE);
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                String ipString = "localhost";
                if (wm != null) {
                    int ip = wm.getConnectionInfo().getIpAddress();
                    if (ip != 0) {
                        ipString = String.format(java.util.Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
                headerApiStatus.setText("API: " + ipString + ":8080");
                headerApiStatus.setTextColor(0xFF34D399); // Neon emerald green
                headerApiStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x2034D399));
            }
        }

        if (!isEnabled) {
            apiServerIpText.setVisibility(View.GONE);
            return;
        }
        apiServerIpText.setVisibility(View.VISIBLE);
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                String ipString = String.format(java.util.Locale.US, "%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                apiServerIpText.setText("URL: http://" + ipString + ":8080/api/data");
                return;
            }
        }
        apiServerIpText.setText("URL: http://localhost:8080/api/data");
    }

    /**
     * Update the Start/Stop logging button appearance.
     * @param isLogging true = show STOP (red), false = show START (primary)
     */
    private void setFabState(boolean isLogging) {
        if (fabLog == null) return;
        com.google.android.material.button.MaterialButton btnHomeConnect = findViewById(R.id.btnHomeConnect);
        if (isLogging) {
            fabLog.setText("STOP");
            fabLog.setIconResource(android.R.drawable.ic_media_pause);
            fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.danger)));
            if (btnHomeConnect != null) {
                btnHomeConnect.setText("DISCONNECT ECU");
                btnHomeConnect.setIconResource(android.R.drawable.ic_media_pause);
                btnHomeConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.danger)));
            }
        } else {
            fabLog.setText("START");
            fabLog.setIconResource(android.R.drawable.ic_media_play);
            fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
            if (btnHomeConnect != null) {
                btnHomeConnect.setText("CONNECT & TUNE");
                btnHomeConnect.setIconResource(android.R.drawable.ic_media_play);
                btnHomeConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
            }
        }
    }

    private void setupTabs() {
        // Navigation is now home-card-only (no bottom nav bar).
        // The bottom status strip shows live data, not navigation.
    }

    private void showTab(int index) {
        if (isTabChanging) return;
        isTabChanging = true;
        try {
            currentTabIndex = index;
            if (panelHome != null) {
                panelHome.setVisibility(index == 6 ? View.VISIBLE : View.GONE);
            }
            panelDashboard.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
            panelGauges.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
            findViewById(R.id.panelFuelMap).setVisibility(index == 2 ? View.VISIBLE : View.GONE);
            panelDtc.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
            panelLogs.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
            panelSettings.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
            if (panelBattery != null) {
                panelBattery.setVisibility(index == 7 ? View.VISIBLE : View.GONE);
            }
            
            if (onBackPressedCallback != null) {
                onBackPressedCallback.setEnabled(index != 6);
            }

            if (btnGoHome != null) {
                btnGoHome.setVisibility(index == 6 ? View.GONE : View.VISIBLE);
            }

            // Status strip: hide on home screen for a cleaner look, show on all other tabs
            if (statusStrip != null) {
                statusStrip.setVisibility(index == 6 ? View.GONE : View.VISIBLE);
            }
            
            if (index == 4) {
                if (!ensureLogFolderSelected(false)) {
                    isTabChanging = false;
                    showTab(6);
                    return;
                }
                loadHistoryFiles();
            }
        } finally {
            isTabChanging = false;
        }
    }

    private void setupHomeMenu() {
        if (btnGoHome != null) {
            btnGoHome.setOnClickListener(v -> showTab(6));
        }
        
        View cardDashboard = findViewById(R.id.cardHomeDashboard);
        if (cardDashboard != null) {
            cardDashboard.setOnClickListener(v -> showTab(0));
        }
        View cardGauges = findViewById(R.id.cardHomeGauges);
        if (cardGauges != null) {
            cardGauges.setOnClickListener(v -> showTab(1));
        }
        View cardMap = findViewById(R.id.cardHomeMap);
        if (cardMap != null) {
            cardMap.setOnClickListener(v -> showTab(2));
        }
        View cardDtc = findViewById(R.id.cardHomeDtc);
        if (cardDtc != null) {
            cardDtc.setOnClickListener(v -> showTab(3));
        }
        View cardLogs = findViewById(R.id.cardHomeLogs);
        if (cardLogs != null) {
            cardLogs.setOnClickListener(v -> showTab(4));
        }
        View cardBattery = findViewById(R.id.cardHomeBattery);
        if (cardBattery != null) {
            cardBattery.setOnClickListener(v -> showTab(7));
        }
    }

    private void setupGauges() {
        GaugeView[] gauges = {gauge1, gauge2, gauge3, gauge4};
        // Per-gauge color themes: {arc, needle, warning}
        // Gauge 0=RPM(red), 1=Speed(cyan), 2=Temp(amber), 3=Load(green)
        int[][] gaugeThemes = {
            {0xFFFF2A55, 0xFFFF2A55, 0xFFFF0000}, // RPM — red
            {0xFF00E5FF, 0xFF00E5FF, 0xFFFF2A55}, // Speed — cyan
            {0xFFFFD600, 0xFFFFD600, 0xFFFF2A55}, // Temp — amber
            {0xFF00FFA3, 0xFF00FFA3, 0xFFFFD600}, // Load — green
        };
        for (int i=0; i<4; i++) {
            if (gauges[i] == null) continue;
            String pidKey = prefGaugePids[i];
            if ("none".equalsIgnoreCase(pidKey)) {
                gauges[i].setRange(0f, 100f);
                gauges[i].setLabel("แตะเพื่อเพิ่ม (Tap to Add)");
                gauges[i].setUnit("+");
                gauges[i].setValue(0f);
            } else {
                PIDDefinition pid = PIDDefinition.findByKey(pidKey);
                if (pid != null) {
                    gauges[i].setRange((float)pid.getMinVal(), (float)pid.getMaxVal());
                    gauges[i].setLabel(pid.getName());
                    gauges[i].setUnit(pid.getUnit());
                    int[] theme = gaugeThemes[i];
                    gauges[i].setFullColors(theme[0], theme[1], theme[2]);
                    // Set warning at 80% of max for RPM, 90% for others
                    gauges[i].setWarningStart(i == 0 ? 0.75f : 0.85f);
                }
            }
        }
    }

    private void setupGraphs() {
        GraphView[] graphs = {graph1, graph2, graph3, graph4, graph5};
        int[] colors = {0xFF38BDF8, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444, 0xFFA78BFA};
        for (int i=0; i<5; i++) {
            if (graphs[i] == null) continue;
            String pidKey = prefGraphPids[i];
            if ("none".equalsIgnoreCase(pidKey)) {
                graphs[i].setLabel("แตะเพื่อเพิ่ม (Tap to Add)", "+");
                graphs[i].setRange(0f, 100f);
            } else {
                PIDDefinition pid = PIDDefinition.findByKey(pidKey);
                if (pid != null) {
                    graphs[i].setLabel(pid.getName(), pid.getUnit());
                    graphs[i].setRange((float)pid.getMinVal(), (float)pid.getMaxVal());
                    graphs[i].setLineColor(colors[i]);
                }
            }
        }
    }

    private void setupListeners() {
        // Init language spinner
        final String[] langCodes = {
            LocaleHelper.LANG_SYSTEM,
            LocaleHelper.LANG_ENGLISH,
            LocaleHelper.LANG_THAI
        };
        String currentLang = LocaleHelper.getLanguage(this);
        int langIndex = 0; // Default to System Default
        for (int i = 0; i < langCodes.length; i++) {
            if (langCodes[i].equals(currentLang)) {
                langIndex = i;
                break;
            }
        }
        languageSpinner.setSelection(langIndex);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLang = langCodes[position];
                if (!selectedLang.equals(LocaleHelper.getLanguage(MainActivity.this))) {
                    LocaleHelper.setLocale(MainActivity.this, selectedLang);
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        final android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        int currentTheme = prefs.getInt("app_theme", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);



        if (currentTheme == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
            themeSpinner.setSelection(1);
        } else if (currentTheme == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            themeSpinner.setSelection(2);
        } else {
            themeSpinner.setSelection(0);
        }
        
        themeSpinner.post(() -> {
            themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                    if (position == 1) mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
                    else if (position == 2) mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
                    
                    if (prefs.getInt("app_theme", -1) != mode) {
                        prefs.edit().putInt("app_theme", mode).commit();
                        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode);
                        recreate(); // Force recreate so it applies immediately and reliably
                    }


                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        });

        transportSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean needsBluetooth = position == 2 || position == 3 || position == 4;
                bluetoothDeviceSpinner.setEnabled(needsBluetooth);
                bluetoothHintText.setEnabled(needsBluetooth);
                if (needsBluetooth && ensureBluetoothPermissions()) {
                    refreshBluetoothDevices();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Header
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showTab(5));
        }

        // DTC
        btnReadDtc.setOnClickListener(v -> readDtcs());
        btnReadDtc.setOnLongClickListener(v -> {
            readDtcsDeep();
            return true;
        });
        btnClearDtc.setOnClickListener(v -> clearDtcs());
        btnReadVin.setOnClickListener(v -> readVin());
        btnReadVin.setOnLongClickListener(v -> {
            showScanHistoryDialog();
            return true;
        });
        btnReadiness.setOnClickListener(v -> checkReadiness());

        // Battery tester buttons
        if (btnBatteryResting != null) btnBatteryResting.setOnClickListener(v -> testBatteryResting());
        if (btnBatteryAlternator != null) btnBatteryAlternator.setOnClickListener(v -> testBatteryAlternator());
        if (btnBatteryLoad != null) btnBatteryLoad.setOnClickListener(v -> testBatteryLoadDrop());
        if (btnBatteryCrank != null) btnBatteryCrank.setOnClickListener(v -> testBatteryCranking());
        if (btnBatteryRipple != null) btnBatteryRipple.setOnClickListener(v -> testBatteryRipple());
        if (btnBatteryFull != null) btnBatteryFull.setOnClickListener(v -> runFullBatteryDiagnostic());

        // Logs tab sub-navigation toggle listener
        com.google.android.material.button.MaterialButtonToggleGroup logsTabToggle = findViewById(R.id.logsTabToggle);
        final View layoutLogsLive = findViewById(R.id.layoutLogsLive);
        final View layoutLogsHistory = findViewById(R.id.layoutLogsHistory);
        if (logsTabToggle != null && layoutLogsLive != null && layoutLogsHistory != null) {
            logsTabToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (isChecked) {
                    layoutLogsLive.setVisibility(checkedId == R.id.btnTabLive ? View.VISIBLE : View.GONE);
                    layoutLogsHistory.setVisibility(checkedId == R.id.btnTabHistory ? View.VISIBLE : View.GONE);
                }
            });
        }



        // Keep screen on
        // Restore the saved preference (default = true) and apply the flag immediately,
        // because setOnCheckedChangeListener only fires on a *change*, not on startup —
        // without this the screen would still sleep even though the box shows as checked.
        android.content.SharedPreferences screenPrefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        boolean keepScreenOn = screenPrefs.getBoolean("keepScreenOn", true);
        keepScreenOnCheckbox.setChecked(keepScreenOn);
        applyKeepScreenOn(keepScreenOn);

        keepScreenOnCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            applyKeepScreenOn(isChecked);
            getSharedPreferences("OBD2Prefs", MODE_PRIVATE)
                    .edit().putBoolean("keepScreenOn", isChecked).apply();
        });
        
        btnSelectLogFolder.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            folderPickerLauncher.launch(intent);
        });
        
        String savedUriStr = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getString("custom_log_folder_uri", null);
        if (savedUriStr != null) {
            updateCustomFolderText(Uri.parse(savedUriStr));
        }
    }

    private class HistoryLogFile {
        Uri uri;
        String name;
        long date;
        boolean isSaf;
        androidx.documentfile.provider.DocumentFile df;
        boolean isMediaStore;
        boolean isFile;
        File file;
        String vin;

        boolean delete(Context context) {
            if (isSaf && df != null) {
                return df.delete();
            } else if (isMediaStore) {
                return context.getContentResolver().delete(uri, null, null) > 0;
            } else if (isFile && file != null) {
                return file.delete();
            }
            return false;
        }
    }

    private void loadHistoryFiles() {
        String savedUriStr = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getString("custom_log_folder_uri", null);
        List<HistoryLogFile> logFiles = new ArrayList<>();

        if (savedUriStr != null) {
            Uri treeUri = Uri.parse(savedUriStr);
            androidx.documentfile.provider.DocumentFile df = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
            if (df != null && df.exists()) {
                historyFolderText.setText("Folder: " + df.getName());
                scanSafFolderRecursively(df, "General", logFiles);
            } else {
                historyFolderText.setText("Cannot access custom folder. Using default.");
                loadDefaultHistory(logFiles);
            }
        } else {
            historyFolderText.setText("Default Folder (Downloads/TunerMapPro)");
            loadDefaultHistory(logFiles);
        }

        java.util.Collections.sort(logFiles, (f1, f2) -> Long.compare(f2.date, f1.date));
        currentLogFiles = logFiles;
        
        // Clean up compareSelection to remove any Uris that no longer exist in logFiles.
        java.util.HashSet<Uri> allUris = new java.util.HashSet<>();
        for (HistoryLogFile f : logFiles) {
            allUris.add(f.uri);
        }
        compareSelection.retainAll(allUris);

        // Group log files by VIN
        java.util.Map<String, java.util.List<HistoryLogFile>> groups = new java.util.HashMap<>();
        for (HistoryLogFile f : logFiles) {
            String v = f.vin == null || f.vin.trim().isEmpty() ? "General" : f.vin;
            if (!groups.containsKey(v)) {
                groups.put(v, new java.util.ArrayList<>());
            }
            groups.get(v).add(f);
        }

        if (selectedVinFolder == null) {
            // Folders view
            if (btnBackToFolders != null) btnBackToFolders.setVisibility(View.GONE);
            if (vinFoldersHeader != null) vinFoldersHeader.setVisibility(View.VISIBLE);
            if (historyListViewFolders != null) historyListViewFolders.setVisibility(View.VISIBLE);
            if (compareBarLayout != null) compareBarLayout.setVisibility(View.GONE);
            if (petrolLogsHeader != null) petrolLogsHeader.setVisibility(View.GONE);
            if (historyListViewPetrol != null) historyListViewPetrol.setVisibility(View.GONE);
            if (lpgLogsHeader != null) lpgLogsHeader.setVisibility(View.GONE);
            if (historyListViewLpg != null) historyListViewLpg.setVisibility(View.GONE);

            List<String> vinKeys = new ArrayList<>(groups.keySet());
            // Sort VIN folders: General/No VIN folder should go last, others alphabetically
            java.util.Collections.sort(vinKeys, (k1, k2) -> {
                if ("General".equalsIgnoreCase(k1)) return 1;
                if ("General".equalsIgnoreCase(k2)) return -1;
                return k1.compareToIgnoreCase(k2);
            });

            if (historyListViewFolders != null) {
                historyListViewFolders.setAdapter(new android.widget.BaseAdapter() {
                    @Override
                    public int getCount() {
                        return vinKeys.size();
                    }

                    @Override
                    public Object getItem(int position) {
                        return vinKeys.get(position);
                    }

                    @Override
                    public long getItemId(int position) {
                        return position;
                    }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View view = convertView;
                        if (view == null) {
                            view = getLayoutInflater().inflate(R.layout.list_item_folder, parent, false);
                        }
                        String vinKey = vinKeys.get(position);
                        TextView nameText = view.findViewById(R.id.folderNameText);
                        TextView countText = view.findViewById(R.id.folderCountText);
                        android.widget.ImageButton btnDeleteFolder = view.findViewById(R.id.btnDeleteFolder);
                        
                        nameText.setText("General".equalsIgnoreCase(vinKey) ? "General / No VIN" : "VIN: " + vinKey);
                        int count = groups.get(vinKey).size();
                        countText.setText(count + " log file" + (count > 1 ? "s" : ""));

                        if (btnDeleteFolder != null) {
                            btnDeleteFolder.setOnClickListener(v -> deleteVinFolder(vinKey, groups.get(vinKey)));
                        }
                        return view;
                    }
                });

                historyListViewFolders.setOnItemClickListener((parent, view, position, id) -> {
                    selectedVinFolder = vinKeys.get(position);
                    loadHistoryFiles();
                });
                setListViewHeightBasedOnChildren(historyListViewFolders);
            }
        } else {
            // Files list view inside the selected VIN folder
            if (btnBackToFolders != null) {
                btnBackToFolders.setVisibility(View.VISIBLE);
                btnBackToFolders.setText("Back to Vehicles (" + ("General".equalsIgnoreCase(selectedVinFolder) ? "General" : selectedVinFolder) + ")");
            }
            if (vinFoldersHeader != null) vinFoldersHeader.setVisibility(View.GONE);
            if (historyListViewFolders != null) historyListViewFolders.setVisibility(View.GONE);
            if (compareBarLayout != null) compareBarLayout.setVisibility(View.VISIBLE);
            if (petrolLogsHeader != null) petrolLogsHeader.setVisibility(View.VISIBLE);
            if (historyListViewPetrol != null) historyListViewPetrol.setVisibility(View.VISIBLE);
            if (lpgLogsHeader != null) lpgLogsHeader.setVisibility(View.VISIBLE);
            if (historyListViewLpg != null) historyListViewLpg.setVisibility(View.VISIBLE);

            List<HistoryLogFile> filteredFiles = groups.get(selectedVinFolder);
            if (filteredFiles == null) {
                filteredFiles = new ArrayList<>();
            }

            List<HistoryLogFile> petrolFiles = new ArrayList<>();
            List<HistoryLogFile> lpgFiles = new ArrayList<>();
            for (HistoryLogFile f : filteredFiles) {
                if (f.name != null && f.name.toUpperCase().startsWith("PETROL")) {
                    petrolFiles.add(f);
                } else {
                    lpgFiles.add(f);
                }
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

            historyListViewPetrol.setAdapter(new android.widget.BaseAdapter() {
                @Override
                public int getCount() {
                    return petrolFiles.size();
                }

                @Override
                public Object getItem(int position) {
                    return petrolFiles.get(position);
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View view = convertView;
                    if (view == null) {
                        view = getLayoutInflater().inflate(R.layout.list_item_history, parent, false);
                    }

                    HistoryLogFile item = petrolFiles.get(position);
                    TextView nameText = view.findViewById(R.id.logNameText);
                    TextView dateText = view.findViewById(R.id.logDateText);
                    View actionLayout = view.findViewById(R.id.actionLayout);
                    android.widget.ImageButton btnShare = view.findViewById(R.id.btnShare);
                    android.widget.ImageButton btnDelete = view.findViewById(R.id.btnDelete);
                    CheckBox compareCheckbox = view.findViewById(R.id.compareCheckbox);

                    nameText.setText(item.name);
                    long size = 0;
                    if (item.isSaf && item.df != null) {
                        size = item.df.length();
                    } else if (item.isFile && item.file != null) {
                        size = item.file.length();
                    }
                    String sizeText = size > 1024 * 1024
                        ? String.format(java.util.Locale.US, "%.2f MB", size / (1024f * 1024f))
                        : (size / 1024) + " KB";
                    dateText.setText(sdf.format(new Date(item.date)) + "  •  " + sizeText);

                    if (compareMode) {
                        actionLayout.setVisibility(View.GONE);
                        compareCheckbox.setVisibility(View.VISIBLE);
                        compareCheckbox.setChecked(compareSelection.contains(item.uri));
                    } else {
                        actionLayout.setVisibility(View.VISIBLE);
                        compareCheckbox.setVisibility(View.GONE);

                        btnShare.setOnClickListener(v -> shareLogFile(item));
                        btnDelete.setOnClickListener(v -> deleteLogFile(item));
                    }

                    return view;
                }
            });

            historyListViewLpg.setAdapter(new android.widget.BaseAdapter() {
                @Override
                public int getCount() {
                    return lpgFiles.size();
                }

                @Override
                public Object getItem(int position) {
                    return lpgFiles.get(position);
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View view = convertView;
                    if (view == null) {
                        view = getLayoutInflater().inflate(R.layout.list_item_history, parent, false);
                    }

                    HistoryLogFile item = lpgFiles.get(position);
                    TextView nameText = view.findViewById(R.id.logNameText);
                    TextView dateText = view.findViewById(R.id.logDateText);
                    View actionLayout = view.findViewById(R.id.actionLayout);
                    android.widget.ImageButton btnShare = view.findViewById(R.id.btnShare);
                    android.widget.ImageButton btnDelete = view.findViewById(R.id.btnDelete);
                    CheckBox compareCheckbox = view.findViewById(R.id.compareCheckbox);

                    nameText.setText(item.name);
                    long size = 0;
                    if (item.isSaf && item.df != null) {
                        size = item.df.length();
                    } else if (item.isFile && item.file != null) {
                        size = item.file.length();
                    }
                    String sizeText = size > 1024 * 1024
                        ? String.format(java.util.Locale.US, "%.2f MB", size / (1024f * 1024f))
                        : (size / 1024) + " KB";
                    dateText.setText(sdf.format(new Date(item.date)) + "  •  " + sizeText);

                    if (compareMode) {
                        actionLayout.setVisibility(View.GONE);
                        compareCheckbox.setVisibility(View.VISIBLE);
                        compareCheckbox.setChecked(compareSelection.contains(item.uri));
                    } else {
                        actionLayout.setVisibility(View.VISIBLE);
                        compareCheckbox.setVisibility(View.GONE);

                        btnShare.setOnClickListener(v -> shareLogFile(item));
                        btnDelete.setOnClickListener(v -> deleteLogFile(item));
                    }

                    return view;
                }
            });

            historyListViewPetrol.setOnItemClickListener((parent, view, position, id) -> {
                HistoryLogFile selectedFile = petrolFiles.get(position);
                if (compareMode) {
                    handleCompareClick(selectedFile.uri);
                    return;
                }
                openReviewActivity(selectedFile);
            });

            historyListViewLpg.setOnItemClickListener((parent, view, position, id) -> {
                HistoryLogFile selectedFile = lpgFiles.get(position);
                if (compareMode) {
                    handleCompareClick(selectedFile.uri);
                    return;
                }
                openReviewActivity(selectedFile);
            });

            setListViewHeightBasedOnChildren(historyListViewPetrol);
            setListViewHeightBasedOnChildren(historyListViewLpg);
        }
        updateCompareUi();
    }

    private void openReviewActivity(HistoryLogFile selectedFile) {
        Intent intent = new Intent(this, ReviewSessionActivity.class);
        intent.setData(selectedFile.uri);
        intent.putExtra("file_name", selectedFile.name);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private void shareLogFile(HistoryLogFile selectedFile) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/csv");
        shareIntent.putExtra(Intent.EXTRA_STREAM, selectedFile.uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Share Log File"));
    }

    private void deleteLogFile(HistoryLogFile selectedFile) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Confirm Delete")
            .setMessage("Are you sure you want to delete this log?")
            .setPositiveButton("Delete", (d, w) -> {
                if (selectedFile.delete(this)) {
                    Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show();
                    
                    // Clean up parent directory if it becomes empty
                    if (selectedFile.isFile && selectedFile.file != null) {
                        File parent = selectedFile.file.getParentFile();
                        if (parent != null && parent.isDirectory()) {
                            File[] children = parent.listFiles();
                            if (children == null || children.length == 0) {
                                parent.delete();
                            }
                        }
                    } else if (selectedFile.isSaf && selectedFile.vin != null && !"General".equals(selectedFile.vin)) {
                        try {
                            String savedUriStr = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getString("custom_log_folder_uri", null);
                            if (savedUriStr != null) {
                                Uri treeUri = Uri.parse(savedUriStr);
                                androidx.documentfile.provider.DocumentFile tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
                                if (tree != null && tree.exists()) {
                                    androidx.documentfile.provider.DocumentFile sub = tree.findFile(selectedFile.vin);
                                    if (sub != null && sub.isDirectory()) {
                                        androidx.documentfile.provider.DocumentFile[] children = sub.listFiles();
                                        if (children == null || children.length == 0) {
                                            sub.delete();
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    
                    loadHistoryFiles();
                } else {
                    Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteVinFolder(String vinKey, List<HistoryLogFile> files) {
        if (files == null || files.isEmpty()) return;
        String titleName = "General".equalsIgnoreCase(vinKey) ? "General / No VIN" : "VIN: " + vinKey;
        new android.app.AlertDialog.Builder(this)
            .setTitle("Confirm Delete Folder")
            .setMessage("Are you sure you want to delete folder \"" + titleName + "\" and all of its " + files.size() + " logs?")
            .setPositiveButton("Delete", (d, w) -> {
                boolean allSuccess = true;
                for (HistoryLogFile f : files) {
                    if (!f.delete(this)) {
                        allSuccess = false;
                    }
                }
                
                // Also clean up physical empty folder if applicable
                HistoryLogFile firstFile = files.get(0);
                if (firstFile.isFile && firstFile.file != null) {
                    File parent = firstFile.file.getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        parent.delete();
                    }
                } else if (firstFile.isSaf && firstFile.vin != null && !"General".equals(firstFile.vin)) {
                    try {
                        String savedUriStr = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getString("custom_log_folder_uri", null);
                        if (savedUriStr != null) {
                            Uri treeUri = Uri.parse(savedUriStr);
                            androidx.documentfile.provider.DocumentFile tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri);
                            if (tree != null && tree.exists()) {
                                androidx.documentfile.provider.DocumentFile sub = tree.findFile(firstFile.vin);
                                if (sub != null && sub.isDirectory()) {
                                    sub.delete();
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (allSuccess) {
                    Toast.makeText(this, "Folder and logs deleted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to delete some logs", Toast.LENGTH_SHORT).show();
                }
                loadHistoryFiles();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Measures every row of the adapter and sets the ListView's height to the
     * total. Required when a ListView lives inside a ScrollView: the ScrollView
     * gives the ListView an unbounded height during measurement, so without this
     * the ListView collapses to (or clips at) a fixed height and its own internal
     * scroll fights the parent ScrollView — making older rows unreachable.
     */
    private void setListViewHeightBasedOnChildren(android.widget.ListView listView) {
        android.widget.ListAdapter adapter = listView.getAdapter();
        if (adapter == null) return;
        int width = listView.getWidth();
        if (width <= 0) {
            // Fallback: estimate width using screen width minus padding
            int screenWidth = listView.getResources().getDisplayMetrics().widthPixels;
            float density = listView.getResources().getDisplayMetrics().density;
            width = screenWidth - (int) (32 * density); // 16dp padding on each side
            // Schedule precise layout recalculation once the view is attached and laid out
            listView.post(() -> setListViewHeightBasedOnChildren(listView));
        }
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST);
        int totalHeight = 0;
        View view = null;
        for (int i = 0; i < adapter.getCount(); i++) {
            view = adapter.getView(i, view, listView);
            view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            totalHeight += view.getMeasuredHeight();
        }
        int dividerHeight = listView.getDividerHeight() * Math.max(adapter.getCount() - 1, 0);
        android.view.ViewGroup.LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight + dividerHeight;
        listView.setLayoutParams(params);
        listView.requestLayout();
    }

    /** Toggle the compare (multi-select) mode and rebuild the history list. */
    private void toggleCompareMode() {
        compareMode = !compareMode;
        compareSelection.clear();
        loadHistoryFiles();
    }

    /** Handle a tap on a row while in compare mode (enforce max 2 selections). */
    private void handleCompareClick(Uri uri) {
        if (compareSelection.contains(uri)) {
            compareSelection.remove(uri);
        } else {
            if (compareSelection.size() >= 2) {
                Toast.makeText(this, R.string.compare_max_two, Toast.LENGTH_SHORT).show();
                return;
            }
            compareSelection.add(uri);
        }
        if (historyListViewPetrol.getAdapter() instanceof android.widget.BaseAdapter) {
            ((android.widget.BaseAdapter) historyListViewPetrol.getAdapter()).notifyDataSetChanged();
        }
        if (historyListViewLpg.getAdapter() instanceof android.widget.BaseAdapter) {
            ((android.widget.BaseAdapter) historyListViewLpg.getAdapter()).notifyDataSetChanged();
        }
        updateCompareUi();
    }

    /** Refresh the compare button label and the hint/count text. */
    private void updateCompareUi() {
        if (btnCompareLogs == null || compareHintText == null) return;
        if (!compareMode) {
            btnCompareLogs.setText(R.string.compare_logs);
            compareHintText.setVisibility(View.GONE);
            btnCompareLogs.setOnClickListener(v -> toggleCompareMode());
            return;
        }
        compareHintText.setVisibility(View.VISIBLE);
        int n = compareSelection.size();
        if (n == 0) {
            btnCompareLogs.setText(R.string.compare_logs_cancel);
            compareHintText.setText(R.string.compare_hint_select);
        } else {
            // With >=1 selected, the button doubles as "Compare now".
            btnCompareLogs.setText(R.string.compare_logs);
            compareHintText.setText(getString(R.string.compare_hint_count, n));
        }
        // Re-wire the button: while comparing, pressing it launches the comparison
        // if files are selected, otherwise it cancels compare mode.
        btnCompareLogs.setOnClickListener(v -> {
            if (compareMode && !compareSelection.isEmpty()) {
                launchCompare();
            } else {
                toggleCompareMode();
            }
        });
    }

    /** Build a file_uris list from the selected rows and open ReviewSessionActivity. */
    private void launchCompare() {
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>(compareSelection);
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.compare_need_two, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Uri uri : uris) {
            String filename = "Log File";
            for (HistoryLogFile f : currentLogFiles) {
                if (f.uri.equals(uri)) {
                    filename = f.name;
                    break;
                }
            }
            if (names.length() > 0) names.append(" vs ");
            names.append(filename);
        }
        Intent intent = new Intent(this, ReviewSessionActivity.class);
        intent.putParcelableArrayListExtra("file_uris", uris);
        intent.putExtra("file_name", names.toString());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private void loadDefaultHistory(List<HistoryLogFile> logFiles) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = new String[] {
                android.provider.MediaStore.Downloads._ID,
                android.provider.MediaStore.Downloads.DISPLAY_NAME,
                android.provider.MediaStore.Downloads.DATE_MODIFIED,
                android.provider.MediaStore.Downloads.RELATIVE_PATH
            };
            String selection = "(" + android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ? OR " +
                               android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ?) AND " +
                               android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = new String[] { "%TunerMapPro%", "%OBD2LPGLogger%", "%.csv" };

            try (android.database.Cursor cursor = getContentResolver().query(collection, projection, selection, selectionArgs, null)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME);
                    int dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DATE_MODIFIED);
                    int relPathCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.RELATIVE_PATH);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String name = cursor.getString(nameCol);
                        if (name != null && name.startsWith("CorrectionMap_")) continue;
                        long date = cursor.getLong(dateCol) * 1000L;
                        String relPath = cursor.getString(relPathCol);
                        Uri contentUri = android.content.ContentUris.withAppendedId(collection, id);
                        
                        String vin = "General";
                        if (relPath != null) {
                            String cleanPath = relPath.replace("Download/TunerMapPro/", "")
                                                      .replace("Downloads/TunerMapPro/", "")
                                                      .replace("Download/TunerMapPro", "")
                                                      .replace("Downloads/TunerMapPro", "")
                                                      .replace("Download/OBD2LPGLogger/", "")
                                                      .replace("Downloads/OBD2LPGLogger/", "")
                                                      .replace("Download/OBD2LPGLogger", "")
                                                      .replace("Downloads/OBD2LPGLogger", "");
                            if (cleanPath.startsWith("/")) {
                                cleanPath = cleanPath.substring(1);
                            }
                            if (cleanPath.endsWith("/")) {
                                cleanPath = cleanPath.substring(0, cleanPath.length() - 1);
                            }
                            cleanPath = cleanPath.trim();
                            if (!cleanPath.isEmpty()) {
                                vin = cleanPath;
                            }
                        }

                        HistoryLogFile hlf = new HistoryLogFile();
                        hlf.uri = contentUri;
                        hlf.name = name;
                        hlf.date = date;
                        hlf.isMediaStore = true;
                        hlf.vin = vin;
                        logFiles.add(hlf);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TunerMapPro");
            if (folder.exists()) {
                scanFolderRecursively(folder, "General", logFiles);
            }
        }
    }

    private void scanFolderRecursively(File dir, String currentVin, List<HistoryLogFile> logFiles) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    scanFolderRecursively(f, f.getName(), logFiles);
                } else if (f.getName().endsWith(".csv") && !f.getName().startsWith("CorrectionMap_")) {
                    HistoryLogFile hlf = new HistoryLogFile();
                    hlf.uri = androidx.core.content.FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".fileprovider", f);
                    hlf.name = f.getName();
                    hlf.date = f.lastModified();
                    hlf.isFile = true;
                    hlf.file = f;
                    hlf.vin = currentVin;
                    logFiles.add(hlf);
                }
            }
        }
    }

    private void scanSafFolderRecursively(androidx.documentfile.provider.DocumentFile dir, String currentVin, List<HistoryLogFile> logFiles) {
        for (androidx.documentfile.provider.DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) {
                scanSafFolderRecursively(f, f.getName(), logFiles);
            } else if (f.getName() != null && f.getName().endsWith(".csv") && !f.getName().startsWith("CorrectionMap_")) {
                HistoryLogFile hlf = new HistoryLogFile();
                hlf.uri = f.getUri();
                hlf.name = f.getName();
                hlf.date = f.lastModified();
                hlf.isSaf = true;
                hlf.df = f;
                hlf.vin = currentVin;
                logFiles.add(hlf);
            }
        }
    }

    private void updateCustomFolderText(Uri uri) {
        try {
            androidx.documentfile.provider.DocumentFile df = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri);
            if (df != null && df.getName() != null) {
                customLogFolderText.setText(df.getName());
            } else {
                customLogFolderText.setText(uri.getLastPathSegment());
            }
        } catch (Exception e) {
            customLogFolderText.setText(getString(R.string.default_log_folder));
        }
    }

    // --- Logging ---

    private boolean startLogging() {
        if (!ensureTransportPermissions()) return false;
        isConnecting = true;

        LoggerConfig config = readConfigFromUi();

        if (config.transportMode == TransportMode.USB || config.transportMode == TransportMode.AUTO) {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            if (!availableDrivers.isEmpty()) {
                UsbSerialDriver driver = availableDrivers.get(0);
                if (!manager.hasPermission(driver.getDevice())) {
                    PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent("com.alpha.obd2logger.USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE);
                    manager.requestPermission(driver.getDevice(), pi);
                    setStatus("Requesting USB permission...", R.color.warning);
                    return true;
                }
            } else if (config.transportMode == TransportMode.USB) {
                isConnecting = false;
                setStatus("No USB Serial device found. Please plug in vLinker.", R.color.danger);
                return false;
            }
        }


        loggingStartTime = SystemClock.elapsedRealtime();
        updateSessionStatus(true);
        clearReadings();
        resetGraphs();
        lastSampleTimeMs = 0;
        setStatus("Connecting via " + config.transportMode.getValue() + "...", R.color.accent);
        headerStatus.setText("Connecting...");
        updateStatusStripConnection(1, "Connecting " + config.transportMode.getValue() + "...");
        countText.setText("Records: 0");
        TextView[] values = {dashValue1, dashValue2, dashValue3, dashValue4};
        for (int i=0; i<4; i++) {
            if (values[i] != null) values[i].setText("—");
        }

        // Sync FuelMapView mode with selected fuel mode so data shows immediately.
        // IMPORTANT: only clear the fuel we're about to (re)log — NOT the whole map.
        // The Tune Assist / Deviation workflow logs Petrol, then switches to LPG and
        // logs again to compare. Wiping the entire map on every Start would erase the
        // Petrol data the moment LPG logging begins, leaving Deviation/Correction
        // permanently empty. clearData(fuelMode) preserves the comparison fuel.
        if (fuelMapView != null) {
            fuelMapView.clearData(config.fuelMode);
            if (config.fuelMode.isGaseous()) {
                sessionLpgData.clear();
            } else {
                sessionPetrolData.clear();
            }
            fuelMapView.setMapMode(config.fuelMode.isGaseous()
                    ? FuelMapView.MapMode.LPG : FuelMapView.MapMode.PETROL);
            // Also sync the toggle button in the Map tab
            com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
            if (mapModeToggle != null) {
                mapModeToggle.check(config.fuelMode.isGaseous() ? R.id.btnMapLpg : R.id.btnMapPetrol);
            }
        }

        setConfigUiEnabled(false);
        watchdogHandler.removeCallbacks(connectionWatchdog);
        watchdogHandler.postDelayed(connectionWatchdog, 20000);

        if (backgroundLoggingCheckbox.isChecked()) {
            startBackgroundLogging(config);
        } else {
            startInProcessLogging(config);
        }
        return true;
    }

    private void startInProcessLogging(LoggerConfig config) {
        running = true;
        activeInProcessConfig = config;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> runLogger(config));
    }

    private void startBackgroundLogging(LoggerConfig config) {
        // On Android 13+ (API 33) a foreground service needs the runtime
        // POST_NOTIFICATIONS permission before startForeground() is called,
        // otherwise startForeground() throws SecurityException on the system
        // binder thread and the whole app crashes. Gate on it and defer the
        // actual start until the user grants it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            pendingBackgroundConfig = config;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_CODE);
            Toast.makeText(this, "Grant notification permission to enable background logging",
                    Toast.LENGTH_LONG).show();
            return;
        }
        actuallyStartBackgroundLogging(config);
    }

    private void actuallyStartBackgroundLogging(LoggerConfig config) {
        running = true;
        LoggerService.setCallback(this);
        LoggerService.setPendingConfig(config);
        Intent intent = new Intent(this, LoggerService.class);
        intent.setAction(LoggerService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to start foreground service", e);
            isConnecting = false;
            running = false;
            watchdogHandler.removeCallbacks(connectionWatchdog);
            setFabState(false);
            setConfigUiEnabled(true);
            updateStatusStripConnection(0, "Service failed");
            if (headerStatus != null) headerStatus.setText("Service failed");
            setStatus("FGS failed: " + e.getMessage(), R.color.danger);
        }
    }

    private void stopLogging() {
        isConnecting = false;
        running = false;
        watchdogHandler.removeCallbacks(connectionWatchdog);
        pendingBackgroundConfig = null; // cancel any deferred (permission-gated) start
        // Don't call currentDriver.disconnect() from the main thread — the
        // logger thread performs disconnect() in its finally block. Calling it
        // here too races with that thread and can double-free native resources
        // (GATT handles, BluetoothSocket, UsbSerialPort). Setting running=false
        // and shutdownNow() is enough to make the logger thread exit and clean up.

        if (backgroundLoggingCheckbox.isChecked() || LoggerService.isLoggingActive()) {
            Intent intent = new Intent(this, LoggerService.class);
            intent.setAction(LoggerService.ACTION_STOP);
            startService(intent);
            LoggerService.setCallback(null);
        }

        ExecutorService service = executor;
        if (service != null) {
            service.shutdownNow();
            executor = null;
        }
        // Note: dtcExecutor is intentionally NOT shut down here — stopping logging
        // must not kill in-flight DTC/VIN/readiness reads. It is shut down in onDestroy().
        headerStatus.setText("Disconnected");
        updateStatusStripConnection(0, "Disconnected");
        updateSessionStatus(false);
        setConfigUiEnabled(true);
    }

    private void runLogger(LoggerConfig config) {
        String fuelPrefix = config.fuelMode != null ? config.fuelMode.name() + "_" : "";
        String simPrefix = (config.transportMode == TransportMode.SIM) ? "Sim_" : "";
        String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String sessionId = simPrefix + fuelPrefix + timeStr;
        BaseDriver driver = DriverFactory.create(config);
        currentDriver = driver;

        if (!driver.isConnected() && !driver.connect()) {
            running = false;
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) {
                    active.isConnecting = false;
                    active.running = false;
                    active.setStatus("Connection failed. Check settings and adapter.", R.color.danger);
                    active.headerStatus.setText("Connection failed");
                    active.updateStatusStripConnection(0, "Connection failed");
                    if (active.fabLog != null) {
                        active.setFabState(false);
                    }
                    active.setConfigUiEnabled(true);
                }
            });
            return;
        }

        if (config.transportMode == TransportMode.AUTO && driver instanceof SimulationDriver) {
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) active.setStatus("Auto probe failed — running simulation.", R.color.warning);
            });
        } else {
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) {
                    active.isConnecting = false;
                    active.setStatus("Connected. Logging started.", R.color.accent);
                }
            });
        }
        runOnActiveActivity(() -> {
            MainActivity active = activeInstance;
            if (active != null) active.headerStatus.setText("Connected: " + config.transportMode.getValue());
            if (active != null) active.updateStatusStripConnection(2, "Connected " + config.transportMode.getValue());
        });

        // Try to read VIN
        String vin = VinReader.readVin(driver);
        if (vin != null) {
            config.vin = vin;
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) {
                    active.headerVin.setText("VIN: " + vin);
                    if (active.txtHomeVin != null) active.txtHomeVin.setText(vin);
                }
            });
        }

        DataWriter writer = null;
        int completed = 0;
        long started = SystemClock.elapsedRealtime();
        List<PIDDefinition> allPids = config.lpgOnlyMode ? PIDCatalogue.getLpgPollSet() : PIDCatalogue.getAll();
        List<PIDDefinition> pids = new ArrayList<>(allPids);
        boolean detectedFromLive = false;

        // --- Auto-detect supported PIDs ---
        if (driver instanceof SimulationDriver) {
            List<String> supportedHex = PidAvailabilityChecker.querySupportedPids(driver);
            if (supportedHex != null) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                detectedFromLive = true;
            }
        } else if (driver instanceof ElmDriver) {
            List<String> supportedHex = getCachedPids(config.vin);
            if (supportedHex != null && !supportedHex.isEmpty()) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                detectedFromLive = true;
                Log.i("OBD2Logger", "Cached PIDs loaded for VIN " + config.vin + ": " + pids.size() + " PIDs");
            } else {
                runOnActiveActivity(() -> {
                    MainActivity active = activeInstance;
                    if (active != null) active.setStatus("Detecting supported PIDs...", R.color.accent);
                });
                supportedHex = PidAvailabilityChecker.querySupportedPids(driver);
                if (supportedHex != null && !supportedHex.isEmpty()) {
                    pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                    detectedFromLive = true;
                    cachePids(config.vin, supportedHex);
                    Log.i("OBD2Logger", "Live detection: " + pids.size() + "/" + allPids.size() + " PIDs supported");
                } else {
                    // Fallback: use VIN-based brand/year profile
                    Log.w("OBD2Logger", "Live PID detection failed — trying VIN-based profile");
                    java.util.Set<String> brandPids = BrandYearProfile.getProfileFromVin(config.vin);
                    if (brandPids != null) {
                        pids = PidAvailabilityChecker.filterCatalogue(
                                new ArrayList<>(brandPids), allPids);
                        Log.i("OBD2Logger", "VIN profile: " + pids.size() + "/" + allPids.size() + " PIDs");
                    }
                }
            }
            final int detectedCount = pids.size();
            final int totalCount = allPids.size();
            final boolean fromLiveFinal = detectedFromLive;
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) {
                    String msg = fromLiveFinal
                            ? detectedCount + "/" + totalCount + " PIDs detected (live query)"
                            : detectedCount + "/" + totalCount + " PIDs (VIN profile fallback)";
                    active.setStatus(msg, R.color.accent);
                }
            });
        }

        final List<PIDDefinition> finalPids = pids;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        final java.util.Map<String, Integer> consecutiveFailures = new java.util.HashMap<>();

        try {
            writer = new DataWriter(this, sessionId, finalPids, config.vin);
            currentWriter = writer;
            final DataWriter dataWriter = writer;
            currentDownloadFolder = dataWriter.getDownloadFolderFile();
            currentCsvUri = dataWriter.getCsvUri();
            currentJsonlUri = dataWriter.getJsonlUri();
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) {
                    active.updateSessionStatus(true);
                }
            });

            // Initialize Air Density Monitor for in-process logging
            if (config.showAirDensity) {
                try {
                    airDensityMonitor = new AirDensityMonitor(this);
                    airDensityMonitor.refreshWeatherSync();
                    Log.i("OBD2Logger", "AirDensityMonitor initialized (in-process)");
                } catch (Exception e) {
                    Log.w("OBD2Logger", "AirDensityMonitor init failed — using defaults", e);
                }
            }

            int retryCount = 0;
            int maxRetries = 3;

            while (running) {
                try {
                    if (!driver.isConnected()) {
                        if (retryCount > 0) {
                            final int finalRetry = retryCount;
                            runOnActiveActivity(() -> {
                                MainActivity active = activeInstance;
                                if (active != null) {
                                    active.setStatus("Connection lost. Reconnecting (" + finalRetry + "/" + maxRetries + ")...", R.color.warning);
                                    active.updateStatusStripConnection(1, "Reconnecting...");
                                }
                            });
                        }
                        if (!driver.connect()) {
                            throw new java.io.IOException("Reconnection failed");
                        }
                        retryCount = 0;
                        runOnActiveActivity(() -> {
                            MainActivity active = activeInstance;
                            if (active != null) {
                                active.setStatus("Connected. Logging resumed.", R.color.accent);
                                active.headerStatus.setText("Connected: " + config.transportMode.getValue());
                                active.updateStatusStripConnection(2, "Connected " + config.transportMode.getValue());
                            }
                        });
                    }

                    while (running) {
                        if (isPaused) {
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                            continue;
                        }
                        Map<String, Double> batch = driver.queryPidBatch(finalPids);
                        List<SensorSample> samples = new ArrayList<>();
                        List<PIDDefinition> toRemove = new ArrayList<>();

                        for (PIDDefinition pid : finalPids) {
                            Double value = batch.get(pid.getName());
                            samples.add(new SensorSample(pid.key(), pid.getName(), value, pid.getUnit(),
                                    value == null ? "err" : "ok"));

                            if (value == null) {
                                int fails = consecutiveFailures.getOrDefault(pid.key(), 0) + 1;
                                consecutiveFailures.put(pid.key(), fails);
                                if (fails >= 3) {
                                    toRemove.add(pid);
                                }
                            } else {
                                consecutiveFailures.put(pid.key(), 0);
                            }
                        }

                        if (!toRemove.isEmpty()) {
                            finalPids.removeAll(toRemove);
                            for (PIDDefinition p : toRemove) {
                                Log.w("OBD2Logger", "Blacklisted unsupported PID: " + p.key() + " (" + p.getName() + ")");
                            }
                        }

                        // ── Derived sensors ──────────────────────
                        // Look up raw values from batch by PID name
                        Double mafValue = batch.get("MAF Air Flow");
                        Double speedValue = batch.get("Vehicle Speed");
                        Double mapValue = batch.get("Intake Manifold Pressure");
                        Double baroValue = batch.get("Barometric Pressure");
                        Double dpfSoot = batch.get("DPF Soot Load");
                        Double dpfAsh = batch.get("DPF Ash Load");
                        Double dpfRegen = batch.get("DPF Regen Status");

                        // Fuel Consumption
                        if (config.showFuelConsumption && mafValue != null && speedValue != null) {
                            Double kml = DerivedSensors.fuelConsumptionKmL(mafValue, speedValue, config.fuelMode);
                            if (kml != null) {
                                samples.add(new SensorSample("derived_fuel_kmL", "Fuel Economy", kml, "km/L", "ok"));
                                Double l100 = DerivedSensors.fuelConsumptionL100km(mafValue, speedValue, config.fuelMode);
                                if (l100 != null) {
                                    samples.add(new SensorSample("derived_fuel_l100", "Fuel Economy", l100, "L/100km", "ok"));
                                }
                            }
                        }

                        // Turbo Boost
                        if (config.showTurboBoost && mapValue != null) {
                            Double boostKpa = DerivedSensors.boostPressureKpa(mapValue, baroValue);
                            if (boostKpa != null) {
                                samples.add(new SensorSample("derived_boost_kpa", "Turbo Boost", boostKpa, "kPa", "ok"));
                                Double boostPsi = DerivedSensors.boostPressurePsi(mapValue, baroValue);
                                if (boostPsi != null) {
                                    samples.add(new SensorSample("derived_boost_psi", "Turbo Boost", boostPsi, "psi", "ok"));
                                }
                            }
                        }

                        // DPF Status (derived interpretations)
                        if (config.dpfMonitorEnabled) {
                            if (dpfSoot != null) {
                                String dpfHealth = DerivedSensors.dpfHealthStatus(dpfSoot, dpfAsh);
                                samples.add(new SensorSample("derived_dpf_health", "DPF Health", 
                                    "Clean".equals(dpfHealth) ? 1.0 : "Moderate".equals(dpfHealth) ? 2.0 
                                    : "Warning".equals(dpfHealth) ? 3.0 : 4.0, "status", "ok"));
                            }
                            if (dpfRegen != null) {
                                String regen = DerivedSensors.dpfRegenStatus(dpfRegen);
                                double regenCode = "Regen Active".equals(regen) ? 1.0 : 0.0;
                                samples.add(new SensorSample("derived_dpf_regen", "DPF Regen", regenCode, "active=" + regen, "ok"));
                            }
                        }

                        // ── Air Density (Banks iDash AAD/MAD/BAD) ──────────────
                        if (config.showAirDensity && airDensityMonitor != null) {
                            airDensityMonitor.refreshWeatherSync();
                            airDensityMonitor.onObdBatch(batch);
                            AirDensityMonitor.AirDensityResult dr = airDensityMonitor.compute();
                            if (dr != null) {
                                if (dr.aad != null) {
                                    samples.add(new SensorSample("derived_aad", "Ambient Air Density",
                                            dr.aad, "lbs/1000ft3", "ok"));
                                }
                                if (dr.mad != null) {
                                    samples.add(new SensorSample("derived_mad", "Manifold Air Density",
                                            dr.mad, "lbs/1000ft3", "ok"));
                                }
                                if (dr.bad != null) {
                                    samples.add(new SensorSample("derived_bad", "Boost Air Density",
                                            dr.bad, "lbs/1000ft3", "ok"));
                                }
                                if (dr.densityPercent != null) {
                                    samples.add(new SensorSample("derived_density_pct", "Air Density %",
                                            dr.densityPercent, "%", "ok"));
                                }
                                if (dr.densityAltitudeFt != null) {
                                    samples.add(new SensorSample("derived_density_alt", "Density Altitude",
                                            (double) dr.densityAltitudeFt, "ft", "ok"));
                                }
                                if (dr.saeJ1349CF != null) {
                                    samples.add(new SensorSample("derived_sae_cf", "SAE J1349 CF",
                                            dr.saeJ1349CF, "", "ok"));
                                }
                                if (dr.grainsH2O != null) {
                                    samples.add(new SensorSample("derived_grains", "Grains H2O",
                                            dr.grainsH2O, "grains/lb", "ok"));
                                }
                                samples.add(new SensorSample("derived_humidity", "Relative Humidity",
                                        dr.humidity, "%", "ok"));

                                // Advanced Air Density (10 formulas beyond Banks)
                                Double rpmValue = batch.get("Engine RPM");
                                Double lambdaValue = batch.get("Lambda (B1S1)");
                                if (lambdaValue == null) lambdaValue = batch.get("Wideband Lambda (B1S1)");

                                AdvancedAirDensity.AdvancedResult ar =
                                        airDensityMonitor.computeAdvanced(
                                            mafValue, rpmValue, lambdaValue,
                                            config.fuelMode,
                                            config.engineDisplacementCC,
                                            config.ratedRPM);
                                if (ar != null) {
                                    if (ar.omdLbs != null) {
                                        samples.add(new SensorSample("derived_omd", "Oxygen Mass Density",
                                                ar.omdLbs, "lbs/1000ft3", "ok"));
                                    }
                                    if (ar.compressorEff != null) {
                                        samples.add(new SensorSample("derived_compressor_eff",
                                                "Compressor Efficiency", ar.compressorEff, "%", "ok"));
                                    }
                                    if (ar.intercoolerEff != null) {
                                        samples.add(new SensorSample("derived_intercooler_eff",
                                                "Intercooler Effectiveness", ar.intercoolerEff, "%", "ok"));
                                    }
                                    if (ar.vePct != null) {
                                        samples.add(new SensorSample("derived_ve",
                                                "Volumetric Efficiency", ar.vePct, "%", "ok"));
                                    }
                                    if (ar.dcafr != null) {
                                        samples.add(new SensorSample("derived_dcafr",
                                                "Density-Corrected AFR", ar.dcafr, "", "ok"));
                                    }
                                    if (ar.tmfGs != null) {
                                        samples.add(new SensorSample("derived_tmf",
                                                "Theoretical Mass Flow", ar.tmfGs, "g/s", "ok"));
                                    }
                                    if (ar.mafDeviationPct != null) {
                                        samples.add(new SensorSample("derived_maf_dev",
                                                "MAF Deviation", ar.mafDeviationPct, "%", "ok"));
                                    }
                                    if (ar.lvdFraction != null) {
                                        samples.add(new SensorSample("derived_lvd",
                                                "Vapor Displacement", ar.lvdFraction, "fraction", "ok"));
                                    }
                                    if (ar.effectiveDensityKgM3 != null) {
                                        samples.add(new SensorSample("derived_eff_density",
                                                "Effective Air Density", ar.effectiveDensityKgM3,
                                                "kg/m3", "ok"));
                                    }
                                    if (ar.eccDeltaT != null) {
                                        samples.add(new SensorSample("derived_ecc_dt",
                                                "Evap Cooling DeltaT", ar.eccDeltaT, "C", "ok"));
                                    }
                                    if (ar.eccCorrectedMAD != null) {
                                        samples.add(new SensorSample("derived_ecc_mad",
                                                "Evap-Corrected MAD", ar.eccCorrectedMAD,
                                                "lbs/1000ft3", "ok"));
                                    }
                                    if (ar.pdi != null) {
                                        samples.add(new SensorSample("derived_pdi",
                                                "Power Density Index", ar.pdi, "", "ok"));
                                    }
                                    if (ar.saeJ607CF != null) {
                                        samples.add(new SensorSample("derived_sae_j607",
                                                "SAE J607 CF", ar.saeJ607CF, "", "ok"));
                                    }
                                    if (ar.saeCFDelta != null) {
                                        samples.add(new SensorSample("derived_sae_cf_delta",
                                                "SAE CF Delta", ar.saeCFDelta, "", "ok"));
                                    }
                                }
                            }
                        }

                        DataRecord record = new DataRecord(
                                iso.format(new Date()),
                                (SystemClock.elapsedRealtime() - started) / 1000.0,
                                config.fuelMode.getValue(),
                                config.vehicleBrand,
                                config.vin,
                                samples
                        );

                        writer.writeRecord(record);
                        completed++;
                        final int finalCompleted = completed;
                        sessionRecordCount = finalCompleted;
                        runOnActiveActivity(() -> {
                            MainActivity active = activeInstance;
                            if (active != null) {
                                active.countText.setText("Records: " + finalCompleted);
                                active.updateDashboard(record);
                                active.updateGraphs(record);
                                active.updateFuelMap(record);
                                active.updateFuelTrim(record);
                                active.updateTuningData(record);
                                active.updateAirDensityPanel(record);
                                active.renderReadings(record);
                                active.updateStatusStrip(record);
                                active.updateBatteryMonitor(record);
                                active.updateLiveMetrics(finalCompleted, active.loggingStartTime);
                            }
                        });

                        // Copy fuel map data to static maps
                        MainActivity active = activeInstance;
                        if (active != null && active.fuelMapView != null) {
                            sessionPetrolData.clear();
                            sessionPetrolData.putAll(active.fuelMapView.getPetrolData());
                            sessionLpgData.clear();
                            sessionLpgData.putAll(active.fuelMapView.getLpgData());
                        }

                        try {
                            Thread.sleep(config.sampleIntervalMs);
                        } catch (InterruptedException ie) {
                            // shutdownNow() interrupted our sleep — exit the loop gracefully
                            running = false;
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (!running || e instanceof InterruptedException) {
                        break;
                    }
                    driver.disconnect();
                    retryCount++;
                    if (retryCount > maxRetries) {
                        running = false;
                        runOnActiveActivity(() -> {
                            MainActivity active = activeInstance;
                            if (active != null) {
                                active.setStatus("Connection failed permanently: " + e.getMessage(), R.color.danger);
                                active.updateStatusStripConnection(0, "Disconnected");
                                if (active.fabLog != null) active.setFabState(false);
                            }
                        });
                        break;
                    }
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            final int finalCompleted2 = completed;
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) active.setStatus("Stopped at " + finalCompleted2 + " records.", R.color.primary);
            });
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                runOnActiveActivity(() -> {
                    MainActivity active = activeInstance;
                    if (active != null) active.setStatus("Logger error: " + e.getMessage(), R.color.danger);
                });
            }
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {
            }
            driver.disconnect();
            currentWriter = null;
            currentDriver = null;
            // Only shut down the executor if it's still the one we started with.
            // stopLogging() on the main thread may have already shut it down and
            // set executor=null (or started a new session) — clobbering that here
            // would either NPE or kill a newly-started logger thread.
            ExecutorService ex = executor;
            if (ex != null && !ex.isShutdown()) {
                ex.shutdown();
            }
            executor = null;
            runOnActiveActivity(() -> {
                MainActivity active = activeInstance;
                if (active != null) {
                    active.isConnecting = false;
                    active.running = false;
                    if (active.fabLog != null) {
                        active.setFabState(false);
                    }
                    active.headerStatus.setText("Disconnected");
                    active.updateStatusStripConnection(0, "Disconnected");
                    active.setConfigUiEnabled(true);
                }
            });
        }
    }

    // --- LoggerService callbacks (background logging) ---

    @Override
    public void onRecord(DataRecord record, int count) {
        if (isFinishing() || isDestroyed()) return;
        isConnecting = false;
        watchdogHandler.removeCallbacks(connectionWatchdog);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (fabLog != null && running) {
                setFabState(true);
            }
            LoggerService service = LoggerService.getInstance();
            LoggerConfig cfg = (service != null && service.getConfig() != null) ? service.getConfig() : activeInProcessConfig;
            String modeStr = (cfg != null && cfg.transportMode != null) ? cfg.transportMode.getValue() : "OBD2";
            updateStatusStripConnection(2, "Connected " + modeStr);
            if (headerStatus != null) {
                headerStatus.setText("Logging...");
            }
            if (countText != null) countText.setText("Records: " + count);
            updateDashboard(record);
            updateGraphs(record);
            updateFuelMap(record);
            updateFuelTrim(record);
            updateTuningData(record);
            updateAirDensityPanel(record);
            renderReadings(record);
            updateLiveMetrics(count, loggingStartTime);
            updateBatteryMonitor(record);
            updateStatusStrip(record);

            if (fuelMapView != null) {
                sessionPetrolData.clear();
                sessionPetrolData.putAll(fuelMapView.getPetrolData());
                sessionLpgData.clear();
                sessionLpgData.putAll(fuelMapView.getLpgData());
            }
        });
    }

    private void updateFuelMap(DataRecord record) {
        Double rpm = valueByKey(record, "01_0C");
        Double map = valueByKey(record, "01_0B");
        Double stft = valueByKey(record, "01_06");
        Double ltft = valueByKey(record, "01_07");
        Double ect = valueByKey(record, "01_05");
        Double fuelStatus = valueByKey(record, "01_03");

        // Fallback for the load axis: many vehicles (MAF-based, no MAP sensor) or
        // adapters that drop MAP from a batch response leave 0x0B null. Engine Load
        // (0x04, A*100/255 → 0-100%) maps onto the same 20-100 fuel-map X-axis range
        // and is supported by virtually every OBD2 vehicle, so use it when MAP is
        // unavailable rather than leaving the entire fuel map empty.
        double loadAxis;
        if (map != null) {
            loadAxis = map;
        } else {
            Double load = valueByKey(record, "01_04");
            loadAxis = (load != null) ? load : Double.NaN;
        }

        if (ect != null && mapEctText != null) {
            mapEctText.setText(String.format(Locale.US, "ECT: %.0f °C", ect));
        } else if (mapEctText != null) {
            mapEctText.setText("ECT: Unknown");
        }

        boolean isClosedLoop = true; // Default to true if not supported so app still works
        if (fuelStatus != null && mapLoopStatusText != null) {
            // SAE J1979 PID 03 Fuel System Status (byte A, bit flags):
            //   0x01 = Open loop (insufficient engine temp)
            //   0x02 = Closed loop (using oxygen sensor feedback) ← THIS IS WHAT WE WANT
            //   0x04 = Open loop (engine load)
            //   0x08 = Open loop (system failure)
            int statusVal = fuelStatus.intValue();
            isClosedLoop = (statusVal & 0x02) != 0;
            mapLoopStatusText.setText(isClosedLoop ? "Status: Closed Loop" : "Status: Open Loop");
            mapLoopStatusText.setTextColor(getColorCompat(isClosedLoop ? R.color.accent : R.color.warning));
        } else if (mapLoopStatusText != null) {
            mapLoopStatusText.setText("Status: Unknown (Assumed Closed)");
            mapLoopStatusText.setTextColor(getColorCompat(R.color.muted));
        }

        if (rpm != null && !Double.isNaN(loadAxis)) {
            // Use STFT if available, otherwise LTFT, otherwise 0 (just track position)
            double trim = 0;
            if (stft != null) {
                trim = stft + (ltft != null ? ltft : 0);
            } else if (ltft != null) {
                trim = ltft;
            }

            boolean tempOk = (ect == null) || (ect >= 80.0);
            if (isClosedLoop && tempOk) {
                if (fuelMapView != null) {
                    FuelMode mode = FuelMode.fromString(record.getFuelMode());
                    fuelMapView.pushData(rpm, loadAxis, trim, mode);
                }
            }
        }
    }

    @Override
    public void onStatus(String status, boolean isError) {
        runOnUiThread(() -> {
            setStatus(status, isError ? R.color.danger : R.color.accent);
            if (!isError && status != null && (status.contains("Connected") || status.contains("Logging in background") || status.contains("Logging active") || status.contains("Logging resumed") || status.contains("running simulation"))) {
                isConnecting = false;
                watchdogHandler.removeCallbacks(connectionWatchdog);
                if (fabLog != null) {
                    setFabState(true);
                }
                LoggerService service = LoggerService.getInstance();
                LoggerConfig cfg = (service != null && service.getConfig() != null) ? service.getConfig() : activeInProcessConfig;
                String modeStr = (cfg != null && cfg.transportMode != null) ? cfg.transportMode.getValue() : "OBD2";
                updateStatusStripConnection(2, "Connected " + modeStr);
                if (headerStatus != null) {
                    headerStatus.setText("Logging...");
                }
            } else if (isError && status != null && status.contains("Connection failed")) {
                isConnecting = false;
                watchdogHandler.removeCallbacks(connectionWatchdog);
                updateStatusStripConnection(0, "Connection failed");
                if (headerStatus != null) {
                    headerStatus.setText("Connection failed");
                }
            }
        });
    }

    @Override
    public void onStopped(int totalRecords) {
        isConnecting = false;
        running = false;
        watchdogHandler.removeCallbacks(connectionWatchdog);
        runOnUiThread(() -> {
            if (fabLog != null) {
                setFabState(false);
            }
            setStatus("Background logging stopped. " + totalRecords + " records saved.", R.color.primary);
            headerStatus.setText("Disconnected");
            updateStatusStripConnection(0, "Disconnected");
            updateSessionStatus(false);
            setConfigUiEnabled(true);
        });
    }

    @Override
    public void onVinRead(String vin) {
        runOnUiThread(() -> {
            headerVin.setText("VIN: " + vin);
            if (txtHomeVin != null) txtHomeVin.setText(vin);

            // ── Auto-detect diesel: enable DPF + Deep Scan on first run ──
            if (vin != null && !vin.isEmpty() && !vin.equals("UNKNOWN")) {
                // Load brand-specific DTC database based on VIN
                String brandName = DtcDatabase.initForVin(this, vin);
                if (brandName != null && !"Unknown".equals(brandName)) {
                    Toast.makeText(this, "Brand: " + brandName, Toast.LENGTH_SHORT).show();
                }

                android.content.SharedPreferences p = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
                boolean alreadySet = p.getBoolean("pref_auto_detect_done", false);
                if (!alreadySet && isDieselVin(vin)) {
                    if (dpfMonitorCheckbox != null) dpfMonitorCheckbox.setChecked(true);
                    if (fordMsCanCheckbox != null) fordMsCanCheckbox.setChecked(true);
                    p.edit().putBoolean("pref_auto_detect_done", true).apply();
                    Toast.makeText(this, "Diesel detected — DPF + Deep Scan enabled", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Heuristic: returns true if VIN suggests a diesel vehicle. */
    private static boolean isDieselVin(String vin) {
        if (vin == null || vin.length() < 3) return false;
        String wmi = vin.substring(0, 3).toUpperCase();
        // Common Thai-market diesel WMI prefixes
        switch (wmi) {
            case "MPA": // Isuzu (Thailand) — almost all diesel
            case "MNB": // Ford (Thailand) — Ranger mostly diesel
            case "MMB": // Mitsubishi (Thailand) — Triton/Pajero diesel
            case "MR0": // Toyota (Thailand) — Hilux/Fortuner mostly diesel
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onPidsDetected(int supportedCount, int totalCount, boolean fromLiveQuery) {
        runOnUiThread(() -> {
            String msg = fromLiveQuery
                    ? supportedCount + "/" + totalCount + " PIDs detected (live query)"
                    : supportedCount + "/" + totalCount + " PIDs (VIN profile fallback)";
            setStatus(msg, R.color.accent);
        });
    }

    @Override
    public void onDeviceDetected(VLinkerOptimizer.DeviceType deviceType) {
        runOnUiThread(() -> {
            String deviceName;
            int color;
            switch (deviceType) {
                case VLINKER_FS_USB:
                    deviceName = "vLinker FS USB (MIC3322)";
                    color = R.color.accent;
                    break;
                case VLINKER_MC_WIFI:
                    deviceName = "vLinker MC WiFi (MIC3313)";
                    color = R.color.accent;
                    break;
                case VLINKER_MC_BT:
                    deviceName = "vLinker MC BT (MIC3313)";
                    color = R.color.accent;
                    break;
                case GENERIC_ELM327:
                    deviceName = "Generic ELM327";
                    color = R.color.warning;
                    break;
                default:
                    return; // Don't show for UNKNOWN
            }
            setStatus(deviceName + " — optimized", color);
            updateStatusStripConnection(2, deviceName);
        });
    }

    @Override
    public void onAdapterCheckResult(boolean isStandard, String details) {
        runOnUiThread(() -> {
            if (!isStandard) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("⚠️ Adapter Warning / เตือนอะแดปเตอร์")
                        .setMessage("The connected OBD2 adapter (" + details + ") is non-standard or a low-quality clone.\n\n" +
                                "It may lack support for critical CAN features, cause latency, drop OBD2 frames, or fail to auto-tune.\n\n" +
                                "We recommend using high-quality adapters like vLinker or OBDLink for reliable operation.\n\n" +
                                "อะแดปเตอร์ที่เชื่อมต่อไม่ได้มาตรฐานหรือเป็นรุ่นลอกเลียนแบบ อาจส่งข้อมูลช้าหรือทำงานไม่เสถียร แนะนำให้ใช้ vLinker หรือ OBDLink")
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    @Override
    public void onDtcAutoScan(int storedCount, int pendingCount, int permanentCount) {
        runOnUiThread(() -> {
            int total = storedCount + pendingCount;
            if (total > 0) {
                Toast.makeText(this, "⚠️ Auto-scan: Found " + total + " active DTC codes!", Toast.LENGTH_LONG).show();
            }
            updateDtcBadge(storedCount, pendingCount, permanentCount);
        });
    }

    @Override
    public void onDtcAutoScanDetails(DtcReader.DtcScanResult result) {
        lastAutoScanModules = result.modules;
        lastAutoScanProtocolStatuses = result.protocolStatuses;
    }

    // Module info from last auto-scan
    private volatile List<DtcReader.ModuleInfo> lastAutoScanModules = null;
    private volatile List<DtcReader.ProtocolScanStatus> lastAutoScanProtocolStatuses = null;
    private volatile List<Mode06Result> lastMode06Results = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void onNewDtcDetected(List<DtcCode> newCodes) {
        runOnUiThread(() -> {
            StringBuilder sb = new StringBuilder();
            for (DtcCode c : newCodes) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(c.getCode());
            }
            Toast.makeText(this, "🚨 NEW DTC DETECTED: " + sb.toString(), Toast.LENGTH_LONG).show();
            
            // Build and show notification
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                // Reuse existing channel id from LoggerService (CHANNEL_ID = "OBD2_LOGGER_CHANNEL" or similar)
                // Let's create a notification channel if not exist
                String channelId = "obd2_dtc_alerts";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(
                            channelId, "OBD2 DTC Alerts", NotificationManager.IMPORTANCE_HIGH);
                    nm.createNotificationChannel(channel);
                }
                androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, channelId)
                        .setContentTitle("🚨 New Fault Code Detected")
                        .setContentText("DTCs: " + sb.toString())
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH);
                nm.notify(9999, builder.build());
            }
        });
    }

    /**
     * Update the bottom status strip with live PID values from the current record.
     * Called from onRecord() on the UI thread.
     */
    private void updateStatusStrip(DataRecord record) {
        if (stripRpm == null) return; // not initialized yet
        Double rpm = valueByKey(record, "01_0C");
        Double speed = valueByKey(record, "01_0D");
        Double voltage = valueByKey(record, "01_42");
        Double coolant = valueByKey(record, "01_05");

        stripRpm.setText(rpm != null ? String.format(Locale.US, "%.0f", rpm) : "--");
        stripSpeed.setText(speed != null ? String.format(Locale.US, "%.0f", speed) : "--");
        if (voltage != null) {
            String voltStr = String.format(Locale.US, "%.1f V", voltage);
            if (txtHomeVoltage != null) txtHomeVoltage.setText(voltStr);
            stripVoltage.setText(String.format(Locale.US, "%.1f", voltage));
            // Color-code voltage: red < 12.2, amber 12.2-12.65, green 12.65-14.7, red > 14.8
            if (voltage < 12.2 || voltage > 14.8) {
                stripVoltage.setTextColor(getColorCompat(R.color.danger));
            } else if (voltage < 12.65 || voltage > 14.7) {
                stripVoltage.setTextColor(getColorCompat(R.color.warning));
            } else {
                stripVoltage.setTextColor(getColorCompat(R.color.accent));
            }
        } else {
            if (txtHomeVoltage != null) txtHomeVoltage.setText("---");
            stripVoltage.setText("--");
            stripVoltage.setTextColor(getColorCompat(R.color.text));
        }

        // Live home page telemetry updates
        if (txtHomeRpm != null) {
            txtHomeRpm.setText(rpm != null ? String.format(Locale.US, "%.0f RPM", rpm) : "---");
        }
        if (txtHomeSpeed != null) {
            txtHomeSpeed.setText(speed != null ? String.format(Locale.US, "%.0f km/h", speed) : "---");
        }
        if (txtHomeCoolant != null) {
            txtHomeCoolant.setText(coolant != null ? String.format(Locale.US, "%.0f °C", coolant) : "---");
        }

        // ── Derived sensors: Fuel Economy + Turbo Boost ──────
        Double fuelKmL = valueByKey(record, "derived_fuel_kmL");
        Double boostPsi = valueByKey(record, "derived_boost_psi");
        Double dpfSoot = valueByKey(record, "01_7A");

        if (txtHomeFuelEconomy != null) {
            txtHomeFuelEconomy.setText(fuelKmL != null ? String.format(Locale.US, "%.1f km/L", fuelKmL) : "---");
        }
        if (txtHomeBoost != null) {
            txtHomeBoost.setText(boostPsi != null ? String.format(Locale.US, "%.1f psi", boostPsi) : "---");
        }
        if (txtHomeDpf != null) {
            if (dpfSoot != null) {
                String health = DerivedSensors.dpfHealthStatus(dpfSoot, null);
                txtHomeDpf.setText(String.format(Locale.US, "DPF: %.0f%% %s", dpfSoot, health));
            } else {
                txtHomeDpf.setText("DPF: ---");
            }
        }

        // Status strip: boost + fuel economy
        if (stripBoost != null) {
            stripBoost.setText(boostPsi != null ? String.format(Locale.US, "%.1f", boostPsi) : "--");
        }
        if (stripFuel != null) {
            stripFuel.setText(fuelKmL != null ? String.format(Locale.US, "%.1f", fuelKmL) : "--");
        }

        // DTC count badge
        if (txtHomeDtc != null) {
            int dtcCount = LoggerService.lastStoredDtcs.size() + LoggerService.lastPendingDtcs.size();
            txtHomeDtc.setText(dtcCount > 0 ? String.valueOf(dtcCount) : "0");
            txtHomeDtc.setTextColor(getColorCompat(dtcCount > 0 ? R.color.danger : R.color.accent));
        }
    }

    /**
     * Update the status strip connection indicator (dot + device text).
     * @param state 0=disconnected, 1=connecting, 2=connected
     * @param deviceName device name or transport mode string
     */
    private void updateStatusStripConnection(int state, String deviceName) {
        if (statusDot == null || statusDeviceText == null) return;
        int dotRes;
        int textColor;
        switch (state) {
            case 1: // connecting
                dotRes = R.drawable.bg_status_dot_connecting;
                textColor = R.color.warning;
                break;
            case 2: // connected
                dotRes = R.drawable.bg_status_dot_on;
                textColor = R.color.accent;
                break;
            default: // disconnected
                dotRes = R.drawable.bg_status_dot_off;
                textColor = R.color.muted;
                break;
        }
        statusDot.setBackgroundResource(dotRes);
        statusDeviceText.setText(deviceName);
        statusDeviceText.setTextColor(getColorCompat(textColor));
        // Sync header status dot + chip
        if (headerStatusDot != null) headerStatusDot.setBackgroundResource(dotRes);
        if (headerStatus != null) {
            headerStatus.setText(state == 2 ? "Connected" : (state == 1 ? "Connecting" : "Offline"));
            headerStatus.setTextColor(getColorCompat(textColor));
        }

        // Update home screen status
        if (txtHomeAdapter != null) {
            txtHomeAdapter.setText(state != 0 && deviceName != null ? deviceName : "---");
        }
        
        TextView txtHomeConnectionState = findViewById(R.id.txtHomeConnectionState);
        if (txtHomeConnectionState != null) {
            txtHomeConnectionState.setText(state == 2 ? "CONNECTED" : (state == 1 ? "CONNECTING" : "DISCONNECTED"));
            txtHomeConnectionState.setTextColor(getColorCompat(textColor));
        }
        
        View homeStatusDot = findViewById(R.id.homeStatusDot);
        if (homeStatusDot != null) {
            homeStatusDot.setBackgroundResource(state == 2 ? R.drawable.bg_status_dot_on : (state == 1 ? R.drawable.bg_status_dot_connecting : R.drawable.bg_status_dot_off));
        }
        
        com.google.android.material.button.MaterialButton btnHomeConnect = findViewById(R.id.btnHomeConnect);
        if (btnHomeConnect != null) {
            if (state == 2) {
                btnHomeConnect.setText("DISCONNECT ECU");
                btnHomeConnect.setIconResource(android.R.drawable.ic_media_pause);
                btnHomeConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.danger)));
            } else {
                btnHomeConnect.setText("CONNECT & TUNE");
                btnHomeConnect.setIconResource(android.R.drawable.ic_media_play);
                btnHomeConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
            }
        }

        if (txtHomeProtocol != null) {
            if (state == 2) {
                LoggerConfig cfg = activeInProcessConfig != null ? activeInProcessConfig : readConfigFromUi();
                txtHomeProtocol.setText(cfg.obdProtocol.name());
            } else if (state == 0) {
                txtHomeProtocol.setText("---");
                if (txtHomeVin != null) txtHomeVin.setText("---");
                if (txtHomeVoltage != null) txtHomeVoltage.setText("---");
                
                // Reset live telemetry stats on home page when disconnected
                if (txtHomeRpm != null) txtHomeRpm.setText("---");
                if (txtHomeSpeed != null) txtHomeSpeed.setText("---");
                if (txtHomeCoolant != null) txtHomeCoolant.setText("---");
            }
        }
    }

    private void updateDtcBadge(int storedCount, int pendingCount, int permanentCount) {
        int total = storedCount + pendingCount;
        if (stripDtcBadge != null) {
            if (total > 0) {
                stripDtcBadge.setVisibility(View.VISIBLE);
                stripDtcBadge.setText(String.valueOf(total));
                if (storedCount > 0) {
                    stripDtcBadge.setBackgroundResource(R.drawable.bg_dtc_badge);
                    stripDtcBadge.getBackground().setTint(getColorCompat(R.color.danger));
                } else {
                    stripDtcBadge.getBackground().setTint(getColorCompat(R.color.warning));
                }
            } else {
                stripDtcBadge.setVisibility(View.GONE);
            }
        }
    }

    // --- Dashboard updates ---

    private void updateDashboard(DataRecord record) {
        TextView[] values = {dashValue1, dashValue2, dashValue3, dashValue4};
        for (int i=0; i<4; i++) {
            if ("none".equalsIgnoreCase(prefDashPids[i])) {
                continue;
            }
            Double val = valueByKey(record, prefDashPids[i]);
            if (val != null && values[i] != null) {
                PIDDefinition pid = PIDDefinition.findByKey(prefDashPids[i]);
                values[i].setText(String.format(Locale.US, "%.2f %s", val, pid != null ? pid.getUnit() : ""));
            }
        }
        
        GaugeView[] gauges = {gauge1, gauge2, gauge3, gauge4};
        for (int i=0; i<4; i++) {
            if ("none".equalsIgnoreCase(prefGaugePids[i])) {
                continue;
            }
            Double val = valueByKey(record, prefGaugePids[i]);
            if (val != null && gauges[i] != null) {
                gauges[i].setValue(val.floatValue());
            }
        }

        // Update Tuning Readiness widget
        Double ect = valueByKey(record, "01_05");
        Double fuelStatus = valueByKey(record, "01_03");

        if (ect != null) {
            if (dashEctText != null) {
                dashEctText.setText(String.format(Locale.US, "%.0f °C", ect));
            }
            if (dashWarmupProgress != null) {
                int progress = (int) Math.min(100, Math.max(0, (ect / 80.0) * 100));
                dashWarmupProgress.setProgress(progress);
                if (ect >= 80.0) {
                    dashWarmupProgress.setIndicatorColor(getColorCompat(R.color.accent));
                } else {
                    dashWarmupProgress.setIndicatorColor(getColorCompat(R.color.warning));
                }
            }
        }

        boolean isClosedLoop = true;
        if (fuelStatus != null) {
            isClosedLoop = (fuelStatus.intValue() & 0x02) != 0;
        }

        boolean tempOk = (ect == null) || (ect >= 80.0);
        if (dashTuningStatus != null) {
            if (!isClosedLoop) {
                dashTuningStatus.setText("Open Loop");
                dashTuningStatus.setTextColor(getColorCompat(R.color.danger));
            } else if (!tempOk) {
                dashTuningStatus.setText("Engine Cold");
                dashTuningStatus.setTextColor(getColorCompat(R.color.warning));
            } else {
                dashTuningStatus.setText("Ready to Tune");
                dashTuningStatus.setTextColor(getColorCompat(R.color.accent));
            }
        }
    }

    private void updateGraphs(DataRecord record) {
        GraphView[] graphs = {graph1, graph2, graph3, graph4, graph5};
        for (int i=0; i<5; i++) {
            if ("none".equalsIgnoreCase(prefGraphPids[i])) {
                continue;
            }
            Double val = valueByKey(record, prefGraphPids[i]);
            if (val != null && graphs[i] != null) {
                graphs[i].pushValue(val.floatValue());
            }
        }
    }

    private void updateFuelTrim(DataRecord record) {
        Double stft = valueByKey(record, "01_06");
        Double ltft = valueByKey(record, "01_07");
        if (stft != null) {
            FuelMode mode = FuelMode.fromString(record.getFuelMode());
            FuelTrimResult result = LPGAnalyzer.analyzeFuelTrim(this, stft, ltft, mode);
            
            if (tuningStatusText != null) {
                int color = result.getStatus().equals(getString(R.string.analyzer_ok)) ? R.color.accent :
                        (result.getStatus().equals(getString(R.string.analyzer_lean)) ? R.color.primary : R.color.danger);
                tuningStatusText.setTextColor(getColorCompat(color));
                tuningStatusText.setText(String.format(Locale.US,
                        getString(R.string.tuning_status_format),
                        result.getStft(), result.getLtft(),
                        result.getStatus(),
                        result.getRecommendation()));
            }

            // Update new professional tuning views
            if (txtTuningStft != null) {
                txtTuningStft.setText(String.format(Locale.US, "%.1f%%", result.getStft()));
            }
            if (txtTuningLtft != null) {
                txtTuningLtft.setText(String.format(Locale.US, "%.1f%%", result.getLtft()));
            }
            if (txtTuningStatus != null) {
                txtTuningStatus.setText(result.getStatus());
                int color = result.getStatus().equals(getString(R.string.analyzer_ok)) ? R.color.accent :
                        (result.getStatus().equals(getString(R.string.analyzer_lean)) ? R.color.primary : R.color.danger);
                txtTuningStatus.setTextColor(getColorCompat(color));
            }
            if (txtTuningAdvice != null) {
                txtTuningAdvice.setText(result.getRecommendation());
            }
        }
    }

    private void updateTuningData(DataRecord record) {
        // Obsolete as we use dynamic dashboard cards now
    }

    // --- Battery Tester ---

    /**
     * Read PID 0x42 voltage from the driver. Returns -1 on failure.
     */
    private double readBatteryVoltage() {
        boolean wasPaused = isPaused;
        if (!wasPaused) {
            isPaused = true;
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        try {
            BaseDriver activeDriver = getActiveDriver();
            if (activeDriver == null || !activeDriver.isConnected()) return -1;
            try {
                Double v = activeDriver.queryPid(PIDDefinition.findByKey("01_42"));
                if (v != null && v > 0) {
                    return v;
                }
            } catch (Exception e) {
                // ignore and fallback
            }
            
            // Fallback: Query direct ELM327 analog voltage using command "AT RV"
            try {
                String raw = activeDriver.sendCommandRaw("AT RV");
                if (raw != null && !raw.isEmpty()) {
                    String clean = raw.replaceAll("[^0-9.]", "");
                    if (!clean.isEmpty()) {
                        double val = Double.parseDouble(clean);
                        if (val > 0 && val < 30) {
                            return val;
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            return -1;
        } finally {
            if (!wasPaused) {
                isPaused = false;
            }
        }
    }

    /**
     * Quick single voltage reading — updates the graph + SoC/SoH display.
     * Called from onRecord to keep the live graph fed.
     */
    private void updateBatteryMonitor(double v) {
        if (v <= 0) return;
        lastBatteryVoltage = v;
        if (batteryTestView != null) {
            batteryTestView.addSample((float) v);
        }
        // Get selected chemistry for accurate SoC
        BatteryTester.Chemistry chem = getSelectedChemistry();
        double soc = BatteryTester.voltageToSoC(v, chem);

        if (batteryVoltageValueText != null) {
            batteryVoltageValueText.setText(String.format(Locale.US, "%.2f V", v));
        }

        if (batteryVoltageStatusBadge != null) {
            String desc = getBatteryStatusDescription(v, chem);
            int color = getBatteryStatusColor(v, chem);
            batteryVoltageStatusBadge.setText(desc);
            
            android.graphics.drawable.GradientDrawable statusGd = new android.graphics.drawable.GradientDrawable();
            statusGd.setCornerRadius(4 * getResources().getDisplayMetrics().density);
            statusGd.setColor(0x20000000 | (0x00FFFFFF & color));
            batteryVoltageStatusBadge.setBackground(statusGd);
            batteryVoltageStatusBadge.setTextColor(color);
        }

        if (batteryVoltageText != null) {
            batteryVoltageText.setText(String.format(Locale.US, "SoC: %.0f%%  •  Chemistry: %s", soc, chem.getDisplayName(this)));
        }

        // Update SoC/SoH summary cards (quick estimate)
        if (socValueText != null) {
            socValueText.setText(String.format(Locale.US, "%.0f%%", soc));
            socVoltageText.setText(String.format(Locale.US, "%.2f V (%s)", v, chem.getDisplayName(this)));
        }
    }

    private String getBatteryStatusDescription(double v) {
        return getBatteryStatusDescription(v, BatteryTester.Chemistry.FLOODED);
    }

    private String getBatteryStatusDescription(double v, BatteryTester.Chemistry chem) {
        double altMin = chem.altMinV;
        double altMax = chem.altMaxV;
        double restLow = chem.restLowV();
        double restDeep = chem.restDeepV();

        if (v >= altMin && v <= altMax) {
            return "กำลังชาร์จ (Charging / Alternator Normal)";
        } else if (v > altMax) {
            return "กำลังชาร์จสูงเกินไป (Overcharging Alert)";
        } else if (v >= altMin - 0.7 && v < altMin) {
            return "แรงดันสแตนด์บาย (Float Charging / Standby)";
        } else if (v >= restLow && v < altMin - 0.7) {
            return "ดับเครื่องปกติ (Resting / Normal)";
        } else if (v >= restDeep && v < restLow) {
            return "แรงดันต่ำ / ใช้โหลดหนัก (Low Battery / Under Load)";
        } else {
            return "วิกฤต / คายประจุลึก (Critically Low / Deep Discharge)";
        }
    }

    private int getBatteryStatusColor(double v) {
        return getBatteryStatusColor(v, BatteryTester.Chemistry.FLOODED);
    }

    private int getBatteryStatusColor(double v, BatteryTester.Chemistry chem) {
        double altMin = chem.altMinV;
        double altMax = chem.altMaxV;
        double restLow = chem.restLowV();

        if (v >= altMin && v <= altMax) {
            return 0xFF22C55E; // Green — charging normal
        } else if (v > altMax) {
            return 0xFFEF4444; // Red — overcharging
        } else if (v >= altMin - 0.7 && v < altMin) {
            return 0xFF38BDF8; // Blue — standby/float
        } else if (v >= restLow && v < altMin - 0.7) {
            return 0xFFF59E0B; // Amber — resting normal
        } else {
            return 0xFFEF4444; // Red — critically low
        }
    }

    private void updateBatteryMonitor(DataRecord record) {
        Double v = valueByKey(record, "01_42");
        if (v == null || v <= 0) return;
        updateBatteryMonitor(v);
    }

    /** Read the currently selected battery chemistry from the spinner. */
    private BatteryTester.Chemistry getSelectedChemistry() {
        if (batteryTypeSpinner != null) {
            String label = batteryTypeSpinner.getText().toString();
            String[] localizedTypes = getResources().getStringArray(R.array.battery_chemistry_types);
            for (int i = 0; i < localizedTypes.length; i++) {
                if (localizedTypes[i].equals(label)) {
                    if (i >= 0 && i < BatteryTester.Chemistry.values().length) {
                        return BatteryTester.Chemistry.values()[i];
                    }
                }
            }
            return BatteryTester.Chemistry.fromSpinner(label);
        }
        return BatteryTester.Chemistry.FLOODED;
    }

    /** Test 1: Resting voltage (engine off). */
    private void testBatteryResting() {
        final BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            batteryStatusText.setText(getString(R.string.battery_not_connected));
            return;
        }
        BatteryTester.Chemistry chem = getSelectedChemistry();
        if (activeDriver instanceof SimulationDriver) {
            ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.RESTING);
        }
        batteryStatusText.setText(getString(R.string.battery_resting_reading, chem.getDisplayName(this)));
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            double v = readBatteryVoltage();
            restingVoltage = v;
            runOnUiThread(() -> {
                if (v > 0) {
                    BatteryTester.BatteryTestResult r = BatteryTester.testStateOfCharge(v, chem);
                    displayBatteryResult(r);
                } else {
                    batteryStatusText.setText(getString(R.string.battery_failed_read_voltage));
                }
            });
        });
    }

    /** Test 3 & 9: Alternator voltage at idle & Charging system regulation efficiency at high RPM. */
    private void testBatteryAlternator() {
        final BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            batteryStatusText.setText(getString(R.string.battery_not_connected));
            return;
        }
        BatteryTester.Chemistry chem = getSelectedChemistry();
        if (activeDriver instanceof SimulationDriver) {
            ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.RUNNING);
        }
        batteryStatusText.setText(getString(R.string.battery_alt_step1));
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            // Sample a few times to get a stable reading
            double sum = 0;
            int count = 0;
            for (int i = 0; i < 5; i++) {
                double v = readBatteryVoltage();
                if (v > 0) { sum += v; count++; }
                try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
            }
            double idleV = count > 0 ? sum / count : -1;
            runningVoltage = idleV;
            // Also try high-RPM reading (ask user to rev)
            runOnUiThread(() -> {
                if (idleV > 0) {
                    batteryStatusText.setText(getString(R.string.battery_alt_step2));
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(getString(R.string.battery_alt_high_rpm_title))
                            .setMessage(getString(R.string.battery_alt_high_rpm_msg))
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                if (activeDriver instanceof SimulationDriver) {
                                    ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.RUNNING_HIGH);
                                }
                                batteryStatusText.setText(getString(R.string.battery_alt_reading_high));
                                dtcExecutor.submit(() -> {
                                    double highSum = 0;
                                    int highCount = 0;
                                    for (int i = 0; i < 5; i++) {
                                        double v = readBatteryVoltage();
                                        if (v > 0) { highSum += v; highCount++; }
                                        try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
                                    }
                                    double highV = highCount > 0 ? highSum / highCount : -1;
                                    highRpmVoltage = highV;

                                    runOnUiThread(() -> {
                                        if (highV > 0) {
                                            LinearLayout container = findViewById(R.id.batteryResultsContainer);
                                            if (container != null) {
                                                container.removeAllViews();
                                                container.addView(batteryStatusText);
                                            }
                                            batteryStatusText.setText("");

                                            BatteryTester.BatteryTestResult rIdle = BatteryTester.testAlternatorVoltage(idleV, chem);
                                            BatteryTester.BatteryTestResult rEff = BatteryTester.testChargingEfficiency(idleV, highV);

                                            addBatteryResultRow(container, rIdle);
                                            addBatteryResultRow(container, rEff);
                                        } else {
                                            batteryStatusText.setText(getString(R.string.battery_alt_failed_high));
                                        }
                                    });
                                });
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    batteryStatusText.setText(getString(R.string.battery_failed_read_voltage));
                }
            });
        });
    }

    /** Test 4 & 5: Voltage drop under electrical load & Voltage recovery after load removal. */
    private void testBatteryLoadDrop() {
        final BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            batteryStatusText.setText(getString(R.string.battery_not_connected));
            return;
        }
        if (activeDriver instanceof SimulationDriver) {
            ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.RUNNING);
        }
        batteryStatusText.setText(getString(R.string.battery_load_step1));
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            double noLoad = readBatteryVoltage();
            noLoadVoltage = noLoad;
            runOnUiThread(() -> {
                batteryStatusText.setText(getString(R.string.battery_load_step2));
                new android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.battery_load_drop_title))
                        .setMessage(getString(R.string.battery_load_drop_msg))
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            if (activeDriver instanceof SimulationDriver) {
                                ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.LOADED);
                            }
                            batteryStatusText.setText(getString(R.string.battery_load_reading_full));
                            dtcExecutor.submit(() -> {
                                double fullLoad = readBatteryVoltage();
                                fullLoadVoltage = fullLoad;
                                runOnUiThread(() -> {
                                    if (noLoad > 0 && fullLoad > 0) {
                                        // Now show Step 3: Turn OFF accessories to test recovery
                                        batteryStatusText.setText(getString(R.string.battery_load_step3));
                                        new android.app.AlertDialog.Builder(this)
                                                .setTitle(getString(R.string.battery_recovery_title))
                                                .setMessage(getString(R.string.battery_recovery_msg))
                                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                                    if (activeDriver instanceof SimulationDriver) {
                                                        ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.RECOVERING);
                                                    }
                                                    batteryStatusText.setText(getString(R.string.battery_recovery_sampling));
                                                    dtcExecutor.submit(() -> {
                                                        // 1. Immediately read post-load voltage (right after turning accessories off)
                                                        double postLoad = readBatteryVoltage();
                                                        postLoadVoltage = postLoad;

                                                        if (currentDriver instanceof SimulationDriver) {
                                                            ((SimulationDriver) currentDriver).setSimState(SimulationDriver.SimState.RECOVERING);
                                                        }

                                                        // 2. Wait 5 seconds for recovery with a live countdown on screen
                                                        for (int i = 5; i > 0; i--) {
                                                            final int secondsLeft = i;
                                                            runOnUiThread(() -> batteryStatusText.setText(getString(R.string.battery_recovery_monitoring, secondsLeft)));
                                                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                                                        }

                                                        // 3. Read recovered voltage
                                                        double recovered = readBatteryVoltage();
                                                        recoveredVoltage = recovered;

                                                        // 4. Calculate delta
                                                        if (postLoad > 0 && recovered > 0) {
                                                            recoveryDelta = noLoad - recovered;
                                                        }

                                                        runOnUiThread(() -> {
                                                            if (postLoad > 0 && recovered > 0) {
                                                                // Display BOTH results in the results container
                                                                LinearLayout container = findViewById(R.id.batteryResultsContainer);
                                                                if (container != null) {
                                                                    container.removeAllViews();
                                                                    container.addView(batteryStatusText);
                                                                }
                                                                batteryStatusText.setText("");

                                                                BatteryTester.BatteryTestResult rDrop = BatteryTester.testVoltageDrop(noLoad, fullLoad);
                                                                BatteryTester.BatteryTestResult rRec = BatteryTester.testVoltageRecovery(noLoad, postLoad, recovered, 5.0);

                                                                addBatteryResultRow(container, rDrop);
                                                                addBatteryResultRow(container, rRec);
                                                            } else {
                                                                batteryStatusText.setText(getString(R.string.battery_recovery_failed));
                                                            }
                                                        });
                                                    });
                                                })
                                                .setNegativeButton(android.R.string.cancel, null)
                                                .show();
                                    } else {
                                        batteryStatusText.setText(getString(R.string.battery_failed_read_voltage));
                                    }
                                });
                            });
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /** Test 6: Cranking voltage — minimum voltage during engine crank. */
    private void testBatteryCranking() {
        final BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            batteryStatusText.setText(getString(R.string.battery_not_connected));
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_crank_title))
                .setMessage(getString(R.string.battery_crank_msg))
                .setPositiveButton(getString(R.string.battery_crank_start), (d, w) -> {
                    if (activeDriver instanceof SimulationDriver) {
                        ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.CRANKING);
                    }
                    batteryStatusText.setText(getString(R.string.battery_crank_sampling));
                    crankMinVoltage = 999;
                    dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
                    dtcExecutor.submit(() -> {
                        double minV = 999;
                        long endTime = System.currentTimeMillis() + 5000;
                        while (System.currentTimeMillis() < endTime) {
                            double v = readBatteryVoltage();
                            if (v > 0 && v < minV) minV = v;
                            try { Thread.sleep(80); } catch (InterruptedException e) { break; }
                        }
                        crankMinVoltage = (minV < 999) ? minV : -1;
                        runOnUiThread(() -> {
                            if (crankMinVoltage > 0) {
                                double rest = restingVoltage > 0 ? restingVoltage : lastBatteryVoltage;
                                BatteryTester.BatteryTestResult r = BatteryTester.testCrankingVoltage(crankMinVoltage, rest);
                                displayBatteryResult(r);
                            } else {
                                batteryStatusText.setText(getString(R.string.battery_crank_failed));
                            }
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Test 7: Ripple / diode health — fast sampling of voltage. */
    private void testBatteryRipple() {
        final BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            batteryStatusText.setText(getString(R.string.battery_not_connected));
            return;
        }
        if (activeDriver instanceof SimulationDriver) {
            ((SimulationDriver) activeDriver).setSimState(SimulationDriver.SimState.RUNNING);
        }
        batteryStatusText.setText(getString(R.string.battery_ripple_sampling));
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            java.util.List<Double> samples = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                double v = readBatteryVoltage();
                if (v > 0) samples.add(v);
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            runOnUiThread(() -> {
                if (samples.size() >= 5) {
                    rippleSamples.clear();
                    rippleSamples.addAll(samples);
                    BatteryTester.BatteryTestResult r = BatteryTester.testRipple(samples);
                    displayBatteryResult(r);
                } else {
                    batteryStatusText.setText(getString(R.string.battery_ripple_insufficient));
                }
            });
        });
    }

    /** Full diagnostic — runs all available tests and shows a comprehensive report. */
    private void runFullBatteryDiagnostic() {
        final BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            batteryStatusText.setText(getString(R.string.battery_not_connected));
            return;
        }
        BatteryTester.Chemistry chem = getSelectedChemistry();
        final int ageMonths;
        if (batteryAgeInput != null) {
            String ageStr = batteryAgeInput.getText().toString().trim();
            int parsed;
            try { parsed = Integer.parseInt(ageStr); } catch (NumberFormatException e) { parsed = -1; }
            ageMonths = parsed;
        } else {
            ageMonths = -1;
        }
        batteryStatusText.setText(getString(R.string.battery_full_running, chem.getDisplayName(this)));
        batteryScoreCard.setVisibility(View.GONE);
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            if (activeDriver instanceof SimulationDriver) {
                // Auto-populate simulation testing values to generate a full report
                if (restingVoltage <= 0) restingVoltage = 12.62;
                if (runningVoltage <= 0) runningVoltage = 14.12;
                if (crankMinVoltage <= 0 || crankMinVoltage == 999) crankMinVoltage = 9.85;
                if (noLoadVoltage <= 0) noLoadVoltage = 14.12;
                if (fullLoadVoltage <= 0) fullLoadVoltage = 13.72;
                if (postLoadVoltage <= 0) postLoadVoltage = 13.72;
                if (recoveredVoltage <= 0) recoveredVoltage = 14.06;
                if (highRpmVoltage <= 0) highRpmVoltage = 14.32;
                if (rippleSamples.isEmpty()) {
                    rippleSamples.clear();
                    rippleSamples.add(14.10);
                    rippleSamples.add(14.12);
                    rippleSamples.add(14.09);
                    rippleSamples.add(14.13);
                    rippleSamples.add(14.11);
                }
                recoveryDelta = noLoadVoltage - recoveredVoltage;
            }
            // Sample current voltage
            double v = readBatteryVoltage();
            double restV = restingVoltage > 0 ? restingVoltage : (v > 0 ? v : -1);
            double runV = runningVoltage > 0 ? runningVoltage : (v > 0 ? v : -1);
            final double rDelta = recoveryDelta >= 0 ? recoveryDelta : -1;
            final double restVF = restV;
            final double runVF = runV;

            BatteryTester.BatteryReport report = BatteryTester.buildFullReport(
                    restVF, runVF, crankMinVoltage,
                    noLoadVoltage, fullLoadVoltage,
                    postLoadVoltage, recoveredVoltage, 5.0,
                    highRpmVoltage,
                    rippleSamples.isEmpty() ? null : new java.util.ArrayList<>(rippleSamples),
                    -1, -1, -1,  // drain values — need long-term monitoring
                    rDelta, ageMonths,
                    chem, true  // tropical climate default for Thailand
            );

            runOnUiThread(() -> {
                // Clear previous results
                LinearLayout container = findViewById(R.id.batteryResultsContainer);
                if (container != null) {
                    container.removeAllViews();
                    // Keep the status text as first child
                    container.addView(batteryStatusText);
                }
                batteryStatusText.setText("");

                for (BatteryTester.BatteryTestResult r : report.results) {
                    addBatteryResultRow(container, r);
                }

                // Show overall score
                batteryScoreCard.setVisibility(View.VISIBLE);
                batteryGradeText.setText(String.format(Locale.US, "%s  (%d%%)", report.grade, report.overallScore));
                batterySummaryText.setText(report.summary);

                // Find SOH and Life results for the summary cards
                for (BatteryTester.BatteryTestResult r : report.results) {
                    if (r.testName.contains("SOH")) {
                        sohValueText.setText(r.value.replaceAll(" .*", ""));
                        sohGradeText.setText(r.value.replaceAll("^\\d+%\\s*", ""));
                    } else if (r.testName.contains("Life")) {
                        batteryLifeText.setText(r.value + " — " + r.remark);
                    }
                }
            });
        });
    }

    /** Display a single battery test result. */
    private void displayBatteryResult(BatteryTester.BatteryTestResult r) {
        LinearLayout container = findViewById(R.id.batteryResultsContainer);
        if (container != null) {
            container.removeAllViews();
            container.addView(batteryStatusText);
        }
        batteryStatusText.setText("");
        addBatteryResultRow(container, r);
    }

    /** Add a result row to the battery results container. */
    private void addBatteryResultRow(LinearLayout container, BatteryTester.BatteryTestResult r) {
        if (container == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 12, 0, 12);

        TextView nameView = new TextView(this);
        nameView.setText(r.testName);
        nameView.setTextColor(getColorCompat(R.color.text));
        nameView.setTextSize(14f);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView valueView = new TextView(this);
        valueView.setText(r.value);
        valueView.setTextSize(16f);
        valueView.setTypeface(null, android.graphics.Typeface.BOLD);

        int color;
        switch (r.severity) {
            case PASS:  color = getColorCompat(R.color.accent); break;
            case WARN:  color = getColorCompat(R.color.warning); break;
            case FAIL:  color = getColorCompat(R.color.danger); break;
            default:    color = getColorCompat(R.color.muted); break;
        }
        valueView.setTextColor(color);

        TextView remarkView = new TextView(this);
        remarkView.setText(r.remark);
        remarkView.setTextColor(getColorCompat(R.color.muted));
        remarkView.setTextSize(12f);
        remarkView.setSingleLine(false);
        remarkView.setMaxLines(3);

        row.addView(nameView);
        row.addView(valueView);
        row.addView(remarkView);

        // Separator
        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        sep.setBackgroundColor(0x33808080);
        row.addView(sep);

        container.addView(row);
    }

    // --- DTC / VIN / Readiness ---

    private void readDtcs() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            dtcStatusText.setTextColor(getColorCompat(R.color.danger));
            return;
        }
        dtcStatusText.setText("Reading DTCs...");
        dtcStatusText.setTextColor(getColorCompat(R.color.muted));
        dtcListContainer.removeAllViews();

        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            boolean wasPaused = isPaused;
            if (!wasPaused) {
                isPaused = true;
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            try {
                boolean msCan = fordMsCanCheckbox != null && fordMsCanCheckbox.isChecked();
                DtcReader.DtcScanResult scanResult = DtcReader.readAllDtcs(activeDriver, msCan);
                List<DtcCode> stored = scanResult.storedDtcs;
                List<DtcCode> pending = scanResult.pendingDtcs;
                List<DtcCode> permanent = scanResult.permanentDtcs;
                
                // Read Mode 06 — on-board monitor test results
                List<Mode06Result> mode06Results = Mode06Reader.readDiagnostic(activeDriver);
                lastMode06Results.clear();
                lastMode06Results.addAll(mode06Results);
                
                // Read per-DTC freeze frames
                List<FreezeFrameReader.FreezeFrameEntry> perDtcFrames = FreezeFrameReader.readAllFreezeFrames(activeDriver);
                
                // Read Mode 09 — Cal-ID and CVN
                List<Mode09Reader.CalIdEntry> calIds = Mode09Reader.readCalIds(activeDriver);
                List<Mode09Reader.CvnEntry> cvns = Mode09Reader.readCvns(activeDriver);
                
                FreezeFrameData ffData = null;
                if (!stored.isEmpty() || !pending.isEmpty()) {
                    ffData = FreezeFrameReader.readFreezeFrame(activeDriver);
                }
                lastFreezeFrame = ffData;
                
                // Scan comparison — compare with previous scan
                if (dtcHistoryDb == null) {
                    dtcHistoryDb = new DtcHistoryDb(MainActivity.this);
                }
                String vinStr = headerVin.getText().toString().replace("VIN: ", "").trim();
                if (vinStr.isEmpty()) vinStr = "UNKNOWN_VIN";
                List<DtcHistoryDb.DtcHistoryRecord> history = dtcHistoryDb.getHistory(vinStr);
                DtcComparison comparison = DtcComparison.compareWithHistory(history, stored, pending);
                
                lastStoredDtcs.clear();
                lastStoredDtcs.addAll(stored);
                lastPendingDtcs.clear();
                lastPendingDtcs.addAll(pending);
                
                LoggerService.lastStoredDtcs.clear();
                LoggerService.lastStoredDtcs.addAll(stored);
                LoggerService.lastPendingDtcs.clear();
                LoggerService.lastPendingDtcs.addAll(pending);
                LoggerService.lastPermanentDtcs.clear();
                LoggerService.lastPermanentDtcs.addAll(permanent);
                
                String ffJson = ffData != null ? ffData.toJsonObject().toString() : null;
                dtcHistoryDb.saveScan(vinStr, stored, pending, permanent, ffJson);
                
                runOnUiThread(() -> {
                    displayDtcs(stored, pending, permanent, mode06Results, perDtcFrames, calIds, cvns, comparison);
                    displayProtocolScanStatus(scanResult.protocolStatuses, scanResult.modules);
                    updateDtcBadge(stored.size(), pending.size(), permanent.size());
                    displayProFeatures();
                });
            } finally {
                if (!wasPaused) {
                    isPaused = false;
                }
            }
        });
    }

    private void displayDtcs(List<DtcCode> stored, List<DtcCode> pending, List<DtcCode> permanent,
                              List<Mode06Result> mode06Results,
                              List<FreezeFrameReader.FreezeFrameEntry> perDtcFrames,
                              List<Mode09Reader.CalIdEntry> calIds,
                              List<Mode09Reader.CvnEntry> cvns,
                              DtcComparison comparison) {
        dtcListContainer.removeAllViews();
        int total = stored.size() + pending.size() + permanent.size();

        if (stored.isEmpty() && pending.isEmpty() && permanent.isEmpty()) {
            dtcStatusText.setText(getString(R.string.no_dtcs));
            dtcStatusText.setTextColor(getColorCompat(R.color.accent));
            return;
        }

        dtcStatusText.setText(String.format(Locale.US, getString(R.string.dtc_count), total));
        dtcStatusText.setTextColor(getColorCompat(R.color.danger));

        // --- Scan Comparison (NEW / CLEARED) ---
        if (comparison != null && comparison.hasPreviousScan()) {
            if (comparison.hasChanges()) {
                TextView compView = new TextView(this);
                compView.setText("🔄 " + comparison.getSummary());
                compView.setTextColor(getColorCompat(R.color.warning));
                compView.setTextSize(14);
                compView.setPadding(0, 8, 0, 8);
                compView.setTypeface(null, android.graphics.Typeface.BOLD);
                dtcListContainer.addView(compView);

                // Show NEW codes prominently
                if (!comparison.getNewCodes().isEmpty()) {
                    addDtcSection("🆕 NEW Since Last Scan", comparison.getNewCodes(), R.color.danger, "🆕");
                }
                // Show CLEARED codes
                if (!comparison.getClearedCodes().isEmpty()) {
                    addDtcSection("✅ CLEARED Since Last Scan", comparison.getClearedCodes(), R.color.accent, "✅");
                }
            } else if (comparison.getPersistingCodes().size() > 0) {
                TextView compView = new TextView(this);
                compView.setText("ℹ️ Same codes as last scan — " + comparison.getPersistingCodes().size() + " persisting");
                compView.setTextColor(getColorCompat(R.color.muted));
                compView.setTextSize(12);
                compView.setPadding(0, 8, 0, 4);
                dtcListContainer.addView(compView);
            }
        }

        if (!stored.isEmpty()) {
            addDtcSection("Stored DTCs (Active)", stored, R.color.danger, "🔍");
        }
        if (!pending.isEmpty()) {
            addDtcSection("Pending DTCs (Warm-up)", pending, R.color.warning, "⏳");
        }
        if (!permanent.isEmpty()) {
            addDtcSection("Permanent DTCs (History)", permanent, R.color.primary, "🔒");
        }

        // --- Per-DTC Freeze Frames ---
        if (perDtcFrames != null && !perDtcFrames.isEmpty()) {
            TextView ffHeader = new TextView(this);
            ffHeader.setText("❄️ Freeze Frame Snapshots (per DTC)");
            ffHeader.setTextColor(getColorCompat(R.color.accent));
            ffHeader.setTextSize(14);
            ffHeader.setPadding(0, 16, 0, 6);
            ffHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            dtcListContainer.addView(ffHeader);

            for (FreezeFrameReader.FreezeFrameEntry entry : perDtcFrames) {
                LinearLayout ffLayout = new LinearLayout(this);
                ffLayout.setOrientation(LinearLayout.VERTICAL);
                ffLayout.setPadding(12, 8, 12, 8);
                ffLayout.setBackgroundResource(R.drawable.bg_dtc_card);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = 6;
                ffLayout.setLayoutParams(lp);

                TextView ffDtcTitle = new TextView(this);
                ffDtcTitle.setText("  " + entry.getDtcCode() + " — Frame #" + entry.getFrameNumber());
                ffDtcTitle.setTextColor(getColorCompat(R.color.warning));
                ffDtcTitle.setTextSize(12);
                ffDtcTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                ffLayout.addView(ffDtcTitle);

                String[] displayPids = {"0C", "0D", "05", "04", "06", "07", "0B", "0F", "0E", "11"};
                String[] displayNames = {"RPM", "Speed", "Coolant", "Load", "STFT B1", "LTFT B1", "MAP", "IAT", "Timing", "Throttle"};
                java.util.Map<String, Double> vals = entry.getData().getValues();
                for (int i = 0; i < displayPids.length; i++) {
                    if (vals.containsKey(displayPids[i])) {
                        TextView tv = new TextView(this);
                        tv.setText("    " + displayNames[i] + ": " + entry.getData().getFormattedValue(displayPids[i]));
                        tv.setTextColor(getColorCompat(R.color.text));
                        tv.setTextSize(11);
                        tv.setPadding(4, 1, 4, 1);
                        ffLayout.addView(tv);
                    }
                }
                dtcListContainer.addView(ffLayout);
            }
        } else if (lastFreezeFrame != null && !lastFreezeFrame.getValues().isEmpty()) {
            // Fallback: show generic freeze frame if per-DTC not available
            TextView ffTitleView = new TextView(this);
            ffTitleView.setText("❄️ Freeze Frame (Snapshot at Trigger)");
            ffTitleView.setTextColor(getColorCompat(R.color.accent));
            ffTitleView.setTextSize(14);
            ffTitleView.setPadding(0, 16, 0, 6);
            ffTitleView.setTypeface(null, android.graphics.Typeface.BOLD);
            dtcListContainer.addView(ffTitleView);

            LinearLayout ffLayout = new LinearLayout(this);
            ffLayout.setOrientation(LinearLayout.VERTICAL);
            ffLayout.setPadding(12, 12, 12, 12);
            ffLayout.setBackgroundResource(R.color.surface2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            ffLayout.setLayoutParams(lp);

            String[] displayPids = {"0C", "0D", "05", "04", "06", "07", "0B", "0F"};
            String[] displayNames = {"RPM", "Speed", "Coolant", "Load", "STFT B1", "LTFT B1", "MAP", "IAT"};
            java.util.Map<String, Double> ffVals = lastFreezeFrame.getValues();
            for (int i = 0; i < displayPids.length; i++) {
                if (ffVals.containsKey(displayPids[i])) {
                    TextView tv = new TextView(this);
                    tv.setText("  • " + displayNames[i] + ": " + lastFreezeFrame.getFormattedValue(displayPids[i]));
                    tv.setTextColor(getColorCompat(R.color.text));
                    tv.setTextSize(12);
                    tv.setPadding(4, 2, 4, 2);
                    ffLayout.addView(tv);
                }
            }
            dtcListContainer.addView(ffLayout);
        }

        // --- Mode 06 — Monitor Test Results ---
        if (mode06Results != null && !mode06Results.isEmpty()) {
            TextView m06Header = new TextView(this);
            m06Header.setText("🔬 Mode 06 — Monitor Test Results");
            m06Header.setTextColor(getColorCompat(R.color.accent));
            m06Header.setTextSize(14);
            m06Header.setPadding(0, 16, 0, 6);
            m06Header.setTypeface(null, android.graphics.Typeface.BOLD);
            dtcListContainer.addView(m06Header);

            for (Mode06Result m06 : mode06Results) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(12, 6, 12, 6);
                row.setBackgroundResource(R.drawable.bg_dtc_card);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = 4;
                row.setLayoutParams(rowLp);

                // Test name line
                TextView nameView = new TextView(this);
                String passIcon = m06.isPassed() ? "✅" : "❌";
                nameView.setText(passIcon + " " + m06.getMonitorName() + " — " + m06.getTestName());
                nameView.setTextColor(m06.isPassed() ? getColorCompat(R.color.accent) : getColorCompat(R.color.danger));
                nameView.setTextSize(12);
                nameView.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(nameView);

                // Value line
                TextView valView = new TextView(this);
                String unit = m06.getUnit().isEmpty() ? "" : " " + m06.getUnit();
                valView.setText(String.format(Locale.US,
                    "    Value: %.2f%s  |  Min: %.2f  |  Max: %.2f%s",
                    m06.getScaledValue(), unit,
                    m06.getScaledMin(), m06.getScaledMax(), unit));
                valView.setTextColor(getColorCompat(R.color.text));
                valView.setTextSize(11);
                row.addView(valView);

                dtcListContainer.addView(row);
            }
        }

        // --- Mode 09 — Cal-ID / CVN ---
        if ((calIds != null && !calIds.isEmpty()) || (cvns != null && !cvns.isEmpty())) {
            TextView m09Header = new TextView(this);
            m09Header.setText("🔧 ECU Calibration Info (Mode 09)");
            m09Header.setTextColor(getColorCompat(R.color.accent));
            m09Header.setTextSize(14);
            m09Header.setPadding(0, 16, 0, 6);
            m09Header.setTypeface(null, android.graphics.Typeface.BOLD);
            dtcListContainer.addView(m09Header);

            LinearLayout m09Layout = new LinearLayout(this);
            m09Layout.setOrientation(LinearLayout.VERTICAL);
            m09Layout.setPadding(12, 8, 12, 8);
            m09Layout.setBackgroundResource(R.drawable.bg_dtc_card);
            LinearLayout.LayoutParams m09Lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            m09Lp.bottomMargin = 6;
            m09Layout.setLayoutParams(m09Lp);

            if (calIds != null) {
                for (Mode09Reader.CalIdEntry cal : calIds) {
                    TextView tv = new TextView(this);
                    tv.setText("  Cal-ID " + (cal.getEcuIndex() + 1) + ": " + cal.getCalId());
                    tv.setTextColor(getColorCompat(R.color.text));
                    tv.setTextSize(12);
                    tv.setPadding(4, 2, 4, 2);
                    m09Layout.addView(tv);
                }
            }
            if (cvns != null) {
                for (Mode09Reader.CvnEntry cvn : cvns) {
                    TextView tv = new TextView(this);
                    tv.setText("  CVN " + (cvn.getEcuIndex() + 1) + ": " + cvn.getCvn());
                    tv.setTextColor(getColorCompat(R.color.text));
                    tv.setTextSize(12);
                    tv.setPadding(4, 2, 4, 2);
                    m09Layout.addView(tv);
                }
            }
            dtcListContainer.addView(m09Layout);
        }

        // Add Export PDF Report button at the bottom of list if DTCs or freeze frame exist
        Button exportBtn = new Button(this);
        exportBtn.setText("📤 Export PDF Report");
        exportBtn.setOnClickListener(v -> exportDtcReport());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = 16;
        exportBtn.setLayoutParams(btnLp);
        dtcListContainer.addView(exportBtn);
    }

    private void exportDtcReport() {
        String vin = headerVin.getText().toString().replace("VIN: ", "").trim();
        if (vin.isEmpty()) {
            vin = "UNKNOWN_VIN";
        }
        // Gather all diagnostic data for the full report
        ReadinessMonitor readiness = null;
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver != null && activeDriver.isConnected()) {
            readiness = ReadinessMonitor.read(activeDriver);
        }
        List<Mode06Result> mode06 = lastMode06Results;
        List<DtcReader.ModuleInfo> modules = lastAutoScanModules;
        List<DtcReader.ProtocolScanStatus> protocols = lastAutoScanProtocolStatuses;
        String driveCycleGuide = readiness != null ? DriveCycleGuide.getSummary(readiness) : null;

        File pdfFile = DtcReportExporter.exportReportToPdf(this, vin,
            lastStoredDtcs, lastPendingDtcs, lastPermanentDtcs, lastFreezeFrame,
            readiness, mode06, modules, protocols, driveCycleGuide);
        if (pdfFile == null) {
            Toast.makeText(this, "Failed to generate PDF report.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Temporary bypass VmPolicy restriction to share file
        android.os.StrictMode.VmPolicy.Builder builder = new android.os.StrictMode.VmPolicy.Builder();
        android.os.StrictMode.setVmPolicy(builder.build());

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(pdfFile));
        intent.putExtra(Intent.EXTRA_SUBJECT, "OBD2 Vehicle Diagnostic Report - " + vin);
        intent.putExtra(Intent.EXTRA_TEXT, "Attached is the OBD2 PDF diagnostic report for vehicle VIN: " + vin);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share Diagnostic PDF Report"));
        Toast.makeText(this, "PDF Report generated: " + pdfFile.getName(), Toast.LENGTH_LONG).show();
    }

    /**
     * Display protocol scan status — shows which protocol buses responded
     * and what modules were found on each.
     */
    private void displayProtocolScanStatus(List<DtcReader.ProtocolScanStatus> protocolStatuses,
                                           List<DtcReader.ModuleInfo> modules) {
        if (protocolStatuses == null || protocolStatuses.isEmpty()) {
            // Fallback: show modules only
            if (modules != null && !modules.isEmpty()) {
                showModulesList(modules);
            }
            return;
        }

        // ── Protocol Scan Summary ──
        int respondedCnt = 0;
        int totalFound = 0;
        for (DtcReader.ProtocolScanStatus s : protocolStatuses) {
            if (s.responded) respondedCnt++;
            totalFound += s.totalDtcCount;
        }

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeader.setPadding(0, dpToPx(8), 0, dpToPx(8));
        sectionHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);

        View accentBar = new View(this);
        accentBar.setBackgroundColor(getColorCompat(R.color.primary));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dpToPx(4), dpToPx(18));
        accentParams.rightMargin = dpToPx(8);
        accentBar.setLayoutParams(accentParams);
        sectionHeader.addView(accentBar);

        TextView headerView = new TextView(this);
        headerView.setText("🔌 Protocols Scanned: " + protocolStatuses.size() + "  |  "
            + respondedCnt + " responded  |  Total DTCs: " + totalFound);
        headerView.setTextColor(getColorCompat(R.color.accent));
        headerView.setTextSize(13);
        headerView.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionHeader.addView(headerView);
        dtcListContainer.addView(sectionHeader);

        // Per-protocol rows
        for (DtcReader.ProtocolScanStatus s : protocolStatuses) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            row.setBackgroundResource(R.drawable.bg_dtc_card);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dpToPx(2);
            row.setLayoutParams(rowLp);

            // Status icon
            TextView iconView = new TextView(this);
            iconView.setText(s.responded ? (s.totalDtcCount > 0 ? "🔴" : "🟢") : "⚫");
            iconView.setTextSize(12);
            iconView.setPadding(0, 0, dpToPx(8), 0);
            row.addView(iconView);

            // Protocol name
            TextView labelView = new TextView(this);
            String extra = s.responded
                ? (" — " + s.modulesFound + " modules, " + s.totalDtcCount + " DTCs")
                : " — no response";
            labelView.setText(s.bus.label + extra);
            labelView.setTextColor(s.responded ? getColorCompat(R.color.text) : getColorCompat(R.color.muted));
            labelView.setTextSize(11);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            labelView.setLayoutParams(labelLp);
            row.addView(labelView);

            dtcListContainer.addView(row);
        }

        // ── Module list ──
        if (modules != null && !modules.isEmpty()) {
            showModulesList(modules);
        }
    }

    /** Show per-module scan status cards. */
    private void showModulesList(List<DtcReader.ModuleInfo> modules) {
        TextView modHeader = new TextView(this);
        modHeader.setText("🔧 Detected Modules (" + modules.size() + "):");
        modHeader.setTextColor(getColorCompat(R.color.accent));
        modHeader.setTextSize(13);
        modHeader.setPadding(0, dpToPx(12), 0, dpToPx(6));
        modHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        dtcListContainer.addView(modHeader);

        for (DtcReader.ModuleInfo mod : modules) {
            LinearLayout modCard = new LinearLayout(this);
            modCard.setOrientation(LinearLayout.VERTICAL);
            modCard.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
            modCard.setBackgroundResource(R.drawable.bg_dtc_card);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dpToPx(3);
            modCard.setLayoutParams(cardLp);

            // Name row
            TextView nameRow = new TextView(this);
            nameRow.setText("  ID:" + mod.canId + " — " + mod.moduleName);
            nameRow.setTextColor(getColorCompat(R.color.text));
            nameRow.setTextSize(12);
            nameRow.setTypeface(null, android.graphics.Typeface.BOLD);
            modCard.addView(nameRow);

            // Status chips
            LinearLayout chipsRow = new LinearLayout(this);
            chipsRow.setOrientation(LinearLayout.HORIZONTAL);
            chipsRow.setPadding(dpToPx(16), dpToPx(2), 0, 0);
            chipsRow.addView(scanStatusChip("Stored", mod.storedDtcCount, mod.storedOk));
            chipsRow.addView(scanStatusChip("Pending", mod.pendingDtcCount, mod.pendingOk));
            chipsRow.addView(scanStatusChip("Permanent", mod.permanentDtcCount, mod.permanentOk));
            modCard.addView(chipsRow);

            dtcListContainer.addView(modCard);
        }
    }

    /**
     * Deep DTC scan — try ALL protocol buses for exhaustive coverage.
     * Triggered by long-press on the Read DTCs button.
     */
    private void readDtcsDeep() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            dtcStatusText.setTextColor(getColorCompat(R.color.danger));
            return;
        }
        dtcStatusText.setText("🔬 Deep Scan: probing all protocols...");
        dtcStatusText.setTextColor(getColorCompat(R.color.warning));
        dtcListContainer.removeAllViews();
        Toast.makeText(this, "Deep scanning all protocols — this may take 20-30s...", Toast.LENGTH_SHORT).show();

        final boolean msCan = fordMsCanCheckbox != null && fordMsCanCheckbox.isChecked();
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            boolean wasPaused = isPaused;
            if (!wasPaused) {
                isPaused = true;
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            try {
                DtcReader.DtcScanResult scanResult = DtcReader.readAllDtcsDeep(activeDriver, msCan);
                List<DtcCode> stored = scanResult.storedDtcs;
                List<DtcCode> pending = scanResult.pendingDtcs;
                List<DtcCode> permanent = scanResult.permanentDtcs;

                // Read Mode 06
                List<Mode06Result> mode06Results = Mode06Reader.readDiagnostic(activeDriver);
                List<FreezeFrameReader.FreezeFrameEntry> perDtcFrames = FreezeFrameReader.readAllFreezeFrames(activeDriver);
                List<Mode09Reader.CalIdEntry> calIds = Mode09Reader.readCalIds(activeDriver);
                List<Mode09Reader.CvnEntry> cvns = Mode09Reader.readCvns(activeDriver);

                FreezeFrameData ffData = null;
                if (!stored.isEmpty() || !pending.isEmpty()) {
                    ffData = FreezeFrameReader.readFreezeFrame(activeDriver);
                }

                // Scan comparison
                if (dtcHistoryDb == null) dtcHistoryDb = new DtcHistoryDb(MainActivity.this);
                String vinStr = headerVin.getText().toString().replace("VIN: ", "").trim();
                if (vinStr.isEmpty()) vinStr = "UNKNOWN_VIN";
                DtcComparison comparison = DtcComparison.compareWithHistory(
                    dtcHistoryDb.getHistory(vinStr), stored, pending);

                lastStoredDtcs.clear(); lastStoredDtcs.addAll(stored);
                lastPendingDtcs.clear(); lastPendingDtcs.addAll(pending);
                lastPermanentDtcs.clear(); lastPermanentDtcs.addAll(permanent);
                LoggerService.lastStoredDtcs.clear(); LoggerService.lastStoredDtcs.addAll(stored);
                LoggerService.lastPendingDtcs.clear(); LoggerService.lastPendingDtcs.addAll(pending);
                LoggerService.lastPermanentDtcs.clear(); LoggerService.lastPermanentDtcs.addAll(permanent);

                String ffJson = ffData != null ? ffData.toJsonObject().toString() : null;
                dtcHistoryDb.saveScan(vinStr, stored, pending, permanent, ffJson);

                runOnUiThread(() -> {
                    displayDtcs(stored, pending, permanent, mode06Results, perDtcFrames, calIds, cvns, comparison);
                    displayProtocolScanStatus(scanResult.protocolStatuses, scanResult.modules);
                    updateDtcBadge(stored.size(), pending.size(), permanent.size());
                    displayProFeatures();
                });
            } finally {
                if (!wasPaused) {
                    isPaused = false;
                }
            }
        });
    }

    /**
     * Create a small status chip showing scan result for one mode.
     * ● Stored: 2 DTCs  (green=OK, red=has DTCs, grey=no response)
     */
    private TextView scanStatusChip(String label, int count, boolean responded) {
        TextView chip = new TextView(this);
        String status;
        int color;

        if (!responded) {
            status = "⚠ " + label + ": no resp";
            color = getColorCompat(R.color.muted);
        } else if (count > 0) {
            status = "● " + label + ": " + count + " DTC";
            color = getColorCompat(R.color.danger);
        } else {
            status = "✓ " + label + ": clean";
            color = getColorCompat(R.color.accent);
        }

        chip.setText(status);
        chip.setTextColor(color);
        chip.setTextSize(11);
        chip.setPadding(0, 0, dpToPx(16), 0);
        return chip;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void addDtcSection(String title, List<DtcCode> codes, int colorRes, String iconPrefix) {
        // Create container card for this DTC type section
        com.google.android.material.card.MaterialCardView sectionCard = new com.google.android.material.card.MaterialCardView(this);
        sectionCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.surface)));
        sectionCard.setRadius(dpToPx(6));
        sectionCard.setStrokeWidth(dpToPx(1));
        sectionCard.setStrokeColor(getColorCompat(R.color.border));
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dpToPx(16);
        sectionCard.setLayoutParams(cardParams);

        // Content layout inside the section card
        LinearLayout sectionContent = new LinearLayout(this);
        sectionContent.setOrientation(LinearLayout.VERTICAL);
        sectionContent.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

        // Header view inside the section card
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(0, 0, 0, dpToPx(8));
        
        // Left accent strip (colored bar representing severity/type)
        View accentBar = new View(this);
        accentBar.setBackgroundColor(getColorCompat(colorRes));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dpToPx(4), dpToPx(18));
        accentParams.rightMargin = dpToPx(8);
        accentBar.setLayoutParams(accentParams);
        headerLayout.addView(accentBar);

        // Header title
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColorCompat(colorRes));
        titleView.setTextSize(14);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleView.setLayoutParams(titleParams);
        headerLayout.addView(titleView);

        // Count badge
        TextView badgeView = new TextView(this);
        badgeView.setText(String.valueOf(codes.size()));
        badgeView.setTextColor(getColorCompat(R.color.surface)); // white text on colored badge
        badgeView.setTextSize(11);
        badgeView.setTypeface(null, android.graphics.Typeface.BOLD);
        badgeView.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
        badgeView.setBackgroundResource(R.drawable.bg_dtc_badge_pill);
        badgeView.getBackground().setTint(getColorCompat(colorRes));
        headerLayout.addView(badgeView);

        sectionContent.addView(headerLayout);

        // Add a divider under header
        View divider = new View(this);
        divider.setBackgroundColor(getColorCompat(R.color.border));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        divParams.bottomMargin = dpToPx(10);
        divider.setLayoutParams(divParams);
        sectionContent.addView(divider);

        // Add the DTC codes inside the container
        for (DtcCode dtc : codes) {
            // Build expandable DTC card with enrichment data
            LinearLayout cardLayout = new LinearLayout(this);
            cardLayout.setOrientation(LinearLayout.VERTICAL);
            cardLayout.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
            cardLayout.setBackgroundResource(R.drawable.bg_dtc_card);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dpToPx(6); // separation between DTC cards
            cardLayout.setLayoutParams(cardLp);

            // Severity Color Coding
            int severityColor = getColorCompat(R.color.text);
            switch (dtc.getSeverity()) {
                case CRITICAL:
                    severityColor = getColorCompat(R.color.danger);
                    break;
                case WARNING:
                    severityColor = getColorCompat(R.color.warning);
                    break;
                case INFO:
                    severityColor = getColorCompat(R.color.accent);
                    break;
            }

            // Code + description line
            TextView codeView = new TextView(this);
            DtcEnrichment.EnrichmentData enrich = DtcEnrichment.lookup(dtc.getCode());
            String emissionsTag = (enrich != null && enrich.isEmissionsRelated()) ? " [Emissions]" : "";
            String systemTag = (enrich != null && !enrich.getSystem().isEmpty()) ? " — " + enrich.getSystem() : "";
            codeView.setText(iconPrefix + "  " + dtc.getCode() + " — " + dtc.getDescription() + emissionsTag + systemTag);
            codeView.setTextColor(severityColor);
            codeView.setTextSize(13);
            codeView.setClickable(true);
            codeView.setFocusable(true);
            cardLayout.addView(codeView);

            // Show enrichment data if available (causes + fixes)
            if (enrich != null) {
                // Causes
                if (!enrich.getCauses().isEmpty()) {
                    TextView causesView = new TextView(this);
                    StringBuilder causesSb = new StringBuilder("    Possible Causes:\n");
                    for (int ci = 0; ci < enrich.getCauses().size(); ci++) {
                        causesSb.append("      • ").append(enrich.getCauses().get(ci));
                        if (ci < enrich.getCauses().size() - 1) causesSb.append("\n");
                    }
                    causesView.setText(causesSb.toString());
                    causesView.setTextColor(getColorCompat(R.color.text));
                    causesView.setTextSize(11);
                    causesView.setPadding(0, dpToPx(4), 0, dpToPx(2));
                    cardLayout.addView(causesView);
                }

                // Fixes
                if (!enrich.getFixes().isEmpty()) {
                    TextView fixesView = new TextView(this);
                    StringBuilder fixesSb = new StringBuilder("    Suggested Repair:\n");
                    for (int fi = 0; fi < enrich.getFixes().size(); fi++) {
                        fixesSb.append("      ").append(fi + 1).append(". ").append(enrich.getFixes().get(fi));
                        if (fi < enrich.getFixes().size() - 1) fixesSb.append("\n");
                    }
                    fixesView.setText(fixesSb.toString());
                    fixesView.setTextColor(getColorCompat(R.color.accent));
                    fixesView.setTextSize(11);
                    fixesView.setPadding(0, dpToPx(2), 0, dpToPx(4));
                    cardLayout.addView(fixesView);
                }

                // Drive cycles to clear
                if (enrich.getDriveCyclesToClear() > 0) {
                    TextView dcView = new TextView(this);
                    dcView.setText("    Drive cycles to clear: " + enrich.getDriveCyclesToClear());
                    dcView.setTextColor(getColorCompat(R.color.muted));
                    dcView.setTextSize(10);
                    dcView.setPadding(0, dpToPx(2), 0, 0);
                    cardLayout.addView(dcView);
                }
            }

            // Tap to Google search
            cardLayout.setOnClickListener(v -> {
                String rawVin = headerVin.getText().toString().replace("VIN: ", "").trim();
                String vin = (rawVin.isEmpty() || "Unknown".equalsIgnoreCase(rawVin)) ? "" : rawVin;
                BrandYearProfile.Brand brand = BrandYearProfile.brandFromVin(vin);
                String brandStr = (brand != BrandYearProfile.Brand.GENERIC) ? brand.name() : "";

                String query = "OBD2 code " + dtc.getCode();
                if (!brandStr.isEmpty()) {
                    query += " " + brandStr;
                }
                if (dtc.getDescription() != null && !dtc.getDescription().isEmpty()) {
                    query += " " + dtc.getDescription();
                }
                query += " symptoms causes fixes";

                String url = "https://www.google.com/search?q=" + Uri.encode(query);
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
                Toast.makeText(this, "Searching " + dtc.getCode() + " details on Google...", Toast.LENGTH_SHORT).show();
            });

            sectionContent.addView(cardLayout);
        }

        sectionCard.addView(sectionContent);
        dtcListContainer.addView(sectionCard);
    }

    private void showScanHistoryDialog() {
        if (dtcHistoryDb == null) {
            dtcHistoryDb = new DtcHistoryDb(this);
        }
        String rawVin = headerVin.getText().toString().replace("VIN: ", "").trim();
        final String vin = rawVin.isEmpty() ? "UNKNOWN_VIN" : rawVin;
        List<DtcHistoryDb.DtcHistoryRecord> history = dtcHistoryDb.getHistory(vin);
        
        if (history.isEmpty()) {
            Toast.makeText(this, "No history found for this vehicle.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(32, 24, 32, 24);

        String lastDate = "";
        for (DtcHistoryDb.DtcHistoryRecord r : history) {
            if (!r.date.equals(lastDate)) {
                lastDate = r.date;
                TextView dateHeader = new TextView(this);
                dateHeader.setText("📅 " + r.date);
                dateHeader.setTextColor(getColorCompat(R.color.accent));
                dateHeader.setTextSize(14);
                dateHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                dateHeader.setPadding(0, 16, 0, 8);
                container.addView(dateHeader);
            }

            TextView item = new TextView(this);
            String prefix = "stored".equals(r.type) ? "🔴 Stored: " : ("pending".equals(r.type) ? "⏳ Pending: " : ("permanent".equals(r.type) ? "🔒 Permanent: " : "🟢 Clean: "));
            item.setText("  " + prefix + r.code + " - " + r.description);
            item.setTextColor(getColorCompat("clean".equals(r.type) ? R.color.accent : R.color.text));
            item.setTextSize(12);
            item.setPadding(8, 4, 8, 4);
            container.addView(item);
        }

        androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(this);
        scrollView.addView(container);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📜 Diagnostic Scan History")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .setNegativeButton("Clear History", (dialog, which) -> {
                    dtcHistoryDb.clearHistory(vin);
                    Toast.makeText(this, "History cleared for this vehicle.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void clearDtcs() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            return;
        }
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            boolean wasPaused = isPaused;
            if (!wasPaused) {
                isPaused = true;
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            try {
                boolean success = DtcReader.clearDtcs(activeDriver);
                if (success) {
                    lastStoredDtcs.clear();
                    lastPendingDtcs.clear();
                    lastPermanentDtcs.clear();
                    lastFreezeFrame = null;
                    LoggerService.lastStoredDtcs.clear();
                    LoggerService.lastPendingDtcs.clear();
                    LoggerService.lastPermanentDtcs.clear();
                }
                runOnUiThread(() -> {
                    if (success) {
                        dtcStatusText.setText("DTCs cleared. MIL should reset after next drive cycle.");
                        dtcStatusText.setTextColor(getColorCompat(R.color.accent));
                        dtcListContainer.removeAllViews();
                        updateDtcBadge(0, 0, 0);
                    } else {
                        dtcStatusText.setText("Failed to clear DTCs.");
                        dtcStatusText.setTextColor(getColorCompat(R.color.danger));
                    }
                });
            } finally {
                if (!wasPaused) {
                    isPaused = false;
                }
            }
        });
    }

    private void readVin() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            return;
        }
        dtcStatusText.setText("Reading VIN...");
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            boolean wasPaused = isPaused;
            if (!wasPaused) {
                isPaused = true;
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            try {
                String vin = VinReader.readVin(activeDriver);
                runOnUiThread(() -> {
                    if (vin != null) {
                        headerVin.setText("VIN: " + vin);
                        dtcStatusText.setText("VIN: " + vin);
                        dtcStatusText.setTextColor(getColorCompat(R.color.accent));
                    } else {
                        dtcStatusText.setText("VIN not available. Some vehicles don't support Mode 09.");
                        dtcStatusText.setTextColor(getColorCompat(R.color.warning));
                    }
                });
            } finally {
                if (!wasPaused) {
                    isPaused = false;
                }
            }
        });
    }

    private void checkReadiness() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            return;
        }
        dtcStatusText.setText("Checking readiness monitors...");
        readinessContainer.removeAllViews();
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            boolean wasPaused = isPaused;
            if (!wasPaused) {
                isPaused = true;
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            try {
                ReadinessMonitor rm = ReadinessMonitor.read(activeDriver);
                runOnUiThread(() -> displayReadiness(rm));
            } finally {
                if (!wasPaused) {
                    isPaused = false;
                }
            }
        });
    }

    private void displayReadiness(ReadinessMonitor rm) {
        readinessContainer.removeAllViews();

        TextView milView = new TextView(this);
        milView.setText(String.format(Locale.US, "MIL: %s | DTCs: %d | %s",
                rm.isMilOn() ? "ON ⚠" : "OFF ✓",
                rm.getDtcCount(),
                rm.isAllReady() ? "Smog Check: READY ✓" : "Smog Check: NOT READY"));
        milView.setTextColor(getColorCompat(rm.isMilOn() ? R.color.danger : R.color.accent));
        milView.setTextSize(14);
        milView.setPadding(0, 12, 0, 8);
        milView.setTypeface(null, android.graphics.Typeface.BOLD);
        readinessContainer.addView(milView);

        LinearLayout currentRow = null;
        int colCount = 0;

        for (ReadinessMonitor.MonitorStatus m : rm.getMonitors()) {
            if (!m.available) continue;

            if (colCount % 2 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = 8;
                currentRow.setLayoutParams(lp);
                readinessContainer.addView(currentRow);
            }

            TextView mv = new TextView(this);
            String icon = m.complete ? "🟢  " : "🔴  ";
            mv.setText(icon + m.name);
            mv.setTextColor(getColorCompat(m.complete ? R.color.text : R.color.warning));
            mv.setTextSize(12);
            mv.setPadding(12, 10, 12, 10);
            mv.setBackgroundResource(R.color.surface2);

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (colCount % 2 == 0) {
                cellLp.rightMargin = 8;
            }
            mv.setLayoutParams(cellLp);

            currentRow.addView(mv);
            colCount++;
        }

        // ── Drive Cycle Guidance for incomplete monitors ──
        List<DriveCycleGuide.DriveCycleStep> steps = DriveCycleGuide.getGuidance(rm);
        if (!steps.isEmpty()) {
            TextView guideHeader = new TextView(this);
            guideHeader.setText("🚗 Drive Cycle Guidance (" + steps.size() + " incomplete)");
            guideHeader.setTextColor(getColorCompat(R.color.warning));
            guideHeader.setTextSize(13);
            guideHeader.setPadding(0, 16, 0, 6);
            guideHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            readinessContainer.addView(guideHeader);

            for (DriveCycleGuide.DriveCycleStep step : steps) {
                TextView stepView = new TextView(this);
                stepView.setText("  ● " + step.monitorName + " (~" + step.estimatedMinutes + " min):\n    " + step.instruction);
                stepView.setTextColor(getColorCompat(R.color.text));
                stepView.setTextSize(11);
                stepView.setPadding(16, 4, 8, 4);
                readinessContainer.addView(stepView);
            }
        }

        // ── DTC → Monitor correlation ──
        if (!lastStoredDtcs.isEmpty()) {
            String correlation = DtcMonitorCorrelation.describeAffectedMonitors(lastStoredDtcs);
            TextView corrView = new TextView(this);
            corrView.setText("🔗 " + correlation);
            corrView.setTextColor(getColorCompat(R.color.muted));
            corrView.setTextSize(11);
            corrView.setPadding(0, 12, 0, 8);
            readinessContainer.addView(corrView);
        }

        dtcStatusText.setText("Readiness check complete.");
        dtcStatusText.setTextColor(getColorCompat(R.color.muted));
    }

    // ─────────────────────────────────────────────────────────────
    //  Pro Scanner Features UI
    // ─────────────────────────────────────────────────────────────

    /**
     * Display pro scanner features section after DTC scan results:
     * - In-use performance tracking (Mode 09 PID 0D/0E/0F)
     * - Bi-directional control button (Mode 08)
     * - Enhanced mode scan button (Mode 21/22)
     * - Per-ECU physical addressing scan button
     */
    private void displayProFeatures() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) return;

        // ── Pro Features header ──
        TextView proHeader = new TextView(this);
        proHeader.setText("🔧 Pro Scanner Features");
        proHeader.setTextColor(getColorCompat(R.color.accent));
        proHeader.setTextSize(14);
        proHeader.setPadding(0, 16, 0, 8);
        proHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        dtcListContainer.addView(proHeader);

        // ── In-Use Performance Tracking ──
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            try {
                Mode09Reader.InUsePerformance perf = Mode09Reader.readInUsePerformance(activeDriver);
                runOnUiThread(() -> {
                    if (perf.ignitionCycles >= 0 || perf.obdTripCount >= 0) {
                        TextView perfView = new TextView(this);
                        perfView.setText("📊 In-Use Performance Since Clear:\n"
                            + "  Ignitions: " + (perf.ignitionCycles >= 0 ? perf.ignitionCycles : "N/A") + "\n"
                            + "  OBD Trips: " + (perf.obdTripCount >= 0 ? perf.obdTripCount : "N/A") + "\n"
                            + "  Distance: " + (perf.distanceSinceClearKm >= 0 ? perf.distanceSinceClearKm + " km" : "N/A") + "\n"
                            + "  Engine Time: " + (perf.timeSinceClearMin >= 0 ? perf.timeSinceClearMin + " min" : "N/A"));
                        perfView.setTextColor(getColorCompat(R.color.text));
                        perfView.setTextSize(11);
                        perfView.setPadding(16, 8, 8, 8);
                        perfView.setBackgroundResource(R.color.surface2);
                        LinearLayout.LayoutParams perfLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        perfLp.bottomMargin = 6;
                        perfView.setLayoutParams(perfLp);
                        dtcListContainer.addView(perfView);
                    }
                });
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "In-use performance read failed", e);
            }
        });

        // ── Bi-Directional Control (Mode 08) button ──
        Button btnMode08 = new Button(this);
        btnMode08.setText("🎮 Bi-Directional Control (Mode 08)");
        btnMode08.setTextSize(12);
        btnMode08.setOnClickListener(v -> showMode08Dialog());
        LinearLayout.LayoutParams m08Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        m08Lp.bottomMargin = 8;
        m08Lp.topMargin = 4;
        btnMode08.setLayoutParams(m08Lp);
        dtcListContainer.addView(btnMode08);

        // ── Enhanced Mode Scan button ──
        Button btnEnhanced = new Button(this);
        btnEnhanced.setText("🔍 Enhanced Scan (Manufacturer Codes)");
        btnEnhanced.setTextSize(12);
        btnEnhanced.setOnClickListener(v -> runEnhancedScan());
        LinearLayout.LayoutParams enhLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        enhLp.bottomMargin = 8;
        btnEnhanced.setLayoutParams(enhLp);
        dtcListContainer.addView(btnEnhanced);

        // ── Per-ECU Physical Addressing Scan button ──
        Button btnEcuScan = new Button(this);
        btnEcuScan.setText("📡 Per-ECU Scan (Physical Addressing)");
        btnEcuScan.setTextSize(12);
        btnEcuScan.setOnClickListener(v -> runPerEcuScan());
        LinearLayout.LayoutParams ecuLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ecuLp.bottomMargin = 8;
        btnEcuScan.setLayoutParams(ecuLp);
        dtcListContainer.addView(btnEcuScan);
    }

    /**
     * Show bi-directional control dialog for Mode 08 tests.
     */
    private void showMode08Dialog() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] testNames = new String[Mode08Controller.STANDARD_TESTS.size()];
        String[] testIds = new String[Mode08Controller.STANDARD_TESTS.size()];
        int i = 0;
        for (Map.Entry<String, Mode08Controller.TestId> entry : Mode08Controller.STANDARD_TESTS.entrySet()) {
            testNames[i] = entry.getValue().name + " (TID " + entry.getKey() + ")";
            testIds[i] = entry.getKey();
            i++;
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle("🎮 Bi-Directional Control (Mode 08)\n⚠ Some tests activate physical components")
            .setItems(testNames, (dialog, which) -> {
                String tid = testIds[which];
                String testName = Mode08Controller.getTestName(tid);
                String testDesc = Mode08Controller.getTestDescription(tid);

                new android.app.AlertDialog.Builder(this)
                    .setTitle("Run: " + testName)
                    .setMessage(testDesc + "\n\nProceed?")
                    .setPositiveButton("Run Test", (d2, w2) -> {
                        dtcExecutor.submit(() -> {
                            boolean ok = Mode08Controller.runTest(activeDriver, tid);
                            runOnUiThread(() -> {
                                Toast.makeText(this,
                                    ok ? "✅ " + testName + " — acknowledged" : "❌ " + testName + " — not supported or failed",
                                    Toast.LENGTH_LONG).show();
                            });
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            })
            .setNeutralButton("Cancel All Tests", (dialog, which) -> {
                dtcExecutor.submit(() -> {
                    Mode08Controller.cancelAllTests(activeDriver);
                    runOnUiThread(() -> Toast.makeText(this, "All tests cancelled", Toast.LENGTH_SHORT).show());
                });
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /**
     * Run enhanced (manufacturer-specific) mode scan.
     * Detects brand from VIN and runs appropriate enhanced modes.
     */
    private void runEnhancedScan() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        String vin = headerVin.getText().toString().replace("VIN: ", "").trim();
        VinBrandDetector.Brand brand = VinBrandDetector.detect(vin);
        String brandName = VinBrandDetector.getBrandName(brand);

        Toast.makeText(this, "Enhanced scan: " + brandName + "...", Toast.LENGTH_SHORT).show();
        dtcExecutor.submit(() -> {
            List<DtcCode> enhanced = DtcReader.scanEnhancedForBrand(activeDriver,
                brandName.equalsIgnoreCase("Unknown") ? null : brandName.toLowerCase());

            runOnUiThread(() -> {
                if (enhanced.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText("🔍 Enhanced scan: No manufacturer-specific codes found.\n(Not all vehicles/adapters support enhanced modes.)");
                    tv.setTextColor(getColorCompat(R.color.muted));
                    tv.setTextSize(11);
                    tv.setPadding(16, 8, 8, 8);
                    dtcListContainer.addView(tv);
                } else {
                    TextView tv = new TextView(this);
                    tv.setText("🔍 Enhanced Scan Results (" + enhanced.size() + " codes):");
                    tv.setTextColor(getColorCompat(R.color.accent));
                    tv.setTextSize(13);
                    tv.setTypeface(null, android.graphics.Typeface.BOLD);
                    tv.setPadding(0, 12, 0, 6);
                    dtcListContainer.addView(tv);
                    for (DtcCode c : enhanced) {
                        TextView codeView = new TextView(this);
                        codeView.setText("  ● " + c.getCode() + " — " + c.getDescription());
                        codeView.setTextColor(getColorCompat(R.color.danger));
                        codeView.setTextSize(11);
                        codeView.setPadding(16, 2, 8, 2);
                        dtcListContainer.addView(codeView);
                    }
                }
            });
        });
    }

    /**
     * Run per-ECU physical addressing scan.
     * Scans each known ECU individually using ATSH/ATCRA.
     */
    private void runPerEcuScan() {
        BaseDriver activeDriver = getActiveDriver();
        if (activeDriver == null || !activeDriver.isConnected()) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Per-ECU scan: querying individual modules...", Toast.LENGTH_SHORT).show();
        dtcExecutor.submit(() -> {
            List<DtcCode> allCodes = new ArrayList<>();
            List<String> scannedEcus = new ArrayList<>();

            for (Map.Entry<String, String> entry : DtcReader.ECU_TX_RX_PAIRS.entrySet()) {
                String tx = entry.getKey();
                String rx = entry.getValue();
                List<DtcCode> codes = DtcReader.scanEcuDirectly(activeDriver, tx, rx);
                if (!codes.isEmpty()) {
                    scannedEcus.add(tx + "→" + rx + ": " + codes.size() + " DTCs");
                    allCodes.addAll(codes);
                }
            }

            runOnUiThread(() -> {
                TextView header = new TextView(this);
                header.setText("📡 Per-ECU Scan Results");
                header.setTextColor(getColorCompat(R.color.accent));
                header.setTextSize(13);
                header.setTypeface(null, android.graphics.Typeface.BOLD);
                header.setPadding(0, 12, 0, 6);
                dtcListContainer.addView(header);

                if (scannedEcus.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText("  No DTCs found from individual ECU scans.\n  (Some modules may not respond to physical addressing.)");
                    tv.setTextColor(getColorCompat(R.color.muted));
                    tv.setTextSize(11);
                    tv.setPadding(16, 4, 8, 8);
                    dtcListContainer.addView(tv);
                } else {
                    for (String s : scannedEcus) {
                        TextView tv = new TextView(this);
                        tv.setText("  ● " + s);
                        tv.setTextColor(getColorCompat(R.color.text));
                        tv.setTextSize(11);
                        tv.setPadding(16, 2, 8, 2);
                        dtcListContainer.addView(tv);
                    }
                    if (!allCodes.isEmpty()) {
                        TextView codesHeader = new TextView(this);
                        codesHeader.setText("  Additional DTCs from per-ECU scan (" + allCodes.size() + "):");
                        codesHeader.setTextColor(getColorCompat(R.color.warning));
                        codesHeader.setTextSize(11);
                        codesHeader.setPadding(0, 8, 0, 4);
                        dtcListContainer.addView(codesHeader);
                        for (DtcCode c : allCodes) {
                            TextView tv = new TextView(this);
                            tv.setText("    " + c.getCode() + " — " + c.getDescription());
                            tv.setTextColor(getColorCompat(R.color.danger));
                            tv.setTextSize(11);
                            tv.setPadding(16, 1, 8, 1);
                            dtcListContainer.addView(tv);
                        }
                    }
                }
            });
        });
    }

    // --- Config ---

    private LoggerConfig readConfigFromUi() {
        LoggerConfig config = new LoggerConfig();
        config.context = getApplicationContext();
        config.transportMode = transportModeFromSpinner(transportSpinner.getSelectedItemPosition());
        config.fuelMode = fuelPositionToMode(fuelSpinner.getSelectedItemPosition());
        config.obdProtocol = obdProtocolFromSpinner(obdProtocolSpinner.getSelectedItemPosition());
        config.wifiIp = text(wifiIpInput, "192.168.0.10");
        config.wifiPort = intText(wifiPortInput, 35000);
        config.baud = intText(baudInput, 115200);
        config.bluetoothDevice = selectedBluetoothDevice();
        double seconds = doubleText(intervalInput, 0.5);
        config.sampleIntervalMs = Math.max(50, (long) Math.round(seconds * 1000.0));
        config.lpgOnlyMode = lpgOnlyCheckbox.isChecked();
        config.enableApiServer = apiServerCheckbox.isChecked();
        config.fordMsCanEnabled = fordMsCanCheckbox != null && fordMsCanCheckbox.isChecked();
        config.showTurboBoost = turboBoostCheckbox == null || turboBoostCheckbox.isChecked();
        config.showFuelConsumption = fuelEconomyCheckbox == null || fuelEconomyCheckbox.isChecked();
        config.dpfMonitorEnabled = dpfMonitorCheckbox != null && dpfMonitorCheckbox.isChecked();
        config.customPidsEnabled = customPidCheckbox != null && customPidCheckbox.isChecked();
        config.showAirDensity = airDensityCheckbox == null || airDensityCheckbox.isChecked();
        return config;
    }

    private void setConfigUiEnabled(boolean enabled) {
        if (fuelSpinner != null) fuelSpinner.setEnabled(enabled);
        if (transportSpinner != null) transportSpinner.setEnabled(enabled);
        if (obdProtocolSpinner != null) obdProtocolSpinner.setEnabled(enabled);
        if (wifiIpInput != null) wifiIpInput.setEnabled(enabled);
        if (wifiPortInput != null) wifiPortInput.setEnabled(enabled);
        if (baudInput != null) baudInput.setEnabled(enabled);
        if (intervalInput != null) intervalInput.setEnabled(enabled);
        if (backgroundLoggingCheckbox != null) backgroundLoggingCheckbox.setEnabled(enabled);
        if (lpgOnlyCheckbox != null) lpgOnlyCheckbox.setEnabled(enabled);
        if (apiServerCheckbox != null) apiServerCheckbox.setEnabled(enabled);
        if (fordMsCanCheckbox != null) fordMsCanCheckbox.setEnabled(enabled);
        if (turboBoostCheckbox != null) turboBoostCheckbox.setEnabled(enabled);
        if (fuelEconomyCheckbox != null) fuelEconomyCheckbox.setEnabled(enabled);
        if (dpfMonitorCheckbox != null) dpfMonitorCheckbox.setEnabled(enabled);
        if (customPidCheckbox != null) customPidCheckbox.setEnabled(enabled);
        if (airDensityCheckbox != null) airDensityCheckbox.setEnabled(enabled);
        if (btnSelectLogFolder != null) btnSelectLogFolder.setEnabled(enabled);
        
        if (bluetoothDeviceSpinner != null) {
            if (enabled) {
                int pos = transportSpinner != null ? transportSpinner.getSelectedItemPosition() : 0;
                bluetoothDeviceSpinner.setEnabled(pos == 2 || pos == 3 || pos == 4);
            } else {
                bluetoothDeviceSpinner.setEnabled(false);
            }
        }
        if (bluetoothHintText != null) {
            if (enabled) {
                int pos = transportSpinner != null ? transportSpinner.getSelectedItemPosition() : 0;
                bluetoothHintText.setEnabled(pos == 2 || pos == 3 || pos == 4);
            } else {
                bluetoothHintText.setEnabled(false);
            }
        }

        if (btnReadDtc != null) btnReadDtc.setEnabled(true);
        if (btnClearDtc != null) btnClearDtc.setEnabled(true);
        if (btnReadVin != null) btnReadVin.setEnabled(true);
        if (btnReadiness != null) btnReadiness.setEnabled(true);

        if (btnBatteryResting != null) btnBatteryResting.setEnabled(true);
        if (btnBatteryAlternator != null) btnBatteryAlternator.setEnabled(true);
        if (btnBatteryLoad != null) btnBatteryLoad.setEnabled(true);
        if (btnBatteryCrank != null) btnBatteryCrank.setEnabled(true);
        if (btnBatteryRipple != null) btnBatteryRipple.setEnabled(true);
        if (btnBatteryFull != null) btnBatteryFull.setEnabled(true);
    }

    private ObdProtocol obdProtocolFromSpinner(int position) {
        ObdProtocol[] protocols = ObdProtocol.values();
        if (position >= 0 && position < protocols.length) return protocols[position];
        return ObdProtocol.AUTO;
    }

    private TransportMode transportModeFromSpinner(int position) {
        switch (position) {
            case 1: return TransportMode.WIFI;
            case 2: return TransportMode.SERIAL;
            case 3: return TransportMode.BLE;
            case 4: return TransportMode.USB;
            case 5: return TransportMode.AUTO;
            case 0:
            default: return TransportMode.SIM;
        }
    }

    // --- Rendering ---



    private final java.util.Map<String, TextView> readingRowCache = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> readingStatusCache = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> gaugeRowCache = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> gaugeStatusCache = new java.util.HashMap<>();

    private void showPidFilterDialog() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.surface));
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        android.widget.TextView titleText = new android.widget.TextView(this);
        titleText.setText("เลือก PID ที่จะแสดง / Select PIDs to Display");
        titleText.setTextColor(getColorCompat(R.color.text));
        titleText.setTextSize(18);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleText.setPadding(0, 0, 0, (int)(4 * getResources().getDisplayMetrics().density));
        root.addView(titleText);

        android.widget.TextView subTitle = new android.widget.TextView(this);
        subTitle.setText("เลือกเฉพาะ PID ที่ต้องการเห็นในหน้า Live Readings\nช่วยลดโหลด UI เมื่อใช้อะแดปเตอร์ช้า");
        subTitle.setTextColor(getColorCompat(R.color.muted));
        subTitle.setTextSize(12);
        subTitle.setPadding(0, 0, 0, (int)(8 * getResources().getDisplayMetrics().density));
        root.addView(subTitle);

        // "Show All" button
        com.google.android.material.button.MaterialButton showAllBtn =
                new com.google.android.material.button.MaterialButton(this);
        showAllBtn.setText("แสดงทั้งหมด (Show All PIDs)");
        showAllBtn.setTextColor(getColorCompat(R.color.background));
        showAllBtn.setCornerRadius(8);
        com.google.android.material.button.MaterialButton toggleDerivedBtn =
                new com.google.android.material.button.MaterialButton(this);
        toggleDerivedBtn.setText(hideDerivedSensors ? "แสดง Derived Sensors (AAD/MAD/BAD...)" : "ซ่อน Derived Sensors");
        toggleDerivedBtn.setTextColor(getColorCompat(R.color.accent));
        toggleDerivedBtn.setCornerRadius(8);
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams showAllLp = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        showAllBtn.setLayoutParams(showAllLp);
        toggleDerivedBtn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(showAllBtn);
        btnRow.addView(toggleDerivedBtn);
        btnRow.setPadding(0, 0, 0, (int)(12 * getResources().getDisplayMetrics().density));
        root.addView(btnRow);

        // Search bar
        android.widget.EditText searchBar = new android.widget.EditText(this);
        searchBar.setHint("ค้นหา PID... (Search)");
        searchBar.setHintTextColor(getColorCompat(R.color.muted));
        searchBar.setTextColor(getColorCompat(R.color.text));
        android.graphics.drawable.GradientDrawable searchGd = new android.graphics.drawable.GradientDrawable();
        searchGd.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        searchGd.setColor(getColorCompat(R.color.surface3));
        searchBar.setBackground(searchGd);
        searchBar.setTextSize(14);
        searchBar.setPadding(padding, padding / 2, padding, padding / 2);
        searchBar.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams searchParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(0, 0, 0, (int)(8 * getResources().getDisplayMetrics().density));
        searchBar.setLayoutParams(searchParams);
        root.addView(searchBar);

        // Checkbox list
        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(android.R.color.transparent);
        android.widget.LinearLayout.LayoutParams listParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (350 * getResources().getDisplayMetrics().density));
        listView.setLayoutParams(listParams);
        root.addView(listView);

        // Build list of all PIDs (raw + derived)
        java.util.List<PIDDefinition> allPids = PIDCatalogue.getAll();
        // Also add derived sensor keys that aren't in the catalogue
        java.util.List<String> derivedKeys = new java.util.ArrayList<>();
        derivedKeys.add("derived_fuel_kmL");
        derivedKeys.add("derived_fuel_l100");
        derivedKeys.add("derived_boost_kpa");
        derivedKeys.add("derived_boost_psi");
        derivedKeys.add("derived_dpf_health");
        derivedKeys.add("derived_dpf_regen");
        derivedKeys.add("derived_aad");
        derivedKeys.add("derived_mad");
        derivedKeys.add("derived_bad");
        derivedKeys.add("derived_density_pct");
        derivedKeys.add("derived_density_alt");
        derivedKeys.add("derived_sae_cf");
        derivedKeys.add("derived_grains");
        derivedKeys.add("derived_humidity");
        derivedKeys.add("derived_omd");
        derivedKeys.add("derived_compressor_eff");
        derivedKeys.add("derived_intercooler_eff");
        derivedKeys.add("derived_ve");
        derivedKeys.add("derived_dcafr");
        derivedKeys.add("derived_tmf");
        derivedKeys.add("derived_maf_dev");
        derivedKeys.add("derived_lvd");
        derivedKeys.add("derived_eff_density");
        derivedKeys.add("derived_ecc_dt");
        derivedKeys.add("derived_ecc_mad");
        derivedKeys.add("derived_pdi");
        derivedKeys.add("derived_sae_j607");
        derivedKeys.add("derived_sae_cf_delta");

        java.util.List<String> allKeys = new java.util.ArrayList<>();
        java.util.Map<String, String> keyToName = new java.util.HashMap<>();
        for (PIDDefinition p : allPids) {
            allKeys.add(p.key());
            keyToName.put(p.key(), p.getName());
        }
        for (String dk : derivedKeys) {
            if (!allKeys.contains(dk)) {
                allKeys.add(dk);
                keyToName.put(dk, dk.replace("derived_", "").replace("_", " ").toUpperCase());
            }
        }

        // Current filter state
        if (visiblePidsFilter == null) {
            visiblePidsFilter = new java.util.HashSet<>();
        }
        final java.util.Set<String> filterSet = visiblePidsFilter;

        // Adapter
        class FilterAdapter extends android.widget.BaseAdapter {
            java.util.List<String> filteredKeys = new java.util.ArrayList<>(allKeys);

            void filter(String text) {
                filteredKeys.clear();
                if (text.isEmpty()) {
                    filteredKeys.addAll(allKeys);
                } else {
                    String q = text.toLowerCase();
                    for (String k : allKeys) {
                        String name = keyToName.getOrDefault(k, k);
                        if (k.toLowerCase().contains(q) || name.toLowerCase().contains(q)) {
                            filteredKeys.add(k);
                        }
                    }
                }
                notifyDataSetChanged();
            }

            @Override public int getCount() { return filteredKeys.size(); }
            @Override public Object getItem(int pos) { return filteredKeys.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, android.view.ViewGroup parent) {
                String key = filteredKeys.get(pos);
                String name = keyToName.getOrDefault(key, key);
                boolean isDerived = key.startsWith("derived_");
                boolean isChecked = filterSet.contains(key);

                android.widget.CheckBox cb = new android.widget.CheckBox(MainActivity.this);
                cb.setText(name + "\n  (" + key + ")");
                cb.setTextSize(13);
                cb.setTextColor(getColorCompat(R.color.text));
                cb.setChecked(isChecked);
                cb.setTag(key);
                cb.setPadding((int)(8 * getResources().getDisplayMetrics().density), (int)(6 * getResources().getDisplayMetrics().density), 0, (int)(6 * getResources().getDisplayMetrics().density));
                cb.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) {
                        filterSet.add((String) button.getTag());
                    } else {
                        filterSet.remove((String) button.getTag());
                    }
                });
                if (isDerived) {
                    cb.setAlpha(0.7f);
                }
                return cb;
            }
        }

        FilterAdapter adapter = new FilterAdapter();
        listView.setAdapter(adapter);

        searchBar.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { adapter.filter(s.toString()); }
        });

        showAllBtn.setOnClickListener(v -> {
            filterSet.clear();
            filterSet.addAll(allKeys);
            adapter.notifyDataSetChanged();
            updateFilterStatusText();
        });

        toggleDerivedBtn.setOnClickListener(v -> {
            hideDerivedSensors = !hideDerivedSensors;
            getSharedPreferences("OBD2Prefs", MODE_PRIVATE).edit().putBoolean("pref_hide_derived", hideDerivedSensors).apply();
            toggleDerivedBtn.setText(hideDerivedSensors ? "แสดง Derived Sensors" : "ซ่อน Derived Sensors");
            updateFilterStatusText();
        });

        // Save filter on dismiss
        dialog.setOnDismissListener(d -> {
            getSharedPreferences("OBD2Prefs", MODE_PRIVATE)
                .edit()
                .putStringSet("pref_pid_filter", filterSet)
                .apply();
            updateFilterStatusText();
            clearReadings();
        });

        dialog.setContentView(root);
        dialog.show();
    }

    private void updateFilterStatusText() {
        if (txtFilterStatus == null) return;
        int total = visiblePidsFilter != null ? visiblePidsFilter.size() : 0;
        if (total == 0) {
            txtFilterStatus.setText(hideDerivedSensors ? "Showing raw PIDs (derived hidden)" : "Showing all PIDs");
        } else {
            txtFilterStatus.setText(total + " PIDs selected");
        }
    }

    private void updateAirDensityPanel(DataRecord record) {
        if (airDensityCard == null) return;

        // Show/hide panel based on config
        boolean showAir = (activeInProcessConfig != null && activeInProcessConfig.showAirDensity)
                || (LoggerService.isLoggingActive() && LoggerService.getInstance() != null
                    && LoggerService.getInstance().getConfig() != null
                    && LoggerService.getInstance().getConfig().showAirDensity);
        airDensityCard.setVisibility(showAir ? View.VISIBLE : View.GONE);
        if (!showAir) return;

        // Extract air density values from record samples
        Double aad = null, mad = null, bad = null, densPct = null, densAlt = null, saeCF = null;
        Double omd = null, compEff = null, icEff = null, ve = null, pdi = null, grains = null;
        Double humidity = null;

        for (SensorSample s : record.getSamples()) {
            String key = s.getPidKey();
            if (key == null) continue;
            switch (key) {
                case "derived_aad": aad = s.getValue(); break;
                case "derived_mad": mad = s.getValue(); break;
                case "derived_bad": bad = s.getValue(); break;
                case "derived_density_pct": densPct = s.getValue(); break;
                case "derived_density_alt": densAlt = s.getValue(); break;
                case "derived_sae_cf": saeCF = s.getValue(); break;
                case "derived_omd": omd = s.getValue(); break;
                case "derived_compressor_eff": compEff = s.getValue(); break;
                case "derived_intercooler_eff": icEff = s.getValue(); break;
                case "derived_ve": ve = s.getValue(); break;
                case "derived_pdi": pdi = s.getValue(); break;
                case "derived_grains": grains = s.getValue(); break;
                case "derived_humidity": humidity = s.getValue(); break;
            }
        }

        if (txtAAD != null) txtAAD.setText(aad != null ? String.format(Locale.US, "%.1f", aad) : "--");
        if (txtMAD != null) txtMAD.setText(mad != null ? String.format(Locale.US, "%.1f", mad) : "--");
        if (txtBAD != null) txtBAD.setText(bad != null ? String.format(Locale.US, "%.1f", bad) : "--");
        if (txtDensityPct != null) txtDensityPct.setText(densPct != null ? String.format(Locale.US, "%.0f%%", densPct) : "--");
        if (txtDensityAlt != null) txtDensityAlt.setText(densAlt != null ? String.format(Locale.US, "%,d", Math.round(densAlt)) : "--");
        if (txtSAECF != null) txtSAECF.setText(saeCF != null ? String.format(Locale.US, "%.3f", saeCF) : "--");
        if (txtOMD != null) txtOMD.setText(omd != null ? String.format(Locale.US, "%.1f", omd) : "--");
        if (txtCompEff != null) txtCompEff.setText(compEff != null ? String.format(Locale.US, "%.0f%%", compEff) : "--");
        if (txtICEff != null) txtICEff.setText(icEff != null ? String.format(Locale.US, "%.0f%%", icEff) : "--");
        if (txtVE != null) txtVE.setText(ve != null ? String.format(Locale.US, "%.0f%%", ve) : "--");
        if (txtPDI != null) txtPDI.setText(pdi != null ? String.format(Locale.US, "%.2f", pdi) : "--");
        if (txtGrains != null) txtGrains.setText(grains != null ? String.format(Locale.US, "%.0f", grains) : "--");

        // Weather info in header
        if (txtAirDensityWeather != null && humidity != null) {
            Double baro = valueByKey(record, "01_33");
            Double ambient = valueByKey(record, "01_46");
            String weatherStr = String.format(Locale.US, "RH: %.0f%%", humidity);
            if (ambient != null) weatherStr += String.format(Locale.US, " • %.0f°C", ambient);
            if (baro != null) weatherStr += String.format(Locale.US, " • %.0fkPa", baro);
            txtAirDensityWeather.setText(weatherStr);
        }
    }

    private void renderReadings(DataRecord record) {
        updateGridContainer(readingsContainer, readingRowCache, readingStatusCache, record, false);
        updateGridContainer(gaugeReadingsContainer, gaugeRowCache, gaugeStatusCache, record, true);
    }

    private void updateGridContainer(LinearLayout container, java.util.Map<String, TextView> rowCache, java.util.Map<String, TextView> statusCache, DataRecord record, boolean isGauge) {
        if (container == null) return;
        // Count visible samples (after filtering) for child count check
        int visibleCount = 0;
        for (SensorSample sample : record.getSamples()) {
            String pidKey = sample.getPidKey();
            if (visiblePidsFilter != null && !visiblePidsFilter.isEmpty()) {
                if (!visiblePidsFilter.contains(pidKey)) continue;
            } else if (hideDerivedSensors && pidKey != null && pidKey.startsWith("derived_")) {
                continue;
            }
            visibleCount++;
        }
        if (container.getChildCount() != (visibleCount + 1) / 2) {
            container.removeAllViews();
            rowCache.clear();
            statusCache.clear();
        }

        LinearLayout currentRow = null;
        int index = 0;
        int margin = (int) (4 * getResources().getDisplayMetrics().density);

        for (SensorSample sample : record.getSamples()) {
            String pidName = sample.getName();
            String pidKey = sample.getPidKey();

            // ── PID Filter: skip samples the user didn't select ──
            if (visiblePidsFilter != null && !visiblePidsFilter.isEmpty()) {
                if (!visiblePidsFilter.contains(pidKey)) continue;
            } else if (hideDerivedSensors && pidKey != null && pidKey.startsWith("derived_")) {
                continue;
            }

            Double val = sample.getValue();

            // Track Min/Max/Avg values
            if (val != null) {
                Double currentMin = pidMinValues.get(pidKey);
                if (currentMin == null || val < currentMin) {
                    pidMinValues.put(pidKey, val);
                }
                Double currentMax = pidMaxValues.get(pidKey);
                if (currentMax == null || val > currentMax) {
                    pidMaxValues.put(pidKey, val);
                }
                Double currentSum = pidSumValues.get(pidKey);
                pidSumValues.put(pidKey, (currentSum != null ? currentSum : 0.0) + val);
                Integer currentCount = pidCountValues.get(pidKey);
                pidCountValues.put(pidKey, (currentCount != null ? currentCount : 0) + 1);
            }

            TextView valueView = rowCache.get(pidName);
            TextView statusView = statusCache.get(pidName);

            if (valueView == null) {
                if (index % 2 == 0) {
                    currentRow = new LinearLayout(this);
                    currentRow.setOrientation(LinearLayout.HORIZONTAL);
                    currentRow.setWeightSum(2.0f);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = 8;
                    currentRow.setLayoutParams(rowLp);
                    container.addView(currentRow);
                }

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundResource(R.drawable.bg_dtc_card);
                card.setPadding(16, 12, 16, 12);

                // --- Fix: equal margins on both sides for symmetric spacing ---
                // Old code applied margin to only one side (right for even, left for odd),
                // creating asymmetric gaps. Now both sides get equal margin.
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                cardLp.leftMargin = margin;
                cardLp.rightMargin = margin;
                card.setLayoutParams(cardLp);

                TextView nameView = new TextView(this);
                nameView.setText(pidName);
                nameView.setTextColor(getColorCompat(R.color.muted));
                nameView.setTextSize(11);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(nameView);

                valueView = new TextView(this);
                valueView.setTextColor(getColorCompat(R.color.primary));
                valueView.setTextSize(16);
                valueView.setTypeface(null, android.graphics.Typeface.BOLD);
                valueView.setPadding(0, 4, 0, 4);
                valueView.setSingleLine(true);
                card.addView(valueView);

                statusView = new TextView(this);
                statusView.setTextSize(9);
                card.addView(statusView);

                if (currentRow != null) {
                    currentRow.addView(card);
                }

                rowCache.put(pidName, valueView);
                statusCache.put(pidName, statusView);
            }

            valueView.setText(formatValue(sample.getValue(), sample.getUnit()));
            valueView.setTextColor(getColorCompat(sample.getStatus().equals("ok") ? R.color.primary : R.color.danger));

            if (isGauge) {
                Double minVal = pidMinValues.get(pidKey);
                Double maxVal = pidMaxValues.get(pidKey);
                Double sumVal = pidSumValues.get(pidKey);
                Integer countVal = pidCountValues.get(pidKey);
                Double avgVal = (sumVal != null && countVal != null && countVal > 0) ? sumVal / countVal : null;
                String minStr = formatValue(minVal, sample.getUnit());
                String avgStr = formatValue(avgVal, sample.getUnit());
                String maxStr = formatValue(maxVal, sample.getUnit());
                int minColor = getColorCompat(R.color.primary);
                int avgColor = getColorCompat(R.color.accent);
                int maxColor = getColorCompat(R.color.danger);
                String minColorHex = String.format(Locale.US, "#%06X", (0xFFFFFF & minColor));
                String avgColorHex = String.format(Locale.US, "#%06X", (0xFFFFFF & avgColor));
                String maxColorHex = String.format(Locale.US, "#%06X", (0xFFFFFF & maxColor));
                String htmlText = "<b>MIN:</b> <font color='" + minColorHex + "'>" + minStr + "</font>  •  <b>AVG:</b> <font color='" + avgColorHex + "'>" + avgStr + "</font>  •  <b>MAX:</b> <font color='" + maxColorHex + "'>" + maxStr + "</font>";
                statusView.setText(androidx.core.text.HtmlCompat.fromHtml(htmlText, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY));
                statusView.setTextColor(getColorCompat(R.color.muted));
            } else {
                statusView.setText(sample.getStatus().toUpperCase(Locale.US));
                statusView.setTextColor(getColorCompat(sample.getStatus().equals("ok") ? R.color.accent : R.color.danger));
            }

            index++;
        }

        // --- Fix: if the last row has only one card (odd number of items),
        // make it span full width instead of leaving the right half empty. ---
        if (currentRow != null && currentRow.getChildCount() == 1) {
            View loneCard = currentRow.getChildAt(0);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) loneCard.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.weight = 0;
            loneCard.setLayoutParams(lp);
        }
    }

    private void updateSessionStatus(boolean active) {
        if (sessionStatusText != null) {
            sessionStatusText.setText(active ? "RECORDING" : "STANDBY");
            sessionStatusText.setTextColor(getColorCompat(active ? R.color.accent : R.color.muted));
        }
        if (sessionStatusDotCard != null) {
            sessionStatusDotCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(
                    getColorCompat(active ? R.color.accent : R.color.muted)));
            if (active) {
                if (pulseAnimation == null) {
                    pulseAnimation = new android.view.animation.AlphaAnimation(0.3f, 1.0f);
                    pulseAnimation.setDuration(800);
                    pulseAnimation.setRepeatMode(android.view.animation.Animation.REVERSE);
                    pulseAnimation.setRepeatCount(android.view.animation.Animation.INFINITE);
                }
                sessionStatusDotCard.startAnimation(pulseAnimation);
            } else {
                sessionStatusDotCard.clearAnimation();
            }
        }
    }

    private void updateLiveMetrics(int count, long startTimeMs) {
        if (txtSessionRecords != null) {
            txtSessionRecords.setText(String.valueOf(count));
        }
        long elapsedMs = SystemClock.elapsedRealtime() - startTimeMs;
        if (txtSessionDuration != null) {
            long secs = elapsedMs / 1000;
            long mins = secs / 60;
            long hrs = mins / 60;
            txtSessionDuration.setText(String.format(Locale.US, "%02d:%02d:%02d", hrs, mins % 60, secs % 60));
        }
        if (txtSessionRate != null) {
            double hz = elapsedMs > 0 ? (count * 1000.0 / elapsedMs) : 0.0;
            txtSessionRate.setText(String.format(Locale.US, "%.1f Hz", hz));
        }
    }

    private void clearReadings() {
        if (readingsContainer != null) readingsContainer.removeAllViews();
        if (gaugeReadingsContainer != null) gaugeReadingsContainer.removeAllViews();
        if (txtSessionDuration != null) txtSessionDuration.setText("00:00:00");
        if (txtSessionRecords != null) txtSessionRecords.setText("0");
        if (txtSessionRate != null) txtSessionRate.setText("0.0 Hz");
        readingRowCache.clear();
        readingStatusCache.clear();
        gaugeRowCache.clear();
        gaugeStatusCache.clear();
        pidMinValues.clear();
        pidMaxValues.clear();
        pidSumValues.clear();
        pidCountValues.clear();
        if (tuningStatusText != null) {
            tuningStatusText.setText(getString(R.string.waiting_for_data));
            tuningStatusText.setTextColor(getColorCompat(R.color.warning));
        }
        if (txtTuningStatus != null) {
            txtTuningStatus.setText(getString(R.string.waiting_for_data));
            txtTuningStatus.setTextColor(getColorCompat(R.color.warning));
        }
        if (txtTuningStft != null) txtTuningStft.setText("--%");
        if (txtTuningLtft != null) txtTuningLtft.setText("--%");
        if (txtTuningAdvice != null) txtTuningAdvice.setText("Please drive the vehicle on both Petrol and LPG modes to capture STFT/LTFT matrix data.");
    }

    private void resetGraphs() {
        GraphView[] graphs = {graph1, graph2, graph3, graph4, graph5};
        for (int i=0; i<5; i++) {
            if (graphs[i] != null) graphs[i].clear();
        }
    }

    private boolean ensureLogFolderSelected() {
        return ensureLogFolderSelected(false);
    }

    private boolean ensureLogFolderSelected(boolean triggerStartLoggingOnSuccess) {
        String savedUriStr = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getString("custom_log_folder_uri", null);
        if (savedUriStr != null) {
            try {
                Uri uri = Uri.parse(savedUriStr);
                androidx.documentfile.provider.DocumentFile tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri);
                if (tree == null || !tree.exists() || !tree.canWrite()) {
                    savedUriStr = null;
                }
            } catch (Exception e) {
                savedUriStr = null;
            }
        }

        if (savedUriStr == null) {
            pendingStartLoggingAfterFolderSelect = triggerStartLoggingOnSuccess;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_select_folder_title)
                    .setMessage(R.string.dialog_select_folder_msg)
                    .setCancelable(false)
                    .setPositiveButton(R.string.dialog_select_folder_btn, (dialog, which) -> {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        folderPickerLauncher.launch(intent);
                    })
                    .show();
            return false;
        }
        return true;
    }

    // --- Log folder ---

    private void openLogFolder() {
        String savedUriStr = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getString("custom_log_folder_uri", null);
        if (savedUriStr != null) {
            try {
                Uri folderUri = Uri.parse(savedUriStr);
                Intent folderIntent = new Intent(Intent.ACTION_VIEW);
                folderIntent.setDataAndType(folderUri, "vnd.android.document/directory");
                folderIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(folderIntent);
                return;
            } catch (Exception e) {
                // Fall through to default behavior if opening custom folder fails
            }
        }

        // Try opening the default Downloads/TunerMapPro folder directly in the file manager
        try {
            Uri defaultUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FTunerMapPro");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(defaultUri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            // Fallback 1: Try primary:Download directory
            try {
                Uri downloadUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download");
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(downloadUri, "vnd.android.document/directory");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } catch (Exception ex) {
                // Fallback 2: Try standard ACTION_GET_CONTENT
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FTunerMapPro"), "*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivity(Intent.createChooser(intent, "Open Logs Folder"));
                } catch (Exception exc) {
                    // Fallback 3: Open the latest single file
                    openLatestLogFile();
                }
            }
        }
    }

    private void openLatestLogFile() {
        Uri uri = currentCsvUri;
        String mimeType = "text/csv";
        if (uri == null) {
            uri = currentJsonlUri;
            mimeType = "application/x-ndjson";
        }
        if (uri == null) {
            Toast.makeText(this, "No log file available yet.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent fileIntent = new Intent(Intent.ACTION_VIEW);
            fileIntent.setDataAndType(uri, mimeType);
            fileIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(fileIntent, "Open OBD2 log file"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open log file.", Toast.LENGTH_LONG).show();
        }
    }

    // --- Utility ---

    private String getFileProviderAuthority() {
        return getPackageName() + ".fileprovider";
    }

    private void setStatus(String message, int colorRes) {
        statusText.setText(message);
        statusText.setTextColor(getColorCompat(colorRes));
    }

    private String formatValue(Double value, String unit) {
        if (value == null) return "-";
        String formatted = String.format(Locale.US, "%.2f", value);
        if (unit == null || unit.isEmpty()) return formatted;
        return formatted + " " + unit;
    }

    private int getColorCompat(int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return getColor(colorRes);
        return getResources().getColor(colorRes);
    }

    private Double valueByKey(DataRecord record, String key) {
        for (SensorSample sample : record.getSamples()) {
            if (sample.getPidKey().equals(key)) return sample.getValue();
        }
        return null;
    }

    private Double valueByName(DataRecord record, String name) {
        for (SensorSample sample : record.getSamples()) {
            if (sample.getName().equals(name)) return sample.getValue();
        }
        return null;
    }

    private String text(EditText input, String def) {
        String s = input.getText().toString().trim();
        return s.isEmpty() ? def : s;
    }

    private int intText(EditText input, int def) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private double doubleText(EditText input, double def) {
        try {
            return Double.parseDouble(input.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    // --- Bluetooth ---

    private void refreshBluetoothDevices() {
        bluetoothDevices.clear();
        if (!hasBluetoothPermissions()) {
            setBluetoothSpinnerMessage("Permission required");
            bluetoothHintText.setText("Grant Bluetooth permission to list OBD2 adapters.");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setBluetoothSpinnerMessage("Bluetooth not supported");
            return;
        }
        if (!adapter.isEnabled()) {
            setBluetoothSpinnerMessage("Bluetooth is off");
            bluetoothHintText.setText("Turn on Bluetooth and pair the ELM327 adapter first.");
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException se) {
            setBluetoothSpinnerMessage("Permission denied");
            bluetoothHintText.setText("Grant Bluetooth connect permission in system settings.");
            return;
        }

        if (bonded == null || bonded.isEmpty()) {
            setBluetoothSpinnerMessage("No paired devices");
            bluetoothHintText.setText("Pair the ELM327/OBD2 adapter in Bluetooth settings first.");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (BluetoothDevice device : bonded) {
            bluetoothDevices.add(device);
            labels.add(deviceLabel(device));
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bluetoothDeviceSpinner.setAdapter(spinnerAdapter);
        bluetoothDeviceSpinner.setSelection(0);
        bluetoothHintText.setText(labels.size() + " paired device(s) found.");
    }

    private String deviceLabel(BluetoothDevice device) {
        String name = "";
        try {
            name = device.getName();
        } catch (SecurityException se) {
            android.util.Log.e("OBD2Logger", "SecurityException reading device name", se);
        }
        String addr = device.getAddress();
        if (name == null || name.isEmpty()) return addr;
        return name + " (" + addr + ")";
    }

    private BluetoothDevice selectedBluetoothDevice() {
        int pos = bluetoothDeviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= bluetoothDevices.size()) return null;
        return bluetoothDevices.get(pos);
    }

    private void setBluetoothSpinnerMessage(String message) {
        bluetoothDevices.clear();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{message});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bluetoothDeviceSpinner.setAdapter(adapter);
    }

    // --- Permissions ---

    private boolean ensureTransportPermissions() {
        int pos = transportSpinner.getSelectedItemPosition();
        if (pos == 2 || pos == 3 || pos == 5) {
            boolean has = ensureBluetoothPermissions();
            if (!has) {
                pendingStartLoggingAfterPermission = true;
            }
            return has;
        }
        return true;
    }

    private boolean ensureBluetoothPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_ADMIN);
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String pkg = getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + pkg));
                    startActivity(intent);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Apply or clear FLAG_KEEP_SCREEN_ON on the activity window.
     * When set, the device screen stays on (and won't dim/lock) the whole time
     * this activity is in the foreground — exactly what we want while logging OBD2 data.
     */
    private void applyKeepScreenOn(boolean enabled) {
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void saveConfigPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = prefs.edit();
        ed.putInt("pref_transport_position", transportSpinner != null ? transportSpinner.getSelectedItemPosition() : 0);
        ed.putInt("pref_fuel_position", fuelSpinner != null ? fuelSpinner.getSelectedItemPosition() : 1);
        ed.putInt("pref_obd_protocol_position", obdProtocolSpinner != null ? obdProtocolSpinner.getSelectedItemPosition() : 0);
        ed.putString("pref_wifi_ip", wifiIpInput != null ? wifiIpInput.getText().toString().trim() : "192.168.0.10");
        ed.putString("pref_wifi_port", wifiPortInput != null ? wifiPortInput.getText().toString().trim() : "35000");
        ed.putString("pref_baud", baudInput != null ? baudInput.getText().toString().trim() : "115200");
        ed.putString("pref_interval", intervalInput != null ? intervalInput.getText().toString().trim() : "0.5");
        ed.putBoolean("pref_lpg_only", lpgOnlyCheckbox != null && lpgOnlyCheckbox.isChecked());
        ed.putBoolean("pref_api_server", apiServerCheckbox != null && apiServerCheckbox.isChecked());
        ed.putBoolean("pref_ford_ms_can", fordMsCanCheckbox != null && fordMsCanCheckbox.isChecked());
        ed.putBoolean("pref_turbo_boost", turboBoostCheckbox != null && turboBoostCheckbox.isChecked());
        ed.putBoolean("pref_fuel_economy", fuelEconomyCheckbox != null && fuelEconomyCheckbox.isChecked());
        ed.putBoolean("pref_dpf_monitor", dpfMonitorCheckbox != null && dpfMonitorCheckbox.isChecked());
        ed.putBoolean("pref_custom_pid", customPidCheckbox != null && customPidCheckbox.isChecked());
        ed.putBoolean("pref_air_density", airDensityCheckbox != null && airDensityCheckbox.isChecked());
        ed.apply();
    }

    private void restoreConfigPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        if (transportSpinner != null) {
            int pos = prefs.getInt("pref_transport_position", 0);
            if (pos >= 0 && pos < transportSpinner.getAdapter().getCount()) {
                transportSpinner.setSelection(pos);
            }
        }
        if (fuelSpinner != null) {
            if (running) {
                LoggerService svc = LoggerService.getInstance();
                LoggerConfig cfg = (svc != null && svc.getConfig() != null) ? svc.getConfig() : activeInProcessConfig;
                if (cfg != null && cfg.fuelMode != null) {
                    fuelSpinner.setSelection(fuelModeToPosition(cfg.fuelMode));
                } else {
                    int pos = prefs.getInt("pref_fuel_position", 1);
                    if (pos >= 0 && pos < fuelSpinner.getAdapter().getCount()) {
                        fuelSpinner.setSelection(pos);
                    }
                }
            } else {
                fuelSpinner.setSelection(fuelModeToPosition(FuelMode.PETROL)); // Always default Start Fuel Type to Petrol
            }
        }
        if (obdProtocolSpinner != null) {
            int pos = prefs.getInt("pref_obd_protocol_position", 0);
            if (pos >= 0 && pos < obdProtocolSpinner.getAdapter().getCount()) {
                obdProtocolSpinner.setSelection(pos);
            }
        }
        if (wifiIpInput != null) {
            wifiIpInput.setText(prefs.getString("pref_wifi_ip", "192.168.0.10"));
        }
        if (wifiPortInput != null) {
            wifiPortInput.setText(prefs.getString("pref_wifi_port", "35000"));
        }
        if (baudInput != null) {
            baudInput.setText(prefs.getString("pref_baud", "115200"));
        }
        if (intervalInput != null) {
            intervalInput.setText(prefs.getString("pref_interval", "0.5"));
        }
        if (lpgOnlyCheckbox != null) {
            lpgOnlyCheckbox.setChecked(prefs.getBoolean("pref_lpg_only", false));
        }
        if (apiServerCheckbox != null) {
            apiServerCheckbox.setChecked(prefs.getBoolean("pref_api_server", false));
        }
        if (fordMsCanCheckbox != null) {
            fordMsCanCheckbox.setChecked(prefs.getBoolean("pref_ford_ms_can", false));
        }
        if (turboBoostCheckbox != null) {
            turboBoostCheckbox.setChecked(prefs.getBoolean("pref_turbo_boost", true));
        if (airDensityCheckbox != null) airDensityCheckbox.setChecked(prefs.getBoolean("pref_air_density", true));
        }
        if (fuelEconomyCheckbox != null) {
            fuelEconomyCheckbox.setChecked(prefs.getBoolean("pref_fuel_economy", true));
        }
        if (dpfMonitorCheckbox != null) {
            dpfMonitorCheckbox.setChecked(prefs.getBoolean("pref_dpf_monitor", false));
        }
        if (customPidCheckbox != null) {
            customPidCheckbox.setChecked(prefs.getBoolean("pref_custom_pid", false));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveConfigPrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreConfigPrefs();
        // Re-assert the keep-screen-on flag on resume in case the activity/window was recreated.
        if (keepScreenOnCheckbox != null) {
            applyKeepScreenOn(keepScreenOnCheckbox.isChecked());
        }
        int pos = transportSpinner.getSelectedItemPosition();
        if (pos == 2 || pos == 3 || pos == 4) {
            refreshBluetoothDevices();
        }
        if (currentTabIndex == 4) {
            loadHistoryFiles();
        }
        
        // Fix AutoCompleteTextView auto-filtering bug on restore
        if (batteryTypeSpinner != null) {
            String[] batteryTypes = getResources().getStringArray(R.array.battery_chemistry_types);
            java.util.List<String> typeList = new java.util.ArrayList<>(java.util.Arrays.asList(batteryTypes));
            batteryTypeSpinner.setAdapter(new android.widget.ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_dropdown_item, typeList));
            int savedChemIndex = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getInt("selected_battery_chemistry_index", 0);
            if (savedChemIndex >= 0 && savedChemIndex < batteryTypes.length) {
                batteryTypeSpinner.setText(batteryTypes[savedChemIndex], false);
            } else {
                batteryTypeSpinner.setText(batteryTypes[0], false);
            }
        }
        syncLoggerState();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_tab", currentTabIndex);
    }

    @Override
    protected void onDestroy() {
        ExecutorService dtc = dtcExecutor;
        if (dtc != null) {
            dtc.shutdownNow();
            dtcExecutor = null;
        }
        if (isFinishing()) {
            stopLogging();
        }
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingBackgroundConfig != null) {
                    LoggerConfig cfg = pendingBackgroundConfig;
                    pendingBackgroundConfig = null;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        actuallyStartBackgroundLogging(cfg);
                    }, 300);
                }
            } else {
                pendingBackgroundConfig = null;
                isConnecting = false;
                running = false;
                watchdogHandler.removeCallbacks(connectionWatchdog);
                runOnUiThread(() -> {
                    setFabState(false);
                    setConfigUiEnabled(true);
                    updateStatusStripConnection(0, "Permission denied");
                    if (headerStatus != null) headerStatus.setText("Disconnected");
                    Toast.makeText(this, "Notification permission denied — background logging off",
                            Toast.LENGTH_LONG).show();
                });
            }
            return;
        }

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                refreshBluetoothDevices();
                if (pendingStartLoggingAfterPermission) {
                    pendingStartLoggingAfterPermission = false;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (startLogging()) {
                            setFabState(true);
                        }
                    }, 300);
                }
            } else {
                pendingStartLoggingAfterPermission = false;
                isConnecting = false;
                running = false;
                watchdogHandler.removeCallbacks(connectionWatchdog);
                runOnUiThread(() -> {
                    setFabState(false);
                    setConfigUiEnabled(true);
                    updateStatusStripConnection(0, "Permission denied");
                    if (headerStatus != null) headerStatus.setText("Disconnected");
                });
                Toast.makeText(this, "Bluetooth permission denied. Simulation/WiFi still work.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private List<String> getCachedPids(String vin) {
        if (vin == null || vin.isEmpty()) return null;
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        String cached = prefs.getString("pids_cache_" + vin, null);
        if (cached == null || cached.isEmpty()) return null;
        
        List<String> list = new ArrayList<>();
        for (String s : cached.split(",")) {
            list.add(s.trim());
        }
        return list;
    }

    private void cachePids(String vin, List<String> pids) {
        if (vin == null || vin.isEmpty() || pids == null || pids.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String s : pids) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        prefs.edit().putString("pids_cache_" + vin, sb.toString()).apply();
    }
}
