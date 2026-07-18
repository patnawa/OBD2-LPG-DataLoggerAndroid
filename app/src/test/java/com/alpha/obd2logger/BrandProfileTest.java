package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates the shipped brand profile and the longest-prefix matcher.
 *
 * <p>Moving brand rules from Java to JSON moves their errors from compile time
 * to runtime, where a typo silently becomes "this car is not recognised" on a
 * customer's vehicle. These tests are what buy that safety back.
 */
public class BrandProfileTest {

    @Before
    @After
    public void reset() {
        BrandProfile.resetForTest();
    }

    /**
     * Read straight from the source tree rather than through AssetManager:
     * Robolectric does not expose merged assets in this module, and the point of
     * these tests is to validate the exact file that ships.
     */
    private static File assetFile(String name) {
        File fromRoot = new File("app/src/main/assets/" + name);
        return fromRoot.exists() ? fromRoot : new File("src/main/assets/" + name);
    }

    private static JSONObject readShippedProfile() throws Exception {
        return new JSONObject(new String(
                Files.readAllBytes(assetFile("brand_profiles.json").toPath()),
                StandardCharsets.UTF_8));
    }

    // ── Shipped-asset validation ───────────────────────────────────────────

    @Test
    public void shippedProfileLoadsAndCoversEveryBrandName() throws Exception {
        JSONObject root = readShippedProfile();
        JSONObject brands = root.getJSONObject("brands");

        for (Iterator<String> it = brands.keys(); it.hasNext(); ) {
            String key = it.next();
            try {
                VinBrandDetector.Brand.valueOf(key);
            } catch (IllegalArgumentException e) {
                fail("brand_profiles.json names a brand with no enum constant: " + key);
            }
        }
        assertTrue("profile should cover most of the enum",
                brands.length() >= VinBrandDetector.Brand.values().length - 3);
    }

    /**
     * Two brands claiming the same WMI is unresolvable by longest-prefix and
     * would silently pick whichever parsed last.
     */
    @Test
    public void noTwoBrandsClaimTheSameWmi() throws Exception {
        JSONObject brands = readShippedProfile().getJSONObject("brands");
        Map<String, String> owner = new HashMap<>();

        for (Iterator<String> it = brands.keys(); it.hasNext(); ) {
            String brand = it.next();
            JSONArray wmi = brands.getJSONObject(brand).optJSONArray("wmi");
            if (wmi == null) continue;
            for (int i = 0; i < wmi.length(); i++) {
                String prefix = wmi.getString(i);
                String previous = owner.put(prefix, brand);
                if (previous != null) {
                    fail("WMI '" + prefix + "' claimed by both " + previous + " and " + brand);
                }
            }
        }
    }

    @Test
    public void everyWmiIsPlausible() throws Exception {
        JSONObject brands = readShippedProfile().getJSONObject("brands");
        for (Iterator<String> it = brands.keys(); it.hasNext(); ) {
            String brand = it.next();
            JSONArray wmi = brands.getJSONObject(brand).optJSONArray("wmi");
            assertNotNull(brand + " must declare at least one WMI", wmi);
            for (int i = 0; i < wmi.length(); i++) {
                String prefix = wmi.getString(i);
                assertTrue(brand + " WMI '" + prefix + "' must be 1-3 chars",
                        prefix.length() >= 1 && prefix.length() <= 3);
                assertTrue(brand + " WMI '" + prefix + "' must be uppercase VIN alphabet",
                        prefix.matches("[A-HJ-NPR-Z0-9]+"));
            }
        }
    }

    @Test
    public void everyEcuIdParsesAsHexAndEveryModePairIsWellFormed() throws Exception {
        JSONObject brands = readShippedProfile().getJSONObject("brands");
        for (Iterator<String> it = brands.keys(); it.hasNext(); ) {
            String brand = it.next();
            JSONObject entry = brands.getJSONObject(brand);

            JSONObject ecus = entry.optJSONObject("ecu_names");
            if (ecus != null) {
                for (Iterator<String> ids = ecus.keys(); ids.hasNext(); ) {
                    String id = ids.next();
                    try {
                        long parsed = Long.parseLong(id, 16);
                        assertTrue(brand + " CAN ID " + id + " out of range", parsed > 0);
                    } catch (NumberFormatException e) {
                        fail(brand + " has non-hex CAN ID: " + id);
                    }
                    assertTrue(brand + " CAN ID " + id + " has an empty name",
                            !ecus.getString(id).trim().isEmpty());
                }
            }

            JSONArray modes = entry.optJSONArray("enhanced_modes");
            if (modes != null) {
                for (int i = 0; i < modes.length(); i++) {
                    assertTrue(brand + " bad enhanced mode: " + modes.getString(i),
                            modes.getString(i).matches("[0-9A-F]{2},[0-9A-F]{2}"));
                }
            }
        }
    }

    // ── Matching behaviour ─────────────────────────────────────────────────

    /**
     * The core of the fix: a specific prefix must beat a broad one regardless of
     * declaration order. The old if-chain got this right only by hand-ordering,
     * and any rule below a broader one became dead code.
     */
    @Test
    public void longerPrefixWinsRegardlessOfDeclarationOrder() throws Exception {
        BrandProfile.loadFrom(readShippedProfile());

        assertEquals(VinBrandDetector.Brand.LEXUS, BrandProfile.brandForWmi("JTH"));
        assertEquals(VinBrandDetector.Brand.TOYOTA, BrandProfile.brandForWmi("JTD"));
        assertEquals(VinBrandDetector.Brand.NISSAN, BrandProfile.brandForWmi("MNT"));
        assertEquals(VinBrandDetector.Brand.TOYOTA, BrandProfile.brandForWmi("MNH"));
        assertEquals(VinBrandDetector.Brand.MAZDA, BrandProfile.brandForWmi("JM1"));
        assertEquals(VinBrandDetector.Brand.MITSUBISHI, BrandProfile.brandForWmi("JMB"));
        assertEquals(VinBrandDetector.Brand.MAZDA, BrandProfile.brandForWmi("MM8"));
        assertEquals(VinBrandDetector.Brand.MITSUBISHI, BrandProfile.brandForWmi("MMB"));
        assertEquals(VinBrandDetector.Brand.JEEP, BrandProfile.brandForWmi("1C4"));
        assertEquals(VinBrandDetector.Brand.CHRYSLER, BrandProfile.brandForWmi("1C3"));
    }

    /** The reported bug: LSG was documented as MG but never matched. */
    @Test
    public void mgIsDetectedFromLsgWhichPreviouslyReturnedUnknown() throws Exception {
        BrandProfile.loadFrom(readShippedProfile());

        assertEquals(VinBrandDetector.Brand.MG, BrandProfile.brandForWmi("LSG"));
        assertEquals(VinBrandDetector.Brand.MG, BrandProfile.brandForWmi("LSJ"));
    }

    /** Thai-market vehicles this app is primarily built for. */
    @Test
    public void thaiMarketWmisResolve() throws Exception {
        BrandProfile.loadFrom(readShippedProfile());

        assertEquals(VinBrandDetector.Brand.TOYOTA, BrandProfile.brandForWmi("MR0"));
        assertEquals(VinBrandDetector.Brand.HONDA, BrandProfile.brandForWmi("MRH"));
        assertEquals(VinBrandDetector.Brand.ISUZU, BrandProfile.brandForWmi("MPA"));
        assertEquals(VinBrandDetector.Brand.ISUZU, BrandProfile.brandForWmi("MPB"));
        assertEquals(VinBrandDetector.Brand.FORD, BrandProfile.brandForWmi("MAF"));
        assertEquals(VinBrandDetector.Brand.SUZUKI, BrandProfile.brandForWmi("MA3"));
        assertEquals(VinBrandDetector.Brand.TATA, BrandProfile.brandForWmi("MAT"));
        assertEquals(VinBrandDetector.Brand.MAHINDRA, BrandProfile.brandForWmi("MA1"));
    }

    @Test
    public void unknownWmiReturnsNull() throws Exception {
        BrandProfile.loadFrom(readShippedProfile());
        assertNull(BrandProfile.brandForWmi("QQQ"));
        assertNull(BrandProfile.brandForWmi(null));
    }

    /** Adding a brand must not be able to break an existing one. */
    @Test
    public void addingABroaderPrefixCannotStealAnExistingSpecificOne() throws Exception {
        JSONObject root = readShippedProfile();
        // "M" is deliberately broad — it would swallow every Thai WMI under the
        // old ordering-sensitive chain.
        JSONObject intruder = new JSONObject();
        intruder.put("wmi", new JSONArray(new String[] { "M" }));
        root.getJSONObject("brands").put("TESLA", intruder);
        BrandProfile.loadFrom(root);

        assertEquals("specific Thai WMIs must survive a broad newcomer",
                VinBrandDetector.Brand.TOYOTA, BrandProfile.brandForWmi("MR0"));
        assertEquals(VinBrandDetector.Brand.HONDA, BrandProfile.brandForWmi("MRH"));
        assertEquals("the broad prefix still applies where nothing else matches",
                VinBrandDetector.Brand.TESLA, BrandProfile.brandForWmi("MZZ"));
    }

    // ── Brand data lookups ─────────────────────────────────────────────────

    @Test
    public void ecuNamesAndEnhancedModesComeFromTheProfile() throws Exception {
        BrandProfile.loadFrom(readShippedProfile());

        Map<Integer, String> toyota = BrandProfile.ecuNamesFor(VinBrandDetector.Brand.TOYOTA);
        assertNotNull(toyota);
        assertTrue(toyota.get(0x7E0).contains("Engine"));

        Map<Integer, String> isuzu = BrandProfile.ecuNamesFor(VinBrandDetector.Brand.ISUZU);
        assertNotNull("29-bit IDs must survive hex parsing", isuzu);
        assertNotNull(isuzu.get(0x18DA00F1));

        assertEquals(List.of("1A,5A"),
                BrandProfile.enhancedModesFor(VinBrandDetector.Brand.NISSAN));
        assertEquals(List.of("21,61", "22,62"),
                BrandProfile.enhancedModesFor(VinBrandDetector.Brand.BMW));
    }

    /** A malformed profile must degrade to the Java rules, never crash. */
    @Test
    public void malformedProfileLeavesDetectionWorking() {
        try {
            BrandProfile.loadFrom(new JSONObject("{\"nonsense\":true}"));
            fail("expected a parse failure");
        } catch (Exception expected) {
            // The loader in load() catches this; detect() must still work.
        }
        assertEquals(VinBrandDetector.Brand.TOYOTA,
                VinBrandDetector.detect("MR053JV9401234567"));
    }

    /** Every brand with a DTC asset must have that asset actually present. */
    @Test
    public void everyReferencedDtcAssetExists() {
        List<String> missing = new ArrayList<>();
        for (VinBrandDetector.Brand brand : VinBrandDetector.Brand.values()) {
            String asset = VinBrandDetector.getDtcDatabaseAsset(brand);
            if (asset == null) continue;
            if (!assetFile(asset).exists()) {
                missing.add(brand + " -> " + asset);
            }
        }
        assertTrue("DTC assets referenced but not bundled: " + missing, missing.isEmpty());
    }
}
