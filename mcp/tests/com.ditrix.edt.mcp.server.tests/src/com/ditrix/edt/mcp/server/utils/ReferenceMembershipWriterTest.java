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
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.ReferenceMembershipWriter.Kind;
import com.google.gson.JsonObject;

/**
 * Tests the pure, BM-independent and UI-independent logic of {@link ReferenceMembershipWriter}: the
 * content op normalization ({@code contentOp}), the per-kind membership reject predicate
 * ({@link Kind#accepts}) and the idempotent-present detection ({@code containsByIdentity}). The
 * model-touching apply path (the BM write boundary, the in-transaction re-fetch by {@code bmGetId()},
 * the not-found remove rollback) is covered by the e2e suite against live catalogs / documents /
 * subsystems.
 *
 * <p>The reject and idempotence assertions use REAL in-memory EMF instances from the
 * {@link MdClassFactory} (the tests bundle is a Fragment-Host, so the mdclass package is on the
 * classpath): a {@link Catalog} is a legal catalog owner but NOT a register; an
 * {@link InformationRegister} is a legal register record but NOT a catalog owner; a {@link Constant} is a
 * legal subsystem content object while a {@link Subsystem} is NOT (the hard self-exclusion) - so each
 * kind's {@link Kind#accepts} predicate is verified against both matching and non-matching objects, and
 * the live {@link Catalog#getOwners()} / {@link Subsystem#getContent()} ELists exercise the
 * identity-based idempotence check.</p>
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
        assertFalse(Kind.SUBSYSTEM_CONTENT.accepts(null));
    }

    // ---- Kind.SUBSYSTEM_CONTENT (the allow-list reject predicate) ----------------------------

    @Test
    public void testSubsystemContentAcceptsAllowListedObjects()
    {
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        // A Constant, a Catalog and an InformationRegister are all top-level configuration objects that
        // may be included in a subsystem's content (the broad allow-list).
        assertTrue("a Constant is a legal subsystem content object", //$NON-NLS-1$
            Kind.SUBSYSTEM_CONTENT.accepts(constant));
        assertTrue("a Catalog is a legal subsystem content object", //$NON-NLS-1$
            Kind.SUBSYSTEM_CONTENT.accepts(catalog));
        assertTrue("an InformationRegister is a legal subsystem content object", //$NON-NLS-1$
            Kind.SUBSYSTEM_CONTENT.accepts(register));
    }

    @Test
    public void testSubsystemContentRejectsSubsystem()
    {
        // A Subsystem is NOT a content member (a nested subsystem is a child of Subsystem.getSubsystems(),
        // never a content reference). This exclusion is a hard rule independent of the allow-list.
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        assertFalse("a Subsystem must never be a subsystem content member", //$NON-NLS-1$
            Kind.SUBSYSTEM_CONTENT.accepts(subsystem));
    }

    @Test
    public void testSubsystemContentRejectsOutOfListKind()
    {
        // A Configuration is a metadata object but NOT a top-level object that may sit in a subsystem's
        // content, so it is rejected up front by the allow-list.
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        assertFalse("a Configuration is not a subsystem content object", //$NON-NLS-1$
            Kind.SUBSYSTEM_CONTENT.accepts(configuration));
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

    @Test
    public void testSubsystemContentIdempotenceOverLiveList()
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        Constant other = MdClassFactory.eINSTANCE.createConstant();
        EList<MdObject> content = Kind.SUBSYSTEM_CONTENT.list(subsystem);
        assertFalse("empty content contains nothing", //$NON-NLS-1$
            ReferenceMembershipWriter.containsByIdentity(content, constant));

        // Add: the object is now present by identity -> a second add would be a no-op (idempotent).
        content.add(constant);
        assertTrue("the added content object is detected by identity", //$NON-NLS-1$
            ReferenceMembershipWriter.containsByIdentity(content, constant));
        assertFalse("a distinct instance is not present by identity", //$NON-NLS-1$
            ReferenceMembershipWriter.containsByIdentity(content, other));
        // The accessor returns the LIVE EList - the mutation is visible on the model object.
        assertEquals(1, subsystem.getContent().size());
        assertSame(constant, subsystem.getContent().get(0));

        // Remove: the object is gone and no longer detected by identity.
        content.remove(constant);
        assertEquals(0, subsystem.getContent().size());
        assertFalse("the removed content object is no longer present", //$NON-NLS-1$
            ReferenceMembershipWriter.containsByIdentity(content, constant));
    }

    // ---- member labels (message wording) ----------------------------------------------------

    @Test
    public void testMemberLabels()
    {
        assertEquals("owner", Kind.CATALOG_OWNERS.memberLabel()); //$NON-NLS-1$
        assertEquals("register record", Kind.DOCUMENT_REGISTER_RECORDS.memberLabel()); //$NON-NLS-1$
        assertEquals("content object", Kind.SUBSYSTEM_CONTENT.memberLabel()); //$NON-NLS-1$
    }
}
