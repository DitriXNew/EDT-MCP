/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Shared compact-decimal probe: stop unsafe normalization before it can allocate exponent digits. */
final class DcsNumberExpansionProbe extends BigDecimal
{
    private static final long serialVersionUID = 1L;
    boolean expansionAttempted;

    DcsNumberExpansionProbe(String literal)
    {
        super(literal);
    }

    DcsNumberExpansionProbe(BigInteger unscaled, int scale)
    {
        super(unscaled, scale);
    }

    @Override
    public BigDecimal setScale(int scale, RoundingMode rounding)
    {
        expansionAttempted = true;
        throw new AssertionError("setScale reached before the Number limits: " + toString());
    }

    @Override
    public String toPlainString()
    {
        expansionAttempted = true;
        throw new AssertionError("toPlainString reached before the Number limits: " + toString());
    }

    void assertNotExpanded()
    {
        assertFalse("the compact decimal must never be expanded", expansionAttempted);
    }
}
