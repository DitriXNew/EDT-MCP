/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.Test;

/** Markdown edge cases independent of the registry and of bundled guide contents. */
public class GuideSectionsTest
{
    @Test
    public void testNestedHeadingsStayInTheirSectionAndH1EndsIt()
    {
        GuideSections sections = GuideSections.parse("# Tool\nIntro\n## Parent\nBody\n"
            + "### Child\nChild body\n#### Grandchild\nMore\n# Other\nOutside\n## Last\nEnd");
        assertEquals("## Parent\nBody\n### Child\nChild body\n#### Grandchild\nMore\n",
            sections.find("Parent").text());
        assertEquals("### Child\nChild body\n#### Grandchild\nMore\n", sections.find("Child").text());
        assertEquals("## Last\nEnd", sections.find("Last").text());
    }

    @Test
    public void testAtxSpacingIndentationAndClosingHashes()
    {
        GuideSections sections = GuideSections.parse("##Not a heading\n    ## Indented code\n"
            + "   ##\tValid title ###\nBody\n## ###\nEmpty heading\n## Last");
        assertNull(sections.find("Not a heading"));
        assertNull(sections.find("Indented code"));
        assertEquals("   ##\tValid title ###\nBody\n", sections.find("Valid title").text());
        assertEquals("## ###\nEmpty heading\n", sections.find("#section").text());
        assertTrue(sections.tableOfContents().contains("|  | #section |"));
        assertEquals("## Last", sections.find("#last").text());
    }

    @Test
    public void testFenceMustCloseWithTheSameCharacterAndEnoughDelimiters()
    {
        GuideSections sections = GuideSections.parse("## Real\n````lang\n~~~\n## Hidden one\n"
            + "```\n## Hidden two\n```` extra\n## Hidden three\n`````\n## Visible\nDone");
        assertNull(sections.find("Hidden one"));
        assertNull(sections.find("Hidden two"));
        assertNull(sections.find("Hidden three"));
        assertEquals("## Visible\nDone", sections.find("Visible").text());
    }

    @Test
    public void testUnclosedIndentedTildeFenceHidesItsRemainingHeadings()
    {
        GuideSections sections = GuideSections.parse("## Real\n   ~~~text\n## Hidden\n");
        assertNull(sections.find("Hidden"));
        assertEquals("## Real\n   ~~~text\n## Hidden\n", sections.find("Real").text());
    }

    @Test
    public void testAnchorsAreUniqueAcrossNestedAndCollidingHeadings()
    {
        GuideSections sections = GuideSections.parse("## Same\nFirst\n### Same\nNested\n"
            + "## Same\nSecond\n## Same-2\nCollision\n");
        assertEquals("## Same\nSecond\n", sections.find("#same-2").text());
        assertEquals("## Same-2\nCollision\n", sections.find("#same-2-1").text());
    }

    @Test
    public void testTableEscapesPipesInHeadings()
    {
        GuideSections sections = GuideSections.parse("## Color | Font\nText");
        assertTrue(sections.tableOfContents().contains("| Color \\| Font | #color--font |"));
        assertEquals("## Color | Font\nText", sections.find("Color | Font").text());
    }

    @Test
    public void testCaseInsensitiveQueriesAndAnchorsDoNotDependOnTheDefaultLocale()
    {
        Locale previous = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String section = "## BINDING\n\u041f\u0440\u0438\u0432\u0435\u0442\n";
            GuideSections sections = GuideSections.parse(section);
            assertEquals(section, sections.find("#binding").text());
            assertTrue(sections.query("binding").contains("- BINDING (#binding): ## BINDING\n"));
            assertTrue(sections.query("\u041f\u0420\u0418\u0412\u0415\u0422")
                .contains("- BINDING (#binding): \u041f\u0440\u0438\u0432\u0435\u0442\n"));
        }
        finally
        {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void testNoSectionsIsAnExplicitEmptyContentsList()
    {
        GuideSections sections = GuideSections.parse("Prose only\n");
        assertEquals("No ## sections.\n", sections.tableOfContents());
        assertNull(sections.find("missing"));
        assertTrue(sections.query("missing").contains("No sections match query `missing`."));
        assertFalse(sections.summary().contains("null"));
    }

    @Test
    public void testListMarkerFencesHideCommentsAndKeepFollowingHeadings()
    {
        List<String[]> markers = List.of(new String[] {"- ", "  "}, new String[] {"+ ", "  "},
            new String[] {"* ", "  "}, new String[] {"1. ", "   "}, new String[] {"12) ", "    "},
            new String[] {"   - ", "     "}, new String[] {"- - ", "    "},
            new String[] {"12. - ", "      "}, new String[] {"-\t", "    "});
        for (String[] marker : markers)
        {
            for (String fence : List.of("```", "~~~~"))
            {
                String markdown = "## Real\n" + marker[0] + fence + "sh\n"
                    + marker[1] + "## shell comment\n" + marker[1] + fence + fence.charAt(0)
                    + "\n## Visible\nDone";
                GuideSections sections = GuideSections.parse(markdown);
                assertNull("Fence after " + marker[0], sections.find("shell comment"));
                assertEquals("## Visible\nDone", sections.find("Visible").text());
                assertTrue(sections.query("shell comment").contains("- Real (#real): ## shell comment\n"));
                assertFalse(sections.tableOfContents().contains("shell comment"));
            }
        }
    }

    @Test
    public void testListFenceEndsWithItsContainerAndSupportsIndentedContinuationFences()
    {
        GuideSections sections = GuideSections.parse("## Real\n- ```sh\n  ## Hidden\n"
            + "## Outside\n12. Commands\n    ```sh\n    ## Also hidden\n    ```\n## Visible\nDone");
        assertNull(sections.find("Hidden"));
        assertNull(sections.find("Also hidden"));
        assertTrue(sections.tableOfContents().contains("| Outside | #outside |"));
        assertEquals("## Visible\nDone", sections.find("Visible").text());
    }

    @Test
    public void testIndentedCodeAndCodeInsideFencesDoNotOpenListFences()
    {
        GuideSections sections = GuideSections.parse("## Real\n    ```sh\n## Visible\n"
            + "```text\n- ~~~sh\n```\n## Last\nDone");
        assertTrue(sections.tableOfContents().contains("| Visible | #visible |"));
        assertEquals("## Last\nDone", sections.find("Last").text());
    }

    @Test
    public void testSnippetClippingKeepsSupplementaryCodePointsWhole()
    {
        String prefix = "needle " + "x".repeat(192);
        String rocket = "\uD83D\uDE80";
        assertEquals(prefix + rocket + "\u2026", querySnippet(prefix + rocket + "y".repeat(80), "needle"));
        String shortLine = "needle " + rocket.repeat(150);
        assertEquals(shortLine, querySnippet(shortLine, "needle"));
    }

    @Test
    public void testSnippetWindowSurroundsTheFirstMatchAndMarksOnlyCutEnds()
    {
        String middle = querySnippet("left ".repeat(80) + "NeEdLe" + " right".repeat(80), "needle");
        assertTrue(middle, middle.startsWith("\u2026"));
        assertTrue(middle, middle.endsWith("\u2026"));
        assertTrue(middle, middle.contains("left NeEdLe right"));
        assertEquals(202, middle.codePointCount(0, middle.length()));
        String tail = querySnippet("x".repeat(400) + "needle", "needle");
        assertTrue(tail.startsWith("\u2026"));
        assertTrue(tail.endsWith("needle"));
        assertEquals(201, tail.codePointCount(0, tail.length()));
        String first = querySnippet("x".repeat(350) + "NeEdLe" + "y".repeat(350) + "needle", "needle");
        assertTrue("The first occurrence must determine the window", first.contains("NeEdLe"));
        assertFalse(first.contains("needle"));
    }

    @Test
    public void testLateSnippetWindowKeepsSupplementaryBoundariesIntact()
    {
        String rocket = "\uD83D\uDE80";
        String snippet = querySnippet(rocket.repeat(350) + " NEEDLE " + rocket.repeat(350), "needle");
        assertTrue(snippet.contains(" NEEDLE "));
        assertTrue(snippet.startsWith("\u2026"));
        assertTrue(snippet.endsWith("\u2026"));
        assertEquals(202, snippet.codePointCount(0, snippet.length()));
        for (int i = 1; i < snippet.length() - 1;)
        {
            if (Character.isHighSurrogate(snippet.charAt(i)))
            {
                assertTrue(Character.isLowSurrogate(snippet.charAt(i + 1)));
                i += 2;
            }
            else
            {
                assertFalse(Character.isLowSurrogate(snippet.charAt(i)));
                i++;
            }
        }
    }

    @Test
    public void testSnippetWindowMapsExpandedLowercaseOffsetsBackToSource()
    {
        String snippet = querySnippet("\u0130".repeat(300) + " before NEEDLE after " + "x".repeat(300), "needle");
        assertTrue(snippet, snippet.contains(" before NEEDLE after "));
        assertEquals(202, snippet.codePointCount(0, snippet.length()));
    }

    @Test
    public void testYoAndYeMatchRequestedAndStoredAnchorsInBothDirections()
    {
        String yo = "\u0412\u0438\u0434\u044b \u0440\u0430\u0441\u0447\u0451\u0442\u0430";
        String ye = yo.replace('\u0451', '\u0435');
        for (String heading : List.of(yo, ye))
        {
            String markdown = "## " + heading + "\nBody\n";
            GuideSections sections = GuideSections.parse(markdown);
            assertTrue("Advertised anchors retain their original spelling",
                sections.tableOfContents().contains("#" + heading.toLowerCase(Locale.ROOT).replace(' ', '-')));
            for (String request : List.of(yo, ye))
            {
                String anchor = request.replace(' ', '-').toUpperCase(Locale.ROOT);
                for (String selector : List.of("#" + anchor, anchor))
                {
                    assertTrue(selector, sections.find(selector) != null);
                    assertEquals(markdown, sections.find(selector).text());
                }
            }
        }
    }

    @Test
    public void testExactAnchorsStayDistinctWhenYoAndYeFoldToTheSameValue()
    {
        GuideSections sections = GuideSections.parse("## \u0401\nFirst\n## \u0415\nSecond\n");
        assertEquals("## \u0401\nFirst\n", sections.find("#\u0451").text());
        assertEquals("## \u0415\nSecond\n", sections.find("#\u0435").text());
    }

    private static String querySnippet(String line, String query)
    {
        String result = GuideSections.parse("## Examples\n" + line + "\n").query(query);
        String prefix = "- Examples (#examples): ";
        int start = result.indexOf(prefix);
        assertTrue("Expected a matching line: " + result, start >= 0);
        start += prefix.length();
        return result.substring(start, result.indexOf('\n', start));
    }
}
