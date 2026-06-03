/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the {@code /health} readiness/liveness contract produced by
 * {@link HealthHandler}.
 * <p>
 * {@link HealthHandler#handle} needs a live {@code HttpExchange} and reaches the
 * EDT services via {@code Activator} statics that cannot be stubbed headlessly,
 * so the readiness DECISION is factored into the pure
 * {@link JsonUtils#buildHealthResponse(String, boolean, Map)} /
 * {@link JsonUtils#computeMissingServices(Map)} helpers and exercised here. This
 * mirrors what {@code HealthHandler} feeds those helpers (running flag + the
 * core service-manager reference map).
 */
public class HealthHandlerTest
{
    /** Builds the core-service map the way {@link HealthHandler} does. */
    private static Map<String, Object> services(Object v8, Object bm, Object orchestrator)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("v8ProjectManager", v8);
        map.put("bmModelManager", bm);
        map.put("servicesOrchestrator", orchestrator);
        return map;
    }

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // --- ready (ok) ---

    @Test
    public void testReadyWhenRunningAndAllServicesPresent()
    {
        String json = JsonUtils.buildHealthResponse("2026.1.1", true,
            services(new Object(), new Object(), new Object()));
        JsonObject obj = parse(json);

        assertEquals(JsonUtils.HEALTH_STATUS_OK, obj.get("status").getAsString());
        assertTrue(obj.get("ready").getAsBoolean());
        assertTrue(obj.get("running").getAsBoolean());
        assertEquals("2026.1.1", obj.get("edt_version").getAsString());

        JsonArray missing = obj.get("missingServices").getAsJsonArray();
        assertEquals(0, missing.size());
    }

    // --- degraded (running but a core service missing) ---

    @Test
    public void testDegradedNamesTheMissingServiceWhenRunning()
    {
        // bmModelManager null while running -> degraded, named in missingServices.
        String json = JsonUtils.buildHealthResponse("2026.1.1", true,
            services(new Object(), null, new Object()));
        JsonObject obj = parse(json);

        assertEquals(JsonUtils.HEALTH_STATUS_DEGRADED, obj.get("status").getAsString());
        assertFalse(obj.get("ready").getAsBoolean());
        assertTrue(obj.get("running").getAsBoolean());

        JsonArray missing = obj.get("missingServices").getAsJsonArray();
        assertEquals(1, missing.size());
        assertEquals("bmModelManager", missing.get(0).getAsString());
    }

    @Test
    public void testDegradedListsAllMissingServicesInOrder()
    {
        // Two of three null while running -> degraded, both named in order.
        String json = JsonUtils.buildHealthResponse("v", true,
            services(null, new Object(), null));
        JsonObject obj = parse(json);

        assertEquals(JsonUtils.HEALTH_STATUS_DEGRADED, obj.get("status").getAsString());
        assertFalse(obj.get("ready").getAsBoolean());

        JsonArray missing = obj.get("missingServices").getAsJsonArray();
        assertEquals(2, missing.size());
        assertEquals("v8ProjectManager", missing.get(0).getAsString());
        assertEquals("servicesOrchestrator", missing.get(1).getAsString());
    }

    // --- starting (server not running yet) ---

    @Test
    public void testStartingWhenNotRunningEvenIfServicesPresent()
    {
        String json = JsonUtils.buildHealthResponse("v", false,
            services(new Object(), new Object(), new Object()));
        JsonObject obj = parse(json);

        assertEquals(JsonUtils.HEALTH_STATUS_STARTING, obj.get("status").getAsString());
        assertFalse(obj.get("ready").getAsBoolean());
        assertFalse(obj.get("running").getAsBoolean());
    }

    // --- liveness ping present in EVERY case ---

    @Test
    public void testLiveFieldAlwaysPresentAndTrue()
    {
        String ok = JsonUtils.buildHealthResponse("v", true,
            services(new Object(), new Object(), new Object()));
        String degraded = JsonUtils.buildHealthResponse("v", true,
            services(null, null, null));
        String starting = JsonUtils.buildHealthResponse("v", false,
            services(null, null, null));

        for (String json : new String[] {ok, degraded, starting})
        {
            JsonObject obj = parse(json);
            assertTrue("live must be present", obj.has("live"));
            assertTrue("live must be a true ping", obj.get("live").getAsBoolean());
        }
    }

    // --- back-compatibility: status stays a string, edt_version preserved ---

    @Test
    public void testStatusStaysStringAndEdtVersionPreserved()
    {
        String json = JsonUtils.buildHealthResponse("2026.1.1", true,
            services(new Object(), new Object(), new Object()));
        JsonObject obj = parse(json);

        assertTrue(obj.get("status").isJsonPrimitive());
        assertTrue(obj.get("status").getAsJsonPrimitive().isString());
        assertTrue(obj.has("edt_version"));
        assertEquals("2026.1.1", obj.get("edt_version").getAsString());
    }

    @Test
    public void testLegacyOverloadStillReportsOkWithLiveness()
    {
        // The no-readiness overload must remain back-compatible (ok + live).
        JsonObject obj = parse(JsonUtils.buildHealthResponse("2026.1.1"));
        assertEquals(JsonUtils.HEALTH_STATUS_OK, obj.get("status").getAsString());
        assertTrue(obj.get("live").getAsBoolean());
        assertEquals("2026.1.1", obj.get("edt_version").getAsString());
    }

    // --- pure helper: computeMissingServices ---

    @Test
    public void testComputeMissingServicesNullMapIsEmpty()
    {
        List<String> missing = JsonUtils.computeMissingServices(null);
        assertNotNull(missing);
        assertTrue(missing.isEmpty());
    }

    @Test
    public void testComputeMissingServicesAllPresent()
    {
        List<String> missing = JsonUtils.computeMissingServices(
            services(new Object(), new Object(), new Object()));
        assertTrue(missing.isEmpty());
    }

    @Test
    public void testComputeMissingServicesNamesNullsInOrder()
    {
        List<String> missing = JsonUtils.computeMissingServices(
            services(null, new Object(), null));
        assertEquals(2, missing.size());
        assertEquals("v8ProjectManager", missing.get(0));
        assertEquals("servicesOrchestrator", missing.get(1));
    }
}
