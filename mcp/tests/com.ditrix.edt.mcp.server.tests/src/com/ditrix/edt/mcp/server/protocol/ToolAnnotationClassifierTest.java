/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;

/**
 * Tests for {@link ToolAnnotationClassifier}.
 */
public class ToolAnnotationClassifierTest
{
    @Test
    public void testDestructiveTool()
    {
        ToolAnnotations a = ToolAnnotationClassifier.classify("delete_metadata_object");
        assertEquals("destructive tool must be destructiveHint=true",
            Boolean.TRUE, a.getDestructiveHint());
        assertEquals("destructive tool must be readOnlyHint=false",
            Boolean.FALSE, a.getReadOnlyHint());
    }

    @Test
    public void testAllDestructiveTools()
    {
        for (String name : new String[] {
            "delete_metadata_object",
            "clean_project",
            "update_database",
            "rename_metadata_object",
            "import_configuration_from_xml",
            "delete_form_item",
            "delete_project" })
        {
            ToolAnnotations a = ToolAnnotationClassifier.classify(name);
            assertEquals(name + " must be destructiveHint=true", Boolean.TRUE, a.getDestructiveHint());
            assertEquals(name + " must be readOnlyHint=false", Boolean.FALSE, a.getReadOnlyHint());
        }
    }

    @Test
    public void testReadOnlyTool()
    {
        ToolAnnotations a = ToolAnnotationClassifier.classify("get_edt_version");
        assertEquals("get_* tool must be readOnlyHint=true", Boolean.TRUE, a.getReadOnlyHint());
        assertEquals("get_* tool must be idempotentHint=true", Boolean.TRUE, a.getIdempotentHint());
        assertNull("read-only tool must not set destructiveHint", a.getDestructiveHint());
    }

    @Test
    public void testReadOnlyPrefixes()
    {
        for (String name : new String[] {
            "get_metadata_objects",
            "list_projects",
            "read_module_source",
            "search_in_code",
            "find_references",
            "validate_query" })
        {
            ToolAnnotations a = ToolAnnotationClassifier.classify(name);
            assertEquals(name + " must be readOnlyHint=true", Boolean.TRUE, a.getReadOnlyHint());
            assertEquals(name + " must be idempotentHint=true", Boolean.TRUE, a.getIdempotentHint());
        }
    }

    @Test
    public void testOtherWriteTool()
    {
        ToolAnnotations a = ToolAnnotationClassifier.classify("write_module_source");
        assertEquals("write tool must be readOnlyHint=false", Boolean.FALSE, a.getReadOnlyHint());
        assertEquals("write tool must be destructiveHint=false", Boolean.FALSE, a.getDestructiveHint());
    }

    @Test
    public void testOpenWorldHintAlwaysFalse()
    {
        assertEquals(Boolean.FALSE,
            ToolAnnotationClassifier.classify("get_edt_version").getOpenWorldHint());
        assertEquals(Boolean.FALSE,
            ToolAnnotationClassifier.classify("write_module_source").getOpenWorldHint());
        assertEquals(Boolean.FALSE,
            ToolAnnotationClassifier.classify("delete_metadata_object").getOpenWorldHint());
    }

    @Test
    public void testNullToolNameIsConservativeWrite()
    {
        ToolAnnotations a = ToolAnnotationClassifier.classify(null);
        assertEquals(Boolean.FALSE, a.getReadOnlyHint());
        assertEquals(Boolean.FALSE, a.getDestructiveHint());
    }
}
