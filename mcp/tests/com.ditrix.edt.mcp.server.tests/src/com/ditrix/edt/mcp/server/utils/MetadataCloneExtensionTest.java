/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Injector;

/** Issue #933 PR-2: cloning an extension's own objects inside that extension. */
public class MetadataCloneExtensionTest
{
    @Test
    public void adoptedSourceIsRefusedAsAFinalKindRefusal()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog adopted = catalog(config, "Goods");
        adopted.setObjectBelonging(ObjectBelonging.ADOPTED);
        try
        {
            MetadataClonePlan.source(config, "Catalog.Goods");
            fail("An adopted base object is not an own object of the extension and must not be cloned there");
        }
        catch (MetadataClonePlan.Refusal expected)
        {
            String message = expected.getMessage();
            assertTrue(message, message.contains("Catalog.Goods"));
            assertTrue(message, message.contains("objectBelonging=Adopted"));
            assertTrue(message, message.contains("base configuration"));
            assertTrue(message, message.contains("adopt_metadata_object"));
        }
        // The Russian TYPE token resolves the same adopted object and is refused the same way.
        try
        {
            MetadataClonePlan.source(config, "Справочник.Goods");
            fail("The Russian type token must reach the same refusal");
        }
        catch (MetadataClonePlan.Refusal expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Catalog.Goods"));
        }
    }

    @Test
    public void ownObjectOfTheExtensionResolves()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog own = catalog(config, "ext_Goods");
        assertEquals(ObjectBelonging.NATIVE, own.getObjectBelonging());
        assertSame(own, MetadataClonePlan.source(config, "Catalog.ext_Goods"));
    }

    @Test
    public void prefixWarningFollowsEdtsCaseSensitiveStartsWith()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog source = catalog(config, "ext_Goods");
        config.setNamePrefix("ext_");
        assertNull(MetadataClonePlan.prefixWarning(config, source, "ext_GoodsCopy"));
        assertNull("Without newName EDT's free name keeps the source name's start",
            MetadataClonePlan.prefixWarning(config, source, null));
        String missing = MetadataClonePlan.prefixWarning(config, source, "GoodsCopy");
        assertNotNull(missing);
        assertTrue(missing, missing.contains("'GoodsCopy'"));
        assertTrue(missing, missing.contains("namePrefix 'ext_'"));
        assertTrue(missing, missing.contains("extension-md-object-prefix"));
        assertNotNull("EDT compares the prefix case-sensitively",
            MetadataClonePlan.prefixWarning(config, source, "EXT_GoodsCopy"));
        source.setName("Goods");
        String automatic = MetadataClonePlan.prefixWarning(config, source, null);
        assertNotNull(automatic);
        assertTrue(automatic, automatic.contains("automatic"));
        assertTrue(automatic, automatic.contains("'Goods'"));
        config.setNamePrefix("");
        assertNull("An extension without a prefix is never flagged", MetadataClonePlan.prefixWarning(config, source,
            "GoodsCopy"));
    }

    @Test
    public void previewInAnExtensionCarriesThePrefixWarningAndAConfigurationPreviewDoesNot() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        fixture.config.setNamePrefix("ext_");
        MetadataClonePlan extension = MetadataClonePlan.capture(fixture.project, fixture.model, fixture.config,
            fixture.source, "Copy", List.of(), fixture.fs, false, true);
        assertEquals(1, extension.kindWarnings.size());
        assertTrue(extension.kindWarnings.get(0), extension.kindWarnings.get(0).contains("namePrefix 'ext_'"));
        MetadataClonePlan configuration = MetadataClonePlan.capture(fixture.project, fixture.model, fixture.config,
            fixture.source, "Copy", List.of(), fixture.fs, false, false);
        assertEquals("Only an extension project is checked, as in EDT", List.of(), configuration.kindWarnings);
        assertEquals(List.of(), fixture.preview().kindWarnings);
    }

    /** Review #943: the project kind changes the copier's semantics, so a confirm across a change must drift. */
    @Test
    public void fingerprintBindsTheDestinationProjectKind() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        MetadataClonePlan extension = MetadataClonePlan.capture(fixture.project, fixture.model, fixture.config,
            fixture.source, "Copy", List.of(), fixture.fs, false, true);
        MetadataClonePlan configuration = MetadataClonePlan.capture(fixture.project, fixture.model, fixture.config,
            fixture.source, "Copy", List.of(), fixture.fs, false, false);
        assertNotEquals("Identical inputs in an extension and a configuration project must not share a hash",
            configuration.fingerprint, extension.fingerprint);
        assertFalse(com.ditrix.edt.mcp.server.utils.ContentHash.matches(extension.fingerprint, configuration.hash()));
        assertEquals("The 8-argument capture is the configuration-project capture", configuration.fingerprint,
            fixture.preview().fingerprint);
        assertEquals(extension.fingerprint, MetadataClonePlan.capture(fixture.project, fixture.model, fixture.config,
            fixture.source, "Copy", List.of(), fixture.fs, false, true).fingerprint);
    }

    @Test
    public void adoptedSubsystemAndRoleOwnersAreReportedOnly()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog source = catalog(config, "ext_Goods");
        subsystem(config, "ext_Own", source);
        subsystem(config, "Base", source).setObjectBelonging(ObjectBelonging.ADOPTED);
        role(config, "ext_Reader", source);
        role(config, "BaseReader", source).setObjectBelonging(ObjectBelonging.ADOPTED);
        JsonArray relations = MetadataClonePlan.transferRelations(config, source);
        assertEquals("supported", support(relations, "subsystem", "Subsystem.ext_Own"));
        assertEquals("reportedOnly", support(relations, "subsystem", "Subsystem.Base"));
        assertEquals("supported", support(relations, "roleRights", "Role.ext_Reader"));
        assertEquals("reportedOnly", support(relations, "roleRights", "Role.BaseReader"));
    }

    @Test
    public void selectingAnAdoptedOwnerIsRefusedBeforeAnyWrite() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        subsystem(fixture.config, "Base", fixture.source).setObjectBelonging(ObjectBelonging.ADOPTED);
        JsonObject selection = new JsonObject();
        selection.addProperty("kind", "subsystem");
        selection.addProperty("owner", "Subsystem.Base");
        try
        {
            MetadataClonePlan.capture(fixture.project, fixture.model, fixture.config, fixture.source, "Copy",
                List.of(selection), fixture.fs, false, true);
            fail("The clone must never be written into an adopted owner's content");
        }
        catch (MetadataClonePlan.Refusal expected)
        {
            String message = expected.getMessage();
            assertTrue(message, message.contains("Subsystem.Base"));
            assertTrue(message, message.contains("adopted"));
            assertTrue(message, message.contains("notCopied"));
        }
    }

    @Test
    public void copierKeepsExtensionPropertiesExactlyInAnExtensionProject()
    {
        Configuration destination = MdClassFactory.eINSTANCE.createConfiguration();
        NativeMetadataClone extension = copier(destination, mock(IExtensionProject.class));
        NativeMetadataClone configuration = copier(destination, mock(IV8Project.class));
        assertTrue(extension.isExtensionProject());
        assertFalse(configuration.isExtensionProject());
        Catalog original = MdClassFactory.eINSTANCE.createCatalog();
        Catalog target = MdClassFactory.eINSTANCE.createCatalog();
        assertTrue(extension.shouldCopyContainment(original, MdClassPackage.Literals.MD_OBJECT__EXTENSION, target,
            MdClassPackage.Literals.MD_OBJECT__EXTENSION, null));
        assertFalse(configuration.shouldCopyContainment(original, MdClassPackage.Literals.MD_OBJECT__EXTENSION,
            target, MdClassPackage.Literals.MD_OBJECT__EXTENSION, null));
        assertTrue(extension.shouldCopyAttribute(MdClassPackage.Literals.MD_OBJECT__OBJECT_BELONGING, original,
            MdClassPackage.Literals.MD_OBJECT__OBJECT_BELONGING, target, null));
        assertFalse(configuration.shouldCopyAttribute(MdClassPackage.Literals.MD_OBJECT__OBJECT_BELONGING, original,
            MdClassPackage.Literals.MD_OBJECT__OBJECT_BELONGING, target, null));
        // Everything else follows EDT's base copier in both projects.
        for (NativeMetadataClone copier : List.of(extension, configuration))
        {
            assertTrue(copier.shouldCopyAttribute(MdClassPackage.Literals.MD_OBJECT__COMMENT, original,
                MdClassPackage.Literals.MD_OBJECT__COMMENT, target, null));
            assertFalse(copier.shouldCopyAttribute(MdClassPackage.Literals.MD_OBJECT__UUID, original,
                MdClassPackage.Literals.MD_OBJECT__UUID, target, null));
        }
        assertFalse("Without a destination there is no extension project",
            copier(null, mock(IExtensionProject.class)).isExtensionProject());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NativeMetadataClone copier(Configuration destination, IV8Project project)
    {
        Injector services = mock(Injector.class);
        when(services.getInstance(any(Class.class))).thenAnswer(call -> mock((Class)call.getArgument(0)));
        IV8ProjectManager projects = mock(IV8ProjectManager.class);
        when(projects.getProject(destination)).thenReturn(project);
        when(services.getInstance(IV8ProjectManager.class)).thenReturn(projects);
        return new NativeMetadataClone(services, mock(IBmTransaction.class), mock(IBmModel.class), destination,
            MdClassFactory.eINSTANCE.createCatalog(), "Copy");
    }

    private static Catalog catalog(Configuration config, String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        config.getCatalogs().add(catalog);
        return catalog;
    }

    private static Subsystem subsystem(Configuration config, String name, Catalog member)
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        subsystem.setName(name);
        subsystem.getContent().add(member);
        config.getSubsystems().add(subsystem);
        return subsystem;
    }

    private static Role role(Configuration config, String name, Catalog object)
    {
        Role role = MdClassFactory.eINSTANCE.createRole();
        role.setName(name);
        RoleDescription rights = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights row = RightsFactory.eINSTANCE.createObjectRights();
        row.setObject(object);
        rights.getRights().add(row);
        role.setRights(rights);
        config.getRoles().add(role);
        return role;
    }

    private static String support(JsonArray relations, String kind, String owner)
    {
        for (JsonElement element : relations)
        {
            JsonObject relation = element.getAsJsonObject();
            if (kind.equals(relation.get("kind").getAsString()) && owner.equals(relation.get("owner").getAsString()))
            {
                return relation.get("transferSupport").getAsString();
            }
        }
        throw new AssertionError("No " + kind + " relation for " + owner + " in " + relations);
    }
}
