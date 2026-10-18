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

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.FormExcludedCommandsFixture;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;

/** The read surface uses the same reflective classification as modification. */
public class GetMetadataDetailsExcludedCommandsTest
{
    private final FormExcludedCommandsFixture fixture = new FormExcludedCommandsFixture();

    @Test
    public void testAssignableListingIncludesCurrentAndPerSourceAllowedCommands()
    {
        fixture.seed("Copy");
        assertTrue(MetadataPropertyIntrospector.assignableNames(fixture.target).contains("excludedCommands"));
        var property = MetadataPropertyIntrospector.find(fixture.target, "excludedCommands");
        assertNotNull(property);
        assertEquals(java.util.List.of("Add", "Copy", "Delete"), property.allowedValues);
        String markdown = GetMetadataDetailsTool.formatAssignable("Catalog.Goods.Form.ItemForm.Table.Rows",
            fixture.target);
        assertTrue(markdown, markdown.contains("| excludedCommands |"));
        assertTrue(markdown, markdown.contains("[\"Copy\"]"));
        assertTrue(markdown, markdown.contains("Add, Copy, Delete"));
    }

    @Test
    public void testResolvedCommandAndUnresolvedProxyReadTheSameCanonicalName()
    {
        fixture.stored().add(fixture.offered().get(1));
        var resolved = MetadataPropertyIntrospector.find(fixture.target, "excludedCommands");
        assertNotNull(resolved);
        assertEquals("[\"Copy\"]", resolved.currentValue);
        fixture.stored().clear();
        fixture.seed("Copy");
        assertEquals(resolved.currentValue,
            MetadataPropertyIntrospector.find(fixture.target, "excludedCommands").currentValue);
    }

    @Test
    public void testAllowedListingIsBoundedAndInferredCommandsStayReadOnly()
    {
        for (int i = 0; i < 30; i++)
        {
            fixture.offer("Extra" + i, null);
        }
        String markdown = GetMetadataDetailsTool.formatAssignable("Catalog.Goods.Form.ItemForm.Table.Rows",
            fixture.target);
        assertTrue(markdown, markdown.contains("| excludedCommands |"));
        assertTrue(markdown, markdown.contains("8 more"));
        assertFalse(markdown, markdown.contains("Extra29"));
        assertNull(MetadataPropertyIntrospector.findFeature(fixture.target, "commands"));
    }

    @Test
    public void testUncomputedCommandsStillExposeAnEmptyCurrentSet()
    {
        fixture.offered().clear();
        var property = MetadataPropertyIntrospector.find(fixture.target, "excludedCommands");
        assertNotNull(property);
        assertEquals("[]", property.currentValue);
        assertTrue(property.allowedValues.isEmpty());
    }

    @Test
    public void testACommandLookupThatThrowsStillListsThePropertyWithNothingAllowed()
    {
        org.mockito.Mockito.when(fixture.target.eGet(fixture.commands))
            .thenThrow(new IllegalStateException("commands not computed yet"));
        var property = MetadataPropertyIntrospector.find(fixture.target, "excludedCommands");
        assertNotNull(property);
        assertTrue(property.allowedValues.isEmpty());
        String markdown = GetMetadataDetailsTool.formatAssignable("Catalog.Goods.Form.ItemForm.Table.Rows",
            fixture.target);
        assertTrue(markdown, markdown.contains("| excludedCommands |"));
    }

    @Test
    public void testAnotherPackagesLookalikeFeatureStaysUnassignable()
    {
        fixture.formPackage.setNsURI("https://example.invalid/other-model");
        assertNull(MetadataPropertyIntrospector.find(fixture.target, "excludedCommands"));
        assertFalse(MetadataPropertyIntrospector.assignableNames(fixture.target).contains("excludedCommands"));
    }
}
