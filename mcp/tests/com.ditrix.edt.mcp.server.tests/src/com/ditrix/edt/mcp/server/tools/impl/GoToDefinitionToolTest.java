/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GoToDefinitionTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation
 * sentinels that return before any Eclipse/EDT access. The actual definition
 * resolution runs on the UI thread via {@code PlatformUI.getWorkbench()} and
 * needs a live workbench, so it is covered by the E2E suite, not here.
 * <p>
 * Bilingual note: the Russian-to-English FQN normalization these navigation
 * tools rely on lives in the shared {@code MetadataTypeUtils.normalizeFqn} and
 * is exhaustively tested in {@code MetadataTypeUtilsTest} (e.g. Документ.Встреча,
 * Справочник.УслугиSLA). This test only asserts that a non-canonical-dialect
 * symbol passes argument validation (i.e. is not rejected as missing/blank).
 */
public class GoToDefinitionToolTest
{
    // ==================== Tool metadata ====================

    @Test
    public void testName()
    {
        assertEquals("go_to_definition", new GoToDefinitionTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GoToDefinitionTool.NAME, new GoToDefinitionTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.MARKDOWN, new GoToDefinitionTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GoToDefinitionTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        String schema = new GoToDefinitionTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"symbol\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
    }

    /**
     * The slimmed description points to the on-demand guide channel and still
     * names the inverse-of relationship used for tool selection.
     */
    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new GoToDefinitionTool().getDescription();
        assertTrue(desc.contains("find_references")); //$NON-NLS-1$
        assertTrue(desc.contains("get_tool_guide('go_to_definition')")); //$NON-NLS-1$
    }

    /**
     * The exhaustive detail (modes, conditional modulePath rule, bilingual
     * notes) moved out of the always-loaded description/schema into getGuide().
     * Assert it landed there rather than vanishing.
     */
    @Test
    public void testGuideNotEmptyAndContainsMigratedDetail()
    {
        String guide = new GoToDefinitionTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        // Conditional-parameter rule migrated from the schema/description.
        assertTrue(guide.contains("modulePath")); //$NON-NLS-1$
        // The three symbol forms / modes section migrated into the guide.
        assertTrue(guide.contains("Modes")); //$NON-NLS-1$
        // Metadata-FQN form example migrated into the guide.
        assertTrue(guide.contains("Catalog.Products")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GoToDefinitionTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingSymbol()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GoToDefinitionTool().execute(params);
        assertTrue(result.contains("symbol is required")); //$NON-NLS-1$
    }

    @Test
    public void testBareMethodNeedsModulePath()
    {
        // An unqualified method name (no dot) must be rejected without modulePath.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("symbol", "MyMethod"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GoToDefinitionTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    /**
     * A symbol written in the Russian dialect (Справочник.Товары) must pass
     * argument validation — it is a valid symbol, so the tool must NOT reject
     * it as missing. (Resolution itself is exercised by E2E / MetadataTypeUtilsTest.)
     */
    @Test
    public void testRussianSymbolPassesValidation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); // blank on purpose: projectName is checked first //$NON-NLS-1$ //$NON-NLS-2$
        params.put("symbol", //$NON-NLS-1$
            "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u0422\u043E\u0432\u0430\u0440\u044B"); // Справочник.Товары //$NON-NLS-1$
        String result = new GoToDefinitionTool().execute(params);
        // projectName is blank → we get the projectName sentinel, proving the
        // Russian symbol was accepted (never reported as "symbol is required").
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse(result.contains("symbol is required")); //$NON-NLS-1$
    }

    /**
     * Blank (empty-string) projectName is treated as missing by the required-argument
     * guard, exactly like an absent key.
     */
    @Test
    public void testBlankProjectNameIsMissing()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("symbol", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GoToDefinitionTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    /**
     * Blank symbol is treated as missing — projectName is present, so the symbol
     * sentinel fires (the symbol guard is the second one checked).
     */
    @Test
    public void testBlankSymbolIsMissing()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("symbol", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GoToDefinitionTool().execute(params);
        assertTrue(result.contains("symbol is required")); //$NON-NLS-1$
    }

    /**
     * A bare method name WITH a blank modulePath is still rejected — an empty
     * modulePath does not satisfy the "module context required" rule.
     */
    @Test
    public void testBareMethodWithBlankModulePathIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("symbol", "MyMethod"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GoToDefinitionTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    // ==================== getResultFileName (pure, no workbench) ====================

    /**
     * A qualified two-part symbol is turned into a per-symbol result filename:
     * the dot becomes a dash and the whole name is lower-cased.
     */
    @Test
    public void testResultFileNameForQualifiedSymbol()
    {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("definition-catalog-products.md", //$NON-NLS-1$
            new GoToDefinitionTool().getResultFileName(params));
    }

    /**
     * A bare (single-part) symbol still produces a per-symbol filename — no dot,
     * so nothing to replace, just the lower-cased name.
     */
    @Test
    public void testResultFileNameForBareSymbol()
    {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "MyMethod"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("definition-mymethod.md", //$NON-NLS-1$
            new GoToDefinitionTool().getResultFileName(params));
    }

    /**
     * The result filename is lower-cased even for an already-mixed-case symbol,
     * and every dot is replaced (not just the first).
     */
    @Test
    public void testResultFileNameLowercasesAndReplacesEveryDot()
    {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", "InformationRegister.Rates.RecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("definition-informationregister-rates-recordset.md", //$NON-NLS-1$
            new GoToDefinitionTool().getResultFileName(params));
    }

    /**
     * With no symbol argument the generic fallback filename is used (the per-symbol
     * branch is guarded by a non-empty check).
     */
    @Test
    public void testResultFileNameFallbackWhenSymbolMissing()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("definition.md", new GoToDefinitionTool().getResultFileName(params)); //$NON-NLS-1$
    }

    /**
     * An empty-string symbol also takes the fallback branch (the guard rejects both
     * null and empty), so it does not yield "definition-.md".
     */
    @Test
    public void testResultFileNameFallbackWhenSymbolBlank()
    {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("definition.md", new GoToDefinitionTool().getResultFileName(params)); //$NON-NLS-1$
    }

    /**
     * An empty params map (no symbol key) falls back to the generic filename
     * without throwing.
     */
    @Test
    public void testResultFileNameWithEmptyParams()
    {
        assertEquals("definition.md", //$NON-NLS-1$
            new GoToDefinitionTool().getResultFileName(new HashMap<>()));
    }
}
