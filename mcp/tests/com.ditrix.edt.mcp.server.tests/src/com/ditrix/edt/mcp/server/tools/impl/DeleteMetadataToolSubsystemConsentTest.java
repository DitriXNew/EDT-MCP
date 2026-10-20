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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The human consent for a confirmed subsystem delete must describe the current cascade. */
public class DeleteMetadataToolSubsystemConsentTest
{
    private Subsystem a;
    private IProject project;
    private IBmModel model;
    private IRefactoring refactoring;
    private IMdRefactoringService service;
    private BmModelResolver.Resolution resolution;
    private DeleteMetadataTool tool;
    private final List<ConsentPreview> asked = new ArrayList<>();
    private final List<String> exports = new ArrayList<>();

    @Before
    public void setUp()
    {
        a = subsystem("A", null);
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
        service = mock(IMdRefactoringService.class);
        when(service.createMdObjectDeleteRefactoring(any())).thenReturn(refactoring);
        tool = new DeleteMetadataTool((name, preview) -> {
            assertEquals("delete_metadata", name);
            asked.add(preview);
            return ConsentDecision.REJECT;
        }, (name, timeout) -> null, (targetProject, container) -> {
            exports.add(container);
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

    private ConsentPreview confirm(Subsystem target, String fqn, boolean force)
    {
        String container = target == a ? "Configuration" : "Subsystem.A";
        String json = tool.prepareMdClassDelete(project, fqn, target, container,
            true, force, service, resolution);
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse("rejecting human consent must prevent deletion: " + json,
            result.get("success").getAsBoolean());
        assertEquals("exactly one human consent request", 1, asked.size());
        verify(refactoring, never()).perform();
        assertTrue("a rejected delete must never submit an export", exports.isEmpty());
        return asked.get(0);
    }

    private static void assertCascade(ConsentPreview preview, String fqn)
    {
        assertEquals("Delete metadata node", preview.getTitle());
        assertEquals("the count includes the target, child and grandchild", 3, preview.getTotalCount());
        assertEquals(List.of(fqn, fqn + ".Subsystem.B", fqn + ".Subsystem.B.Subsystem.C"),
            preview.getTopNames());
        assertTrue(preview.getSubtitle(), preview.getSubtitle().contains("2 nested subsystem(s)"));
    }

    @Test
    public void testConfirmedSubsystemConsentNamesChildAndGrandchild()
    {
        Subsystem b = subsystem("B", a);
        subsystem("C", b);

        ConsentPreview preview = confirm(a, "Subsystem.A", false);

        assertCascade(preview, "Subsystem.A");
        assertEquals("This deletes 'Subsystem.A' and its 2 nested subsystem(s) and cascades "
            + "reference cleanup (BSL, forms, metadata).", preview.getSubtitle());
        verify(model).executeReadonlyTask(any());
    }

    @Test
    public void testNestedSubsystemConsentCountsOnlyItsOwnDescendants()
    {
        Subsystem target = subsystem("Parent", a);
        Subsystem b = subsystem("B", target);
        subsystem("C", b);
        subsystem("Sibling", a);

        ConsentPreview preview = confirm(target, "Subsystem.A.Subsystem.Parent", false);

        assertCascade(preview, "Subsystem.A.Subsystem.Parent");
        verify(model).executeReadonlyTask(any());
    }

    @Test
    public void testConfirmIncludesGrandchildAddedAfterPreview()
    {
        Subsystem b = subsystem("B", a);
        String json = tool.prepareMdClassDelete(project, "Subsystem.A", a, "Configuration",
            false, false, service, resolution);
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(json, result.get("success").getAsBoolean());
        assertEquals("preview", result.get("action").getAsString());
        assertFalse(json, result.get("message").getAsString().contains("Subsystem.C"));
        assertTrue("the tool preview never asks for consent", asked.isEmpty());
        subsystem("C", b);

        assertCascade(confirm(a, "Subsystem.A", false), "Subsystem.A");

        verify(model, times(2)).executeReadonlyTask(any());
    }

    @Test
    public void testSubsystemWithoutChildrenKeepsTheOriginalConsent()
    {
        ConsentPreview preview = confirm(a, "Subsystem.A", false);

        assertEquals(1, preview.getTotalCount());
        assertEquals(Collections.singletonList("Subsystem.A"), preview.getTopNames());
        assertEquals("This deletes 'Subsystem.A' and cascades reference cleanup (BSL, forms, metadata).",
            preview.getSubtitle());
    }

    private void addPlatformProhibition()
    {
        RefactoringStatus status = new RefactoringStatus();
        status.addProblem(mock(IRefactoringProblem.class));
        when(refactoring.getStatus()).thenReturn(status);
    }

    @Test
    public void testForcedConsentRetainsProhibitionAndDisclosesDescendants()
    {
        Subsystem b = subsystem("B", a);
        subsystem("C", b);
        addPlatformProhibition();

        ConsentPreview preview = confirm(a, "Subsystem.A", true);

        assertCascade(preview, "Subsystem.A");
        assertEquals("This deletes 'Subsystem.A' despite 1 platform prohibition(s) reported by EDT. "
            + "This also deletes its 2 nested subsystem(s).", preview.getSubtitle());
    }

    @Test
    public void testForcedLeafKeepsTheOriginalProhibitionSubtitle()
    {
        addPlatformProhibition();

        ConsentPreview preview = confirm(a, "Subsystem.A", true);

        assertEquals(1, preview.getTotalCount());
        assertEquals(Collections.singletonList("Subsystem.A"), preview.getTopNames());
        assertEquals("This deletes 'Subsystem.A' despite 1 platform prohibition(s) reported by EDT.",
            preview.getSubtitle());
    }
}
