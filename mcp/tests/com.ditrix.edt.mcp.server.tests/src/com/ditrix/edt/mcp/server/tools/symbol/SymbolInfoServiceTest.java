/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.symbol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;

/**
 * Tests for {@link SymbolInfoService}.
 * <p>
 * Two layers:
 * <ul>
 * <li>{@link SymbolInfoService#meaningfulOrNull(String)} — the pure string guard that keeps
 * get_symbol_info from leaking a bare {@code Object.toString()} reference when a hover (e.g. an
 * annotation/quick-fix hover at a validation-marked position) has no extractable text.</li>
 * <li>{@link SymbolInfoService#buildEObjectInfo(org.eclipse.emf.ecore.EObject)} — the per-kind
 * Markdown-table formatter. It is driven here with in-memory {@code BslFactory}-built BSL model
 * objects (no live workbench, editor, Xtext resource or injector). Every branch exercised below
 * reads only EMF getters on the constructed object; {@code getStartLine}/{@code getEndLine}
 * return 0 for an object with no node model, so the "Lines" row is simply omitted — no exception.
 * The live editor/hover path of {@code getSymbolInfo} needs a real workbench and is e2e-covered.</li>
 * </ul>
 * Same-package access reaches the package-private {@code buildEObjectInfo}, matching the seam
 * convention used by {@code AbstractMetadataFormatterTest}.
 */
public class SymbolInfoServiceTest
{
    private final SymbolInfoService service = new SymbolInfoService();

    // ==================== meaningfulOrNull — the pure string guard ====================

    @Test
    public void testRawObjectReferenceBecomesNull()
    {
        // The exact shape observed in the audit: a BslAnnotationInfo quick-fix hover.
        assertNull(SymbolInfoService.meaningfulOrNull(
            "com._1c.g5.v8.dt.bsl.ui.hover.BslAnnotationWithQuickFixesHover$BslAnnotationInfo@1b8ef34a"));
    }

    @Test
    public void testSimpleClassAtHashBecomesNull()
    {
        assertNull(SymbolInfoService.meaningfulOrNull("Foo@deadbeef"));
    }

    @Test
    public void testNullAndBlankBecomeNull()
    {
        assertNull(SymbolInfoService.meaningfulOrNull(null));
        assertNull(SymbolInfoService.meaningfulOrNull("   "));
    }

    @Test
    public void testRealHoverContentIsKept()
    {
        String real = "Parameter Minuend\nPassing: by reference";
        assertEquals(real, SymbolInfoService.meaningfulOrNull(real));
    }

    @Test
    public void testTextWithAtSignInProseIsKept()
    {
        // A real hover with an @ inside multi-token prose is NOT a bare ClassName@hash.
        String s = "Annotation &Вместо(\"Method\") — overrides at line 9";
        assertEquals(s, SymbolInfoService.meaningfulOrNull(s));
    }

    // ==================== cell / codeCell — the pure row builders ====================

    @Test
    public void testCellEscapesPipe()
    {
        // A '|' in the value must be escaped so it cannot break the table column.
        assertEquals("| **Type** | String \\| Number |\n",
            SymbolInfoService.cell("Type", "String | Number"));
    }

    @Test
    public void testCellNullValueRendersEmpty()
    {
        assertEquals("| **Kind** |  |\n", SymbolInfoService.cell("Kind", null));
    }

    @Test
    public void testCodeCellWrapsInBackticks()
    {
        assertEquals("| **Symbol** | `Name` |\n", SymbolInfoService.codeCell("Symbol", "Name"));
    }

    // ==================== buildEObjectInfo — per-kind formatting ====================

    @Test
    public void testProcedureInfo()
    {
        Procedure proc = BslFactory.eINSTANCE.createProcedure();
        proc.setName("DoWork");

        String info = service.buildEObjectInfo(proc);

        assertTrue("has table header", info.startsWith("| Property | Value |"));
        assertTrue("symbol row", info.contains("| **Symbol** | `DoWork` |"));
        assertTrue("kind is Procedure", info.contains("| **Kind** | Procedure |"));
        assertTrue("signature row", info.contains("| **Signature** | `Procedure DoWork(-)` |"));
        assertTrue("not exported", info.contains("| **Export** | No |"));
        // No node model on an in-memory object -> no "Lines" row.
        assertFalse("no lines row without node model", info.contains("**Lines**"));
    }

    @Test
    public void testExportedFunctionWithParameter()
    {
        Function fn = BslFactory.eINSTANCE.createFunction();
        fn.setName("Compute");
        fn.setExport(true);

        FormalParam p = BslFactory.eINSTANCE.createFormalParam();
        p.setName("Source");
        p.setByValue(true);
        fn.getFormalParams().add(p);

        String info = service.buildEObjectInfo(fn);

        assertTrue("kind is Function", info.contains("| **Kind** | Function |"));
        assertTrue("exported", info.contains("| **Export** | Yes |"));
        // ByVal parameter appears in both the signature and the Parameters row.
        assertTrue("signature includes Val param and Export",
            info.contains("| **Signature** | `Function Compute(Val Source) Export` |"));
        assertTrue("parameters row", info.contains("| **Parameters** | Val Source |"));
    }

    @Test
    public void testFormalParamInfo()
    {
        // A param contained by a method so the "In method" row is exercised too.
        Method owner = BslFactory.eINSTANCE.createProcedure();
        owner.setName("Owner");

        FormalParam p = BslFactory.eINSTANCE.createFormalParam();
        p.setName("Value");
        p.setByValue(false);
        owner.getFormalParams().add(p);

        String info = service.buildEObjectInfo(p);

        assertTrue("symbol row", info.contains("| **Symbol** | `Value` |"));
        assertTrue("kind is Parameter", info.contains("| **Kind** | Parameter |"));
        assertTrue("not by value", info.contains("| **ByValue** | No |"));
        assertTrue("containing method", info.contains("| **In method** | `Owner` |"));
    }

    @Test
    public void testStaticFeatureAccessInfo()
    {
        StaticFeatureAccess sfa = BslFactory.eINSTANCE.createStaticFeatureAccess();
        sfa.setName("MyVar");

        String info = service.buildEObjectInfo(sfa);

        assertTrue("symbol row", info.contains("| **Symbol** | `MyVar` |"));
        assertTrue("kind", info.contains("| **Kind** | StaticFeatureAccess |"));
        assertTrue("emf type row", info.contains("| **EMF type** | StaticFeatureAccess |"));
    }

    @Test
    public void testDynamicFeatureAccessInfo()
    {
        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName("Property");

        String info = service.buildEObjectInfo(dfa);

        assertTrue("symbol row", info.contains("| **Symbol** | `Property` |"));
        assertTrue("kind", info.contains("| **Kind** | DynamicFeatureAccess |"));
        assertTrue("emf type row", info.contains("| **EMF type** | DynamicFeatureAccess |"));
    }

    @Test
    public void testInvocationInfoUsesMethodAccessName()
    {
        StaticFeatureAccess called = BslFactory.eINSTANCE.createStaticFeatureAccess();
        called.setName("Message");

        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(called);

        String info = service.buildEObjectInfo(inv);

        assertTrue("kind is Invocation", info.contains("| **Kind** | Invocation |"));
        assertTrue("emf type row", info.contains("| **EMF type** | Invocation |"));
        assertTrue("symbol taken from method access", info.contains("| **Symbol** | `Message` |"));
    }

    @Test
    public void testModuleInfo()
    {
        Module module = BslFactory.eINSTANCE.createModule();

        String info = service.buildEObjectInfo(module);

        assertTrue("kind is Module", info.contains("| **Kind** | Module |"));
        assertTrue("emf type row", info.contains("| **EMF type** | Module |"));
    }
}
