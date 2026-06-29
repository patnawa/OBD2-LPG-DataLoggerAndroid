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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review_session);

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
        if (intent == null || intent.getData() == null) {
            Toast.makeText(this, "No file provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Uri fileUri = intent.getData();
        String fileName = intent.getStringExtra("file_name");
        if (fileName != null) {
            tvFileName.setText(fileName);
        } else {
            tvFileName.setText("Review Session");
        }

        parseLogFile(fileUri);
    }

    private void parseLogFile(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Parsing data...");

        executor.execute(() -> {
            int lineCount = 0;
            int plottedPoints = 0;
            
            try (InputStream in = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    throw new Exception("File is empty");
                }

                LogReplayParser.Columns cols = LogReplayParser.parseHeader(headerLine);

                // MAP is optional: parser falls back to Engine Load for the X-axis
                // (MAF-based vehicles, or adapters that drop MAP) — mirrors updateFuelMap().
                if (!cols.hasRequired()) {
                    throw new Exception("Missing required columns. Need 'Engine RPM' and either 'Intake Manifold Pressure' or 'Engine Load'.");
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    LogReplayParser.Point p = LogReplayParser.parseLine(line, cols);
                    if (p == null) continue; // too short, open loop, or unparseable

                    // Push to map on the UI thread
                    runOnUiThread(() -> fuelMapView.pushData(p.rpm, p.axis, p.trim, p.fuelMode));
                    plottedPoints++;
                }
                
                int finalLines = lineCount;
                int finalPlotted = plottedPoints;
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText(String.format("Loaded %d valid points (from %d lines)", finalPlotted, finalLines));
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error parsing log", e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Error: " + e.getMessage());
                    Toast.makeText(this, "Failed to parse log", Toast.LENGTH_LONG).show();
                });
            }
        });
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
