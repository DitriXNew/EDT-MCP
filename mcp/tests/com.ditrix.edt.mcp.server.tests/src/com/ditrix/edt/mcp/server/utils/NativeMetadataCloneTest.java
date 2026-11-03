/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.md.copy.ModelObjectCopier.CopyContext;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.inject.Injector;

public class NativeMetadataCloneTest
{
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void rootNameIsChosenBeforeAttachmentForEverySupportedType()
    {
        for (String type : MetadataClonePlan.SUPPORTED)
        {
            MdObject source = (MdObject)MdClassFactory.eINSTANCE.create(
                (org.eclipse.emf.ecore.EClass)MdClassPackage.eINSTANCE.getEClassifier(type));
            MdObject target = (MdObject)MdClassFactory.eINSTANCE.create(source.eClass());
            Injector services = mock(Injector.class);
            when(services.getInstance(any(Class.class))).thenAnswer(call -> mock((Class)call.getArgument(0)));
            NativeMetadataClone copier = new NativeMetadataClone(services, mock(IBmTransaction.class),
                mock(IBmModel.class), source, "Requested");
            assertEquals(type, "Requested", copier.generateMdCopyName(source, target,
                MdClassFactory.eINSTANCE.createConfiguration(), null, new CopyContext()));
        }
    }
}
