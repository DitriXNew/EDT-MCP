/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.graphics.ImageData;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.FormPageSwitchTest;

/**
 * Tests for {@link GetFormScreenshotTool}.
 * <p>
 * Covers tool metadata, the IMAGE response type, the input schema, and the
 * "projectName is required when formPath is specified" validation that returns
 * before any {@code Display} access. Capturing the form needs a live workbench
 * with the WYSIWYG editor and is covered by the E2E suite.
 */
public class GetFormScreenshotToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_form_screenshot", new GetFormScreenshotTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetFormScreenshotTool.NAME, new GetFormScreenshotTool().getName());
    }

    @Test
    public void testResponseTypeImage()
    {
        assertEquals(ResponseType.IMAGE, new GetFormScreenshotTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetFormScreenshotTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetFormScreenshotTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"refresh\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"showElement\"")); //$NON-NLS-1$
        // The refresh contract: a forced re-render either happens or the tool errors; it must
        // never silently return a stale image, and the schema documents that.
        assertTrue(schema.contains("re-render")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new GetFormScreenshotTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail moved out of the slimmed description/schema must live in the guide.
        assertTrue(guide.contains("nativeFormBufferedLayoutRender")); //$NON-NLS-1$
        assertTrue(guide.contains("CommonForm")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testFormPathWithoutProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formPath", "Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetFormScreenshotTool().execute(params);
        assertTrue(result.contains("projectName is required when formPath is specified")); //$NON-NLS-1$
    }

    // ==================== showElement: a failed restore is never hidden ====================

    /** Controller stand-in that always has the mapping root ready. */
    private static final class ReadyController
    {
        @SuppressWarnings("unused") // invoked reflectively (MappingController.getMappingRoot(Class))
        Object getMappingRoot(Class<?> mappingClass)
        {
            return new FormPageSwitchTest.FakeMapping();
        }
    }

    /** A WYSIWYG viewer whose control is gone, so the print fallback has no image either. */
    public static final class ViewerWithoutControl
    {
        public Object getControl()
        {
            return null;
        }
    }

    /** The reflectively driven editor surface: renders run until render {@code throwOnRender}, no image. */
    private static final class EditorWithoutImage
    {
        @SuppressWarnings("unused") // read reflectively
        final Form form;
        @SuppressWarnings("unused") // read reflectively
        final ReadyController controller = new ReadyController();
        @SuppressWarnings("unused") // read reflectively
        Object hippoSession = new Object();
        int renders;
        int throwOnRender = Integer.MAX_VALUE;

        EditorWithoutImage(Form form)
        {
            this.form = form;
        }

        @SuppressWarnings("unused") // invoked reflectively (the synchronous render body)
        private void rebuildInternal(Object renderedForm, FormPageSwitchTest.FakeMapping mapping,
            FormPageSwitchTest.FakeEvent event, boolean updateOnly)
        {
            if (renders++ == throwOnRender)
            {
                throw new IllegalStateException("render failed"); //$NON-NLS-1$
            }
            hippoSession = new Object();
        }

        @SuppressWarnings("unused") // invoked reflectively (readFormImageData)
        private ImageData getFormImageData()
        {
            return null;
        }
    }

    @Test
    public void testImageReadFailureCarriesTheRestoreFailure()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup pages = FormFactory.eINSTANCE.createFormGroup();
        pages.setType(ManagedFormGroupType.PAGES);
        FormGroup page = FormFactory.eINSTANCE.createFormGroup();
        page.setType(ManagedFormGroupType.PAGE);
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("Target"); //$NON-NLS-1$
        field.setId(7);
        page.getItems().add(field);
        pages.getItems().add(page);
        form.getItems().add(pages);
        EditorWithoutImage editor = new EditorWithoutImage(form);
        editor.throwOnRender = 2; // the switch's select and frame-clear render, then the restore throws
        EditorScreenshotHelper.ShowElementTarget target = EditorScreenshotHelper.resolveShowElement(editor, "Target"); //$NON-NLS-1$
        assertNull(target.getError());

        CaptureResult result = GetFormScreenshotTool.captureShowingElement(editor, new ViewerWithoutControl(), true,
            null, target);

        assertFalse(result.isSuccess());
        assertTrue(result.getError(), result.getError().contains("Form image data is not available")); //$NON-NLS-1$
        assertTrue("the caller must learn the editor was left switched: " + result.getError(), //$NON-NLS-1$
            result.getError().contains("could not be returned to the page it showed before")); //$NON-NLS-1$
        assertEquals(3, editor.renders);
    }
}
