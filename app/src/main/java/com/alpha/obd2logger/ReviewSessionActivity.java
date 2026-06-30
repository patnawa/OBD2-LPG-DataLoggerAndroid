package com.alpha.obd2logger;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReviewSessionActivity extends AppCompatActivity {
    private static final String TAG = "ReviewSessionActivity";
    
    private FuelMapView fuelMapView;
    private TextView tvFileName;
    private TextView tvStatus;
    private ProgressBar progressBar;
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review_session);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
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

        fuelMapView = findViewById(R.id.reviewFuelMapView);
        tvFileName = findViewById(R.id.reviewFileName);
        tvStatus = findViewById(R.id.reviewStatus);
        progressBar = findViewById(R.id.reviewProgress);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        MaterialButtonToggleGroup mapToggle = findViewById(R.id.reviewMapToggle);
        View btnReviewExportCsv = findViewById(R.id.btnReviewExportCsv);
        mapToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnReviewPetrol) {
                    fuelMapView.setMapMode(FuelMapView.MapMode.PETROL);
                } else if (checkedId == R.id.btnReviewLpg) {
                    fuelMapView.setMapMode(FuelMapView.MapMode.LPG);
                } else if (checkedId == R.id.btnReviewDiff) {
                    fuelMapView.setMapMode(FuelMapView.MapMode.DEVIATION);
                } else if (checkedId == R.id.btnReviewCorrection) {
                    fuelMapView.setMapMode(FuelMapView.MapMode.CORRECTION);
                }
                
                if (btnReviewExportCsv != null) {
                    btnReviewExportCsv.setVisibility(checkedId == R.id.btnReviewCorrection ? View.VISIBLE : View.GONE);
                }
            }
        });
        
        if (btnReviewExportCsv != null) {
            btnReviewExportCsv.setOnClickListener(v -> exportCorrectionCsv());
        }
        
        View btnReviewMapInfo = findViewById(R.id.btnReviewMapInfo);
        if (btnReviewMapInfo != null) {
            btnReviewMapInfo.setOnClickListener(v -> showMapInfoDialog());
        }
        
        fuelMapView.setMapMode(FuelMapView.MapMode.PETROL);

        Intent intent = getIntent();
        if (intent == null || (intent.getData() == null && intent.getParcelableArrayListExtra("file_uris") == null)) {
            Toast.makeText(this, "No file provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Build the list of files to parse: either a single setData() URI, or a
        // "file_uris" ArrayList (multi-select — e.g. a Petrol log + an LPG log,
        // both plotted onto the same map for cross-file Deviation/Tune-Assist).
        java.util.ArrayList<Uri> uris = intent.getParcelableArrayListExtra("file_uris");
        if (uris == null || uris.isEmpty()) {
            uris = new java.util.ArrayList<>();
            if (intent.getData() != null) uris.add(intent.getData());
        }

        String fileName = intent.getStringExtra("file_name");
        if (fileName != null) {
            tvFileName.setText(fileName);
        } else {
            tvFileName.setText("Review Session");
        }

        parseLogFiles(uris);
    }

    private void parseLogFiles(java.util.List<Uri> uris) {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Parsing data...");

        final java.util.List<Uri> fileList = new java.util.ArrayList<>(uris);

        executor.execute(() -> {
            int totalLines = 0;
            int totalPlotted = 0;
            int filesOk = 0;
            StringBuilder errors = new StringBuilder();

            for (Uri uri : fileList) {
                int[] counts = parseSingleFile(uri, errors);
                if (counts != null) {
                    totalLines += counts[0];
                    totalPlotted += counts[1];
                    filesOk++;
                }
            }

            final int fLines = totalLines;
            final int fPlotted = totalPlotted;
            final int fFiles = filesOk;
            final int fTotalFiles = fileList.size();
            final String errMsg = errors.toString();

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (fPlotted == 0) {
                    tvStatus.setText(errMsg.isEmpty()
                            ? "No valid tuning points found (check closed-loop / columns)."
                            : "Error: " + errMsg.trim());
                    Toast.makeText(this, "No data plotted", Toast.LENGTH_LONG).show();
                } else {
                    String suffix = (fTotalFiles > 1)
                            ? String.format(" across %d/%d files", fFiles, fTotalFiles) : "";
                    tvStatus.setText(String.format("Loaded %d valid points (from %d lines)%s",
                            fPlotted, fLines, suffix));
                    if (!errMsg.isEmpty()) {
                        Toast.makeText(this, "Some files skipped: " + errMsg.trim(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    /**
     * Parses one log file, pushing valid points to the (shared) FuelMapView.
     * Returns {lineCount, plottedPoints} on success, or null on failure (and
     * appends a short reason to {@code errors}). Plotting onto the same map
     * accumulates data, so calling this for a Petrol file then an LPG file
     * populates both layers for Deviation/Correction.
     */
    private int[] parseSingleFile(Uri uri, StringBuilder errors) {
        int lineCount = 0;
        int plottedPoints = 0;

        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                errors.append("empty file; ");
                return null;
            }

            LogReplayParser.Columns cols = LogReplayParser.parseHeader(headerLine);

            // MAP is optional: parser falls back to Engine Load for the X-axis
            // (MAF-based vehicles, or adapters that drop MAP) — mirrors updateFuelMap().
            if (!cols.hasRequired()) {
                errors.append("missing RPM/MAP/Load columns; ");
                return null;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                LogReplayParser.Point p = LogReplayParser.parseLine(line, cols);
                if (p == null) continue; // too short, open loop, or unparseable

                // Batch UI updates: calling runOnUiThread per line floods the
                // message queue on large logs (10000+ lines) and causes ANR.
                // Instead, push data directly without invalidating, then trigger
                // a single redraw at the end via postInvalidate (thread-safe).
                fuelMapView.pushDataNoInvalidate(p.rpm, p.axis, p.trim, p.fuelMode);
                plottedPoints++;
            }
            // Trigger a single redraw after all data is pushed.
            fuelMapView.postInvalidate();

            return new int[]{lineCount, plottedPoints};

        } catch (Exception e) {
            Log.e(TAG, "Error parsing log", e);
            errors.append(e.getMessage()).append("; ");
            return null;
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
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
            java.io.File csvFile = new java.io.File(dir, "CorrectionMap_Review_" + timestamp + ".csv");
            
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
            android.util.Log.e("ReviewSessionActivity", "Failed to export CSV", e);
            android.widget.Toast.makeText(this, "Failed to export CSV: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showMapInfoDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.how_to_read_map_title)
            .setMessage(R.string.how_to_read_map_desc)
            .setPositiveButton("OK", null)
            .show();
    }
}
