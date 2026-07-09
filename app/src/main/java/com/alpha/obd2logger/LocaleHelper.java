package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import java.util.Locale;

/**
 * Per-app language handling for Android 6 (API 23) through 16 (API 36+).
 *
 * Single source of truth: androidx.appcompat's Application Locales.
 * AppCompatDelegate.setApplicationLocales()/getApplicationLocales() persist the
 * user's choice across process restarts on EVERY supported API level (AppCompat
 * stores it internally), and on API 33+ AppCompatActivity applies those locales
 * in attachBaseContext — overriding any manual context wrapping. So we use
 * AppCompat's store exclusively and do NOT keep a second, conflicting copy in
 * our own OBD2Prefs (the old dual-write caused the language to reset to the
 * wrong value at startup / not be remembered on Android 13+).
 *
 * On API < 33 AppCompat also applies the stored locales, but we additionally
 * wrap the context via createConfigurationContext as a belt-and-suspenders
 * guarantee (idempotent with AppCompat's apply).
 */
public class LocaleHelper {

    public static final String LANG_SYSTEM = "default";
    public static final String LANG_ENGLISH = "en";
    public static final String LANG_THAI = "th";

    // Legacy key kept ONLY for one-time migration of an existing user setting.
    private static final String LEGACY_SELECTED_LANGUAGE = "Locale.Helper.Selected.Language";

    public static Context onAttach(Context context) {
        // On API 33+ AppCompatActivity/AppCompatDelegate applies the application
        // locales from its own store during attachBaseContext (after this call),
        // so we must not wrap here — AppCompat is authoritative and would override
        // any manual wrap anyway. Migrate a legacy OBD2Prefs value first so the
        // user's old choice is preserved.
        migrateLegacyPreference(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context;
        }
        // Pre-N: AppCompat applies stored locales too, but manually wrap to be safe.
        return wrapContext(context, resolveLanguage(context));
    }

    public static String getLanguage(Context context) {
        return getPersistedLanguage(context);
    }

    public static Context setLocale(Context context, String language) {
        applyLanguage(language);
        // Re-wrap for the calling context on pre-N (post-N relies on AppCompat store).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return wrapContext(context, language);
        }
        return context;
    }

    // ---- internals ----

    private static String resolveLanguage(Context context) {
        LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
        if (appLocales.isEmpty()) {
            return LANG_SYSTEM;
        }
        String tag = appLocales.toLanguageTags().split(",")[0];
        return (tag == null || tag.isEmpty()) ? LANG_SYSTEM : tag;
    }

    private static void applyLanguage(String language) {
        try {
            if (LANG_SYSTEM.equals(language)) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static String getPersistedLanguage(Context context) {
        // Source of truth is AppCompat's application locale store.
        return resolveLanguage(context);
    }

    private static void migrateLegacyPreference(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
        if (!prefs.contains(LEGACY_SELECTED_LANGUAGE)) {
            return; // nothing to migrate
        }
        String legacy = prefs.getString(LEGACY_SELECTED_LANGUAGE, LANG_SYSTEM);
        // Only migrate if AppCompat has no explicit choice yet (avoid clobbering).
        if (AppCompatDelegate.getApplicationLocales().isEmpty() && legacy != null
                && !legacy.equals(LANG_SYSTEM)) {
            applyLanguage(legacy);
        }
        // Clear the legacy key so we never read it again.
        prefs.edit().remove(LEGACY_SELECTED_LANGUAGE).apply();
    }

    @SuppressWarnings("deprecation")
    private static Context wrapContext(Context context, String language) {
        String resolveLang = language;
        if (LANG_SYSTEM.equals(language)) {
            Locale sysLocale;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sysLocale = Resources.getSystem().getConfiguration().getLocales().get(0);
            } else {
                sysLocale = Resources.getSystem().getConfiguration().locale;
            }
            resolveLang = sysLocale.getLanguage();
        }

        Locale locale = new Locale(resolveLang);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocale(locale);
            LocaleList localeList = new LocaleList(locale);
            localeList.setDefault(localeList);
            configuration.setLocales(localeList);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                configuration.setLayoutDirection(locale);
            }
            return context.createConfigurationContext(configuration);
        } else {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocale(locale);
            Locale.setDefault(locale);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                configuration.setLayoutDirection(locale);
            }
            context.getResources().updateConfiguration(configuration, context.getResources().getDisplayMetrics());
            return context;
        }
    }
}
