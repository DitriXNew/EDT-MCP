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
    public void observesEveryNativeTopAttachmentIncludingSeparateForms()
    {
        Injector services = mock(Injector.class);
        when(services.getInstance(any(Class.class))).thenAnswer(call -> mock((Class)call.getArgument(0)));
        var generator = mock(com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator.class);
        when(services.getInstance(com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator.class)).thenReturn(generator);
        when(generator.generateStandaloneObjectFqn(any(), any())).thenReturn("Catalog.Copy");
        IBmTransaction tx = mock(IBmTransaction.class);
        NativeMetadataClone copier = new NativeMetadataClone(services, tx, mock(IBmModel.class),
            MdClassFactory.eINSTANCE.createCatalog(), "Copy");
        var root = mock(com._1c.g5.v8.bm.core.IBmObject.class, withSettings().extraInterfaces(MdObject.class));
        when(root.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(root.bmIsTop()).thenReturn(true);
        when(root.bmGetFqn()).thenReturn("Catalog.Copy");
        when(((MdObject)root).getName()).thenReturn("Copy");
        copier.attachModelObject(root, true);
        for (String name : java.util.List.of("ItemForm", "ListForm"))
        {
            var owner = MdClassFactory.eINSTANCE.createCatalogForm();
            owner.setName(name);
            var form = mock(com._1c.g5.v8.bm.core.IBmObject.class);
            String fqn = "Catalog.Copy.Form." + name + ".Form";
            when(form.bmIsTop()).thenReturn(true);
            when(form.bmGetFqn()).thenReturn(fqn);
            when(generator.generateExternalPropertyFqn(owner, MdClassPackage.Literals.BASIC_FORM__FORM)).thenReturn(fqn);
            copier.attachExternalProperty(form, owner, MdClassPackage.Literals.BASIC_FORM__FORM);
            verify(tx).attachTopObject(form, fqn);
        }
        var indexes = mock(com._1c.g5.v8.bm.core.IBmObject.class);
        when(indexes.bmIsTop()).thenReturn(true);
        when(indexes.bmGetFqn()).thenReturn("Catalog.Copy.AdditionalIndexes");
        var owner = MdClassFactory.eINSTANCE.createCatalog();
        var reference = (org.eclipse.emf.ecore.EReference)owner.eClass().getEStructuralFeature("additionalIndexes");
        when(generator.generateExternalPropertyFqn(owner, reference)).thenReturn("Catalog.Copy.AdditionalIndexes");
        copier.attachExternalProperty(indexes, owner, reference);
        assertEquals(java.util.Set.of("Catalog.Copy", "Catalog.Copy.Form.ItemForm.Form",
            "Catalog.Copy.Form.ListForm.Form", "Catalog.Copy.AdditionalIndexes"), copier.attachedTopFqns());
        verify(tx).attachTopObject(root, "Catalog.Copy");
        verify(tx).attachTopObject(indexes, "Catalog.Copy.AdditionalIndexes");
        var contained = mock(com._1c.g5.v8.bm.core.IBmObject.class);
        when(contained.bmIsTop()).thenReturn(true);
        copier.attachModelObject(contained, false);
        verify(contained, never()).bmGetFqn();
        assertEquals("Unattached containment must not be added to the export roots", 4, copier.attachedTopFqns().size());
    }

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
