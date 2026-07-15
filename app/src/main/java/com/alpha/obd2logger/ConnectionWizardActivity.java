package com.alpha.obd2logger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
 *
 * The readiness hint inspects system state only (Wi-Fi / Bluetooth / USB presence);
 * it does not open a socket or run the ELM327 handshake, so the isolation above holds.
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

    /** Single source of truth for the current choice; defaults to Auto-detect. */
    private int selectedPosition = POS_AUTO;

    private TextView readyHint;

    private final int[] CARD_IDS = {
            R.id.optWifi, R.id.optUsb, R.id.optBt,
            R.id.optBle, R.id.optAuto, R.id.optSim
    };
    private final int[] CHECK_IDS = {
            R.id.checkWifi, R.id.checkUsb, R.id.checkBt,
            R.id.checkBle, R.id.checkAuto, R.id.checkSim
    };
    private final int[] POSITIONS = {
            POS_WIFI, POS_USB, POS_SERIAL, POS_BLE, POS_AUTO, POS_SIM
    };
    // Parallel to CARD_IDS: title + description strings for accessibility labels.
    private final int[] TITLE_RES = {
            R.string.wizard_opt_wifi, R.string.wizard_opt_usb, R.string.wizard_opt_bt,
            R.string.wizard_opt_ble, R.string.wizard_opt_auto, R.string.wizard_opt_sim
    };
    private final int[] DESC_RES = {
            R.string.wizard_opt_wifi_desc, R.string.wizard_opt_usb_desc, R.string.wizard_opt_bt_desc,
            R.string.wizard_opt_ble_desc, R.string.wizard_opt_auto_desc, R.string.wizard_opt_sim_desc
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Apply persisted per-app locale before setContentView.
        LocaleHelper.setLocale(this, LocaleHelper.getLanguage(this));
        setContentView(R.layout.activity_connection_wizard);

        readyHint = findViewById(R.id.wizardReady);
        MaterialButton btnContinue = findViewById(R.id.btnWizardContinue);
        TextView btnSkip = findViewById(R.id.btnWizardSkip);

        View.OnClickListener cardListener = v -> selectCard(v.getId());
        for (int id : CARD_IDS) {
            View card = findViewById(id);
            if (card != null) card.setOnClickListener(cardListener);
        }

        // Default selection = Auto-detect (drives both visuals and selectedPosition).
        selectCard(R.id.optAuto);

        btnContinue.setOnClickListener(v -> {
            persistSelection(selectedPosition);
            finishWithResult();
        });
        // "Set up later" keeps whatever the user has highlighted (Auto by default)
        // instead of silently discarding a deliberate choice.
        btnSkip.setOnClickListener(v -> {
            persistSelection(selectedPosition);
            finishWithResult();
        });
    }

    private void selectCard(int cardId) {
        int strokePx = Math.round(2 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < CARD_IDS.length; i++) {
            MaterialCardView card = findViewById(CARD_IDS[i]);
            View check = findViewById(CHECK_IDS[i]);
            boolean selected = (CARD_IDS[i] == cardId);
            if (card != null) {
                // setStrokeWidth() takes pixels, so a dp→px conversion is required
                // or the selection outline is invisible on high-density screens.
                card.setStrokeWidth(selected ? strokePx : 0);
                card.setContentDescription(buildCardLabel(i, selected));
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
        updateReadyHint(cardId);
    }

    /** "<title>. <desc>. selected" so TalkBack reads one clean node per card. */
    private CharSequence buildCardLabel(int index, boolean selected) {
        String label = getString(TITLE_RES[index]) + ". " + getString(DESC_RES[index]);
        if (selected) label += ". " + getString(R.string.wizard_selected);
        return label;
    }

    /**
     * Shows a live readiness hint for the highlighted transport. System-state only:
     * Wi-Fi connectivity, Bluetooth adapter state, or a present USB device. Never
     * opens a driver connection. All lookups are wrapped defensively.
     */
    private void updateReadyHint(int cardId) {
        if (readyHint == null) return;
        int msg;
        try {
            if (cardId == R.id.optWifi) {
                msg = isOnWifi() ? R.string.wizard_ready_wifi_ok : R.string.wizard_ready_wifi_no;
            } else if (cardId == R.id.optUsb) {
                msg = hasUsbDevice() ? R.string.wizard_ready_usb_ok : R.string.wizard_ready_usb_no;
            } else if (cardId == R.id.optBt || cardId == R.id.optBle) {
                msg = isBluetoothOn() ? R.string.wizard_ready_bt_ok : R.string.wizard_ready_bt_no;
            } else if (cardId == R.id.optSim) {
                msg = R.string.wizard_ready_sim;
            } else {
                msg = R.string.wizard_ready_auto;
            }
        } catch (Exception e) {
            readyHint.setText("");
            return;
        }
        readyHint.setText(msg);
    }

    private boolean isOnWifi() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private boolean hasUsbDevice() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        return um != null && !um.getDeviceList().isEmpty();
    }

    private boolean isBluetoothOn() {
        BluetoothManager bm =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
        return adapter != null && adapter.isEnabled();
    }

    private void persistSelection(int position) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_FIRST_RUN, true)
                .putInt(KEY_TRANSPORT_POS, position)
                .apply();
    }

    private void finishWithResult() {
        // Signal MainActivity to re-apply the transport it now finds in prefs.
        setResult(RESULT_OK);
        finish();
    }

    /** Returns true if the wizard has never been completed. */
    public static boolean shouldShow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getBoolean(KEY_FIRST_RUN, false);
    }
}
