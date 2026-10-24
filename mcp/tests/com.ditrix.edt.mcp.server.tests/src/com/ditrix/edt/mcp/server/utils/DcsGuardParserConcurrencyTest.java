/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

/** Parser errors stay isolated across the existing guard and planner interleavings. */
public class DcsGuardParserConcurrencyTest
{
    @Test public void testConcurrentGuardsCannotClearEachOthersParserError() throws Exception
    {
        DcsParserConcurrencyTestSupport.race(false);
    }

    @Test public void testGuardCannotClearAnActivePlannersParserError() throws Exception
    {
        DcsParserConcurrencyTestSupport.race(true);
    }

    @Test
    public void testNoNewStaticMutableParserScratchFields()
    {
        for (Class<?> type : List.of(DcsSettingsWriter.class, DcsWriter.class,
            DcsPresentationParser.class, DcsDynamicListWriter.class))
        {
            assertNoStaticMutableFields(type);
        }
    }

    private static void assertNoStaticMutableFields(Class<?> type)
    {
        Set<String> mutable = Arrays.stream(type.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .filter(field -> Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()))
            .map(field -> field.getName()).collect(Collectors.toSet());
        assertEquals(type.getName(), Set.of(), mutable);
        for (Class<?> nested : type.getDeclaredClasses()) assertNoStaticMutableFields(nested);
    }
}
