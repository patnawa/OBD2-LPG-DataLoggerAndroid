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

public class LocaleHelper {

    public static final String LANG_SYSTEM = "default";
    public static final String LANG_ENGLISH = "en";
    public static final String LANG_THAI = "th";
    private static final String SELECTED_LANGUAGE = "Locale.Helper.Selected.Language";

    public static Context onAttach(Context context) {
        String lang = getPersistedData(context, LANG_SYSTEM);
        return wrapContext(context, lang);
    }

    public static String getLanguage(Context context) {
        return getPersistedData(context, LANG_SYSTEM);
    }

    public static Context setLocale(Context context, String language) {
        persist(context, language);

        // Update AndroidX AppCompat application locales
        try {
            if (LANG_SYSTEM.equals(language)) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return wrapContext(context, language);
    }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return updateResources(context, resolveLang);
        }
        return updateResourcesLegacy(context, resolveLang);
    }

    private static String getPersistedData(Context context, String defaultLanguage) {
        SharedPreferences preferences = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
        return preferences.getString(SELECTED_LANGUAGE, defaultLanguage);
    }

    private static void persist(Context context, String language) {
        SharedPreferences preferences = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(SELECTED_LANGUAGE, language);
        editor.apply();
    }

    private static Context updateResources(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale);
            LocaleList localeList = new LocaleList(locale);
            LocaleList.setDefault(localeList);
            configuration.setLocales(localeList);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                configuration.setLayoutDirection(locale);
            }
            context.getResources().updateConfiguration(configuration, context.getResources().getDisplayMetrics());
            return context.createConfigurationContext(configuration);
        } else {
            configuration.setLocale(locale);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                configuration.setLayoutDirection(locale);
            }
            context.getResources().updateConfiguration(configuration, context.getResources().getDisplayMetrics());
            return context;
        }
    }

    @SuppressWarnings("deprecation")
    private static Context updateResourcesLegacy(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());
        configuration.locale = locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            configuration.setLayoutDirection(locale);
        }

        resources.updateConfiguration(configuration, resources.getDisplayMetrics());

        return context;
    }
}
