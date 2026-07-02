/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures;

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
 * it is the single headlessly unit-tested piece of the feature. All structural on-page strings are
 * English (project surface rule); the picture names/synonyms/labels/errors are real 1C data and stay
 * as-is (Cyrillic is expected). EVERY 1C-sourced string is HTML-escaped before it reaches the page,
 * so a name such as {@code <foo>} can never inject markup; the base64 PNG is embedded verbatim as a
 * {@code data:} URI. The output is deterministic: the same inputs always produce the same document.
 */
public final class CommonPicturesHtmlGenerator
{
    /** Media type of every embedded thumbnail (the reader always emits PNG). */
    private static final String DATA_URI_PREFIX = "data:image/png;base64,"; //$NON-NLS-1$

    /** Newline used between emitted lines (LF for a deterministic, platform-independent document). */
    private static final String NL = "\n"; //$NON-NLS-1$

    private CommonPicturesHtmlGenerator()
    {
        // Pure static utility.
    }

    /**
     * Renders the whole gallery page.
     *
     * @param configName the configuration name shown in the title/heading (a 1C string; escaped). A
     *            {@code null} is rendered as an empty string.
     * @param entries the pictures to render, in order (never {@code null}); each carries its variants
     *            (or an error). This is the already-capped subset — up to {@code totalCount} pictures
     *            exist in the configuration.
     * @param totalCount the total number of CommonPictures in the configuration (may exceed
     *            {@code entries.size()} when the page was capped); a "showing X of Y" notice is
     *            emitted when {@code entries.size() < totalCount}
     * @return a self-contained HTML document string (never {@code null})
     */
    public static String render(String configName, List<PicturePageEntry> entries, int totalCount)
    {
        List<PicturePageEntry> safeEntries = entries != null ? entries : new ArrayList<>();
        String title = "Common Pictures: " + safe(configName); //$NON-NLS-1$

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html>").append(NL); //$NON-NLS-1$
        sb.append("<html lang=\"en\">").append(NL); //$NON-NLS-1$
        sb.append("<head>").append(NL); //$NON-NLS-1$
        sb.append("<meta charset=\"UTF-8\">").append(NL); //$NON-NLS-1$
        sb.append("<title>").append(escapeHtml(title)).append("</title>").append(NL); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(styleBlock());
        sb.append("</head>").append(NL); //$NON-NLS-1$
        sb.append("<body>").append(NL); //$NON-NLS-1$

        sb.append("<h1>").append(escapeHtml(title)).append("</h1>").append(NL); //$NON-NLS-1$ //$NON-NLS-2$
        appendCountNotice(sb, safeEntries.size(), totalCount);

        sb.append("<div class=\"grid\">").append(NL); //$NON-NLS-1$
        for (PicturePageEntry entry : safeEntries)
        {
            appendCard(sb, entry);
        }
        sb.append("</div>").append(NL); //$NON-NLS-1$

        sb.append("</body>").append(NL); //$NON-NLS-1$
        sb.append("</html>").append(NL); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Emits the total count and, when the page was capped, a "showing X of Y" notice so the developer
     * knows some pictures were not decompressed.
     *
     * @param sb the buffer
     * @param shown the number of pictures rendered on the page
     * @param totalCount the total number of pictures in the configuration
     */
    private static void appendCountNotice(StringBuilder sb, int shown, int totalCount)
    {
        int total = Math.max(totalCount, shown);
        sb.append("<p class=\"total\">Total: ").append(total).append(" picture"); //$NON-NLS-1$ //$NON-NLS-2$
        if (total != 1)
        {
            sb.append('s');
        }
        if (shown < total)
        {
            sb.append(" &mdash; showing ").append(shown).append(" of ").append(total) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" (the rest were not decompressed)"); //$NON-NLS-1$
        }
        sb.append("</p>").append(NL); //$NON-NLS-1$
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
        sb.append("</div>").append(NL); //$NON-NLS-1$

        if (hasError)
        {
            sb.append("<div class=\"error\">Could not read this picture's Picture.zip: ") //$NON-NLS-1$
                .append(escapeHtml(entry.error)).append("</div>").append(NL); //$NON-NLS-1$
        }
        else if (entry.variants == null || entry.variants.isEmpty())
        {
            sb.append("<div class=\"novariants\">No variants</div>").append(NL); //$NON-NLS-1$
        }
        else
        {
            appendBest(sb, entry.best);
            appendVariants(sb, entry.variants);
        }

        sb.append("</div>").append(NL); //$NON-NLS-1$
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
        sb.append("</div>").append(NL); //$NON-NLS-1$
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
                    .append("</div>").append(NL); //$NON-NLS-1$
            }
            sb.append("</figure>").append(NL); //$NON-NLS-1$
        }
        sb.append("</div>").append(NL); //$NON-NLS-1$
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
            + ".total{color:#555;margin:0 0 16px;font-size:13px;}" + NL //$NON-NLS-1$
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
