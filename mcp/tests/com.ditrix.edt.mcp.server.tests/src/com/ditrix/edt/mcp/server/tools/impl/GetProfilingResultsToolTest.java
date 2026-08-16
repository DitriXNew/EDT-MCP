/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link GetProfilingResultsTool}.
 * <p>
 * The headless surface of this tool is two-fold. First, the static contract
 * (name, response type, the slim description that steers to the guide, the
 * input/output schemas, and the migrated guide detail). Second, the part of
 * {@code execute()} that runs BEFORE the EDT/reflection path:
 * <ul>
 * <li>argument parsing for {@code moduleFilter}, {@code minFrequency},
 * {@code applicationId} and the {@code responseFormat} concise/detailed
 * coercion — none of which validates or rejects, so for ANY input
 * {@code execute()} stays total and returns parseable {@code success}-carrying
 * JSON;</li>
 * <li>the {@code profilingActive} precomputation, which reads the shared on/off
 * state owned by {@link StartProfilingTool} (no EDT access) — the
 * {@code isAnyProfilingActive()} branch when no {@code applicationId} is given and
 * the {@code isProfilingActive(id)} branch when one is.</li>
 * </ul>
 * <p>
 * Beyond that precomputation, {@code execute()}'s first action inside its
 * {@code try} is an OSGi bundle-presence guard ({@code Platform.getBundle(...)});
 * whether the {@code com._1c.g5.v8.dt.profiling.core} bundle resolves — and thus
 * whether the call reaches the benign "no results" sentinel (which carries
 * {@code count}/{@code profilingActive}) or returns a bundle-missing error — is
 * environment-dependent. The tests below therefore assert the precomputation only
 * on the sentinel path and otherwise pin only the env-independent invariant that
 * the result is parseable JSON. The per-result summary helpers and {@code latestOnly}
 * use reflective {@code Method} handles on a live profiling object and are off-limits
 * headless; the profiling readout is covered by the E2E suite.
 */
public class GetProfilingResultsToolTest
{
    /**
     * The {@code profilingActive} flag mirrors {@link StartProfilingTool}'s shared
     * state, so isolate every test from leaked profiling markers (here and in any
     * other test) before and after running.
     */
    @Before
    public void resetState()
    {
        StartProfilingTool.clearStateForTests();
    }

    @After
    public void clearState()
    {
        StartProfilingTool.clearStateForTests();
    }

    @Test
    public void testName()
    {
        assertEquals("get_profiling_results", new GetProfilingResultsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetProfilingResultsTool.NAME, new GetProfilingResultsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetProfilingResultsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetProfilingResultsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetProfilingResultsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"moduleFilter\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"minFrequency\"")); //$NON-NLS-1$
        // applicationId surfaces the on/off profiling state for a specific session.
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresResponseFormatEnum()
    {
        // responseFormat toggles concise (default, lean per-line rows) vs detailed
        // (full rows incl. code/method/dur). It must be declared as a closed enum so
        // schema-driven clients only offer the two accepted values, and to keep the
        // schema<->execute parity ratchet (the value is read in execute()).
        String schema = new GetProfilingResultsTool().getInputSchema();
        assertTrue(schema.contains("\"responseFormat\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"concise\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"detailed\"")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsActiveState()
    {
        // The tool surfaces whether profiling is currently active so a client can
        // tell a stop is pending; the description must advertise it.
        assertTrue(new GetProfilingResultsTool().getGuide().contains("active")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        // The slim description must point clients to the on-demand guide channel.
        assertTrue(new GetProfilingResultsTool().getDescription().contains("get_tool_guide")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // Exhaustive parameter/output detail moved out of the always-loaded
        // description/schema into the on-demand guide; verify it landed there.
        String guide = new GetProfilingResultsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("minFrequency")); //$NON-NLS-1$
        assertTrue(guide.contains("profilingActive")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsResponseFormat()
    {
        // The guide must explain the concise/detailed split and what concise omits so a
        // client knows the default is lean and how to opt into the full per-line readout.
        String guide = new GetProfilingResultsTool().getGuide();
        assertTrue(guide.contains("responseFormat")); //$NON-NLS-1$
        assertTrue(guide.contains("concise")); //$NON-NLS-1$
        assertTrue(guide.contains("detailed")); //$NON-NLS-1$
    }

    // ==================== Output schema (pure, env-independent) ====================

    @Test
    public void testOutputSchemaDeclaresResultFields()
    {
        // The output schema is the wire contract clients key off: the success flag,
        // the count (0 or 1 — only the latest session), the profilingActive on/off
        // hint, the no-results message and the per-result "results" array. Pin them so
        // a future edit can't drop a field the consumer relies on.
        String schema = new GetProfilingResultsTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare count", schema.contains("\"count\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare profilingActive", //$NON-NLS-1$
            schema.contains("\"profilingActive\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare message", schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare results", schema.contains("\"results\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaCountNotesLatestSessionOnly()
    {
        // The "only the latest session" contract (count is 0 or 1, historical sessions
        // dropped) must be documented on the count field so the consumer knows a single
        // result set — not a history — is returned.
        String schema = new GetProfilingResultsTool().getOutputSchema();
        assertTrue("outputSchema must note count is 0 or 1 (latest session only)", //$NON-NLS-1$
            schema.contains("latest session")); //$NON-NLS-1$
    }

    // ==================== execute() totality (runs before the EDT path) ====================
    //
    // The tool does NO argument validation: every parameter is optional and parsed
    // leniently (extractStringArgument / extractIntArgument), and the responseFormat
    // concise/detailed coercion treats any blank/unknown value as concise. So for ANY
    // input execute() must not throw and must return parseable JSON carrying the
    // success flag. The EDT/reflection readout that follows the precomputation is
    // covered by the E2E suite; here we pin only the env-independent invariant. A
    // null/false success-typed JSON is still well-formed (a bundle-missing error is
    // success:false; the no-results sentinel is success:true) — both are accepted, we
    // assert only that the field is present and boolean.

    private static JsonObject executeAsJson(Map<String, String> params)
    {
        String result = new GetProfilingResultsTool().execute(params);
        assertNotNull("execute() must never return null", result); //$NON-NLS-1$
        JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
        assertTrue("every execute() result must carry a boolean success flag", //$NON-NLS-1$
            obj.has("success") && obj.get("success").isJsonPrimitive()); //$NON-NLS-1$ //$NON-NLS-2$
        return obj;
    }

    /**
     * @return {@code true} when the call reached the benign "no results" sentinel
     *         (the OSGi profiling bundles resolved and there were no results), i.e.
     *         the env-dependent happy path that emits {@code count}/{@code profilingActive}.
     *         When a profiling bundle is absent the call returns a bundle-missing error
     *         instead, which carries neither field.
     */
    private static boolean isNoResultsSentinel(JsonObject obj)
    {
        return obj.get("success").getAsBoolean() && obj.has("count") //$NON-NLS-1$ //$NON-NLS-2$
            && obj.has("profilingActive"); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithNoParamsReturnsParseableJson()
    {
        // The all-defaults call (no filter, minFrequency=1, concise) must stay total
        // and well-formed regardless of which profiling bundles the runtime resolves.
        executeAsJson(new HashMap<>());
    }

    @Test
    public void testExecuteWithModuleFilterReturnsParseableJson()
    {
        // A module filter is a free-form, never-rejected substring — it must parse and
        // the call stays total.
        Map<String, String> params = new HashMap<>();
        params.put("moduleFilter", "CommonModule.Foo"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(params);
    }

    @Test
    public void testExecuteWithValidMinFrequencyReturnsParseableJson()
    {
        // A valid integer minFrequency parses via extractIntArgument; the call is total.
        Map<String, String> params = new HashMap<>();
        params.put("minFrequency", "5"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(params);
    }

    @Test
    public void testExecuteWithJsonNumberMinFrequencyReturnsParseableJson()
    {
        // Gson stringifies JSON numbers as "5.0"; extractIntArgument accepts the whole
        // form (5.0 -> 5) — the lenient parse must not throw.
        Map<String, String> params = new HashMap<>();
        params.put("minFrequency", "5.0"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(params);
    }

    @Test
    public void testExecuteWithGarbageMinFrequencyFallsBackAndDoesNotThrow()
    {
        // A non-numeric (and a non-integer "1.5") minFrequency falls back to the
        // default of 1 inside extractIntArgument rather than erroring — the parse is
        // lenient, so the call still returns well-formed JSON.
        Map<String, String> garbage = new HashMap<>();
        garbage.put("minFrequency", "abc"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(garbage);
        Map<String, String> fractional = new HashMap<>();
        fractional.put("minFrequency", "1.5"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(fractional);
    }

    @Test
    public void testExecuteWithResponseFormatDetailedReturnsParseableJson()
    {
        // detailed flips the verbose-columns coercion (case-insensitive); the branch
        // before the EDT path must not throw and the result stays parseable.
        Map<String, String> params = new HashMap<>();
        params.put("responseFormat", "DETAILED"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(params);
    }

    @Test
    public void testExecuteWithResponseFormatConciseReturnsParseableJson()
    {
        // The explicit concise value (the default verbosity) parses and stays total.
        Map<String, String> params = new HashMap<>();
        params.put("responseFormat", "concise"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(params);
    }

    @Test
    public void testExecuteWithUnknownResponseFormatCoercesToConciseWithoutThrowing()
    {
        // Any blank/unrecognized responseFormat is treated as concise (detailed is only
        // "detailed", case-insensitive). The coercion (responseFormat==null?null:trim())
        // must not throw on an unexpected token and the call stays total.
        Map<String, String> garbage = new HashMap<>();
        garbage.put("responseFormat", "verbose-please"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(garbage);
        Map<String, String> blank = new HashMap<>();
        blank.put("responseFormat", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(blank);
    }

    @Test
    public void testExecuteWithAllOptionsTogetherReturnsParseableJson()
    {
        // Every option supplied at once: all four parse paths run before the EDT
        // boundary and the result is still well-formed JSON.
        Map<String, String> params = new HashMap<>();
        params.put("moduleFilter", "Doc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("minFrequency", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app-xyz"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("responseFormat", "detailed"); //$NON-NLS-1$ //$NON-NLS-2$
        executeAsJson(params);
    }

    // ============ profilingActive precomputation reads the shared StartProfilingTool state ============
    //
    // The on/off flag is computed BEFORE the EDT path from StartProfilingTool's shared
    // markers: with no applicationId it surfaces isAnyProfilingActive(), and with one it
    // surfaces isProfilingActive(id). Whether the call then reaches the sentinel that
    // EMITS the flag depends on the profiling bundle resolving, so each assertion below
    // is guarded on the sentinel path; on the bundle-missing error path the result is
    // still parseable (executeAsJson already verified that) and the active-state check is
    // simply skipped. The precomputation's two branches are also pinned directly through
    // the StartProfilingTool state helpers, which need no EDT at all.

    @Test
    public void testProfilingActiveTrueWhenAnySessionActiveAndNoApplicationId()
    {
        // No applicationId -> the flag mirrors isAnyProfilingActive(); with a marker set
        // it must report true on the sentinel path.
        StartProfilingTool.markActive("some-session"); //$NON-NLS-1$
        JsonObject obj = executeAsJson(new HashMap<>());
        if (isNoResultsSentinel(obj))
        {
            assertTrue("profilingActive must mirror isAnyProfilingActive()==true", //$NON-NLS-1$
                obj.get("profilingActive").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void testProfilingActiveFalseWhenNoSessionActiveAndNoApplicationId()
    {
        // No applicationId and no markers -> isAnyProfilingActive()==false, so the
        // sentinel reports profilingActive:false.
        JsonObject obj = executeAsJson(new HashMap<>());
        if (isNoResultsSentinel(obj))
        {
            assertFalse("profilingActive must mirror isAnyProfilingActive()==false", //$NON-NLS-1$
                obj.get("profilingActive").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void testProfilingActiveTracksPerApplicationIdState()
    {
        // With an applicationId the flag is isProfilingActive(id): active only for the
        // marked id, false for a different one — proving the per-id branch, not the
        // global any-active branch, is used when an id is supplied.
        StartProfilingTool.markActive("debug-1"); //$NON-NLS-1$

        Map<String, String> active = new HashMap<>();
        active.put("applicationId", "debug-1"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject activeObj = executeAsJson(active);
        if (isNoResultsSentinel(activeObj))
        {
            assertTrue("profilingActive must be true for the marked applicationId", //$NON-NLS-1$
                activeObj.get("profilingActive").getAsBoolean()); //$NON-NLS-1$
        }

        Map<String, String> other = new HashMap<>();
        other.put("applicationId", "debug-2"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject otherObj = executeAsJson(other);
        if (isNoResultsSentinel(otherObj))
        {
            assertFalse("profilingActive must be false for an unmarked applicationId", //$NON-NLS-1$
                otherObj.get("profilingActive").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void testNoResultsSentinelCarriesGuidanceMessage()
    {
        // When the sentinel path is reached (profiling bundles resolved, no results
        // accumulated), it is a benign success — count:0 plus a message that steers the
        // caller back to start_profiling. Only asserted on that path; a bundle-missing
        // runtime returns an error instead, which has no count/message pair.
        JsonObject obj = executeAsJson(new HashMap<>());
        if (isNoResultsSentinel(obj))
        {
            assertEquals("the no-results sentinel must report count:0", //$NON-NLS-1$
                0, obj.get("count").getAsInt()); //$NON-NLS-1$
            assertTrue("the no-results sentinel must carry a guidance message", //$NON-NLS-1$
                obj.has("message")); //$NON-NLS-1$
            assertTrue("the message must steer the caller to start_profiling", //$NON-NLS-1$
                obj.get("message").getAsString().contains("start_profiling")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
