/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.junit.Test;

/** Direct tests for the reflection-only breakpoint option helpers. */
public class BreakpointUtilsTest
{
    public enum TestHitCondition
    {
        EQUALS,
        EQUAL_OR_LESS,
        EQUAL_OR_HIGHER,
        MULTIPLIER
    }

    public interface NativeLineOptions
    {
        void setCondition(String value);

        void setHitCount(int value);

        void setHitCondition(TestHitCondition value);
    }

    @Test
    public void testHitConditionLiteralsAreExact()
    {
        assertArrayEquals(new String[] {
            "EQUALS", "EQUAL_OR_LESS", "EQUAL_OR_HIGHER", "MULTIPLIER" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }, BreakpointUtils.getValidHitConditions());
        for (String value : BreakpointUtils.getValidHitConditions())
        {
            assertTrue(BreakpointUtils.isValidHitCondition(value));
        }
        assertFalse(BreakpointUtils.isValidHitCondition("equals")); //$NON-NLS-1$
        assertFalse(BreakpointUtils.isValidHitCondition("AFTER")); //$NON-NLS-1$
        assertFalse(BreakpointUtils.isValidHitCondition(null));
    }

    @Test
    public void testNativeSettersArePreferred()
        throws Exception
    {
        ILineBreakpoint breakpoint = mock(ILineBreakpoint.class,
            withSettings().extraInterfaces(NativeLineOptions.class));
        IMarker marker = mock(IMarker.class);
        when(breakpoint.getMarker()).thenReturn(marker);

        BreakpointUtils.LineBreakpointConfiguration result =
            BreakpointUtils.configureLineBreakpoint(breakpoint, "A > 0", 3, "MULTIPLIER"); //$NON-NLS-1$ //$NON-NLS-2$

        NativeLineOptions options = (NativeLineOptions)breakpoint;
        verify(options).setCondition("A > 0"); //$NON-NLS-1$
        verify(options).setHitCount(3);
        verify(options).setHitCondition(TestHitCondition.MULTIPLIER);
        assertTrue(result.isApplied());
        assertTrue(result.getMarkerFallbacks().isEmpty());
    }

    @Test
    public void testMissingSettersFallBackToVerifiedMarkerAttributes()
        throws Exception
    {
        ILineBreakpoint breakpoint = mock(ILineBreakpoint.class);
        IMarker marker = mock(IMarker.class);
        when(breakpoint.getMarker()).thenReturn(marker);

        BreakpointUtils.LineBreakpointConfiguration result =
            BreakpointUtils.configureLineBreakpoint(breakpoint, "A > 0", 3, "MULTIPLIER"); //$NON-NLS-1$ //$NON-NLS-2$

        verify(marker).setAttribute(BreakpointUtils.CONDITION_ATTRIBUTE, "A > 0"); //$NON-NLS-1$
        verify(marker).setAttribute(BreakpointUtils.HIT_COUNT_ATTRIBUTE, Integer.valueOf(3));
        verify(marker).setAttribute(BreakpointUtils.HIT_CONDITION_ATTRIBUTE, "MULTIPLIER"); //$NON-NLS-1$
        assertTrue(result.isApplied());
        assertTrue(result.getMarkerFallbacks().contains("condition")); //$NON-NLS-1$
        assertTrue(result.getMarkerFallbacks().contains("hitCount")); //$NON-NLS-1$
        assertTrue(result.getMarkerFallbacks().contains("hitCondition")); //$NON-NLS-1$
    }
}
