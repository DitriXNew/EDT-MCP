/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indexes the level-two sections of a rendered guide without rewriting their text.
 * Nested headings stay in their enclosing section; fenced examples are never headings.
 */
public final class GuideSections
{
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$"); //$NON-NLS-1$
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[\\t ]+(.*)|[\\t ]*)$"); //$NON-NLS-1$
    private static final Pattern CLOSING_HASHES = Pattern.compile("(?:^|[\\t ]+)#+[\\t ]*$"); //$NON-NLS-1$
    private static final Pattern SLUG_PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}\\p{M}_\\-\\s]"); //$NON-NLS-1$
    private static final Pattern SLUG_WHITESPACE = Pattern.compile("\\s"); //$NON-NLS-1$

    /** One addressable heading and its original text, including nested subsections. */
    public record Section(String heading, String anchor, String text)
    {
    }

    private final String introduction;
    private final List<Section> sections;

    private GuideSections(String introduction, List<Section> sections)
    {
        this.introduction = introduction;
        this.sections = List.copyOf(sections);
    }

    /** Scans ATX headings outside backtick/tilde fences, preserving every original byte of each span. */
    public static GuideSections parse(String markdown)
    {
        List<Section> sections = new ArrayList<>();
        Set<String> anchors = new HashSet<>();
        int firstSection = markdown.length();
        int sectionStart = -1;
        String heading = null;
        String anchor = null;
        char fenceChar = 0;
        int fenceLength = 0;
        for (int lineStart = 0; lineStart < markdown.length();)
        {
            int newline = markdown.indexOf('\n', lineStart);
            int lineEnd = newline >= 0 ? newline : markdown.length();
            String line = markdown.substring(lineStart, lineEnd);
            if (line.endsWith("\r")) //$NON-NLS-1$
            {
                line = line.substring(0, line.length() - 1);
            }
            Matcher fence = FENCE.matcher(line);
            if (fenceChar != 0)
            {
                if (fence.matches() && fence.group(1).charAt(0) == fenceChar
                    && fence.group(1).length() >= fenceLength && fence.group(2).isBlank())
                {
                    fenceChar = 0;
                }
            }
            else if (fence.matches()
                && (fence.group(1).charAt(0) != '`' || fence.group(2).indexOf('`') < 0))
            {
                fenceChar = fence.group(1).charAt(0);
                fenceLength = fence.group(1).length();
            }
            else
            {
                Matcher atx = HEADING.matcher(line);
                if (atx.matches())
                {
                    int level = atx.group(1).length();
                    String title = atx.group(2) == null ? "" //$NON-NLS-1$
                        : CLOSING_HASHES.matcher(atx.group(2)).replaceFirst("").strip(); //$NON-NLS-1$
                    String slug = uniqueAnchor(title, anchors);
                    if (level <= 2 && sectionStart >= 0)
                    {
                        sections.add(new Section(heading, anchor, markdown.substring(sectionStart, lineStart)));
                        sectionStart = -1;
                    }
                    if (level == 2)
                    {
                        firstSection = Math.min(firstSection, lineStart);
                        sectionStart = lineStart;
                        heading = title;
                        anchor = slug;
                    }
                }
            }
            lineStart = newline >= 0 ? newline + 1 : markdown.length();
        }
        if (sectionStart >= 0)
        {
            sections.add(new Section(heading, anchor, markdown.substring(sectionStart)));
        }
        return new GuideSections(markdown.substring(0, firstSection), sections);
    }

    private static String uniqueAnchor(String title, Set<String> anchors)
    {
        String slug = SLUG_WHITESPACE.matcher(
            SLUG_PUNCTUATION.matcher(title.toLowerCase(Locale.ROOT)).replaceAll("")).replaceAll("-"); //$NON-NLS-1$ //$NON-NLS-2$
        if (slug.isEmpty())
        {
            slug = "section"; //$NON-NLS-1$
        }
        String candidate = slug;
        for (int suffix = 1; !anchors.add(candidate); suffix++)
        {
            candidate = slug + "-" + suffix; //$NON-NLS-1$
        }
        return candidate;
    }

    /** Finds an exact title (first occurrence) or a unique anchor; an explicit #anchor takes priority. */
    public Section find(String selector)
    {
        String value = selector.strip();
        boolean explicitAnchor = value.startsWith("#"); //$NON-NLS-1$
        String anchor = explicitAnchor ? value.substring(1) : value;
        Section byTitle = null;
        Section byAnchor = null;
        for (Section section : sections)
        {
            if (byTitle == null && section.heading().equalsIgnoreCase(value))
            {
                byTitle = section;
            }
            if (section.anchor().equalsIgnoreCase(anchor))
            {
                byAnchor = section;
            }
        }
        if (explicitAnchor && byAnchor != null)
        {
            return byAnchor;
        }
        return byTitle != null ? byTitle : byAnchor;
    }

    /** Heading/anchor pairs for both discovery and actionable selection errors. */
    public String tableOfContents()
    {
        if (sections.isEmpty())
        {
            return "No ## sections.\n"; //$NON-NLS-1$
        }
        StringBuilder table = new StringBuilder(MarkdownUtils.tableHeader("Section", "Anchor")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Section section : sections)
        {
            table.append(MarkdownUtils.tableRow(section.heading(), "#" + section.anchor())); //$NON-NLS-1$
        }
        return table.toString();
    }

    /** Keeps the tool description and parameter table, replacing the guide body with its contents list. */
    public String summary()
    {
        StringBuilder summary = new StringBuilder(introduction);
        if (!sections.isEmpty() && "Parameters".equals(sections.get(0).heading())) //$NON-NLS-1$
        {
            summary.append(sections.get(0).text());
        }
        return summary.append("\n## Sections\n\n").append(tableOfContents()).toString(); //$NON-NLS-1$
    }

    /** Returns complete matching sections, in document order, using a literal case-insensitive search. */
    public String query(String query)
    {
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Section section : sections)
        {
            if (section.text().toLowerCase(Locale.ROOT).contains(needle))
            {
                matches.add(section.text());
            }
        }
        return matches.isEmpty()
            ? "No sections match query " + MarkdownUtils.inlineCode(query) //$NON-NLS-1$
                + ". Available sections:\n\n" + tableOfContents() //$NON-NLS-1$
            : String.join("\n", matches); //$NON-NLS-1$
    }
}
