/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com.google.gson.JsonArray;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.Path;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;

public class MetadataClonePlanTest
{
    @Test
    public void catalogHasBilingualProgrammaticResolution()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("\u0422\u043e\u0432\u0430\u0440\u044b");
        catalog.getSynonym().put("en", "Products synonym");
        config.getCatalogs().add(catalog);
        assertSame(catalog, MetadataClonePlan.source(config, "Catalog." + catalog.getName()));
        assertSame(catalog, MetadataClonePlan.source(config, "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a." + catalog.getName()));
        try
        {
            MetadataClonePlan.source(config, "Catalog.Products synonym");
            fail("Synonyms are not addresses");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("not a synonym"));
        }
    }

    @Test
    public void documentHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.DOCUMENT, "documents");
    }

    @Test
    public void reportHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.REPORT, "reports");
    }

    @Test
    public void dataProcessorHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.DATA_PROCESSOR, "dataProcessors");
    }

    private static void assertCollection(EClass type, String expected)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        MdObject object = (MdObject)MdClassFactory.eINSTANCE.create(type);
        assertEquals(expected, MetadataClonePlan.collection(config, object).getName());
        assertNull(MetadataClonePlan.requestError(type.getName() + ".Source", null, false, null, null));
    }

    @Test
    public void nameCollisionIsCaseInsensitive()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog source = MdClassFactory.eINSTANCE.createCatalog();
        source.setName("Taken");
        config.getCatalogs().add(source);
        try
        {
            MetadataClonePlan.checkName(config, source, "TAKEN");
            fail("Collision must be refused before native copy");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("case-insensitive"));
        }
        MetadataClonePlan.checkName(config, source, "Available");
    }

    @Test
    public void validatesIdentifiersAndRequiredHash()
    {
        for (String bad : List.of("", "with space", "2Name", "A.B", "A/B"))
        {
            assertTrue(bad, MetadataClonePlan.requestError("Catalog.Source", bad, false, null, null)
                .contains("newName"));
        }
        assertNull(MetadataClonePlan.requestError("Catalog.Source", "_Valid2", false, null, null));
        assertNull(MetadataClonePlan.requestError("Catalog.Source", "\u041a\u043e\u043f\u0438\u044f", false, null, null));
        assertTrue(MetadataClonePlan.requestError("Catalog.Source", null, true, null, null).contains("expectedHash"));
    }

    @Test
    public void malformedOrUnsupportedTransfersCannotBeSilentlyDropped()
    {
        for (String json : List.of("{}", "[1]", "[{\"kind\":\"subsystem\"}]", "bad json",
            "[{\"kind\":\"eventSubscription\",\"owner\":\"EventSubscription.X\"}]",
            "[{\"kind\":\"subsystem\",\"owner\":\"Subsystem.X\",\"extra\":true}]",
            "[{\"kind\":\"subsystem\",\"owner\":\"Subsystem.X\"},{\"kind\":\"subsystem\",\"owner\":\"Subsystem.x\"}]"))
        {
            assertNotNull(json, MetadataClonePlan.requestError("Catalog.Source", null, false, null, json));
        }
        assertNull(MetadataClonePlan.requestError("Catalog.Source", null, false, null,
            "[{\"kind\":\"roleRights\",\"owner\":\"Role.Y\"}]"));
    }

    @Test
    public void literalSourceReferencesReportExactLinesInBothDialects()
    {
        String text = "// Catalogs.Source\nX = \u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a\u0438.Source;\n"
            + "Catalogs.SourceSuffix;\nOtherCatalogs.Source;\nCatalog.Source";
        JsonArray refs = MetadataClonePlan.textReferences("ObjectModule.bsl", text, "Catalog.Source");
        assertEquals(3, refs.size());
        assertEquals(1, refs.get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(2, refs.get(1).getAsJsonObject().get("line").getAsInt());
        assertEquals(5, refs.get(2).getAsJsonObject().get("line").getAsInt());
        assertEquals("ObjectModule.bsl", refs.get(0).getAsJsonObject().get("file").getAsString());
    }

    @Test
    public void binaryHashDoesNotNormalizeLineEndings()
    {
        assertNotEquals(MetadataClonePlan.bytesHash(new byte[] {13, 10}),
            MetadataClonePlan.bytesHash(new byte[] {10}));
    }

    @Test
    public void previewFingerprintTracksSourceModelFilesDestinationAndRequestedName() throws Exception
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog source = MdClassFactory.eINSTANCE.createCatalog();
        source.setName("Source");
        config.getCatalogs().add(source);
        com._1c.g5.v8.dt.metadata.mdclass.Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        subsystem.setName("Incoming");
        subsystem.getContent().add(source);
        config.getSubsystems().add(subsystem);
        com._1c.g5.v8.dt.metadata.mdclass.Role role = MdClassFactory.eINSTANCE.createRole();
        role.setName("Incoming");
        RoleDescription roleRights = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights authored = RightsFactory.eINSTANCE.createObjectRights();
        authored.setObject(source);
        roleRights.getRights().add(authored);
        role.setRights(roleRights);
        config.getRoles().add(role);
        IProject project = mock(IProject.class);
        IBmModel model = mock(IBmModel.class);
        IProjectFileSystemSupport fs = mock(IProjectFileSystemSupport.class);
        IFile file = mock(IFile.class);
        IFile notes = mock(IFile.class);
        IFolder folder = mock(IFolder.class);
        IFolder collection = mock(IFolder.class);
        IFolder destination = mock(IFolder.class);
        when(fs.getFile(source)).thenReturn(file);
        when(file.exists()).thenReturn(true);
        when(file.getParent()).thenReturn(folder);
        when(file.getFullPath()).thenReturn(new Path("/P/src/Catalogs/Source/Source.mdo"));
        when(file.getProjectRelativePath()).thenReturn(new Path("src/Catalogs/Source/Source.mdo"));
        when(notes.getFullPath()).thenReturn(new Path("/P/src/Catalogs/Source/README.md"));
        when(notes.getContents(true)).thenAnswer(call -> new java.io.ByteArrayInputStream(new byte[] {1, 2}));
        when(folder.getParent()).thenReturn(collection);
        when(collection.getFolder(org.mockito.ArgumentMatchers.any(org.eclipse.core.runtime.IPath.class)))
            .thenReturn(destination);
        when(folder.getFullPath()).thenReturn(new Path("/P/src/Catalogs/Source"));
        when(folder.getProjectRelativePath()).thenReturn(new Path("src/Catalogs/Source"));
        java.util.concurrent.atomic.AtomicReference<String> bytes = new java.util.concurrent.atomic.AtomicReference<>("first");
        when(file.getContents(true)).thenAnswer(call -> new java.io.ByteArrayInputStream(
            bytes.get().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        doAnswer(call -> {
            ((IResourceVisitor)call.getArgument(0)).visit(file);
            ((IResourceVisitor)call.getArgument(0)).visit(notes);
            return null;
        }).when(folder).accept(org.mockito.ArgumentMatchers.any(IResourceVisitor.class));
        java.util.function.Function<String, String> hash = name -> MetadataClonePlan.capture(project, model,
            config, source, name, List.of(), fs, false).hash();
        assertEquals("Unowned notes must not become required native-copy resources", 1,
            MetadataClonePlan.capture(project, model, config, source, "Requested", List.of(), fs, false).files.size());
        MetadataClonePlan incoming = MetadataClonePlan.capture(project, model, config, source,
            "Requested", List.of(), fs, false);
        assertEquals("roleRights", incoming.incoming.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("subsystem", incoming.incoming.get(1).getAsJsonObject().get("kind").getAsString());
        String initial = hash.apply("Requested");
        assertEquals(initial, hash.apply("Requested"));
        assertNotEquals(initial, hash.apply("AnotherName"));
        source.setComment("model changed");
        String changedModel = hash.apply("Requested");
        assertNotEquals(initial, changedModel);
        bytes.set("second");
        String changedFile = hash.apply("Requested");
        assertNotEquals(changedModel, changedFile);
        Catalog sibling = MdClassFactory.eINSTANCE.createCatalog();
        sibling.setName("DestinationChanged");
        config.getCatalogs().add(sibling);
        String changedDestination = hash.apply("Requested");
        assertNotEquals(changedFile, changedDestination);
        roleRights.setSetForNewObjects(true);
        assertNotEquals(changedDestination, hash.apply("Requested"));
    }

    @Test
    public void roleRowIsCopiedWithDeniedValuesAndSourceIsPreserved()
    {
        EObject source = MdClassFactory.eINSTANCE.createCatalog();
        EObject clone = MdClassFactory.eINSTANCE.createCatalog();
        RoleDescription role = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights row = RightsFactory.eINSTANCE.createObjectRights();
        row.setObject(source);
        ObjectRight right = RightsFactory.eINSTANCE.createObjectRight();
        Right read = RightsFactory.eINSTANCE.createRight();
        read.setName("Read");
        right.setRight(read);
        right.setValue(RightValue.UNSET);
        row.getRights().add(right);
        role.getRights().add(row);
        role.setSetForNewObjects(true);
        MetadataClonePlan.copyRightsRow(role, source, clone, Map.of("", source), Map.of("", clone));
        assertEquals(2, role.getRights().size());
        assertSame(source, row.getObject());
        ObjectRights copied = role.getRights().get(1);
        assertNotSame(row, copied);
        assertSame(clone, copied.getObject());
        assertSame(read, copied.getRights().get(0).getRight());
        assertEquals(RightValue.UNSET, copied.getRights().get(0).getValue());
        assertTrue(role.isSetForNewObjects());
    }

    @Test
    public void rlsFieldProxyRetainsFieldPathUnderNewOwner()
    {
        org.eclipse.emf.common.util.URI original = com._1c.g5.v8.bm.core.BmUriUtil.createContainedBmObjectUri(
            "project", "Catalog.Source", "/dbViewDefs/mainView/fields.Description");
        org.eclipse.emf.common.util.URI copied = MetadataClonePlan.retargetRlsFieldUri(original,
            "Catalog.Source", "Catalog.Requested", "project");
        assertEquals("Catalog.Requested", com._1c.g5.v8.bm.core.BmUriUtil.extractTopObjectFqn(copied));
        assertEquals(original.fragment(), copied.fragment());
    }

    @Test
    public void rlsFieldOutsideSourceIsRefused()
    {
        try
        {
            MetadataClonePlan.retargetRlsFieldUri(com._1c.g5.v8.bm.core.BmUriUtil.createContainedBmObjectUri(
                "project", "Catalog.Other", "/field"), "Catalog.Source", "Catalog.Requested", "project");
            fail("A foreign RLS field cannot silently remain on the source");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("outside the cloned source"));
        }
    }
}
