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
}
