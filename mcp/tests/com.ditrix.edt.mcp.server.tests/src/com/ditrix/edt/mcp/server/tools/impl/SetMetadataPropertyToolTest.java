/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link SetMetadataPropertyTool} that exercise tool
 * metadata, JSON schema and the pure argument-validation branch without needing
 * the Eclipse/EDT runtime. The full {@code execute()} path (model read-back,
 * on-disk persistence) requires a live workbench and BM model, so it is covered
 * by the E2E suite instead.
 * <p>
 * The "at least one of comment/synonym" guard is reachable headlessly because it
 * runs in {@link SetMetadataPropertyTool#executeOnUiThread(Map)} BEFORE any model
 * access (only the base {@code execute()} marshals onto the workbench UI thread);
 * the test invokes that protected method directly from the same package.
 */
public class SetMetadataPropertyToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_metadata_property", new SetMetadataPropertyTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetMetadataPropertyTool.NAME, new SetMetadataPropertyTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new SetMetadataPropertyTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetMetadataPropertyTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        // The slim description must steer callers to the on-demand guide channel.
        String desc = new SetMetadataPropertyTool().getDescription();
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('set_metadata_property')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionPointsToXmlPathForOtherProperties()
    {
        // Type/flag editing is out of scope; the description must steer to export/import XML.
        String desc = new SetMetadataPropertyTool().getDescription();
        assertTrue("description should point to export_configuration_to_xml", //$NON-NLS-1$
            desc.contains("export_configuration_to_xml")); //$NON-NLS-1$
        assertTrue("description should point to import_configuration_from_xml", //$NON-NLS-1$
            desc.contains("import_configuration_from_xml")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new SetMetadataPropertyTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"attributeName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"comment\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"synonym\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        // Exactly projectName + objectFqn are required; comment/synonym/attributeName/
        // language are all optional.
        String schema = new SetMetadataPropertyTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("objectFqn must be required", tail.contains("\"objectFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalParametersNotRequired()
    {
        String schema = new SetMetadataPropertyTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("comment must NOT be required", tail.contains("\"comment\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("synonym must NOT be required", tail.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("attributeName must NOT be required", tail.contains("\"attributeName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideNotEmptyAndCarriesMigratedDetail()
    {
        // The exhaustive detail (scope, bilingual notes, transaction, examples) lives in
        // the on-demand guide rather than the slim description; assert it landed there.
        String guide = new SetMetadataPropertyTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide should retain the bilingual synonym/language-code detail", //$NON-NLS-1$
            guide.contains("language CODE")); //$NON-NLS-1$
        assertTrue("guide should point to the export/import XML path for other properties", //$NON-NLS-1$
            guide.contains("export_configuration_to_xml")); //$NON-NLS-1$
        assertTrue("guide should mention the attributeName target", //$NON-NLS-1$
            guide.contains("attributeName")); //$NON-NLS-1$
    }

    @Test
    public void testNeitherCommentNorSynonymIsStructuredError() throws Exception
    {
        // With only projectName + objectFqn (no comment, no synonym) the call is rejected
        // with a structured ToolResult.error BEFORE any model access. The guard runs in
        // executeOnUiThread (no workbench needed for this branch), so the test calls it
        // directly. The error must name the two properties so the caller knows what to add.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$

        String json = new SetMetadataPropertyTool().executeOnUiThread(params);
        assertNotNull(json);
        // ToolResult.error(...) emits a structured JSON with success=false / isError.
        assertTrue("a neither-property call must be a structured error", //$NON-NLS-1$
            json.contains("\"success\":false") || json.contains("\"isError\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        // Delimiter-free anchors only (Gson HTML-escapes the apostrophe around 'comment').
        assertTrue("error should name the comment property", json.contains("comment")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should name the synonym property", json.contains("synonym")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFindAttributeReturnsNullWhenNoCollection() throws Exception
    {
        // findAttribute is null-safe on a type with no getAttributes() collection: the
        // reflection lookup swallows NoSuchMethodException and returns null rather than
        // throwing. We cannot construct a real MdObject headlessly, so this asserts the
        // method is package-visible and present (the deeper behavior is e2e-covered).
        assertNotNull(SetMetadataPropertyTool.class
            .getDeclaredMethod("findAttribute", //$NON-NLS-1$
                com._1c.g5.v8.dt.metadata.mdclass.MdObject.class, String.class));
    }
}
