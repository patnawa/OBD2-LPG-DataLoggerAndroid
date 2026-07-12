package com.alpha.obd2logger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * First-run connection wizard.
 *
 * Isolated from all map / trim calculation logic. Its only side effect is writing
 * the user's chosen transport into the SAME SharedPreferences key that MainActivity's
 * transport spinner reads (pref_transport_position) plus a first_run_done flag.
 * It never instantiates a driver or touches LiveMapStore / MapBinning / LPGAnalyzer.
 */
public class ConnectionWizardActivity extends AppCompatActivity {

    // Matches MainActivity.transportSpinnerToPosition():
    // 1=WIFI, 2=SERIAL, 3=BLE, 4=USB, 5=AUTO, 0=SIM
    private static final int POS_WIFI = 1;
    private static final int POS_SERIAL = 2;
    private static final int POS_BLE = 3;
    private static final int POS_USB = 4;
    private static final int POS_AUTO = 5;
    private static final int POS_SIM = 0;

    private static final String PREFS = "OBD2Prefs";
    private static final String KEY_FIRST_RUN = "first_run_done";
    private static final String KEY_TRANSPORT_POS = "pref_transport_position";

    private int selectedPosition = POS_AUTO; // sensible default

    private final int[] CARD_IDS = {
            R.id.optWifi, R.id.optUsb, R.id.optBt,
            R.id.optBle, R.id.optAuto, R.id.optSim
    };
    private final int[] CHECK_IDS = {
            R.id.checkWifi, R.id.checkUsb, R.id.checkBt,
            R.id.checkBle, R.id.checkAuto, R.id.checkSim
    };
    private final int[] POSITIONS = {
            POS_WIFI, POS_USB, POS_BLE, POS_BLE, POS_AUTO, POS_SIM
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Apply persisted per-app locale before setContentView.
        LocaleHelper.setLocale(this, LocaleHelper.getLanguage(this));
        setContentView(R.layout.activity_connection_wizard);

        MaterialButton btnContinue = findViewById(R.id.btnWizardContinue);
        TextView btnSkip = findViewById(R.id.btnWizardSkip);

        View.OnClickListener cardListener = v -> selectCard(v.getId());
        for (int id : CARD_IDS) {
            View card = findViewById(id);
            if (card != null) card.setOnClickListener(cardListener);
        }

        // Default selection = Auto-detect.
        selectCard(R.id.optAuto);

        btnContinue.setOnClickListener(v -> {
            persistSelection(selectedPosition);
            finish();
        });
        btnSkip.setOnClickListener(v -> {
            persistSelection(POS_AUTO);
            finish();
        });
    }

    private void selectCard(int cardId) {
        for (int i = 0; i < CARD_IDS.length; i++) {
            MaterialCardView card = findViewById(CARD_IDS[i]);
            View check = findViewById(CHECK_IDS[i]);
            boolean selected = (CARD_IDS[i] == cardId);
            if (card != null) {
                card.setStrokeWidth(selected ? 2 : 0);
            }
            if (check != null) {
                check.setVisibility(selected ? View.VISIBLE : View.GONE);
                if (selected) {
                    check.setBackgroundResource(R.drawable.bg_status_dot_on);
                    check.getBackground().setTint(
                            ContextCompat.getColor(this, R.color.primary));
                    selectedPosition = POSITIONS[i];
                }
            }
        }
    }

    private void persistSelection(int position) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_FIRST_RUN, true)
                .putInt(KEY_TRANSPORT_POS, position)
                .apply();
    }

    /** Returns true if the wizard has never been completed. */
    public static boolean shouldShow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getBoolean(KEY_FIRST_RUN, false);
    }
}
