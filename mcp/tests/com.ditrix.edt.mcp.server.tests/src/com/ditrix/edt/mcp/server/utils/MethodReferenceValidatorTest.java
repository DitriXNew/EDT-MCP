/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonParser;

/**
 * Tests the pure, model-independent logic of {@link MethodReferenceValidator}: parsing a
 * {@code <ModuleName>.<MethodName>} reference (with/without a bilingual {@code CommonModule} type-token
 * prefix), the {@code Export} keyword detection from raw source lines (single-line signature / a
 * parameter list spanning multiple lines / not exported at all), and the {@link
 * MethodReferenceValidator#decide} decision (method missing, not exported, module not Server, valid).
 * The {@link org.eclipse.core.resources.IProject}-touching {@link MethodReferenceValidator#validate}
 * seam (module resolution + {@code Module.bsl} reading) is covered by the e2e suite against a live
 * ScheduledJob / EventSubscription.
 */
public class MethodReferenceValidatorTest
{
    private static final String LABEL = "methodName"; //$NON-NLS-1$
    private static final String FORMAT = "'CommonModuleName.MethodName'"; //$NON-NLS-1$
    private static final String EXAMPLE = "Calc.Add"; //$NON-NLS-1$

    private static String errorOf(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    private static String fromCp(int... codePoints)
    {
        return new String(codePoints, 0, codePoints.length);
    }

    private static CommonModule moduleWithServerFlag(boolean server)
    {
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setServer(server);
        return module;
    }

    // ---- parse --------------------------------------------------------------------------------

    @Test
    public void testParsePlainModuleDotMethod()
    {
        MethodReferenceValidator.ParsedReference r =
            MethodReferenceValidator.parse("Calc.Add", LABEL, FORMAT, EXAMPLE); //$NON-NLS-1$
        assertNull(r.error);
        assertEquals("Calc", r.moduleName); //$NON-NLS-1$
        assertEquals("Add", r.methodName); //$NON-NLS-1$
    }

    @Test
    public void testParseStripsEnglishCommonModulePrefix()
    {
        MethodReferenceValidator.ParsedReference r =
            MethodReferenceValidator.parse("CommonModule.Calc.Add", LABEL, FORMAT, EXAMPLE); //$NON-NLS-1$
        assertNull(r.error);
        assertEquals("Calc", r.moduleName); //$NON-NLS-1$
        assertEquals("Add", r.methodName); //$NON-NLS-1$
    }

    @Test
    public void testParseStripsRussianCommonModulePrefix()
    {
        // ОбщийМодуль (= CommonModule) built from code points, so the assertion verifies the REAL
        // Cyrillic mapping through MetadataTypeUtils, not a round-trip of the same literal (the
        // bilingual-test convention: see CommonAttributeContentWriterTest).
        String ru = fromCp(0x041e, 0x0431, 0x0449, 0x0438, 0x0439, 0x041c, 0x043e, 0x0434, 0x0443, 0x043b,
            0x044c);
        MethodReferenceValidator.ParsedReference r =
            MethodReferenceValidator.parse(ru + ".Calc.Add", LABEL, FORMAT, EXAMPLE); //$NON-NLS-1$
        assertNull(r.error);
        assertEquals("Calc", r.moduleName); //$NON-NLS-1$
        assertEquals("Add", r.methodName); //$NON-NLS-1$
    }

    @Test
    public void testParseNoDotIsActionableError()
    {
        MethodReferenceValidator.ParsedReference r =
            MethodReferenceValidator.parse("JustAName", LABEL, FORMAT, EXAMPLE); //$NON-NLS-1$
        assertNotNull(r.error);
        String msg = errorOf(r.error);
        assertTrue("must name the bad value", msg.contains("JustAName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the property", msg.contains(LABEL)); //$NON-NLS-1$
        assertTrue("must show an example", msg.contains(EXAMPLE)); //$NON-NLS-1$
    }

    @Test
    public void testParseTrailingDotIsActionableError()
    {
        MethodReferenceValidator.ParsedReference r =
            MethodReferenceValidator.parse("Calc.", LABEL, FORMAT, EXAMPLE); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue(errorOf(r.error).contains("Calc.")); //$NON-NLS-1$
    }

    @Test
    public void testParseLeadingDotIsActionableError()
    {
        MethodReferenceValidator.ParsedReference r =
            MethodReferenceValidator.parse(".Add", LABEL, FORMAT, EXAMPLE); //$NON-NLS-1$
        assertNotNull(r.error);
    }

    @Test
    public void testParseNullValueIsActionableError()
    {
        MethodReferenceValidator.ParsedReference r = MethodReferenceValidator.parse(null, LABEL, FORMAT, EXAMPLE);
        assertNotNull(r.error);
    }

    @Test
    public void testParseNonPrefixModulePartWithDotIsKeptVerbatim()
    {
        // "Foo.Calc.Add": the segment before the module part's own first dot ("Foo") does NOT resolve
        // to CommonModule, so nothing is stripped - the whole module part is kept, proving the prefix
        // strip is a real type-token lookup, not "drop anything before the last dot".
        MethodReferenceValidator.ParsedReference r =
            MethodReferenceValidator.parse("Foo.Calc.Add", LABEL, FORMAT, EXAMPLE); //$NON-NLS-1$
        assertNull(r.error);
        assertEquals("Foo.Calc", r.moduleName); //$NON-NLS-1$
        assertEquals("Add", r.methodName); //$NON-NLS-1$
    }

    // ---- isExported -----------------------------------------------------------------------------

    @Test
    public void testIsExportedSingleLineSignature()
    {
        List<String> lines = List.of(
            "Function Add(A, B) Export", //$NON-NLS-1$
            "  Return A + B;", //$NON-NLS-1$
            "EndFunction"); //$NON-NLS-1$
        BslModuleUtils.TextMethod found = BslModuleUtils.findMethodViaText(lines, "Add"); //$NON-NLS-1$
        assertTrue(found.found);
        assertTrue(MethodReferenceValidator.isExported(lines, found));
    }

    @Test
    public void testIsExportedParamsSpanMultipleLines()
    {
        List<String> lines = List.of(
            "Procedure Foo(", //$NON-NLS-1$
            "    A,", //$NON-NLS-1$
            "    B) Export", //$NON-NLS-1$
            "  DoSomething();", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        BslModuleUtils.TextMethod found = BslModuleUtils.findMethodViaText(lines, "Foo"); //$NON-NLS-1$
        assertTrue(found.found);
        assertTrue(MethodReferenceValidator.isExported(lines, found));
    }

    @Test
    public void testIsExportedFalseWhenNoExportKeyword()
    {
        List<String> lines = List.of(
            "Procedure Foo(A, B)", //$NON-NLS-1$
            "  DoSomething();", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        BslModuleUtils.TextMethod found = BslModuleUtils.findMethodViaText(lines, "Foo"); //$NON-NLS-1$
        assertTrue(found.found);
        assertFalse(MethodReferenceValidator.isExported(lines, found));
    }

    @Test
    public void testIsExportedFalseWhenNotExportedButBodyMentionsTheWord()
    {
        // The body (past the closing paren) mentions "Export" in a comment - must NOT be mistaken
        // for the modifier: the scan stops at the line that closes the parameter list.
        List<String> lines = List.of(
            "Procedure Foo(A, B)", //$NON-NLS-1$
            "  // Export note: not the real modifier", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        BslModuleUtils.TextMethod found = BslModuleUtils.findMethodViaText(lines, "Foo"); //$NON-NLS-1$
        assertTrue(found.found);
        assertFalse(MethodReferenceValidator.isExported(lines, found));
    }

    @Test
    public void testIsExportedRussianKeyword()
    {
        // Функция Сумма(А, Б) Экспорт / Возврат А + Б; / КонецФункции - built from code points.
        String func = fromCp(0x0424, 0x0443, 0x043d, 0x043a, 0x0446, 0x0438, 0x044f); // Функция
        String sum = fromCp(0x0421, 0x0443, 0x043c, 0x043c, 0x0430); // Сумма
        String export = fromCp(0x042d, 0x043a, 0x0441, 0x043f, 0x043e, 0x0440, 0x0442); // Экспорт
        String endFunc =
            fromCp(0x041a, 0x043e, 0x043d, 0x0435, 0x0446, 0x0424, 0x0443, 0x043d, 0x043a, 0x0446, 0x0438,
                0x0438); // КонецФункции
        List<String> lines = List.of(
            func + " " + sum + "(A, B) " + export, //$NON-NLS-1$ //$NON-NLS-2$
            "  Return A + B;", //$NON-NLS-1$
            endFunc);
        BslModuleUtils.TextMethod found = BslModuleUtils.findMethodViaText(lines, sum);
        assertTrue(found.found);
        assertTrue(MethodReferenceValidator.isExported(lines, found));
    }

    @Test
    public void testIsExportedIncludesAdjacentDocCommentInScanRange()
    {
        // startLine (from findMethodViaText) points at the doc comment, not the declaration; isExported
        // must still locate the REAL declaration line and detect Export there.
        List<String> lines = List.of(
            "// Adds two numbers.", //$NON-NLS-1$
            "Function Add(A, B) Export", //$NON-NLS-1$
            "  Return A + B;", //$NON-NLS-1$
            "EndFunction"); //$NON-NLS-1$
        BslModuleUtils.TextMethod found = BslModuleUtils.findMethodViaText(lines, "Add"); //$NON-NLS-1$
        assertTrue(found.found);
        assertEquals(0, found.startLine); // doc-comment line, not the declaration
        assertTrue(MethodReferenceValidator.isExported(lines, found));
    }

    // ---- decide ---------------------------------------------------------------------------------

    @Test
    public void testDecideMethodNotFoundWhenLinesNull()
    {
        String json = MethodReferenceValidator.decide(moduleWithServerFlag(true), "Calc", null, "Add", LABEL); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(json);
        String msg = errorOf(json);
        assertTrue(msg.contains("Add")); //$NON-NLS-1$
        assertTrue(msg.contains("Calc")); //$NON-NLS-1$
        assertTrue("must point at write_module_source", msg.contains("write_module_source")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDecideMethodNotFoundWhenMissingFromSource()
    {
        List<String> lines = List.of("Function Other() Export", "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$
        String json = MethodReferenceValidator.decide(moduleWithServerFlag(true), "Calc", lines, "Add", LABEL); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(errorOf(json).contains("not found")); //$NON-NLS-1$
    }

    @Test
    public void testDecideNotExported()
    {
        List<String> lines = List.of("Function Add(A, B)", "  Return A + B;", "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String json = MethodReferenceValidator.decide(moduleWithServerFlag(true), "Calc", lines, "Add", LABEL); //$NON-NLS-1$ //$NON-NLS-2$
        String msg = errorOf(json);
        assertTrue(msg.contains("Export")); //$NON-NLS-1$
        assertTrue(msg.contains("Calc")); //$NON-NLS-1$
        assertTrue(msg.contains("Add")); //$NON-NLS-1$
    }

    @Test
    public void testDecideNotServer()
    {
        List<String> lines = List.of("Function Add(A, B) Export", "  Return A + B;", "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String json = MethodReferenceValidator.decide(moduleWithServerFlag(false), "Calc", lines, "Add", LABEL); //$NON-NLS-1$ //$NON-NLS-2$
        String msg = errorOf(json);
        assertTrue(msg.contains("Server")); //$NON-NLS-1$
        assertTrue(msg.contains("Calc")); //$NON-NLS-1$
    }

    @Test
    public void testDecideValidReturnsNull()
    {
        List<String> lines = List.of("Function Add(A, B) Export", "  Return A + B;", "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String json = MethodReferenceValidator.decide(moduleWithServerFlag(true), "Calc", lines, "Add", LABEL); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(json);
    }

    @Test
    public void testDecideChecksExportBeforeServer()
    {
        // A non-exported method on a non-server module must report the EXPORT problem first (fix one
        // thing at a time - matches the checklist order: exists -> exported -> server).
        List<String> lines = List.of("Function Add(A, B)", "  Return A + B;", "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String json = MethodReferenceValidator.decide(moduleWithServerFlag(false), "Calc", lines, "Add", LABEL); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(errorOf(json).contains("Export")); //$NON-NLS-1$
    }

    // ---- review round: comment-stripping + canonical form ---------------------------------------

    @Test
    public void testTrailingCommentExportIsNotExported()
    {
        // `Procedure Foo() // TODO: Export` - the keyword lives in a trailing comment, not the
        // modifier position, and must NOT count as exported.
        List<String> lines = List.of(
            "Procedure Foo() // TODO: Export", //$NON-NLS-1$
            "  Return;", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        BslModuleUtils.TextMethod found = BslModuleUtils.findMethodViaText(lines, "Foo"); //$NON-NLS-1$
        assertTrue(found.found);
        assertFalse("Export inside a trailing comment must NOT count as the modifier", //$NON-NLS-1$
            MethodReferenceValidator.isExported(lines, found));
    }

    @Test
    public void testStripInlineCommentKeepsSlashesInsideStrings()
    {
        assertEquals("Procedure Foo(A = \"http://x\") Export ", //$NON-NLS-1$
            MethodReferenceValidator.stripInlineComment("Procedure Foo(A = \"http://x\") Export // note")); //$NON-NLS-1$
        assertEquals("Procedure Foo() ", //$NON-NLS-1$
            MethodReferenceValidator.stripInlineComment("Procedure Foo() // Export")); //$NON-NLS-1$
    }

    @Test
    public void testCanonicalReferenceResolvedCasingAndPrefixes()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Calc"); //$NON-NLS-1$
        config.getCommonModules().add(module);
        // methodName form: no type prefix, resolved metadata casing (findObject is case-insensitive).
        assertEquals("Calc.Add", MethodReferenceValidator.canonicalReference(config, "CommonModule.Calc.Add", false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Calc.Add", MethodReferenceValidator.canonicalReference(config, "calc.Add", false)); //$NON-NLS-1$ //$NON-NLS-2$
        // handler form: English CommonModule prefix restored regardless of the input variant.
        assertEquals("CommonModule.Calc.Add", MethodReferenceValidator.canonicalReference(config, "Calc.Add", true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModule.Calc.Add", //$NON-NLS-1$
            MethodReferenceValidator.canonicalReference(config, "\u041E\u0431\u0449\u0438\u0439\u041C\u043E\u0434\u0443\u043B\u044C.Calc.Add", true)); //$NON-NLS-1$
        // Defensive: an unresolvable module yields null (caller keeps the raw value).
        assertNull(MethodReferenceValidator.canonicalReference(config, "NoSuch.Add", false)); //$NON-NLS-1$
    }
}
