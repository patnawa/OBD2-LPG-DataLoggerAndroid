package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SmartSecondaryBusScanPreferencesTest {

    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(
                "SmartSecondaryBusScanPreferencesTest", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void freshInstallDefaultsEnabledAndInitializesCompatibilityKey() {
        assertTrue(SmartSecondaryBusScanPreferences.getEnabled(preferences));

        assertTrue(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.ENABLED_KEY, false));
        assertTrue(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, false));
        assertTrue(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.MIGRATED_KEY, false));
    }

    @Test
    public void legacyDisabledChoiceIsPreservedConservatively() {
        preferences.edit().putBoolean(
                SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, false).commit();

        assertFalse(SmartSecondaryBusScanPreferences.getEnabled(preferences));
        assertFalse(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.ENABLED_KEY, true));
    }

    @Test
    public void migratedLegacyOptOutCannotBeReenabledByFordVinAutomation() {
        preferences.edit().putBoolean(
                SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, false).commit();
        LoggerConfig config = new LoggerConfig();
        config.fordMsCanEnabled = true;

        VinSessionAutomation.applyFromPreferences(
                config, "MNBUMFF50FW123456", preferences, true);

        assertFalse(config.fordMsCanEnabled);
        assertFalse(SmartSecondaryBusScanPreferences.getEnabled(preferences));
    }

    @Test
    public void freshDefaultStillAllowsVerifiedFordAutomation() {
        LoggerConfig config = new LoggerConfig();

        VinSessionAutomation.applyFromPreferences(
                config, "MNBUMFF50FW123456", preferences, true);

        assertTrue(config.fordMsCanEnabled);
        assertTrue(SmartSecondaryBusScanPreferences.getEnabled(preferences));
    }

    @Test
    public void legacyEnabledChoiceIsPreserved() {
        preferences.edit().putBoolean(
                SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, true).commit();

        assertTrue(SmartSecondaryBusScanPreferences.getEnabled(preferences));
        assertTrue(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.ENABLED_KEY, false));
    }

    @Test
    public void newKeyIsAuthoritativeAndRepairsStaleLegacyMirror() {
        preferences.edit()
                .putBoolean(SmartSecondaryBusScanPreferences.ENABLED_KEY, false)
                .putBoolean(SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, true)
                .commit();

        assertFalse(SmartSecondaryBusScanPreferences.getEnabled(preferences));
        assertFalse(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, true));
    }

    @Test
    public void explicitChoiceWritesBothKeysForOldAndNewConsumers() {
        SmartSecondaryBusScanPreferences.setEnabled(preferences, false);
        assertFalse(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.ENABLED_KEY, true));
        assertFalse(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, true));

        SmartSecondaryBusScanPreferences.setEnabled(preferences, true);
        assertTrue(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.ENABLED_KEY, false));
        assertTrue(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, false));
    }

    @Test
    public void malformedNewValueFallsBackToValidLegacyBooleanAndIsRepaired() {
        preferences.edit()
                .putString(SmartSecondaryBusScanPreferences.ENABLED_KEY, "invalid")
                .putBoolean(SmartSecondaryBusScanPreferences.LEGACY_ENABLED_KEY, false)
                .commit();

        assertFalse(SmartSecondaryBusScanPreferences.getEnabled(preferences));
        assertFalse(preferences.getBoolean(
                SmartSecondaryBusScanPreferences.ENABLED_KEY, true));
    }

    @Test
    public void nullPreferenceOwnerUsesNewInstallDefaultWithoutCrashing() {
        assertTrue(SmartSecondaryBusScanPreferences.getEnabled(null));
        SmartSecondaryBusScanPreferences.setEnabled(null, false);
    }
}
