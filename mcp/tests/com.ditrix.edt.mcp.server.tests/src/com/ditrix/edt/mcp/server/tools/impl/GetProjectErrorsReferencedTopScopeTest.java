/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures;
import com.ditrix.edt.mcp.server.utils.MarkerRows;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/** Exact error scopes follow the shared referenced-top file ownership rule (PR #812). */
public class GetProjectErrorsReferencedTopScopeTest
{
    @Test
    public void tableRequestExcludesSourceSiblingTablesCubesAndFunctions()
    {
        var fixture = new ExternalDataSourceFixtures();
        var sibling = named(MdClassFactory.eINSTANCE.createTable(), "T2"); //$NON-NLS-1$
        fixture.eds.getTables().add(sibling);
        sibling.setParentDataSource(fixture.eds);
        Set<String> scope = resolvedScope(fixture.scope(), TABLE);
        assertEquals(Collections.singleton(TABLE), scope);
        assertNull(GetProjectErrorsTool.markerOwnerFqn(TABLE));
        assertMarkerIncluded(scope, TABLE);
        assertMarkerIncluded(scope, TABLE + ".ManagerModule"); //$NON-NLS-1$
        assertMarkerExcluded(scope, EDS + ".Table.T2"); //$NON-NLS-1$
        assertMarkerExcluded(scope, EDS);
        assertMarkerExcluded(scope, EDS + ".ManagerModule"); //$NON-NLS-1$
        assertMarkerExcluded(scope, CUBE);
        assertMarkerExcluded(scope, FUNCTION);
    }

    @Test
    public void cubeRequestExcludesSourceSiblingCubesTablesAndFunctions()
    {
        var fixture = new ExternalDataSourceFixtures();
        Set<String> scope = resolvedScope(fixture.scope(), CUBE);
        assertEquals(Collections.singleton(CUBE), scope);
        assertNull(GetProjectErrorsTool.markerOwnerFqn(CUBE));
        assertMarkerIncluded(scope, CUBE);
        assertMarkerIncluded(scope, CUBE + ".RecordSetModule"); //$NON-NLS-1$
        assertMarkerExcluded(scope, EDS + ".Cube.C2"); //$NON-NLS-1$
        assertMarkerExcluded(scope, EDS);
        assertMarkerExcluded(scope, TABLE);
        assertMarkerExcluded(scope, FUNCTION);
    }

    @Test
    public void fieldRequestScopesToItsTableIncludingOtherContainedMarkers()
    {
        var fixture = new ExternalDataSourceFixtures();
        Set<String> scope = resolvedScope(fixture.scope(), FIELD);
        assertEquals(new LinkedHashSet<>(Arrays.asList(FIELD, TABLE)), scope);
        assertEquals(TABLE, GetProjectErrorsTool.markerOwnerFqn(FIELD));
        assertMarkerIncluded(scope, TABLE);
        assertMarkerIncluded(scope, FIELD);
        assertMarkerIncluded(scope, TABLE + ".Field.Other"); //$NON-NLS-1$
        assertMarkerIncluded(scope, TABLE + ".ManagerModule"); //$NON-NLS-1$
        assertMarkerExcluded(scope, EDS);
        assertMarkerExcluded(scope, EDS + ".Table.T2"); //$NON-NLS-1$
        assertMarkerExcluded(scope, CUBE);
        assertMarkerExcluded(scope, FUNCTION);
    }

    @Test
    public void otherContainedMembersKeepTheirReferencedTopOwner()
    {
        assertEquals(TABLE, GetProjectErrorsTool.markerOwnerFqn(TABLE + ".Command.Run")); //$NON-NLS-1$
        assertEquals(TABLE, GetProjectErrorsTool.markerOwnerFqn(TABLE + ".Template.Layout")); //$NON-NLS-1$
        assertEquals(CUBE, GetProjectErrorsTool.markerOwnerFqn(CUBE + ".Command.Run")); //$NON-NLS-1$
    }

    @Test
    public void russianTableAndFieldRequestsKeepTheirTableScope()
    {
        var fixture = new ExternalDataSourceFixtures();
        String table = RU_EDS + ".Eds." + RU_TABLE + ".T"; //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> tableScope = resolvedScope(fixture.scope(), table);
        assertEquals(Collections.singleton(MetadataTypeUtils.normalizeFqn(table)), tableScope);
        assertMarkerIncluded(tableScope, TABLE);
        assertMarkerExcluded(tableScope, EDS);
        assertMarkerExcluded(tableScope, EDS + ".Table.T2"); //$NON-NLS-1$
        String field = table + '.' + RU_FIELD + ".F"; //$NON-NLS-1$
        Set<String> fieldScope = resolvedScope(fixture.scope(), field);
        assertEquals(new LinkedHashSet<>(Arrays.asList(MetadataTypeUtils.normalizeFqn(field),
            MetadataTypeUtils.normalizeFqn(table))), fieldScope);
        assertMarkerIncluded(fieldScope, TABLE);
        assertMarkerExcluded(fieldScope, EDS);
        assertMarkerExcluded(fieldScope, EDS + ".Table.T2"); //$NON-NLS-1$
    }

    @Test
    public void pluralAndMixedCaseTableRequestsKeepTheirTableScope()
    {
        var fixture = new ExternalDataSourceFixtures();
        String table = EDS + ".tAbLeS.T"; //$NON-NLS-1$
        Set<String> scope = resolvedScope(fixture.scope(), table);
        assertEquals(Collections.singleton(table), scope);
        assertMarkerIncluded(scope, TABLE);
        assertMarkerExcluded(scope, EDS);
        assertMarkerExcluded(scope, EDS + ".Table.T2"); //$NON-NLS-1$
    }

    @Test
    public void functionRemainsContainedInTheSource()
    {
        var fixture = new ExternalDataSourceFixtures();
        Set<String> scope = resolvedScope(fixture.scope(), FUNCTION);
        assertEquals(new LinkedHashSet<>(Arrays.asList(FUNCTION, EDS)), scope);
        assertEquals(EDS, GetProjectErrorsTool.markerOwnerFqn(FUNCTION));
        assertMarkerIncluded(scope, EDS);
    }

    @Test
    public void sourceRequestStillIncludesAllItsMembers()
    {
        var fixture = new ExternalDataSourceFixtures();
        Set<String> scope = resolvedScope(fixture.scope(), EDS);
        assertEquals(Collections.singleton(EDS), scope);
        assertMarkerIncluded(scope, EDS);
        assertMarkerIncluded(scope, TABLE);
        assertMarkerIncluded(scope, CUBE);
        assertMarkerIncluded(scope, FUNCTION);
    }

    @Test
    public void catalogAttributeScopeRemainsIdentical()
    {
        var fixture = new ExternalDataSourceFixtures();
        var catalog = named(MdClassFactory.eINSTANCE.createCatalog(), "Products"); //$NON-NLS-1$
        catalog.getAttributes().add(named(MdClassFactory.eINSTANCE.createCatalogAttribute(), "Weight")); //$NON-NLS-1$
        fixture.config.getCatalogs().add(catalog);
        String attribute = "Catalog.Products.Attribute.Weight"; //$NON-NLS-1$
        Set<String> scope = resolvedScope(fixture.scope(), attribute);
        assertEquals(new LinkedHashSet<>(Arrays.asList(attribute, "Catalog.Products")), scope); //$NON-NLS-1$
        assertMarkerIncluded(scope, "Catalog.Products"); //$NON-NLS-1$
        assertMarkerIncluded(scope, "Catalog.Products.Attribute.Other"); //$NON-NLS-1$
        assertMarkerExcluded(scope, "Catalog.Products2"); //$NON-NLS-1$
    }

    @Test
    public void existingOwnerAndRootSpellingsRemainIdentical()
    {
        assertEquals("Catalog.Products", GetProjectErrorsTool.markerOwnerFqn( //$NON-NLS-1$
            "Catalog.Products.TabularSection.Goods.Attribute.Price")); //$NON-NLS-1$
        assertEquals("Catalog.Products", GetProjectErrorsTool.markerOwnerFqn( //$NON-NLS-1$
            "Catalog.Products.Predefined.Sample")); //$NON-NLS-1$
        assertNull(GetProjectErrorsTool.markerOwnerFqn("Catalog.Products")); //$NON-NLS-1$
        assertNull(GetProjectErrorsTool.markerOwnerFqn("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertNull(GetProjectErrorsTool.markerOwnerFqn("CommonForm.Settings")); //$NON-NLS-1$
        assertNull(GetProjectErrorsTool.markerOwnerFqn("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$
    }

    private static Set<String> resolvedScope(MetadataScope scope, String address)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("ScopeTest"); //$NON-NLS-1$
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(mock(IBmTransaction.class), null);
        });
        GetProjectErrorsTool.ProjectResolution decision = GetProjectErrorsTool.resolveInProject(
            project, model, scope, Collections.singletonList(address));
        assertTrue("the resolution pass must complete", decision.passCompleted); //$NON-NLS-1$
        assertTrue("the address must resolve: " + address, decision.resolved.containsKey(address)); //$NON-NLS-1$
        return decision.resolved.get(address);
    }

    private static Set<String> scanVariants(Set<String> scope)
    {
        Map<String, Set<String>> variants = GetProjectErrorsTool.filterVariantsByProject(
            Collections.singletonMap("ScopeTest", scope)); //$NON-NLS-1$
        return GetProjectErrorsTool.objectsForProject(variants, Collections.emptySet(), "ScopeTest"); //$NON-NLS-1$
    }

    private static void assertMarkerIncluded(Set<String> scope, String presentation)
    {
        assertFalse("expected marker in scope: " + presentation, //$NON-NLS-1$
            MarkerRows.excludedByObjectsFilter(scanVariants(scope), true, presentation, new int[1], true));
    }

    private static void assertMarkerExcluded(Set<String> scope, String presentation)
    {
        assertTrue("unexpected marker in scope: " + presentation, //$NON-NLS-1$
            MarkerRows.excludedByObjectsFilter(scanVariants(scope), true, presentation, new int[1], true));
    }
}
