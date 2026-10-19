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
 * Indexes all heading levels in a rendered guide without rewriting their text.
 * The contents list shows level two; fenced examples are searchable text, never headings.
 */
public final class GuideSections
{
    private static final int MAX_QUERY_HITS = 20;
    private static final int MAX_SNIPPET_CHARS = 200;
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$"); //$NON-NLS-1$
    private static final Pattern LIST_MARKER = Pattern.compile("^ {0,3}(?:[-+*]|[0-9]{1,9}[.)]) {1,4}(?=\\S|$)"); //$NON-NLS-1$
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[\\t ]+(.*)|[\\t ]*)$"); //$NON-NLS-1$
    private static final Pattern CLOSING_HASHES = Pattern.compile("(?:^|[\\t ]+)#+[\\t ]*$"); //$NON-NLS-1$
    private static final Pattern SLUG_PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}\\p{M}_\\-\\s]"); //$NON-NLS-1$
    private static final Pattern SLUG_WHITESPACE = Pattern.compile("\\s"); //$NON-NLS-1$
    private static final Pattern LEADING_HASHES = Pattern.compile("^#+[\\t ]+"); //$NON-NLS-1$
    private static final Pattern TITLE_WHITESPACE = Pattern.compile("\\s+"); //$NON-NLS-1$

    /** One addressable heading and its original text, including nested subsections. */
    public record Section(String heading, String anchor, String text)
    {
    }

    private record Heading(int level, String title, String anchor, int start)
    {
    }

    private final String markdown;
    private final String introduction;
    private final List<Heading> headings;
    private final List<Section> sections;

    private GuideSections(String markdown, int firstSection, List<Heading> headings, List<Section> sections)
    {
        this.markdown = markdown;
        this.introduction = markdown.substring(0, firstSection);
        this.headings = List.copyOf(headings);
        this.sections = List.copyOf(sections);
    }

    /** Scans ATX headings outside backtick/tilde fences, preserving every original byte of each span. */
    public static GuideSections parse(String markdown)
    {
        List<Heading> headings = new ArrayList<>();
        Set<String> anchors = new HashSet<>();
        int firstSection = markdown.length();
        char fenceChar = 0;
        int fenceLength = 0;
        int fenceIndent = 0;
        List<Integer> listIndents = new ArrayList<>();
        for (int lineStart = 0; lineStart < markdown.length();)
        {
            int newline = markdown.indexOf('\n', lineStart);
            int lineEnd = newline >= 0 ? newline : markdown.length();
            String line = markdown.substring(lineStart, lineEnd);
            if (line.endsWith("\r")) //$NON-NLS-1$
            {
                line = line.substring(0, line.length() - 1);
            }
            String fenceLine = expandTabs(line);
            int indentation = 0;
            while (indentation < fenceLine.length() && fenceLine.charAt(indentation) == ' ')
            {
                indentation++;
            }
            if (!fenceLine.isBlank())
            {
                while (!listIndents.isEmpty() && indentation < listIndents.get(listIndents.size() - 1))
                {
                    listIndents.remove(listIndents.size() - 1);
                }
                if (fenceChar != 0 && indentation < fenceIndent)
                {
                    // A fence inside a list ends when its containing item ends.
                    fenceChar = 0;
                }
            }
            int listIndent = listIndents.isEmpty() ? 0 : listIndents.get(listIndents.size() - 1);
            String fenceContent = fenceLine.substring(Math.min(listIndent, fenceLine.length()));
            if (fenceChar == 0)
            {
                Matcher marker = LIST_MARKER.matcher(fenceContent);
                while (marker.find())
                {
                    listIndent += marker.end();
                    listIndents.add(listIndent);
                    fenceContent = fenceContent.substring(marker.end());
                    marker = LIST_MARKER.matcher(fenceContent);
                }
            }
            Matcher fence = FENCE.matcher(fenceContent);
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
                fenceIndent = listIndent;
            }
            else
            {
                Matcher atx = HEADING.matcher(line);
                if (atx.matches())
                {
                    int level = atx.group(1).length();
                    String title = atx.group(2) == null ? "" //$NON-NLS-1$
                        : CLOSING_HASHES.matcher(atx.group(2)).replaceFirst("").strip(); //$NON-NLS-1$
                    headings.add(new Heading(level, title, uniqueAnchor(title, anchors), lineStart));
                    if (level == 2)
                    {
                        firstSection = Math.min(firstSection, lineStart);
                    }
                }
            }
            lineStart = newline >= 0 ? newline + 1 : markdown.length();
        }
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++)
        {
            Heading heading = headings.get(i);
            int end = markdown.length();
            for (int j = i + 1; j < headings.size(); j++)
            {
                if (headings.get(j).level() <= heading.level())
                {
                    end = headings.get(j).start();
                    break;
                }
            }
            sections.add(new Section(heading.title(), heading.anchor(), markdown.substring(heading.start(), end)));
        }
        return new GuideSections(markdown, firstSection, headings, sections);
    }

    private static String expandTabs(String line)
    {
        if (line.indexOf('\t') < 0)
        {
            return line;
        }
        StringBuilder expanded = new StringBuilder();
        for (int i = 0; i < line.length(); i++)
        {
            char value = line.charAt(i);
            if (value == '\t')
            {
                expanded.append(" ".repeat(4 - expanded.length() % 4)); //$NON-NLS-1$
            }
            else
            {
                expanded.append(value);
            }
        }
        return expanded.toString();
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

    private static String fold(String value)
    {
        return MdNameNormalizer.normalizeYo(value.toLowerCase(Locale.ROOT));
    }

    private static String normalizeTitle(String value)
    {
        return fold(TITLE_WHITESPACE.matcher(value.replace("`", "").strip()).replaceAll(" ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Finds a title or unique anchor; exact addressing wins before removing pasted heading markers. */
    public Section find(String selector)
    {
        String value = selector.strip();
        if (value.isEmpty())
        {
            return null;
        }
        Section exact = findExact(value);
        return exact != null ? exact : findExact(LEADING_HASHES.matcher(value).replaceFirst("")); //$NON-NLS-1$
    }

    private Section findExact(String value)
    {
        boolean explicitAnchor = value.startsWith("#"); //$NON-NLS-1$
        String anchor = (explicitAnchor ? value.substring(1) : value).toLowerCase(Locale.ROOT);
        String foldedAnchor = fold(anchor);
        String title = normalizeTitle(value);
        Section byTitle = null;
        Section byAnchor = null;
        Section exactAnchor = null;
        for (Section section : sections)
        {
            if (byTitle == null && normalizeTitle(section.heading()).equals(title))
            {
                byTitle = section;
            }
            if (section.anchor().equals(anchor))
            {
                exactAnchor = section;
            }
            if (byAnchor == null && fold(section.anchor()).equals(foldedAnchor))
            {
                byAnchor = section;
            }
        }
        byAnchor = exactAnchor != null ? exactAnchor : byAnchor;
        if (explicitAnchor && byAnchor != null)
        {
            return byAnchor;
        }
        return byTitle != null ? byTitle : byAnchor;
    }

    /** Level-two heading/anchor pairs for discovery and actionable selection errors. */
    public String tableOfContents()
    {
        StringBuilder table = new StringBuilder();
        for (int i = 0; i < sections.size(); i++)
        {
            if (headings.get(i).level() == 2)
            {
                if (table.length() == 0)
                {
                    table.append(MarkdownUtils.tableHeader("Section", "Anchor")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                Section section = sections.get(i);
                table.append(MarkdownUtils.tableRow(section.heading(), "#" + section.anchor())); //$NON-NLS-1$
            }
        }
        return table.length() == 0 ? "No ## sections.\n" : table.toString(); //$NON-NLS-1$
    }

    /** Keeps the tool description and parameter table, replacing the guide body with its contents list. */
    public String summary()
    {
        StringBuilder summary = new StringBuilder(introduction);
        for (int i = 0; i < sections.size(); i++)
        {
            if (headings.get(i).level() == 2)
            {
                if ("Parameters".equals(sections.get(i).heading())) //$NON-NLS-1$
                {
                    summary.append(sections.get(i).text());
                }
                break;
            }
        }
        return summary.append("\n## Sections\n\n").append(tableOfContents()).toString(); //$NON-NLS-1$
    }

    /** Returns at most twenty matching lines with their enclosing headings, anchors and an exact remainder. */
    public String query(String query)
    {
        String needle = fold(query.strip());
        List<String> hits = new ArrayList<>();
        Set<String> matchingSections = new HashSet<>();
        int total = 0;
        int headingIndex = 0;
        Section current = null;
        for (int lineStart = 0; lineStart < markdown.length();)
        {
            while (headingIndex < headings.size() && headings.get(headingIndex).start() <= lineStart)
            {
                current = sections.get(headingIndex++);
            }
            int newline = markdown.indexOf('\n', lineStart);
            int lineEnd = newline >= 0 ? newline : markdown.length();
            String line = markdown.substring(lineStart, lineEnd);
            if (fold(line).contains(needle))
            {
                total++;
                matchingSections.add(current != null ? current.anchor() : ""); //$NON-NLS-1$
                if (hits.size() < MAX_QUERY_HITS)
                {
                    String snippet = snippet(line.strip(), needle);
                    String owner = current != null ? current.heading() + " (#" + current.anchor() + ")" //$NON-NLS-1$ //$NON-NLS-2$
                        : "Introduction"; //$NON-NLS-1$
                    hits.add("- " + owner + ": " + snippet + "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
            lineStart = newline >= 0 ? newline + 1 : markdown.length();
        }
        if (total == 0)
        {
            return "No sections match query " + MarkdownUtils.inlineCode(query) //$NON-NLS-1$
                + ". Available sections:\n\n" + tableOfContents(); //$NON-NLS-1$
        }
        StringBuilder result = new StringBuilder().append(total)
            .append(total == 1 ? " matching line in " : " matching lines in ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(matchingSections.size())
            .append(matchingSections.size() == 1 ? " section;" : " sections;") //$NON-NLS-1$ //$NON-NLS-2$
            .append(" fetch one with section=<heading|#anchor>.\n\n"); //$NON-NLS-1$
        hits.forEach(result::append);
        if (total > hits.size())
        {
            result.append("... (").append(total - hits.size()).append(" more)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result.toString();
    }

    private static String snippet(String line, String needle)
    {
        int length = line.codePointCount(0, line.length());
        if (length <= MAX_SNIPPET_CHARS)
        {
            return line;
        }
        int match = fold(line).indexOf(needle);
        int matchStart = 0;
        // Map the folded UTF-16 offset back to source code points; lowercase can expand U+0130.
        for (int offset = 0, foldedOffset = 0; offset < line.length();)
        {
            int next = line.offsetByCodePoints(offset, 1);
            int width = fold(line.substring(offset, next)).length();
            if (foldedOffset + width > match)
            {
                break;
            }
            foldedOffset += width;
            offset = next;
            matchStart++;
        }
        int context = Math.max(0, (MAX_SNIPPET_CHARS - needle.codePointCount(0, needle.length())) / 2);
        int start = Math.min(Math.max(0, matchStart - context), length - MAX_SNIPPET_CHARS);
        int end = start + MAX_SNIPPET_CHARS;
        return (start > 0 ? "\u2026" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + line.substring(line.offsetByCodePoints(0, start), line.offsetByCodePoints(0, end))
            + (end < length ? "\u2026" : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
