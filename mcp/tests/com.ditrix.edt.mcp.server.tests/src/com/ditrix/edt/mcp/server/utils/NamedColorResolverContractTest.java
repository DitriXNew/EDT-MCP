/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.Test;

/** A caller supplies one lookup result; no default adapter can introduce a second precedence. */
public class NamedColorResolverContractTest
{
    @Test
    public void testTheResolverHasOneAbstractEntryAndNoFallbackAdapter() throws Exception
    {
        var methods = StyleValueBuilder.NamedColorResolver.class.getDeclaredMethods();
        assertEquals(Arrays.toString(methods), 1, methods.length);
        assertEquals("resolveColor", methods[0].getName());
        assertTrue("resolution cannot default to another precedence", Modifier.isAbstract(methods[0].getModifiers()));
        assertFalse(methods[0].isDefault());
        for (var method : AppearanceValueBuilder.class.getDeclaredMethods())
        {
            assertFalse("the detached-target adapter must be removed: " + method,
                Arrays.asList(method.getParameterTypes()).contains(StyleValueBuilder.NamedColorResolver.class));
        }
    }
}
