/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the per-object validation wait (issue #643): the slot and timeout guards, and the
 * registered-segment guard - an id the project's pipeline does not register must never reach
 * {@code waitComputation}, because EDT 2026.2.1 queues it in the priority task queue and the
 * derived-data scheduler then fails on every pass.
 */
public class BuildUtilsValidationWaitTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final long DEADLINE_MS = 10_000L;

    /** A pipeline that registers only the given ids and records every call. */
    private static final class FakePipeline implements BuildUtils.ValidationPipeline
    {
        final Set<String> registered;
        final Set<Long> brokenIds = new HashSet<>();
        final List<Map<Long, Collection<String>>> waits = new ArrayList<>();
        final List<Collection<String>> recordChecks = new ArrayList<>();
        boolean waitAnswer = true;

        FakePipeline(String... registered)
        {
            this.registered = new HashSet<>(Arrays.asList(registered));
        }

        @Override
        public boolean registers(String segmentId)
        {
            return registered.contains(segmentId);
        }

        @Override
        public boolean waitComputation(Map<Long, Collection<String>> scope, long timeoutMs)
        {
            waits.add(scope);
            return waitAnswer;
        }

        @Override
        public boolean isComputed(long topObjectId, Collection<String> segmentIds)
        {
            recordChecks.add(segmentIds);
            return !brokenIds.contains(Long.valueOf(topObjectId));
        }
    }

    @Before
    public void reset()
    {
        BuildUtils.forgetExportWaits();
    }

    @Test
    public void testValidationAndExportSlotsOfOneProjectDoNotBlockEachOther()
    {
        assertNotNull(BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));
        assertNotNull("an outstanding export wait must not refuse the validation wait of the same write", //$NON-NLS-1$
            BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS));
        assertNotNull("nor the marker read of the same write", //$NON-NLS-1$
            BuildUtils.beginMarkerRead(PROJECT, DEADLINE_MS));
        assertNull("but a second validation wait is refused", //$NON-NLS-1$
            BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS));
        assertNull("and a second marker read", BuildUtils.beginMarkerRead(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
        assertNull("and the export slot is still held", BuildUtils.beginExportWait(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
    }

    @Test
    public void testTheValidationSlotReopensOnReturn()
    {
        AtomicBoolean first = BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS);
        assertNotNull(first);
        first.set(true);

        AtomicBoolean second = BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS);
        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    public void testAnUnreturnedValidationClaimLapses() throws Exception
    {
        assertNotNull(BuildUtils.beginValidationWait(PROJECT, 1L));
        assertNull(BuildUtils.beginValidationWait(PROJECT, 1L));

        Thread.sleep(25);

        assertNotNull("an expired claim must not keep refusing", //$NON-NLS-1$
            BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS));
    }

    @Test
    public void testThePlatformNeverGetsANonPositiveTimeout()
    {
        assertEquals(1L, BuildUtils.platformTimeoutMs(0L));
        assertEquals(1L, BuildUtils.platformTimeoutMs(-1L));
        assertEquals(1L, BuildUtils.platformTimeoutMs(Long.MIN_VALUE));
        assertEquals(DEADLINE_MS, BuildUtils.platformTimeoutMs(DEADLINE_MS));
    }

    @Test
    public void testAnEmptyScopeIsUnobservableWithoutAskingThePlatform()
    {
        assertEquals(BuildUtils.ValidationState.UNOBSERVABLE,
            BuildUtils.waitForObjectValidation(null, Collections.<Long> emptyList(), DEADLINE_MS));
        assertNotNull("no slot was taken", BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
    }

    // ========== registered-segment guard ==========

    @Test
    public void testOnlyRegisteredSegmentsReachThePlatform() throws Exception
    {
        FakePipeline pipeline = new FakePipeline("M_CHECKS_SEGMENT", "CM_CHECKS_SEGMENT", "NOT_IN_OUR_LIST"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        BuildUtils.ValidationState state = BuildUtils.validate(pipeline, Arrays.asList(7L, 8L), DEADLINE_MS);

        assertEquals(BuildUtils.ValidationState.COMPLETE, state);
        assertEquals(1, pipeline.waits.size());
        Map<Long, Collection<String>> scope = pipeline.waits.get(0);
        assertEquals(new HashSet<>(Arrays.asList(7L, 8L)), scope.keySet());
        for (Collection<String> segments : scope.values())
        {
            assertEquals(Arrays.asList("M_CHECKS_SEGMENT", "CM_CHECKS_SEGMENT"), new ArrayList<>(segments)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Collection<String> segments : pipeline.recordChecks)
        {
            assertTrue("the record re-check asks the same registered ids", //$NON-NLS-1$
                pipeline.registered.containsAll(segments));
        }
    }

    @Test
    public void testNoRegisteredSegmentMeansNoWaitAndUnobservable() throws Exception
    {
        FakePipeline pipeline = new FakePipeline();

        assertEquals(BuildUtils.ValidationState.UNOBSERVABLE,
            BuildUtils.validate(pipeline, Collections.singletonList(7L), DEADLINE_MS));
        assertTrue("never 'clean' and never a wait on an unknown segment", pipeline.waits.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testNoRegisteredSegmentThroughTheBoundedWait()
    {
        FakePipeline pipeline = new FakePipeline();

        assertEquals(BuildUtils.ValidationState.UNOBSERVABLE,
            BuildUtils.waitForObjectValidation(pipeline, PROJECT, Collections.singletonList(7L), DEADLINE_MS));
        assertTrue(pipeline.waits.isEmpty());
        assertNotNull("the slot was released", BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
    }

    @Test
    public void testTheListNamesNoPartIds()
    {
        for (String partId : Arrays.asList("MD_VAL", "FORM_VAL", "MD_EXT_VAL")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertFalse(partId + " is a part id, registered as a segment by no pipeline", //$NON-NLS-1$
                BuildUtils.VALIDATION_SEGMENTS.contains(partId));
        }
    }

    // ========== the answer ==========

    @Test
    public void testAFalseWaitIsPending() throws Exception
    {
        FakePipeline pipeline = new FakePipeline("M_CHECKS_SEGMENT"); //$NON-NLS-1$
        pipeline.waitAnswer = false;

        assertEquals(BuildUtils.ValidationState.PENDING,
            BuildUtils.validate(pipeline, Collections.singletonList(7L), DEADLINE_MS));
        assertTrue("no record re-check after a timed-out wait", pipeline.recordChecks.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testABrokenRecordIsNotComplete() throws Exception
    {
        // waitComputation counts a broken record as done; its failed segment stays pending.
        FakePipeline pipeline = new FakePipeline("M_CHECKS_SEGMENT"); //$NON-NLS-1$
        pipeline.brokenIds.add(8L);

        assertEquals(BuildUtils.ValidationState.UNOBSERVABLE,
            BuildUtils.validate(pipeline, Arrays.asList(7L, 8L), DEADLINE_MS));
    }
}
