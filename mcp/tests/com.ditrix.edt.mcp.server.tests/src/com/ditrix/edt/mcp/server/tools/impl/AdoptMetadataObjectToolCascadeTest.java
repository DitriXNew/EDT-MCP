/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmGlobalEditingContext;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.bm.integration.event.IBmSyncEventListener;
import com._1c.g5.v8.bm.core.event.BmAssociationEvent;
import com._1c.g5.v8.bm.core.event.BmEvent;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTopObjects;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Model-measured additions, ordinary-adoption compatibility, and uncapped export/history scope. */
public class AdoptMetadataObjectToolCascadeTest
{
    @Test
    public void adoptionReleasesRootBeforeExporterCompletionOnBothHosts() throws Exception
    {
        String application = System.getProperty("eclipse.application");
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        try
        {
            for (String host : List.of(com.ditrix.edt.mcp.server.HostKind.APPLICATION_ID,
                "org.eclipse.ui.ide.workbench"))
            {
                System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
                setUp();
                System.setProperty("eclipse.application", host);
                var workspace = org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
                var manager = mock(com._1c.g5.v8.derived.IDerivedDataManager.class);
                var provider = mock(com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider.class);
                Activator activator = mock(Activator.class);
                when(activator.getDerivedDataManagerProvider()).thenReturn(provider);
                when(provider.get(model)).thenReturn(manager);
                plugin.set(null, activator);
                var wrapping = new java.util.concurrent.atomic.AtomicReference<
                    com._1c.g5.v8.derived.operations.WrappingOperation>();
                when(manager.scheduleOperation(any(), any(), eq(true), any())).thenAnswer(call -> {
                    wrapping.set(call.getArgument(0));
                    return null;
                });
                var exported = new java.util.concurrent.CountDownLatch(1);
                var notifications = new java.util.concurrent.atomic.AtomicBoolean();
                var exporter = new org.eclipse.core.runtime.jobs.Job("Adoption regression exporter")
                {
                    @Override protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
                    {
                        exported.countDown();
                        return Status.OK_STATUS;
                    }
                };
                exporter.setRule(workspace.getRoot());
                beforeWrite = () -> assertTrue("The root rule must precede the BM transaction",
                    org.eclipse.core.runtime.jobs.Job.getJobManager().currentRule().contains(workspace.getRoot()));
                var copy = top(MdObject.class, "ExchangePlan.Branches");
                when(tx.getObjectById(42L)).thenReturn((IBmObject)copy);
                afterWrite = () -> {
                    exporter.schedule();
                    var operation = wrapping.get();
                    if (operation != null)
                    {
                        operation.setImplicitWaitingContext(List.of());
                        operation.setAsyncEventScheduler(() -> {
                            assertNull("Commit notifications must leave the root rule",
                                org.eclipse.core.runtime.jobs.Job.getJobManager().currentRule());
                            notifications.set(true);
                        });
                    }
                    else
                    {
                        try
                        {
                            assertTrue("Global commit waited for an exporter blocked by its root rule",
                                exported.await(500, java.util.concurrent.TimeUnit.MILLISECONDS));
                        }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                };
                try
                {
                    var result = AdoptMetadataObjectTool.adoptUnderWorkspaceRule(workspace, model, adopter,
                        source.object, sourceScope, source.fqn, target);
                    assertEquals(Long.valueOf(((IBmObject)copy).bmGetId()), result.copyId);
                    assertTrue("Export must be able to acquire its rule after commit",
                        exported.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue("Native commit notifications were lost", notifications.get());
                    verify(manager, never()).implicitDerivedDataWait(any());
                    verify(manager, never()).disableImplicitWaiting();
                }
                finally { exporter.join(); }
            }
        }
        finally
        {
            plugin.set(null, previous);
            if (application == null) System.clearProperty("eclipse.application");
            else System.setProperty("eclipse.application", application);
        }
    }

    private final List<IBmObject> objects = new ArrayList<>();
    private final Map<IBmObject, String> names = new IdentityHashMap<>();
    private final List<IBmSyncEventListener> listeners = new ArrayList<>();
    private List<IBmObject> associated = new ArrayList<>();
    private Runnable beforeWrite = () -> { };
    private Runnable afterWrite = () -> { };
    private boolean emitCommitEvent = true;
    private boolean committed;
    private long nextId = 100;
    private boolean reading;
    private boolean writing;
    private IBmModel model;
    private IBmTransaction tx;
    private Configuration root;
    private IBmObject returnedCopy;
    private AdoptMetadataObjectTool.AdoptionSource source;
    private IModelObjectAdopter adopter;
    private IExtensionProject target;
    private MetadataScope sourceScope;

    @Before
    public void setUp() throws Exception
    {
        tx = mock(IBmTransaction.class);
        model = mock(IBmModel.class);
        adopter = mock(IModelObjectAdopter.class);
        target = mock(IExtensionProject.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Base.Ext");
        when(target.getProject()).thenReturn(project);
        IBmEngine engine = mock(IBmEngine.class);
        when(model.getEngine()).thenReturn(engine);
        when(target.getAdapter(IBmModel.class)).thenReturn(model);
        when(engine.getCurrentTransaction()).thenAnswer(inv -> writing ? tx : null);
        IBmGlobalEditingContext global = mock(IBmGlobalEditingContext.class);
        when(model.getGlobalContext()).thenReturn(global);
        doAnswer(inv -> listeners.add(inv.getArgument(0))).when(model).addSyncEventListener(any());
        doAnswer(inv -> listeners.remove(inv.getArgument(0))).when(model).removeSyncEventListener(any());
        when(global.execute(any())).thenAnswer(inv -> {
            beforeWrite.run();
            IBmTask<?> task = inv.getArgument(0);
            reading = true;
            writing = true;
            Object result;
            try
            {
                result = task.execute(tx, null);
            }
            finally
            {
                reading = false;
                writing = false;
            }
            if (emitCommitEvent)
            {
                fireEvent(task.getId(), associated);
            }
            committed = true;
            afterWrite.run();
            return result;
        });
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            assertTrue("the list overload must join the active write transaction", writing);
            assertEquals(List.of(source.object), inv.getArgument(0));
            return List.of((EObject)returnedCopy);
        });
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
        sourceScope = MetadataScope.ofConfiguration(base);
        source = AdoptMetadataObjectTool.AdoptionSource.resolve(sourceScope,
            "ExchangePlan.Branches");
    }

    private <T extends MdObject> T top(Class<T> type, String fqn)
    {
        T object = mock(type, withSettings().extraInterfaces(IBmObject.class));
        IBmObject bm = (IBmObject)object;
        long id = nextId++;
        names.put(bm, fqn);
        when(bm.bmGetId()).thenReturn(id);
        when(bm.bmIsTop()).thenReturn(true);
        when(tx.getObjectById(id)).thenReturn(bm);
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
        associated = objects.stream().filter(object -> !before.contains(names.get(object))).toList();
        return AdoptMetadataObjectTool.adoptWithChanges(model, adopter, source.object, target).changes;
    }

    private void fireEvent(Object operationId, List<IBmObject> attached)
    {
        BmAssociationEvent association = new BmAssociationEvent(attached, List.of(), List.of(), Map.of());
        BmEvent event = new BmEvent(1L, operationId, null, null, association, null, null);
        for (IBmSyncEventListener listener : List.copyOf(listeners))
        {
            listener.handleSyncEvent(event);
        }
    }

    private JsonObject result(AdoptMetadataObjectTool.AdoptionChanges changes, String note)
    {
        return JsonParser.parseString(AdoptMetadataObjectTool.adoptedResult(source, "Base.Ext", true,
            note, changes)).getAsJsonObject();
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
    public void testConcurrentAdditionsBetweenOldSnapshotsAreNotAttributed()
    {
        Set<String> oldBefore = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        MdObject cascade = top(MdObject.class, "Catalog.Goods");
        IBmObject concurrentBefore = (IBmObject)top(MdObject.class, "Catalog.ConcurrentBeforeWrite");
        IBmObject concurrentAfter = (IBmObject)top(MdObject.class, "Catalog.ConcurrentAfterCommit");
        objects.addAll(List.of((IBmObject)plan, (IBmObject)cascade));
        beforeWrite = () -> objects.add(concurrentBefore);
        afterWrite = () -> {
            objects.add(concurrentAfter);
            fireEvent(new Object(), List.of(concurrentBefore, concurrentAfter));
        };

        var changes = after(oldBefore, plan);
        assertEquals(List.of("Catalog.Goods"), changes.cascaded);
        assertEquals(List.of("ExchangePlan.Branches", "Catalog.Goods", "Configuration"), changes.dirty);
        assertEquals("[\"Catalog.Goods\"]", result(changes, null).get("cascadedObjects").toString());
        Set<String> oldDifference = before();
        oldDifference.removeAll(oldBefore);
        assertTrue("the old model-wide snapshots would include both unrelated additions",
            oldDifference.containsAll(List.of("Catalog.ConcurrentBeforeWrite", "Catalog.ConcurrentAfterCommit")));
        assertTrue("the commit observer must be removed", listeners.isEmpty());
    }

    @Test
    public void testMissingCommitEvidenceReturnsSuccessWithUnknownCascade() throws Exception
    {
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        objects.add((IBmObject)plan);
        emitCommitEvent = false;
        WriteScope scope = new WriteScope();
        AdoptMetadataObjectTool.AdoptionChanges[] changes = new AdoptMetadataObjectTool.AdoptionChanges[1];
        WriteScope.runWithScope(scope, () -> changes[0] = after(before, plan));
        assertTrue(committed);
        assertUnavailableSuccess(changes[0], "EDT did not report", scope);
        assertTrue(listeners.isEmpty());
    }

    @Test
    public void testDisappearedAssociationAfterCommitReturnsSuccessWithUnknownCascade() throws Exception
    {
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        IBmObject missing = (IBmObject)top(MdObject.class, "Catalog.Goods");
        objects.addAll(List.of((IBmObject)plan, missing));
        afterWrite = () -> when(tx.getObjectById(missing.bmGetId())).thenReturn(null);
        WriteScope scope = new WriteScope();
        AdoptMetadataObjectTool.AdoptionChanges[] changes = new AdoptMetadataObjectTool.AdoptionChanges[1];
        WriteScope.runWithScope(scope, () -> changes[0] = after(before, plan));
        assertTrue(committed);
        assertUnavailableSuccess(changes[0], "disappeared after commit", scope);
    }

    @Test
    public void testPostCommitReadFailureReturnsSuccessWithUnknownCascade() throws Exception
    {
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        objects.add((IBmObject)plan);
        doThrow(new IllegalStateException("model closing")).when(model).executeReadonlyTask(any());
        WriteScope scope = new WriteScope();
        AdoptMetadataObjectTool.AdoptionChanges[] changes = new AdoptMetadataObjectTool.AdoptionChanges[1];
        WriteScope.runWithScope(scope, () -> changes[0] = after(before, plan));
        assertTrue(committed);
        assertUnavailableSuccess(changes[0], "could not be read", scope);
    }

    private void assertUnavailableSuccess(AdoptMetadataObjectTool.AdoptionChanges changes, String reason,
        WriteScope scope) throws Exception
    {
        JsonObject result = result(changes, "Mapping note.");
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("adopted", result.get("action").getAsString());
        assertEquals("ExchangePlan.Branches", result.get("fqn").getAsString());
        assertEquals("unavailable", result.get("cascadeAttribution").getAsString());
        assertTrue(result.get("warning").getAsString().contains("Adoption committed"));
        assertTrue(result.get("warning").getAsString().contains(reason));
        assertTrue(result.get("warning").getAsString().contains("get_metadata_objects"));
        assertTrue(result.get("warning").getAsString().contains("do not retry"));
        assertEquals("Mapping note.", result.get("message").getAsString());
        assertFalse(result.has("cascadedObjects"));
        assertFalse(result.has("cascadedCount"));
        assertTrue(changes.cascaded.isEmpty());
        assertEquals(List.of("ExchangePlan.Branches", "Configuration"), changes.dirty);
        assertTrue(scope.hasRecordedWrite());
        persistAndAssertScope(changes, changes.dirty, scope);
    }

    @Test
    public void testAdopterFailurePreservesInFlightUnknownMutationOutcome() throws Exception
    {
        WriteScope scope = new WriteScope();
        doAnswer(inv -> {
            JsonObject inFlight = JsonParser.parseString(scope.markErrorAfterRecordedWrite(
                ToolResult.error("in-flight probe").toJson())).getAsJsonObject();
            assertTrue(inFlight.get("mutationOutcomeUnknown").getAsBoolean());
            assertFalse(inFlight.has("mutationCommitted"));
            throw new CoreException(new Status(IStatus.ERROR, "test", "mid-adoption failure"));
        }).when(adopter).adoptAndAttach(anyList(), eq(target), any());
        assertPreCommitFailure(scope, () -> AdoptMetadataObjectTool.adoptWithChanges(model, adopter,
            source.object, target), "mid-adoption failure");
    }

    @Test
    public void testCopyUnavailableInsideWriteIsAPreCommitFailure()
    {
        Set<String> before = before();
        MdObject plan = top(MdObject.class, "ExchangePlan.Branches");
        objects.add((IBmObject)plan);
        beforeWrite = () -> when(tx.getObjectById(42L)).thenReturn(null);
        assertPreCommitFailure(new WriteScope(), () -> after(before, plan), "Adopted copy is unavailable");
    }

    private void assertPreCommitFailure(WriteScope scope, Runnable action, String message)
    {
        WriteScope.runWithScope(scope, () -> {
            try
            {
                action.run();
                org.junit.Assert.fail("a failing write task must not commit");
            }
            catch (IllegalStateException expected)
            {
                assertTrue(expected.getMessage(), expected.getMessage().contains(message));
                JsonObject error = JsonParser.parseString(scope.markErrorAfterRecordedWrite(
                    ToolResult.error(expected.getMessage()).toJson())).getAsJsonObject();
                assertFalse(error.get("success").getAsBoolean());
                assertTrue(error.get("mutationOutcomeUnknown").getAsBoolean());
                assertFalse(error.has("mutationCommitted"));
            }
        });
        assertFalse(committed);
        assertFalse(scope.hasRecordedWrite());
        assertTrue(scope.exportedTopObjects("Base.Ext").isEmpty());
        assertTrue(listeners.isEmpty());
    }

    @Test
    public void testSourceAdoptedBeforeOurTransactionProducesNoWrite() throws Exception
    {
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenReturn(List.of());
        emitCommitEvent = false;
        var outcome = AdoptMetadataObjectTool.adoptWithChanges(model, adopter, source.object, target);
        org.junit.Assert.assertNull(outcome.copyId);
        assertTrue(outcome.changes.dirty.isEmpty());
        assertTrue(outcome.changes.cascaded.isEmpty());
        assertTrue(listeners.isEmpty());
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

        WriteScope scope = new WriteScope();
        persistAndAssertScope(changes, changes.dirty, scope);
        assertTrue(scope.exportedTopObjects("Base.Ext").contains("Catalog.Item149"));
    }

    private void persistAndAssertScope(AdoptMetadataObjectTool.AdoptionChanges changes, List<String> expected,
        WriteScope scope) throws Exception
    {
        IProject project = target.getProject();
        IDtProject dtProject = mock(IDtProject.class);
        IDtProjectManager projects = mock(IDtProjectManager.class);
        IBmModelManager models = mock(IBmModelManager.class);
        when(projects.getDtProject(project)).thenReturn(dtProject);
        when(models.forceExport(dtProject, expected)).thenReturn(true);
        Activator activator = mock(Activator.class);
        when(activator.getDtProjectManager()).thenReturn(projects);
        when(activator.getBmModelManager()).thenReturn(models);
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        try
        {
            plugin.set(null, activator);
            WriteScope.runWithScope(scope, () -> assertTrue(changes.persistToDisk(project)));
        }
        finally
        {
            plugin.set(null, previous);
        }
        verify(models).forceExport(dtProject, expected);
        assertEquals(expected, scope.exportedTopObjects("Base.Ext"));
        assertEquals(List.of("Base.Ext"), scope.writtenProjects());
    }
}
