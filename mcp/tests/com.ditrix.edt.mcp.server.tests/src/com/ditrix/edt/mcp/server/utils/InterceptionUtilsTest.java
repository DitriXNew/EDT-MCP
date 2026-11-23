/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.common.IModuleExtensionService;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.lcore.util.CaseInsensitiveString;

public class InterceptionUtilsTest
{
    private static Module objectModule(String project)
    {
        Module module = BslFactory.eINSTANCE.createModule();
        ResourceImpl resource = new ResourceImpl(
            URI.createPlatformResourceURI("/" + project + "/src/Documents/Sale/ObjectModule.bsl", true));
        resource.getContents().add(module);
        return module;
    }

    private static Method addMethod(Module module, String name)
    {
        Method method = BslFactory.eINSTANCE.createProcedure();
        method.setName(name);
        module.getMethods().add(method);
        return method;
    }

    private static Pragma pragma(String symbol)
    {
        Pragma pragma = mock(Pragma.class);
        when(pragma.getSymbol()).thenReturn(symbol);
        return pragma;
    }

    @Test
    public void summaryUsesTheSameLiveLinksAsIndividualMethodsWithoutAReverseMapping()
    {
        Module base = objectModule("Base");
        Method onWrite = addMethod(base, "ПриЗаписи");
        Method initialize = addMethod(base, "Initialize");
        Method inspect = addMethod(base, "Inspect");
        addMethod(base, "INITIALIZE"); // e.g. a second declaration in a different preprocessor branch
        Module existing = objectModule("Base.Existing");
        Method existingAfter = addMethod(existing, "existing_Initialize");
        Module added = objectModule("Base.Fresh");
        Method newBefore = addMethod(added, "fresh_Initialize");
        Method newAfter = addMethod(added, "fresh_ПриЗаписи");
        Method unicodeCase = addMethod(added, "fresh_Inspect");
        Method otherTarget = addMethod(added, "fresh_UnrelatedTarget");
        Module unrelated = objectModule("Other.Fresh");
        Method otherProject = addMethod(unrelated, "other_Initialize");

        IModuleExtensionService svc = mock(IModuleExtensionService.class);
        // The previously resolved extension works via both platform lookups. The new extension's
        // pragmas are queryable by target, while its reverse source lookup returns no link.
        Pragma after = pragma("After");
        Pragma before = pragma("Before");
        Pragma afterRu = pragma("После");
        when(svc.getSourceMethod(existingAfter)).thenReturn(Map.of(after, initialize));
        when(svc.getSourceMethodNames(existingAfter)).thenReturn(
            Map.of(new CaseInsensitiveString("Initialize"), List.of(after, after)));
        when(svc.getSourceMethodNames(newBefore)).thenReturn(
            Map.of(new CaseInsensitiveString("initialize"), List.of(before)));
        when(svc.getSourceMethodNames(newAfter)).thenReturn(
            Map.of(new CaseInsensitiveString("приЗаписи"), List.of(afterRu)));
        when(svc.getSourceMethodNames(unicodeCase)).thenReturn(
            Map.of(new CaseInsensitiveString("\u0131nspect"), List.of(before)));
        when(svc.getSourceMethodNames(otherTarget)).thenReturn(
            Map.of(new CaseInsensitiveString("MissingFromBase"), List.of(after)));
        when(svc.getSourceMethodNames(otherProject)).thenReturn(
            Map.of(new CaseInsensitiveString("Initialize"), List.of(after)));
        when(svc.getExtensionMethods(existing, "Initialize")).thenReturn(Map.of(after, existingAfter));
        when(svc.getExtensionMethods(added, "Initialize")).thenReturn(Map.of(before, newBefore));
        when(svc.getExtensionMethods(added, "ПриЗаписи")).thenReturn(Map.of(afterRu, newAfter));
        when(svc.getExtensionMethods(added, "Inspect")).thenReturn(Map.of(before, unicodeCase));
        when(svc.getExtensionMethods(unrelated, "Initialize")).thenReturn(Map.of(after, existingAfter));
        when(svc.getExtensionModules(base)).thenReturn(List.of(existing), List.of(existing, added));

        String initial = InterceptionUtils.moduleFooter(base, svc);
        assertTrue(initial, initial.contains("`Initialize` <- intercepted by extension `Base.Existing`"));
        assertFalse(initial, initial.contains("Base.Fresh"));

        String updated = InterceptionUtils.moduleFooter(base, svc);
        String methodFooter = InterceptionUtils.methodFooter(base, onWrite, svc)
            + InterceptionUtils.methodFooter(base, initialize, svc)
            + InterceptionUtils.methodFooter(base, inspect, svc);
        for (String link : List.of("`Base.Existing`: `existing_Initialize` via `&After`",
            "`Base.Fresh`: `fresh_Initialize` via `&Before`",
            "`Base.Fresh`: `fresh_ПриЗаписи` via `&После`",
            "`Base.Fresh`: `fresh_Inspect` via `&Before`"))
        {
            assertTrue(updated, updated.contains(link));
            assertTrue(methodFooter, methodFooter.contains(link));
        }
        assertEquals(updated, 4L, updated.lines().filter(line -> line.startsWith("- ")).count());
        assertFalse(updated, updated.contains("`INITIALIZE`"));
        assertFalse(updated, updated.contains("Other.Fresh"));
        assertFalse(updated, updated.contains("MissingFromBase"));
        verify(svc, never()).getSourceMethodNames(otherProject);
        verify(svc, never()).getExtensionMethods(unrelated, "Initialize");
        verify(svc, never()).getSourceMethod(newBefore);
        verify(svc, never()).getSourceMethod(newAfter);
    }

    @Test
    public void extensionSideKeepsUsingTheSourceMethodLookup()
    {
        Module base = objectModule("Base");
        Method original = addMethod(base, "Initialize");
        Module extension = objectModule("Base.Fresh");
        Method hook = addMethod(extension, "fresh_Initialize");
        IModuleExtensionService svc = mock(IModuleExtensionService.class);
        when(svc.isExtensionModule(extension)).thenReturn(true);
        Pragma before = pragma("Before");
        when(svc.getSourceMethod(hook)).thenReturn(Map.of(before, original));

        assertEquals("\n## Extension interception\n\n"
            + "- `fresh_Initialize` -> intercepts core method `Initialize` via `&Before`\n",
            InterceptionUtils.moduleFooter(extension, svc));
        verify(svc, never()).getExtensionModules(extension);
    }

    @Test
    public void summaryOmitsTheSectionWhenThereAreNoMatches()
    {
        Module base = objectModule("Base");
        Method method = addMethod(base, "Initialize");
        Module extension = objectModule("Base.Fresh");
        IModuleExtensionService svc = mock(IModuleExtensionService.class);
        when(svc.getExtensionModules(base)).thenReturn(List.of(extension));
        assertNull(InterceptionUtils.moduleFooter(base, svc));
        assertNull(InterceptionUtils.methodFooter(base, method, svc));
    }
}
