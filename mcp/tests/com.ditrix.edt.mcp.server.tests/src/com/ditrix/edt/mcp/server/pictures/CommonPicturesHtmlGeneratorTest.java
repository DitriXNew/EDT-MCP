/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.PicturePageEntry;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.Variant;

/**
 * Headless ratchet for the pure {@link CommonPicturesHtmlGenerator}: it needs no SWT/EMF/live model,
 * so the whole rendering contract is unit-tested here. Asserts that a variant embeds its base64 PNG
 * as a {@code data:} URI, every variant label appears, an empty variant list renders the "No
 * variants" note, a non-null error renders an error card, and — the security invariant — a 1C name
 * containing {@code <} is HTML-escaped so it cannot inject markup.
 */
public class CommonPicturesHtmlGeneratorTest
{
    /** A 1-by-1 transparent PNG, base64 (a valid data-URI payload; content is irrelevant to the test). */
    private static final String B64_PNG =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="; //$NON-NLS-1$

    private static Variant variant(String label)
    {
        return new Variant(label, B64_PNG);
    }

    private static PicturePageEntry entry(String name, String synonym, List<Variant> variants, String error)
    {
        PicturePageEntry e = new PicturePageEntry();
        e.name = name;
        e.synonym = synonym;
        e.variants = variants;
        e.error = error;
        return e;
    }

    // --- happy path: images, labels ---------------------------------------

    @Test
    public void rendersImageAsBase64DataUri()
    {
        PicturePageEntry pic = entry("Icon", "Пиктограмма", Arrays.asList(variant("HDPI/Light")), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String html = CommonPicturesHtmlGenerator.render("MyConfig", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertTrue("must embed the PNG as a data URI <img>", //$NON-NLS-1$
            html.contains("<img src=\"data:image/png;base64," + B64_PNG + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- frozen Q4: one 'best' thumbnail per picture inline ----------------

    @Test
    public void rendersBestThumbnailInline()
    {
        // A distinct base64 for 'best' so it is unmistakably the inline best image, not a variant.
        String bestB64 = "QUJDREVGaW1hZ2VCZXN0"; //$NON-NLS-1$
        PicturePageEntry pic = entry("Icon", null, Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$
        pic.best = new Variant("best", bestB64); //$NON-NLS-1$

        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertTrue("the 'best' thumbnail must be embedded inline (frozen Q4)", //$NON-NLS-1$
            html.contains("data:image/png;base64," + bestB64)); //$NON-NLS-1$
        assertTrue("the 'best' thumbnail must sit in its own .best block", //$NON-NLS-1$
            html.contains("class=\"best\"")); //$NON-NLS-1$
    }

    @Test
    public void omitsBestBlockWhenNoBestThumbnail()
    {
        PicturePageEntry pic = entry("Icon", null, Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$
        // pic.best left null: a picture with no separate best raster shows only its variant strip.
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertFalse("no 'best' thumbnail must emit no .best block", html.contains("class=\"best\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void rendersPerVariantErrorUnderItsLabel()
    {
        Variant broken = new Variant("HDPI/Dark", null); //$NON-NLS-1$
        broken.error = "could not decode this variant"; //$NON-NLS-1$
        PicturePageEntry pic = entry("Icon", null, Arrays.asList(broken), null); //$NON-NLS-1$

        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertTrue("a variant that failed to decode must surface its error", //$NON-NLS-1$
            html.contains("could not decode this variant")); //$NON-NLS-1$
        assertTrue("the per-variant error must sit in its own .variant-error block", //$NON-NLS-1$
            html.contains("class=\"variant-error\"")); //$NON-NLS-1$
    }

    @Test
    public void rendersEveryVariantLabel()
    {
        List<Variant> variants = Arrays.asList(
            variant("MDPI/Light/Taxi/LTR/template 16x16"), //$NON-NLS-1$
            variant("HDPI/Dark/Version8_5/RTL"), //$NON-NLS-1$
            variant("SVG vector")); //$NON-NLS-1$
        PicturePageEntry pic = entry("MultiVariant", null, variants, null); //$NON-NLS-1$

        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        for (Variant v : variants)
        {
            assertTrue("label must appear on the page: " + v.label, html.contains(v.label)); //$NON-NLS-1$
        }
    }

    @Test
    public void rendersConfigNameInTitleAndHeading()
    {
        String html = CommonPicturesHtmlGenerator.render("SalesConfig", //$NON-NLS-1$
            Collections.<PicturePageEntry> emptyList(), 0);

        assertTrue("title must carry the config name", //$NON-NLS-1$
            html.contains("<title>Common Pictures: SalesConfig</title>")); //$NON-NLS-1$
        assertTrue("heading must carry the config name", //$NON-NLS-1$
            html.contains("<h1>Common Pictures: SalesConfig</h1>")); //$NON-NLS-1$
    }

    @Test
    public void rendersSynonymWhenPresent()
    {
        PicturePageEntry pic = entry("Save", "Сохранить", Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertTrue("synonym must appear next to the name", html.contains("Сохранить")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- empty variants: "No variants" note -------------------------------

    @Test
    public void rendersNoVariantsNoteForEmptyList()
    {
        PicturePageEntry pic = entry("Empty", null, new ArrayList<Variant>(), null); //$NON-NLS-1$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertTrue("empty variant list must render the 'No variants' note", //$NON-NLS-1$
            html.contains("No variants")); //$NON-NLS-1$
    }

    @Test
    public void rendersNoVariantsNoteForNullList()
    {
        PicturePageEntry pic = entry("NullVariants", null, null, null); //$NON-NLS-1$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertTrue("null variant list must render the 'No variants' note", //$NON-NLS-1$
            html.contains("No variants")); //$NON-NLS-1$
    }

    // --- error card -------------------------------------------------------

    @Test
    public void rendersErrorCardForNonNullError()
    {
        PicturePageEntry pic = entry("Broken", null, null, "Picture.zip is corrupt"); //$NON-NLS-1$ //$NON-NLS-2$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertTrue("error message must be surfaced", html.contains("Picture.zip is corrupt")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a broken picture must render the error card style", html.contains("card-error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an error card must not also show the 'No variants' note", //$NON-NLS-1$
            errorCardShowsNoVariants(html));
    }

    /** Whether the (single-card) error page also emitted a No-variants note (it must not). */
    private static boolean errorCardShowsNoVariants(String html)
    {
        return html.contains("No variants"); //$NON-NLS-1$
    }

    @Test
    public void oneBrokenPictureDoesNotAbortTheRestOfThePage()
    {
        PicturePageEntry broken = entry("Broken", null, null, "unreadable"); //$NON-NLS-1$ //$NON-NLS-2$
        PicturePageEntry good = entry("Good", null, Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(broken, good), 2); //$NON-NLS-1$

        assertTrue("the broken picture card must appear", html.contains("unreadable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the healthy picture that follows must still render", html.contains("Good")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the healthy picture's thumbnail must still render", //$NON-NLS-1$
            html.contains("data:image/png;base64," + B64_PNG)); //$NON-NLS-1$
    }

    // --- "showing X of Y" notice ------------------------------------------

    @Test
    public void rendersShowingXofYNoticeWhenCapped()
    {
        List<PicturePageEntry> shown = Arrays.asList(
            entry("A", null, Arrays.asList(variant("HDPI")), null), //$NON-NLS-1$ //$NON-NLS-2$
            entry("B", null, Arrays.asList(variant("HDPI")), null)); //$NON-NLS-1$ //$NON-NLS-2$

        String html = CommonPicturesHtmlGenerator.render("Cfg", shown, 50); //$NON-NLS-1$

        assertTrue("a capped page must state the total", html.contains("Total: 50")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a capped page must show the 'showing X of Y' notice", //$NON-NLS-1$
            html.contains("showing 2 of 50")); //$NON-NLS-1$
    }

    @Test
    public void omitsShowingNoticeWhenNotCapped()
    {
        List<PicturePageEntry> shown = Arrays.asList(
            entry("A", null, Arrays.asList(variant("HDPI")), null)); //$NON-NLS-1$ //$NON-NLS-2$

        String html = CommonPicturesHtmlGenerator.render("Cfg", shown, 1); //$NON-NLS-1$

        assertTrue("an uncapped page must state the total", html.contains("Total: 1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an uncapped page must not show the 'showing X of Y' notice", //$NON-NLS-1$
            html.contains("showing")); //$NON-NLS-1$
    }

    // --- security: HTML escaping of 1C strings ----------------------------

    @Test
    public void escapesNameContainingAngleBrackets()
    {
        // A 1C name that, unescaped, would inject a bogus <name> element / attack payload.
        PicturePageEntry pic = entry("<name>", null, Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertFalse("the raw '<name>' must NOT appear (would be injected markup)", //$NON-NLS-1$
            html.contains("<name>")); //$NON-NLS-1$
        assertTrue("the name must be HTML-escaped", html.contains("&lt;name&gt;")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void escapesErrorContainingMarkup()
    {
        PicturePageEntry pic = entry("Pic", null, null, "boom <script>alert(1)</script>"); //$NON-NLS-1$ //$NON-NLS-2$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertFalse("a raw <script> from an error string must not survive", //$NON-NLS-1$
            html.contains("<script>")); //$NON-NLS-1$
        assertTrue("the script text must be escaped", html.contains("&lt;script&gt;")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void escapesLabelAndSynonymMarkup()
    {
        PicturePageEntry pic = entry("Pic", "syn<em>x</em>", Arrays.asList(variant("lab<b>el")), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String html = CommonPicturesHtmlGenerator.render("Cfg", Arrays.asList(pic), 1); //$NON-NLS-1$

        assertFalse("a raw <em> from a synonym must not survive", html.contains("<em>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a raw <b> from a label must not survive", html.contains("lab<b>el")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the label markup must be escaped", html.contains("lab&lt;b&gt;el")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- determinism ------------------------------------------------------

    @Test
    public void outputIsDeterministic()
    {
        List<PicturePageEntry> entries = Arrays.asList(
            entry("A", "СинонимA", Arrays.asList(variant("HDPI"), variant("MDPI")), null), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            entry("B", null, new ArrayList<Variant>(), null), //$NON-NLS-1$
            entry("C", null, null, "broken")); //$NON-NLS-1$ //$NON-NLS-2$

        String first = CommonPicturesHtmlGenerator.render("Cfg", entries, 3); //$NON-NLS-1$
        String second = CommonPicturesHtmlGenerator.render("Cfg", entries, 3); //$NON-NLS-1$

        assertEquals("the same inputs must produce byte-identical output", first, second); //$NON-NLS-1$
    }

    // --- null tolerance ---------------------------------------------------

    @Test
    public void toleratesNullConfigNameAndNullEntries()
    {
        String html = CommonPicturesHtmlGenerator.render(null, null, 0);

        assertTrue("a well-formed document is still emitted", html.contains("<html")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the title prefix is still present", //$NON-NLS-1$
            html.contains("<title>Common Pictures: </title>")); //$NON-NLS-1$
    }
}
