/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tests for {@link GetMetadataObjectsTool}.
 * <p>
 * Covers tool metadata (name/constant, response type, description, input schema,
 * output schema, result file name, guide) and the {@code projectName}
 * required-argument validation in {@code execute(Map)} that returns BEFORE the
 * first {@code PlatformUI.getWorkbench().getDisplay()} call. Pure {@link CommonModule}
 * matching and collection are covered directly; project/scope resolution and final
 * formatting need a live EDT workspace and are covered by the E2E suite.
 */
public class GetMetadataObjectsToolTest
{
    // ==================== Metadata: name / response type ====================

    @Test
    public void testName()
    {
        assertEquals("get_metadata_objects", new GetMetadataObjectsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMetadataObjectsTool.NAME, new GetMetadataObjectsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetMetadataObjectsTool().getResponseType());
    }

    // ==================== Metadata: description ====================

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetMetadataObjectsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testDescriptionSteersToGuideAndSiblingTool()
    {
        // The lean description must point at the on-demand guide for the full parameter
        // set and at get_metadata_details for the single-object drill-down.
        String desc = new GetMetadataObjectsTool().getDescription();
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('get_metadata_objects')")); //$NON-NLS-1$
        assertTrue("description must point at get_metadata_details for one object", //$NON-NLS-1$
            new GetMetadataObjectsTool().getGuide().contains("get_metadata_details")); //$NON-NLS-1$
    }

    // ==================== Metadata: input schema ====================

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetMetadataObjectsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"metadataType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"nameFilter\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"textFilter\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresLimitAndLanguageParameters()
    {
        String schema = new GetMetadataObjectsTool().getInputSchema();
        assertTrue("schema must declare the limit parameter", schema.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare the language parameter", //$NON-NLS-1$
            schema.contains("\"language\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameIsRequiredInSchema()
    {
        // projectName is the only required parameter; the optional filters must NOT be
        // in the required array.
        String schema = new GetMetadataObjectsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("metadataType must NOT be required", //$NON-NLS-1$
            !requiredBlock.contains("\"metadataType\"")); //$NON-NLS-1$
        assertFalse("nameFilter must NOT be required", requiredBlock.contains("\"nameFilter\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("textFilter must NOT be required", //$NON-NLS-1$
            requiredBlock.contains("\"textFilter\"")); //$NON-NLS-1$
        assertFalse("limit must NOT be required", requiredBlock.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", requiredBlock.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Metadata: output schema ====================

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // This is a MARKDOWN tool: it returns content, not structuredContent, so it must
        // inherit the IMcpTool default null output schema (over-declaring one would lie to
        // clients about a structured envelope that never arrives).
        assertNull("markdown tool must not declare an output schema", //$NON-NLS-1$
            new GetMetadataObjectsTool().getOutputSchema());
    }

    // ==================== Metadata: result file name (both branches, no workspace) ====================

    @Test
    public void testResultFileNameUsesLowercasedProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("metadata-myproject.md", //$NON-NLS-1$
            new GetMetadataObjectsTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallbackWhenProjectNameMissing()
    {
        // No projectName -> the generic file name.
        Map<String, String> params = new HashMap<>();
        assertEquals("metadata-objects.md", //$NON-NLS-1$
            new GetMetadataObjectsTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallbackWhenProjectNameEmpty()
    {
        // An empty projectName is treated like a missing one for the file name.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("metadata-objects.md", //$NON-NLS-1$
            new GetMetadataObjectsTool().getResultFileName(params));
    }

    // ==================== Metadata: guide ====================

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive per-tool detail moved out of the always-loaded
        // description/schema and into the on-demand guide channel. The guide
        // must be non-empty and still carry the type vocabulary, both text-filter
        // rules, and the module/subsystem boundaries.
        String guide = new GetMetadataObjectsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("HTTPService")); //$NON-NLS-1$
        assertTrue(guide.contains("nameFilter")); //$NON-NLS-1$
        assertTrue(guide.contains("textFilter")); //$NON-NLS-1$
        assertTrue(guide.contains("ManagerModule")); //$NON-NLS-1$
        assertTrue(guide.contains("list_subsystems")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any workbench access) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetMetadataObjectsTool().execute(params);
        // Genuine errors now return a ToolResult.error JSON payload (success=false,
        // error=<message>). "projectName is required" has no delimiter characters,
        // so Gson does not unicode-escape it and the substring survives verbatim.
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"error\"")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // The required-argument guard appends an actionable discovery hint pointing the
        // caller at list_projects; pin it so the lean error stays self-service.
        Map<String, String> params = new HashMap<>();
        String result = new GetMetadataObjectsTool().execute(params);
        assertTrue("error must steer the caller to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyProjectNameIsError()
    {
        // An empty (blank) projectName is rejected by the same required-argument guard,
        // before any workbench access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMetadataObjectsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameIgnoresOtherArgs()
    {
        // Supplying metadataType/nameFilter/limit/language without a projectName still
        // trips the projectName guard first (the guard runs before defaults and before
        // the workbench is touched), so the metadataType value is never validated here.
        Map<String, String> params = new HashMap<>();
        params.put("metadataType", "catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("nameFilter", "Prod"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("limit", "50"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("language", "en"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMetadataObjectsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue("an invalid metadataType must NOT leak through before the projectName guard", //$NON-NLS-1$
            !result.contains("Unknown metadata type")); //$NON-NLS-1$
    }

    @Test
    public void testNameAndTextFiltersAreMutuallyExclusive()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("nameFilter", "DebtAdjustment"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("textFilter", "Debt adjustment"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new GetMetadataObjectsTool().execute(params);

        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("Use either nameFilter or textFilter, not both")); //$NON-NLS-1$
        assertTrue(result.contains("nameFilter='DebtAdjustment'")); //$NON-NLS-1$
        assertTrue(result.contains("textFilter='Debt adjustment'")); //$NON-NLS-1$
        assertTrue(result.contains("programmatic Name")); //$NON-NLS-1$
        assertTrue(result.contains("effective language")); //$NON-NLS-1$
    }

    // ==================== Name / localized-text matching (pure model logic) ====================

    @Test
    public void testTextFilterMatchesEnglishNameCaseInsensitively()
    {
        CommonModule object = metadataObject("DebtAdjustment", "Debt adjustment", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041A\u043E\u0440\u0440\u0435\u043A\u0442\u0438\u0440\u043E\u0432\u043A\u0430 " //$NON-NLS-1$
            + "\u0434\u043E\u043B\u0433\u0430"); //$NON-NLS-1$

        assertTrue(new GetMetadataObjectsTool().matches(object, "adjust", //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.TEXT, "en")); //$NON-NLS-1$
    }

    @Test
    public void testTextFilterMatchesRussianNameCaseInsensitively()
    {
        String russianName = "\u0412\u0437\u0430\u0438\u043C\u043E\u0437\u0430\u0447\u0435\u0442"; //$NON-NLS-1$
        CommonModule object = metadataObject(russianName, "Offset", //$NON-NLS-1$
            "\u041A\u043E\u0440\u0440\u0435\u043A\u0442\u0438\u0440\u043E\u0432\u043A\u0430 " //$NON-NLS-1$
            + "\u0434\u043E\u043B\u0433\u0430"); //$NON-NLS-1$

        assertTrue(new GetMetadataObjectsTool().matches(object,
            "\u0437\u0430\u0447\u0435\u0442", //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.TEXT, "en")); //$NON-NLS-1$
    }

    @Test
    public void testTextFilterMatchesRussianSynonymInRussian()
    {
        CommonModule object = metadataObject("DebtOffset", "Debt offset", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041A\u043E\u0440\u0440\u0435\u043A\u0442\u0438\u0440\u043E\u0432\u043A\u0430 " //$NON-NLS-1$
            + "\u0434\u043E\u043B\u0433\u0430"); //$NON-NLS-1$

        assertTrue(new GetMetadataObjectsTool().matches(object,
            "\u043A\u043E\u0440\u0440\u0435\u043A\u0442", //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.TEXT, "ru")); //$NON-NLS-1$
    }

    @Test
    public void testTextFilterDoesNotMatchSynonymFromAnotherLanguage()
    {
        CommonModule object = metadataObject("DebtOffset", "Debt offset", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041A\u043E\u0440\u0440\u0435\u043A\u0442\u0438\u0440\u043E\u0432\u043A\u0430 " //$NON-NLS-1$
            + "\u0434\u043E\u043B\u0433\u0430"); //$NON-NLS-1$

        assertFalse(new GetMetadataObjectsTool().matches(object, "Debt offset", //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.TEXT, "ru")); //$NON-NLS-1$
    }

    @Test
    public void testNameFilterRemainsNameOnly()
    {
        CommonModule object = metadataObject("DebtOffset", "Human caption", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041F\u043E\u0434\u043F\u0438\u0441\u044C"); //$NON-NLS-1$
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();

        assertTrue(tool.matches(object, "offset", //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.NAME, "en")); //$NON-NLS-1$
        assertFalse(tool.matches(object, "Human caption", //$NON-NLS-1$
            GetMetadataObjectsTool.FilterMode.NAME, "en")); //$NON-NLS-1$
    }

    @Test
    public void testTextFilterMatchingBothFieldsProducesOneResult()
    {
        CommonModule object = metadataObject("SharedCaption", "SharedCaption", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041E\u0431\u0449\u0430\u044F\u041F\u043E\u0434\u043F\u0438\u0441\u044C"); //$NON-NLS-1$
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.getCommonModules().add(object);
        List<GetMetadataObjectsTool.MetadataInfo> rows = new ArrayList<>();

        int total = new GetMetadataObjectsTool().collectMetadataObjects(config,
            MetadataTypeUtils.resolve("CommonModule"), rows, "caption", //$NON-NLS-1$ //$NON-NLS-2$
            GetMetadataObjectsTool.FilterMode.TEXT, 10, "en"); //$NON-NLS-1$

        assertEquals("an object matching both fields must count once", 1, total); //$NON-NLS-1$
        assertEquals("an object matching both fields must produce one row", 1, rows.size()); //$NON-NLS-1$
    }

    @Test
    public void testEmptyOrAbsentTextFilterDoesNotFilter()
    {
        CommonModule object = metadataObject("AnyName", "Any caption", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041B\u044E\u0431\u0430\u044F\u041F\u043E\u0434\u043F\u0438\u0441\u044C"); //$NON-NLS-1$
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();

        assertTrue(tool.matches(object, null, GetMetadataObjectsTool.FilterMode.TEXT, "ru")); //$NON-NLS-1$
        assertTrue(tool.matches(object, "", GetMetadataObjectsTool.FilterMode.TEXT, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The corner the shared resolver decides, pinned so it stays a decision: an object with NO
     * synonym in the effective language falls back to its first non-empty one - exactly what the
     * Synonym COLUMN displays for that row - so the filter can match text from another language.
     * Matching what the caller can see in the table is the point; diverging from the column would
     * make a visible row unfindable by the text printed in it.
     */
    @Test
    public void testTextFilterFallsBackToTheDisplayedSynonymWhenTheLanguageHasNone()
    {
        CommonModule object = MdClassFactory.eINSTANCE.createCommonModule();
        object.setName("DebtOffset"); //$NON-NLS-1$
        object.getSynonym().put("ru", "\u041A\u043E\u0440\u0440\u0435\u043A\u0442\u0438\u0440\u043E\u0432\u043A\u0430 " //$NON-NLS-1$
            + "\u0434\u043E\u043B\u0433\u0430"); //$NON-NLS-2$

        assertTrue("an object whose only synonym is the one the column shows must be findable "
            + "by that text", new GetMetadataObjectsTool().matches(object, "\u043A\u043E\u0440\u0440\u0435\u043A\u0442",
                GetMetadataObjectsTool.FilterMode.TEXT, "en")); //$NON-NLS-1$
    }

    private static CommonModule metadataObject(String name, String englishSynonym,
        String russianSynonym)
    {
        CommonModule object = MdClassFactory.eINSTANCE.createCommonModule();
        object.setName(name);
        object.getSynonym().put("en", englishSynonym); //$NON-NLS-1$
        object.getSynonym().put("ru", russianSynonym); //$NON-NLS-1$
        return object;
    }

    // ==================== metadataType normalization (pure logic, no workbench) ====================

    @Test
    public void testNormalizeMetadataTypeAcceptsLegacyCategoryTokens()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("all", tool.normalizeMetadataType("all")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ScheduledJob", tool.normalizeMetadataType("scheduledjobs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog", tool.normalizeMetadataType("catalogs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModule", tool.normalizeMetadataType("commonmodules")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNormalizeMetadataTypeAcceptsEnglishTypeNameToken()
    {
        // Standard FQN type-name tokens and legacy plurals normalize to one canonical token.
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("ScheduledJob", tool.normalizeMetadataType("ScheduledJob")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ScheduledJob", tool.normalizeMetadataType("scheduledjob")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Document", tool.normalizeMetadataType("Document")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModule", tool.normalizeMetadataType("CommonModule")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNormalizeMetadataTypeAcceptsRussianTypeNameToken()
    {
        // Bilingual side of the resolver (MetadataTypeUtils), both singular forms.
        // Escaped so the RU tokens survive a non-UTF-8 Tycho build (see CLAUDE.md hard
        // don't #7); same escape sequences as MetadataTypeUtils.MetadataTypeInfo.
        String ruScheduledJob = // РегламентноеЗадание (ScheduledJob)
            "\u0420\u0435\u0433\u043B\u0430\u043C\u0435\u043D\u0442\u043D\u043E\u0435\u0417\u0430\u0434\u0430\u043D\u0438\u0435"; //$NON-NLS-1$
        String ruCatalog = // Справочник (Catalog)
            "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A"; //$NON-NLS-1$
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("ScheduledJob", tool.normalizeMetadataType(ruScheduledJob)); //$NON-NLS-1$
        assertEquals("Catalog", tool.normalizeMetadataType(ruCatalog)); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeMetadataTypeAcceptsEveryPreviouslyUncollectedProbe()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("Role", tool.normalizeMetadataType("Role")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Subsystem", tool.normalizeMetadataType("Subsystem")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("HTTPService", tool.normalizeMetadataType("HTTPService")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ExternalDataSource", //$NON-NLS-1$
            tool.normalizeMetadataType("ExternalDataSource")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeMetadataTypeAcceptsRussianPreviouslyUncollectedProbes()
    {
        String ruRole = // Роль (Role)
            "\u0420\u043E\u043B\u044C"; //$NON-NLS-1$
        String ruSubsystem = // Подсистема (Subsystem)
            "\u041F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u0430"; //$NON-NLS-1$
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("Role", tool.normalizeMetadataType(ruRole)); //$NON-NLS-1$
        assertEquals("Subsystem", tool.normalizeMetadataType(ruSubsystem)); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeMetadataTypeAcceptsXdtoPackageTokens()
    {
        // XDTO packages had NO listing route at all, which left the XDTO tools' own advice
        // ("check the name with get_metadata_objects") pointing nowhere (issue #321).
        // The configuration collection is "xDTOPackages"; the shared resolver preserves
        // its unusual DTO capitalization while returning the canonical singular type.
        String ruXdtoPackage = // ПакетXDTO (XDTOPackage)
            "\u041F\u0430\u043A\u0435\u0442XDTO"; //$NON-NLS-1$
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertEquals("XDTOPackage", tool.normalizeMetadataType("xdtopackages")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("XDTOPackage", tool.normalizeMetadataType("XDTOPACKAGES")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("XDTOPackage", tool.normalizeMetadataType("XDTOPackage")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("XDTOPackage", tool.normalizeMetadataType(ruXdtoPackage)); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeMetadataTypeRejectsStandaloneTypeOnConfigurationPath()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertNull(tool.normalizeMetadataType("ExternalDataProcessor")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeMetadataTypeRejectsUnrecognizedValue()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertNull(tool.normalizeMetadataType("bogusType_e2e")); //$NON-NLS-1$
        assertNull(tool.normalizeMetadataType("")); //$NON-NLS-1$
        assertNull(tool.normalizeMetadataType(null));
    }

    /**
     * An EXTERNAL-OBJECTS project has its own two-entry vocabulary (issue #309): the category
     * tokens and the bilingual type names both resolve, and nothing else does - a configuration
     * category asked of such a project is refused rather than answered from the base
     * configuration.
     */
    @Test
    public void testNormalizeExternalMetadataTypeAcceptsItsOwnVocabulary()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        // ВнешняяОбработка / ВнешниеОтчеты
        String ruProcessor = new String(new int[] { 0x0412, 0x043D, 0x0435, 0x0448, 0x043D, 0x044F,
            0x044F, 0x041E, 0x0431, 0x0440, 0x0430, 0x0431, 0x043E, 0x0442, 0x043A, 0x0430 }, 0, 16);
        String ruReports = new String(new int[] { 0x0412, 0x043D, 0x0435, 0x0448, 0x043D, 0x0438,
            0x0435, 0x041E, 0x0442, 0x0447, 0x0435, 0x0442, 0x044B }, 0, 13);

        assertEquals("all", tool.normalizeExternalMetadataType("all")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("externaldataprocessors", //$NON-NLS-1$
            tool.normalizeExternalMetadataType("externalDataProcessors")); //$NON-NLS-1$
        assertEquals("externaldataprocessors", //$NON-NLS-1$
            tool.normalizeExternalMetadataType("ExternalDataProcessor")); //$NON-NLS-1$
        assertEquals("externaldataprocessors", tool.normalizeExternalMetadataType(ruProcessor)); //$NON-NLS-1$
        assertEquals("externalreports", tool.normalizeExternalMetadataType("ExternalReports")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("externalreports", tool.normalizeExternalMetadataType(ruReports)); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeExternalMetadataTypeRejectsConfigurationCategories()
    {
        GetMetadataObjectsTool tool = new GetMetadataObjectsTool();
        assertNull(tool.normalizeExternalMetadataType("catalogs")); //$NON-NLS-1$
        assertNull(tool.normalizeExternalMetadataType("Document")); //$NON-NLS-1$
        assertNull(tool.normalizeExternalMetadataType("dataProcessors")); //$NON-NLS-1$
        assertNull(tool.normalizeExternalMetadataType("bogusType_e2e")); //$NON-NLS-1$
        assertNull(tool.normalizeExternalMetadataType(""));  //$NON-NLS-1$
        assertNull(tool.normalizeExternalMetadataType(null));
    }
}
