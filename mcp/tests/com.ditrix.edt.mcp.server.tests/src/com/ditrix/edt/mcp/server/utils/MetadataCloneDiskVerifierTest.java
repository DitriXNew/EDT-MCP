/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

public class MetadataCloneDiskVerifierTest
{
    @Test
    public void copiedFileBytesMustMatchAfterExport() throws Exception
    {
        var file = MetadataCloneFilesFixture.file("Copy/CommandModule.bsl", true, "// copied code");
        byte[] expected = "// copied code".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(expected, MetadataCloneDiskVerifier.verifyCopiedFile(file, MetadataClonePlan.bytesHash(expected)));
        try
        {
            MetadataCloneDiskVerifier.verifyCopiedFile(file, MetadataClonePlan.bytesHash(new byte[] {1}));
            fail("A readable but corrupt copied module must fail verification");
        }
        catch (IllegalStateException failure)
        {
            assertTrue(failure.getMessage().contains("Copied bytes differ"));
            assertTrue(failure.getMessage().contains("CommandModule.bsl"));
        }
    }

    @Test
    public void metadataDiskVerificationAllowsAbsentModuleFiles() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        verifyMetadataFileWithModuleReferences(fixture);
        verify(fixture.objectModule, never()).getContents(true);
        verify(fixture.managerModule, never()).getContents(true);
    }

    @Test
    public void metadataDiskVerificationDoesNotTreatPresentModulesAsEmbeddedXml() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        when(fixture.objectModule.exists()).thenReturn(true);
        when(fixture.managerModule.exists()).thenReturn(true);
        verifyMetadataFileWithModuleReferences(fixture);
        // Separate copied-file hashes verify BSL bytes; the .mdo has no embedded module AST/proxy.
        verify(fixture.objectModule, never()).getContents(true);
        verify(fixture.managerModule, never()).getContents(true);
    }

    private static void verifyMetadataFileWithModuleReferences(MetadataCloneFilesFixture fixture) throws Exception
    {
        Catalog disk = EcoreUtil.copy(fixture.source);
        disk.setObjectModule(null);
        disk.setManagerModule(null);
        org.eclipse.emf.ecore.resource.Resource resource = mock(org.eclipse.emf.ecore.resource.Resource.class);
        when(resource.getErrors()).thenReturn(new org.eclipse.emf.common.util.BasicEList<>());
        when(resource.getContents()).thenReturn(new org.eclipse.emf.common.util.BasicEList<>(java.util.List.of(disk)));
        org.eclipse.emf.ecore.resource.Resource.Factory factory = uri -> resource;
        MapRestore registration = new MapRestore("clone819", factory);
        try
        {
            when(fixture.sourceFile.getFullPath()).thenReturn(new org.eclipse.core.runtime.Path("/P/Source.clone819"));
            MetadataCloneDiskVerifier.verify(fixture.sourceFile, fixture.source,
                mock(com._1c.g5.v8.dt.core.platform.IDtProject.class), null, mock(ISymbolicNameService.class));
            verify(resource).load(any(java.io.InputStream.class), any(java.util.Map.class));
            verify(resource).unload();
        }
        finally
        {
            registration.close();
        }
    }

    private static final class MapRestore implements AutoCloseable
    {
        private final java.util.Map<String, Object> factories =
            org.eclipse.emf.ecore.resource.Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap();
        private final String extension;
        private final Object previous;

        MapRestore(String extension, org.eclipse.emf.ecore.resource.Resource.Factory factory)
        {
            this.extension = extension;
            previous = factories.put(extension, factory);
        }

        @Override
        public void close()
        {
            if (previous == null)
            {
                factories.remove(extension);
            }
            else
            {
                factories.put(extension, previous);
            }
        }
    }

    @Test
    public void equalPersistedModelsPass()
    {
        Catalog model = MdClassFactory.eINSTANCE.createCatalog();
        model.setName("Source");
        MetadataCloneDiskVerifier.compare(model, EcoreUtil.copy(model), mock(ISymbolicNameService.class));
    }

    @Test
    public void sameNodeCountWithCorruptPropertyFails()
    {
        Catalog model = MdClassFactory.eINSTANCE.createCatalog();
        model.setName("Expected");
        Catalog disk = EcoreUtil.copy(model);
        disk.setName("Wrong");
        assertRefused(model, disk, mock(ISymbolicNameService.class), "/name");
    }

    @Test
    public void wrongReferenceTargetFails()
    {
        Catalog model = MdClassFactory.eINSTANCE.createCatalog();
        Catalog other = MdClassFactory.eINSTANCE.createCatalog();
        other.setName("Other");
        model.getOwners().add(other);
        Catalog disk = EcoreUtil.copy(model);
        Catalog wrong = MdClassFactory.eINSTANCE.createCatalog();
        wrong.setName("Wrong");
        disk.getOwners().clear();
        disk.getOwners().add(wrong);
        ISymbolicNameService names = mock(ISymbolicNameService.class);
        when(names.generateSymbolicName(any(), any(), any())).thenAnswer(call ->
            ((MdObject)call.getArgument(0)).getName());
        assertRefused(model, disk, names, "/owners");
    }

    private static void assertRefused(Catalog model, Catalog disk, ISymbolicNameService names, String feature)
    {
        try
        {
            MetadataCloneDiskVerifier.compare(model, disk, names);
            fail("A mismatched exported file must never be verified");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains(feature));
        }
    }
}
