/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.function.ToIntFunction;

import org.junit.Test;

/**
 * The status item paints into a fixed-width canvas, but its text is not fixed: a long running
 * tool name, a larger UI font or display scaling all make it wider. These tests pin the rule
 * that the counter survives that and the status is what gives way.
 */
public class StatusTextLayoutTest
{
    /** Every character 7 px wide - enough to reason about, and independent of any display. */
    private static final ToIntFunction<String> MEASURE = text -> text.length() * 7;

    @Test
    public void testTextThatFitsIsLeftAlone()
    {
        assertEquals("MCP: idle", StatusTextLayout.elide("MCP: idle", 200, MEASURE)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLongerTextIsCutToTheRoomItWasGiven()
    {
        String elided =
            StatusTextLayout.elide("MCP: import_configuration_from_xml", 70, MEASURE); //$NON-NLS-1$

        assertTrue(elided.endsWith(StatusTextLayout.ELLIPSIS));
        assertTrue("elided text must fit: " + elided, MEASURE.applyAsInt(elided) <= 70); //$NON-NLS-1$
        assertTrue(elided.startsWith("MCP: ")); //$NON-NLS-1$
    }

    @Test
    public void testNoRoomYieldsNoStatusRatherThanAFragmentOverTheCounter()
    {
        assertEquals("", StatusTextLayout.elide("MCP: idle", 0, MEASURE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", StatusTextLayout.elide("MCP: idle", -20, MEASURE)); //$NON-NLS-1$ //$NON-NLS-2$
        // Not even one character plus the ellipsis fits into 14 px (4 characters = 28 px).
        assertEquals("", StatusTextLayout.elide("MCP: idle", 14, MEASURE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", StatusTextLayout.elide(null, 100, MEASURE)); //$NON-NLS-1$
    }

    @Test
    public void testCounterKeepsItsShareOfTheWidth()
    {
        // 200 px canvas, text starts at 18, a "00:12" counter of 35 px and a 4 px gap.
        assertEquals(143, StatusTextLayout.statusRoom(200, 18, 4, 35));
        // No counter, no reservation.
        assertEquals(182, StatusTextLayout.statusRoom(200, 18, 4, 0));
        // A canvas too small for the reservation must not hand out negative room.
        assertEquals(0, StatusTextLayout.statusRoom(40, 18, 4, 35));
    }

    @Test
    public void testCounterIsPulledLeftInsteadOfFallingOffTheEdge()
    {
        // Normal case: straight after the status.
        assertEquals(97, StatusTextLayout.counterX(18, 75, 4, 35, 200));
        // Tight case: the flow position would end at 213 px on a 200 px canvas, so the counter
        // moves left until its right edge lands on the border - clipped is not an option.
        assertEquals(165, StatusTextLayout.counterX(18, 160, 4, 35, 200));
        // Absurd case: never before the text origin.
        assertEquals(18, StatusTextLayout.counterX(18, 160, 4, 35, 30));
    }
}
