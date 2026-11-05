// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import com.ditrix.edt.mcp.server.HostKind;

public class ReadToolExecutionTest
{
    @Test public void transitiveHierarchyKeepsOneWorkbenchDisplayForRootAndChunks() throws Exception
    {
        String saved = System.getProperty("eclipse.application");
        try (var first = new ReadTestWorkbench())
        {
            System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
            var method = com.ditrix.edt.mcp.server.tools.impl.GetMethodCallHierarchyTool.class
                .getDeclaredMethod("transitiveExecutor", String.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            var executor = (java.util.function.Consumer<Runnable>)method.invoke(null, "project");
            try (var second = new ReadTestWorkbench())
            {
                int[] runs = {0};
                executor.accept(() -> runs[0]++);
                executor.accept(() -> runs[0]++);
                assertEquals(2, runs[0]);
                org.mockito.Mockito.verify(first.display, org.mockito.Mockito.times(2))
                    .syncExec(org.mockito.ArgumentMatchers.any());
                org.mockito.Mockito.verify(second.display, org.mockito.Mockito.never())
                    .syncExec(org.mockito.ArgumentMatchers.any());
            }
        }
        finally
        {
            if (saved == null) System.clearProperty("eclipse.application");
            else System.setProperty("eclipse.application", saved);
        }
    }

    @Test public void nestedPreparationBorrowsTheCapturedModelOnlyWhileItsReadIsActive() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            var manager = com.ditrix.edt.mcp.server.Activator.getDefault().getBmModelManager();
            org.mockito.Mockito.when(manager.getModel(fixture.project())).thenReturn(fixture.model, null);
            String result = ReadToolExecution.readOnHost(fixture.project(), "outer read", () ->
                ReadToolExecution.readOnHost(fixture.project(), "nested preparation", () -> {
                    assertEquals(1, fixture.depth);
                    return fixture.config.getCommonModules().get(0).getName();
                }));
            assertEquals("EnglishName", result);
            assertEquals(1, fixture.reads);
            org.mockito.Mockito.verify(manager, org.mockito.Mockito.times(1)).getModel(fixture.project());
            var refusal = assertThrows(IllegalStateException.class, () ->
                ReadToolExecution.readOnHost(fixture.project(), "next request", () -> "must not run"));
            assertTrue(refusal.getMessage(), refusal.getMessage().contains("BM model is unavailable"));
            assertEquals(0, fixture.depth);
            org.mockito.Mockito.verify(manager, org.mockito.Mockito.times(2)).getModel(fixture.project());
        }
    }

    @Test public void failedPreparationReleasesItsCapturedModel() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            var manager = com.ditrix.edt.mcp.server.Activator.getDefault().getBmModelManager();
            org.mockito.Mockito.when(manager.getModel(fixture.project())).thenReturn(fixture.model, null);
            var failure = new IllegalArgumentException("failed preparation");
            assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
                ReadToolExecution.readOnHost(fixture.project(), "outer read", () ->
                    ReadToolExecution.readOnHost(fixture.project(), "nested preparation", () -> { throw failure; }))));
            assertEquals(0, fixture.depth);
            assertThrows(IllegalStateException.class, () ->
                ReadToolExecution.readOnHost(fixture.project(), "next request", () -> "must not run"));
            org.mockito.Mockito.verify(manager, org.mockito.Mockito.times(2)).getModel(fixture.project());
        }
    }

    @Test public void metadataWriterRetainsCapturedWorkbenchDisplay() throws Exception
    {
        String saved = System.getProperty("eclipse.application");
        try (var first = new ReadTestWorkbench())
        {
            System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
            var executor = ReadToolExecution.workbenchExecutor();
            try (var second = new ReadTestWorkbench())
            {
                int[] runs = {0};
                executor.accept(() -> runs[0]++);
                assertEquals(1, runs[0]);
                org.mockito.Mockito.verify(first.display).syncExec(org.mockito.ArgumentMatchers.any());
                org.mockito.Mockito.verify(second.display, org.mockito.Mockito.never())
                    .syncExec(org.mockito.ArgumentMatchers.any());
            }
        }
        finally
        {
            if (saved == null) System.clearProperty("eclipse.application");
            else System.setProperty("eclipse.application", saved);
        }
    }

    @Test public void configurationPropertiesRunsInlineOnDefaultDisplayThread() throws Exception
    {
        String saved = System.getProperty("eclipse.application");
        var field = org.eclipse.swt.widgets.Display.class.getDeclaredField("Default"); field.setAccessible(true);
        Object previous = field.get(null);
        var display = org.mockito.Mockito.mock(org.eclipse.swt.widgets.Display.class);
        org.mockito.Mockito.when(display.getThread()).thenReturn(Thread.currentThread());
        try
        {
            field.set(null, display);
            System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
            int[] runs = {0};
            ReadToolExecution.dispatchOnDefaultDisplay(() -> runs[0]++);
            assertEquals(1, runs[0]);
            org.mockito.Mockito.verify(display, org.mockito.Mockito.never()).syncExec(org.mockito.ArgumentMatchers.any());
        }
        finally
        {
            field.set(null, previous);
            if (saved == null) System.clearProperty("eclipse.application");
            else System.setProperty("eclipse.application", saved);
        }
    }

    @Test public void dcsKeepsTheDefaultDisplayCapturedAtAdmission() throws Exception
    {
        String saved = System.getProperty("eclipse.application");
        var field = org.eclipse.swt.widgets.Display.class.getDeclaredField("Default"); field.setAccessible(true);
        Object previous = field.get(null);
        var first = org.mockito.Mockito.mock(org.eclipse.swt.widgets.Display.class);
        var second = org.mockito.Mockito.mock(org.eclipse.swt.widgets.Display.class);
        try
        {
            field.set(null, first);
            System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
            var executor = ReadToolExecution.defaultDisplayExecutor();
            field.set(null, second);
            Runnable work = () -> { };
            executor.accept(work);
            org.mockito.Mockito.verify(first).syncExec(work);
            org.mockito.Mockito.verify(second, org.mockito.Mockito.never()).syncExec(org.mockito.ArgumentMatchers.any());
        }
        finally
        {
            field.set(null, previous);
            if (saved == null) System.clearProperty("eclipse.application");
            else System.setProperty("eclipse.application", saved);
        }
    }

    @Test public void currentDisplayQueryValidationDoesNotDispatchToDefault() throws Exception
    {
        var current = org.mockito.Mockito.mock(org.eclipse.swt.widgets.Display.class);
        var other = org.mockito.Mockito.mock(org.eclipse.swt.widgets.Display.class);
        Class<?> type = org.eclipse.swt.widgets.Display.class;
        var displays = type.getDeclaredField("Displays"); displays.setAccessible(true);
        var defaultDisplay = type.getDeclaredField("Default"); defaultDisplay.setAccessible(true);
        var thread = type.getDeclaredField("thread"); thread.setAccessible(true);
        Object previousDisplays = displays.get(null), previousDefault = defaultDisplay.get(null);
        String application = System.getProperty("eclipse.application");
        try
        {
            thread.set(current, Thread.currentThread());
            displays.set(null, new org.eclipse.swt.widgets.Display[] { current });
            defaultDisplay.set(null, other);
            System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
            int[] runs = {0};
            new QueryValidationService().dispatch(() -> runs[0]++);
            assertEquals(1, runs[0]);
            org.mockito.Mockito.verifyNoMoreInteractions(other);
            org.mockito.Mockito.verifyNoMoreInteractions(current);
        }
        finally
        {
            displays.set(null, previousDisplays); defaultDisplay.set(null, previousDefault);
            if (application == null) System.clearProperty("eclipse.application");
            else System.setProperty("eclipse.application", application);
        }
    }

    @Test public void headlessOptionalDispatchAndRenderingNeverCreateSwt() throws Exception
    {
        String previous = System.getProperty("eclipse.application");
        try
        {
            System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
            Thread caller = Thread.currentThread();
            ReadToolExecution.dispatchOnDefaultDisplay(() -> assertSame(caller, Thread.currentThread()));
            assertTrue(ReadToolExecution.dispatchAsync(() -> assertSame(caller, Thread.currentThread())));
            assertTrue(ReadToolExecution.defaultDisplayAvailable());
            assertFalse(ReadToolExecution.dispatchRendering(() -> fail("Headless renderer reached SWT")));
        }
        finally
        {
            if (previous == null) System.clearProperty("eclipse.application");
            else System.setProperty("eclipse.application", previous);
        }
    }

    @Test public void callerThreadReadRetainsBmBoundaryForEnglishAndCyrillicNames() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            Thread caller = Thread.currentThread();
            for (String name : new String[] { "EnglishName", "Код" })
            {
                ReadToolExecution.dispatchRead(fixture.name(), "headless read " + name, () -> {
                    assertSame(caller, Thread.currentThread());
                    assertTrue(fixture.depth > 0);
                    assertTrue(fixture.config.getCommonModules().stream().anyMatch(m -> m.getName().equals(name)));
                });
            }
            assertEquals(2, fixture.reads);
        }
    }

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
