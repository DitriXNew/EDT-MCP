/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor;
import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideRenderer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** End-to-end unit coverage of selective guides, including the unchanged renderer contract. */
public class GetToolGuideToolSectionsTest
{
    private static final String TARGET = "section_fixture";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
    private static final String ALPHA = "## Alpha\r\nneedle[.] in the body.\r\n"
        + "### Nested\r\nNested instructions.\r\n\r\n";
    private static final String BETA = "## Beta\r\nUnrelated instructions.\r\n\r\n";
    private static final String GAMMA = "## NEEDLE[.] heading\r\nLast instructions.\r\n";
    private static final String BODY = "Guide introduction.\r\n\r\n" + ALPHA + BETA + GAMMA;
    private final GetToolGuideTool tool = new GetToolGuideTool();

    @Before
    public void setUp()
    {
        McpToolRegistry.getInstance().clear();
        register(BODY);
    }

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    private static IMcpTool register(String guide)
    {
        IMcpTool target = new IMcpTool()
        {
            @Override
            public String getName()
            {
                return TARGET;
            }

            @Override
            public String getDescription()
            {
                return "Fixture description.";
            }

            @Override
            public String getInputSchema()
            {
                return SCHEMA;
            }

            @Override
            public String getGuide()
            {
                return guide;
            }

            @Override
            public String execute(Map<String, String> params)
            {
                throw new AssertionError("Guide retrieval must never execute its target");
            }
        };
        McpToolRegistry.getInstance().register(target);
        return target;
    }

    private String execute(String... arguments)
    {
        Map<String, String> params = new HashMap<>();
        params.put("toolName", TARGET);
        for (int i = 0; i < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return tool.execute(params);
    }

    private static String error(String json)
    {
        assertTrue("Expected a ToolResult error envelope, got a guide", json.startsWith("{"));
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse("Errors must use the ToolResult envelope", result.get("success").getAsBoolean());
        return result.get("error").getAsString();
    }

    @Test
    public void testDefaultIsByteIdenticalToTheCurrentRendererAndPinnedFullText()
    {
        String expected = "# section_fixture\n\nFixture description.\n\n"
            + "## Parameters\nNo parameters.\n\n## Guide\n" + BODY;
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), execute().getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, GuideRenderer.render(McpToolRegistry.getInstance().getTool(TARGET)));
    }

    @Test
    public void testDefaultAndExplicitFullMatchRendererForEveryBuiltInTool()
    {
        BuiltInToolRegistrar.registerAll(McpToolRegistry.getInstance());
        assertTrue(McpToolRegistry.getInstance().getToolCount() > 50);
        for (IMcpTool target : McpToolRegistry.getInstance().getAllTools())
        {
            byte[] expected = GuideRenderer.render(target).getBytes(StandardCharsets.UTF_8);
            Map<String, String> params = new HashMap<>();
            params.put("toolName", target.getName());
            assertArrayEquals(target.getName(), expected, tool.execute(params).getBytes(StandardCharsets.UTF_8));
            params.put("mode", "full");
            assertArrayEquals(target.getName(), expected, tool.execute(params).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testSummaryKeepsDescriptionAndParametersButOnlyListsGuideHeadings()
    {
        String result = execute("mode", "summary");
        assertTrue(result.startsWith("# section_fixture\n\nFixture description.\n\n## Parameters\nNo parameters."));
        assertTrue(result.contains("## Sections\n"));
        assertTrue(result.contains("| Parameters | #parameters |"));
        assertTrue(result.contains("| Guide | #guide |"));
        assertTrue(result.contains("| Alpha | #alpha |"));
        assertTrue(result.contains("| NEEDLE[.] heading | #needle-heading |"));
        assertFalse(result.contains("Nested instructions."));
        assertFalse(result.contains("Guide introduction."));
        assertFalse(result.contains("| Nested |"));
    }

    @Test
    public void testSectionMatchesHeadingCaseInsensitivelyAndPreservesNestedTextAndCrLf()
    {
        assertEquals(ALPHA, execute("section", "aLpHa"));
    }

    @Test
    public void testSectionAcceptsBareAndHashPrefixedAnchors()
    {
        assertEquals(GAMMA, execute("section", "#NEEDLE-HEADING"));
        assertEquals(GAMMA, execute("section", "needle-heading"));
    }

    @Test
    public void testHeadingStartingWithHashRemainsSelectableByItsExactTitle()
    {
        String section = "## # Heading\nBody.\n";
        register(section);
        assertEquals(section, execute("section", "# Heading"));
        assertEquals(section, execute("section", "#-heading"));
        register("## Heading\nPlain.\n## #Heading\nHash.\n");
        assertEquals("## Heading\nPlain.\n", execute("section", "#heading"));
        assertEquals("## #Heading\nHash.\n", execute("section", "#heading-1"));
        register("## ###\nEmpty heading.\n");
        assertEquals("## ###\nEmpty heading.\n", execute("section", "#section"));
        assertTrue(execute("mode", "summary").contains("|  | #section |"));
    }

    @Test
    public void testDuplicateHeadingsAreIndividuallyAddressable()
    {
        register("## Repeat\nFirst.\n\n## Repeat\nSecond.\n");
        assertEquals("## Repeat\nFirst.\n\n", execute("section", "Repeat"));
        assertEquals("## Repeat\nSecond.\n", execute("section", "#repeat-1"));
        assertTrue(execute("mode", "summary").contains("| Repeat | #repeat-1 |"));
    }

    @Test
    public void testQueryReturnsMatchingLinesWithSectionsAndAnchorsInOrder()
    {
        String result = execute("query", "nEeDlE[.]");
        assertEquals("2 matching lines in 2 sections; fetch one with section=<heading|#anchor>.\n\n"
            + "- Alpha (#alpha): needle[.] in the body.\n"
            + "- NEEDLE[.] heading (#needle-heading): ## NEEDLE[.] heading\n", result);
        assertFalse(result.contains("Nested instructions."));
        assertFalse(result.contains("Last instructions."));
    }

    @Test
    public void testQueryIsLiteralAndNoMatchesListAvailableHeadings()
    {
        String result = execute("query", "needle.*");
        assertTrue(result.startsWith("No sections match query `needle.*`."));
        assertTrue(result.contains("| Alpha | #alpha |"));
        assertTrue(result.contains("| Beta | #beta |"));
        assertFalse(result.contains("Nested instructions."));
    }

    @Test
    public void testUnknownSectionIsAnActionableErrorListingHeadings()
    {
        String message = error(execute("section", "missing-heading"));
        assertTrue(message.contains("Unknown section: missing-heading"));
        assertTrue(message.contains("Pass a heading or anchor"));
        assertTrue(message.contains("| Alpha | #alpha |"));
        assertTrue(message.contains("| Beta | #beta |"));
        assertFalse(message.contains("Nested instructions."));
    }

    @Test
    public void testEmptySectionDoesNotSilentlyReturnTheFullGuide()
    {
        assertTrue(error(execute("section", "")).contains("section must not be blank"));
    }

    @Test
    public void testBlankQueryIsAnActionableError()
    {
        String message = error(execute("query", " \t\r\n"));
        assertTrue(message.contains("query must not be blank"));
        assertTrue(message.contains("Pass a literal search term"));
    }

    @Test
    public void testUnknownModeIsAnActionableError()
    {
        String message = error(execute("mode", "tiny"));
        assertTrue(message.contains("Unknown mode: tiny"));
        assertTrue(message.contains("Use 'full' or 'summary'"));
    }

    @Test
    public void testSelectorsArePairwiseMutuallyExclusiveIncludingExplicitFull()
    {
        for (String[] arguments : new String[][] {
            {"mode", "summary", "section", "Alpha"},
            {"mode", "full", "query", "needle"},
            {"section", "Alpha", "query", "needle"},
            {"mode", "full", "section", "Alpha", "query", "needle"}})
        {
            String message = error(execute(arguments));
            assertTrue(message.contains("mode, section and query are mutually exclusive"));
            assertTrue(message.contains("Pass only one"));
        }
    }

    @Test
    public void testSummaryAndParametersSelectionWorkWithoutAnExtendedGuide()
    {
        register("");
        assertEquals("## Parameters\nNo parameters.\n", execute("section", "Parameters"));
        String result = execute("mode", "summary");
        assertTrue(result.contains("| Parameters | #parameters |"));
        assertFalse(result.contains("| Guide |"));
    }

    @Test
    public void testFencedHeadingsAreNotSelectableOrListed()
    {
        register("## Real\n````markdown\n## Fake backtick\n```\n## Still fenced\n````\n"
            + "~~~text\n## Fake tilde\n~~~~\n## Next\nTail.");
        String result = execute("mode", "summary");
        assertTrue(result.contains("| Real | #real |"));
        assertTrue(result.contains("| Next | #next |"));
        assertFalse(result.contains("Fake"));
        assertFalse(result.contains("Still fenced"));
        assertTrue(error(execute("section", "Fake backtick")).contains("Unknown section: Fake backtick"));
        assertTrue(execute("section", "Real").contains("## Still fenced"));
    }

    @Test
    public void testSectionBeyondTheOutputGuardRemainsReachable()
    {
        register("## Large\n" + "x".repeat(110_000) + "\n## Tail\nReachable.\n");
        assertEquals("## Tail\nReachable.\n", execute("section", "Tail"));
        assertTrue(execute("query", "Reachable").contains("- Tail (#tail): Reachable.\n"));
        assertFalse(execute("mode", "summary").contains("x".repeat(100)));
    }

    @Test
    public void testModifyMetadataSectionIsMuchSmallerThanItsFullGuide()
    {
        IMcpTool target = new ModifyMetadataTool();
        McpToolRegistry.getInstance().register(target);
        String full = GuideRenderer.render(target);
        assertTrue("Load the actual large resource, not a guide-less fallback", full.length() > 50_000);
        String title = "## Setting the data type\n";
        int start = full.indexOf(title);
        assertTrue(start >= 0);
        int end = full.indexOf("\n## ", start + title.length()) + 1;
        assertTrue(end > start);
        String selected = tool.execute(Map.of("toolName", "modify_metadata", "section", "Setting the data type"));
        assertEquals(full.substring(start, end), selected);
        assertTrue(selected.length() < full.length() / 10);
    }

    @Test
    public void testNewSelectorsAreOptionalAndOnlyTheirEnumSurvivesWireCompaction()
    {
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals("[\"toolName\"]", schema.getAsJsonArray("required").toString());
        JsonObject properties = schema.getAsJsonObject("properties");
        assertEquals(4, properties.size());
        assertEquals("[\"full\",\"summary\"]", properties.getAsJsonObject("mode").get("enum").toString());
        JsonObject compact = InputSchemaCompactor.compact(tool.getName(), schema).getAsJsonObject();
        assertEquals(properties.getAsJsonObject("mode").get("enum"),
            compact.getAsJsonObject("properties").getAsJsonObject("mode").get("enum"));
        for (String name : new String[] {"mode", "section", "query"})
        {
            assertFalse(compact.getAsJsonObject("properties").getAsJsonObject(name).has("description"));
        }
    }
}
