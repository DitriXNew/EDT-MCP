/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;

import com.ditrix.edt.mcp.server.Activator;

/**
 * A generic line-oriented regex search over the files of a project's {@code src/} tree: visits every
 * file with a given extension whose (src-stripped) path passes a caller-supplied filter, matches a
 * compiled {@link Pattern} against each line, and collects per-file matches with optional context.
 * <p>
 * The file-type and path-filtering policy live with the caller (a {@link Predicate} on the display
 * path), so both a BSL-module search and a Data Composition Schema search can share this one scanner
 * instead of each carrying its own resource visitor. Reads via {@link BslModuleUtils#readFileLines}
 * (UTF-8 BOM aware, with a filesystem fallback).
 */
public final class ResourceLineSearch
{
    private ResourceLineSearch()
    {
    }

    /** One matching line with its surrounding context (each context line is prefixed {@code "<n>: "}). */
    public static final class Match
    {
        public final int lineNumber;
        public final List<String> contextLines;

        Match(int lineNumber, List<String> contextLines)
        {
            this.lineNumber = lineNumber;
            this.contextLines = contextLines;
        }
    }

    /** The collected search outcome. {@link #matchesByFile} is populated only when details were requested. */
    public static final class Result
    {
        public final Map<String, List<Match>> matchesByFile = new LinkedHashMap<>();
        public final Map<String, Integer> matchCountByFile = new LinkedHashMap<>();
        public int totalMatches;
        public int totalMatchedFiles;
        public int skippedFiles;
        public int shownMatches;
        public boolean interrupted;
    }

    /**
     * Scans {@code project}'s {@code src/} tree.
     *
     * @param project the EDT project
     * @param fileExtension the file suffix to scan (e.g. {@code ".bsl"} / {@code ".dcs"})
     * @param pathFilter accepts a file's display path (project-relative, {@code src/} stripped); files
     *     for which it returns {@code false} are skipped. Pass {@code p -> true} for no filter.
     * @param pattern the compiled search pattern (already flagged for case/regex by the caller)
     * @param maxResults cap on the number of detailed matches collected (counts stay exact)
     * @param contextLines lines of context to keep before/after each match (detail mode)
     * @param collectDetails when {@code false}, only counts are collected (count / files output modes)
     * @return the collected {@link Result}; never {@code null}
     * @throws CoreException if the workspace traversal fails
     */
    public static Result search(IProject project, String fileExtension, Predicate<String> pathFilter,
        Pattern pattern, int maxResults, int contextLines, boolean collectDetails) throws CoreException
    {
        Result result = new Result();
        IResource srcFolder = project.findMember("src"); //$NON-NLS-1$
        if (srcFolder == null)
        {
            return result;
        }
        Predicate<String> filter = pathFilter != null ? pathFilter : p -> true;
        srcFolder.accept(new Visitor(result, fileExtension, filter, pattern, maxResults, contextLines,
            collectDetails));
        return result;
    }

    /** True when the project has a {@code src/} folder to scan (else {@link #search} returns an empty result). */
    public static boolean hasSrc(IProject project)
    {
        return project.findMember("src") != null; //$NON-NLS-1$
    }

    private static final class Visitor implements IResourceVisitor
    {
        private final Result result;
        private final String fileExtension;
        private final Predicate<String> pathFilter;
        private final Pattern pattern;
        private final int maxResults;
        private final int contextLines;
        private final boolean collectDetails;

        Visitor(Result result, String fileExtension, Predicate<String> pathFilter, Pattern pattern,
            int maxResults, int contextLines, boolean collectDetails)
        {
            this.result = result;
            this.fileExtension = fileExtension;
            this.pathFilter = pathFilter;
            this.pattern = pattern;
            this.maxResults = maxResults;
            this.contextLines = contextLines;
            this.collectDetails = collectDetails;
        }

        @Override
        public boolean visit(IResource resource)
        {
            if (Thread.currentThread().isInterrupted())
            {
                result.interrupted = true;
                return false;
            }
            if (resource.getType() != IResource.FILE)
            {
                return true; // descend into folders
            }
            if (!resource.getName().endsWith(fileExtension))
            {
                return false;
            }
            String displayPath = resource.getProjectRelativePath().toString();
            if (displayPath.startsWith("src/")) //$NON-NLS-1$
            {
                displayPath = displayPath.substring(4);
            }
            if (!pathFilter.test(displayPath))
            {
                return false;
            }
            try
            {
                searchInFile((IFile)resource, displayPath);
            }
            catch (Exception e)
            {
                Activator.logWarning("Failed to search in file: " + displayPath + " - " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                result.skippedFiles++;
            }
            return false;
        }

        private void searchInFile(IFile file, String displayPath) throws Exception
        {
            List<String> lines = BslModuleUtils.readFileLines(file);
            int fileMatches = 0;
            for (int i = 0; i < lines.size(); i++)
            {
                Matcher matcher = pattern.matcher(lines.get(i));
                if (!matcher.find())
                {
                    continue;
                }
                result.totalMatches++;
                fileMatches++;
                if (collectDetails && result.shownMatches < maxResults)
                {
                    int from = Math.max(0, i - contextLines);
                    int to = Math.min(lines.size() - 1, i + contextLines);
                    List<String> context = new ArrayList<>();
                    for (int j = from; j <= to; j++)
                    {
                        context.add((j + 1) + ": " + lines.get(j)); //$NON-NLS-1$
                    }
                    result.matchesByFile.computeIfAbsent(displayPath, k -> new ArrayList<>())
                        .add(new Match(i + 1, context));
                    result.shownMatches++;
                }
            }
            if (fileMatches > 0)
            {
                result.totalMatchedFiles++;
                result.matchCountByFile.put(displayPath, fileMatches);
            }
        }
    }
}
