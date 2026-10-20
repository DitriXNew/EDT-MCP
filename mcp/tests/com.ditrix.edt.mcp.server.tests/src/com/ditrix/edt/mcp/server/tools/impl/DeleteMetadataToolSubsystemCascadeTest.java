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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.BasicEList;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The stand-proven EDT cascade must remain available, and its preview must disclose the tree. */
public class DeleteMetadataToolSubsystemCascadeTest
{
    private Subsystem a;
    private Subsystem b;
    private Subsystem c;
    private IProject project;
    private IBmModel model;
    private IRefactoring refactoring;
    private IMdRefactoringService service;
    private BmModelResolver.Resolution resolution;
    private DeleteMetadataTool tool;
    private final List<String> writes = new ArrayList<>();

    @Before
    public void setUp()
    {
        a = subsystem("A", null);
        b = subsystem("B", a);
        c = subsystem("C", b);
        subsystem("D", a);
        project = mock(IProject.class);
        when(project.getName()).thenReturn("TestConfiguration");
        model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(invocation -> {
            IBmTask<?> task = invocation.getArgument(0);
            return task.execute(null, null);
        });
        IBmModelManager manager = mock(IBmModelManager.class);
        when(manager.getModel(project)).thenReturn(model);
        resolution = BmModelResolver.resolve(project, manager);
        refactoring = mock(IRefactoring.class);
        when(refactoring.getTitle()).thenReturn("Delete subsystem");
        IRefactoringItem item = mock(IRefactoringItem.class);
        when(item.getName()).thenReturn("Delete A");
        when(refactoring.getItems()).thenReturn(Collections.singletonList(item));
        service = mock(IMdRefactoringService.class);
        when(service.createMdObjectDeleteRefactoring(any())).thenReturn(refactoring);
        tool = new DeleteMetadataTool((name, preview) -> {
            writes.add("consent");
            return ConsentDecision.ALLOW;
        }, (name, timeout) -> null, (targetProject, container) -> {
            writes.add(container);
            return true;
        }, base -> Collections.emptyList());
    }

    private static Subsystem subsystem(String name, Subsystem parent)
    {
        Subsystem result = MdClassFactory.eINSTANCE.createSubsystem();
        result.setName(name);
        if (parent != null)
        {
            parent.getSubsystems().add(result);
            result.setParentSubsystem(parent);
        }
        return result;
    }

    private JsonObject run(Subsystem parent, String fqn, boolean confirm, boolean force)
    {
        String container = parent == a ? "Configuration" : "Subsystem.A";
        String result = tool.prepareMdClassDelete(project, fqn, parent, container,
            confirm, force, service, resolution);
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(result, json.get("success").getAsBoolean());
        verify(service).createMdObjectDeleteRefactoring(Collections.singletonList(parent));
        return json;
    }

    private String preview(Subsystem parent, String fqn)
    {
        JsonObject json = run(parent, fqn, false, false);
        assertEquals("preview", json.get("action").getAsString());
        assertEquals("the platform's items stay unchanged", 1, json.getAsJsonArray("items").size());
        assertEquals("Delete A", json.getAsJsonArray("items").get(0).getAsJsonObject()
            .get("name").getAsString());
        assertFalse("no new output-schema member", json.has("nestedSubsystems"));
        assertTrue("preview must never ask for consent or submit an export", writes.isEmpty());
        verify(refactoring, never()).perform();
        verify(model).executeReadonlyTask(any());
        return json.get("message").getAsString();
    }

    @Test
    public void testTopLevelPreviewNamesEveryNestedDescendant()
    {
        String message = preview(a, "Subsystem.A");
        assertTrue(message, message.contains("Subsystem.A.Subsystem.B"));
        assertTrue(message, message.contains("Subsystem.A.Subsystem.B.Subsystem.C"));
        assertTrue(message, message.contains("Subsystem.A.Subsystem.D"));
        assertTrue(message, message.contains("Call with confirm=true to apply."));
    }

    @Test
    public void testNestedParentPreviewNamesItsDescendantsOnly()
    {
        String message = preview(b, "Subsystem.A.Subsystem.B");
        assertTrue(message, message.contains("Subsystem.A.Subsystem.B.Subsystem.C"));
        assertFalse(message, message.contains("Subsystem.A.Subsystem.D"));
    }

    @Test
    public void testTopLevelConfirmDelegatesCascadeToEdt()
    {
        JsonObject result = run(a, "Subsystem.A", true, false);
        assertEquals("executed", result.get("action").getAsString());
        verify(refactoring).perform();
        assertEquals(List.of("consent", "Configuration"), writes);
    }

    @Test
    public void testNestedParentConfirmAndForceDelegateCascadeToEdt()
    {
        JsonObject result = run(b, "Subsystem.A.Subsystem.B", true, true);
        assertEquals("executed", result.get("action").getAsString());
        assertTrue(result.get("forced").getAsBoolean());
        verify(refactoring).perform();
        assertEquals(List.of("consent", "Subsystem.A"), writes);
    }

    @Test
    public void testPreviewUsesStoredTopLevelCasing()
    {
        String message = preview(a, "Subsystem.a");
        assertTrue(message, message.contains("Subsystem.A.Subsystem.B"));
        assertFalse(message, message.contains("Subsystem.a.Subsystem.B"));
    }

    @Test
    public void testPreviewUsesStoredNestedLineage()
    {
        String message = preview(b, "Subsystem.a.Subsystem.b");
        assertTrue(message, message.contains("Subsystem.A.Subsystem.B.Subsystem.C"));
        assertFalse(message, message.contains("Subsystem.a.Subsystem.b"));
    }

    @Test
    public void testPreviewSkipsProxyAndNamelessChildren()
    {
        // An unresolved proxy stays unresolved in this list, as it does when EDT cannot load it.
        Subsystem parent = mock(Subsystem.class);
        when(parent.getName()).thenReturn("A");
        when(parent.getSubsystems()).thenReturn(new BasicEList<>());
        parent.getSubsystems().add(b);
        Subsystem proxy = mock(Subsystem.class);
        when(proxy.eIsProxy()).thenReturn(true);
        parent.getSubsystems().add(proxy);
        parent.getSubsystems().add(mock(Subsystem.class));
        String message = preview(parent, "Subsystem.A");
        assertTrue(message, message.contains("Subsystem.A.Subsystem.B.Subsystem.C"));
        assertFalse(message, message.contains("Subsystem.null"));
        verify(proxy, never()).getName();
    }

    @Test
    public void testPreviewDoesNotRepeatAChildListCycle()
    {
        c.getSubsystems().add(a);
        String message = preview(a, "Subsystem.A");
        assertTrue(message, message.contains("Subsystem.A.Subsystem.B.Subsystem.C"));
        assertFalse(message, message.contains("Subsystem.C.Subsystem.A"));
    }

    @Test
    public void testMissingNestedChainKeepsExternalScopeHint()
    {
        assertExternalScopeHint("Subsystem.A.Subsystem.B");
    }

    @Test
    public void testMissingRussianNestedChainKeepsExternalScopeHint()
    {
        assertExternalScopeHint("Subsystem.A.\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430.B");
    }

    private void assertExternalScopeHint(String fqn)
    {
        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        base.getSubsystems().add(a);
        MetadataScope external = MetadataScopeTestFixtures.externalObjectsWithBase(base);
        DeleteMetadataTool.ChainTarget result = DeleteMetadataTool.resolveNestedSubsystemTarget(external, fqn);
        assertNull("must never target the linked base's subsystem", result.object);
        assertTrue(result.error, result.error.contains(fqn));
        String hint = external.addressingHint(fqn);
        assertFalse("the parity check must not use an empty hint", hint.isEmpty());
        assertTrue(result.error, result.error.endsWith(hint));
    }
}
