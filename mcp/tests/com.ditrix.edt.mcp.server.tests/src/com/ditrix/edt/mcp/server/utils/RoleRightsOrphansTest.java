/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com.ditrix.edt.mcp.server.utils.RoleRightsOrphans.Judgement;
import com.ditrix.edt.mcp.server.utils.RoleRightsOrphans.Scan;
import com.ditrix.edt.mcp.server.utils.RoleRightsOrphans.Verdict;

/**
 * Tests the three-way verdict of {@link RoleRightsOrphans} against a mocked transaction holding
 * real (detached) metadata objects: a target that resolves is present, a proven-gone one is absent,
 * and anything this code cannot prove - a blocker, a stale link, an unknown kind, a case-only
 * match - is undetermined and never removed.
 *
 * <p>Russian tokens are built from code points so a non-UTF-8 build cannot corrupt them.</p>
 */
public class RoleRightsOrphansTest
{
    /** "Справочник" (Catalog). */
    private static final String RU_CATALOG = fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447,
        0x043d, 0x0438, 0x043a);
    /** "Товары" (a Russian object Name). */
    private static final String RU_GOODS = fromCp(0x0422, 0x043e, 0x0432, 0x0430, 0x0440, 0x044b);
    /** "Реквизит" (Attribute). */
    private static final String RU_ATTRIBUTE = fromCp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438,
        0x0442);

    private IBmTransaction tx;

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    @Before
    public void setUp()
    {
        tx = mock(IBmTransaction.class);
        when(tx.getTopObjectsByFqnIgnoreCase(anyString())).thenReturn(Collections.emptyIterator());
    }

    // ==================== addressOf: what a proxy still names ====================

    @Test
    public void testAddressOfUnresolvedScheme()
    {
        assertEquals("Catalog.Gone", RoleRightsOrphans.addressOf(URI.createURI("unresolved:/Catalog.Gone"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAddressOfLazyLinkFragment()
    {
        URI uri = URI.createURI("bm://P/Role.R.Rights#lazyLink_1::Catalog.Gone.Attribute.A"); //$NON-NLS-1$
        assertEquals("Catalog.Gone.Attribute.A", RoleRightsOrphans.addressOf(uri)); //$NON-NLS-1$
    }

    @Test
    public void testAddressOfNothingReadable()
    {
        assertNull(RoleRightsOrphans.addressOf(null));
        assertNull(RoleRightsOrphans.addressOf(URI.createURI("unresolved:/"))); //$NON-NLS-1$
        assertNull(RoleRightsOrphans.addressOf(URI.createURI("platform:/resource/x#/1"))); //$NON-NLS-1$
    }

    @Test
    public void testFieldNameOfTheLiveDbViewFieldProxy()
    {
        // The exact URI EDT 2026.2.1 left on an RLS field after its attribute was deleted.
        URI uri = URI.createURI("bm://TestConfiguration/Catalog.OrphA#/dbViewDefs/mainView/fields:AttrA"); //$NON-NLS-1$
        assertEquals("AttrA", RoleRightsOrphans.fieldNameOf(uri)); //$NON-NLS-1$
        assertNull(RoleRightsOrphans.fieldNameOf(URI.createURI("bm://P/Catalog.X#/attributes/1"))); //$NON-NLS-1$
        assertNull(RoleRightsOrphans.fieldNameOf(null));
    }

    // ==================== judge: the three verdicts ====================

    @Test
    public void testLiveTargetIsPresent()
    {
        assertEquals(Verdict.PRESENT, RoleRightsOrphans.judge(tx, MdClassFactory.eINSTANCE.createCatalog(), null).verdict);
    }

    @Test
    public void testDeletedTopObjectIsAbsent()
    {
        assertEquals(Verdict.ABSENT, RoleRightsOrphans.judge(tx, proxy("Catalog.Gone"), null).verdict); //$NON-NLS-1$
    }

    @Test
    public void testDeletedObjectWithRussianTypeTokenAndNameIsAbsent()
    {
        // Only the type token is bilingual; the Name is the programmatic Name, in either script.
        assertEquals(Verdict.ABSENT,
            RoleRightsOrphans.judge(tx, proxy(RU_CATALOG + '.' + RU_GOODS), null).verdict);
    }

    @Test
    public void testABlockerTurnsAbsentIntoUndetermined()
    {
        Judgement judgement = RoleRightsOrphans.judge(tx, proxy("Catalog.Gone"), "model not ready"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Verdict.UNDETERMINED, judgement.verdict);
        assertEquals("model not ready", judgement.reason); //$NON-NLS-1$
    }

    @Test
    public void testExistingTargetBehindAStaleProxyIsUndeterminedNotAbsent()
    {
        topObject("Catalog.Products", catalog("Products")); //$NON-NLS-1$ //$NON-NLS-2$
        Judgement judgement = RoleRightsOrphans.judge(tx, proxy("Catalog.Products"), null); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED, judgement.verdict);
        assertTrue(judgement.reason, judgement.reason.contains("clean_project")); //$NON-NLS-1$
    }

    @Test
    public void testExistingRussianNamedTargetIsNotJudgedAbsent()
    {
        topObject("Catalog." + RU_GOODS, catalog(RU_GOODS)); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED,
            RoleRightsOrphans.judge(tx, proxy(RU_CATALOG + '.' + RU_GOODS), null).verdict);
    }

    @Test
    public void testCaseOnlyMatchOfATopObjectIsUndetermined()
    {
        IBmObject other = (IBmObject)catalog("GONE"); //$NON-NLS-1$
        when(tx.getTopObjectsByFqnIgnoreCase("Catalog.Gone")).thenReturn(List.of(other).iterator()); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judge(tx, proxy("Catalog.Gone"), null).verdict); //$NON-NLS-1$
    }

    @Test
    public void testDeletedAttributeOfALiveObjectIsAbsent()
    {
        topObject("Catalog.Products", catalog("Products", "Price")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Verdict.ABSENT,
            RoleRightsOrphans.judge(tx, proxy("Catalog.Products.Attribute.Weight"), null).verdict); //$NON-NLS-1$
    }

    @Test
    public void testDeletedAttributeAddressedWithRussianKindTokenIsAbsent()
    {
        topObject("Catalog.Products", catalog("Products", "Price")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Verdict.ABSENT,
            RoleRightsOrphans.judge(tx, proxy("Catalog.Products." + RU_ATTRIBUTE + ".Weight"), null).verdict); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLiveAttributeBehindAStaleProxyIsUndetermined()
    {
        topObject("Catalog.Products", catalog("Products", "Price")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Verdict.UNDETERMINED,
            RoleRightsOrphans.judge(tx, proxy("Catalog.Products.Attribute.Price"), null).verdict); //$NON-NLS-1$
    }

    @Test
    public void testCaseOnlyAttributeMatchIsUndetermined()
    {
        topObject("Catalog.Products", catalog("Products", "price")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Verdict.ABSENT, RoleRightsOrphans.judgeAddress(tx, "Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, "Catalog.Products.Attribute.Price")); //$NON-NLS-1$
    }

    @Test
    public void testUnwalkableAddressesAreUndetermined()
    {
        topObject("Catalog.Products", catalog("Products")); //$NON-NLS-1$ //$NON-NLS-2$
        // A kind this code cannot walk, an unknown type, the configuration itself, a malformed arity.
        assertEquals(Verdict.UNDETERMINED,
            RoleRightsOrphans.judgeAddress(tx, "Catalog.Products.StandardAttribute.Code")); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, "NoSuchType.X")); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, "Configuration.Main")); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, "Catalog.Products.Attribute")); //$NON-NLS-1$
    }

    @Test
    public void testAProxyWithoutAnAddressIsUndetermined()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        ((InternalEObject)catalog).eSetProxyURI(URI.createURI("platform:/resource/x#/1")); //$NON-NLS-1$
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judge(tx, catalog, null).verdict);
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judge(tx, null, null).verdict);
    }

    // ==================== scan: only ABSENT is removed ====================

    @Test
    public void testScanReportsAndRemovesOnlyProvenAbsentEntries()
    {
        topObject("Catalog.Products", catalog("Products")); //$NON-NLS-1$ //$NON-NLS-2$
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights live = entry(MdClassFactory.eINSTANCE.createCatalog(), false);
        ObjectRights gone = entry(proxy("Catalog.Gone"), true); //$NON-NLS-1$
        ObjectRights stale = entry(proxy("Catalog.Products"), false); //$NON-NLS-1$
        description.getRights().add(live);
        description.getRights().add(gone);
        description.getRights().add(stale);
        roles(role(description));

        Scan report = RoleRightsOrphans.scan(tx, true, true, false);
        assertEquals(1, report.rolesScanned);
        assertEquals(1, report.count(Verdict.ABSENT));
        assertEquals(1, report.count(Verdict.UNDETERMINED));
        assertEquals("a report must not change the role", 3, description.getRights().size()); //$NON-NLS-1$
        assertTrue(report.removed.isEmpty());
        assertEquals("Catalog.Gone", report.entries.get(0).target); //$NON-NLS-1$
        assertTrue(report.entries.get(0).hasRls);
        assertEquals(List.of("Read=allowed"), report.entries.get(0).rights); //$NON-NLS-1$

        roles(role(description));
        Scan cleaned = RoleRightsOrphans.scan(tx, true, true, true);
        assertEquals(1, cleaned.removed.size());
        assertEquals(List.of(live, stale), description.getRights());
    }

    @Test
    public void testNothingIsRemovedWhenTheModelIsNotReadyOrInAnExtension()
    {
        for (boolean[] flags : new boolean[][] {{false, true}, {true, false}})
        {
            RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
            description.getRights().add(entry(proxy("Catalog.Gone"), false)); //$NON-NLS-1$
            roles(role(description));
            Scan scan = RoleRightsOrphans.scan(tx, flags[0], flags[1], true);
            assertEquals(0, scan.count(Verdict.ABSENT));
            assertEquals(1, scan.count(Verdict.UNDETERMINED));
            assertEquals(1, description.getRights().size());
        }
    }

    @Test
    public void testAnEntryWithNoTargetIsRemovedAndTheRoleBecomesEditable()
    {
        // In an extension too: a null target secures nothing, whatever project it sits in.
        for (boolean baseConfiguration : new boolean[] {true, false})
        {
            RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
            description.getRights().add(entry(null, false));
            roles(role(description));
            Scan report = RoleRightsOrphans.scan(tx, true, baseConfiguration, false);
            assertEquals(1, report.count(Verdict.ABSENT));
            assertEquals(RoleRightsOrphans.NO_TARGET, report.entries.get(0).target);
            assertEquals(RoleRightsOrphans.NO_TARGET_REASON, report.entries.get(0).reason);
            assertEquals(List.of(RoleRightsOrphans.NO_TARGET), RoleRightsWriter.unresolvedTargets(description));

            roles(role(description));
            Scan cleaned = RoleRightsOrphans.scan(tx, true, baseConfiguration, true);
            assertEquals(1, cleaned.removed.size());
            assertTrue(description.getRights().isEmpty());
            assertTrue("the edit preflight must pass once the entry is gone", //$NON-NLS-1$
                RoleRightsWriter.unresolvedTargets(description).isEmpty());
        }
    }

    @Test
    public void testAnEntryWithNoTargetIsKeptWhileTheModelIsIncomplete()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(entry(null, false));
        roles(role(description));
        Scan scan = RoleRightsOrphans.scan(tx, false, true, true);
        assertEquals(1, scan.count(Verdict.UNDETERMINED));
        assertEquals(RoleRightsOrphans.NOT_READY_REASON, scan.entries.get(0).reason);
        assertEquals(1, description.getRights().size());
    }

    @Test
    public void testTheVerdictWaitsForTheModelGateNotForValidation()
    {
        // The gate is the write tools' model-readiness probe; its ignoring of validation is pinned
        // in ProjectStateCheckerTest (modelDataIsComputedWhenItsSegmentsAre).
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("P"); //$NON-NLS-1$
        assertEquals(ProjectStateChecker.modelBuildingErrorOrNull(project) == null,
            RoleRightsOrphans.MODEL_READY.test(project));
    }

    // ==================== helpers ====================

    private static EObject proxy(String address)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        ((InternalEObject)catalog).eSetProxyURI(URI.createURI("unresolved:/" + address)); //$NON-NLS-1$
        return catalog;
    }

    private static Catalog catalog(String name, String... attributes)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        for (String attributeName : attributes)
        {
            CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
            attribute.setName(attributeName);
            catalog.getAttributes().add(attribute);
        }
        return catalog;
    }

    private void topObject(String fqn, Catalog catalog)
    {
        when(tx.getTopObjectByFqn(fqn)).thenReturn((IBmObject)catalog);
    }

    private void roles(Role role)
    {
        when(tx.getTopObjectIterator(MdClassPackage.Literals.ROLE)).thenReturn(List.of((IBmObject)role).iterator());
    }

    private static Role role(RoleDescription description)
    {
        Role role = MdClassFactory.eINSTANCE.createRole();
        role.setName("R"); //$NON-NLS-1$
        role.setRights(description);
        return role;
    }

    private static ObjectRights entry(EObject target, boolean withRls)
    {
        ObjectRights objectRights = RightsFactory.eINSTANCE.createObjectRights();
        objectRights.setObject(target);
        Right right = RightsFactory.eINSTANCE.createRight();
        right.setName("Read"); //$NON-NLS-1$
        ObjectRight objectRight = RightsFactory.eINSTANCE.createObjectRight();
        objectRight.setRight(right);
        objectRight.setValue(RightValue.SET);
        if (withRls)
        {
            Rls rls = RightsFactory.eINSTANCE.createRls();
            rls.setCondition("WHERE TRUE"); //$NON-NLS-1$
            objectRight.getRestrictionsByCondition().add(rls);
        }
        objectRights.getRights().add(objectRight);
        return objectRights;
    }
}
