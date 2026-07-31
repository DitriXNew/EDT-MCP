/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.doc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Unit tests for {@link PlatformHelpService}'s HTML-to-text conversion (issue #299).
 * <p>
 * The syntax helper stores its sections as HTML fragments, so everything read out of it passes
 * through {@link PlatformHelpService#toPlainText}. Rendering a section raw put markup, seven-levels-
 * up relative hrefs and the help's own {@code {ссылка:...}} markers straight into an answer - these
 * tests lock the conversion down. The lookup itself needs a live EDT and is covered by the e2e
 * suite.
 */
public class PlatformHelpServiceTest
{
    @Test
    public void blankInputYieldsNothingAtAll()
    {
        // A missing section must render NOTHING rather than an empty paragraph: an EDT without the
        // platform documentation has to produce the same output as before the enrichment existed.
        assertNull(PlatformHelpService.toPlainText(null));
        assertNull(PlatformHelpService.toPlainText(""));
        assertNull(PlatformHelpService.toPlainText("   "));
        assertNull(PlatformHelpService.toPlainText("<div></div>"));
    }

    @Test
    public void tagsAreStrippedAndALinkKeepsItsVisibleText()
    {
        // The real shape of an AccessToken page: a link whose href climbs seven directories. The
        // words are what a caller needs; the href is noise that must not reach the answer.
        String html = "The <a href=\"../../../../../../../SyntaxHelperContext/objects/catalog63/"
            + "InternetMail/methods/Logon2043.html\">Logon</a> method authenticates.";
        assertEquals("The Logon method authenticates.", PlatformHelpService.toPlainText(html));
    }

    @Test
    public void lineBreaksBecomeSpacesRatherThanRunOnWords()
    {
        // <br> separates sentences in the source; dropping it outright would glue words together.
        assertEquals("String in Base64 format. Information about the key.",
            PlatformHelpService.toPlainText("String in Base64 format.<br>Information about the key."));
        assertEquals("One Two", PlatformHelpService.toPlainText("<p>One</p><p>Two</p>"));
    }

    @Test
    public void bracesThatAreNotHelpMarkersSurviveVerbatim()
    {
        // The first cut deleted EVERY brace-delimited fragment, which ate real documentation: an
        // XDTO namespace and a quantifier are content, not bookkeeping (issue #299 review).
        assertEquals("The namespace {http://v8.1c.ru/8.1/xdto} is used.",
            PlatformHelpService.toPlainText("The namespace {http://v8.1c.ru/8.1/xdto} is used."));
        assertEquals("Matches {3,5} times.", PlatformHelpService.toPlainText("Matches {3,5} times."));
    }

    @Test
    public void listItemsDoNotRunTogetherWhenTheirTagsAreStripped()
    {
        // <li>Area;</li><li>Stacked areas;</li> must not collapse into "Area;Stacked areas;".
        assertEquals("Area; Stacked areas;",
            PlatformHelpService.toPlainText("<ul><li>Area;</li><li>Stacked areas;</li></ul>"));
    }

    @Test
    public void helpCrossReferenceMarkersKeepTheReferencedName()
    {
        // The help's own marker, e.g. {ссылка:Объекты; 772; ИнтернетПочта} - the trailing name is
        // the readable part; the catalog/id bookkeeping is not.
        assertEquals("The InternetMail object will authenticate.", PlatformHelpService.toPlainText(
            "The {ссылка:Объекты; 772; InternetMail} object will authenticate."));
    }

    @Test
    public void entitiesAreDecodedAndWhitespaceCollapses()
    {
        assertEquals("a < b & c > d",
            PlatformHelpService.toPlainText("a &lt; b &amp; c &gt; d"));
        assertEquals("one two",
            PlatformHelpService.toPlainText("one&nbsp;&nbsp;   \n\t two"));
    }

    @Test
    public void aPlainSentenceSurvivesUnchanged()
    {
        // The common case: most sections are already plain prose and must not be mangled.
        String plain = "Adds a signature to the access token according to the algorithm specified.";
        assertEquals(plain, PlatformHelpService.toPlainText(plain));
    }

    @Test
    public void markupOnlyInputYieldsNothing()
    {
        // Stripping must not leave an "empty" description that would render as a blank paragraph.
        assertNull(PlatformHelpService.toPlainText("<div class=\"types\"><span></span></div>"));
    }

    @Test
    public void theDisabledInstanceReadsNothingAtAll()
    {
        // The concise rendering uses this to skip the lookups entirely - it must answer "no
        // documentation" without ever reaching for the helper.
        PlatformHelpService off = PlatformHelpService.disabled();
        assertTrue("the disabled instance must report itself unavailable", !off.isAvailable());
        assertNull(off.typeDescription("AccessToken"));
        assertNull(off.memberDescription("AccessToken", "Sign"));
        assertNull(off.methodReturnValue("AccessToken", "Sign"));
    }

    @Test
    public void anUnknownNameYieldsNoDocumentationRatherThanAFailure()
    {
        // The contract the renderer depends on: a lookup NEVER throws and NEVER returns an empty
        // string - "nothing to add" is expressed as null, so a missing section renders nothing at
        // all. Asserted against names no platform has, which makes the outcome the same whether or
        // not the syntax helper is reachable in the runtime this test happens to run in. (It IS
        // reachable under Tycho, which is why an earlier version of this test - built with a null
        // version so every lookup short-circuited - proved nothing.)
        PlatformHelpService service = new PlatformHelpService(Version.LATEST, "en");
        assertNull(service.typeDescription("Z299NoSuchType"));
        assertNull(service.memberDescription("Z299NoSuchType", "Z299NoSuchMember"));
        assertNull(service.methodReturnValue("Z299NoSuchType", "Z299NoSuchMember"));
    }
}
