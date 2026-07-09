/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, headless HTML renderer for the CommonPictures preview gallery (#213 follow-up UI feature).
 * <p>
 * Given the DETACHED per-picture data collected out of a {@code BmTransactions.read} boundary (name,
 * synonym, a "best" inline thumbnail, per-variant label + base64 PNG, and an optional error), it
 * builds one self-contained {@code <html>} document — a grid of cards, one per picture. Per the frozen
 * Q4 decision, each card shows one prominent "best" thumbnail inline above the strip of per-variant
 * thumbnails, all as {@code <img src="data:image/png;base64,...">}. The SWT {@code Browser} host editor
 * feeds this string to {@code browser.setText(html)}.
 * <p>
 * This class has NO SWT/EMF/model dependency (only {@link java.util.List}/{@link StringBuilder}), so
 * it is the single headlessly unit-tested piece of the feature. The picture names/synonyms/labels/
 * errors are real 1C data and stay as-is (Cyrillic is expected). EVERY 1C-sourced string is
 * HTML-escaped before it reaches the page, so a name such as {@code <foo>} can never inject markup;
 * the base64 PNG is embedded verbatim as a {@code data:} URI. The output is deterministic: the same
 * inputs always produce the same document.
 * <p>
 * <b>Server-side search + pagination.</b> The gallery is paged: a {@link GalleryPage} carries the
 * already-filtered, already-paged {@code entries} plus the {@code filteredTotal} (the size of the
 * NAME/synonym filter over the WHOLE configuration, not just this page), the current {@code page},
 * the {@code pageSize} and the search {@code query}. From those this generator emits a search box and
 * a «◀ Пред · Страница N из M · След ▶» pager whose Prev/Next controls point at a custom
 * {@code edtmcp:gallery?q=<urlencoded>&page=<n>} URL; the SWT-Browser host editor intercepts that URL
 * (a {@code LocationListener}) and re-runs the filter/paginate seam off the UI thread. The
 * user-facing chrome (search placeholder, «Найти», «◀ Пред», «След ▶», the «Всего» header, «Нет
 * вариантов») is Russian to match the now-Russian menu and a Russian EDT; the picture NAMES stay
 * as-is (real data) and internal structural comments stay English.
 */
public final class CommonPicturesHtmlGenerator
{
    /** Media type of every embedded thumbnail (the reader always emits PNG). */
    private static final String DATA_URI_PREFIX = "data:image/png;base64,"; //$NON-NLS-1$

    /** Newline used between emitted lines (LF for a deterministic, platform-independent document). */
    private static final String NL = "\n"; //$NON-NLS-1$

    /** Closing {@code </div>} tag, emitted for every opened block wrapper. */
    private static final String CLOSE_DIV = "</div>"; //$NON-NLS-1$

    /**
     * The custom URL scheme+path the Prev/Next/search controls navigate to. The SWT-Browser host
     * editor's {@code LocationListener} recognises this prefix, cancels the real navigation, parses
     * {@code q}/{@code page} and re-renders. Keep this in sync with the editor's interceptor.
     */
    static final String NAV_URL_PREFIX = "edtmcp:gallery"; //$NON-NLS-1$

    private CommonPicturesHtmlGenerator()
    {
        // Pure static utility.
    }

    /**
     * Renders one page of the gallery: the title/heading, the search box, the «Всего …» header, the
     * «◀ Пред · Страница N из M · След ▶» pager, then the grid of this page's cards.
     *
     * @param page the page descriptor (configuration name, this page's already-filtered/paged
     *            entries, the filtered total, the page index, the page size, and the search query);
     *            a {@code null} page renders an empty gallery
     * @return a self-contained HTML document string (never {@code null})
     */
    public static String render(GalleryPage page)
    {
        GalleryPage safePage = page != null ? page : new GalleryPage(null, null, 0, 0, 1, null);
        List<PicturePageEntry> safeEntries =
            safePage.entries != null ? safePage.entries : new ArrayList<>();
        String query = safe(safePage.query);
        int pageSize = Math.max(1, safePage.pageSize);
        int filteredTotal = Math.max(safePage.filteredTotal, safeEntries.size());
        int pageCount = pageCount(filteredTotal, pageSize);
        int pageIndex = clamp(safePage.page, 0, pageCount - 1);
        String title = "Общие картинки: " + safe(safePage.configName); //$NON-NLS-1$

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html>").append(NL); //$NON-NLS-1$
        sb.append("<html lang=\"ru\">").append(NL); //$NON-NLS-1$
        sb.append("<head>").append(NL); //$NON-NLS-1$
        sb.append("<meta charset=\"UTF-8\">").append(NL); //$NON-NLS-1$
        sb.append("<title>").append(escapeHtml(title)).append("</title>").append(NL); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(styleBlock());
        sb.append("</head>").append(NL); //$NON-NLS-1$
        sb.append("<body>").append(NL); //$NON-NLS-1$

        sb.append("<h1>").append(escapeHtml(title)).append("</h1>").append(NL); //$NON-NLS-1$ //$NON-NLS-2$
        appendSearchBox(sb, query);
        appendHeader(sb, filteredTotal, pageIndex, pageCount, query);
        appendPager(sb, query, pageIndex, pageCount);

        sb.append("<div class=\"grid\">").append(NL); //$NON-NLS-1$
        for (PicturePageEntry entry : safeEntries)
        {
            appendCard(sb, entry);
        }
        sb.append(CLOSE_DIV).append(NL);

        sb.append("</body>").append(NL); //$NON-NLS-1$
        sb.append("</html>").append(NL); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Emits the search box (a text {@code <input>} pre-filled with the active query + a «Найти»
     * button) plus a small inline script: pressing Enter or clicking «Найти» navigates the browser to
     * {@code edtmcp:gallery?q=<encoded input>&page=0}, which the host editor intercepts to re-run the
     * server-side filter. The active query is HTML-escaped into the {@code value} attribute so a query
     * containing quotes/markup cannot break the input.
     *
     * @param sb the buffer
     * @param query the active search query (already null-safe; escaped here)
     */
    private static void appendSearchBox(StringBuilder sb, String query)
    {
        sb.append("<div class=\"search\">").append(NL); //$NON-NLS-1$
        sb.append("<input id=\"q\" type=\"text\" placeholder=\"Поиск по имени...\" value=\"") //$NON-NLS-1$
            .append(escapeHtml(query))
            .append("\" onkeydown=\"if(event.keyCode===13){edtmcpSearch();return false;}\">").append(NL); //$NON-NLS-1$
        sb.append("<button type=\"button\" onclick=\"edtmcpSearch()\">Найти</button>").append(NL); //$NON-NLS-1$
        sb.append(CLOSE_DIV).append(NL);
        // encodeURIComponent runs in the Browser; the editor's LocationListener does the decode. A
        // search always resets to page 0.
        sb.append("<script>function edtmcpSearch(){") //$NON-NLS-1$
            .append("var v=document.getElementById('q').value;") //$NON-NLS-1$
            .append("window.location.href='").append(NAV_URL_PREFIX) //$NON-NLS-1$
            .append("?q='+encodeURIComponent(v)+'&page=0';}</script>").append(NL); //$NON-NLS-1$
    }

    /**
     * Emits the «Всего: Y картинок · страница N из M · фильтр: "<q>"» header line (1-based page
     * numbers for the human; {@code filteredTotal} is the size of the filter over the WHOLE
     * configuration). The filter clause is only added when a query is active, and the query is
     * HTML-escaped so a name containing {@code <}/{@code &} cannot inject markup when reflected here.
     *
     * @param sb the buffer
     * @param filteredTotal the number of pictures matching the filter
     * @param pageIndex the current 0-based page index
     * @param pageCount the total number of pages (at least 1)
     * @param query the active search query (escaped here)
     */
    private static void appendHeader(StringBuilder sb, int filteredTotal, int pageIndex, int pageCount,
        String query)
    {
        sb.append("<p class=\"total\">Всего: ").append(filteredTotal).append(" картинок") //$NON-NLS-1$ //$NON-NLS-2$
            .append(" &middot; страница ").append(pageIndex + 1).append(" из ").append(pageCount); //$NON-NLS-1$ //$NON-NLS-2$
        if (!query.isEmpty())
        {
            sb.append(" &middot; фильтр: \"").append(escapeHtml(query)).append('"'); //$NON-NLS-1$
        }
        sb.append("</p>").append(NL); //$NON-NLS-1$
    }

    /**
     * Emits the «◀ Пред · Страница N из M · След ▶» pager. Prev is disabled on the first page and Next
     * on the last; an enabled control is an {@code <a href="edtmcp:gallery?q=...&page=...">} the host
     * editor intercepts (the query is carried across, URL-encoded), a disabled one is an inert
     * {@code <span>}. 1-based page numbers are shown to the human.
     *
     * @param sb the buffer
     * @param query the active search query, carried across the nav (URL-encoded here)
     * @param pageIndex the current 0-based page index
     * @param pageCount the total number of pages (at least 1)
     */
    private static void appendPager(StringBuilder sb, String query, int pageIndex, int pageCount)
    {
        sb.append("<div class=\"pager\">").append(NL); //$NON-NLS-1$
        appendPagerLink(sb, "prev", "◀ Пред", query, pageIndex - 1, pageIndex > 0); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("<span class=\"pageinfo\">Страница ").append(pageIndex + 1).append(" из ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(pageCount).append("</span>").append(NL); //$NON-NLS-1$
        appendPagerLink(sb, "next", "След ▶", query, pageIndex + 1, pageIndex < pageCount - 1); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(CLOSE_DIV).append(NL);
    }

    /**
     * Emits one pager control: an enabled {@code <a>} that navigates to the target page (carrying the
     * query), or an inert disabled {@code <span>}. The href is a fully URL-encoded
     * {@code edtmcp:gallery?q=...&page=...}; the visible label is escaped.
     *
     * @param sb the buffer
     * @param cssClass the control's CSS class ({@code prev}/{@code next})
     * @param label the visible label (escaped)
     * @param query the active search query (URL-encoded into the href)
     * @param targetPage the 0-based page this control navigates to
     * @param enabled whether the control is enabled
     */
    private static void appendPagerLink(StringBuilder sb, String cssClass, String label, String query,
        int targetPage, boolean enabled)
    {
        if (enabled)
        {
            sb.append("<a class=\"pg ").append(cssClass).append("\" href=\"") //$NON-NLS-1$ //$NON-NLS-2$
                .append(escapeHtml(navUrl(query, targetPage))).append("\">") //$NON-NLS-1$
                .append(escapeHtml(label)).append("</a>").append(NL); //$NON-NLS-1$
        }
        else
        {
            sb.append("<span class=\"pg ").append(cssClass).append(" disabled\">") //$NON-NLS-1$ //$NON-NLS-2$
                .append(escapeHtml(label)).append("</span>").append(NL); //$NON-NLS-1$
        }
    }

    /**
     * Builds the {@code edtmcp:gallery?q=<urlencoded>&page=<n>} nav URL for a target page, URL-encoding
     * the query so any character (including {@code &}/{@code =}/{@code <}) survives the round-trip
     * intact. The caller still HTML-escapes the whole URL before it enters an {@code href} attribute.
     *
     * @param query the search query (may be empty)
     * @param targetPage the 0-based target page
     * @return the nav URL
     */
    static String navUrl(String query, int targetPage)
    {
        return NAV_URL_PREFIX + "?q=" + urlEncode(query) + "&page=" + targetPage; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * URL-encodes a query string as UTF-8 for the {@code edtmcp:} nav URL. A {@code +} (space in
     * {@code application/x-www-form-urlencoded}) is left as-is by the editor's decoder, which uses the
     * same {@link java.net.URLDecoder} contract.
     *
     * @param text the query (null-safe)
     * @return the URL-encoded query
     */
    private static String urlEncode(String text)
    {
        try
        {
            return URLEncoder.encode(safe(text), StandardCharsets.UTF_8.name());
        }
        catch (UnsupportedEncodingException e) // NOSONAR UTF-8 is always available; unreachable
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Number of pages needed to show {@code filteredTotal} items at {@code pageSize} per page — always
     * at least 1, so an empty filter still renders «страница 1 из 1».
     *
     * @param filteredTotal the number of items after filtering
     * @param pageSize the page size (at least 1)
     * @return the page count (at least 1)
     */
    private static int pageCount(int filteredTotal, int pageSize)
    {
        if (filteredTotal <= 0)
        {
            return 1;
        }
        return (filteredTotal + pageSize - 1) / pageSize;
    }

    /**
     * Clamps {@code value} into {@code [min, max]} (with {@code max} treated as {@code >= min}).
     *
     * @param value the value
     * @param min the lower bound
     * @param max the upper bound
     * @return the clamped value
     */
    private static int clamp(int value, int min, int max)
    {
        int hi = Math.max(min, max);
        return Math.min(Math.max(value, min), hi);
    }

    /**
     * Appends one picture card: its name/synonym heading, then either an error note (non-null error),
     * a "No variants" note (empty variant list) or its variant thumbnails.
     *
     * @param sb the buffer
     * @param entry the picture (a {@code null} entry is skipped)
     */
    private static void appendCard(StringBuilder sb, PicturePageEntry entry)
    {
        if (entry == null)
        {
            return;
        }
        boolean hasError = entry.error != null && !entry.error.isEmpty();
        sb.append("<div class=\"card").append(hasError ? " card-error" : "").append("\">").append(NL); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        sb.append("<div class=\"name\">").append(escapeHtml(safe(entry.name))); //$NON-NLS-1$
        if (entry.synonym != null && !entry.synonym.isEmpty())
        {
            sb.append(" <span class=\"synonym\">(").append(escapeHtml(entry.synonym)).append(")</span>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(CLOSE_DIV).append(NL);

        if (hasError)
        {
            sb.append("<div class=\"error\">Could not read this picture's Picture.zip: ") //$NON-NLS-1$
                .append(escapeHtml(entry.error)).append(CLOSE_DIV).append(NL);
        }
        else if (entry.variants == null || entry.variants.isEmpty())
        {
            sb.append("<div class=\"novariants\">Нет вариантов</div>").append(NL); //$NON-NLS-1$
        }
        else
        {
            appendBest(sb, entry.best);
            appendVariants(sb, entry.variants);
        }

        sb.append(CLOSE_DIV).append(NL);
    }

    /**
     * Appends the picture's single "best" (densest) inline thumbnail above the per-variant grid, per the
     * frozen Q4 decision (one best thumbnail per picture inline). A {@code null} best thumbnail (or one
     * with no image bytes) renders nothing, so a picture that carries only per-variant thumbnails is
     * unaffected.
     *
     * @param sb the buffer
     * @param best the best inline thumbnail, or {@code null}
     */
    private static void appendBest(StringBuilder sb, Variant best)
    {
        if (best == null || best.base64Png == null || best.base64Png.isEmpty())
        {
            return;
        }
        sb.append("<div class=\"best\">").append(NL); //$NON-NLS-1$
        // The base64 is our own reader's output (never a raw 1C string), embedded verbatim as a data
        // URI; the alt text is a 1C-derived label and is escaped.
        sb.append("<img src=\"").append(DATA_URI_PREFIX).append(best.base64Png) //$NON-NLS-1$
            .append("\" alt=\"").append(escapeHtml(safe(best.label))).append("\">").append(NL); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(CLOSE_DIV).append(NL);
    }

    /**
     * Appends the variant thumbnails of one picture: each variant's label and its base64 PNG as a
     * {@code data:} URI (a variant with no base64 shows only its label; a variant that failed to decode
     * shows its per-variant error under the label).
     *
     * @param sb the buffer
     * @param variants the picture's variants (non-empty)
     */
    private static void appendVariants(StringBuilder sb, List<Variant> variants)
    {
        sb.append("<div class=\"variants\">").append(NL); //$NON-NLS-1$
        for (Variant variant : variants)
        {
            if (variant == null)
            {
                continue;
            }
            sb.append("<figure class=\"variant\">").append(NL); //$NON-NLS-1$
            if (variant.base64Png != null && !variant.base64Png.isEmpty())
            {
                // The base64 is our own reader's output (never a raw 1C string), embedded verbatim as
                // a data URI; the alt text is a 1C-derived label and is escaped.
                sb.append("<img src=\"").append(DATA_URI_PREFIX).append(variant.base64Png) //$NON-NLS-1$
                    .append("\" alt=\"").append(escapeHtml(safe(variant.label))).append("\">").append(NL); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("<figcaption>").append(escapeHtml(safe(variant.label))).append("</figcaption>").append(NL); //$NON-NLS-1$ //$NON-NLS-2$
            if (variant.error != null && !variant.error.isEmpty())
            {
                // A single variant that failed to decode: surfaced under its label, never aborts the card.
                sb.append("<div class=\"variant-error\">").append(escapeHtml(variant.error)) //$NON-NLS-1$
                    .append(CLOSE_DIV).append(NL);
            }
            sb.append("</figure>").append(NL); //$NON-NLS-1$
        }
        sb.append(CLOSE_DIV).append(NL);
    }

    /**
     * The inline stylesheet (a grid of cards). Kept minimal and static so the document is
     * self-contained and needs no external resources inside the SWT {@code Browser}.
     *
     * @return the {@code <style>} block, newline-terminated
     */
    private static String styleBlock()
    {
        return "<style>" + NL //$NON-NLS-1$
            + "body{font-family:Segoe UI,Tahoma,sans-serif;margin:16px;color:#1e1e1e;}" + NL //$NON-NLS-1$
            + "h1{font-size:18px;margin:0 0 4px;}" + NL //$NON-NLS-1$
            + ".total{color:#555;margin:0 0 8px;font-size:13px;}" + NL //$NON-NLS-1$
            + ".search{margin:0 0 8px;display:flex;gap:6px;}" + NL //$NON-NLS-1$
            + ".search input{flex:0 1 320px;padding:4px 6px;font-size:13px;" //$NON-NLS-1$
            + "border:1px solid #bbb;border-radius:4px;}" + NL //$NON-NLS-1$
            + ".search button{padding:4px 12px;font-size:13px;cursor:pointer;" //$NON-NLS-1$
            + "border:1px solid #bbb;border-radius:4px;background:#f0f0f0;}" + NL //$NON-NLS-1$
            + ".pager{margin:0 0 16px;display:flex;gap:10px;align-items:center;font-size:13px;}" + NL //$NON-NLS-1$
            + ".pager .pg{text-decoration:none;padding:3px 10px;border:1px solid #bbb;" //$NON-NLS-1$
            + "border-radius:4px;color:#1e1e1e;background:#f0f0f0;}" + NL //$NON-NLS-1$
            + ".pager .pg.disabled{color:#aaa;background:#f7f7f7;border-color:#ddd;cursor:default;}" + NL //$NON-NLS-1$
            + ".pager .pageinfo{color:#555;}" + NL //$NON-NLS-1$
            + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px;}" + NL //$NON-NLS-1$
            + ".card{border:1px solid #ccc;border-radius:6px;padding:10px;background:#fafafa;}" + NL //$NON-NLS-1$
            + ".card-error{border-color:#c0392b;background:#fdecea;}" + NL //$NON-NLS-1$
            + ".name{font-weight:600;margin-bottom:8px;word-break:break-all;}" + NL //$NON-NLS-1$
            + ".synonym{font-weight:400;color:#666;}" + NL //$NON-NLS-1$
            + ".error{color:#c0392b;font-size:13px;}" + NL //$NON-NLS-1$
            + ".novariants{color:#888;font-style:italic;font-size:13px;}" + NL //$NON-NLS-1$
            + ".best{margin-bottom:8px;}" + NL //$NON-NLS-1$
            + ".best img{max-width:96px;max-height:96px;border:1px solid #ddd;background:#fff;" //$NON-NLS-1$
            + "image-rendering:pixelated;}" + NL //$NON-NLS-1$
            + ".variants{display:flex;flex-wrap:wrap;gap:10px;}" + NL //$NON-NLS-1$
            + ".variant{margin:0;text-align:center;max-width:96px;}" + NL //$NON-NLS-1$
            + ".variant img{max-width:64px;max-height:64px;border:1px solid #ddd;background:#fff;" //$NON-NLS-1$
            + "image-rendering:pixelated;}" + NL //$NON-NLS-1$
            + ".variant figcaption{font-size:11px;color:#555;margin-top:2px;word-break:break-all;}" + NL //$NON-NLS-1$
            + ".variant-error{font-size:11px;color:#c0392b;margin-top:2px;word-break:break-all;}" + NL //$NON-NLS-1$
            + "</style>" + NL; //$NON-NLS-1$
    }

    /**
     * Escapes the five XML/HTML metacharacters so a 1C-sourced string can never inject markup or break
     * an attribute value. Null-safe (a {@code null} is treated as empty).
     *
     * @param text the raw 1C string (may be {@code null})
     * @return the HTML-safe string (never {@code null})
     */
    static String escapeHtml(String text)
    {
        if (text == null || text.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            switch (c)
            {
            case '&':
                out.append("&amp;"); //$NON-NLS-1$
                break;
            case '<':
                out.append("&lt;"); //$NON-NLS-1$
                break;
            case '>':
                out.append("&gt;"); //$NON-NLS-1$
                break;
            case '"':
                out.append("&quot;"); //$NON-NLS-1$
                break;
            case '\'':
                out.append("&#39;"); //$NON-NLS-1$
                break;
            default:
                out.append(c);
                break;
            }
        }
        return out.toString();
    }

    /**
     * Null-safe passthrough that turns a {@code null} into an empty string (before escaping).
     *
     * @param text the string, may be {@code null}
     * @return the string, or {@code ""} when {@code null}
     */
    private static String safe(String text)
    {
        return text != null ? text : ""; //$NON-NLS-1$
    }

    /**
     * The descriptor of one rendered gallery page: the configuration name, THIS page's already
     * filtered + paged entries, the filtered total (the count of NAME/synonym matches across the
     * WHOLE configuration — what «Всего» reflects and what the pager pages over), the 0-based page
     * index, the page size and the active search query. A DETACHED value object built by the handler
     * off the model; the generator reads it and never mutates it.
     */
    public static final class GalleryPage
    {
        /** The configuration name shown in the title/heading (a 1C string; escaped by the generator). */
        public final String configName;

        /** This page's entries, already filtered by the query and paged (never held as live EObjects). */
        public final List<PicturePageEntry> entries;

        /** The number of pictures matching the filter across the whole configuration (drives «Всего»/pager). */
        public final int filteredTotal;

        /** The current 0-based page index (clamped into range by the generator). */
        public final int page;

        /** The page size (pictures per page); the generator treats a value below 1 as 1. */
        public final int pageSize;

        /** The active search query ({@code null}/empty = no filter); escaped/encoded by the generator. */
        public final String query;

        /**
         * Creates a page descriptor.
         *
         * @param configName the configuration name for the title/heading
         * @param entries this page's already filtered + paged entries
         * @param filteredTotal the number of pictures matching the filter across the configuration
         * @param page the 0-based page index
         * @param pageSize the page size (pictures per page)
         * @param query the active search query (may be {@code null})
         */
        public GalleryPage(String configName, List<PicturePageEntry> entries, int filteredTotal, int page,
            int pageSize, String query)
        {
            this.configName = configName;
            this.entries = entries;
            this.filteredTotal = filteredTotal;
            this.page = page;
            this.pageSize = pageSize;
            this.query = query;
        }
    }

    /**
     * One picture's detached page data: its programmatic name, chosen-language synonym, the variant
     * thumbnails, and an optional error. A DETACHED POJO (no live {@code EObject}) so it is built
     * inside the read transaction and safely formatted after it closes. The generator escapes every
     * string field before it reaches the page.
     */
    public static final class PicturePageEntry
    {
        /** The picture's programmatic name (a 1C string; escaped by the generator). */
        public String name;

        /** The picture's synonym for the chosen language ({@code null}/empty renders no synonym). */
        public String synonym;

        /**
         * The picture's single "best" (densest) inline thumbnail, shown prominently above the per-variant
         * strip per the frozen Q4 decision. {@code null} (a picture with no multi-variant content, or one
         * whose 'best' selector did not resolve) renders no inline preview.
         */
        public Variant best;

        /** The picture's variant thumbnails ({@code null}/empty renders the "No variants" note). */
        public List<Variant> variants;

        /** Set when this picture's Picture.zip could not be read (renders an error card). */
        public String error;
    }

    /**
     * One picture variant thumbnail: a human label (dpi/theme/interface/direction/template + glyph
     * composed by the caller) and the base64 PNG bytes embedded as a {@code data:} URI. Detached (no
     * model handle). The label is escaped by the generator; the base64 is embedded verbatim.
     */
    public static final class Variant
    {
        /** The variant label (dpi/theme/interface/direction/template + glyph); escaped when rendered. */
        public String label;

        /** Base64-encoded PNG bytes ({@code null}/empty renders the label with no image). */
        public String base64Png;

        /**
         * Set when this single variant could not be decoded (renders a per-variant error note under the
         * label). One undecodable variant is surfaced here and never hides the picture's other variants.
         */
        public String error;

        /** Creates an empty variant (fields set by the caller). */
        public Variant()
        {
            // Fields populated by the caller (Dev-B/handler).
        }

        /**
         * Creates a variant with the given label and base64 PNG.
         *
         * @param label the variant label
         * @param base64Png the base64-encoded PNG bytes
         */
        public Variant(String label, String base64Png)
        {
            this.label = label;
            this.base64Png = base64Png;
        }
    }
}
