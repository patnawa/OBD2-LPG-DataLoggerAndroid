package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ApiSecurityTest {
    @Test
    public void generatedTokensAreStrongAndUnique() {
        String first = ApiSecurity.generateToken();
        String second = ApiSecurity.generateToken();
        assertTrue(ApiSecurity.isValid(first));
        assertTrue(ApiSecurity.isValid(second));
        assertNotEquals(first, second);
        assertFalse(ApiSecurity.isValid("short"));
    }
}
