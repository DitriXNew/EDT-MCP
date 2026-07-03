/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.EList;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.utils.ReferenceMembershipWriter.Kind;
import com.google.gson.JsonObject;

/**
 * Tests the pure, BM-independent and UI-independent logic of {@link ReferenceMembershipWriter}: the
 * content op normalization ({@code contentOp}), the per-kind required-interface reject predicate
 * ({@link Kind#accepts}) and the idempotent-present detection ({@code containsByIdentity}). The
 * model-touching apply path (the BM write boundary, the in-transaction re-fetch by {@code bmGetId()},
 * the not-found remove rollback) is covered by the e2e suite against live catalogs / documents.
 *
 * <p>The reject and idempotence assertions use REAL in-memory EMF instances from the
 * {@link MdClassFactory} (the tests bundle is a Fragment-Host, so the mdclass package is on the
 * classpath): a {@link Catalog} is a legal catalog owner but NOT a register; an
 * {@link InformationRegister} is a legal register record but NOT a catalog owner - so each kind's
 * {@link Kind#accepts} predicate is verified against both a matching and a non-matching object, and the
 * live {@link Catalog#getOwners()} EList exercises the identity-based idempotence check.</p>
 */
public class ReferenceMembershipWriterTest
{
    private static JsonObject entryWithOp(String op)
    {
        JsonObject entry = new JsonObject();
        if (op != null)
        {
            entry.addProperty("op", op); //$NON-NLS-1$
        }
        return entry;
    }

    // ---- contentOp --------------------------------------------------------------------------

    @Test
    public void testContentOpDefaultsToAdd()
    {
        assertEquals("add", ReferenceMembershipWriter.contentOp(new JsonObject())); //$NON-NLS-1$
        assertEquals("add", ReferenceMembershipWriter.contentOp(entryWithOp("  "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("add", ReferenceMembershipWriter.contentOp(entryWithOp(null))); //$NON-NLS-1$
    }

    @Test
    public void testContentOpNormalizesCase()
    {
        assertEquals("remove", ReferenceMembershipWriter.contentOp(entryWithOp("REMOVE"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("remove", ReferenceMembershipWriter.contentOp(entryWithOp(" Remove "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("add", ReferenceMembershipWriter.contentOp(entryWithOp("Add"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- Kind.accepts (the required-interface reject predicate) -----------------------------

    @Test
    public void testCatalogOwnersAcceptsCatalogRejectsRegister()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        // A Catalog IS a CatalogOwner (a catalog may be owned by another catalog); an
        // InformationRegister is NOT - only a catalog owner may sit in Catalog.owners.
        assertTrue(Kind.CATALOG_OWNERS.accepts(catalog));
        assertFalse(Kind.CATALOG_OWNERS.accepts(register));
    }

    @Test
    public void testCatalogOwnersRejectsDocument()
    {
        // A Document is NOT a CatalogOwner (Document does not implement the CatalogOwner interface), so
        // it must be rejected as a catalog owner - this pins the false Document/Task/BusinessProcess
        // wording that the reject hint used to (wrongly) advertise as valid owner kinds.
        Document document = MdClassFactory.eINSTANCE.createDocument();
        assertFalse("a Document is not a legal catalog owner", //$NON-NLS-1$
            Kind.CATALOG_OWNERS.accepts(document));
    }

    @Test
    public void testCatalogOwnersHintNamesOnlyRealOwnerKinds()
    {
        // The reject hint must list only the real CatalogOwner kinds (Catalog / ChartOfCharacteristicTypes
        // / ChartOfAccounts / ChartOfCalculationTypes / ExchangePlan) and must NOT advertise a Document,
        // Task or BusinessProcess (none of which implement CatalogOwner) as a valid owner.
        String hint = Kind.CATALOG_OWNERS.requiredKindHint();
        assertFalse("the owner hint must not name a Document as a valid owner", //$NON-NLS-1$
            hint.contains("Document")); //$NON-NLS-1$
        assertFalse("the owner hint must not name a Task as a valid owner", //$NON-NLS-1$
            hint.contains("Task")); //$NON-NLS-1$
        assertFalse("the owner hint must not name a BusinessProcess as a valid owner", //$NON-NLS-1$
            hint.contains("BusinessProcess")); //$NON-NLS-1$
        assertTrue("the owner hint must name ChartOfAccounts as a valid owner", //$NON-NLS-1$
            hint.contains("ChartOfAccounts")); //$NON-NLS-1$
        assertTrue("the owner hint must name ChartOfCalculationTypes as a valid owner", //$NON-NLS-1$
            hint.contains("ChartOfCalculationTypes")); //$NON-NLS-1$
    }

    @Test
    public void testRegisterRecordsAcceptsRegisterRejectsCatalog()
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        // An InformationRegister IS a BasicRegister (a valid document register record); a Catalog is
        // NOT - only a register may sit in Document.registerRecords.
        assertTrue(Kind.DOCUMENT_REGISTER_RECORDS.accepts(register));
        assertFalse(Kind.DOCUMENT_REGISTER_RECORDS.accepts(catalog));
    }

    @Test
    public void testAcceptsRejectsNull()
    {
        assertFalse(Kind.CATALOG_OWNERS.accepts(null));
        assertFalse(Kind.DOCUMENT_REGISTER_RECORDS.accepts(null));
    }

    // ---- idempotent-present detection (containsByIdentity over the live EList) ---------------

    @Test
    public void testContainsByIdentityDetectsPresentOwner()
    {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
        Catalog other = MdClassFactory.eINSTANCE.createCatalog();
        EList<MdObject> owners = Kind.CATALOG_OWNERS.list(MdClassFactory.eINSTANCE.createCatalog());
        assertFalse("empty list contains nothing", //$NON-NLS-1$
            ReferenceMembershipWriter.containsByIdentity(owners, owner));

        owners.add(owner);
        // Present by identity -> an add would be a no-op (idempotent, added stays 0).
        assertTrue("the added owner is detected by identity", //$NON-NLS-1$
            ReferenceMembershipWriter.containsByIdentity(owners, owner));
        // A different instance is NOT present even though it is the same kind.
        assertFalse("a distinct instance is not present by identity", //$NON-NLS-1$
            ReferenceMembershipWriter.containsByIdentity(owners, other));
    }

    @Test
    public void testListAccessorReturnsLiveList()
    {
        Document document = MdClassFactory.eINSTANCE.createDocument();
        EList<MdObject> records = Kind.DOCUMENT_REGISTER_RECORDS.list(document);
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        records.add(register);
        // The accessor returns the LIVE EList - a mutation through it is visible on the model object.
        assertEquals(1, document.getRegisterRecords().size());
        assertSame(register, document.getRegisterRecords().get(0));
    }

    // ---- member labels (message wording) ----------------------------------------------------

    @Test
    public void testMemberLabels()
    {
        assertEquals("owner", Kind.CATALOG_OWNERS.memberLabel()); //$NON-NLS-1$
        assertEquals("register record", Kind.DOCUMENT_REGISTER_RECORDS.memberLabel()); //$NON-NLS-1$
    }
}
