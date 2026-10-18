/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com.ditrix.edt.mcp.server.utils.FormExcludedCommandsFixture;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exercises the shared form-property preparation and application, without a running EDT. */
public class ModifyMetadataToolExcludedCommandsTest
{
    private final FormExcludedCommandsFixture fixture = new FormExcludedCommandsFixture();

    @Test
    public void testEnglishNamesReplaceTheWholeSetWithNameKeyedProxies()
    {
        fixture.seed("Add");
        var changes = prepare("[\"copy\",\"Delete\"]");
        assertEquals("preparation must not write", List.of("Add"), fixture.storedNames());
        changes.get(0).change().applyTo(fixture.target, null);
        assertEquals(List.of("Copy", "Delete"), fixture.storedNames());
        for (var entry : fixture.stored())
        {
            assertTrue("never store a detached command implementation", entry.eIsProxy());
        }
        URI uri = ((InternalEObject)fixture.stored().get(0)).eProxyURI();
        assertEquals("Catalog.Goods.Form.ItemForm", BmUriUtil.extractTopObjectFqn(uri));
        assertEquals("/items:Rows/commands:Copy", BmUriUtil.extractContainmentPath(uri));
    }

    @Test
    public void testRussianNameUsesTheModelsAliasAndEchoesCanonicalEnglish()
    {
        String russian = new String(new int[] {
            0x0421, 0x043a, 0x043e, 0x043f, 0x0438, 0x0440, 0x043e, 0x0432, 0x0430, 0x0442, 0x044c
        }, 0, 11);
        JsonArray names = new JsonArray();
        names.add(russian);
        prepare(names.toString()).get(0).change().applyTo(fixture.target, null);
        assertEquals(List.of("Copy"), fixture.storedNames());
        assertEquals("[\"Copy\"]", MetadataPropertyIntrospector.find(fixture.target, "excludedCommands")
            .currentValue);
        // A different alias proves resolution reads the supplied model, not a translation table.
        fixture.offer("ModelOnly", "PlatformAlias");
        prepare("[\"PlatformAlias\"]").get(0).change().applyTo(fixture.target, null);
        assertEquals(List.of("ModelOnly"), fixture.storedNames());
    }

    @Test
    public void testEmptyArrayClearsTheWholeSet()
    {
        fixture.seed("Add");
        fixture.seed("Copy");
        prepare("[]").get(0).change().applyTo(fixture.target, null);
        assertTrue(fixture.stored().isEmpty());
        assertEquals("[]", MetadataPropertyIntrospector.find(fixture.target, "excludedCommands").currentValue);
    }

    @Test
    public void testUnknownNameRefusesWholeBatchAndListsAllowedNames()
    {
        fixture.seed("Add");
        JsonObject visible = property("false");
        visible.addProperty("name", "visible");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        String error = verdict(List.of(visible, property("[\"Copy\",\"NoSuchCommand\"]")), changes);
        assertNotNull(error);
        assertTrue(error, error.contains("NoSuchCommand"));
        assertTrue(error, error.contains("Allowed: Add, Copy, Delete"));
        assertTrue(error, error.contains("get_metadata_details"));
        assertTrue(changes.isEmpty());
        assertEquals(List.of("Add"), fixture.storedNames());
    }

    @Test
    public void testDuplicatesIncludingBilingualAliasesAreRefused()
    {
        fixture.offer("ModelOnly", "PlatformAlias");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        String error = verdict(List.of(property("[\"ModelOnly\",\"PlatformAlias\"]")), changes);
        assertNotNull(error);
        assertTrue(error, error.contains("ModelOnly"));
        assertTrue(error, error.contains("once"));
        assertTrue(changes.isEmpty());
    }

    @Test
    public void testUncomputedCommandsRefuseBothNonemptyAndEmptyInputWithRetry()
    {
        fixture.seed("Add");
        fixture.offered().clear();
        for (String value : List.of("[\"Copy\"]", "[]"))
        {
            String error = verdict(List.of(property(value)), new ArrayList<>());
            assertNotNull(error);
            assertTrue(error, error.contains("not computed"));
            assertTrue(error, error.contains("retry"));
            assertEquals(List.of("Add"), fixture.storedNames());
        }
    }

    @Test
    public void testInvalidJsonShapesAreRefusedWithoutWriting()
    {
        for (String value : List.of("\"Copy\"", "null", "false", "[1]", "[null]", "[{}]"))
        {
            String error = verdict(List.of(property(value)), new ArrayList<>());
            assertNotNull(value, error);
            assertTrue(error, error.contains("JSON array"));
            assertTrue(error, error.contains("excludedCommands"));
            assertTrue(fixture.stored().isEmpty());
        }
    }

    @Test
    public void testApplyRevalidatesIfCommandsDisappearBeforeTheWrite()
    {
        fixture.seed("Add");
        var changes = prepare("[\"Copy\"]");
        fixture.offered().clear();
        try
        {
            changes.get(0).change().applyTo(fixture.target, null);
            fail("uncomputed commands must never permit an unvalidated reference");
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("retry"));
        }
        assertEquals(List.of("Add"), fixture.storedNames());
    }

    @Test
    public void testAFailedRevalidationIsAMarkedRefusalNotAnInternalError()
    {
        fixture.seed("Add");
        var changes = prepare("[\"Copy\"]");
        fixture.offered().remove(1);
        try
        {
            changes.get(0).change().applyTo(fixture.target, null);
            fail("a stale validated name must be refused on the write target");
        }
        catch (IllegalStateException e)
        {
            assertTrue("logged as a refusal, not an ERROR with a stack",
                e instanceof com.ditrix.edt.mcp.server.utils.Refusals.Marker);
        }
        assertEquals(List.of("Add"), fixture.storedNames());
    }

    @Test
    public void testApplyRefusesACommandNoLongerOfferedBeforeChangingTheList()
    {
        fixture.seed("Add");
        var changes = prepare("[\"Copy\"]");
        fixture.offered().remove(1);
        try
        {
            changes.get(0).change().applyTo(fixture.target, null);
            fail("a stale validated name must be checked on the write target");
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("Copy"));
        }
        assertEquals(List.of("Add"), fixture.storedNames());
    }

    @Test
    public void testUnknownNameAllowedListIsBounded()
    {
        for (int i = 0; i < 30; i++)
        {
            fixture.offer("Extra" + i, "Alias" + i);
        }
        String error = verdict(List.of(property("[\"Unknown\"]")), new ArrayList<>());
        assertNotNull(error);
        assertTrue(error, error.contains("Allowed: Add, Copy, Delete"));
        assertTrue(error, error.contains("8 more"));
        org.junit.Assert.assertFalse(error, error.contains("Extra29"));
    }

    @Test
    public void testNestedSourcePathIsPreservedInProxyAddresses()
    {
        when(fixture.target.bmGetUri()).thenReturn(BmUriUtil.createContainedBmObjectUri(
            "test", "Catalog.Goods.Form.ItemForm", "/items:Group/items:Rows"));
        prepare("[\"Copy\"]").get(0).change().applyTo(fixture.target, null);
        URI uri = ((InternalEObject)fixture.stored().get(0)).eProxyURI();
        assertEquals("/items:Group/items:Rows/commands:Copy", BmUriUtil.extractContainmentPath(uri));
    }

    @Test
    public void testFormRootAndFieldUseTheirOwnInheritedCommandSet()
    {
        fixture.table.setName("Form");
        when(fixture.target.bmGetUri()).thenReturn(BmUriUtil.createTopBmObjectUri(
            "test", "Catalog.Goods.Form.ItemForm"));
        prepare("[\"Copy\"]").get(0).change().applyTo(fixture.target, null);
        URI uri = ((InternalEObject)fixture.stored().get(0)).eProxyURI();
        assertEquals("/commands:Copy", BmUriUtil.extractContainmentPath(uri));
        fixture.table.setName("FormField");
        fixture.offer("FieldOnly", "FieldAlias");
        prepare("[\"FieldAlias\"]").get(0).change().applyTo(fixture.target, null);
        assertEquals(List.of("FieldOnly"), fixture.storedNames());
    }

    @Test
    public void testSourceChangesNeedASeparateCallBeforeCommandValidation()
    {
        for (String name : List.of("dataPath", "type", "changeRowSet", "choiceMode", "selectionMode",
            "searchControlLocation", "searchStringLocation", "viewStatusLocation", "allowFormCustomize"))
        {
            JsonObject sourceChange = property("\"AnotherSource\"");
            sourceChange.addProperty("name", name);
            List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
            String error = verdict(List.of(property("[\"Copy\"]"), sourceChange), changes);
            assertNotNull(error);
            JsonObject parsed = JsonParser.parseString(error).getAsJsonObject();
            assertTrue(error, !parsed.get("success").getAsBoolean() && parsed.has("error"));
            assertTrue(error, error.contains("separate modify_metadata calls"));
            assertTrue(error, error.contains(name));
            assertTrue(changes.isEmpty());
        }
    }

    private List<ModifyMetadataTool.HolderChange> prepare(String value)
    {
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        assertNull(verdict(List.of(property(value)), changes));
        assertEquals(1, changes.size());
        return changes;
    }

    private String verdict(List<JsonObject> properties, List<ModifyMetadataTool.HolderChange> changes)
    {
        return new ModifyMetadataTool().formRetypeVerdict(null, null, fixture.target, properties,
            new MdNameNormalizer.Report(true), changes);
    }

    private static JsonObject property(String value)
    {
        JsonObject property = new JsonObject();
        property.addProperty("name", "excludedCommands");
        property.add("value", JsonParser.parseString(value));
        return property;
    }
}
