/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.symbol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for {@link SymbolInfoService#meaningfulOrNull(String)} — the guard that keeps
 * get_symbol_info from leaking a bare {@code Object.toString()} reference when a hover
 * (e.g. an annotation/quick-fix hover at a validation-marked position) has no
 * extractable text. The full hover path needs a live workbench and is e2e-covered; this
 * unit-tests just the pure string guard.
 */
public class SymbolInfoServiceTest
{
    @Test
    public void testRawObjectReferenceBecomesNull()
    {
        // The exact shape observed in the audit: a BslAnnotationInfo quick-fix hover.
        assertNull(SymbolInfoService.meaningfulOrNull(
            "com._1c.g5.v8.dt.bsl.ui.hover.BslAnnotationWithQuickFixesHover$BslAnnotationInfo@1b8ef34a"));
    }

    @Test
    public void testSimpleClassAtHashBecomesNull()
    {
        assertNull(SymbolInfoService.meaningfulOrNull("Foo@deadbeef"));
    }

    @Test
    public void testNullAndBlankBecomeNull()
    {
        assertNull(SymbolInfoService.meaningfulOrNull(null));
        assertNull(SymbolInfoService.meaningfulOrNull("   "));
    }

    @Test
    public void testRealHoverContentIsKept()
    {
        String real = "Parameter Minuend\nPassing: by reference";
        assertEquals(real, SymbolInfoService.meaningfulOrNull(real));
    }

    @Test
    public void testTextWithAtSignInProseIsKept()
    {
        // A real hover with an @ inside multi-token prose is NOT a bare ClassName@hash.
        String s = "Annotation &Вместо(\"Method\") — overrides at line 9";
        assertEquals(s, SymbolInfoService.meaningfulOrNull(s));
    }
}
