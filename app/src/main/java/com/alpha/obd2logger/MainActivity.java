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
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.app.PendingIntent;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

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
    private com.google.android.material.bottomnavigation.BottomNavigationView bottomNav;
    private View panelDashboard, panelGauges, panelDtc, panelLogs, panelSettings;

    // --- UI: Header ---
    private TextView headerStatus, headerVin;
    private android.widget.ImageButton btnSettings;

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

    // --- UI: Gauges tab ---
    private GraphView graph1, graph2, graph3, graph4, graph5;
    private TextView allPidsText;

    // --- UI: DTC tab ---
    private Button btnReadDtc, btnClearDtc, btnReadVin, btnReadiness;
    private TextView dtcStatusText;
    private LinearLayout dtcListContainer, readinessContainer;

    // --- UI: Logs tab ---
    private TextView logPathText;
    private TableLayout readingsTable;

    private com.google.android.material.floatingactionbutton.FloatingActionButton fabLog;
    private FuelMapView fuelMapView;

    // --- UI: History tab ---
    private View panelHistory;
    private android.widget.ListView historyListView;
    private TextView historyFolderText;

    // --- State ---
    private volatile ExecutorService executor;
    // Separate executor for DTC/VIN/readiness operations so that stopping the
    // logging executor (shutdownNow) doesn't kill pending diagnostic reads.
    private volatile ExecutorService dtcExecutor;
    private volatile boolean running;
    private DataWriter currentWriter;
    private volatile BaseDriver currentDriver;
    private File currentDownloadFolder;
    private Uri currentCsvUri, currentJsonlUri;
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
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
            int currentTheme = prefs.getInt("app_theme", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(currentTheme);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

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
            
            if (savedInstanceState != null) {
                currentTabIndex = savedInstanceState.getInt("current_tab", 0);
                showTab(currentTabIndex);
            } else {
                showTab(0);
            }
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

    private void initViews() {
        // Header
        headerStatus = findViewById(R.id.headerStatus);
        headerVin = findViewById(R.id.headerVin);
        btnSettings = findViewById(R.id.btnSettings);
        
        historyListView = findViewById(R.id.historyListView);
        historyFolderText = findViewById(R.id.historyFolderText);
        
        bottomNav = findViewById(R.id.tabBar);
        panelDashboard = findViewById(R.id.panelDashboard);
        panelGauges = findViewById(R.id.panelGauges);
        View panelFuelMap = findViewById(R.id.panelFuelMap);
        panelDtc = findViewById(R.id.panelDtc);
        panelLogs = findViewById(R.id.panelLogs);
        panelSettings = findViewById(R.id.panelSettings);

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
        if (appVersionText != null) {
            try {
                String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                appVersionText.setText("Version " + versionName);
            } catch (Exception e) {
                appVersionText.setText("Version 2.0.0");
            }
        }

        // Dashboard
        fabLog = findViewById(R.id.fabLog);
        fabLog.setOnClickListener(v -> {
            if (running) {
                stopLogging();
                fabLog.setImageResource(android.R.drawable.ic_media_play);
                fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
            } else {
                startLogging();
                fabLog.setImageResource(android.R.drawable.ic_media_pause);
                fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.danger)));
            }
        });
        
        com.google.android.material.button.MaterialButtonToggleGroup dashboardFuelToggle = findViewById(R.id.dashboardFuelToggle);
        dashboardFuelToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                fuelSpinner.setSelection(checkedId == R.id.btnToggleLpg ? 0 : 1);
                applyFuelTheme(checkedId == R.id.btnToggleLpg ? FuelMode.LPG : FuelMode.PETROL);
            }
        });
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
        allPidsText = findViewById(R.id.allPidsText);

        // DTC tab
        btnReadDtc = findViewById(R.id.btnReadDtc);
        btnClearDtc = findViewById(R.id.btnClearDtc);
        btnReadVin = findViewById(R.id.btnReadVin);
        btnReadiness = findViewById(R.id.btnReadiness);
        dtcStatusText = findViewById(R.id.dtcStatusText);
        dtcListContainer = findViewById(R.id.dtcListContainer);
        readinessContainer = findViewById(R.id.readinessContainer);

        // Logs tab
        logPathText = findViewById(R.id.logPathText);
        readingsTable = findViewById(R.id.readingsTable);
        logPathText.setOnClickListener(v -> openLogFolder());
        
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
        
        setupDynamicPids();
    }
    
    private void showMapInfoDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.how_to_read_map_title)
            .setMessage(R.string.how_to_read_map_desc)
            .setPositiveButton("OK", null)
            .show();
    }
    
    private void applyFuelTheme(FuelMode mode) {
        int primaryColor = mode == FuelMode.LPG ? 0xFFF59E0B : 0xFF38BDF8; // Orange vs Blue
        int accentColor = mode == FuelMode.LPG ? 0xFFD97706 : 0xFF0284C7;
        
        if (fabLog != null) {
            fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(running ? getColorCompat(R.color.danger) : primaryColor));
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

    private void setupTabs() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                showTab(0);
                return true;
            } else if (id == R.id.nav_gauges) {
                showTab(1);
                return true;
            } else if (id == R.id.nav_map) {
                showTab(2);
                return true;
            } else if (id == R.id.nav_dtc) {
                showTab(3);
                return true;
            } else if (id == R.id.nav_logs) {
                showTab(4);
                return true;
            }
            return false;
        });
    }

    private void showTab(int index) {
        currentTabIndex = index;
        panelDashboard.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelGauges.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.panelFuelMap).setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        panelDtc.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        panelLogs.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
        
        if (index == 4) {
            loadHistoryFiles();
        }
    }

    private void setupGauges() {
        GaugeView[] gauges = {gauge1, gauge2, gauge3, gauge4};
        for (int i=0; i<4; i++) {
            PIDDefinition pid = PIDDefinition.findByKey(prefGaugePids[i]);
            if (pid != null && gauges[i] != null) {
                gauges[i].setRange((float)pid.getMinVal(), (float)pid.getMaxVal());
                gauges[i].setLabel(pid.getName());
                gauges[i].setUnit(pid.getUnit());
                gauges[i].setColors(0xFF38BDF8, 0xFF22C55E);
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
        String currentLang = LocaleHelper.getLanguage(this);
        languageSpinner.setSelection(currentLang.equals(LocaleHelper.LANG_THAI) ? 1 : 0);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLang = position == 1 ? LocaleHelper.LANG_THAI : LocaleHelper.LANG_ENGLISH;
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
        
        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                if (position == 1) mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
                else if (position == 2) mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
                
                if (prefs.getInt("app_theme", -1) != mode) {
                    prefs.edit().putInt("app_theme", mode).apply();
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
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
        btnClearDtc.setOnClickListener(v -> clearDtcs());
        btnReadVin.setOnClickListener(v -> readVin());
        btnReadiness.setOnClickListener(v -> checkReadiness());



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
                for (androidx.documentfile.provider.DocumentFile f : df.listFiles()) {
                    if (f.getName() != null && f.getName().endsWith(".csv")) {
                        HistoryLogFile hlf = new HistoryLogFile();
                        hlf.uri = f.getUri();
                        hlf.name = f.getName();
                        hlf.date = f.lastModified();
                        hlf.isSaf = true;
                        hlf.df = f;
                        logFiles.add(hlf);
                    }
                }
            } else {
                historyFolderText.setText("Cannot access custom folder. Using default.");
                loadDefaultHistory(logFiles);
            }
        } else {
            historyFolderText.setText("Default Folder (Downloads/OBD2LPGLogger)");
            loadDefaultHistory(logFiles);
        }

        java.util.Collections.sort(logFiles, (f1, f2) -> Long.compare(f2.date, f1.date));

        List<String> names = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (HistoryLogFile hlf : logFiles) {
            names.add(hlf.name + "\n" + sdf.format(new Date(hlf.date)));
        }

        historyListView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));

        historyListView.setOnItemClickListener((parent, view, position, id) -> {
            HistoryLogFile selectedFile = logFiles.get(position);
            Intent intent = new Intent(this, ReviewSessionActivity.class);
            intent.setData(selectedFile.uri);
            intent.putExtra("file_name", selectedFile.name);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        });

        historyListView.setOnItemLongClickListener((parent, view, position, id) -> {
            HistoryLogFile selectedFile = logFiles.get(position);
            String[] options = {"Share Log", "Delete Log"};
            new android.app.AlertDialog.Builder(this)
                .setTitle(selectedFile.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) { // Share
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/csv");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, selectedFile.uri);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "Share Log File"));
                    } else if (which == 1) { // Delete
                        new android.app.AlertDialog.Builder(this)
                            .setTitle("Confirm Delete")
                            .setMessage("Are you sure you want to delete this log?")
                            .setPositiveButton("Delete", (d, w) -> {
                                if (selectedFile.delete(this)) {
                                    Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show();
                                    loadHistoryFiles();
                                } else {
                                    Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    }
                })
                .show();
            return true;
        });
    }

    private void loadDefaultHistory(List<HistoryLogFile> logFiles) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = new String[] {
                android.provider.MediaStore.Downloads._ID,
                android.provider.MediaStore.Downloads.DISPLAY_NAME,
                android.provider.MediaStore.Downloads.DATE_MODIFIED
            };
            String selection = android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND " +
                               android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = new String[] { "%OBD2LPGLogger%", "%.csv" };

            try (android.database.Cursor cursor = getContentResolver().query(collection, projection, selection, selectionArgs, null)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME);
                    int dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DATE_MODIFIED);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String name = cursor.getString(nameCol);
                        if (name != null && name.startsWith("CorrectionMap_")) continue;
                        long date = cursor.getLong(dateCol) * 1000L;
                        Uri contentUri = android.content.ContentUris.withAppendedId(collection, id);
                        
                        HistoryLogFile hlf = new HistoryLogFile();
                        hlf.uri = contentUri;
                        hlf.name = name;
                        hlf.date = date;
                        hlf.isMediaStore = true;
                        logFiles.add(hlf);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OBD2LPGLogger");
            if (folder.exists()) {
                File[] files = folder.listFiles((dir, name) -> name.endsWith(".csv") && !name.startsWith("CorrectionMap_"));
                if (files != null) {
                    for (File f : files) {
                        HistoryLogFile hlf = new HistoryLogFile();
                        hlf.uri = androidx.core.content.FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".fileprovider", f);
                        hlf.name = f.getName();
                        hlf.date = f.lastModified();
                        hlf.isFile = true;
                        hlf.file = f;
                        logFiles.add(hlf);
                    }
                }
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
        clearReadings();
        resetGraphs();
        lastSampleTimeMs = 0;
        setStatus("Connecting via " + config.transportMode.getValue() + "...", R.color.accent);
        headerStatus.setText("Connecting...");
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
        if (currentDriver != null) {
            currentDriver.disconnect();
        }

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
            runOnUiThread(() -> {
                setStatus("Connection failed. Check settings and adapter.", R.color.danger);
                headerStatus.setText("Connection failed");
                if (fabLog != null) {
                    fabLog.setImageResource(android.R.drawable.ic_media_play);
                    fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
                }
            });
            return;
        }

        if (config.transportMode == TransportMode.AUTO && driver instanceof SimulationDriver) {
            runOnUiThread(() -> setStatus("Auto probe failed — running simulation.", R.color.warning));
        } else {
            runOnUiThread(() -> setStatus("Connected. Logging started.", R.color.accent));
        }
        runOnUiThread(() -> headerStatus.setText("Connected: " + config.transportMode.getValue()));

        // Try to read VIN
        String vin = VinReader.readVin(driver);
        if (vin != null) {
            config.vin = vin;
            runOnUiThread(() -> headerVin.setText("VIN: " + vin));
        }

        DataWriter writer = null;
        int completed = 0;
        long started = SystemClock.elapsedRealtime();
        List<PIDDefinition> allPids = config.lpgOnlyMode ? PIDCatalogue.getLpgCritical() : PIDCatalogue.getAll();
        List<PIDDefinition> pids = allPids;

        // --- Auto-detect supported PIDs ---
        if (driver instanceof SimulationDriver) {
            List<String> supportedHex = PidAvailabilityChecker.querySupportedPids(driver);
            if (supportedHex != null) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
            }
        } else if (driver instanceof ElmDriver) {
            runOnUiThread(() -> setStatus("Detecting supported PIDs...", R.color.accent));
            List<String> supportedHex = PidAvailabilityChecker.querySupportedPids(driver);
            boolean fromLive = false;
            if (supportedHex != null && !supportedHex.isEmpty()) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                fromLive = true;
            } else {
                // Fallback: VIN-based brand/year profile
                java.util.Set<String> brandPids = BrandYearProfile.getProfileFromVin(config.vin);
                if (brandPids != null) {
                    pids = PidAvailabilityChecker.filterCatalogue(
                            new ArrayList<>(brandPids), allPids);
                }
            }
            final int detectedCount = pids.size();
            final int totalCount = allPids.size();
            final boolean fromLiveFinal = fromLive;
            runOnUiThread(() -> {
                String msg = fromLiveFinal
                        ? detectedCount + "/" + totalCount + " PIDs detected (live query)"
                        : detectedCount + "/" + totalCount + " PIDs (VIN profile fallback)";
                setStatus(msg, R.color.accent);
            });
        }

        final List<PIDDefinition> finalPids = pids;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);

        try {
            writer = new DataWriter(this, sessionId, finalPids);
            currentWriter = writer;
            final DataWriter dataWriter = writer;
            currentDownloadFolder = dataWriter.getDownloadFolderFile();
            currentCsvUri = dataWriter.getCsvUri();
            currentJsonlUri = dataWriter.getJsonlUri();
            runOnUiThread(() -> logPathText.setText("CSV: " + dataWriter.getCsvLocation()
                    + "\nJSONL: " + dataWriter.getJsonlLocation()
                    + "\n" + getString(R.string.tap_to_open_logs)));

            while (running) {
                Map<String, Double> batch = driver.queryPidBatch(finalPids);
                List<SensorSample> samples = new ArrayList<>();
                for (PIDDefinition pid : finalPids) {
                    Double value = batch.get(pid.getName());
                    samples.add(new SensorSample(pid.key(), pid.getName(), value, pid.getUnit(),
                            value == null ? "err" : "ok"));
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
                runOnUiThread(() -> {
                    countText.setText("Records: " + finalCompleted);
                    updateDashboard(record);
                    updateGraphs(record);
                    updateFuelMap(record);
                    updateFuelTrim(record);
                    updateTuningData(record);
                    renderReadings(record);
                });

                try {
                    Thread.sleep(config.sampleIntervalMs);
                } catch (InterruptedException ie) {
                    // shutdownNow() interrupted our sleep — exit the loop gracefully
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            final int finalCompleted2 = completed;
            runOnUiThread(() -> setStatus("Stopped at " + finalCompleted2 + " records.", R.color.primary));
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                runOnUiThread(() -> setStatus("Logger error: " + e.getMessage(), R.color.danger));
            }
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {
            }
            driver.disconnect();
            currentWriter = null;
            currentDriver = null;
            if (executor != null) {
                executor.shutdown();
            }
            executor = null;
            runOnUiThread(() -> {
                if (fabLog != null) {
                    fabLog.setImageResource(android.R.drawable.ic_media_play);
                    fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
                }
                headerStatus.setText("Disconnected");
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
                fabLog.setImageResource(android.R.drawable.ic_media_play);
                fabLog.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColorCompat(R.color.primary)));
            }
            setStatus("Background logging stopped. " + totalRecords + " records saved.", R.color.primary);
            headerStatus.setText("Disconnected");
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
        });
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
            runOnUiThread(() -> displayDtcs(stored, pending));
        });
    }

    private void displayDtcs(List<DtcCode> stored, List<DtcCode> pending) {
        dtcListContainer.removeAllViews();
        int total = stored.size() + pending.size();

        if (stored.isEmpty() && pending.isEmpty()) {
            dtcStatusText.setText(getString(R.string.no_dtcs));
            dtcStatusText.setTextColor(getColorCompat(R.color.accent));
            return;
        }

        dtcStatusText.setText(String.format(Locale.US, getString(R.string.dtc_count), total));
        dtcStatusText.setTextColor(getColorCompat(R.color.danger));

        if (!stored.isEmpty()) {
            addDtcSection("Stored DTCs", stored, R.color.danger);
        }
        if (!pending.isEmpty()) {
            addDtcSection("Pending DTCs", pending, R.color.warning);
        }
    }

    private void addDtcSection(String title, List<DtcCode> codes, int colorRes) {
        TextView titleView = new TextView(this);
        titleView.setText(title + " (" + codes.size() + ")");
        titleView.setTextColor(getColorCompat(colorRes));
        titleView.setTextSize(14);
        titleView.setPadding(0, 12, 0, 6);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        dtcListContainer.addView(titleView);

        for (DtcCode dtc : codes) {
            TextView codeView = new TextView(this);
            codeView.setText("  " + dtc.getCode() + " — " + dtc.getDescription());
            codeView.setTextColor(getColorCompat(R.color.text));
            codeView.setTextSize(13);
            codeView.setPadding(8, 6, 8, 6);
            codeView.setBackgroundResource(R.color.surface2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 4;
            codeView.setLayoutParams(lp);
            dtcListContainer.addView(codeView);
        }
    }

    private void clearDtcs() {
        if (currentDriver == null || !currentDriver.isConnected()) {
            dtcStatusText.setText("Not connected. Start logging first.");
            return;
        }
        dtcExecutor = dtcExecutor != null ? dtcExecutor : Executors.newSingleThreadExecutor();
        dtcExecutor.submit(() -> {
            boolean success = DtcReader.clearDtcs(currentDriver);
            runOnUiThread(() -> {
                if (success) {
                    dtcStatusText.setText("DTCs cleared. MIL should reset after next drive cycle.");
                    dtcStatusText.setTextColor(getColorCompat(R.color.accent));
                    dtcListContainer.removeAllViews();
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
                rm.isAllReady() ? "All monitors ready ✓" : "Not all monitors ready"));
        milView.setTextColor(getColorCompat(rm.isMilOn() ? R.color.danger : R.color.accent));
        milView.setTextSize(14);
        milView.setPadding(0, 12, 0, 8);
        milView.setTypeface(null, android.graphics.Typeface.BOLD);
        readinessContainer.addView(milView);

        for (ReadinessMonitor.MonitorStatus m : rm.getMonitors()) {
            if (!m.available) continue;
            TextView mv = new TextView(this);
            String icon = m.complete ? "✓" : "✗";
            mv.setText(String.format("  %s  %s", icon, m.name));
            mv.setTextColor(getColorCompat(m.complete ? R.color.accent : R.color.warning));
            mv.setTextSize(13);
            mv.setPadding(8, 4, 8, 4);
            readinessContainer.addView(mv);
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

    private void renderReadings(DataRecord record) {
        if (readingsTable.getChildCount() - 1 != record.getSamples().size()) {
            readingsTable.removeViews(1, Math.max(0, readingsTable.getChildCount() - 1));
            readingRowCache.clear();
            readingStatusCache.clear();
        }

        StringBuilder allPids = new StringBuilder();
        for (SensorSample sample : record.getSamples()) {
            String pidName = sample.getName();
            TextView valueView = readingRowCache.get(pidName);
            TextView statusView = readingStatusCache.get(pidName);

            if (valueView == null) {
                TableRow row = new TableRow(this);
                row.setPadding(0, 4, 0, 4);

                TextView pidView = new TextView(this);
                pidView.setText(pidName);
                pidView.setTextColor(getColorCompat(R.color.text));
                pidView.setPadding(8, 6, 8, 6);

                valueView = new TextView(this);
                valueView.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
                valueView.setPadding(8, 6, 8, 6);

                statusView = new TextView(this);
                statusView.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
                statusView.setPadding(8, 6, 8, 6);

                row.addView(pidView);
                row.addView(valueView);
                row.addView(statusView);
                readingsTable.addView(row, new TableLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                readingRowCache.put(pidName, valueView);
                readingStatusCache.put(pidName, statusView);
            }

            valueView.setText(formatValue(sample.getValue(), sample.getUnit()));
            valueView.setTextColor(getColorCompat(sample.getStatus().equals("ok") ? R.color.accent : R.color.danger));

            statusView.setText(sample.getStatus());
            statusView.setTextColor(getColorCompat(sample.getStatus().equals("ok") ? R.color.accent : R.color.danger));

            allPids.append(pidName).append(": ")
                    .append(formatValue(sample.getValue(), sample.getUnit()))
                    .append("\n");
        }
        allPidsText.setText(allPids.toString());
    }

    private void clearReadings() {
        readingsTable.removeViews(1, Math.max(0, readingsTable.getChildCount() - 1));
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

        if (currentDownloadFolder == null) {
            currentDownloadFolder = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OBD2LPGLogger");
        }
        if (!currentDownloadFolder.exists() && !currentDownloadFolder.mkdirs()) {
            Toast.makeText(this, "Cannot create Downloads/OBD2LPGLogger folder.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri folderUri = FileProvider.getUriForFile(this, getFileProviderAuthority(), currentDownloadFolder);
            Intent folderIntent = new Intent(Intent.ACTION_VIEW);
            folderIntent.setDataAndType(folderUri, "vnd.android.document/directory");
            folderIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(folderIntent);
        } catch (Exception e) {
            openLatestLogFile();
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
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
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
        String name = device.getName();
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
}
