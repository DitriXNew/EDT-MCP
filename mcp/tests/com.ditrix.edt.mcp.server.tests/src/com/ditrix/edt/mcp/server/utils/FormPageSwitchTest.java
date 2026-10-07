/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;

/**
 * Tests for {@link FormPageSwitch}: the exact render sequence of a page switch and of its restore,
 * which renders run (and which do not) when a step fails, and the retry on a foreign editor rebuild.
 * The fake drives the same reflective surface of {@code FormWysiwygRepresentation} the production
 * code uses; the pump stands in for the UI event loop, where EDT's own async rebuilds run.
 */
public class FormPageSwitchTest
{
    private static final int SHORT_TIMEOUT_MS = 400;
    private static final int PAGE_ID = 7;

    /** Stand-in for {@code CommandInterfaceMapping}. */
    public static final class FakeMapping
    {
    }

    /** Stand-in for {@code NativeRenderEvent} with the two factories the helper calls. */
    public static final class FakeEvent
    {
        int[] selectedIds;

        public static FakeEvent buildUpdateEvent()
        {
            return new FakeEvent();
        }

        public static FakeEvent buildSelectByIdEvent(long windowHandle, int[] ids)
        {
            FakeEvent event = new FakeEvent();
            event.selectedIds = ids;
            return event;
        }
    }

    /** Controller stand-in; answers {@code null} (mapping not ready) once its budget is spent. */
    private static final class FakeController
    {
        int readyRoots = Integer.MAX_VALUE;

        @SuppressWarnings("unused") // invoked reflectively (MappingController.getMappingRoot(Class))
        Object getMappingRoot(Class<?> mappingClass)
        {
            if (readyRoots <= 0)
            {
                return null;
            }
            readyRoots--;
            return new FakeMapping();
        }
    }

    /** The reflectively driven surface of {@code FormWysiwygRepresentation}, recording every render. */
    private static final class FakeEditor
    {
        @SuppressWarnings("unused") // read reflectively
        final Object form = new Object();
        final FakeController controller = new FakeController();
        Object hippoSession = new Object();
        @SuppressWarnings("unused") // read reflectively (FormPageSwitch.selectedIds)
        IStructuredSelection lastDomainSelection;
        final List<String> renders = new ArrayList<>();
        int throwOnRender = -1;

        // The native visualizer's page state, modelled only when pages are declared (see page()):
        // a full render shows every Pages group on its default page, then a select-by-id switches the
        // groups holding the selected ids and frames them; an update-only render keeps the pages.
        final Map<Integer, Integer> groupOfPage = new HashMap<>();
        final Map<Integer, Integer> pageOfElement = new HashMap<>();
        final Map<Integer, Integer> defaultPage = new TreeMap<>();
        final Map<Integer, Integer> shownPage = new TreeMap<>();
        int[] frame = new int[0];
        @SuppressWarnings("unused") // read reflectively (the render buffer)
        ImageData formImageData;

        void page(int group, int page, boolean isDefault)
        {
            groupOfPage.put(page, group);
            if (isDefault)
            {
                defaultPage.put(group, page);
                shownPage.put(group, page);
            }
        }

        void paint()
        {
            formImageData = stateImage(shownPage + "|" + Arrays.toString(frame)); //$NON-NLS-1$
        }

        @SuppressWarnings("unused") // invoked reflectively (the synchronous render body)
        private void rebuildInternal(Object renderedForm, FakeMapping mapping, FakeEvent event, boolean updateOnly)
        {
            int index = renders.size();
            renders.add((event.selectedIds == null ? "update" : "select" + Arrays.toString(event.selectedIds)) //$NON-NLS-1$ //$NON-NLS-2$
                + (updateOnly ? "/updateOnly" : "/full")); //$NON-NLS-1$ //$NON-NLS-2$
            if (index == throwOnRender)
            {
                throw new IllegalStateException("render failed"); //$NON-NLS-1$
            }
            hippoSession = new Object();
            if (groupOfPage.isEmpty())
            {
                return;
            }
            if (!updateOnly)
            {
                shownPage.putAll(defaultPage);
                frame = new int[0];
            }
            if (event.selectedIds != null)
            {
                for (int id : event.selectedIds)
                {
                    Integer page = groupOfPage.containsKey(id) ? Integer.valueOf(id) : pageOfElement.get(id);
                    if (page != null)
                    {
                        shownPage.put(groupOfPage.get(page), page);
                    }
                }
                frame = event.selectedIds;
            }
            paint();
        }
    }

    /** A 24-bit image whose pixels spell out the visualizer state, so equal images mean equal state. */
    private static ImageData stateImage(String state)
    {
        byte[] text = state.getBytes(StandardCharsets.UTF_8);
        int width = (text.length + 2) / 3;
        return new ImageData(width, 1, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF), 1,
            Arrays.copyOf(text, width * 3));
    }

    private static FormGroup pageItem(int id)
    {
        FormGroup page = FormFactory.eINSTANCE.createFormGroup();
        page.setName("Page" + id); //$NON-NLS-1$
        page.setId(id);
        page.setType(ManagedFormGroupType.PAGE);
        return page;
    }

    /** One Pages group 100 with pages 11 (default), 12 and 13; the target element 7 lies on page 13. */
    private static FakeEditor editorWithPages()
    {
        FakeEditor editor = new FakeEditor();
        editor.page(100, 11, true);
        editor.page(100, 12, false);
        editor.page(100, 13, false);
        editor.pageOfElement.put(Integer.valueOf(PAGE_ID), Integer.valueOf(13));
        editor.paint();
        return editor;
    }

    private static EditorScreenshotHelper.ShowElementTarget target()
    {
        return EditorScreenshotHelper.ShowElementTarget.resolved("Delivery", PAGE_ID); //$NON-NLS-1$
    }

    private static FormPageSwitch show(Object editor, Runnable pump)
    {
        FormPageSwitch pageSwitch = FormPageSwitch.prepare(editor, target(), SHORT_TIMEOUT_MS, pump);
        pageSwitch.show();
        return pageSwitch;
    }

    private static final Runnable NO_FOREIGN_REBUILD = () -> {
        // nothing else rebuilds the editor
    };

    @Test
    public void testSwitchSelectsClearsThenRestoresTheDefaultPages()
    {
        FakeEditor editor = new FakeEditor();
        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);

        assertNull(pageSwitch.getError());
        assertTrue(pageSwitch.isTouched());
        assertEquals(List.of("select[7]/full", "select[]/updateOnly"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(pageSwitch.restore());
        // Nothing was selected before: a plain full render, i.e. the default pages.
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "update/full"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testRestoreReappliesTheEditorsOwnSelection()
    {
        // The user clicked the 'Totals' tab: EDT stores the page as its last domain selection.
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup totals = FormFactory.eINSTANCE.createFormGroup();
        totals.setName("Totals"); //$NON-NLS-1$
        totals.setId(12);
        totals.setType(ManagedFormGroupType.PAGE);
        FakeEditor editor = new FakeEditor();
        editor.lastDomainSelection = new StructuredSelection(new Object[] { form, totals, "not an entity" }); //$NON-NLS-1$

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.restore());

        // The way EDT re-applies it after its own renders: 0 for the form, the item id for the page.
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[0, 12]/full"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            editor.renders);
    }

    @Test
    public void testSelectedIdsSkipNonEntities()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(5);
        FakeEditor editor = new FakeEditor();
        editor.lastDomainSelection = new StructuredSelection(new Object[] { field, new Object() });
        assertEquals("[5]", Arrays.toString(FormPageSwitch.selectedIds(editor))); //$NON-NLS-1$

        editor.lastDomainSelection = null;
        assertEquals(0, FormPageSwitch.selectedIds(editor).length);
    }

    @Test
    public void testFailedFirstRenderSwitchesNothingAndRestoresNothing()
    {
        FakeEditor editor = new FakeEditor();
        editor.controller.readyRoots = 0; // the mapping root never becomes ready

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);

        assertNotNull(pageSwitch.getError());
        assertTrue(pageSwitch.getError(), pageSwitch.getError().contains("still loading")); //$NON-NLS-1$
        assertFalse(pageSwitch.getError(), pageSwitch.getError().contains("-D")); //$NON-NLS-1$
        assertFalse(pageSwitch.isTouched());
        assertNull("nothing was switched, so there is nothing to restore", pageSwitch.restore()); //$NON-NLS-1$
        assertTrue("no render may run for a switch that never started", editor.renders.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testUnreachableHooksNameTheRealCause()
    {
        // No synchronous render hooks at all (another EDT build): no flag advice, nothing to restore.
        FormPageSwitch pageSwitch = show(new Object(), NO_FOREIGN_REBUILD);

        assertTrue(pageSwitch.getError(), pageSwitch.getError().contains("could not be driven")); //$NON-NLS-1$
        assertTrue(pageSwitch.getError(), pageSwitch.getError().contains("EDT log")); //$NON-NLS-1$
        assertFalse(pageSwitch.getError(), pageSwitch.getError().contains("nativeFormBufferedLayoutRender")); //$NON-NLS-1$
        assertFalse(pageSwitch.isTouched());
    }

    @Test
    public void testRenderThatThrowsIsStillRestored()
    {
        FakeEditor editor = new FakeEditor();
        editor.throwOnRender = 0;

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);

        assertTrue(pageSwitch.getError(), pageSwitch.getError().contains("failed")); //$NON-NLS-1$
        assertTrue("a throw inside the render may have switched the page", pageSwitch.isTouched()); //$NON-NLS-1$
        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[7]/full", "update/full"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFrameClearFailureIsReportedAndRestored()
    {
        FakeEditor editor = new FakeEditor();
        editor.throwOnRender = 1;

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);

        assertTrue(pageSwitch.getError(), pageSwitch.getError().contains("selection frame could not be cleared")); //$NON-NLS-1$
        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "update/full"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testForeignRebuildBetweenRendersRetriesTheSwitch()
    {
        FakeEditor editor = new FakeEditor();
        boolean[] foreignPending = { true };
        Runnable pump = () -> {
            if (foreignPending[0])
            {
                // EDT's own async rebuild lands while the event loop is pumped after our select render.
                foreignPending[0] = false;
                editor.hippoSession = new Object();
                editor.renders.add("foreign"); //$NON-NLS-1$
            }
        };

        FormPageSwitch pageSwitch = show(editor, pump);

        assertNull(pageSwitch.getError());
        assertEquals(List.of("select[7]/full", "foreign", "select[7]/full", "select[]/updateOnly"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            editor.renders);
    }

    @Test
    public void testPersistentForeignRebuildFailsInsteadOfReturningTheWrongPage()
    {
        FakeEditor editor = new FakeEditor();
        Runnable pump = () -> editor.hippoSession = new Object();

        FormPageSwitch pageSwitch = show(editor, pump);

        assertNotNull(pageSwitch.getError());
        assertTrue(pageSwitch.getError(), pageSwitch.getError().contains("re-rendered on its own")); //$NON-NLS-1$
        assertTrue(pageSwitch.isTouched());
        assertEquals(List.of("select[7]/full", "select[7]/full", "select[7]/full"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testRestoreFailureIsReported()
    {
        FakeEditor editor = new FakeEditor();
        editor.controller.readyRoots = 2; // the switch renders, then the mapping root is gone

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.getError());

        String restoreError = pageSwitch.restore();
        assertNotNull(restoreError);
        assertTrue(restoreError, restoreError.contains("could not be returned to the page it showed before")); //$NON-NLS-1$
        assertTrue(restoreError, restoreError.contains("'Delivery'")); //$NON-NLS-1$
        assertTrue(restoreError, restoreError.contains("refresh=true")); //$NON-NLS-1$
        assertNull("the restore runs once", pageSwitch.restore()); //$NON-NLS-1$
    }

    @Test
    public void testRestoreRunsOnce()
    {
        FakeEditor editor = new FakeEditor();
        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.restore());
        assertNull(pageSwitch.restore());
        assertEquals(3, editor.renders.size());
    }

    @Test
    public void testRenderThatReturnsWithoutRenderingIsNotReady()
    {
        // rebuildInternal returns early (no actual form object yet) and keeps the old session: nothing
        // was rendered, so this is not a switch and not something to restore.
        FakeEarlyReturnEditor early = new FakeEarlyReturnEditor();

        FormPageSwitch pageSwitch = show(early, NO_FOREIGN_REBUILD);

        assertTrue(pageSwitch.getError(), pageSwitch.getError().contains("still loading")); //$NON-NLS-1$
        assertFalse(pageSwitch.isTouched());
        assertTrue("the render was retried until the deadline", early.calls > 1); //$NON-NLS-1$
    }

    // ---- the restore against a modelled page state ----

    @Test
    public void testRestoreReturnsAClickedTabExactly()
    {
        // The user clicked tab 12: EDT selected it (frame) and keeps it as its last domain selection.
        FakeEditor editor = editorWithPages();
        editor.shownPage.put(100, 12);
        editor.frame = new int[] { 12 };
        editor.paint();
        editor.lastDomainSelection = new StructuredSelection(pageItem(12));
        ImageData before = (ImageData)editor.formImageData.clone();

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.getError());
        assertEquals("the switch shows the page holding the element", Integer.valueOf(13), editor.shownPage.get(100)); //$NON-NLS-1$
        assertFalse(FormPageSwitch.sameImage(before, editor.formImageData));

        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[12]/full"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("the editor shows exactly what it showed before", //$NON-NLS-1$
            FormPageSwitch.sameImage(before, editor.formImageData));
    }

    @Test
    public void testRestoreDropsAFrameTheEditorDidNotShow()
    {
        // The form is the last selection, but the buffer came from a plain render: no frame on it.
        FakeEditor editor = editorWithPages();
        editor.lastDomainSelection = new StructuredSelection(FormFactory.eINSTANCE.createForm());
        ImageData before = (ImageData)editor.formImageData.clone();

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.restore());

        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[0]/full", "select[]/updateOnly"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            editor.renders);
        assertTrue(FormPageSwitch.sameImage(before, editor.formImageData));
    }

    @Test
    public void testRestoreFallsBackToTheDefaultPages()
    {
        // Tab 12 was clicked earlier, but EDT re-rendered since: the editor shows the default page.
        FakeEditor editor = editorWithPages();
        editor.lastDomainSelection = new StructuredSelection(pageItem(12));
        ImageData before = (ImageData)editor.formImageData.clone();

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.restore());

        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[12]/full", "select[]/updateOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "update/full"), editor.renders); //$NON-NLS-1$
        assertTrue(FormPageSwitch.sameImage(before, editor.formImageData));
    }

    @Test
    public void testRestoreSettlesOnTheSelectionWhenNothingReproducesTheBuffer()
    {
        // The buffer is older than the model: no render reproduces it.
        FakeEditor editor = editorWithPages();
        editor.formImageData = stateImage("stale"); //$NON-NLS-1$
        editor.lastDomainSelection = new StructuredSelection(pageItem(12));

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.restore());

        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "select[12]/full", "select[]/updateOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "update/full", "select[12]/full"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(12), editor.shownPage.get(100));
    }

    @Test
    public void testASecondPagesGroupReturnsToItsDefaultPage()
    {
        // EDT's limit, pinned: group 200 shows its page 22, but EDT keeps no record of it, and every
        // full render shows a group without a selected element on its default page.
        FakeEditor editor = editorWithPages();
        editor.page(200, 21, true);
        editor.page(200, 22, false);
        editor.shownPage.put(100, 12);
        editor.shownPage.put(200, 22);
        editor.frame = new int[] { 12 };
        editor.paint();
        editor.lastDomainSelection = new StructuredSelection(pageItem(12));

        FormPageSwitch pageSwitch = show(editor, NO_FOREIGN_REBUILD);
        assertNull(pageSwitch.restore());

        assertEquals("the group holding the selection gets its page back", Integer.valueOf(12), //$NON-NLS-1$
            editor.shownPage.get(100));
        assertEquals("the other group is back on its default page", Integer.valueOf(21), editor.shownPage.get(200)); //$NON-NLS-1$
    }

    @Test
    public void testForeignRebuildDuringTheRestoreIsNotOverwritten()
    {
        // EDT re-renders on its own right after the restore render: that render started from the
        // restored pages and is newer than them, so the restore stops instead of repainting.
        FakeEditor editor = editorWithPages();
        editor.formImageData = stateImage("stale"); //$NON-NLS-1$
        boolean[] restoring = { false };
        Runnable pump = () -> {
            if (restoring[0])
            {
                restoring[0] = false;
                editor.hippoSession = new Object();
                editor.renders.add("foreign"); //$NON-NLS-1$
            }
        };
        FormPageSwitch pageSwitch = show(editor, pump);
        assertNull(pageSwitch.getError());

        restoring[0] = true;
        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[7]/full", "select[]/updateOnly", "update/full", "foreign"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testAThrowFromTheEventLoopAfterARenderIsRestored()
    {
        // The render ran, then the pumped event loop threw: the switch must still count as made.
        FakeEditor editor = editorWithPages();
        ImageData before = (ImageData)editor.formImageData.clone();
        boolean[] thrown = { false };
        Runnable pump = () -> {
            if (!thrown[0])
            {
                thrown[0] = true;
                throw new IllegalStateException("async runnable failed"); //$NON-NLS-1$
            }
        };
        FormPageSwitch pageSwitch = FormPageSwitch.prepare(editor, target(), SHORT_TIMEOUT_MS, pump);

        String error = pageSwitch.show();
        assertNotNull(error);
        assertTrue(error, error.contains("threw")); //$NON-NLS-1$
        assertTrue(pageSwitch.isTouched());

        assertNull(pageSwitch.restore());
        assertEquals(List.of("select[7]/full", "update/full"), editor.renders); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(FormPageSwitch.sameImage(before, editor.formImageData));
    }

    @Test
    public void testAThrowDuringTheRestoreIsReportedNotThrown()
    {
        FakeEditor editor = editorWithPages();
        boolean[] restoring = { false };
        Runnable pump = () -> {
            if (restoring[0])
            {
                throw new IllegalStateException("async runnable failed"); //$NON-NLS-1$
            }
        };
        FormPageSwitch pageSwitch = show(editor, pump);
        restoring[0] = true;

        String restoreError = pageSwitch.restore();
        assertNotNull(restoreError);
        assertTrue(restoreError, restoreError.contains("could not be returned to the page it showed before")); //$NON-NLS-1$
        assertTrue(restoreError, restoreError.contains("threw")); //$NON-NLS-1$
    }

    @Test
    public void testSameImageComparesPixels()
    {
        assertTrue(FormPageSwitch.sameImage(stateImage("a"), stateImage("a"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(FormPageSwitch.sameImage(stateImage("abc"), stateImage("abd"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(FormPageSwitch.sameImage(stateImage("a"), null)); //$NON-NLS-1$
        assertFalse(FormPageSwitch.sameImage(null, null));
    }

    /** An editor whose render returns early and never replaces the session. */
    private static final class FakeEarlyReturnEditor
    {
        @SuppressWarnings("unused") // read reflectively
        final Object form = new Object();
        @SuppressWarnings("unused") // read reflectively
        final FakeController controller = new FakeController();
        @SuppressWarnings("unused") // read reflectively
        final Object hippoSession = new Object();
        int calls;

        @SuppressWarnings("unused") // invoked reflectively
        private void rebuildInternal(Object renderedForm, FakeMapping mapping, FakeEvent event, boolean updateOnly)
        {
            calls++;
        }
    }
}
