/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers.Reason;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers.Report;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers.Row;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tests the written-object marker report of the metadata writes (issue #643): the
 * {@code markersIncomplete} decision table, the reason precedence, the cap and the severity sort,
 * the Configuration exclusion and the published shape. The platform is replaced by a scripted
 * {@link WrittenObjectMarkers.Environment}; that a COMPLETE wait means current markers is a live
 * property the e2e suite checks.
 */
public class WrittenObjectMarkersTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final long BUDGET_MS = 10_000L;

    /** The Russian Catalog type token. */
    private static final String RU_CATALOG = "Справочник"; //$NON-NLS-1$
    /** A Russian programmatic Name. */
    private static final String RU_NAME = "Товары"; //$NON-NLS-1$
    /** The Russian configuration token. */
    private static final String RU_CONFIGURATION = "Конфигурация"; //$NON-NLS-1$

    /** A scripted platform that records what it was asked. */
    private static final class ScriptedEnvironment implements WrittenObjectMarkers.Environment
    {
        final Map<String, Long> ids = new HashMap<>();
        ValidationState state = ValidationState.COMPLETE;
        final List<Row> rows = new ArrayList<>();
        boolean checksDisabled;
        RuntimeException resolveFailure;
        RuntimeException readFailure;
        final List<Collection<Long>> waits = new ArrayList<>();
        final List<Long> waitTimeouts = new ArrayList<>();
        int reads;

        @Override
        public Map<String, Long> resolveTopObjects(String projectName, List<String> fqns)
        {
            if (resolveFailure != null)
            {
                throw resolveFailure;
            }
            Map<String, Long> resolved = new LinkedHashMap<>();
            for (String fqn : fqns)
            {
                if (ids.containsKey(fqn))
                {
                    resolved.put(fqn, ids.get(fqn));
                }
            }
            return resolved;
        }

        @Override
        public ValidationState awaitValidation(String projectName, Collection<Long> topObjectIds, long timeoutMs)
        {
            waits.add(new ArrayList<>(topObjectIds));
            waitTimeouts.add(Long.valueOf(timeoutMs));
            return state;
        }

        @Override
        public WrittenObjectMarkers.ProjectMarkers read(String projectName, Map<String, Long> topObjectIds,
            int maxRows)
        {
            reads++;
            if (readFailure != null)
            {
                throw readFailure;
            }
            List<Row> kept = new ArrayList<>(rows);
            kept.sort(WrittenObjectMarkers.ROW_ORDER);
            return new WrittenObjectMarkers.ProjectMarkers(rows.size(),
                kept.size() > maxRows ? new ArrayList<>(kept.subList(0, maxRows)) : kept);
        }

        @Override
        public boolean checksDisabled()
        {
            return checksDisabled;
        }
    }

    private static Map<String, List<String>> slice(String... fqns)
    {
        Map<String, List<String>> slice = new LinkedHashMap<>();
        slice.put(PROJECT, Arrays.asList(fqns));
        return slice;
    }

    private static Row row(String object, MarkerSeverity severity, String checkId)
    {
        return new Row(object, severity, checkId, "message of " + checkId, object, false); //$NON-NLS-1$
    }

    private static JsonObject attached(Report report)
    {
        JsonObject success = new JsonObject();
        success.addProperty("success", true); //$NON-NLS-1$
        success.addProperty("message", "Created Catalog.X"); //$NON-NLS-1$ //$NON-NLS-2$
        WrittenObjectMarkers.attach(success, report);
        return success;
    }

    // ========== decision table ==========

    @Test
    public void testCompleteAndCleanIsTheOnlyFalse()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$

        Report report = WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS); //$NON-NLS-1$
        JsonObject json = attached(report);

        assertFalse(json.get(WrittenObjectMarkers.KEY_INCOMPLETE).getAsBoolean());
        assertFalse("a complete report carries no reason", json.has(WrittenObjectMarkers.KEY_REASON)); //$NON-NLS-1$
        assertEquals(0, json.get(WrittenObjectMarkers.KEY_COUNT).getAsInt());
        assertEquals(0, json.getAsJsonArray(WrittenObjectMarkers.KEY_MARKERS).size());
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.startsWith("Created Catalog.X ")); //$NON-NLS-1$
        assertTrue(message, message.contains("finished: no markers")); //$NON-NLS-1$
        assertEquals(Collections.singletonList(Collections.singletonList(7L)), env.waits);
    }

    @Test
    public void testPendingIsIncompleteAndStillReturnsTheSnapshot()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$
        env.state = ValidationState.PENDING;
        env.rows.add(row("Catalog.X", MarkerSeverity.MAJOR, "db-object-anyref-type")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject json = attached(WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS)); //$NON-NLS-1$

        assertTrue(json.get(WrittenObjectMarkers.KEY_INCOMPLETE).getAsBoolean());
        assertEquals("validationPending", json.get(WrittenObjectMarkers.KEY_REASON).getAsString()); //$NON-NLS-1$
        assertEquals(1, json.get(WrittenObjectMarkers.KEY_COUNT).getAsInt());
        assertEquals("the snapshot is still read and returned", 1, //$NON-NLS-1$
            json.getAsJsonArray(WrittenObjectMarkers.KEY_MARKERS).size());
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("had not finished within 10s")); //$NON-NLS-1$
        assertTrue(message, message.contains("do not treat it as clean")); //$NON-NLS-1$
        assertTrue(message, message.contains("get_project_errors objectFqns=[Catalog.X]")); //$NON-NLS-1$
    }

    @Test
    public void testUnobservableWait()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$
        env.state = ValidationState.UNOBSERVABLE;

        Report report = WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS); //$NON-NLS-1$

        assertEquals(Reason.VALIDATION_UNOBSERVABLE, report.reason());
    }

    @Test
    public void testOneUnresolvedObjectMakesTheWholeReportIncomplete()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$

        Report report = WrittenObjectMarkers.collect(env, slice("Catalog.X", "Catalog.Gone"), BUDGET_MS); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("never silently dropped: the other object's COMPLETE does not vouch for it", //$NON-NLS-1$
            Reason.OBJECT_UNRESOLVED, report.reason());
        assertEquals("the resolved one is still waited for", 1, env.waits.size()); //$NON-NLS-1$
        assertEquals(Collections.singletonList(7L), env.waits.get(0));
    }

    @Test
    public void testNothingResolvedSkipsTheWaitAndTheRead()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();

        Report report = WrittenObjectMarkers.collect(env, slice("Catalog.Gone"), BUDGET_MS); //$NON-NLS-1$

        assertEquals(Reason.OBJECT_UNRESOLVED, report.reason());
        assertTrue("the platform rejects an empty scope, so no wait is started", env.waits.isEmpty()); //$NON-NLS-1$
        assertEquals(0, env.reads);
    }

    @Test
    public void testAReadThatThrowsIsReadFailedAndNeverEscapes()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$
        env.readFailure = new IllegalStateException("presentation exploded"); //$NON-NLS-1$

        JsonObject json = attached(WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS)); //$NON-NLS-1$

        assertTrue(json.get(WrittenObjectMarkers.KEY_INCOMPLETE).getAsBoolean());
        assertEquals("readFailed", json.get(WrittenObjectMarkers.KEY_REASON).getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().contains("could not be read")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAResolutionThatThrowsIsReadFailed()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.resolveFailure = new IllegalStateException("no model"); //$NON-NLS-1$

        Report report = WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS); //$NON-NLS-1$

        assertEquals(Reason.READ_FAILED, report.reason());
        assertTrue(env.waits.isEmpty());
    }

    @Test
    public void testChecksDisabledGloballyIsNeverClean()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$
        env.checksDisabled = true;

        Report report = WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS); //$NON-NLS-1$

        assertEquals("a COMPLETE wait is vacuous when the check framework produces no markers", //$NON-NLS-1$
            Reason.CHECKS_DISABLED, report.reason());
    }

    @Test
    public void testReasonPrecedence()
    {
        assertNull(WrittenObjectMarkers.reasonOf(false, false, false, false, false));
        assertEquals(Reason.READ_FAILED, WrittenObjectMarkers.reasonOf(true, true, true, true, true));
        assertEquals(Reason.OBJECT_UNRESOLVED, WrittenObjectMarkers.reasonOf(false, true, true, true, true));
        assertEquals(Reason.VALIDATION_UNOBSERVABLE, WrittenObjectMarkers.reasonOf(false, false, true, true, true));
        assertEquals(Reason.VALIDATION_PENDING, WrittenObjectMarkers.reasonOf(false, false, false, true, true));
        assertEquals(Reason.CHECKS_DISABLED, WrittenObjectMarkers.reasonOf(false, false, false, false, true));
        assertEquals(Arrays.asList("readFailed", "objectUnresolved", "validationUnobservable", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "validationPending", "checksDisabled"), Arrays.asList(Reason.wireValues())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnExhaustedBudgetIsPendingWithoutStartingAWait()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$

        Report report = WrittenObjectMarkers.collect(env, slice("Catalog.X"), 10L); //$NON-NLS-1$

        assertEquals(Reason.VALIDATION_PENDING, report.reason());
        assertTrue("a slice too small to observe anything must not start an un-cancellable wait", //$NON-NLS-1$
            env.waits.isEmpty());
        assertEquals("the snapshot is still read", 1, env.reads); //$NON-NLS-1$
    }

    @Test
    public void testSeveralObjectsShareOneWaitAndOneList()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$
        env.ids.put("Catalog.X.Form.Item.Form", 8L); //$NON-NLS-1$
        env.rows.add(row("Catalog.X.Form.Item.Form", MarkerSeverity.MINOR, "form-check")); //$NON-NLS-1$ //$NON-NLS-2$
        env.rows.add(row("Catalog.X", MarkerSeverity.CRITICAL, "mdo-name-length")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject json = attached(
            WrittenObjectMarkers.collect(env, slice("Catalog.X", "Catalog.X.Form.Item.Form"), BUDGET_MS)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("one wait over every object", 1, env.waits.size()); //$NON-NLS-1$
        assertEquals(Arrays.asList(7L, 8L), env.waits.get(0));
        assertTrue("the wait gets the remaining budget, positive", //$NON-NLS-1$
            env.waitTimeouts.get(0).longValue() > 0 && env.waitTimeouts.get(0).longValue() <= BUDGET_MS);
        JsonArray markers = json.getAsJsonArray(WrittenObjectMarkers.KEY_MARKERS);
        assertEquals(2, markers.size());
        JsonObject first = markers.get(0).getAsJsonObject();
        assertEquals("most severe first", "CRITICAL", first.get("severity").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Catalog.X", first.get("object").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("mdo-name-length", first.get("checkId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.has("location")); //$NON-NLS-1$
        assertTrue(first.has("message")); //$NON-NLS-1$
        assertTrue(first.has("hasQuickFix")); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("EDT reports 2 marker(s)")); //$NON-NLS-1$
        assertTrue(message, message.contains("1 at CRITICAL or above - fix them")); //$NON-NLS-1$
    }

    // ========== cap and sort ==========

    @Test
    public void testTruncationIsVisibleAndNeverSetsTheFlag()
    {
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$
        for (int i = 0; i < 20; i++)
        {
            env.rows.add(row("Catalog.X", MarkerSeverity.MINOR, "minor-" + i)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (int i = 0; i < 3; i++)
        {
            env.rows.add(row("Catalog.X", MarkerSeverity.CRITICAL, "critical-" + i)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        env.rows.add(row("Catalog.X", MarkerSeverity.ERRORS, "error-0")); //$NON-NLS-1$ //$NON-NLS-2$
        env.rows.add(row("Catalog.X", MarkerSeverity.TRIVIAL, "trivial-0")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject json = attached(WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS)); //$NON-NLS-1$

        assertFalse("truncation is not incompleteness", json.get(WrittenObjectMarkers.KEY_INCOMPLETE).getAsBoolean()); //$NON-NLS-1$
        assertEquals(25, json.get(WrittenObjectMarkers.KEY_COUNT).getAsInt());
        JsonArray markers = json.getAsJsonArray(WrittenObjectMarkers.KEY_MARKERS);
        assertEquals(WrittenObjectMarkers.MAX_ROWS, markers.size());
        assertEquals("ERRORS", markers.get(0).getAsJsonObject().get("severity").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 1; i <= 3; i++)
        {
            assertEquals("every error-class marker survives the cap", "CRITICAL", //$NON-NLS-1$ //$NON-NLS-2$
                markers.get(i).getAsJsonObject().get("severity").getAsString()); //$NON-NLS-1$
        }
        for (int i = 0; i < markers.size(); i++)
        {
            assertFalse("the least severe is dropped first", //$NON-NLS-1$
                "TRIVIAL".equals(markers.get(i).getAsJsonObject().get("severity").getAsString())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.contains("Showing 20 of 25")); //$NON-NLS-1$
        assertTrue(message, message.contains("4 at CRITICAL or above")); //$NON-NLS-1$
    }

    @Test
    public void testAllErrorClassPageSaysAtLeast()
    {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < WrittenObjectMarkers.MAX_ROWS; i++)
        {
            rows.add(row("Catalog.X", MarkerSeverity.ERRORS, "e" + i)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put("Catalog.X", 7L); //$NON-NLS-1$
        env.rows.addAll(rows);
        env.rows.add(row("Catalog.X", MarkerSeverity.ERRORS, "e-extra")); //$NON-NLS-1$ //$NON-NLS-2$

        String message = attached(WrittenObjectMarkers.collect(env, slice("Catalog.X"), BUDGET_MS)) //$NON-NLS-1$
            .get("message").getAsString(); //$NON-NLS-1$

        assertTrue(message, message.contains("at least 20 at CRITICAL or above")); //$NON-NLS-1$
    }

    @Test
    public void testRowOrderIsSeverityThenObjectThenLocation()
    {
        List<Row> rows = new ArrayList<>(Arrays.asList(
            new Row("Catalog.B", MarkerSeverity.MAJOR, "c", "m", "Catalog.B", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            new Row("Catalog.A", MarkerSeverity.MAJOR, "c", "m", "Catalog.A.Attribute.Z", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            new Row("Catalog.A", MarkerSeverity.MAJOR, "c", "m", "Catalog.A", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            new Row("Catalog.Z", null, "c", "m", "Catalog.Z", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            new Row("Catalog.Z", MarkerSeverity.BLOCKER, "c", "m", "Catalog.Z", false))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        rows.sort(WrittenObjectMarkers.ROW_ORDER);

        List<String> order = new ArrayList<>();
        for (Row r : rows)
        {
            order.add(r.location);
        }
        assertEquals(Arrays.asList("Catalog.Z", "Catalog.A", "Catalog.A.Attribute.Z", "Catalog.B", "Catalog.Z"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            order);
        assertNull("unknown severity sorts last", rows.get(4).severity); //$NON-NLS-1$
    }

    // ========== slice ==========

    @Test
    public void testIncidentalConfigurationIsDropped()
    {
        assertEquals(Collections.singletonList("Catalog.X"), //$NON-NLS-1$
            WrittenObjectMarkers.slice(Arrays.asList("Catalog.X", "Configuration"), "Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testIncidentalConfigurationIsDroppedForARussianAddress()
    {
        String ruFqn = RU_CATALOG + '.' + RU_NAME;
        String exported = "Catalog." + RU_NAME; //$NON-NLS-1$
        assertEquals(Collections.singletonList(exported),
            WrittenObjectMarkers.slice(Arrays.asList(exported, "Configuration"), ruFqn)); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationIsKeptWhenItIsTheTarget()
    {
        List<String> exported = Arrays.asList("Configuration", "Configuration.MainSectionCommandInterface"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(exported, WrittenObjectMarkers.slice(exported, "Configuration")); //$NON-NLS-1$
        assertEquals("the Russian configuration token addresses it too", exported, //$NON-NLS-1$
            WrittenObjectMarkers.slice(exported, RU_CONFIGURATION));
        assertEquals(exported,
            WrittenObjectMarkers.slice(exported, "Configuration.MainSectionCommandInterface")); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationIsKeptWhenItIsTheOnlyObjectWritten()
    {
        assertEquals(Collections.singletonList("Configuration"), //$NON-NLS-1$
            WrittenObjectMarkers.slice(Collections.singletonList("Configuration"), "Language.English")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCyrillicObjectFlowsIntoTheObjectColumnAndTheRecheck()
    {
        String fqn = "Catalog." + RU_NAME; //$NON-NLS-1$
        ScriptedEnvironment env = new ScriptedEnvironment();
        env.ids.put(fqn, 9L);
        env.state = ValidationState.PENDING;
        env.rows.add(row(fqn, MarkerSeverity.MAJOR, "x")); //$NON-NLS-1$

        JsonObject json = attached(WrittenObjectMarkers.collect(env, slice(fqn), BUDGET_MS));

        assertEquals(fqn, json.getAsJsonArray(WrittenObjectMarkers.KEY_MARKERS).get(0).getAsJsonObject()
            .get("object").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().contains("objectFqns=[" + fqn + "]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ========== budget ==========

    @Test
    public void testBudgetStaysWithinFiveToFifteenSeconds()
    {
        assertTrue(WrittenObjectMarkers.VALIDATION_BUDGET_MS >= 5_000L);
        assertTrue(WrittenObjectMarkers.VALIDATION_BUDGET_MS <= 15_000L);
        assertEquals(5_000L, WrittenObjectMarkers.clampBudgetMs(1L));
        assertEquals(15_000L, WrittenObjectMarkers.clampBudgetMs(60_000L));
        assertEquals(8_000L, WrittenObjectMarkers.clampBudgetMs(8_000L));
    }

    @Test
    public void testOutputSchemaDeclaresTheFourMembers()
    {
        String schema = WrittenObjectMarkers.declareOutput(JsonSchemaBuilder.object()).build();

        for (String key : Arrays.asList("markersIncomplete", "markersIncompleteReason", "markerCount", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "markers")) //$NON-NLS-1$
        {
            assertTrue(key + " declared: " + schema, schema.contains('"' + key + '"')); //$NON-NLS-1$
        }
        assertTrue(schema, schema.contains("\"validationPending\"")); //$NON-NLS-1$
    }
}
