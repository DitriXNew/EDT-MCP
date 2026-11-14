/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/** A modulePath refusal names the queryText fallback exactly once (#921). */
public class ValidateQueryToolModuleErrorTest
{
    private static final String PATH = "CommonModules/M/Module.bsl";

    @Test
    public void anExtractorRefusalKeepsItsOwnFixWithoutASecondFallback()
    {
        String refusal = "Stored BSL has a syntax error at line 8 (x). This is the module's BSL, not a query; "
            + "fix it using read_module_source or pass queryText directly.";
        String error = ValidateQueryTool.moduleReadError(PATH, new IllegalArgumentException(refusal));
        assertEquals("Could not read query literals from modulePath '" + PATH + "': " + refusal, error);
        assertEquals(error, 1, error.split("queryText", -1).length - 1);
        assertFalse(error, error.contains(".."));
    }

    @Test
    public void anUnexpectedFailureGetsTheGenericFallback()
    {
        String error = ValidateQueryTool.moduleReadError(PATH, new IllegalStateException("resource gone"));
        assertEquals("Could not read query literals from modulePath '" + PATH
            + "': resource gone. Check read_module_source or pass queryText directly.", error);
    }
}
