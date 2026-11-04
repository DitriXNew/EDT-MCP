// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import com.ditrix.edt.mcp.server.HostKind;

public class ReadToolExecutionTest
{
    @Test public void headlessDispatchCannotReachUiAndKeepsTheCallingThread()
    {
        Thread caller = Thread.currentThread();
        boolean[] ran = {false};
        ReadToolExecution.dispatch(HostKind.HEADLESS, () -> {
            assertSame(caller, Thread.currentThread()); ran[0] = true;
        }, work -> { throw new AssertionError("Headless dispatch reached UI"); });
        assertTrue(ran[0]);
    }

    @Test public void windowedDispatchStillUsesTheUiExecutorOnce()
    {
        int[] count = {0, 0};
        ReadToolExecution.dispatch(HostKind.WINDOWED, () -> count[0]++, work -> { count[1]++; work.run(); });
        assertArrayEquals(new int[] {1, 1}, count);
    }

    @Test public void coreExecutorHasNoPlatformUiOrDisplayLinkage() throws Exception
    {
        try (var bytes = ReadToolExecution.class.getResourceAsStream("ReadToolExecution.class"))
        {
            String constants = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constants, constants.contains("org/eclipse/ui/PlatformUI"));
            assertFalse(constants, constants.contains("org/eclipse/swt/widgets/Display"));
        }
    }

    @Test public void missingModelRefusesWithProjectAndRecoveryAdvice() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            org.mockito.Mockito.when(com.ditrix.edt.mcp.server.Activator.getDefault().getBmModelManager()).thenReturn(null);
            String result = fixture.call(new com.ditrix.edt.mcp.server.tools.impl.GetMetadataObjectsTool(), java.util.Map.of());
            assertTrue(result, result.contains("BM model is unavailable"));
            assertTrue(result, result.contains(fixture.name()));
            assertTrue(result, result.contains("list_projects"));
            assertEquals(0, fixture.reads);
        }
    }

    @Test public void unavailableModelIsAnExpectedRefusalInTheEdtLog() throws Exception
    {
        java.util.List<org.eclipse.core.runtime.IStatus> records = new java.util.ArrayList<>();
        org.eclipse.core.runtime.ILogListener listener = (status, plugin) -> {
            if (status.getMessage().startsWith("Error getting metadata")) { records.add(status); }
        };
        org.eclipse.core.runtime.Platform.addLogListener(listener);
        try (var fixture = new HeadlessReadFixture())
        {
            org.mockito.Mockito.when(com.ditrix.edt.mcp.server.Activator.getDefault().getBmModelManager()).thenReturn(null);
            fixture.call(new com.ditrix.edt.mcp.server.tools.impl.GetMetadataObjectsTool(), java.util.Map.of());
            fixture.call(new com.ditrix.edt.mcp.server.tools.impl.GetMetadataDetailsTool(),
                java.util.Map.of("objectFqns", "[\"CommonModule.Код\"]"));
            assertEquals(2, records.size());
            for (var record : records)
            {
                assertEquals(record.toString(), org.eclipse.core.runtime.IStatus.INFO, record.getSeverity());
                assertNull(record.getException());
            }
        }
        finally { org.eclipse.core.runtime.Platform.removeLogListener(listener); }
    }

    @Test public void unexpectedCoreFailuresKeepTheirStackInTheEdtLog() throws Exception
    {
        var failure = new IllegalStateException("Broken native model manager");
        java.util.List<org.eclipse.core.runtime.IStatus> records = new java.util.ArrayList<>();
        org.eclipse.core.runtime.ILogListener listener = (status, plugin) -> {
            if (status.getException() == failure) { records.add(status); }
        };
        org.eclipse.core.runtime.Platform.addLogListener(listener);
        try (var fixture = new HeadlessReadFixture())
        {
            var manager = com.ditrix.edt.mcp.server.Activator.getDefault().getBmModelManager();
            org.mockito.Mockito.when(manager.getModel(fixture.project())).thenThrow(failure);
            String objects = fixture.call(new com.ditrix.edt.mcp.server.tools.impl.GetMetadataObjectsTool(), java.util.Map.of());
            String details = fixture.call(new com.ditrix.edt.mcp.server.tools.impl.GetMetadataDetailsTool(),
                java.util.Map.of("objectFqns", "[\"CommonModule.Код\"]"));
            assertTrue(objects, objects.contains("Broken native model manager"));
            assertTrue(details, details.contains("Broken native model manager"));
            assertEquals(2, records.size());
            assertTrue(records.stream().allMatch(status -> status.getSeverity() == org.eclipse.core.runtime.IStatus.ERROR));
        }
        finally { org.eclipse.core.runtime.Platform.removeLogListener(listener); }
    }
}
