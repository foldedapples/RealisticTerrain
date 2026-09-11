package com.cokedoutsnail.realisticterrain.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings codec is what makes a world reproducible after a reload, and what keeps an old world
 * loadable when a new setting is added. Both are cheap to break silently, so both are pinned here.
 */
final class TerrainSettingsCodecTest {

    private static JsonElement encode(TerrainSettings settings) {
        return TerrainSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow();
    }

    private static TerrainSettings decode(String json) {
        return TerrainSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    /** Every setting survives a save/load round trip, bit for bit as far as the engine can tell. */
    @Test
    void roundTripsEverySetting() {
        TerrainSettings custom = TerrainSettings.DEFAULT
                .with(TerrainSetting.MOUNTAIN_HEIGHT, 2.35)
                .with(TerrainSetting.SEA_LEVEL, 140)
                .with(TerrainSetting.COAST_LINE, -0.21)
                .with(TerrainSetting.RIVER_DENSITY, 0.0)
                .with(TerrainSetting.GENERATE_STRUCTURES, 0.0)
                .with(TerrainSetting.MAXIMUM_TERRAIN_Y, 1500);
        TerrainSettings back = TerrainSettings.CODEC
                .parse(JsonOps.INSTANCE, encode(custom))
                .getOrThrow();
        assertEquals(custom, back);
        for (TerrainSetting key : TerrainSetting.values()) {
            assertEquals(custom.raw(key), back.raw(key), 1e-9, "lost " + key.jsonKey());
        }
    }

    /** A world saved before a setting existed must load, with the new key defaulted. */
    @Test
    void missingFieldsFallBackToDefaults() {
        TerrainSettings loaded = decode("{\"mountain_height\": 2.0}");
        assertEquals(2.0, loaded.mountainHeight(), 1e-6);
        for (TerrainSetting key : TerrainSetting.values()) {
            if (key == TerrainSetting.MOUNTAIN_HEIGHT) continue;
            assertEquals(key.defaultValue(), loaded.raw(key), 1e-9,
                    key.jsonKey() + " did not fall back to its default");
        }
    }

    /** Keys this version has never heard of - written by a newer build - must not fail the load. */
    @Test
    void unknownFieldsAreIgnored() {
        TerrainSettings loaded = decode("{\"mountain_height\": 1.5, \"a_future_setting\": 9.0, \"x\": \"y\"}");
        assertEquals(1.5, loaded.mountainHeight(), 1e-6);
    }

    /** A whole garbage payload still loads as defaults rather than aborting the registry load. */
    @Test
    void nonObjectPayloadLoadsAsDefaults() {
        assertEquals(TerrainSettings.DEFAULT, decode("42"));
        assertEquals(TerrainSettings.DEFAULT, decode("\"nonsense\""));
    }

    /** Hand-edited or corrupt values are clamped into the documented range, never rejected. */
    @Test
    void outOfRangeValuesAreClamped() {
        TerrainSettings loaded = decode(
                "{\"mountain_height\": 999.0, \"sea_level\": -500, \"coast_line\": 12.0, \"river_density\": -3.0}");
        assertEquals(TerrainSetting.MOUNTAIN_HEIGHT.max(), loaded.mountainHeight(), 1e-6);
        assertEquals(TerrainSetting.SEA_LEVEL.min(), loaded.seaLevel(), 1e-6);
        assertEquals(TerrainSetting.COAST_LINE.max(), loaded.coastLine(), 1e-6);
        assertEquals(TerrainSetting.RIVER_DENSITY.min(), loaded.riverDensity(), 1e-6);
    }

    /** Worlds saved before the descriptor table nested the control points; they must still decode. */
    @Test
    void legacyNestedControlPointsStillDecode() {
        TerrainSettings loaded = decode(
                "{\"mountain_height\": 1.0, \"control_points\": {\"coast_line\": -0.20, \"ocean_depth\": 137.0}}");
        assertEquals(-0.20f, loaded.coastLine(), 1e-4);
        assertEquals(137.0f, loaded.oceanDepth(), 1e-3);
    }

    /** Nothing the UI can offer may sit outside the range the codec accepts. */
    @Test
    void everyProfileStaysInsideItsDescriptorRange() {
        assertTrue(TerrainSettings.PROFILES.size() >= 7, "expected the named profiles");
        for (TerrainSettings.Profile p : TerrainSettings.PROFILES) {
            for (TerrainSetting key : TerrainSetting.values()) {
                double v = p.settings().raw(key);
                assertTrue(v >= key.min() && v <= key.max(),
                        p.nameKey() + " declares " + key.jsonKey() + "=" + v
                                + " outside [" + key.min() + ", " + key.max() + "]");
            }
        }
    }

    /** Settings that change generation must change the cache key, or two worlds would share terrain. */
    @Test
    void settingsThatChangeGenerationChangeTheCacheKey() {
        TerrainSettings base = TerrainSettings.DEFAULT;
        for (TerrainSetting key : TerrainSetting.values()) {
            double other = key.clamp(base.raw(key) == key.max() ? key.min() : base.raw(key) + 1.0);
            if (other == base.raw(key)) continue;
            TerrainSettings changed = base.with(key, other);
            assertNotEquals(base, changed, key.jsonKey() + " did not change the settings");
            assertNotEquals(base.hashCode(), changed.hashCode(),
                    key.jsonKey() + " did not change the cache key");
        }
        assertFalse(TerrainSettings.DEFAULT.withSeedSalt(7L).equals(TerrainSettings.DEFAULT));
        assertNotEquals(TerrainSettings.DEFAULT.hashCode(), TerrainSettings.DEFAULT.withSeedSalt(7L).hashCode());
    }

    /** The descriptor table's own claims must hold: legal defaults, sane bounds, unique keys. */
    @Test
    void descriptorTableIsSelfConsistent() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (TerrainSetting key : TerrainSetting.values()) {
            assertTrue(keys.add(key.jsonKey()), "duplicate json key " + key.jsonKey());
            assertTrue(key.min() < key.max(), key.jsonKey() + " has an empty range");
            assertEquals(key.defaultValue(), key.clamp(key.defaultValue()), 1e-9,
                    key.jsonKey() + " default is outside its own range");
            assertEquals(key.clamp(key.min() - 1), key.clamp(key.min()), 1e-9);
            assertFalse(key.tooltip().isBlank(), key.jsonKey() + " has no tooltip");
        }
        assertEquals(TerrainSetting.values().length, TerrainSettings.UI_SETTING_COUNT);
        for (TerrainSetting.Category category : TerrainSetting.CATEGORIES) {
            boolean used = false;
            for (TerrainSetting key : TerrainSetting.values()) {
                if (key.category() == category) used = true;
            }
            assertTrue(used, "category " + category.id() + " has no settings");
        }
    }

    /** The encoded form is the flat key:number object the world save has always used. */
    @Test
    void encodesAsAFlatNumericObject() {
        JsonObject json = encode(TerrainSettings.DEFAULT).getAsJsonObject();
        for (TerrainSetting key : TerrainSetting.values()) {
            assertTrue(json.has(key.jsonKey()), "missing " + key.jsonKey());
            assertTrue(json.get(key.jsonKey()).isJsonPrimitive(), key.jsonKey() + " is not a primitive");
        }
        assertTrue(json.has("seed_salt"));
    }
}
