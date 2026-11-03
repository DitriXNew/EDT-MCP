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
