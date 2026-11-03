/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;

/** The native containment fast path can mistake a different namespace's child for a stored child. */
public class AdoptedFormAttachmentTest
{
    private final IBmTransaction tx = mock(IBmTransaction.class);
    private final IBmNamespace namespace = mock(IBmNamespace.class);
    private final IBmNamespaceDataStore primary = mock(IBmNamespaceDataStore.class);
    private final IBmPlatformTransaction platform = mock(IBmPlatformTransaction.class);
    private final Catalog baseOwner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
    private final Catalog owner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
    private final CatalogForm source = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
    private final CatalogForm copy = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
    private final Form content = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
    private final Form snapshot = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
    private final ITopObjectFqnGenerator generator = mock(ITopObjectFqnGenerator.class);
    private final AtomicLong copyId = new AtomicLong(IBmObject.BM_NULL_ID);
    private CatalogForm storedCopy;
    private boolean falseContains = true;
    private final BasicEList<EObject> forms = new BasicEList<>()
    {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean contains(Object value)
        {
            return value == source ? falseContains : super.contains(value);
        }

        @Override
        public boolean add(EObject value)
        {
            if (value == copy)
            {
                if (storedCopy != null)
                {
                    return super.add(storedCopy);
                }
                copyId.set(2003L);
            }
            return super.add(value);
        }
    };

    @Before
    public void setUp()
    {
        UUID ownerId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        when(baseOwner.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(baseOwner.getName()).thenReturn("Goods");
        when(baseOwner.getUuid()).thenReturn(ownerId);
        when(((IBmObject)baseOwner).bmGetId()).thenReturn(2001L);
        when(((IBmObject)baseOwner).bmGetNamespace()).thenReturn(mock(IBmNamespace.class));
        when(owner.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(owner.getName()).thenReturn("Goods");
        when(owner.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        when(owner.getExtendedConfigurationObject()).thenReturn(ownerId);
        when(((IBmObject)owner).bmGetId()).thenReturn(2001L);
        when(owner.eGet(MdClassPackage.Literals.CATALOG__FORMS)).thenReturn(forms);
        when(source.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        when(source.getName()).thenReturn("Item");
        when(source.getUuid()).thenReturn(formId);
        when(source.eContainer()).thenReturn(baseOwner);
        when(source.eContainingFeature()).thenReturn(MdClassPackage.Literals.CATALOG__FORMS);
        when(copy.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        when(copy.getName()).thenReturn("Item");
        when(copy.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        when(copy.getExtendedConfigurationObject()).thenReturn(formId);
        when(copy.getForm()).thenReturn(content);
        when(((IBmObject)copy).bmGetId()).thenAnswer(inv -> copyId.get());
        when(tx.getNamespace()).thenReturn(namespace);
        when(namespace.getPrimaryStore()).thenReturn(primary);
        when(tx.getPlatformTransaction()).thenReturn(platform);
        when(platform.getContainingDataStore(any())).thenReturn(primary);
        when(tx.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject)owner);
        when(((IBmObject)content).bmGetId()).thenReturn(3001L);
        when(((IBmObject)content).bmIsTop()).thenReturn(true);
        when(((IBmObject)content).bmGetFqn()).thenReturn("CatalogForm.Item.Form");
        when(((IBmObject)snapshot).bmGetId()).thenReturn(3002L);
        when(((IBmObject)snapshot).bmGetFqn()).thenReturn("CatalogForm.Item.Form.BaseForm");
        when(content.getBaseForm()).thenReturn(snapshot);
        when(tx.getTopObjectIterator()).thenAnswer(inv -> List.of((IBmObject)content, (IBmObject)snapshot).iterator());
        when(generator.generateExternalPropertyFqn(copy, MdClassPackage.Literals.BASIC_FORM__FORM))
            .thenAnswer(inv -> copyId.get() == IBmObject.BM_NULL_ID
                ? "CatalogForm.Item.Form" : "Catalog.Goods.Form.Item.Form");
        CatalogForm other = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        when(other.getName()).thenReturn("Other");
        forms.add(other);
    }

    @Test
    public void testCompleteNativeCopySkippedByCrossNamespaceContainmentCollision()
    {
        assertNull("there is no attached copy for an owner walk or the native index to find",
            AdoptedFormLookup.find(tx, source));
        assertTrue("ModelObjectAdopter's contains(source) skips addChild", forms.contains(source));
        assertSame(copy, AdoptedFormAttachment.complete(tx, source, copy, generator));
        assertEquals(2, forms.size());
        assertSame(copy, forms.get(1));
        assertSame(content, copy.getForm());
        assertSame(snapshot, content.getBaseForm());
        verify(tx).updateTopObjectFqn((IBmObject)content, "Catalog.Goods.Form.Item.Form");
        verify(tx).updateTopObjectFqn((IBmObject)snapshot, "Catalog.Goods.Form.Item.Form.BaseForm");
        verify(content).setMdForm(copy);
        verify(snapshot).setMdForm(copy);
    }

    @Test
    public void testDoNotAttachWhenContainsDoesNotHaveTheNativeFalsePositive()
    {
        falseContains = false;
        assertRefused();
    }

    @Test
    public void testInsertionMayMaterializeADifferentTransactionObject()
    {
        storedCopy = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        when(storedCopy.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        when(storedCopy.getName()).thenReturn("Item");
        when(storedCopy.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        UUID origin = source.getUuid();
        when(storedCopy.getExtendedConfigurationObject()).thenReturn(origin);
        when(((IBmObject)storedCopy).bmGetId()).thenReturn(2003L);
        when(generator.generateExternalPropertyFqn(storedCopy, MdClassPackage.Literals.BASIC_FORM__FORM))
            .thenReturn("Catalog.Goods.Form.Item.Form");
        assertSame(storedCopy, AdoptedFormAttachment.complete(tx, source, copy, generator));
        assertEquals("the original allocation may remain detached", IBmObject.BM_NULL_ID, copyId.get());
        verify(content).setMdForm(storedCopy);
        verify(snapshot).setMdForm(storedCopy);
    }

    @Test
    public void testDoNotReportSuccessWhenTheNativeCopierOmittedContent()
    {
        when(source.getForm()).thenReturn(content);
        when(copy.getForm()).thenReturn(null);
        assertRefused();
    }

    @Test
    public void testDoNotAttachWhenOwnerIdsDoNotCollide()
    {
        when(((IBmObject)baseOwner).bmGetId()).thenReturn(9001L);
        assertRefused();
    }

    @Test
    public void testDoNotAttachSourceInTheSameNamespace()
    {
        when(((IBmObject)baseOwner).bmGetNamespace()).thenReturn(namespace);
        assertRefused();
    }

    @Test
    public void testDoNotAttachWhenAnExistingFormHasTheSameName()
    {
        when(((CatalogForm)forms.get(0)).getName()).thenReturn("Item");
        assertRefused();
    }

    @Test
    public void testDoNotAttachACopyWithTheWrongOrigin()
    {
        when(copy.getExtendedConfigurationObject()).thenReturn(UUID.randomUUID());
        assertRefused();
    }

    @Test
    public void testDoNotMoveSecondaryFormContent()
    {
        when(platform.getContainingDataStore((IBmObject)content)).thenReturn(mock(IBmNamespaceDataStore.class));
        assertRefused();
    }

    @Test
    public void testContentCollisionFailsTheWriteRatherThanOverwritingAnotherForm()
    {
        IBmObject existing = mock(IBmObject.class);
        when(existing.bmGetId()).thenReturn(9001L);
        when(tx.getTopObjectByFqn("Catalog.Goods.Form.Item.Form")).thenReturn(existing);
        try
        {
            AdoptedFormAttachment.complete(tx, source, copy, generator);
            fail("the caller must roll back a conflicting native allocation");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("content already exists"));
        }
        verify(tx, never()).updateTopObjectFqn(any(), any());
    }

    @Test
    public void testMissingCopyDiagnosticsIncludeCandidateStoreAndOriginFlags()
    {
        String text = AdoptedFormAttachment.describe(tx, source, copy);
        assertTrue(text, text.contains("copy=") && text.contains("#-1"));
        assertTrue(text, text.contains("container=null, feature=null"));
        assertTrue(text, text.contains("ownerFound=true") && text.contains("formsCount=1"));
        assertTrue(text, text.contains("adopted=false,primary=true,originUuidMatch=false"));
    }

    private void assertRefused()
    {
        assertNull(AdoptedFormAttachment.complete(tx, source, copy, generator));
        assertEquals(1, forms.size());
        verify(tx, never()).updateTopObjectFqn(any(), any());
    }
}
