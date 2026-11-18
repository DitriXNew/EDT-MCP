/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.junit.Test;

import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;

/**
 * Issue #975: the preview's own passes poll the monitor the tool hands in - the Xtext rename's
 * condition checks and change creation, our text search, and the leaf walk that renders the change
 * points - and stop with {@link OperationCanceledException} once it is cancelled.
 */
public class MetadataRenameCancellationTest
{
    @Test
    public void testTheXtextRenameGetsAMonitorThatFollowsTheCallsMonitor() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        IProgressMonitor callMonitor = new NullProgressMonitor();
        service.useMonitor(callMonitor);
        RecordingRefactoring refactoring = new RecordingRefactoring();
        RecordingProcessor processor = new RecordingProcessor();

        service.createRenameChange(refactoring, processor, "New");

        assertEquals("New", processor.newName);
        assertEquals("checkInitialConditions, checkFinalConditions and createChange each get a monitor",
            3, refactoring.monitors.size());
        for (IProgressMonitor received : refactoring.monitors)
        {
            assertFalse(received.isCanceled());
        }
        callMonitor.setCanceled(true);
        for (IProgressMonitor received : refactoring.monitors)
        {
            assertTrue("A pass must see the preview being stopped", received.isCanceled());
        }
    }

    @Test
    public void testTheProcessorPathGetsAMonitorThatFollowsTheCallsMonitor() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        IProgressMonitor callMonitor = new NullProgressMonitor();
        service.useMonitor(callMonitor);
        RecordingProcessor processor = new RecordingProcessor();

        service.createRenameChange(null, processor, "New");

        assertEquals(3, processor.monitors.size());
        callMonitor.setCanceled(true);
        for (IProgressMonitor received : processor.monitors)
        {
            assertTrue(received.isCanceled());
        }
    }

    @Test
    public void testAStoppedPreviewStartsNoXtextRename() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        IProgressMonitor callMonitor = new NullProgressMonitor();
        callMonitor.setCanceled(true);
        service.useMonitor(callMonitor);
        RecordingRefactoring refactoring = new RecordingRefactoring();
        RecordingProcessor processor = new RecordingProcessor();

        assertThrows(OperationCanceledException.class,
            () -> service.createRenameChange(refactoring, processor, "New"));
        assertTrue(refactoring.monitors.isEmpty());
        assertNull(processor.newName);
    }

    @Test
    public void testTheTextSearchGetsTheMonitorAndStopsBetweenSearchStrings() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        IProgressMonitor callMonitor = new NullProgressMonitor();
        service.useMonitor(callMonitor);
        List<RecordingSearcher> searchers = new ArrayList<>();

        assertThrows(OperationCanceledException.class, () -> service.searchAll(List.of("Old", "Old2"),
            searchString -> {
                RecordingSearcher searcher = new RecordingSearcher(callMonitor);
                searchers.add(searcher);
                return searcher;
            }));
        assertEquals("The second search string is never searched once the preview stops", 1,
            searchers.size());
        assertNotNull(searchers.get(0).monitor);
        assertTrue("TextSearcher.search must see the preview being stopped",
            searchers.get(0).monitor.isCanceled());
    }

    @Test
    public void testTheRenderedLeafWalkStopsAtACancelledMonitor() throws Exception
    {
        MetadataRenameService rendering = new MetadataRenameService();
        String preview = preview(rendering, tree());
        assertTrue(preview, preview.contains("contentHash"));

        MetadataRenameService stopped = new MetadataRenameService();
        IProgressMonitor callMonitor = new NullProgressMonitor();
        callMonitor.setCanceled(true);
        stopped.useMonitor(callMonitor);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
            () -> preview(stopped, tree()));
        assertTrue(String.valueOf(failure.getCause()), failure.getCause() instanceof OperationCanceledException);
    }

    @Test
    public void testAPassThatFailsBecauseThePreviewStoppedIsNotSwallowed() throws Exception
    {
        // Cancelled only after the entry check: the pass then fails inside its try (no target), and
        // the catch must stop the preview instead of logging and carrying on with no matches.
        MetadataRenameService service = new MetadataRenameService();
        service.useMonitor(new CancelledAfterFirstAsk());
        Method exactMatches = method("exactMatchPass", org.eclipse.core.resources.IProject.class,
            com._1c.g5.v8.dt.metadata.mdclass.MdObject.class, String.class);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
            () -> exactMatches.invoke(service, null, null, "New"));
        assertTrue(String.valueOf(failure.getCause()), failure.getCause() instanceof OperationCanceledException);

        MetadataRenameService other = new MetadataRenameService();
        other.useMonitor(new CancelledAfterFirstAsk());
        Method edtPreview = method("edtBslPass", org.eclipse.core.resources.IProject.class,
            com._1c.g5.v8.dt.metadata.mdclass.MdObject.class, String.class);
        failure = assertThrows(InvocationTargetException.class, () -> edtPreview.invoke(other, null, null, "New"));
        assertTrue(String.valueOf(failure.getCause()), failure.getCause() instanceof OperationCanceledException);
    }

    @Test
    public void testANeverCancelledMonitorKeepsTheFailingPassesDegradingAsBefore() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        Method exactMatches = method("exactMatchPass", org.eclipse.core.resources.IProject.class,
            com._1c.g5.v8.dt.metadata.mdclass.MdObject.class, String.class);
        assertSame(MetadataRenameService.ExactPass.NONE, exactMatches.invoke(service, null, null, "New"));
        Method edtPreview = method("edtBslPass", org.eclipse.core.resources.IProject.class,
            com._1c.g5.v8.dt.metadata.mdclass.MdObject.class, String.class);
        assertNull(edtPreview.invoke(service, null, null, "New"));
    }

    /** Answers "not cancelled" to the first question and "cancelled" ever after. */
    private static final class CancelledAfterFirstAsk extends NullProgressMonitor
    {
        private int asked;

        @Override
        public boolean isCanceled()
        {
            return asked++ > 0;
        }
    }

    /** Shapes like the Xtext {@code RenameRefactoring}: the three calls the preview drives. */
    public static final class RecordingRefactoring
    {
        final List<IProgressMonitor> monitors = new ArrayList<>();

        public Object checkInitialConditions(IProgressMonitor monitor)
        {
            monitors.add(monitor);
            return null;
        }

        public Object checkFinalConditions(IProgressMonitor monitor)
        {
            monitors.add(monitor);
            return null;
        }

        public Change createChange(IProgressMonitor monitor)
        {
            monitors.add(monitor);
            return new NullChange("rename");
        }
    }

    /** Shapes like the Xtext rename processor. */
    public static final class RecordingProcessor
    {
        String newName;
        final List<IProgressMonitor> monitors = new ArrayList<>();

        public void setNewName(String name)
        {
            newName = name;
        }

        public Object checkInitialConditions(IProgressMonitor monitor)
        {
            monitors.add(monitor);
            return null;
        }

        public Object checkFinalConditions(IProgressMonitor monitor, CheckConditionsContext context)
        {
            monitors.add(monitor);
            return null;
        }

        public Change createChange(IProgressMonitor monitor)
        {
            monitors.add(monitor);
            return new NullChange("rename");
        }
    }

    /** Shapes like EDT's {@code TextSearcher}; stopping the preview while its search runs. */
    public static final class RecordingSearcher
    {
        private final IProgressMonitor stopper;
        IProgressMonitor monitor;

        RecordingSearcher(IProgressMonitor stopper)
        {
            this.stopper = stopper;
        }

        public void search(IProgressMonitor received)
        {
            monitor = received;
            stopper.setCanceled(true);
        }
    }

    private static List<IRefactoring> tree()
    {
        INativeChangeRefactoringItem item = mock(INativeChangeRefactoringItem.class);
        when(item.getNativeChange()).thenReturn(new CompositeChange("native",
            new Change[] {new NullChange("first"), new NullChange("second")}));
        when(item.isChecked()).thenReturn(true);
        when(item.isOptional()).thenReturn(true);
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getTitle()).thenReturn("Rename");
        when(refactoring.getItems()).thenReturn(List.of(item));
        return List.of(refactoring);
    }

    private static String preview(MetadataRenameService service, List<IRefactoring> tree) throws Exception
    {
        return (String)method("renderPreview", String.class, String.class, String.class, Collection.class,
            int.class, Map.class, List.class).invoke(service, "CommonModule.Old", "New", "Old", tree,
                0, Map.of(), List.of());
    }

    private static Method method(String name, Class<?>... parameters) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
}
