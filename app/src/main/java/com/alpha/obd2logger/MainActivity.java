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
    private TextView headerStatus, headerVin;
    private android.widget.ImageButton btnSettings;
    private android.widget.ImageButton btnThemeToggle;
    private android.widget.ImageButton btnGoHome;

    // --- UI: Settings ---
    private Spinner languageSpinner, themeSpinner, transportSpinner, fuelSpinner, obdProtocolSpinner, bluetoothDeviceSpinner;
    private EditText wifiIpInput, wifiPortInput, baudInput, intervalInput;
    private TextView bluetoothHintText;
    private CheckBox lpgOnlyCheckbox, backgroundLoggingCheckbox, keepScreenOnCheckbox, apiServerCheckbox;
    private TextView apiServerIpText;
    private TextView customLogFolderText;
    private Button btnSelectLogFolder;

    // --- UI: Dashboard ---
    private TextView statusText, countText;
    private TextView dashTitle1, dashValue1, dashTitle2, dashValue2, dashTitle3, dashValue3, dashTitle4, dashValue4;
    private GaugeView gauge1, gauge2, gauge3, gauge4;
    private TextView tuningStatusText;
    private TextView mapEctText, mapLoopStatusText;
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
    private LinearLayout readingsContainer;

    private com.google.android.material.button.MaterialButton fabLog;
    private FuelMapView fuelMapView;

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
    private int currentTabIndex = 0;

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_CODE = 1002;

    private androidx.activity.result.ActivityResultLauncher<Intent> folderPickerLauncher;

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
                            }
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
                        fuelSpinner.setSelection(activeConfig.fuelMode == FuelMode.LPG ? 0 : 1);
                    }
                    if (transportSpinner != null) {
                        transportSpinner.setSelection(transportSpinnerToPosition(activeConfig.transportMode));
                    }
                    if (fuelMapView != null) {
                        fuelMapView.setMapMode(activeConfig.fuelMode == FuelMode.LPG
                                ? FuelMapView.MapMode.LPG : FuelMapView.MapMode.PETROL);
                        com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
                        if (mapModeToggle != null) {
                            mapModeToggle.check(activeConfig.fuelMode == FuelMode.LPG ? R.id.btnMapLpg : R.id.btnMapPetrol);
                        }
                    }
                }
                
                int count = service.getRecordCount();
                countText.setText("Records: " + count);
                setStatus("Background logging active...", R.color.accent);
                headerStatus.setText("Logging...");
                if (activeConfig != null) updateStatusStripConnection(2, "Connected " + activeConfig.transportMode.getValue());
            }

            if (fuelMapView != null) {
                fuelMapView.setPetrolData(sessionPetrolData);
                fuelMapView.setLpgData(sessionLpgData);
            }
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
                    fuelSpinner.setSelection(activeInProcessConfig.fuelMode == FuelMode.LPG ? 0 : 1);
                }
                if (transportSpinner != null) {
                    transportSpinner.setSelection(transportSpinnerToPosition(activeInProcessConfig.transportMode));
                }
                if (fuelMapView != null) {
                    fuelMapView.setMapMode(activeInProcessConfig.fuelMode == FuelMode.LPG
                            ? FuelMapView.MapMode.LPG : FuelMapView.MapMode.PETROL);
                    com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
                    if (mapModeToggle != null) {
                        mapModeToggle.check(activeInProcessConfig.fuelMode == FuelMode.LPG ? R.id.btnMapLpg : R.id.btnMapPetrol);
                    }
                }
            }
            
            countText.setText("Records: " + sessionRecordCount);

            if (fuelMapView != null) {
                fuelMapView.setPetrolData(sessionPetrolData);
                fuelMapView.setLpgData(sessionLpgData);
            }
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
        btnSettings = findViewById(R.id.btnSettings);
        btnThemeToggle = findViewById(R.id.btnThemeToggle);
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
        // Populate battery type dropdown
        String[] batteryTypes = {"Flooded (Standard)", "AGM/Gel", "Calcium"};
        java.util.List<String> typeList = new java.util.ArrayList<>(java.util.Arrays.asList(batteryTypes));
        batteryTypeSpinner.setAdapter(new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, typeList));
        batteryTypeSpinner.setText(batteryTypes[0], false);

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
        customLogFolderText = findViewById(R.id.customLogFolderText);
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
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
                if (!ensureLogFolderSelected()) {
                    return;
                }
                startLogging();
                setFabState(true);
            }
        });
        
        dashTuningStatus = findViewById(R.id.dashTuningStatus);
        dashEctText = findViewById(R.id.dashEctText);
        dashWarmupProgress = findViewById(R.id.dashWarmupProgress);

        fuelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyFuelTheme(position == 0 ? FuelMode.LPG : FuelMode.PETROL);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        fuelSpinner.setSelection(1); // Default to Petrol on startup
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
        int primaryColor = mode == FuelMode.LPG ? 0xFFF59E0B : 0xFF38BDF8; // Orange vs Blue
        int accentColor = mode == FuelMode.LPG ? 0xFFD97706 : 0xFF0284C7;
        
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

    private void setupDynamicPids() {
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        
        for (int i=0; i<4; i++) prefGaugePids[i] = prefs.getString("gauge_" + i, prefGaugePids[i]);
        for (int i=0; i<4; i++) prefDashPids[i] = prefs.getString("dash_" + i, prefDashPids[i]);
        for (int i=0; i<5; i++) prefGraphPids[i] = prefs.getString("graph_" + i, prefGraphPids[i]);
        
        // Setup Long Click Listeners
        View[] gaugeCards = {findViewById(R.id.gaugeCard1), findViewById(R.id.gaugeCard2), findViewById(R.id.gaugeCard3), findViewById(R.id.gaugeCard4)};
        View[] dashCards = {findViewById(R.id.dashCard1), findViewById(R.id.dashCard2), findViewById(R.id.dashCard3), findViewById(R.id.dashCard4)};
        View[] graphCards = {findViewById(R.id.graphCard1), findViewById(R.id.graphCard2), findViewById(R.id.graphCard3), findViewById(R.id.graphCard4), findViewById(R.id.graphCard5)};
        
        for (int i=0; i<4; i++) {
            final int idx = i;
            if (gaugeCards[i] != null) gaugeCards[i].setOnLongClickListener(v -> { showPidSelectionDialog("gauge_" + idx, prefGaugePids, idx, this::setupGauges); return true; });
            if (dashCards[i] != null) dashCards[i].setOnLongClickListener(v -> { showPidSelectionDialog("dash_" + idx, prefDashPids, idx, this::setupDashboard); return true; });
        }
        for (int i=0; i<5; i++) {
            final int idx = i;
            if (graphCards[i] != null) graphCards[i].setOnLongClickListener(v -> { showPidSelectionDialog("graph_" + idx, prefGraphPids, idx, this::setupGraphs); return true; });
        }
    }

    private void showPidSelectionDialog(String prefKey, String[] array, int index, Runnable onUpdated) {
        java.util.List<PIDDefinition> allPids = PIDCatalogue.getAll();
        String[] items = new String[allPids.size()];
        for (int i=0; i<allPids.size(); i++) {
            items[i] = allPids.get(i).getName() + " (" + allPids.get(i).getUnit() + ")";
        }
        
        new android.app.AlertDialog.Builder(this)
            .setTitle("Select PID to Display")
            .setItems(items, (dialog, which) -> {
                array[index] = allPids.get(which).key();
                getSharedPreferences("OBD2Prefs", MODE_PRIVATE).edit().putString(prefKey, array[index]).apply();
                onUpdated.run();
            })
            .setNegativeButton("Cancel", null)
            .show();
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
            PIDDefinition pid = PIDDefinition.findByKey(prefDashPids[i]);
            if (pid != null && titles[i] != null) {
                titles[i].setText(pid.getName());
                values[i].setText("—");
            }
        }
    }

    private void updateApiServerIpText(boolean isEnabled) {
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
        if (isLogging) {
            fabLog.setIconResource(android.R.drawable.ic_media_pause);
            fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.danger)));
        } else {
            fabLog.setIconResource(android.R.drawable.ic_media_play);
            fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
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
                if (!ensureLogFolderSelected()) {
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
        View cardSettings = findViewById(R.id.cardHomeSettings);
        if (cardSettings != null) {
            cardSettings.setOnClickListener(v -> showTab(5));
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
            PIDDefinition pid = PIDDefinition.findByKey(prefGaugePids[i]);
            if (pid != null && gauges[i] != null) {
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

    private void setupGraphs() {
        GraphView[] graphs = {graph1, graph2, graph3, graph4, graph5};
        int[] colors = {0xFF38BDF8, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444, 0xFFA78BFA};
        for (int i=0; i<5; i++) {
            PIDDefinition pid = PIDDefinition.findByKey(prefGraphPids[i]);
            if (pid != null && graphs[i] != null) {
                graphs[i].setLabel(pid.getName(), pid.getUnit());
                graphs[i].setRange((float)pid.getMinVal(), (float)pid.getMaxVal());
                graphs[i].setLineColor(colors[i]);
            }
        }
    }

    private void setupListeners() {
        // Init language spinner
        final String[] langCodes = {
            LocaleHelper.LANG_SYSTEM,
            LocaleHelper.LANG_ENGLISH,
            LocaleHelper.LANG_THAI,
            "es",
            "pt",
            "de",
            "fr",
            "it",
            "ru",
            "hi",
            "ar",
            "id",
            "vi",
            "ja",
            "ko",
            "zh"
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

        // Initial icon setup for btnThemeToggle
        boolean isCurrentNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) 
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        btnThemeToggle.setImageResource(isCurrentNight ? R.drawable.ic_sun : R.drawable.ic_moon);

        btnThemeToggle.setOnClickListener(v -> {
            boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) 
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            int newMode = night ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
            // Use commit() for synchronous write so the pref is saved
            // before the activity recreates itself
            prefs.edit().putInt("app_theme", newMode).commit();
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newMode);
            recreate(); // Explicitly recreate the activity to apply theme change immediately
        });

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

                    boolean isNight;
                    if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
                        isNight = (android.content.res.Resources.getSystem().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    } else {
                        isNight = (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
                    }
                    btnThemeToggle.setImageResource(isNight ? R.drawable.ic_sun : R.drawable.ic_moon);
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
            showScanHistoryDialog();
            return true;
        });
        btnClearDtc.setOnClickListener(v -> clearDtcs());
        btnReadVin.setOnClickListener(v -> readVin());
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
                    dateText.setText(sdf.format(new Date(item.date)));

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
                    dateText.setText(sdf.format(new Date(item.date)));

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

    private void startLogging() {
        if (!ensureTransportPermissions()) return;

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
                    return;
                }
            } else if (config.transportMode == TransportMode.USB) {
                setStatus("No USB Serial device found. Please plug in vLinker.", R.color.danger);
                return;
            }
        }

        // Request battery optimization exemption if background logging is enabled
        if (backgroundLoggingCheckbox.isChecked()) {
            requestBatteryOptimizationExemption();
        }
        running = true;
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
            if (config.fuelMode == FuelMode.LPG) {
                sessionLpgData.clear();
            } else {
                sessionPetrolData.clear();
            }
            fuelMapView.setMapMode(config.fuelMode == FuelMode.LPG
                    ? FuelMapView.MapMode.LPG : FuelMapView.MapMode.PETROL);
            // Also sync the toggle button in the Map tab
            com.google.android.material.button.MaterialButtonToggleGroup mapModeToggle = findViewById(R.id.mapModeToggle);
            if (mapModeToggle != null) {
                mapModeToggle.check(config.fuelMode == FuelMode.LPG ? R.id.btnMapLpg : R.id.btnMapPetrol);
            }
        }

        if (backgroundLoggingCheckbox.isChecked()) {
            startBackgroundLogging(config);
        } else {
            startInProcessLogging(config);
        }
    }

    private void startInProcessLogging(LoggerConfig config) {
        activeInProcessConfig = config;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> runLogger(config));
    }

    private void startBackgroundLogging(LoggerConfig config) {
        LoggerService.setCallback(this);
        LoggerService.setPendingConfig(config);
        Intent intent = new Intent(this, LoggerService.class);
        intent.setAction(LoggerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopLogging() {
        running = false;
        // Don't call currentDriver.disconnect() from the main thread — the
        // logger thread performs disconnect() in its finally block. Calling it
        // here too races with that thread and can double-free native resources
        // (GATT handles, BluetoothSocket, UsbSerialPort). Setting running=false
        // and shutdownNow() is enough to make the logger thread exit and clean up.

        if (backgroundLoggingCheckbox.isChecked()) {
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
                    active.setStatus("Connection failed. Check settings and adapter.", R.color.danger);
                    active.headerStatus.setText("Connection failed");
                    active.updateStatusStripConnection(0, "Connection failed");
                    if (active.fabLog != null) {
                        active.setFabState(false);
                    }
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
                if (active != null) active.setStatus("Connected. Logging started.", R.color.accent);
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
                if (active != null) active.headerVin.setText("VIN: " + vin);
            });
        }

        DataWriter writer = null;
        int completed = 0;
        long started = SystemClock.elapsedRealtime();
        List<PIDDefinition> allPids = config.lpgOnlyMode ? PIDCatalogue.getLpgCritical() : PIDCatalogue.getAll();
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

            while (running) {
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
                        active.renderReadings(record);
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
                    Thread.currentThread().interrupt();
                    break;
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
                    if (active.fabLog != null) {
                        active.setFabState(false);
                    }
                    active.headerStatus.setText("Disconnected");
                    active.updateStatusStripConnection(0, "Disconnected");
                }
            });
        }
    }

    // --- LoggerService callbacks (background logging) ---

    @Override
    public void onRecord(DataRecord record, int count) {
        runOnUiThread(() -> {
            countText.setText("Records: " + count);
            updateDashboard(record);
            updateGraphs(record);
            updateFuelMap(record);
            updateFuelTrim(record);
            updateTuningData(record);
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
                    FuelMode mode = "lpg/cng".equalsIgnoreCase(record.getFuelMode()) ? FuelMode.LPG : FuelMode.PETROL;
                    fuelMapView.pushData(rpm, loadAxis, trim, mode);
                }
            }
        }
    }

    @Override
    public void onStatus(String status, boolean isError) {
        runOnUiThread(() -> setStatus(status, isError ? R.color.danger : R.color.accent));
    }

    @Override
    public void onStopped(int totalRecords) {
        runOnUiThread(() -> {
            if (fabLog != null) {
                setFabState(false);
            }
            setStatus("Background logging stopped. " + totalRecords + " records saved.", R.color.primary);
            headerStatus.setText("Disconnected");
            updateStatusStripConnection(0, "Disconnected");
            updateSessionStatus(false);
        });
    }

    @Override
    public void onVinRead(String vin) {
        runOnUiThread(() -> headerVin.setText("VIN: " + vin));
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

        stripRpm.setText(rpm != null ? String.format(Locale.US, "%.0f", rpm) : "--");
        stripSpeed.setText(speed != null ? String.format(Locale.US, "%.0f", speed) : "--");
        if (voltage != null) {
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
            stripVoltage.setText("--");
            stripVoltage.setTextColor(getColorCompat(R.color.text));
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
            Double val = valueByKey(record, prefDashPids[i]);
            if (val != null && values[i] != null) {
                PIDDefinition pid = PIDDefinition.findByKey(prefDashPids[i]);
                values[i].setText(String.format(Locale.US, "%.2f %s", val, pid != null ? pid.getUnit() : ""));
            }
        }
        
        GaugeView[] gauges = {gauge1, gauge2, gauge3, gauge4};
        for (int i=0; i<4; i++) {
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
            FuelMode mode = "lpg/cng".equalsIgnoreCase(record.getFuelMode()) ? FuelMode.LPG : FuelMode.PETROL;
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
        if (currentDriver == null || !currentDriver.isConnected()) return -1;
        try {
            Double v = currentDriver.queryPid(PIDDefinition.findByKey("01_42"));
            return v != null ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Quick single voltage reading — updates the graph + SoC/SoH display.
     * Called from onRecord to keep the live graph fed.
     */
    private void updateBatteryMonitor(DataRecord record) {
        Double v = valueByKey(record, "01_42");
        if (v == null || v <= 0) return;
        lastBatteryVoltage = v;
        if (batteryTestView != null) {
            batteryTestView.addSample(v.floatValue());
        }
        if (batteryVoltageText != null) {
            double soc = BatteryTester.voltageToSoC(v);
            batteryVoltageText.setText(String.format(Locale.US, "%.2f V  |  SoC: %.0f%%", v, soc));
        }
        // Update SoC/SoH summary cards (quick estimate)
        if (socValueText != null) {
            double soc = BatteryTester.voltageToSoC(v);
            socValueText.setText(String.format(Locale.US, "%.0f%%", soc));
            socVoltageText.setText(String.format(Locale.US, "%.2f V", v));
        }
    }

    /** Test 1: Resting voltage (engine off). */
    private void testBatteryResting() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            batteryStatusText.setText("Not connected. Start logging first.");
            return;
        }
        batteryStatusText.setText("Reading resting voltage (ensure engine is OFF)...");
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            double v = readBatteryVoltage();
            restingVoltage = v;
            runOnUiThread(() -> {
                if (v > 0) {
                    BatteryTester.BatteryTestResult r = BatteryTester.testStateOfCharge(v);
                    displayBatteryResult(r);
                } else {
                    batteryStatusText.setText("Failed to read voltage.");
                }
            });
        });
    }

    /** Test 3: Alternator voltage at idle (engine running). */
    private void testBatteryAlternator() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            batteryStatusText.setText("Not connected. Start logging first.");
            return;
        }
        batteryStatusText.setText("Reading alternator voltage (ensure engine is RUNNING at idle)...");
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
            double v = count > 0 ? sum / count : -1;
            runningVoltage = v;
            // Also try high-RPM reading (ask user to rev)
            runOnUiThread(() -> {
                if (v > 0) {
                    BatteryTester.BatteryTestResult r = BatteryTester.testAlternatorVoltage(v);
                    displayBatteryResult(r);
                } else {
                    batteryStatusText.setText("Failed to read voltage.");
                }
            });
        });
    }

    /** Test 4: Voltage drop under electrical load. */
    private void testBatteryLoadDrop() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            batteryStatusText.setText("Not connected. Start logging first.");
            return;
        }
        batteryStatusText.setText("Step 1: Reading no-load voltage (engine running, accessories OFF)...");
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            double noLoad = readBatteryVoltage();
            noLoadVoltage = noLoad;
            runOnUiThread(() -> {
                batteryStatusText.setText("Step 2: Turn ON headlights, blower, AC, rear defroster.\nTap OK when ready.");
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Voltage Drop Test")
                        .setMessage("Turn ON all electrical loads:\n• Headlights (high beam)\n• Blower motor (max)\n• AC\n• Rear defroster\n\nKeep engine at idle.\n\nPress OK when loads are ON.")
                        .setPositiveButton("OK", (d, w) -> {
                            batteryStatusText.setText("Reading full-load voltage...");
                            dtcExecutor.submit(() -> {
                                double fullLoad = readBatteryVoltage();
                                fullLoadVoltage = fullLoad;
                                runOnUiThread(() -> {
                                    if (noLoad > 0 && fullLoad > 0) {
                                        BatteryTester.BatteryTestResult r = BatteryTester.testVoltageDrop(noLoad, fullLoad);
                                        displayBatteryResult(r);
                                    } else {
                                        batteryStatusText.setText("Failed to read voltage.");
                                    }
                                });
                            });
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    /** Test 6: Cranking voltage — minimum voltage during engine crank. */
    private void testBatteryCranking() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            batteryStatusText.setText("Not connected. Start logging first.");
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Cranking Voltage Test")
                .setMessage("This test records the minimum battery voltage during engine crank.\n\n1. Turn engine OFF\n2. Press Start below\n3. Immediately crank the engine\n\nThe test will sample rapidly for 5 seconds.")
                .setPositiveButton("Start", (d, w) -> {
                    batteryStatusText.setText("Sampling crank voltage — crank the engine NOW!");
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
                                batteryStatusText.setText("Failed to read crank voltage.");
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Test 7: Ripple / diode health — fast sampling of voltage. */
    private void testBatteryRipple() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            batteryStatusText.setText("Not connected. Start logging first.");
            return;
        }
        batteryStatusText.setText("Sampling voltage for ripple test (2 seconds)...");
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
                    BatteryTester.BatteryTestResult r = BatteryTester.testRipple(samples);
                    displayBatteryResult(r);
                } else {
                    batteryStatusText.setText("Insufficient samples for ripple test.");
                }
            });
        });
    }

    /** Full diagnostic — runs all available tests and shows a comprehensive report. */
    private void runFullBatteryDiagnostic() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            batteryStatusText.setText("Not connected. Start logging first.");
            return;
        }
        final boolean isAgm = batteryTypeSpinner != null && batteryTypeSpinner.getText().toString().contains("AGM");
        final int ageMonths;
        if (batteryAgeInput != null) {
            String ageStr = batteryAgeInput.getText().toString().trim();
            int parsed;
            try { parsed = Integer.parseInt(ageStr); } catch (NumberFormatException e) { parsed = -1; }
            ageMonths = parsed;
        } else {
            ageMonths = -1;
        }
        batteryStatusText.setText("Running full battery diagnostic...");
        batteryScoreCard.setVisibility(View.GONE);
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
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
                    postLoadVoltage, recoveredVoltage, -1,
                    highRpmVoltage,
                    rippleSamples.isEmpty() ? null : new java.util.ArrayList<>(rippleSamples),
                    -1, -1, -1,  // drain values — need long-term monitoring
                    rDelta, ageMonths,
                    isAgm, true  // tropical climate default for Thailand
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
        if (currentDriver == null || !currentDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            dtcStatusText.setTextColor(getColorCompat(R.color.danger));
            return;
        }
        dtcStatusText.setText("Reading DTCs...");
        dtcStatusText.setTextColor(getColorCompat(R.color.muted));
        dtcListContainer.removeAllViews();

        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            List<DtcCode> stored = DtcReader.readStoredDtcs(currentDriver);
            List<DtcCode> pending = DtcReader.readPendingDtcs(currentDriver);
            List<DtcCode> permanent = DtcReader.readPermanentDtcs(currentDriver);
            
            // Read Mode 06 — on-board monitor test results
            List<Mode06Result> mode06Results = Mode06Reader.readDiagnostic(currentDriver);
            
            // Read per-DTC freeze frames
            List<FreezeFrameReader.FreezeFrameEntry> perDtcFrames = FreezeFrameReader.readAllFreezeFrames(currentDriver);
            
            // Read Mode 09 — Cal-ID and CVN
            List<Mode09Reader.CalIdEntry> calIds = Mode09Reader.readCalIds(currentDriver);
            List<Mode09Reader.CvnEntry> cvns = Mode09Reader.readCvns(currentDriver);
            
            FreezeFrameData ffData = null;
            if (!stored.isEmpty() || !pending.isEmpty()) {
                ffData = FreezeFrameReader.readFreezeFrame(currentDriver);
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
                updateDtcBadge(stored.size(), pending.size(), permanent.size());
            });
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
        File pdfFile = DtcReportExporter.exportReportToPdf(this, vin, lastStoredDtcs, lastPendingDtcs, lastPermanentDtcs, lastFreezeFrame);
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

    private void addDtcSection(String title, List<DtcCode> codes, int colorRes, String iconPrefix) {
        TextView titleView = new TextView(this);
        titleView.setText(title + " (" + codes.size() + ")");
        titleView.setTextColor(getColorCompat(colorRes));
        titleView.setTextSize(14);
        titleView.setPadding(0, 12, 0, 6);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        dtcListContainer.addView(titleView);

        for (DtcCode dtc : codes) {
            // Build expandable DTC card with enrichment data
            LinearLayout cardLayout = new LinearLayout(this);
            cardLayout.setOrientation(LinearLayout.VERTICAL);
            cardLayout.setPadding(10, 8, 10, 8);
            cardLayout.setBackgroundResource(R.drawable.bg_dtc_card);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = 4;
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
                    causesView.setPadding(0, 4, 0, 2);
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
                    fixesView.setPadding(0, 2, 0, 4);
                    cardLayout.addView(fixesView);
                }

                // Drive cycles to clear
                if (enrich.getDriveCyclesToClear() > 0) {
                    TextView dcView = new TextView(this);
                    dcView.setText("    Drive cycles to clear: " + enrich.getDriveCyclesToClear());
                    dcView.setTextColor(getColorCompat(R.color.muted));
                    dcView.setTextSize(10);
                    dcView.setPadding(0, 2, 0, 0);
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

            dtcListContainer.addView(cardLayout);
        }
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
        if (currentDriver == null || !currentDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            return;
        }
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            boolean success = DtcReader.clearDtcs(currentDriver);
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
        });
    }

    private void readVin() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            return;
        }
        dtcStatusText.setText("Reading VIN...");
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            String vin = VinReader.readVin(currentDriver);
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
        });
    }

    private void checkReadiness() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            return;
        }
        dtcStatusText.setText("Checking readiness monitors...");
        readinessContainer.removeAllViews();
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            ReadinessMonitor rm = ReadinessMonitor.read(currentDriver);
            runOnUiThread(() -> displayReadiness(rm));
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

        dtcStatusText.setText("Readiness check complete.");
        dtcStatusText.setTextColor(getColorCompat(R.color.muted));
    }

    // --- Config ---

    private LoggerConfig readConfigFromUi() {
        LoggerConfig config = new LoggerConfig();
        config.transportMode = transportModeFromSpinner(transportSpinner.getSelectedItemPosition());
        config.fuelMode = fuelSpinner.getSelectedItemPosition() == 0 ? FuelMode.LPG : FuelMode.PETROL;
        config.obdProtocol = obdProtocolFromSpinner(obdProtocolSpinner.getSelectedItemPosition());
        config.wifiIp = text(wifiIpInput, "192.168.0.10");
        config.wifiPort = intText(wifiPortInput, 35000);
        config.baud = intText(baudInput, 115200);
        config.bluetoothDevice = selectedBluetoothDevice();
        double seconds = doubleText(intervalInput, 0.5);
        config.sampleIntervalMs = Math.max(50, (long) Math.round(seconds * 1000.0));
        config.lpgOnlyMode = lpgOnlyCheckbox.isChecked();
        config.enableApiServer = apiServerCheckbox.isChecked();
        return config;
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

    private void renderReadings(DataRecord record) {
        updateGridContainer(readingsContainer, readingRowCache, readingStatusCache, record, false);
        updateGridContainer(gaugeReadingsContainer, gaugeRowCache, gaugeStatusCache, record, true);
    }

    private void updateGridContainer(LinearLayout container, java.util.Map<String, TextView> rowCache, java.util.Map<String, TextView> statusCache, DataRecord record, boolean isGauge) {
        if (container == null) return;
        if (container.getChildCount() != (record.getSamples().size() + 1) / 2) {
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
    }

    private void resetGraphs() {
        GraphView[] graphs = {graph1, graph2, graph3, graph4, graph5};
        for (int i=0; i<5; i++) {
            if (graphs[i] != null) graphs[i].clear();
        }
    }

    private boolean ensureLogFolderSelected() {
        String savedUriStr = getSharedPreferences("OBD2Prefs", MODE_PRIVATE).getString("custom_log_folder_uri", null);
        if (savedUriStr == null) {
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
        if (pos == 2 || pos == 3 || pos == 4) {
            return ensureBluetoothPermissions();
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

    @Override
    protected void onResume() {
        super.onResume();
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
            } else {
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
