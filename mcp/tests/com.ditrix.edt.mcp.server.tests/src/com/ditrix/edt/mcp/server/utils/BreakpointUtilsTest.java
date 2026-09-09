/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Arrays;
import java.util.Collections;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.model.IBreakpoint;
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

    /**
     * A markerless member outranks a disagreement that was already seen. Two readable duplicates
     * with different filters plus one this code cannot read is not "they differ" - it is "nothing
     * was established", and the caller is owed the weaker claim.
     */
    @Test
    public void testAnUnreadableMemberOutranksADisagreementFoundBeforeIt()
    {
        IBreakpoint catchesAll = breakpointWithFilter(Boolean.TRUE, null);
        IBreakpoint filtered = breakpointWithFilter(Boolean.FALSE, "division by zero"); //$NON-NLS-1$
        IBreakpoint markerless = mock(IBreakpoint.class);
        when(markerless.getMarker()).thenReturn(null);

        // The order is the test: the disagreement is found FIRST, so an early return on it would
        // never look at the markerless member.
        assertEquals(BreakpointUtils.FilterAgreement.UNVERIFIED,
            BreakpointUtils.filterAgreement(Arrays.asList(catchesAll, filtered, markerless)));
    }

    /**
     * The other edge of the same rule. DIFFERENT is still returned when every member WAS read -
     * a scan that answered UNVERIFIED for any disagreement would pass the test above and lose the
     * distinction the caller acts on.
     */
    @Test
    public void testFiltersThatDisagreeAreStillReportedAsDifferentWhenAllWereRead()
    {
        IBreakpoint catchesAll = breakpointWithFilter(Boolean.TRUE, null);
        IBreakpoint filtered = breakpointWithFilter(Boolean.FALSE, "division by zero"); //$NON-NLS-1$
        assertEquals(BreakpointUtils.FilterAgreement.DIFFERENT,
            BreakpointUtils.filterAgreement(Arrays.asList(catchesAll, filtered)));

        IBreakpoint twin = breakpointWithFilter(Boolean.FALSE, "division by zero"); //$NON-NLS-1$
        assertEquals(BreakpointUtils.FilterAgreement.SAME,
            BreakpointUtils.filterAgreement(Arrays.asList(filtered, twin)));
    }

    /**
     * A breakpoint whose marker answers the two attributes the filter is read from.
     *
     * @param catchesAll the value of the all-exceptions attribute
     * @param message the exception message filter, or {@code null} for none
     * @return the mocked breakpoint
     */
    private static IBreakpoint breakpointWithFilter(Boolean catchesAll, String message)
    {
        IMarker marker = mock(IMarker.class);
        IBreakpoint breakpoint = mock(IBreakpoint.class);
        try
        {
            when(marker.exists()).thenReturn(true);
            when(marker.getAttribute(BreakpointUtils.ALL_EXCEPTIONS_ATTRIBUTE)).thenReturn(catchesAll);
            when(marker.getAttribute(BreakpointUtils.EXCEPTION_MESSAGE_ATTRIBUTE)).thenReturn(message);
        }
        catch (CoreException cannotHappenOnAMock)
        {
            throw new IllegalStateException(cannotHappenOnAMock);
        }
        when(breakpoint.getMarker()).thenReturn(marker);
        return breakpoint;
    }

    /**
     * The ids a half-done exception update touched travel WITH the failure, because nothing
     * withdraws them. The empty case is pinned too: a set whose members were all markerless has
     * nothing to name, and an empty list rendered as "" would read as a truncated sentence.
     */
    @Test
    public void testAPartialExceptionUpdateCarriesTheIdsItTouched()
    {
        BreakpointUtils.TouchedBreakpoints two = new BreakpointUtils.TouchedBreakpoints();
        two.add(breakpointWithMarkerId(41L));
        two.add(breakpointWithMarkerId(42L));
        BreakpointUtils.PartialExceptionUpdateException partial =
            new BreakpointUtils.PartialExceptionUpdateException(two,
                new IllegalStateException("marker no longer exists")); //$NON-NLS-1$
        assertEquals(Arrays.asList(Long.valueOf(41L), Long.valueOf(42L)), partial.getChangedIds());
        assertEquals("id(s) 41, 42", partial.describeTouched()); //$NON-NLS-1$
        assertEquals(2, partial.getBegunCount());
        // The cause survives: the caller is owed WHY it failed as much as what it touched.
        assertEquals("marker no longer exists", partial.getMessage()); //$NON-NLS-1$
    }

    /**
     * A member with no marker was changed like the rest and is reachable by no returned id, so
     * it has to be COUNTED. A phrase built from the ids alone understates the damage exactly
     * where the caller can do least about it.
     */
    @Test
    public void testAPartialExceptionUpdateCountsWhatItCouldNotName()
    {
        BreakpointUtils.TouchedBreakpoints mixed = new BreakpointUtils.TouchedBreakpoints();
        mixed.add(breakpointWithMarkerId(41L));
        mixed.add(markerlessBreakpoint());
        BreakpointUtils.PartialExceptionUpdateException partial =
            new BreakpointUtils.PartialExceptionUpdateException(mixed, new IllegalStateException());
        assertEquals("id(s) 41 and 1 more with no marker to name it by", //$NON-NLS-1$
            partial.describeTouched());
        assertEquals(2, partial.getBegunCount());
        // A cause with no message must not leave the sentence blank either.
        assertEquals("IllegalStateException", partial.getMessage()); //$NON-NLS-1$

        BreakpointUtils.TouchedBreakpoints nameless = new BreakpointUtils.TouchedBreakpoints();
        nameless.add(markerlessBreakpoint());
        nameless.add(markerlessBreakpoint());
        assertEquals("2 breakpoint(s), none of which has a marker to name it by", //$NON-NLS-1$
            new BreakpointUtils.PartialExceptionUpdateException(nameless,
                new IllegalStateException()).describeTouched());
    }

    /**
     * A breakpoint whose marker answers with the given id.
     *
     * @param id the marker id
     * @return the mocked breakpoint
     */
    private static IBreakpoint breakpointWithMarkerId(long id)
    {
        IMarker marker = mock(IMarker.class);
        when(marker.getId()).thenReturn(Long.valueOf(id));
        IBreakpoint breakpoint = mock(IBreakpoint.class);
        when(breakpoint.getMarker()).thenReturn(marker);
        return breakpoint;
    }

    /**
     * A breakpoint the platform recognises by interface but that carries no marker.
     *
     * @return the mocked breakpoint
     */
    private static IBreakpoint markerlessBreakpoint()
    {
        IBreakpoint breakpoint = mock(IBreakpoint.class);
        when(breakpoint.getMarker()).thenReturn(null);
        return breakpoint;
    }
}
