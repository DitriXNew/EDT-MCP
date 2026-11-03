/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTopObjects;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Model-measured additions, ordinary-adoption compatibility, and uncapped export/history scope. */
public class AdoptMetadataObjectToolCascadeTest
{
    private final List<IBmObject> objects = new ArrayList<>();
    private boolean reading;
    private IBmModel model;
    private IBmTransaction tx;
    private Configuration root;
    private IBmObject returnedCopy;
    private AdoptMetadataObjectTool.AdoptionSource source;

    @Before
    public void setUp()
    {
        tx = mock(IBmTransaction.class);
        model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            assertFalse(reading);
            reading = true;
            try
            {
                IBmTask<?> task = inv.getArgument(0);
                return task.execute(tx, null);
            }
            finally
            {
                reading = false;
            }
        });
        when(tx.getTopObjectIterator()).thenAnswer(inv -> {
            assertTrue("enumeration requires the BM read boundary", reading);
            return objects.iterator();
        });
        root = top(Configuration.class, "Configuration");
        objects.add((IBmObject)root);
        when(tx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)root);
        returnedCopy = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(returnedCopy.bmGetId()).thenReturn(42L);

        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        var plan = MdClassFactory.eINSTANCE.createExchangePlan();
        plan.setName("Branches");
        base.getExchangePlans().add(plan);
        source = AdoptMetadataObjectTool.AdoptionSource.resolve(MetadataScope.ofConfiguration(base),
            "ExchangePlan.Branches");
    }

    private <T extends MdObject> T top(Class<T> type, String fqn)
    {
        T object = mock(type, withSettings().extraInterfaces(IBmObject.class));
        IBmObject bm = (IBmObject)object;
        when(bm.bmGetTopObject()).thenReturn(bm);
        when(bm.bmGetFqn()).thenAnswer(inv -> {
            assertTrue("FQNs must be read inside the BM transaction", reading);
            return fqn;
        });
        return object;
    }

    private Set<String> before()
    {
        return new LinkedHashSet<>(MetadataTopObjects.snapshot(model));
    }

    private AdoptMetadataObjectTool.AdoptionChanges after(Set<String> before, EObject copy)
    {
        when(tx.getObjectById(42L)).thenReturn((IBmObject)copy);
        return AdoptMetadataObjectTool.collectChanges(model, before, (EObject)returnedCopy);
    }

    private JsonObject result(AdoptMetadataObjectTool.AdoptionChanges changes, String note)
    {
        return JsonParser.parseString(AdoptMetadataObjectTool.adoptedResult(source, "Base.Ext", true,
            note, changes.cascaded)).getAsJsonObject();
    }

    @Test
    public void testDifferenceUsesModelAndExcludesExistingObjectsAndRequestedTop()
    {
        objects.add((IBmObject)top(MdObject.class, "Catalog.Existing"));
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        objects.addAll(List.of((IBmObject)plan, (IBmObject)top(MdObject.class, "Document.Order"),
            (IBmObject)top(MdObject.class, "Catalog.Goods")));

        var changes = after(before, plan);
        assertEquals(List.of("Catalog.Goods", "Document.Order"), changes.cascaded);
        assertEquals(List.of("ExchangePlan.Branches", "Document.Order", "Catalog.Goods", "Configuration"),
            changes.dirty);
        JsonObject result = result(changes, "Mapping note.");
        assertEquals("[\"Catalog.Goods\",\"Document.Order\"]", result.get("cascadedObjects").toString());
        assertEquals(2, result.get("cascadedCount").getAsInt());
        assertEquals("Mapping note.", result.get("message").getAsString());
        verify(tx).getObjectById(42L);
        verify(returnedCopy, never()).bmGetTopObject();
        verify(model, never()).execute(any());
    }

    @Test
    public void testNoCascadePreservesTheWholeResponse()
    {
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        objects.add((IBmObject)plan);
        var changes = after(before, plan);
        assertTrue(changes.cascaded.isEmpty());
        assertEquals(List.of("ExchangePlan.Branches", "Configuration"), changes.dirty);
        assertEquals(JsonParser.parseString("{\"success\":true,\"action\":\"adopted\",\"fqn\":\"ExchangePlan.Branches\","
            + "\"extensionProject\":\"Base.Ext\",\"objectBelonging\":\"ADOPTED\",\"persisted\":true}"),
            result(changes, null));
    }

    @Test
    public void testOrdinaryMemberAdoptionDoesNotReportItsNewOwnerAsCascade()
    {
        Set<String> before = before();
        MdObject owner = top(MdObject.class, "Catalog.Goods");
        IBmObject member = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(member.bmGetTopObject()).thenReturn((IBmObject)owner);
        objects.add((IBmObject)owner);
        var changes = after(before, (EObject)member);
        assertTrue(changes.cascaded.isEmpty());
        assertEquals(List.of("Catalog.Goods", "Configuration"), changes.dirty);
        assertFalse(result(changes, null).has("cascadedObjects"));
    }

    @Test
    public void testMemberOnExistingOwnerStillReportsOtherNewTops()
    {
        MdObject owner = top(MdObject.class, "Catalog.Goods");
        objects.add((IBmObject)owner);
        Set<String> before = before();
        IBmObject member = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(member.bmGetTopObject()).thenReturn((IBmObject)owner);
        objects.add((IBmObject)top(MdObject.class, "ExchangePlan.Branches"));
        var changes = after(before, (EObject)member);
        assertEquals(List.of("ExchangePlan.Branches"), changes.cascaded);
        assertEquals(List.of("Catalog.Goods", "ExchangePlan.Branches", "Configuration"), changes.dirty);
    }

    @Test
    public void testNestedSubsystemKeepsItsExistingImplicitParentExportContract()
    {
        Set<String> before = before();
        Subsystem parent = top(Subsystem.class, "Subsystem.Sales");
        Subsystem child = top(Subsystem.class, "Subsystem.Sales.Subsystem.Orders");
        when(child.getParentSubsystem()).thenReturn(parent);
        objects.addAll(List.of((IBmObject)parent, (IBmObject)child));
        var changes = after(before, child);
        assertTrue(changes.cascaded.isEmpty());
        assertEquals(List.of("Subsystem.Sales.Subsystem.Orders", "Subsystem.Sales", "Configuration"),
            changes.dirty);
    }

    @Test
    public void testSharedSnapshotFiltersNonMetadataAndKeepsNestedTopFqns()
    {
        IBmObject index = mock(IBmObject.class);
        when(index.bmGetFqn()).thenThrow(new AssertionError("internal indexes must be skipped unread"));
        objects.addAll(Arrays.asList(null, index, (IBmObject)top(MdObject.class, ""),
            (IBmObject)top(MdObject.class, "Subsystem.Sales.Subsystem.Orders")));
        assertEquals(List.of("Configuration", "Subsystem.Sales.Subsystem.Orders"), MetadataTopObjects.snapshot(model));
        verify(index, never()).bmGetFqn();
    }

    @Test
    public void testStoredUnicodeNamesArePreservedInTheDifferenceAndResponse()
    {
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        String fqn = "Catalog.\u0422\u043e\u0432\u0430\u0440\u044b";
        objects.addAll(List.of((IBmObject)plan, (IBmObject)top(MdObject.class, fqn)));
        var changes = after(before, plan);
        assertEquals(List.of(fqn), changes.cascaded);
        assertEquals(fqn, result(changes, null).getAsJsonArray("cascadedObjects").get(0).getAsString());
    }

    @Test
    public void testResponseCapNeverCapsExportsOrWriteScope() throws Exception
    {
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        objects.add((IBmObject)plan);
        for (int i = 0; i < 150; i++)
        {
            objects.add((IBmObject)top(MdObject.class, String.format("Catalog.Item%03d", i)));
        }
        var changes = after(before, plan);
        JsonObject result = result(changes, null);
        assertTrue("a measured cascade must publish its total", result.has("cascadedCount"));
        assertEquals(150, result.get("cascadedCount").getAsInt());
        assertEquals(100, result.getAsJsonArray("cascadedObjects").size());
        assertEquals("Catalog.Item099", result.getAsJsonArray("cascadedObjects").get(99).getAsString());
        assertEquals(152, changes.dirty.size());

        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Base.Ext");
        IDtProject dtProject = mock(IDtProject.class);
        IDtProjectManager projects = mock(IDtProjectManager.class);
        IBmModelManager models = mock(IBmModelManager.class);
        when(projects.getDtProject(project)).thenReturn(dtProject);
        when(models.forceExport(dtProject, changes.dirty)).thenReturn(true);
        Activator activator = mock(Activator.class);
        when(activator.getDtProjectManager()).thenReturn(projects);
        when(activator.getBmModelManager()).thenReturn(models);
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        WriteScope scope = new WriteScope();
        try
        {
            plugin.set(null, activator);
            WriteScope.runWithScope(scope, () -> assertTrue(changes.persistToDisk(project)));
        }
        finally
        {
            plugin.set(null, previous);
        }
        verify(models).forceExport(dtProject, changes.dirty);
        assertEquals(changes.dirty, scope.exportedTopObjects("Base.Ext"));
        assertTrue(scope.exportedTopObjects("Base.Ext").contains("Catalog.Item149"));
    }
}
