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
        mapToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnReviewPetrol) {
                    fuelMapView.setMapMode(FuelMapView.MapMode.PETROL);
                } else if (checkedId == R.id.btnReviewLpg) {
                    fuelMapView.setMapMode(FuelMapView.MapMode.LPG);
                } else if (checkedId == R.id.btnReviewDiff) {
                    fuelMapView.setMapMode(FuelMapView.MapMode.DEVIATION);
                }
            }
        });
        
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
                
                String[] headers = headerLine.split(",");
                
                int fuelModeIdx = -1;
                int loopStatusIdx = -1;
                int rpmIdx = -1;
                int mapIdx = -1;
                int stftIdx = -1;
                int ltftIdx = -1;
                
                for (int i = 0; i < headers.length; i++) {
                    String h = headers[i].toLowerCase().replace("\"", "");
                    if (h.equals("fuel_mode")) fuelModeIdx = i;
                    else if (h.equals("loop_status")) loopStatusIdx = i;
                    else if (h.contains("engine rpm")) rpmIdx = i;
                    else if (h.contains("manifold pressure")) mapIdx = i;
                    else if (h.contains("short term fuel trim")) stftIdx = i;
                    else if (h.contains("long term fuel trim")) ltftIdx = i;
                }
                
                if (fuelModeIdx == -1 || rpmIdx == -1 || mapIdx == -1 || stftIdx == -1) {
                    throw new Exception("Missing required columns in CSV (Needs RPM, MAP, STFT)");
                }
                
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    // Basic split (assumes no commas inside quotes for numeric data)
                    String[] parts = line.split(",");
                    if (parts.length <= Math.max(fuelModeIdx, Math.max(rpmIdx, mapIdx))) continue;
                    
                    String fuelModeStr = parts[fuelModeIdx].replace("\"", "").trim();
                    FuelMode mode = fuelModeStr.equalsIgnoreCase("PETROL") ? FuelMode.PETROL : FuelMode.LPG;
                    
                    String loopStatus = (loopStatusIdx != -1 && parts.length > loopStatusIdx) ? 
                                        parts[loopStatusIdx].replace("\"", "").trim() : "Closed";
                                        
                    if (loopStatus.equalsIgnoreCase("Open")) {
                        continue; // Skip open loop data for tuning
                    }
                    
                    try {
                        double rpm = Double.parseDouble(parts[rpmIdx]);
                        double map = Double.parseDouble(parts[mapIdx]);
                        double stft = Double.parseDouble(parts[stftIdx]);
                        double ltft = (ltftIdx != -1 && parts.length > ltftIdx && !parts[ltftIdx].isEmpty()) ? 
                                      Double.parseDouble(parts[ltftIdx]) : 0.0;
                                      
                        double trim = stft + ltft;
                        
                        // Push to map
                        runOnUiThread(() -> fuelMapView.pushData(rpm, map, trim, mode));
                        plottedPoints++;
                        
                    } catch (NumberFormatException ignored) {
                        // Skip unparseable lines
                    }
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
        executor.shutdownNow();
    }
}
