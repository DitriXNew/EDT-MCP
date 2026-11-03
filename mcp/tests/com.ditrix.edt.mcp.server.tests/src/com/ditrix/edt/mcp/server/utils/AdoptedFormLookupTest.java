/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.UUID;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.ecore.EClass;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;

/** Transaction-local identity matching, independent of EDT's global metadata scope. */
public class AdoptedFormLookupTest
{
    private final IBmTransaction tx = mock(IBmTransaction.class);
    private final IBmPlatformTransaction platform = mock(IBmPlatformTransaction.class);
    private final IBmNamespaceDataStore primary = mock(IBmNamespaceDataStore.class);
    private final CatalogForm source = MdClassFactory.eINSTANCE.createCatalogForm();
    private final Catalog baseOwner = MdClassFactory.eINSTANCE.createCatalog();
    private final Catalog owner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));

    @Before
    public void setUp()
    {
        baseOwner.setName("Goods");
        source.setName("Item");
        source.setUuid(UUID.randomUUID());
        baseOwner.getForms().add(source);
        IBmNamespace namespace = mock(IBmNamespace.class);
        when(tx.getNamespace()).thenReturn(namespace);
        when(namespace.getPrimaryStore()).thenReturn(primary);
        when(tx.getPlatformTransaction()).thenReturn(platform);
        when(platform.getContainingDataStore(any())).thenReturn(primary);
        when(tx.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject)owner);
        describe(owner, MdClassPackage.Literals.CATALOG, "Goods", null);
    }

    private void describe(MdObject object, EClass type, String name, UUID link)
    {
        when(object.eClass()).thenReturn(type);
        when(object.getName()).thenReturn(name);
        when(object.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        when(object.getExtendedConfigurationObject()).thenReturn(link);
        when(((IBmObject)object).bmGetId()).thenReturn(100L);
        IBmNamespace namespace = tx.getNamespace();
        when(((IBmObject)object).bmGetNamespace()).thenReturn(namespace);
    }

    private CatalogForm form(String name, UUID link)
    {
        CatalogForm result = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        describe(result, MdClassPackage.Literals.CATALOG_FORM, name, link);
        return result;
    }

    private void forms(CatalogForm... values)
    {
        when(owner.eGet(MdClassPackage.Literals.CATALOG__FORMS)).thenReturn(new BasicEList<>(List.of(values)));
    }

    @Test
    public void testOriginUuidWinsOverAnUnlinkedSameNameAndSupportsRenamedCopies()
    {
        CatalogForm named = form("Item", null);
        CatalogForm linked = form("Renamed", source.getUuid());
        forms(named, linked);
        assertSame(linked, AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testWrongOriginIsNotAcceptedByName()
    {
        forms(form("Item", UUID.randomUUID()));
        assertNull(AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testNameMappingUsesProgrammaticRussianNameWithoutRequiringAnIdLink()
    {
        source.setName("\u0424\u043e\u0440\u043c\u0430");
        source.getSynonym().put("ru", "Displayed form");
        CatalogForm named = form("\u0424\u043e\u0440\u043c\u0430", null);
        forms(form("Displayed form", null), named);
        assertSame(named, AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testOwnExtensionObjectIsNotMistakenForAnAdoptedCopy()
    {
        CatalogForm own = form("Item", null);
        when(own.getObjectBelonging()).thenReturn(ObjectBelonging.NATIVE);
        forms(own);
        assertNull(AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testDetachedAndSecondaryCopiesAreNotAccepted()
    {
        CatalogForm detached = form("Item", source.getUuid());
        when(((IBmObject)detached).bmGetId()).thenReturn(IBmObject.BM_NULL_ID);
        CatalogForm inherited = form("Item", source.getUuid());
        when(platform.getContainingDataStore((IBmObject)inherited)).thenReturn(mock(IBmNamespaceDataStore.class));
        forms(detached, inherited);
        assertNull(AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testAmbiguousIdentityAndNameAreRefused()
    {
        forms(form("Item", source.getUuid()), form("Other", source.getUuid()));
        assertNull(AdoptedFormLookup.find(tx, source));
        forms(form("Item", null), form("ITEM", null));
        assertNull(AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testMissingOwnerOrFormIsUnavailable()
    {
        forms();
        assertNull(AdoptedFormLookup.find(tx, source));
        when(tx.getTopObjectByFqn("Catalog.Goods")).thenReturn(null);
        assertNull(AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testCommonFormUsesItsTransactionalTopObject()
    {
        CommonForm common = MdClassFactory.eINSTANCE.createCommonForm();
        common.setName("Shared");
        common.setUuid(UUID.randomUUID());
        CommonForm copy = mock(CommonForm.class, withSettings().extraInterfaces(IBmObject.class));
        describe(copy, MdClassPackage.Literals.COMMON_FORM, "Shared", common.getUuid());
        when(tx.getTopObjectByFqn("CommonForm.Shared")).thenReturn((IBmObject)copy);
        assertSame(copy, AdoptedFormLookup.find(tx, common));
    }

    @Test
    public void testRenamedOwnerIsMatchedByOriginBeforeWalkingItsForms()
    {
        baseOwner.setUuid(UUID.randomUUID());
        when(tx.getTopObjectByFqn("Catalog.Goods")).thenReturn(null);
        describe(owner, MdClassPackage.Literals.CATALOG, "RenamedGoods", baseOwner.getUuid());
        CatalogForm copy = form("RenamedItem", source.getUuid());
        forms(copy);
        Configuration root = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(root.eClass()).thenReturn(MdClassPackage.Literals.CONFIGURATION);
        when(root.eGet(MdClassPackage.Literals.CONFIGURATION__CATALOGS)).thenReturn(new BasicEList<>(List.of(owner)));
        when(tx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)root);
        assertSame(copy, AdoptedFormLookup.find(tx, source));
        when(owner.getExtendedConfigurationObject()).thenReturn(UUID.randomUUID());
        assertNull(AdoptedFormLookup.find(tx, source));
    }

    @Test
    public void testRenamedCommonFormMustBelongToThePrimaryStore()
    {
        CommonForm common = MdClassFactory.eINSTANCE.createCommonForm();
        common.setName("Shared");
        common.setUuid(UUID.randomUUID());
        CommonForm copy = mock(CommonForm.class, withSettings().extraInterfaces(IBmObject.class));
        describe(copy, MdClassPackage.Literals.COMMON_FORM, "RenamedShared", common.getUuid());
        Configuration root = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(root.eClass()).thenReturn(MdClassPackage.Literals.CONFIGURATION);
        when(root.eGet(MdClassPackage.Literals.CONFIGURATION__COMMON_FORMS)).thenReturn(new BasicEList<>(List.of(copy)));
        when(tx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)root);
        assertSame(copy, AdoptedFormLookup.find(tx, common));
        when(platform.getContainingDataStore((IBmObject)copy)).thenReturn(mock(IBmNamespaceDataStore.class));
        assertNull(AdoptedFormLookup.find(tx, common));
    }
}
