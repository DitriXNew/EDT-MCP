/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideLoader;
import com.ditrix.edt.mcp.server.utils.GuideRenderer;
import com.ditrix.edt.mcp.server.utils.GuideSections;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Regression coverage for guide review findings, using real guides where possible. */
public class GetToolGuideToolReviewTest
{
    private final GetToolGuideTool tool = new GetToolGuideTool();

    @Before
    public void setUp()
    {
        McpToolRegistry.getInstance().clear();
    }

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    private String execute(IMcpTool target, String... arguments)
    {
        McpToolRegistry.getInstance().register(target);
        Map<String, String> params = new HashMap<>();
        params.put("toolName", target.getName());
        for (int i = 0; i < arguments.length; i += 2)
        {
            params.put(arguments[i], arguments[i + 1]);
        }
        return tool.execute(params);
    }

    private static String error(String json)
    {
        assertTrue("Expected a ToolResult error, got guide text", json.startsWith("{"));
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        return result.get("error").getAsString();
    }

    @Test
    public void testBroadModifyMetadataQueriesCapMatchingLinesWithExactRemainder()
    {
        ModifyMetadataTool target = new ModifyMetadataTool();
        assertFalse("The real bundled guide must be available", target.getGuide().isEmpty());
        String full = GuideRenderer.render(target);
        assertTrue(full.length() > 90_000);
        for (String query : List.of("type", "in"))
        {
            long total = full.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains(query)).count();
            assertTrue("Exercise the remainder, not just a short result", total > 20);
            String result = execute(target, "query", query);
            assertTrue("Query must remain far below the full guide: " + result.length(), result.length() < 10_000);
            assertTrue(result.startsWith(total + " matching lines in "));
            assertTrue(result.contains("section=<heading|#anchor>"));
            assertEquals(20L, result.lines().filter(line -> line.startsWith("- ")).count());
            assertTrue(result.endsWith("... (" + (total - 20) + " more)\n"));
            assertTrue(result.contains("(#parameters): "));
            if ("type".equals(query))
            {
                String unrelated = GuideSections.parse(full).find("Predefined items").text();
                assertFalse("Chosen unrelated section must not match", unrelated.toLowerCase(Locale.ROOT).contains(query));
                assertFalse(result.contains("A PREDEFINED item is addressed through its owner's member FQN"));
            }
        }
    }

    @Test
    public void testQueryReportsMatchingLinesOnlyIncludingFencedExamplesAndClipsLongLines()
    {
        String longLine = "needle " + "x".repeat(300);
        GuideSections sections = GuideSections.parse("# Tool\nIntro\n## One\nUnrelated body.\n"
            + "```text\n" + longLine + "\n## Fenced needle\n```\n## Two\nNEEDLE again\n");
        String result = sections.query("needle");
        assertTrue(result.startsWith("3 matching lines in 2 sections;"));
        assertTrue(result.contains("- One (#one): " + longLine.substring(0, 200) + "\u2026\n"));
        assertTrue(result.contains("- One (#one): ## Fenced needle\n"));
        assertTrue(result.contains("- Two (#two): NEEDLE again\n"));
        assertFalse(result.contains("Unrelated body."));
        assertFalse(result.contains("x".repeat(201)));
    }

    @Test
    public void testQueryTrimsTheSearchTermButKeepsItLiteral()
    {
        GuideSections sections = GuideSections.parse("## Literal\nNeedle[.]\nNeedleX\n");
        String result = sections.query("  nEeDlE[.]  ");
        assertTrue(result.startsWith("1 matching line in 1 section;"));
        assertTrue(result.contains("- Literal (#literal): Needle[.]\n"));
        assertFalse(result.contains("NeedleX"));
    }

    @Test
    public void testNestedRealHeadingIsIndividuallyAddressableWithoutItsSiblings()
    {
        ModifyMetadataTool target = new ModifyMetadataTool();
        assertFalse(target.getGuide().isEmpty());
        String full = GuideRenderer.render(target);
        String title = "Adjustable flags (`view` / `edit` / `userVisible` / `use`)";
        int start = full.indexOf("### " + title + "\n");
        int end = full.indexOf("### Use always", start);
        assertTrue(start >= 0 && end > start);
        String result = execute(target, "section", title);
        assertEquals(full.substring(start, end), result);
        assertFalse(result.contains("### Use always"));
        assertFalse(result.contains("## Form members"));
        assertEquals(result, execute(target, "section", "#adjustable-flags-view--edit--uservisible--use"));
    }

    @Test
    public void testEveryHeadingLevelKeepsNestedSpansAndVerbatimWhitespace()
    {
        String markdown = "# Tool\nIntro\n## Parent\nBody\n### Child\nChild body\n"
            + "#### Grandchild\nMore\n### Sibling\nSibling body\n# Other\nOutside\n## Last\nEnd";
        GuideSections sections = GuideSections.parse(markdown);
        assertEquals(markdown.substring(0, markdown.indexOf("# Other")), sections.find("Tool").text());
        assertEquals("### Child\nChild body\n#### Grandchild\nMore\n", sections.find("Child").text());
        assertEquals("#### Grandchild\nMore\n", sections.find("Grandchild").text());
        assertEquals("# Other\nOutside\n## Last\nEnd", sections.find("Other").text());
        assertFalse(sections.tableOfContents().contains("| Child |"));
        assertFalse(sections.tableOfContents().contains("| Other |"));
    }

    @Test
    public void testBodyH1IntroductionCanBeSelectedAndQueriedInRealDebugGuide()
    {
        DebugYaxunitTestsTool target = new DebugYaxunitTestsTool();
        assertFalse(GuideLoader.load("debug_yaxunit_tests").isEmpty());
        String full = GuideRenderer.render(target);
        String result = execute(target, "query", "The job resolves and prepares the application");
        assertTrue(result.startsWith("1 matching line in 1 section;"));
        assertTrue(result.contains("debug_yaxunit_tests (deprecated) (#debug_yaxunit_tests-deprecated)"));
        assertFalse(result.contains("## Parameter details"));
        assertEquals("## Guide\n", execute(target, "section", "Guide"));
        int start = full.indexOf("# debug_yaxunit_tests (deprecated)\n");
        assertTrue(start >= 0);
        assertEquals(full.substring(start), execute(target, "section", "debug_yaxunit_tests (deprecated)"));
    }

    @Test
    public void testYoAndYeMatchInBothDirectionsForSectionsAndQueries()
    {
        String yo = "\u0432\u0438\u0434\u044b \u0440\u0430\u0441\u0447\u0451\u0442\u0430";
        String ye = yo.replace('\u0451', '\u0435');
        for (String heading : List.of(yo, ye))
        {
            String markdown = "## " + heading + "\nBody\n";
            GuideSections sections = GuideSections.parse(markdown);
            for (String request : List.of(yo, ye))
            {
                assertNotNull(sections.find(request.toUpperCase(Locale.ROOT)));
                assertEquals(markdown, sections.find(request.toUpperCase(Locale.ROOT)).text());
                assertTrue(sections.query(request.toUpperCase(Locale.ROOT)).contains("- " + heading + " (#"));
            }
        }
    }

    @Test
    public void testPastedHeadingBackticksAndWhitespaceAreNormalizedAfterExactLookup()
    {
        String markdown = "### Adjustable flags (`view` / `edit` / `userVisible` / `use`)\nBody\n";
        GuideSections sections = GuideSections.parse(markdown);
        assertNotNull(sections.find("###  ADJUSTABLE flags (view / edit / userVisible / use)"));
        assertEquals(markdown, sections.find("###  ADJUSTABLE flags (view / edit / userVisible / use)").text());
        GuideSections literalHash = GuideSections.parse("## # Heading\nLiteral\n## Heading\nPlain\n");
        assertEquals("## # Heading\nLiteral\n", literalHash.find("# Heading").text());
        assertEquals("## Heading\nPlain\n", literalHash.find("#heading").text());
    }

    @Test
    public void testBlankSectionsAreRejectedEvenWhenAnEmptyHeadingExists()
    {
        IMcpTool target = new GetToolGuideTool()
        {
            @Override
            public String getGuide()
            {
                return "## ###\nEmpty heading body.\n";
            }
        };
        for (String request : List.of("", "   ", " \t\r\n"))
        {
            String message = error(execute(target, "section", request));
            assertTrue(message.contains("section must not be blank"));
            assertTrue(message.contains("Pass a heading or #anchor from mode=summary"));
            assertFalse(message.contains("Empty heading body."));
        }
    }

    @Test
    public void testOneCharacterQueryHasAnActionableMinimumLengthError()
    {
        String message = error(execute(new ModifyMetadataTool(), "query", " a "));
        assertTrue(message.contains("query must contain at least 2 characters"));
        assertTrue(message.contains("Pass a longer literal search term"));
    }

    @Test
    public void testSelectorDescriptionsAreStrippedWhileModeEnumSurvives()
    {
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        JsonObject compact = InputSchemaCompactor.compact(tool.getName(), schema).getAsJsonObject();
        assertEquals("[\"toolName\"]", compact.getAsJsonArray("required").toString());
        JsonObject properties = compact.getAsJsonObject("properties");
        for (String name : List.of("mode", "section", "query"))
        {
            assertFalse(name + " prose must not be paid on every tools/list",
                properties.getAsJsonObject(name).has("description"));
        }
        assertEquals("{\"type\":\"string\",\"enum\":[\"full\",\"summary\"]}", properties.get("mode").toString());
        assertEquals("{\"type\":\"string\"}", properties.get("section").toString());
        assertEquals("{\"type\":\"string\"}", properties.get("query").toString());
        assertTrue(tool.getDescription().contains("mode=summary lists sections; section=<heading|#anchor>; query=<text>"));
    }

    @Test
    public void testRealModifyMetadataSummaryIsSmallCompleteAndOffersTheFullResource()
    {
        ModifyMetadataTool target = new ModifyMetadataTool();
        assertFalse(GuideLoader.load("modify_metadata").isEmpty());
        String full = GuideRenderer.render(target);
        assertTrue(full.length() > 90_000);
        String summary = execute(target, "mode", "summary");
        assertTrue("Summary must be only a few KB: " + summary.length(), summary.length() < 15_000);
        assertTrue(summary.contains(target.getDescription()));
        assertTrue(summary.contains("| fqn |"));
        GuideSections index = GuideSections.parse(full);
        for (String line : full.lines().filter(value -> value.startsWith("## ")).toList())
        {
            String title = line.substring(3);
            assertNotNull(index.find(title));
            assertTrue(title, summary.contains("| " + title + " | #" + index.find(title).anchor() + " |"));
        }
        assertFalse(summary.contains("When the `fqn` is a **Role**"));
        assertTrue(summary.contains("section=<heading|#anchor>"));
        assertTrue(summary.contains("guide://modify_metadata"));
    }

    @Test
    public void testRealCreateInfobaseSummaryExcludesFencedCommentsAndOffersTheFullResource()
    {
        CreateInfobaseTool target = new CreateInfobaseTool();
        assertFalse(GuideLoader.load("create_infobase").isEmpty());
        GuideSections index = GuideSections.parse(GuideRenderer.render(target));
        assertNull(index.find("Create a brand-new infobase:"));
        assertNull(index.find("Or register an existing one:"));
        String summary = execute(target, "mode", "summary");
        assertTrue(summary.contains("| Typical workflow | #typical-workflow |"));
        assertFalse(summary.contains("Create a brand-new infobase:"));
        assertFalse(summary.contains("Or register an existing one:"));
        assertTrue(summary.contains("guide://create_infobase"));
    }

    @Test
    public void testRealCredentialsQueryShowsTheMatchBeyondTheFirstTwoHundredCharacters()
    {
        SetInfobaseCredentialsTool target = new SetInfobaseCredentialsTool();
        assertFalse("The bundled credentials guide must be available", target.getGuide().isEmpty());
        String query = "client half normally";
        String full = GuideRenderer.render(target);
        String line = full.lines().filter(value -> value.contains(query)).findFirst().orElseThrow();
        assertTrue("The real match must be beyond the old clipping boundary", line.indexOf(query) > 200);
        String result = execute(target, "query", query);
        assertTrue(result.startsWith("1 matching line in 1 section;"));
        assertTrue(result, result.contains("all three write the client half normally, because"));
        assertTrue(result.contains("(#what-these-credentials-are-and-are-not): \u2026"));
        assertFalse(result.contains("A **shared** launch configuration"));
        String hit = result.lines().filter(value -> value.startsWith("- ")).findFirst().orElseThrow();
        String snippet = hit.substring(hit.indexOf("): ") + 3);
        assertTrue("At most 200 source code points and two clipping markers",
            snippet.codePointCount(0, snippet.length()) <= 202);
    }

    @org.junit.Test
    public void testOneSupplementaryCharacterQueryIsTooShort()
    {
        // A single emoji is one code point but two UTF-16 chars: it must not pass the 2-character minimum.
        String message = error(execute(new ModifyMetadataTool(), "query", new String(Character.toChars(0x1F6AB))));
        assertTrue(message, message.contains("query must contain at least 2 characters"));
    }
}
