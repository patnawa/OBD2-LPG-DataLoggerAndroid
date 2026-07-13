package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Session-independent API credential stored in private app preferences. */
public final class ApiSecurity {
    private static final String PREFS = "OBD2Prefs";
    private static final String KEY = "pref_api_access_token_v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiSecurity() {}

    public static String getOrCreateToken(Context context) {
        if (context == null) return generateToken();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY, "");
        if (isValid(existing)) return existing;
        String generated = generateToken();
        prefs.edit().putString(KEY, generated).apply();
        return generated;
    }

    static String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) token.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        return token.toString();
    }

    static boolean isValid(String token) {
        return token != null && token.matches("[0-9a-fA-F]{32,64}");
    }
}
