/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.UUID;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IAfterAdopt;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IAdopterContext;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IAdopterContext.ITransactionalAdopterContext;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IModelObjectAdopterParticipant;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/** Native participants must receive the attached form instead of consulting the stale global scope. */
public class AdoptedFormContentTest
{
    @Test
    @SuppressWarnings("unchecked")
    public void testNativeAfterAdoptPinsMetadataAndCreatesContentInsteadOfReusingForeignLookup() throws Exception
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        IBmModel model = mock(IBmModel.class);
        IBmNamespace namespace = mock(IBmNamespace.class);
        IBmNamespaceDataStore primary = mock(IBmNamespaceDataStore.class);
        IBmPlatformTransaction platform = mock(IBmPlatformTransaction.class);
        when(tx.getNamespace()).thenReturn(namespace);
        when(namespace.getPrimaryStore()).thenReturn(primary);
        when(tx.getPlatformTransaction()).thenReturn(platform);
        when(platform.getContainingDataStore(any())).thenReturn(primary);
        when(tx.toTransactionObject(any())).thenAnswer(inv -> inv.getArgument(0));
        CatalogForm source = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        CatalogForm attached = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        Form foreign = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
        Form own = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
        when(source.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        when(source.getForm()).thenReturn(foreign);
        when(source.getUuid()).thenReturn(UUID.randomUUID());
        when(foreign.eClass()).thenReturn(FormPackage.Literals.FORM);
        when(foreign.getMdForm()).thenReturn(source);
        when(((IBmObject)foreign).bmGetId()).thenReturn(103079215104L);
        IBmNamespace baseNamespace = mock(IBmNamespace.class);
        when(((IBmObject)foreign).bmGetNamespace()).thenReturn(baseNamespace);
        Form anotherView = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)anotherView).bmGetId()).thenReturn(103079215104L);
        when(((IBmObject)anotherView).bmGetNamespace()).thenReturn(baseNamespace);
        when(platform.getContainingDataStore((IBmObject)foreign)).thenReturn(mock(IBmNamespaceDataStore.class));
        IModelObjectAdopter adopter = mock(IModelObjectAdopter.class);
        IExtensionProject target = mock(IExtensionProject.class);
        when(adopter.getAdopted(foreign, target)).thenReturn(foreign);
        IAfterAdopt<BasicForm> metadataParticipant = mock(IAfterAdopt.class);
        IModelObjectAdopterParticipant<Form> contentParticipant = mock(IModelObjectAdopterParticipant.class);
        CommonPicture picture = mock(CommonPicture.class, withSettings().extraInterfaces(IBmObject.class));
        CommonPicture adoptedPicture = mock(CommonPicture.class, withSettings().extraInterfaces(IBmObject.class));
        when(adopter.adoptAndAttach(eq(List.of(picture)), eq(target), any())).thenReturn(List.of(adoptedPicture));
        when(contentParticipant.adopt(eq(foreign), any(), any())).thenAnswer(inv -> {
            ITransactionalAdopterContext context = inv.getArgument(2);
            assertSame(tx, context.getTransaction());
            assertSame(model, context.getModel());
            assertSame(attached, context.adopt(foreign.getMdForm()));
            assertSame(adoptedPicture, context.adopt(picture));
            assertSame(adoptedPicture, context.adopt(picture));
            attached.setForm(own);
            return own;
        });
        doAnswer(inv -> {
            IAdopterContext context = inv.getArgument(2);
            assertSame(own, context.adopt(source.getForm()));
            assertSame("a second BM view must reuse the newly created primary content", own, context.adopt(anotherView));
            return null;
        }).when(metadataParticipant).afterAdopt(eq(source), eq(attached), any());
        AdoptedFormContent.adopt(tx, model, adopter, target, source, attached,
            type -> type == MdClassPackage.Literals.CATALOG_FORM ? metadataParticipant : contentParticipant);
        verify(contentParticipant).adopt(eq(foreign), any(), any());
        verify(metadataParticipant).afterAdopt(eq(source), eq(attached), any());
        verify(adopter).adoptAndAttach(eq(List.of(picture)), eq(target), any());
        verify(adopter, never()).getAdopted(any(), any());
        verify(foreign, never()).setMdForm(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAlreadyAdoptedPictureLookupProxyIsResolvedInTheSameTransaction() throws Exception
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        IBmNamespace namespace = mock(IBmNamespace.class);
        IBmNamespaceDataStore primary = mock(IBmNamespaceDataStore.class);
        IBmPlatformTransaction platform = mock(IBmPlatformTransaction.class);
        when(tx.getNamespace()).thenReturn(namespace);
        when(namespace.getPrimaryStore()).thenReturn(primary);
        when(tx.getPlatformTransaction()).thenReturn(platform);
        when(platform.getContainingDataStore(any())).thenReturn(primary);
        when(tx.toTransactionObject(any())).thenAnswer(inv -> inv.getArgument(0));
        BasicForm source = mock(BasicForm.class);
        BasicForm attached = mock(BasicForm.class);
        CommonPicture picture = MdClassFactory.eINSTANCE.createCommonPicture();
        CommonPicture proxy = MdClassFactory.eINSTANCE.createCommonPicture();
        URI uri = URI.createURI("bm:/extension/CommonPicture.Logo");
        ((InternalEObject)proxy).eSetProxyURI(uri);
        CommonPicture adoptedPicture = mock(CommonPicture.class, withSettings().extraInterfaces(IBmObject.class));
        when(tx.getObjectByUri(uri)).thenReturn((IBmObject)adoptedPicture);
        IModelObjectAdopter adopter = mock(IModelObjectAdopter.class);
        IExtensionProject target = mock(IExtensionProject.class);
        when(adopter.adoptAndAttach(eq(List.of(picture)), eq(target), any())).thenReturn(List.of());
        when(adopter.getAdopted(picture, target)).thenReturn(proxy);
        IAfterAdopt<BasicForm> participant = mock(IAfterAdopt.class);
        doAnswer(inv -> {
            assertSame(adoptedPicture, ((IAdopterContext)inv.getArgument(2)).adopt(picture));
            return null;
        }).when(participant).afterAdopt(eq(source), eq(attached), any());
        AdoptedFormContent.adopt(tx, mock(IBmModel.class), adopter, target, source, attached, type -> participant);
        verify(tx).getObjectByUri(uri);
    }

    @Test
    public void testUnavailableNativeAfterAdoptParticipantRefusesRecovery()
    {
        BasicForm source = mock(BasicForm.class);
        try
        {
            AdoptedFormContent.adopt(mock(IBmTransaction.class), mock(IBmModel.class), mock(IModelObjectAdopter.class),
                mock(IExtensionProject.class), source, mock(BasicForm.class), type -> null);
            fail("a missing platform participant cannot be replaced with a skeleton form");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("after-adopt participant is unavailable"));
        }
    }
}
