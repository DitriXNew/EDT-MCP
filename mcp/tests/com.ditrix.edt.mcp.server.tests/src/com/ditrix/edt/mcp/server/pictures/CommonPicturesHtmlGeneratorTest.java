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

import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.GalleryPage;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.PicturePageEntry;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.Variant;

/**
 * Headless ratchet for the pure {@link CommonPicturesHtmlGenerator}: it needs no SWT/EMF/live model,
 * so the whole rendering contract is unit-tested here. Asserts that a variant embeds its base64 PNG
 * as a {@code data:} URI, every variant label appears, an empty variant list renders the "Нет
 * вариантов" note, a non-null error renders an error card, the search box + pager render with correct
 * Prev/Next enabling at the first/last page and a filtered header reflects the query, and — the
 * security invariant — a 1C name containing {@code <}/{@code &} is HTML-escaped so it cannot inject
 * markup (in a card AND when reflected in the «фильтр» header).
 */
public class CommonPicturesHtmlGeneratorTest
{
    /** A 1-by-1 transparent PNG, base64 (a valid data-URI payload; content is irrelevant to the test). */
    private static final String B64_PNG =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="; //$NON-NLS-1$

    /** The gallery's page size — the number of pictures decompressed per page. */
    private static final int PAGE_SIZE = 100;

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

    /** Renders a single-page gallery (query="", page 0, filteredTotal = entries.size()). */
    private static String renderSinglePage(String configName, List<PicturePageEntry> entries)
    {
        return CommonPicturesHtmlGenerator.render(
            new GalleryPage(configName, entries, entries.size(), 0, PAGE_SIZE, "")); //$NON-NLS-1$
    }

    // --- happy path: images, labels ---------------------------------------

    @Test
    public void rendersImageAsBase64DataUri()
    {
        PicturePageEntry pic = entry("Icon", "Пиктограмма", Arrays.asList(variant("HDPI/Light")), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String html = renderSinglePage("MyConfig", Arrays.asList(pic)); //$NON-NLS-1$

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

        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

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
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        assertFalse("no 'best' thumbnail must emit no .best block", html.contains("class=\"best\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void rendersPerVariantErrorUnderItsLabel()
    {
        Variant broken = new Variant("HDPI/Dark", null); //$NON-NLS-1$
        broken.error = "could not decode this variant"; //$NON-NLS-1$
        PicturePageEntry pic = entry("Icon", null, Arrays.asList(broken), null); //$NON-NLS-1$

        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

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

        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        for (Variant v : variants)
        {
            assertTrue("label must appear on the page: " + v.label, html.contains(v.label)); //$NON-NLS-1$
        }
    }

    @Test
    public void rendersConfigNameInTitleAndHeading()
    {
        String html = renderSinglePage("SalesConfig", Collections.<PicturePageEntry> emptyList()); //$NON-NLS-1$

        assertTrue("title must carry the config name", //$NON-NLS-1$
            html.contains("<title>Общие картинки: SalesConfig</title>")); //$NON-NLS-1$
        assertTrue("heading must carry the config name", //$NON-NLS-1$
            html.contains("<h1>Общие картинки: SalesConfig</h1>")); //$NON-NLS-1$
    }

    @Test
    public void rendersSynonymWhenPresent()
    {
        PicturePageEntry pic = entry("Save", "Сохранить", Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        assertTrue("synonym must appear next to the name", html.contains("Сохранить")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- empty variants: "Нет вариантов" note -----------------------------

    @Test
    public void rendersNoVariantsNoteForEmptyList()
    {
        PicturePageEntry pic = entry("Empty", null, new ArrayList<Variant>(), null); //$NON-NLS-1$
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        assertTrue("empty variant list must render the 'Нет вариантов' note", //$NON-NLS-1$
            html.contains("Нет вариантов")); //$NON-NLS-1$
    }

    @Test
    public void rendersNoVariantsNoteForNullList()
    {
        PicturePageEntry pic = entry("NullVariants", null, null, null); //$NON-NLS-1$
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        assertTrue("null variant list must render the 'Нет вариантов' note", //$NON-NLS-1$
            html.contains("Нет вариантов")); //$NON-NLS-1$
    }

    // --- error card -------------------------------------------------------

    @Test
    public void rendersErrorCardForNonNullError()
    {
        PicturePageEntry pic = entry("Broken", null, null, "Picture.zip is corrupt"); //$NON-NLS-1$ //$NON-NLS-2$
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        assertTrue("error message must be surfaced", html.contains("Picture.zip is corrupt")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a broken picture must render the error card style", html.contains("card-error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an error card must not also show the 'Нет вариантов' note", //$NON-NLS-1$
            html.contains("Нет вариантов")); //$NON-NLS-1$
    }

    @Test
    public void oneBrokenPictureDoesNotAbortTheRestOfThePage()
    {
        PicturePageEntry broken = entry("Broken", null, null, "unreadable"); //$NON-NLS-1$ //$NON-NLS-2$
        PicturePageEntry good = entry("Good", null, Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$
        String html = renderSinglePage("Cfg", Arrays.asList(broken, good)); //$NON-NLS-1$

        assertTrue("the broken picture card must appear", html.contains("unreadable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the healthy picture that follows must still render", html.contains("Good")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the healthy picture's thumbnail must still render", //$NON-NLS-1$
            html.contains("data:image/png;base64," + B64_PNG)); //$NON-NLS-1$
    }

    // --- search box -------------------------------------------------------

    @Test
    public void rendersSearchBoxWithPlaceholderAndButton()
    {
        String html = renderSinglePage("Cfg", Collections.<PicturePageEntry> emptyList()); //$NON-NLS-1$

        assertTrue("a search input must appear", html.contains("<input")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Russian search placeholder must appear", //$NON-NLS-1$
            html.contains("placeholder=\"Поиск по имени...\"")); //$NON-NLS-1$
        assertTrue("the Russian 'Найти' button must appear", html.contains(">Найти</button>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Enter must submit the search (keyCode 13 handler)", //$NON-NLS-1$
            html.contains("event.keyCode===13")); //$NON-NLS-1$
        assertTrue("a search must navigate to page 0 of the edtmcp: scheme", //$NON-NLS-1$
            html.contains("edtmcp:gallery") && html.contains("page=0")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void reflectsActiveQueryInSearchInputValue()
    {
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", Collections.<PicturePageEntry> emptyList(), 0, 0, PAGE_SIZE, "Save")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the active query must pre-fill the search input", //$NON-NLS-1$
            html.contains("value=\"Save\"")); //$NON-NLS-1$
    }

    // --- pager: «Страница N из M», Prev/Next enable/disable ----------------

    @Test
    public void firstPageDisablesPrevAndEnablesNext()
    {
        // 250 filtered pictures at 100/page => 3 pages; page 0 => Prev disabled, Next enabled.
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", pageOfEntries(100), 250, 0, PAGE_SIZE, "")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the pager must show 'Страница 1 из 3'", html.contains("Страница 1 из 3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Prev must be disabled on the first page", //$NON-NLS-1$
            html.contains("<span class=\"pg prev disabled\">◀ Пред</span>")); //$NON-NLS-1$
        // The '&' between the query params is HTML-escaped to '&amp;' inside the href attribute.
        assertTrue("Next must be an enabled link on the first page", //$NON-NLS-1$
            html.contains("class=\"pg next\" href=\"edtmcp:gallery?q=&amp;page=1\"")); //$NON-NLS-1$
    }

    @Test
    public void middlePageEnablesBothPrevAndNext()
    {
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", pageOfEntries(100), 250, 1, PAGE_SIZE, "")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the pager must show 'Страница 2 из 3'", html.contains("Страница 2 из 3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Prev must link to page 0", //$NON-NLS-1$
            html.contains("class=\"pg prev\" href=\"edtmcp:gallery?q=&amp;page=0\"")); //$NON-NLS-1$
        assertTrue("Next must link to page 2", //$NON-NLS-1$
            html.contains("class=\"pg next\" href=\"edtmcp:gallery?q=&amp;page=2\"")); //$NON-NLS-1$
    }

    @Test
    public void lastPageEnablesPrevAndDisablesNext()
    {
        // Page 2 (0-based) is the last of 3; 50 entries on the final page.
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", pageOfEntries(50), 250, 2, PAGE_SIZE, "")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the pager must show 'Страница 3 из 3'", html.contains("Страница 3 из 3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Prev must be an enabled link on the last page", //$NON-NLS-1$
            html.contains("class=\"pg prev\" href=\"edtmcp:gallery?q=&amp;page=1\"")); //$NON-NLS-1$
        assertTrue("Next must be disabled on the last page", //$NON-NLS-1$
            html.contains("<span class=\"pg next disabled\">След ▶</span>")); //$NON-NLS-1$
    }

    @Test
    public void singlePageDisablesBothPrevAndNext()
    {
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", pageOfEntries(5), 5, 0, PAGE_SIZE, "")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a single page must read 'Страница 1 из 1'", html.contains("Страница 1 из 1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Prev disabled on a single page", //$NON-NLS-1$
            html.contains("<span class=\"pg prev disabled\">◀ Пред</span>")); //$NON-NLS-1$
        assertTrue("Next disabled on a single page", //$NON-NLS-1$
            html.contains("<span class=\"pg next disabled\">След ▶</span>")); //$NON-NLS-1$
    }

    // --- «Всего» header + filter -------------------------------------------

    @Test
    public void headerStatesFilteredTotalAndPage()
    {
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", pageOfEntries(100), 250, 1, PAGE_SIZE, "")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the header must state the filtered total", //$NON-NLS-1$
            html.contains("Всего: 250 картинок")); //$NON-NLS-1$
        assertTrue("the header must state the 1-based current page / page count", //$NON-NLS-1$
            html.contains("страница 2 из 3")); //$NON-NLS-1$
    }

    @Test
    public void headerReflectsActiveQueryFilter()
    {
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", pageOfEntries(3), 3, 0, PAGE_SIZE, "Save")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a filtered header must reflect the query", //$NON-NLS-1$
            html.contains("фильтр: \"Save\"")); //$NON-NLS-1$
        assertTrue("a filtered header states the (filtered) total", //$NON-NLS-1$
            html.contains("Всего: 3 картинок")); //$NON-NLS-1$
    }

    @Test
    public void headerOmitsFilterClauseWhenNoQuery()
    {
        String html = renderSinglePage("Cfg", pageOfEntries(3)); //$NON-NLS-1$

        assertFalse("no query means no 'фильтр:' clause", html.contains("фильтр:")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- security: HTML escaping of 1C strings ----------------------------

    @Test
    public void escapesNameContainingAngleBrackets()
    {
        // A 1C name that, unescaped, would inject a bogus <name> element / attack payload.
        PicturePageEntry pic = entry("<name>", null, Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        assertFalse("the raw '<name>' must NOT appear (would be injected markup)", //$NON-NLS-1$
            html.contains("<name>")); //$NON-NLS-1$
        assertTrue("the name must be HTML-escaped", html.contains("&lt;name&gt;")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void escapesErrorContainingMarkup()
    {
        PicturePageEntry pic = entry("Pic", null, null, "boom <script>alert(1)</script>"); //$NON-NLS-1$ //$NON-NLS-2$
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        // The page has one legitimate inline <script> (the search JS); the ERROR string's injected
        // script must not survive as executable markup — its payload appears only escaped.
        assertFalse("the injected <script>alert(1)</script> from the error string must not survive", //$NON-NLS-1$
            html.contains("<script>alert(1)</script>")); //$NON-NLS-1$
        assertTrue("the script text from the error must be escaped", //$NON-NLS-1$
            html.contains("boom &lt;script&gt;alert(1)&lt;/script&gt;")); //$NON-NLS-1$
    }

    @Test
    public void escapesLabelAndSynonymMarkup()
    {
        PicturePageEntry pic = entry("Pic", "syn<em>x</em>", Arrays.asList(variant("lab<b>el")), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String html = renderSinglePage("Cfg", Arrays.asList(pic)); //$NON-NLS-1$

        assertFalse("a raw <em> from a synonym must not survive", html.contains("<em>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a raw <b> from a label must not survive", html.contains("lab<b>el")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the label markup must be escaped", html.contains("lab&lt;b&gt;el")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void escapesQueryContainingMarkupInFilterHeaderAndSearchInput()
    {
        // A query containing < and & must be escaped both in the «фильтр» header AND the input value,
        // and never survive as raw markup anywhere on the page.
        String query = "a<b>&c"; //$NON-NLS-1$
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", Collections.<PicturePageEntry> emptyList(), 0, 0, PAGE_SIZE, query)); //$NON-NLS-1$

        assertFalse("the raw '<b>' from the query must not survive anywhere", html.contains("<b>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the query must be escaped in the 'фильтр' header", //$NON-NLS-1$
            html.contains("фильтр: \"a&lt;b&gt;&amp;c\"")); //$NON-NLS-1$
        assertTrue("the query must be escaped in the search input value", //$NON-NLS-1$
            html.contains("value=\"a&lt;b&gt;&amp;c\"")); //$NON-NLS-1$
    }

    @Test
    public void escapesQueryInCardNameAndFilterHeaderTogether()
    {
        // A picture NAME and the active filter both containing < and & must each be escaped: the card
        // name in its own tile, and the same query reflected in the header — neither injects markup.
        String query = "<x>&y"; //$NON-NLS-1$
        PicturePageEntry pic = entry("<x>&y", null, Arrays.asList(variant("HDPI")), null); //$NON-NLS-1$ //$NON-NLS-2$
        String html = CommonPicturesHtmlGenerator.render(
            new GalleryPage("Cfg", Arrays.asList(pic), 1, 0, PAGE_SIZE, query)); //$NON-NLS-1$

        assertFalse("the raw '<x>' must not survive in the card or the header", html.contains("<x>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the escaped name must appear in the card", html.contains("&lt;x&gt;&amp;y")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the escaped query must appear in the filter header", //$NON-NLS-1$
            html.contains("фильтр: \"&lt;x&gt;&amp;y\"")); //$NON-NLS-1$
    }

    // --- determinism ------------------------------------------------------

    @Test
    public void outputIsDeterministic()
    {
        List<PicturePageEntry> entries = Arrays.asList(
            entry("A", "СинонимA", Arrays.asList(variant("HDPI"), variant("MDPI")), null), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            entry("B", null, new ArrayList<Variant>(), null), //$NON-NLS-1$
            entry("C", null, null, "broken")); //$NON-NLS-1$ //$NON-NLS-2$

        String first = renderSinglePage("Cfg", entries); //$NON-NLS-1$
        String second = renderSinglePage("Cfg", entries); //$NON-NLS-1$

        assertEquals("the same inputs must produce byte-identical output", first, second); //$NON-NLS-1$
    }

    // --- null tolerance ---------------------------------------------------

    @Test
    public void toleratesNullPage()
    {
        String html = CommonPicturesHtmlGenerator.render(null);

        assertTrue("a well-formed document is still emitted", html.contains("<html")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the title prefix is still present", //$NON-NLS-1$
            html.contains("<title>Общие картинки: </title>")); //$NON-NLS-1$
    }

    @Test
    public void toleratesNullConfigNameAndNullEntries()
    {
        String html = CommonPicturesHtmlGenerator.render(new GalleryPage(null, null, 0, 0, PAGE_SIZE, null));

        assertTrue("a well-formed document is still emitted", html.contains("<html")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the title prefix is still present", //$NON-NLS-1$
            html.contains("<title>Общие картинки: </title>")); //$NON-NLS-1$
    }

    /** A list of {@code count} trivial single-variant entries (name A0, A1, …) for pager tests. */
    private static List<PicturePageEntry> pageOfEntries(int count)
    {
        List<PicturePageEntry> list = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            list.add(entry("A" + i, null, Arrays.asList(variant("HDPI")), null)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return list;
    }
}
