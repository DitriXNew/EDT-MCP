/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.ditrix.edt.mcp.server.utils.MetadataAutoSort;

public class CreateMetadataInsertPositionTest
{
    @Test
    public void governedTopAndMemberPositionsAreRefusedWithoutCreatingAnything()
    {
        var config = MdClassFactory.eINSTANCE.createConfiguration();
        var owner = MdClassFactory.eINSTANCE.createCatalog();
        owner.setName("X");
        config.getCatalogs().add(owner);
        for (String fqn : List.of("Catalog.New", "Catalog.X.Attribute.New"))
        {
            var error = assertThrows(IllegalArgumentException.class, () -> CreateMetadataTool.validateInsertDestination(
                config, fqn, 1, ref -> new MetadataAutoSort.Policy(true, true, true, "topObjects")));
            assertTrue(error.getMessage(), error.getMessage().contains("revert"));
            assertTrue(error.getMessage(), error.getMessage().contains("ask the user"));
            assertEquals(List.of(owner), config.getCatalogs());
            assertTrue(owner.getAttributes().isEmpty());
        }
    }

    @Test
    public void defaultCreationAddsTheSameNoticeAndUserActionToTheSuccessMessage()
    {
        var policy = new MetadataAutoSort.Policy(true, true, false, "topObjects");
        String original = "{\"success\":true,\"fqn\":\"Catalog.New\",\"message\":\"Created Catalog.New.\"}";
        var tool = new CreateMetadataTool()
        {
            @Override
            protected String executeCreate(java.util.Map<String, String> params)
            {
                assertFalse(params.containsKey("insertPosition"));
                return original;
            }

            @Override
            protected MetadataAutoSort.Notice createdAutosortNotice(String project, String fqn)
            {
                assertEquals("P", project);
                return policy.notice("Configuration.catalogs", MetadataAutoSort.CREATED);
            }
        };
        var result = com.google.gson.JsonParser.parseString(tool.executeOnUiThread(java.util.Map.of("projectName", "P"))).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("ascending, plain", result.getAsJsonObject("autosort").get("order").getAsString());
        assertTrue(result.get("message").getAsString().contains("alphabetical position"));
        assertTrue(result.get("message").getAsString().contains("ask the user"));
        assertFalse(result.toString(), result.toString().contains(".prefs"));
    }

    @Test
    public void absentOrOffPolicyPreservesDefaultSuccessByteForByte()
    {
        String original = "{ \"success\": true, \"fqn\": \"Catalog.New\" }";
        var tool = new CreateMetadataTool()
        {
            @Override
            protected String executeCreate(java.util.Map<String, String> params)
            {
                return original;
            }

            @Override
            protected MetadataAutoSort.Notice createdAutosortNotice(String project, String fqn)
            {
                return MetadataAutoSort.disabled().forCollection(null).notice("Configuration.catalogs", MetadataAutoSort.CREATED);
            }
        };
        assertEquals(original, tool.executeOnUiThread(java.util.Map.of("projectName", "P")));
    }

    @Test
    public void creationNoticeUsesActualTopOrMemberCollectionAndRefusalKeepsTheBlock()
    {
        var factory = MdClassFactory.eINSTANCE;
        var config = factory.createConfiguration();
        var catalog = factory.createCatalog();
        catalog.setName("X");
        config.getCatalogs().add(catalog);
        var attribute = factory.createCatalogAttribute();
        attribute.setName("A");
        catalog.getAttributes().add(attribute);
        MetadataAutoSort.Settings settings = ref -> new MetadataAutoSort.Policy(true, false, true, "subordinateObjects");
        assertEquals("Configuration.catalogs", CreateMetadataTool.creationNotice(config, "Catalog.X", settings).governs());
        assertEquals("Catalog.X.attributes", CreateMetadataTool.creationNotice(config, "Catalog.X.Attribute.A", settings).governs());
        assertNull(CreateMetadataTool.creationNotice(config, "Catalog.X", MetadataAutoSort.disabled()));
        var error = assertThrows(MetadataAutoSort.ManualOrderRefusal.class,
            () -> CreateMetadataTool.validateInsertDestination(config, "Catalog.New", 1, settings));
        var result = com.google.gson.JsonParser.parseString(MetadataAutoSort.refusalJson(error)).getAsJsonObject();
        assertEquals("Configuration.catalogs", result.getAsJsonObject("autosort").get("governs").getAsString());
        assertTrue(result.get("error").getAsString().contains("move the object anyway"));
        assertTrue(result.get("message").getAsString().contains("All subordinate objects"));
    }

    @Test
    public void creationNoticeCoversNestedSubsystemListsAndIgnoresSeparateContentModels()
    {
        var factory = MdClassFactory.eINSTANCE;
        var config = factory.createConfiguration();
        var parent = factory.createSubsystem();
        parent.setName("Parent");
        var child = factory.createSubsystem();
        child.setName("Child");
        config.getSubsystems().add(parent);
        parent.getSubsystems().add(child);
        child.setParentSubsystem(parent);
        var notice = CreateMetadataTool.creationNotice(config, "Subsystem.Parent.Subsystem.Child",
            ref -> new MetadataAutoSort.Policy(true, true, true, "topObjects"));
        assertEquals("Subsystem.Parent.subsystems", notice.governs());
        assertNull(CreateMetadataTool.creationNotice(config, "Subsystem.Parent.Form.F.Item.X", ref -> {
            fail("Separate content models must not query metadata collection policy");
            return null;
        }));
    }

    @Test
    public void absentPluginOrUngovernedMemberAllowsInsertionPreflight()
    {
        var config = MdClassFactory.eINSTANCE.createConfiguration();
        var owner = MdClassFactory.eINSTANCE.createCatalog();
        owner.setName("X");
        config.getCatalogs().add(owner);
        CreateMetadataTool.validateInsertDestination(config, "Catalog.New", 2, MetadataAutoSort.disabled());
        CreateMetadataTool.validateInsertDestination(config, "Catalog.X.Attribute.New", 1,
            ref -> new MetadataAutoSort.Policy(ref == MdClassPackage.Literals.CONFIGURATION__CATALOGS,
                true, true, "topObjects"));
        var error = assertThrows(IllegalArgumentException.class, () -> CreateMetadataTool.validateInsertDestination(
            config, "Catalog.New", 0, MetadataAutoSort.disabled()));
        assertTrue(error.getMessage(), error.getMessage().contains("outside 1..2"));
    }
}
