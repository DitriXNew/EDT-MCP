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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetContentAssistTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live EDT access. {@code execute()} validates
 * projectName, filePath, and the numeric/positive line+column before the first
 * {@code ResourcesPlugin.getWorkspace()} / {@code PlatformUI.getWorkbench()}
 * call. The actual content-assist invocation needs a live workbench/editor and
 * is covered by the E2E suite.
 */
public class GetContentAssistToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_content_assist", new GetContentAssistTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetContentAssistTool.NAME, new GetContentAssistTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetContentAssistTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetContentAssistTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetContentAssistTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$ deprecated alias
        assertTrue(schema.contains("\"line\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"column\"")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        // The slimmed description must keep the standard pointer to the on-demand guide.
        String desc = new GetContentAssistTool().getDescription();
        assertTrue(desc.contains("get_tool_guide('get_content_assist')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail removed from description/schema must live in the guide
        // (now served from the bundled guides/get_content_assist.md). The renderer adds
        // the auto "## Parameters" table itself, so the guide body uses "## Parameter
        // details" for its prose.
        String guide = new GetContentAssistTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        assertTrue(guide.contains("## Parameter details")); //$NON-NLS-1$
        assertTrue(guide.contains("extendedDocumentation")); //$NON-NLS-1$
        // The position/readiness nuance that no longer clutters the schema.
        assertTrue(guide.contains("1-based")); //$NON-NLS-1$
        assertTrue(guide.contains("retry")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresPaginationAndFilterParameters()
    {
        // The lean schema must still surface the pagination/filter/doc knobs so a client
        // can drive offset/limit/contains/extendedDocumentation without reading the guide.
        String schema = new GetContentAssistTool().getInputSchema();
        assertTrue(schema.contains("\"limit\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"offset\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"contains\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"extendedDocumentation\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParametersInInputSchema()
    {
        // projectName/line/column are the required trio; the optional knobs must NOT be
        // listed in the required array (a client must be free to omit them).
        String schema = new GetContentAssistTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue(open >= 0 && close > open);
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("line must be required", requiredBlock.contains("\"line\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("column must be required", requiredBlock.contains("\"column\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("offset must NOT be required", requiredBlock.contains("\"offset\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("contains must NOT be required", requiredBlock.contains("\"contains\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("limit must NOT be required", requiredBlock.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsDeclaredForJsonTool()
    {
        // A JSON tool puts its payload in structuredContent, so it MUST publish an output
        // schema (the IMcpTool default is null). Pin the success envelope + result fields.
        String schema = new GetContentAssistTool().getOutputSchema();
        assertNotNull("a JSON tool must override getOutputSchema()", schema); //$NON-NLS-1$
        assertFalse(schema.isEmpty());
        assertTrue("output must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output must declare file", schema.contains("\"file\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output must declare line", schema.contains("\"line\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output must declare column", schema.contains("\"column\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output must declare totalProposals", schema.contains("\"totalProposals\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output must declare filteredOut", schema.contains("\"filteredOut\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output must declare skipped", schema.contains("\"skipped\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output must declare returnedProposals", //$NON-NLS-1$
            schema.contains("\"returnedProposals\"")); //$NON-NLS-1$
        assertTrue("output must declare proposals", schema.contains("\"proposals\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResultFileNameDefaultsToToolNameMarkdown()
    {
        // Inherited IMcpTool default: getName() + ".md", independent of the params map.
        GetContentAssistTool tool = new GetContentAssistTool();
        assertEquals("get_content_assist.md", tool.getResultFileName(new HashMap<>())); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "Whatever"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("get_content_assist.md", tool.getResultFileName(params)); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFilePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testNonNumericLineOrColumn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "src/Foo.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // line/column omitted -> parse fails -> "Invalid line or column number"
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Invalid line or column number")); //$NON-NLS-1$
    }

    @Test
    public void testLineColumnMustBePositive()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "src/Foo.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        // Source message is "Line and column must be >= 1"; assert on the prefix
        // without '>': ToolResult.toJson() (Gson) escapes '>' as > in JSON.
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Line and column must be")); //$NON-NLS-1$
    }

    @Test
    public void testColumnMustBePositive()
    {
        // Mirror of the line guard, but the failing coordinate is the column: line >= 1,
        // column 0 must still trip the "Line and column must be >= 1" branch.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/M/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "5"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Line and column must be")); //$NON-NLS-1$
    }

    @Test
    public void testNegativeLineRejected()
    {
        // A negative line also fails the >= 1 guard (distinct from the 0 boundary).
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/M/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "-3"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Line and column must be")); //$NON-NLS-1$
    }

    @Test
    public void testDoubleFormatLineBelowOneRejected()
    {
        // A numeric coordinate below one keeps the established range error.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/M/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "0.4"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Line and column must be")); //$NON-NLS-1$
    }

    @Test
    public void testNonNumericColumnIsInvalid()
    {
        // line parses fine, column "abc" throws NumberFormatException -> the combined
        // "Invalid line or column number" branch (column-side of the try).
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/M/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "abc"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Invalid line or column number")); //$NON-NLS-1$
    }

    @Test
    public void testModulePathCanonicalKeyIsReadBeforeFilePathAlias()
    {
        // Supplying ONLY the canonical modulePath (no filePath alias) must satisfy the
        // file-path requirement; execution then proceeds to the numeric parse and fails
        // there (line missing) rather than at the "modulePath is required" guard. This
        // proves modulePath is read first.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/M/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertFalse("modulePath provided -> must NOT report modulePath as missing", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
        assertTrue("with no line/column the next guard is the numeric parse", //$NON-NLS-1$
            result.contains("Invalid line or column number")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyModulePathFallsBackToFilePathAlias()
    {
        // An empty modulePath (present but blank) must fall through to the deprecated
        // filePath alias rather than failing the requirement. With filePath set and no
        // line/column the next stop is the numeric-parse error, NOT "modulePath is required".
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "src/Foo.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertFalse("filePath alias must satisfy the requirement", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
        assertTrue(result.contains("Invalid line or column number")); //$NON-NLS-1$
    }

    // ==================== formatProposals (filter / offset / limit) ====================
    // Pure result-shaping over a mocked proposal array. Result JSON is built by
    // the shared ToolResult/Gson path (compact "key":value).

    private static ICompletionProposal proposal(String displayString)
    {
        ICompletionProposal p = mock(ICompletionProposal.class);
        when(p.getDisplayString()).thenReturn(displayString);
        return p;
    }

    @Test
    public void testFormatProposalsBasic()
    {
        ICompletionProposal[] props = {proposal("AddRow"), proposal("InsertRow"), proposal("CountRows")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String json = GetContentAssistTool.formatProposals(props, 10, 0, null, false, 5, 2, "/P/src/Foo.bsl"); //$NON-NLS-1$
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":3")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":3")); //$NON-NLS-1$
        assertTrue(json.contains("\"filteredOut\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"skipped\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"line\":5")); //$NON-NLS-1$
        assertTrue(json.contains("\"column\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"AddRow\"")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsLimit()
    {
        ICompletionProposal[] props = {proposal("A"), proposal("B"), proposal("C"), proposal("D")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String json = GetContentAssistTool.formatProposals(props, 2, 0, null, false, 1, 1, "/f"); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":4")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"A\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"displayString\":\"C\"")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsOffset()
    {
        ICompletionProposal[] props = {proposal("A"), proposal("B"), proposal("C"), proposal("D")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String json = GetContentAssistTool.formatProposals(props, 10, 2, null, false, 1, 1, "/f"); //$NON-NLS-1$
        assertTrue(json.contains("\"skipped\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"C\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"D\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"displayString\":\"A\"")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsSortsForDeterministicPagination()
    {
        // Proposals can arrive in a warm-up-dependent order; formatProposals sorts them
        // case-insensitively BEFORE offset/limit so pagination is reproducible. Unsorted input
        // [Delta, alpha, Charlie, bravo] -> sorted [alpha, bravo, Charlie, Delta]; with offset 1,
        // limit 2 the page is bravo, Charlie - NOT the input's positional slice.
        ICompletionProposal[] props = //$NON-NLS-1$
            {proposal("Delta"), proposal("alpha"), proposal("Charlie"), proposal("bravo")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String json = GetContentAssistTool.formatProposals(props, 2, 1, null, false, 1, 1, "/f"); //$NON-NLS-1$
        assertTrue(json.contains("\"skipped\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"bravo\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"displayString\":\"Charlie\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"displayString\":\"alpha\"")); // consumed by offset //$NON-NLS-1$
        assertFalse(json.contains("\"displayString\":\"Delta\"")); // beyond the limit //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsContainsFilter()
    {
        ICompletionProposal[] props = {proposal("GetName"), proposal("SetName"), proposal("GetValue")}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // case-insensitive substring filter on "get"
        String json = GetContentAssistTool.formatProposals(props, 10, 0, "get", false, 1, 1, "/f"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.contains("\"filteredOut\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":2")); //$NON-NLS-1$
        assertTrue(json.contains("GetName")); //$NON-NLS-1$
        assertTrue(json.contains("GetValue")); //$NON-NLS-1$
        assertFalse(json.contains("SetName")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsEmpty()
    {
        String json = GetContentAssistTool.formatProposals(new ICompletionProposal[0], 10, 0, null, false, 1, 1, "/f"); //$NON-NLS-1$
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"proposals\":[]")); //$NON-NLS-1$
    }

    // ==================== formatProposals null-array guard (no proposal construction) ====================
    // formatProposals guards every dereference with proposals != null, so a null array is a
    // valid input (the engine can hand back null). These exercise the false side of the sort
    // guard, the count guard and the totalProposals computation without building any proposal.

    @Test
    public void testFormatProposalsNullArrayIsEmptySuccess()
    {
        String json = GetContentAssistTool.formatProposals(null, 10, 0, null, false, 7, 3, "/P/src/Bar.bsl"); //$NON-NLS-1$
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"filteredOut\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"skipped\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"proposals\":[]")); //$NON-NLS-1$
        // line/column/file are echoed straight through, untouched by the null guard.
        assertTrue(json.contains("\"line\":7")); //$NON-NLS-1$
        assertTrue(json.contains("\"column\":3")); //$NON-NLS-1$
        assertTrue(json.contains("Bar.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testFormatProposalsNullArrayWithFilterStaysEmpty()
    {
        // A contains filter is parsed before the (skipped) loop; with a null array there is
        // nothing to filter, so filteredOut stays 0 and the result is still an empty success.
        String json = GetContentAssistTool.formatProposals(null, 5, 2, "get,add", true, 1, 1, "/f"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"totalProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"returnedProposals\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"filteredOut\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"proposals\":[]")); //$NON-NLS-1$
    }

    @Test
    public void fractionalAndOverflowingCoordinatesAreRefusedBeforeResolvingTheModule()
    {
        for (String key : java.util.List.of("line", "column"))
        {
            for (String value : java.util.List.of("12.7", "NaN", "Infinity", "2147483648", "1e100"))
            {
                java.util.Map<String, String> params = new java.util.HashMap<>(java.util.Map.of(
                    "projectName", "P", "modulePath", "CommonModules/Calc/Module.bsl", "line", "1", "column", "1"));
                params.put(key, value);
                String result = new GetContentAssistTool().execute(params);
                assertTrue(key + "=" + value + ": " + result, result.contains("Invalid line or column number"));
                assertTrue(result, result.contains("false"));
            }
        }
    }

    @Test
    public void fractionalCoordinatesBelowOneKeepTheRangeError()
    {
        for (String key : java.util.List.of("line", "column"))
        {
            var params = new java.util.HashMap<>(java.util.Map.of("projectName", "P",
                "modulePath", "CommonModules/Calc/Module.bsl", "line", "2", "column", "2"));
            params.put(key, "0.4");
            String result = new GetContentAssistTool().execute(params);
            assertTrue(result, result.contains("Line and column must be"));
        }
    }
}
