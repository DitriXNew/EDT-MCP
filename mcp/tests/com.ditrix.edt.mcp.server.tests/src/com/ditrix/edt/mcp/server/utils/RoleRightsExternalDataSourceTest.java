/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.util.Collections;
import java.util.List;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.*;
import com.ditrix.edt.mcp.server.utils.RoleRightsOrphans.Verdict;

/** Rights addressing and removal of proven EDS orphans are intentional grammar consumers (#448). */
public class RoleRightsExternalDataSourceTest
{
    @Test public void writerResolvesTableAndFieldAndKeepsValidRights()
    {
        var f = new ExternalDataSourceFixtures();
        assertSame(f.table, RoleRightsWriter.resolveObject(f.config, TABLE));
        assertSame(f.field, RoleRightsWriter.resolveObject(f.config, FIELD));
        assertSame(f.field, RoleRightsWriter.resolveObject(f.config,
            RU_EDS + ".Eds." + RU_TABLE + ".T." + RU_FIELD + ".F"));
        var right = RightsFactory.eINSTANCE.createRight();
        right.setName("Read");
        IRightInfosService infos = mock(IRightInfosService.class);
        when(infos.getRights(f.table)).thenReturn(java.util.Set.of(right));
        assertSame(right, RoleRightsWriter.resolveRight(infos, f.table, "Read"));
    }

    @Test public void readerPrintsCanonicalEdsMemberAddresses()
    {
        var f = new ExternalDataSourceFixtures();
        RoleDescription rights = RightsFactory.eINSTANCE.createRoleDescription();
        rights.getRights().add(entry(f.table));
        rights.getRights().add(entry(f.field));
        String text = RoleRightsReader.render("Role.R", rights, true, "en", 0);
        assertTrue(text, text.contains(TABLE));
        assertTrue(text, text.contains(FIELD));
    }

    @Test public void orphanAddressVerdictsBecomePresentOrAbsent()
    {
        var tx = tx();
        assertEquals(Verdict.PRESENT, RoleRightsOrphans.judgeAddress(tx, TABLE));
        assertEquals(Verdict.PRESENT, RoleRightsOrphans.judgeAddress(tx, FIELD));
        assertEquals(Verdict.ABSENT, RoleRightsOrphans.judgeAddress(tx, EDS + ".Table.Gone"));
        assertEquals(Verdict.ABSENT, RoleRightsOrphans.judgeAddress(tx, TABLE + ".Field.Gone"));
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, EDS + ".Table.t"));
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judge(tx, proxy(TABLE), null).verdict);
        for (String malformed : List.of(TABLE + ".Field.Gone.", EDS + ".Table..Field.F",
            TABLE + ".Field. Gone"))
        {
            assertEquals(malformed, Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, malformed));
        }
        assertEquals(Verdict.UNDETERMINED,
            RoleRightsOrphans.judge(tx, proxy(TABLE + ".Field.Gone"), "model not ready").verdict);
    }

    @Test public void anUnresolvedTableInTheWalkedListKeepsAMissingTableUndetermined()
    {
        // An unresolved element has no readable name: it may be the addressed table, so deleting the
        // right would act on a guess.
        var f = new ExternalDataSourceFixtures();
        var unresolved = MdClassFactory.eINSTANCE.createTable();
        ((InternalEObject)unresolved).eSetProxyURI(URI.createURI("bm://TestConfiguration/" + EDS + ".Table.Gone"));
        f.eds.getTables().add(unresolved);
        var tx = tx(f);
        assertEquals(Verdict.PRESENT, RoleRightsOrphans.judgeAddress(tx, TABLE));
        assertEquals(Verdict.PRESENT, RoleRightsOrphans.judgeAddress(tx, FIELD));
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, EDS + ".Table.Gone"));
        assertEquals(Verdict.UNDETERMINED, RoleRightsOrphans.judgeAddress(tx, EDS + ".Table.Other"));
        assertEquals(Verdict.ABSENT, RoleRightsOrphans.judgeAddress(tx, TABLE + ".Field.Gone"));
    }

    @Test public void cleanupRemovesOnlyProvenMissingEdsRights()
    {
        var tx = tx();
        RoleDescription rights = RightsFactory.eINSTANCE.createRoleDescription();
        var liveButStale = entry(proxy(FIELD));
        var goneField = entry(proxy(TABLE + ".Field.Gone"));
        var goneTable = entry(proxy(EDS + ".Table.Gone"));
        rights.getRights().addAll(List.of(liveButStale, goneField, goneTable));
        var role = mock(Role.class, withSettings().extraInterfaces(IBmObject.class));
        when(role.getRights()).thenReturn(rights);
        when(role.getObjectBelonging()).thenReturn(ObjectBelonging.NATIVE);
        when(((IBmObject)role).bmGetFqn()).thenReturn("Role.R");
        when(((IBmObject)role).bmIsTop()).thenReturn(true);
        when(tx.getTopObjectIterator(MdClassPackage.Literals.ROLE)).thenReturn(List.of((IBmObject)role).iterator());
        var scan = RoleRightsOrphans.scan(tx, true, true, true);
        assertEquals(2, scan.removed.size());
        assertEquals(List.of(liveButStale), rights.getRights());
        assertEquals(Verdict.UNDETERMINED, scan.entries.get(0).verdict);
    }

    private static IBmTransaction tx()
    {
        return tx(new ExternalDataSourceFixtures());
    }

    private static IBmTransaction tx(ExternalDataSourceFixtures f)
    {
        var tx = mock(IBmTransaction.class);
        var eds = mock(ExternalDataSource.class, withSettings().extraInterfaces(IBmObject.class));
        when(eds.eClass()).thenReturn(MdClassPackage.Literals.EXTERNAL_DATA_SOURCE);
        when(eds.eGet(MdClassPackage.Literals.EXTERNAL_DATA_SOURCE__TABLES)).thenReturn(f.eds.getTables());
        when(tx.getTopObjectByFqn(EDS)).thenReturn((IBmObject)eds);
        when(tx.getTopObjectsByFqnIgnoreCase(anyString())).thenReturn(Collections.emptyIterator());
        return tx;
    }

    private static EObject proxy(String fqn)
    {
        var value = MdClassFactory.eINSTANCE.createTable();
        ((InternalEObject)value).eSetProxyURI(URI.createURI("unresolved:/" + fqn));
        return value;
    }

    private static ObjectRights entry(EObject target)
    {
        var value = RightsFactory.eINSTANCE.createObjectRights();
        value.setObject(target);
        var right = RightsFactory.eINSTANCE.createRight();
        right.setName("Read");
        var cell = RightsFactory.eINSTANCE.createObjectRight();
        cell.setRight(right);
        cell.setValue(RightValue.SET);
        value.getRights().add(cell);
        return value;
    }
}
