/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #662 review: the shared marker collection measures its budget on a monotonic clock, so a wall-clock jump
 * during the object lookup neither grants the validation wait more time than the caller had left nor takes
 * any away.
 */
public class WrittenObjectMarkersClockTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String OBJECT = "Report.Sales.Template.Main.Template"; //$NON-NLS-1$

    private final AtomicLong millis = new AtomicLong(1_000_000L);
    private LongSupplier previous;

    @Before
    public void setUp()
    {
        previous = WrittenObjectMarkers.useClock(millis::get);
    }

    @After
    public void tearDown()
    {
        WrittenObjectMarkers.useClock(previous);
    }

    @Test
    public void testTheValidationWaitGetsWhatTheBudgetClockLeftAfterTheLookup()
    {
        List<Long> waits = new ArrayList<>();
        WrittenObjectMarkers.Environment env = new WrittenObjectMarkers.Environment()
        {
            @Override
            public Map<String, Long> resolveTopObjects(String projectName, List<String> fqns, long timeoutMs)
            {
                millis.addAndGet(3_000L); // the lookup takes 3 s on the budget clock
                Map<String, Long> ids = new LinkedHashMap<>();
                ids.put(OBJECT, Long.valueOf(10L));
                return ids;
            }

            @Override
            public BuildUtils.ValidationState awaitValidation(String projectName, Collection<Long> topObjectIds,
                long timeoutMs)
            {
                waits.add(Long.valueOf(timeoutMs));
                return BuildUtils.ValidationState.COMPLETE;
            }

            @Override
            public WrittenObjectMarkers.ProjectMarkers read(String projectName, Map<String, Long> topObjectIds,
                int maxRows, long timeoutMs)
            {
                return new WrittenObjectMarkers.ProjectMarkers(0, new ArrayList<>());
            }

            @Override
            public boolean checksDisabled()
            {
                return false;
            }
        };

        WrittenObjectMarkers.collect(env, Map.of(PROJECT, List.of(OBJECT)), 10_000L);

        assertEquals("the wait is the budget less the lookup, on the budget clock", List.of(Long.valueOf(7_000L)), waits); //$NON-NLS-1$
    }

    @Test
    public void testTheDefaultBudgetClockIsMonotonic()
    {
        WrittenObjectMarkers.useClock(previous);
        long before = System.nanoTime() / 1_000_000L;
        long reading = WrittenObjectMarkers.useClock(millis::get).getAsLong();
        long after = System.nanoTime() / 1_000_000L;
        assertEquals("the default clock reads System.nanoTime, not the wall clock", (before + after) / 2.0, //$NON-NLS-1$
            reading, (after - before) / 2.0 + 1.0);
    }
}
