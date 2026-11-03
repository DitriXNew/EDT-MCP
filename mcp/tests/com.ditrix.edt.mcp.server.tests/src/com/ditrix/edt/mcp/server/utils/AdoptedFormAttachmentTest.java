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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.common.util.URI;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
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
        when(((IBmObject)owner).bmGetNamespace()).thenReturn(namespace);
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
        when(((IBmObject)copy).bmGetNamespace()).thenReturn(namespace);
        when(tx.getNamespace()).thenReturn(namespace);
        when(namespace.getPrimaryStore()).thenReturn(primary);
        when(tx.getPlatformTransaction()).thenReturn(platform);
        when(platform.getContainingDataStore(any())).thenReturn(primary);
        when(tx.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject)owner);
        when(((IBmObject)content).bmGetId()).thenReturn(3001L);
        when(((IBmObject)content).bmGetNamespace()).thenReturn(namespace);
        when(((IBmObject)content).bmIsTop()).thenReturn(true);
        when(((IBmObject)content).bmGetFqn()).thenReturn("CatalogForm.Item.Form");
        when(((IBmObject)snapshot).bmGetId()).thenReturn(3002L);
        when(((IBmObject)snapshot).bmGetNamespace()).thenReturn(namespace);
        when(((IBmObject)snapshot).bmGetFqn()).thenReturn("CatalogForm.Item.Form.BaseForm");
        when(content.getBaseForm()).thenReturn(snapshot);
        when(tx.getTopObjectIterator()).thenAnswer(inv -> List.of((IBmObject)content, (IBmObject)snapshot).iterator());
        when(generator.generateExternalPropertyFqn(copy, MdClassPackage.Literals.BASIC_FORM__FORM))
            .thenAnswer(inv -> copyId.get() == IBmObject.BM_NULL_ID
                ? "CatalogForm.Item.Form" : "Catalog.Goods.Form.Item.Form");
        CatalogForm other = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        when(other.getName()).thenReturn("Other");
        when(((IBmObject)other).bmGetNamespace()).thenReturn(namespace);
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
    public void testCiStandardPictureFormUsesStoredContentWhenTheAllocationReferenceIsMissing()
    {
        String name = "E2EStandardPictureAdoptForm";
        String oldFqn = "CatalogForm." + name + ".Form";
        String newFqn = "Catalog.Catalog.Form." + name + ".Form";
        when(baseOwner.getName()).thenReturn("Catalog");
        when(owner.getName()).thenReturn("Catalog");
        when(((IBmObject)baseOwner).bmGetId()).thenReturn(12884901888L);
        when(((IBmObject)owner).bmGetId()).thenReturn(12884901888L);
        when(((IBmObject)baseOwner).bmGetNamespace().getName()).thenReturn("TestConfiguration");
        when(((IBmObject)owner).bmGetNamespace()).thenReturn(namespace);
        when(namespace.getName()).thenReturn("TestConfiguration.tests");
        when(source.getName()).thenReturn(name);
        when(((IBmObject)source).bmGetId()).thenReturn(12884902302L);
        when(copy.getName()).thenReturn(name);
        CatalogForm item = (CatalogForm)forms.get(0);
        when(item.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        when(item.getName()).thenReturn("ItemForm");
        when(item.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        when(((IBmObject)item).bmGetId()).thenReturn(12884901896L);
        when(tx.getTopObjectByFqn("Catalog.Catalog")).thenReturn((IBmObject)owner);

        Form data = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        Picture standard = mock(Picture.class, withSettings().extraInterfaces(InternalEObject.class));
        when(standard.eIsProxy()).thenReturn(true);
        when(((InternalEObject)standard).eProxyURI()).thenReturn(URI.createURI("v8:/Pictures/Std/8.3.27#/Change"));
        PictureRef ref = McoreFactory.eINSTANCE.createPictureRef();
        ref.setPicture(standard);
        field.setHeaderPicture(ref);
        data.getItems().add(field);
        Form stored = mock(Form.class, withSettings().extraInterfaces(IBmObject.class)
            .defaultAnswer(delegatesTo(data)));
        org.mockito.Mockito.doReturn(3001L).when((IBmObject)stored).bmGetId();
        org.mockito.Mockito.doReturn(namespace).when((IBmObject)stored).bmGetNamespace();
        org.mockito.Mockito.doReturn(true).when((IBmObject)stored).bmIsTop();
        org.mockito.Mockito.doReturn(oldFqn).when((IBmObject)stored).bmGetFqn();
        when(source.getForm()).thenReturn(data);
        when(copy.getForm()).thenReturn(null); // BasicForm.form is a transient external-content reference.
        when(tx.getTopObjectByFqn(oldFqn)).thenReturn((IBmObject)stored);
        when(tx.getTopObjectIterator()).thenAnswer(inv -> List.of((IBmObject)stored).iterator());
        when(generator.generateExternalPropertyFqn(copy, MdClassPackage.Literals.BASIC_FORM__FORM))
            .thenAnswer(inv -> copyId.get() == IBmObject.BM_NULL_ID ? oldFqn : newFqn);
        String before = AdoptedFormAttachment.describe(tx, source, copy);
        assertTrue(before, before.contains("#12884902302") && before.contains("container=null, feature=null"));
        assertTrue(before, before.contains("ownerFound=true") && before.contains("formsCount=1, containsSource=true"));
        assertTrue(before, before.contains("adopted=true,primary=true,originUuidMatch=false"));

        assertSame(copy, AdoptedFormAttachment.complete(tx, source, copy, generator));
        verify(copy).setForm(stored);
        verify(stored).setMdForm(copy);
        verify(tx).updateTopObjectFqn((IBmObject)stored, newFqn);
        assertSame(standard, ((PictureRef)field.getHeaderPicture()).getPicture());
        CatalogForm check = MdClassFactory.eINSTANCE.createCatalogForm();
        check.setForm(stored);
        FormPictureDependencies.Result pictures = FormPictureDependencies.collect(check);
        assertTrue(pictures.found.toString(), pictures.found.isEmpty());
        assertTrue(pictures.unresolved.toString(), pictures.unresolved.isEmpty());
    }

    @Test
    public void testDoNotAttachWhenContainsDoesNotHaveTheNativeFalsePositive()
    {
        falseContains = false;
        assertEquals("notFalseContainment", assertRefused());
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
        when(((IBmObject)storedCopy).bmGetNamespace()).thenReturn(namespace);
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
        assertTrue(assertRefused().startsWith("missingContent(expectedFqn=CatalogForm.Item.Form, registered=null,"));
    }

    @Test
    public void testDoNotAttachWhenOwnerIdsDoNotCollide()
    {
        when(((IBmObject)baseOwner).bmGetId()).thenReturn(9001L);
        assertEquals("ownerIdsOrNamespacesDoNotCollide", assertRefused());
    }

    @Test
    public void testDoNotAttachSourceInTheSameNamespace()
    {
        when(((IBmObject)baseOwner).bmGetNamespace()).thenReturn(namespace);
        assertEquals("ownerIdsOrNamespacesDoNotCollide", assertRefused());
    }

    @Test
    public void testDoNotAttachWhenAnExistingFormHasTheSameName()
    {
        when(((CatalogForm)forms.get(0)).getName()).thenReturn("Item");
        assertTrue(assertRefused().startsWith("duplicateForm("));
    }

    @Test
    public void testDoNotAttachACopyWithTheWrongOrigin()
    {
        when(copy.getExtendedConfigurationObject()).thenReturn(UUID.randomUUID());
        assertEquals("allocationOriginMismatch", assertRefused());
    }

    @Test
    public void testDoNotMoveSecondaryFormContent()
    {
        when(platform.getContainingDataStore((IBmObject)content)).thenReturn(mock(IBmNamespaceDataStore.class));
        assertTrue(assertRefused().startsWith("contentNotPrimary("));
    }

    @Test
    public void testCiRegisteredContentCreatedInThisTransactionHasNoStoreYet() throws Exception
    {
        IBmNamespace ownNamespace = BmCreationTestFixture.namespace();
        IBmPlatformTransaction ownPlatform = BmCreationTestFixture.platform();
        IBmNamespaceDataStore ownStore = BmCreationTestFixture.store();
        when(ownNamespace.getPrimaryStore()).thenReturn(ownStore);
        when(tx.getNamespace()).thenReturn(ownNamespace);
        when(tx.getPlatformTransaction()).thenReturn(ownPlatform);
        when(ownPlatform.getContainingDataStore(any())).thenReturn(ownStore);
        when(((IBmObject)owner).bmGetNamespace()).thenReturn(ownNamespace);
        when(((IBmObject)copy).bmGetNamespace()).thenReturn(ownNamespace);
        when(((IBmObject)content).bmGetNamespace()).thenReturn(ownNamespace);
        when(((IBmObject)snapshot).bmGetNamespace()).thenReturn(ownNamespace);
        when(((IBmObject)content).bmGetId()).thenReturn(103079215104L);
        when(ownPlatform.getContainingDataStore((IBmObject)content)).thenReturn(null);
        when(ownPlatform.getContainingDataStore((IBmObject)snapshot)).thenReturn(null);
        IBmObject created = BmCreationTestFixture.object(tx, 103079215104L, "CREATED");
        IBmObject baseSnapshot = BmCreationTestFixture.object(tx, 3002L, "CREATED");
        when(tx.toTransactionObject((EObject)content)).thenReturn(created);
        when(tx.toTransactionObject((EObject)snapshot)).thenReturn(baseSnapshot);
        when(tx.getTopObjectByFqn("CatalogForm.Item.Form")).thenReturn((IBmObject)content);
        assertSame(copy, AdoptedFormAttachment.complete(tx, source, copy, generator));
        verify(tx).updateTopObjectFqn((IBmObject)content, "Catalog.Goods.Form.Item.Form");
        verify(tx).updateTopObjectFqn((IBmObject)snapshot, "Catalog.Goods.Form.Item.Form.BaseForm");
    }

    @Test
    public void testRegisteredContentFromAnotherNamespaceIsRefusedEvenWithTheSameStoreLabel()
    {
        when(((IBmObject)content).bmGetNamespace()).thenReturn(mock(IBmNamespace.class));
        when(tx.getTopObjectByFqn("CatalogForm.Item.Form")).thenReturn((IBmObject)content);
        assertTrue(assertRefused().startsWith("contentNotPrimary("));
    }

    @Test
    public void testFindNativePrimaryContentByItsAllocationBackReference()
    {
        Form inherited = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)inherited).bmGetId()).thenReturn(103079215104L);
        when(platform.getContainingDataStore((IBmObject)inherited)).thenReturn(mock(IBmNamespaceDataStore.class));
        when(copy.getForm()).thenReturn(inherited);
        when(content.getMdForm()).thenReturn(copy);
        when(((IBmObject)content).bmGetFqn()).thenReturn("Catalog.Goods.Form.Item.Form");
        when(((IBmObject)snapshot).bmGetFqn()).thenReturn("Catalog.Goods.Form.Item.Form.BaseForm");
        assertSame(copy, AdoptedFormAttachment.complete(tx, source, copy, generator));
        verify(copy).setForm(content);
        verify(inherited, never()).setMdForm(any());
    }

    @Test
    public void testForeignContentDeclineIncludesAddressNamespaceAndStore()
    {
        IBmNamespaceDataStore foreign = mock(IBmNamespaceDataStore.class);
        when(foreign.getName()).thenReturn("base-primary");
        when(platform.getContainingDataStore((IBmObject)content)).thenReturn(foreign);
        when(((IBmObject)content).bmGetNamespace()).thenReturn(mock(IBmNamespace.class));
        when(((IBmObject)content).bmGetNamespace().getName()).thenReturn("TestConfiguration");
        String reason = assertRefused();
        assertTrue(reason, reason.contains("expectedFqn=CatalogForm.Item.Form"));
        assertTrue(reason, reason.contains("registered=null"));
        assertTrue(reason, reason.contains("namespace=TestConfiguration, store=base-primary"));
    }

    @Test
    public void testRegisteredPrimaryFormReplacesAnInheritedAllocationReference()
    {
        when(platform.getContainingDataStore((IBmObject)content)).thenReturn(mock(IBmNamespaceDataStore.class));
        Form own = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)own).bmGetId()).thenReturn(3003L);
        when(((IBmObject)own).bmGetNamespace()).thenReturn(namespace);
        when(((IBmObject)own).bmIsTop()).thenReturn(true);
        when(((IBmObject)own).bmGetFqn()).thenReturn("CatalogForm.Item.Form");
        when(tx.getTopObjectByFqn("CatalogForm.Item.Form")).thenReturn((IBmObject)own);
        when(tx.getTopObjectIterator()).thenAnswer(inv -> List.of((IBmObject)own).iterator());
        assertSame(copy, AdoptedFormAttachment.complete(tx, source, copy, generator));
        verify(copy).setForm(own);
        verify(own).setMdForm(copy);
        verify(tx).updateTopObjectFqn((IBmObject)own, "Catalog.Goods.Form.Item.Form");
        verify(content, never()).setMdForm(any());
    }

    @Test
    public void testDoNotUseAnInheritedRegisteredFormWhenOwnContentIsMissing()
    {
        when(source.getForm()).thenReturn(content);
        when(copy.getForm()).thenReturn(null);
        when(platform.getContainingDataStore((IBmObject)content)).thenReturn(mock(IBmNamespaceDataStore.class));
        when(tx.getTopObjectByFqn("CatalogForm.Item.Form")).thenReturn((IBmObject)content);
        assertTrue(assertRefused().startsWith("missingContent(expectedFqn=CatalogForm.Item.Form, registered="));
    }

    @Test
    public void testDeclineIdentifiesTheContentFqnMismatch()
    {
        when(((IBmObject)content).bmGetFqn()).thenReturn("CatalogForm.Other.Form");
        assertEquals("contentFqnMismatch(old=CatalogForm.Other.Form, expected=CatalogForm.Item.Form)", assertRefused());
    }

    @Test
    public void testDeclineIdentifiesMissingExternalTopObjects()
    {
        when(tx.getTopObjectIterator()).thenReturn(List.<IBmObject>of().iterator());
        assertEquals("contentMissingFromTopObjects(CatalogForm.Item.Form)", assertRefused());
    }

    @Test
    public void testDeclineIdentifiesANonAdoptedAllocation()
    {
        when(copy.getObjectBelonging()).thenReturn(ObjectBelonging.NATIVE);
        assertEquals("allocationNotAdopted", assertRefused());
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

    private String assertRefused()
    {
        List<String> reasons = new ArrayList<>();
        assertNull(AdoptedFormAttachment.complete(tx, source, copy, generator, reasons::add));
        assertEquals(1, forms.size());
        verify(tx, never()).updateTopObjectFqn(any(), any());
        assertEquals("each decline must identify exactly one guard", 1, reasons.size());
        return reasons.get(0);
    }
}
