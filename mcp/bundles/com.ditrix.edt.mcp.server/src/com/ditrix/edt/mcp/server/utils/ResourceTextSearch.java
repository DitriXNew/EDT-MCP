/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;

import com.ditrix.edt.mcp.server.Activator;

/**
 * The one line-by-line text scanner over a project's {@code src/} tree, shared by the
 * full-text search tools ({@code search_in_code} over {@code *.bsl}, {@code search_in_dcs}
 * over {@code *.dcs}). It is a resource visitor: walk a folder with
 * {@code folder.accept(search)} and read the result back from the accessors.
 * <p>
 * <b>Display path.</b> Every file is identified by its project-relative path with the leading
 * {@code src/} removed (e.g. {@code Reports/Stock/Templates/Main/Template.dcs}). The same string
 * is what {@code fileMask} is matched against, what {@code folderPrefix} must start with, and
 * what the result maps are keyed by - so a caller never sees one path in a filter and another
 * in the output.
 * <p>
 * <b>Counting.</b> Every file is scanned to the end, so {@link #getTotalMatches()} and the
 * per-file counts are exact; only the detailed matches (line plus context) stop at
 * {@code maxResults}, and only when details are collected at all. A line counts once however
 * many times the pattern occurs on it.
 * <p>
 * <b>Completeness.</b> A file whose lines cannot be read is skipped and counted
 * ({@link #getSkippedFiles()}); an interrupted thread stops the walk ({@link #wasInterrupted()}).
 * Either makes {@link #isComplete()} false: the absence of a match then proves nothing.
 */
public final class ResourceTextSearch implements IResourceVisitor
{
    /** The leading project folder stripped from every display path. */
    private static final String SRC_PREFIX = "src/"; //$NON-NLS-1$

    /** Reads a file's lines; a seam so a test can make a file unreadable. */
    @FunctionalInterface
    public interface LineReader
    {
        /**
         * @param file the file to read
         * @return its lines, without terminators
         * @throws Exception when the file cannot be read
         */
        List<String> read(IFile file) throws Exception; // NOSONAR mirrors BslModuleUtils.readFileLines
    }

    /** One matched line with its numbered context window. */
    public static final class Match
    {
        private final int lineNumber;
        private final List<String> contextLines;

        Match(int lineNumber, List<String> contextLines)
        {
            this.lineNumber = lineNumber;
            this.contextLines = Collections.unmodifiableList(contextLines);
        }

        /** @return the 1-based number of the matched line */
        public int getLineNumber()
        {
            return lineNumber;
        }

        /** @return the context window, each line rendered as {@code "<number>: <text>"} */
        public List<String> getContextLines()
        {
            return contextLines;
        }
    }

    private final Pattern pattern;
    private final String extension;
    private final String fileMask;
    private final String folderPrefix;
    private final int maxResults;
    private final int contextLines;
    private final boolean collectDetails;
    private final LineReader lineReader;
    private final UnaryOperator<String> matchLineTransform;

    private final Map<String, List<Match>> matchesByFile = new LinkedHashMap<>();
    private final Map<String, Integer> matchCountByFile = new LinkedHashMap<>();
    private int totalMatches;
    private int totalMatchedFiles;
    private int skippedFiles;
    private int collectedMatches;
    private boolean interrupted;

    /**
     * Creates a scanner that reads files with {@link BslModuleUtils#readFileLines(IFile)}.
     *
     * @param pattern the compiled pattern each line is matched against ({@code find()})
     * @param extension the file-name suffix to scan, e.g. {@code ".bsl"}; other files are skipped
     * @param fileMask a case-insensitive substring the display path must contain, or
     *     {@code null}/empty for no mask
     * @param folderPrefix a prefix the display path must start with (e.g. {@code "Documents/"}),
     *     or {@code null} for none
     * @param maxResults how many detailed matches to keep
     * @param contextLines lines of context kept before and after each detailed match
     * @param collectDetails whether to keep detailed matches at all (counts are always kept)
     */
    public ResourceTextSearch(Pattern pattern, String extension, String fileMask, String folderPrefix,
        int maxResults, int contextLines, boolean collectDetails)
    {
        this(pattern, extension, fileMask, folderPrefix, maxResults, contextLines, collectDetails,
            BslModuleUtils::readFileLines);
    }

    /**
     * Creates a scanner with an explicit line reader (the test seam).
     *
     * @param lineReader reads one file's lines
     * @see #ResourceTextSearch(Pattern, String, String, String, int, int, boolean)
     */
    public ResourceTextSearch(Pattern pattern, String extension, String fileMask, String folderPrefix,
        int maxResults, int contextLines, boolean collectDetails, LineReader lineReader)
    {
        this(pattern, extension, fileMask, folderPrefix, maxResults, contextLines, collectDetails,
            lineReader, UnaryOperator.identity());
    }

    /**
     * Creates a scanner that transforms each source line for matching only. Detailed context and
     * line numbers still refer to the original file, including when a transform decodes a newline.
     *
     * @param lineReader reads one file's lines
     * @param matchLineTransform transforms one source line before matching, without splitting lines
     * @see #ResourceTextSearch(Pattern, String, String, String, int, int, boolean)
     */
    public ResourceTextSearch(Pattern pattern, String extension, String fileMask, String folderPrefix,
        int maxResults, int contextLines, boolean collectDetails, LineReader lineReader,
        UnaryOperator<String> matchLineTransform)
    {
        this.pattern = pattern;
        this.extension = extension;
        this.fileMask = fileMask;
        this.folderPrefix = folderPrefix;
        this.maxResults = maxResults;
        this.contextLines = contextLines;
        this.collectDetails = collectDetails;
        this.lineReader = lineReader;
        this.matchLineTransform = matchLineTransform;
    }

    /**
     * The display path of a resource: its project-relative path without the leading
     * {@code src/}.
     *
     * @param resource the resource
     * @return the display path
     */
    public static String displayPath(IResource resource)
    {
        String relativePath = resource.getProjectRelativePath().toString();
        if (relativePath.startsWith(SRC_PREFIX))
        {
            return relativePath.substring(SRC_PREFIX.length());
        }
        return relativePath;
    }

    @Override
    public boolean visit(IResource resource) throws CoreException
    {
        if (Thread.currentThread().isInterrupted())
        {
            interrupted = true;
            return false;
        }

        if (resource.getType() != IResource.FILE)
        {
            return true; // Continue visiting children
        }

        if (!resource.getName().endsWith(extension))
        {
            return false;
        }

        String displayPath = displayPath(resource);

        if (fileMask != null && !fileMask.isEmpty()
            && !displayPath.toLowerCase(Locale.ROOT).contains(fileMask.toLowerCase(Locale.ROOT)))
        {
            return false;
        }

        if (folderPrefix != null && !displayPath.startsWith(folderPrefix))
        {
            return false;
        }

        try
        {
            searchInFile((IFile)resource, displayPath);
        }
        catch (Exception e) // NOSONAR any read failure skips the file and is reported, never thrown
        {
            Activator.logWarning("Failed to search in file: " + displayPath + " - " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            skippedFiles++;
        }

        return false;
    }

    private void searchInFile(IFile file, String displayPath) throws Exception
    {
        List<String> lines = lineReader.read(file);
        int fileMatches = 0;

        for (int i = 0; i < lines.size(); i++)
        {
            if (pattern.matcher(matchLineTransform.apply(lines.get(i))).find())
            {
                totalMatches++;
                fileMatches++;

                // Collect detailed match info only when asked and within the limit
                if (collectDetails && collectedMatches < maxResults)
                {
                    int from = Math.max(0, i - contextLines);
                    int to = Math.min(lines.size() - 1, i + contextLines);
                    List<String> context = new ArrayList<>();
                    for (int j = from; j <= to; j++)
                    {
                        context.add((j + 1) + ": " + lines.get(j)); //$NON-NLS-1$
                    }
                    matchesByFile.computeIfAbsent(displayPath, k -> new ArrayList<>())
                        .add(new Match(i + 1, context));
                    collectedMatches++;
                }
            }
        }

        if (fileMatches > 0)
        {
            totalMatchedFiles++;
            matchCountByFile.put(displayPath, fileMatches);
        }
    }

    /** @return detailed matches by display path, in walk order (empty unless details are collected) */
    public Map<String, List<Match>> getMatchesByFile()
    {
        return Collections.unmodifiableMap(matchesByFile);
    }

    /** @return the exact match count of every matched file by display path, in walk order */
    public Map<String, Integer> getMatchCountByFile()
    {
        return Collections.unmodifiableMap(matchCountByFile);
    }

    /** @return the exact number of matched lines across every scanned file */
    public int getTotalMatches()
    {
        return totalMatches;
    }

    /** @return the number of files with at least one match */
    public int getTotalMatchedFiles()
    {
        return totalMatchedFiles;
    }

    /** @return the number of detailed matches kept (at most {@code maxResults}) */
    public int getShownMatches()
    {
        return collectedMatches;
    }

    /** @return the number of files skipped because they could not be read */
    public int getSkippedFiles()
    {
        return skippedFiles;
    }

    /** @return whether the walk stopped on an interrupted thread */
    public boolean wasInterrupted()
    {
        return interrupted;
    }

    /** @return {@code true} when every candidate file was read and the walk was not interrupted */
    public boolean isComplete()
    {
        return !interrupted && skippedFiles == 0;
    }
}
