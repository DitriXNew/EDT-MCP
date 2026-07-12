/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.PiiDefaultsFetcher.FetchResult;
import com.ditrix.edt.mcp.server.preferences.PiiDefaultsFetcher.RulesetDecoder;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleCodec;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleSet;

/**
 * Tests the pure parse / validate / error-shaping core of {@link PiiDefaultsFetcher}
 * ({@code parseAndValidate}). The wire shape under test is the S1 {@code PiiRuleCodec}
 * schema (each rule carries a {@code "regex"} pattern plus optional
 * {@code enabled}/{@code scope}/{@code representation}/{@code countable} fields).
 *
 * <p>Most assertions stub the codec with a {@link RecordingDecoder} so they run headlessly
 * without any real network; {@link #testBundledCodecDefaultsPassValidation()} wires the
 * real {@link PiiRuleCodec} against the bundled defaults to prove the fetcher's validator
 * and the codec agree on one schema (the seam {@code PrivacyTab.updateFromRepo} exercises).
 *
 * <p>The invariant under test: untrusted content is size-bounded and per-row regex
 * compile-validated <b>before</b> being handed back, every failure yields a clear error
 * result object (never a thrown exception), and the decoder is only ever invoked once the
 * safety envelope has passed.
 */
public class PiiDefaultsFetcherTest
{
    private static final String DECODED = "DECODED-RULESET"; //$NON-NLS-1$

    /** A stub {@link RulesetDecoder} that records invocation and returns a sentinel (or throws). */
    private static final class RecordingDecoder implements RulesetDecoder<String>
    {
        int calls;
        private final boolean fail;

        RecordingDecoder(boolean fail)
        {
            this.fail = fail;
        }

        @Override
        public String decode(String json) throws Exception
        {
            calls++;
            if (fail)
            {
                throw new IllegalStateException("stub codec rejection"); //$NON-NLS-1$
            }
            return DECODED;
        }
    }

    // ---------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------

    @Test
    public void testValidPayloadReturnsDecodedRuleset()
    {
        String json = "{\"rules\":[" //$NON-NLS-1$
            + "{\"enabled\":true,\"scope\":\"NAME\",\"countable\":true,\"representation\":\"[redacted]\",\"regex\":\"email\"}," //$NON-NLS-1$
            + "{\"enabled\":true,\"scope\":\"VALUE\",\"countable\":true,\"representation\":\"[redacted]\",\"regex\":\"[0-9]{10}\"}" //$NON-NLS-1$
            + "]}"; //$NON-NLS-1$
        RecordingDecoder decoder = new RecordingDecoder(false);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, decoder);

        assertTrue("a well-formed payload must succeed", result.isOk()); //$NON-NLS-1$
        assertNull(result.getErrorMessage());
        assertEquals(DECODED, result.getRuleset());
        assertEquals(2, result.getRuleCount());
        assertSame("the validated raw JSON is returned verbatim", json, result.getRawJson()); //$NON-NLS-1$
        assertEquals("the decoder is invoked exactly once on success", 1, decoder.calls); //$NON-NLS-1$
    }

    @Test
    public void testBareTopLevelArrayIsAccepted()
    {
        String json = "[{\"scope\":\"NAME\",\"regex\":\"email\"}]"; //$NON-NLS-1$
        RecordingDecoder decoder = new RecordingDecoder(false);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, decoder);

        assertTrue(result.isOk());
        assertEquals(1, result.getRuleCount());
        assertEquals(1, decoder.calls);
    }

    @Test
    public void testNameScopeRegexIsCompileValidated()
    {
        // Every rule's regex is compile-checked regardless of scope (the redactor compiles
        // NAME-scope stems too), so a NAME-scope rule with an invalid regex is rejected.
        String json = "{\"rules\":[{\"scope\":\"NAME\",\"regex\":\"[unclosed\"}]}"; //$NON-NLS-1$
        RecordingDecoder decoder = new RecordingDecoder(false);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, decoder);

        assertFalse("a NAME-scope invalid regex must be rejected", result.isOk()); //$NON-NLS-1$
        assertTrue(result.getErrorMessage().toLowerCase().contains("regular expression")); //$NON-NLS-1$
        assertEquals("an uncompilable regex is rejected before decoding", 0, decoder.calls); //$NON-NLS-1$
    }

    @Test
    public void testNullDecoderValidatesOnly()
    {
        String json = "{\"rules\":[{\"scope\":\"NAME\",\"regex\":\"email\"}]}"; //$NON-NLS-1$

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, null);

        assertTrue("validation-only must succeed with a null decoder", result.isOk()); //$NON-NLS-1$
        assertNull("no decoder means no rule set", result.getRuleset()); //$NON-NLS-1$
        assertEquals(1, result.getRuleCount());
    }

    // ---------------------------------------------------------------------
    // Integration: the real codec-schema defaults survive the safety envelope
    // ---------------------------------------------------------------------

    @Test
    public void testBundledCodecDefaultsPassValidation()
    {
        // The REAL codec-schema defaults (regex/scope/representation/countable) must survive
        // the fetcher's safety envelope and decode cleanly, exactly the production seam
        // PrivacyTab.updateFromRepo drives: parseAndValidate(json, PiiRuleCodec::decode).
        PiiRuleSet defaults = PiiRuleCodec.loadBundledDefaults();
        assertFalse("the bundled PII defaults must load non-empty", defaults.isEmpty()); //$NON-NLS-1$
        String json = PiiRuleCodec.encode(defaults);

        FetchResult<PiiRuleSet> result =
            PiiDefaultsFetcher.<PiiRuleSet>parseAndValidate(json, PiiRuleCodec::decode);

        assertTrue("the real codec-schema defaults must pass the safety envelope: " //$NON-NLS-1$
            + result.getErrorMessage(), result.isOk());
        assertNull(result.getErrorMessage());
        assertEquals(defaults.getRules().size(), result.getRuleCount());
        assertEquals("the fetcher must hand back the codec-decoded rule set", defaults, result.getRuleset()); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------
    // Malformed / empty
    // ---------------------------------------------------------------------

    @Test
    public void testMalformedJsonReturnsErrorAndSkipsDecoder()
    {
        RecordingDecoder decoder = new RecordingDecoder(false);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate("{\"rules\":", decoder); //$NON-NLS-1$

        assertFalse(result.isOk());
        assertNull(result.getRuleset());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().toLowerCase().contains("json")); //$NON-NLS-1$
        assertEquals("the decoder must NOT run on malformed input", 0, decoder.calls); //$NON-NLS-1$
    }

    @Test
    public void testEmptyPayloadReturnsError()
    {
        assertFalse(PiiDefaultsFetcher.parseAndValidate(null, new RecordingDecoder(false)).isOk());
        assertFalse(PiiDefaultsFetcher.parseAndValidate("   ", new RecordingDecoder(false)).isOk()); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------
    // Size / count bounds (untrusted content)
    // ---------------------------------------------------------------------

    @Test
    public void testOversizedPayloadReturnsErrorAndSkipsDecoder()
    {
        StringBuilder big = new StringBuilder(PiiDefaultsFetcher.MAX_PAYLOAD_BYTES + 1024);
        for (int i = 0; i < PiiDefaultsFetcher.MAX_PAYLOAD_BYTES + 512; i++)
        {
            big.append('a');
        }
        RecordingDecoder decoder = new RecordingDecoder(false);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(big.toString(), decoder);

        assertFalse(result.isOk());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().toLowerCase().contains("exceed")); //$NON-NLS-1$
        assertEquals("an oversized payload is rejected before parsing/decoding", 0, decoder.calls); //$NON-NLS-1$
    }

    @Test
    public void testTooManyRulesReturnsError()
    {
        StringBuilder json = new StringBuilder("{\"rules\":["); //$NON-NLS-1$
        int count = PiiDefaultsFetcher.MAX_RULES + 1;
        for (int i = 0; i < count; i++)
        {
            if (i > 0)
            {
                json.append(',');
            }
            json.append("{\"scope\":\"NAME\",\"regex\":\"x\"}"); //$NON-NLS-1$
        }
        json.append("]}"); //$NON-NLS-1$
        RecordingDecoder decoder = new RecordingDecoder(false);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json.toString(), decoder);

        assertFalse(result.isOk());
        assertTrue(result.getErrorMessage().toLowerCase().contains("rule")); //$NON-NLS-1$
        assertEquals(0, decoder.calls);
    }

    // ---------------------------------------------------------------------
    // Per-row validation
    // ---------------------------------------------------------------------

    @Test
    public void testInvalidRegexReturnsErrorAndSkipsDecoder()
    {
        String json = "{\"rules\":[{\"scope\":\"VALUE\",\"regex\":\"[unclosed\"}]}"; //$NON-NLS-1$
        RecordingDecoder decoder = new RecordingDecoder(false);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, decoder);

        assertFalse(result.isOk());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().toLowerCase().contains("regular expression")); //$NON-NLS-1$
        assertEquals("an uncompilable regex is rejected before decoding", 0, decoder.calls); //$NON-NLS-1$
    }

    @Test
    public void testScopelessRuleRegexIsCompileValidated()
    {
        // A rule that omits scope (the codec defaults it to BOTH) still has its regex
        // compile-checked.
        String json = "{\"rules\":[{\"regex\":\"(unbalanced\"}]}"; //$NON-NLS-1$

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, new RecordingDecoder(false));

        assertFalse(result.isOk());
        assertTrue(result.getErrorMessage().toLowerCase().contains("regular expression")); //$NON-NLS-1$
    }

    @Test
    public void testOverlongPatternReturnsError()
    {
        StringBuilder pattern = new StringBuilder(PiiDefaultsFetcher.MAX_PATTERN_LENGTH + 16);
        for (int i = 0; i < PiiDefaultsFetcher.MAX_PATTERN_LENGTH + 5; i++)
        {
            pattern.append('a');
        }
        String json = "{\"rules\":[{\"scope\":\"VALUE\",\"regex\":\"" + pattern + "\"}]}"; //$NON-NLS-1$ //$NON-NLS-2$

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, new RecordingDecoder(false));

        assertFalse(result.isOk());
        assertTrue(result.getErrorMessage().toLowerCase().contains("longer")); //$NON-NLS-1$
    }

    @Test
    public void testMissingRegexReturnsError()
    {
        String json = "{\"rules\":[{\"scope\":\"NAME\"}]}"; //$NON-NLS-1$

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, new RecordingDecoder(false));

        assertFalse(result.isOk());
        assertTrue(result.getErrorMessage().toLowerCase().contains("regex")); //$NON-NLS-1$
    }

    @Test
    public void testRuleNotAnObjectReturnsError()
    {
        String json = "{\"rules\":[\"not-an-object\"]}"; //$NON-NLS-1$

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, new RecordingDecoder(false));

        assertFalse(result.isOk());
        assertTrue(result.getErrorMessage().toLowerCase().contains("object")); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------
    // Shape
    // ---------------------------------------------------------------------

    @Test
    public void testObjectWithoutRulesArrayReturnsError()
    {
        FetchResult<String> result =
            PiiDefaultsFetcher.parseAndValidate("{\"foo\":1}", new RecordingDecoder(false)); //$NON-NLS-1$

        assertFalse(result.isOk());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    public void testScalarPayloadReturnsError()
    {
        FetchResult<String> result =
            PiiDefaultsFetcher.parseAndValidate("42", new RecordingDecoder(false)); //$NON-NLS-1$

        assertFalse(result.isOk());
        assertNotNull(result.getErrorMessage());
    }

    // ---------------------------------------------------------------------
    // Decoder rejection is shaped, never thrown
    // ---------------------------------------------------------------------

    @Test
    public void testDecoderRejectionIsShapedAsError()
    {
        String json = "{\"rules\":[{\"scope\":\"NAME\",\"regex\":\"email\"}]}"; //$NON-NLS-1$
        RecordingDecoder decoder = new RecordingDecoder(true);

        FetchResult<String> result = PiiDefaultsFetcher.parseAndValidate(json, decoder);

        assertFalse("a codec that throws must yield an error result, not propagate", result.isOk()); //$NON-NLS-1$
        assertNull(result.getRuleset());
        assertNotNull(result.getErrorMessage());
        assertEquals("the decoder was reached (safety envelope passed)", 1, decoder.calls); //$NON-NLS-1$
    }

    @Test
    public void testDefaultRefIsMaster()
    {
        assertEquals("master", PiiDefaultsFetcher.DEFAULT_REF); //$NON-NLS-1$
    }
}
