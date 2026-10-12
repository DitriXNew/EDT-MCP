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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.form.FormLayoutSnapshotService;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;

/**
 * Tests for {@link GetFormLayoutSnapshotTool}.
 * <p>
 * Covers tool metadata, the TEXT response type, the input schema, and the
 * "projectName is required when formPath is specified" validation that returns
 * before any {@code Display} access. Capturing the WYSIWYG layout needs a live
 * workbench and is covered by the E2E suite.
 */
public class GetFormLayoutSnapshotToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_form_layout_snapshot", new GetFormLayoutSnapshotTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetFormLayoutSnapshotTool.NAME, new GetFormLayoutSnapshotTool().getName());
    }

    @Test
    public void testResponseTypeText()
    {
        assertEquals(ResponseType.TEXT, new GetFormLayoutSnapshotTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetFormLayoutSnapshotTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetFormLayoutSnapshotTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"mode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"showElement\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail moved out of getDescription()/getInputSchema() into
        // getGuide(); assert it is non-empty and still carries the migrated keywords.
        String guide = new GetFormLayoutSnapshotTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("nativeFormBufferedLayoutRender")); //$NON-NLS-1$
        assertTrue(guide.contains("nativeFormLayoutRender")); //$NON-NLS-1$
        assertTrue(guide.contains("get_server_status")); //$NON-NLS-1$
        assertTrue(guide.contains("compact")); //$NON-NLS-1$
        assertTrue(guide.contains("full")); //$NON-NLS-1$
    }

    // ==================== No-bounds diagnosis (pure/headless) ====================

    @Test
    public void testNoBoundsWarningForNativeRenderExplainsStructuralLimitation()
    {
        String warning = FormLayoutSnapshotService.buildNoBoundsWarning(0, NativeRenderMode.ON);

        assertTrue(warning.contains("does not produce Java-side per-element bounds")); //$NON-NLS-1$
        assertTrue(warning.contains("C++ visualizer")); //$NON-NLS-1$
        assertTrue(warning.contains("structural rather than transient")); //$NON-NLS-1$
        assertTrue(warning.contains("will not help")); //$NON-NLS-1$
        assertTrue(warning.contains("relaunching EDT with -DnativeFormLayoutRender=false")); //$NON-NLS-1$
        assertTrue(warning.contains("get_form_screenshot's image path uses")); //$NON-NLS-1$
        assertTrue(warning.contains("get_metadata_details")); //$NON-NLS-1$
        assertFalse("native-render diagnosis must not tell the caller to retry", //$NON-NLS-1$
            warning.contains("Retry the call")); //$NON-NLS-1$
    }

    @Test
    public void testNoBoundsWarningForJavaRenderSuggestsRetry()
    {
        String warning = FormLayoutSnapshotService.buildNoBoundsWarning(0, NativeRenderMode.OFF);

        assertTrue(warning.contains("Native render mode is off")); //$NON-NLS-1$
        assertTrue(warning.contains("may not have finished rendering")); //$NON-NLS-1$
        assertTrue(warning.contains("Retry the call")); //$NON-NLS-1$
        assertTrue(warning.contains("refresh is true")); //$NON-NLS-1$
        assertFalse(warning.contains("will not help")); //$NON-NLS-1$
    }

    @Test
    public void testNoBoundsWarningForUnknownRenderModeAssertsNeitherCause()
    {
        String warning = FormLayoutSnapshotService.buildNoBoundsWarning(0, NativeRenderMode.UNKNOWN);

        assertTrue(warning.contains("effective native render mode could not be read")); //$NON-NLS-1$
        assertTrue(warning.contains("when native render mode is on")); //$NON-NLS-1$
        assertTrue(warning.contains("when native render mode is off")); //$NON-NLS-1$
        assertTrue(warning.contains("get_metadata_details with the form FQN")); //$NON-NLS-1$
        assertTrue(warning.contains("regardless of render mode")); //$NON-NLS-1$
        assertTrue(warning.contains("native render by default")); //$NON-NLS-1$
        assertTrue(warning.contains("-DnativeFormLayoutRender is not set explicitly")); //$NON-NLS-1$
        assertFalse("unknown diagnosis must not use get_server_status as an effective-mode oracle", //$NON-NLS-1$
            warning.contains("get_server_status")); //$NON-NLS-1$
        assertFalse("unknown diagnosis must not assert that a retry will work", //$NON-NLS-1$
            warning.contains("Retry the call")); //$NON-NLS-1$
        assertFalse("unknown diagnosis must not assert that retries cannot work", //$NON-NLS-1$
            warning.contains("will not help")); //$NON-NLS-1$
    }

    @Test
    public void testNoBoundsWarningOmittedWhenBoundsExist()
    {
        assertNull(FormLayoutSnapshotService.buildNoBoundsWarning(1, NativeRenderMode.ON));
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testFormPathWithoutProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formPath", "Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetFormLayoutSnapshotTool().execute(params);
        assertTrue(result.contains("projectName is required when formPath is specified")); //$NON-NLS-1$
    }

    // ==================== showElement (pure/headless) ====================

    /** A representation whose {@code form} field holds a real form model. */
    private static final class FakeFormRepresentation
    {
        @SuppressWarnings("unused") // read reflectively (EditorScreenshotHelper.getRepresentationForm)
        final Form form;

        FakeFormRepresentation(Form form)
        {
            this.form = form;
        }
    }

    private static FakeFormRepresentation formWithPages()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup pages = FormFactory.eINSTANCE.createFormGroup();
        pages.setName("Pages"); //$NON-NLS-1$
        pages.setId(1);
        pages.setType(ManagedFormGroupType.PAGES);
        String[] names = { "PageMain", "СтраницаИтоги" }; //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < names.length; i++)
        {
            FormGroup page = FormFactory.eINSTANCE.createFormGroup();
            page.setName(names[i]);
            page.setId(10 + i);
            page.setType(ManagedFormGroupType.PAGE);
            pages.getItems().add(page);
        }
        form.getItems().add(pages);
        return new FakeFormRepresentation(form);
    }

    @Test
    public void testShowElementUnknownNameIsNotFoundInEveryRenderMode()
    {
        for (NativeRenderMode mode : NativeRenderMode.values())
        {
            String error = FormLayoutSnapshotService.checkShowElement(formWithPages(), "NoSuchPage", mode); //$NON-NLS-1$
            assertNotNull(mode.toString(), error);
            assertTrue(error, error.contains("'NoSuchPage' was not found")); //$NON-NLS-1$
            assertTrue(error, error.contains("Its pages: PageMain")); //$NON-NLS-1$
        }
    }

    @Test
    public void testShowElementRefusedInNativeRenderOnly()
    {
        String russianPage = "страницаитоги"; //$NON-NLS-1$
        String refusal = FormLayoutSnapshotService.checkShowElement(formWithPages(), russianPage, NativeRenderMode.ON);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("no per-element bounds")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("get_form_screenshot with showElement")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("-DnativeFormLayoutRender=false")); //$NON-NLS-1$

        assertNull(FormLayoutSnapshotService.checkShowElement(formWithPages(), russianPage, NativeRenderMode.OFF));
        assertNull(FormLayoutSnapshotService.checkShowElement(formWithPages(), "pagemain", NativeRenderMode.UNKNOWN)); //$NON-NLS-1$
    }

    private static Map<String, Object> element(String name, int width, int height, Map<String, Object> child)
    {
        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("left", 0); //$NON-NLS-1$
        bounds.put("top", 0); //$NON-NLS-1$
        bounds.put("width", width); //$NON-NLS-1$
        bounds.put("height", height); //$NON-NLS-1$
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("name", name); //$NON-NLS-1$
        element.put("bounds", bounds); //$NON-NLS-1$
        if (child != null)
        {
            element.put("children", List.of(child)); //$NON-NLS-1$
        }
        return element;
    }

    @Test
    public void testFindElementWithBoundsSearchesNestedCaseInsensitively()
    {
        Map<String, Object> label = element("ExtraPageLabel", 120, 18, null); //$NON-NLS-1$
        List<Map<String, Object>> tree = List.of(element("Pages", 300, 200, //$NON-NLS-1$
            element("PageExtra", 300, 180, label))); //$NON-NLS-1$

        assertSame(label, FormLayoutSnapshotService.findElementWithBounds(tree, "extrapagelabel")); //$NON-NLS-1$
        assertNull(FormLayoutSnapshotService.findElementWithBounds(tree, "Missing")); //$NON-NLS-1$
    }

    @Test
    public void testFindElementWithBoundsSkipsZeroBounds()
    {
        List<Map<String, Object>> tree = List.of(element("Hidden", 0, 18, null)); //$NON-NLS-1$
        assertNull(FormLayoutSnapshotService.findElementWithBounds(tree, "Hidden")); //$NON-NLS-1$
        Map<String, Object> noBounds = new LinkedHashMap<>();
        noBounds.put("name", "Bare"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormLayoutSnapshotService.findElementWithBounds(List.of(noBounds), "Bare")); //$NON-NLS-1$
    }

    @Test
    public void testShownElementEntryIsDumpedWithoutYamlAliases()
    {
        Map<String, Object> label = element("ExtraPageLabel", 120, 18, null); //$NON-NLS-1$
        Map<String, Object> entry = FormLayoutSnapshotService.shownElementEntry(label);
        assertEquals("ExtraPageLabel", entry.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(label.get("bounds"), entry.get("bounds")); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shownElement", entry); //$NON-NLS-1$
        result.put("elements", List.of(label)); //$NON-NLS-1$
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml = new Yaml(options).dump(result);
        assertFalse("the bounds must be written out twice, not as an anchor and an alias:\n" + yaml, //$NON-NLS-1$
            yaml.contains("&id") || yaml.contains("*id")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoBoundsForShownElementNamesTheCausePerMode()
    {
        String java = FormLayoutSnapshotService.noBoundsForShownElement("PageExtra", NativeRenderMode.OFF); //$NON-NLS-1$
        assertTrue(java, java.contains("'PageExtra' exists in the form but has no calculated bounds")); //$NON-NLS-1$
        assertTrue(java, java.contains("refresh: true")); //$NON-NLS-1$
        assertFalse(java, java.contains("nativeFormLayoutRender")); //$NON-NLS-1$

        String unknown = FormLayoutSnapshotService.noBoundsForShownElement("PageExtra", NativeRenderMode.UNKNOWN); //$NON-NLS-1$
        assertTrue(unknown, unknown.contains("-DnativeFormLayoutRender=false")); //$NON-NLS-1$
        assertTrue(unknown, unknown.contains("refresh: true")); //$NON-NLS-1$
    }
}
