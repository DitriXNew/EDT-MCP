/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link OutputSizeGuard}.
 *
 * <p>Proves the absolute output-size guard's contract: a pure no-op below the
 * budget (byte-for-byte identical, same instance), and an actionable size-keyed
 * truncation above it that stays within the budget. The guard operates only on
 * content text; the handler-level proof that a JSON tool's structuredContent is
 * never touched lives in {@code McpProtocolHandlerTest}, but a representative
 * "JSON payload survives the guard intact" round-trip is asserted here too.</p>
 */
public class OutputSizeGuardTest
{
    // ========== below the budget: pure no-op ==========

    @Test
    public void testNullPassesThroughUnchanged()
    {
        assertNull("null content must stay null", OutputSizeGuard.cap(null));
    }

    @Test
    public void testEmptyStringIsUnchanged()
    {
        String empty = "";
        assertSame("an empty string must be returned as the same instance",
            empty, OutputSizeGuard.cap(empty));
    }

    @Test
    public void testBelowBudgetReturnsSameInstanceByteIdentical()
    {
        // A typical, already-paginated result is far below the cap and must be the
        // SAME string instance (no reformatting, no copy) — byte-for-byte identical.
        String content = "# Metadata\n\n| Name | Value |\n| --- | --- |\n| Code | 9 |\n";
        String capped = OutputSizeGuard.cap(content);
        assertSame("sub-budget content must be the same instance (pure no-op)",
            content, capped);
    }

    @Test
    public void testExactlyAtBudgetIsUnchanged()
    {
        // The boundary: length == budget is NOT truncated (the cap is exclusive of
        // the equal case), so an output that exactly fills the budget is preserved.
        String content = repeat('a', 64);
        String capped = OutputSizeGuard.cap(content, 64);
        assertSame("content exactly at the budget must be the same instance",
            content, capped);
    }

    @Test
    public void testOneCharBelowBudgetIsUnchanged()
    {
        String content = repeat('a', 63);
        assertSame(content, OutputSizeGuard.cap(content, 64));
    }

    // ========== above the budget: truncate + size-keyed actionable notice ==========

    @Test
    public void testAboveBudgetIsTruncated()
    {
        // Budget must exceed the notice length for the within-budget invariant to
        // hold: when the budget is large enough to hold the notice, the cut point
        // reserves room for it so the total stays within budget. (A pathologically
        // small budget keeps the full notice instead -- see
        // testBudgetSmallerThanNoticeKeepsTheFullNotice.)
        int budget = 2000;
        String content = repeat('x', budget + 5000);
        String capped = OutputSizeGuard.cap(content, budget);
        assertFalse("an over-budget result must change", content.equals(capped));
        assertTrue("a truncated result must stay within the budget",
            capped.length() <= budget);
    }

    @Test
    public void testTruncationNoticeIsSizeKeyedAndActionable()
    {
        // Use a budget large enough to fit the whole notice so its full, actionable
        // text is present in the capped output.
        int budget = 2000;
        String content = repeat('x', budget + 5000);
        String capped = OutputSizeGuard.cap(content, budget);

        assertTrue("notice must flag truncation", capped.contains("[OUTPUT TRUNCATED]"));
        // Size-keyed: it reports the budget and the original (full) size.
        assertTrue("notice must name the budget size", capped.contains(String.valueOf(budget)));
        assertTrue("notice must report the original size",
            capped.contains(String.valueOf(content.length())));
        // Actionable: it names concrete narrowing levers an agent can use.
        assertTrue("notice must name the limit lever", capped.contains("limit"));
        assertTrue("notice must name the modulePath lever", capped.contains("modulePath"));
        assertTrue("notice must name the fqn lever", capped.contains("fqn"));
    }

    @Test
    public void testTruncatedResultEndsWithTheNotice()
    {
        int budget = 2000;
        String content = repeat('x', budget + 100);
        String capped = OutputSizeGuard.cap(content, budget);
        String notice = OutputSizeGuard.buildTruncationNotice(content.length(), budget);
        assertTrue("the notice must be appended at the end", capped.endsWith(notice));
    }

    @Test
    public void testDefaultBudgetTruncatesAVeryLargeDump()
    {
        // A dump well over the project-wide MAX_CONTENT_CHARS is truncated by the
        // default cap and the result stays within that budget.
        String content = repeat('y', OutputSizeGuard.MAX_CONTENT_CHARS + 50_000);
        String capped = OutputSizeGuard.cap(content);
        assertTrue("over-default-budget content must be capped",
            capped.length() <= OutputSizeGuard.MAX_CONTENT_CHARS);
        assertTrue("default-budget truncation must carry the notice",
            capped.contains("[OUTPUT TRUNCATED]"));
    }

    @Test
    public void testDefaultBudgetConstantIsTheDocumentedSize()
    {
        assertEquals("the documented ~25k-token budget is 100_000 chars",
            100_000, OutputSizeGuard.MAX_CONTENT_CHARS);
    }

    // ========== JSON content survives the guard intact (round-trip) ==========

    @Test
    public void testSubBudgetJsonPayloadRoundTripsIntact()
    {
        // A JSON tool's payload that is below the budget must pass through the guard
        // unchanged, so the structured data still parses and round-trips. (The
        // handler keeps structuredContent off the content-text channel entirely;
        // this asserts the guard itself never corrupts a JSON string it does see.)
        String json = "{\"success\":true,\"value\":7,\"items\":[\"a\",\"b\"]}";
        String capped = OutputSizeGuard.cap(json);
        assertSame("a sub-budget JSON string must be the same instance", json, capped);

        JsonObject obj = JsonParser.parseString(capped).getAsJsonObject();
        assertEquals(7, obj.get("value").getAsInt());
        assertTrue(obj.get("success").getAsBoolean());
        assertEquals(2, obj.getAsJsonArray("items").size());
    }

    // ========== degenerate budgets ==========

    @Test
    public void testBudgetSmallerThanNoticeKeepsTheFullNotice()
    {
        // A pathologically small budget must not corrupt the notice: the guard keeps
        // the full notice rather than cutting it (a half-notice would be useless).
        String content = repeat('z', 10_000);
        String capped = OutputSizeGuard.cap(content, 5);
        String notice = OutputSizeGuard.buildTruncationNotice(content.length(), 5);
        assertEquals("a sub-notice budget yields exactly the notice", notice, capped);
    }

    @Test
    public void testNonPositiveBudgetIsTreatedAsOne()
    {
        // A zero/negative budget must not throw; it is clamped to 1 internally.
        String content = repeat('q', 100);
        String capped = OutputSizeGuard.cap(content, 0);
        assertTrue("a non-positive budget must still produce the notice",
            capped.contains("[OUTPUT TRUNCATED]"));
    }

    private static String repeat(char c, int count)
    {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++)
        {
            sb.append(c);
        }
        return sb.toString();
    }
}
