/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.xtext.resource.XtextResource;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.BslStandaloneSetup;
import com._1c.g5.v8.dt.bsl.model.Module;

/**
 * #987: {@code get_module_structure} lists both rows of a name the module declares twice - EDT's model keeps
 * both - and says above the table that the name repeats, where, and what that means.
 */
public class GetModuleStructureDuplicatesTest
{
    /** Loads through EDT's BSL resource, as {@link GetModuleStructureToolTest} does. */
    private static Module parseModule(List<String> lines)
    {
        new BslStandaloneSetup().createInjectorAndDoEMFRegistration();
        var provider = org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(com.ditrix.edt.mcp.server.utils.BslModuleUtils.BSL_LOOKUP_URI);
        XtextResource resource = provider.get(XtextResource.class);
        var set = provider.get(org.eclipse.xtext.resource.XtextResourceSet.class);
        resource.setURI(com.ditrix.edt.mcp.server.utils.BslModuleUtils.BSL_LOOKUP_URI);
        set.getResources().add(resource);
        try (var input = new java.io.ByteArrayInputStream(
            (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        {
            resource.load(input, java.util.Map.of(XtextResource.OPTION_ENCODING, "UTF-8")); //$NON-NLS-1$
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError(e);
        }
        return (Module)resource.getContents().get(0);
    }

    private static String structure(List<String> source)
    {
        return new GetModuleStructureTool().formatStructure("Module.bsl", parseModule(source), source, false, false, //$NON-NLS-1$
            false);
    }

    @Test
    public void aNameDeclaredTwiceAtTopLevelIsNamedAboveTheTable()
    {
        String result = structure(List.of("Функция Ф(П) Экспорт", "\tВозврат П;", "КонецФункции", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Функция ф(П)", "\tВозврат 2;", "КонецФункции")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result, result.contains("> **Repeated method names.** Ф (lines 1, 5): They share one preprocessor " //$NON-NLS-1$
            + "context, so the 1C platform will refuse to load the module")); //$NON-NLS-1$
        assertTrue(result, result.contains("**Total:** 0 procedures, 2 functions")); //$NON-NLS-1$
        int notice = result.indexOf("Repeated method names"); //$NON-NLS-1$
        assertTrue("the notice comes before the table", notice < result.indexOf("### Methods")); //$NON-NLS-1$
    }

    @Test
    public void namesInDifferentBranchesAreNamedAsSuch()
    {
        String result = structure(List.of("#If Server Then", "Procedure P()", "EndProcedure", "#Else", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Procedure P()", "EndProcedure", "#EndIf")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result, result.contains("P (lines 2, 5): They sit in different branches of the #If block opened " //$NON-NLS-1$
            + "at line 1")); //$NON-NLS-1$
    }

    @Test
    public void aModuleWithoutRepeatedNamesHasNoNotice()
    {
        String result = structure(List.of("Procedure A()", "EndProcedure", "Procedure B()", "EndProcedure")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(result, result.contains("Repeated method names")); //$NON-NLS-1$
    }
}
