/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com.e1c.g5.v8.dt.check.qfix.IFixRepository;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Builds report rows from EDT validation markers: the symbolic check id, the quick-fix flag, the
 * object presentation (or a placeholder when it cannot be resolved) and the BSL module locator.
 * Shared by {@code get_project_errors} and the written-object marker report of the metadata writes.
 */
public final class MarkerRows
{
    private MarkerRows()
    {
        // Utility class
    }

    /** One marker row. */
    public static class Row
    {
        /** Project that owns the marker, or {@code null}. */
        public String project;
        /** Short UID like "SU23". */
        public String checkCode;
        /** Symbolic ID like "bsl-legacy-check-expression-type", or {@code null}. */
        public String checkId;
        /** Check contributor, when the full UID could be resolved. */
        public String contributorId;
        /** The marker message. */
        public String message;
        /** Where the marker is, or a placeholder when it could not be resolved. */
        public String objectPresentation;
        /** Whether documentation exists for this check. */
        public boolean hasDocumentation;
        /** Source-folder-relative BSL module path, or {@code null}. */
        public String modulePath;
        /** 1-based line inside the module, or {@code null}. */
        public Integer line;
        /** Stored 0-based UTF-16 text offset, or {@code null}; never inferred from the line. */
        public Integer offset;
        /** Stored UTF-16 text length, or {@code null}; zero is a valid length. */
        public Integer length;
        /** Whether this check has an EDT auto-fix (apply via apply_quick_fix). */
        public boolean hasQuickFix;
    }

    /**
     * Applies the severity/checkId/objects filters to a single marker and, if it passes,
     * builds its row. Returns {@code null} when the marker is filtered out.
     *
     * <p>Must be called inside a BM read transaction so that
     * {@link Marker#getObjectPresentation()} can resolve. The symbolic check id is resolved
     * exactly once here and reused for both the checkId filter and the resulting row, avoiding a
     * second {@link ICheckRepository#getUidForShortUid} call. The filter order
     * (severity -> checkId -> objects) is preserved so the {@code unresolvedFilteredOut} counter
     * keeps the same semantics. {@code exactScope} selects segment-boundary vs substring matching -
     * see {@link #excludedByObjectsFilter(Set, boolean, String, int[], boolean)}.</p>
     *
     * @param <R> the row type
     * @param newRow creates the row to fill
     * @return the row, or {@code null} when filtered out
     */
    public static <R extends Row> R buildIfMatches(Marker marker, MarkerSeverity severityFilter, String checkId,
        Set<String> objects, ICheckRepository checkRepository, IFixRepository fixRepository,
        int[] unresolvedShown, int[] unresolvedFilteredOut, boolean exactScope, Supplier<R> newRow)
    {
        return buildIfMatches(marker, severityFilter, checkId, objects, Collections.<String> emptySet(),
            checkRepository, fixRepository, unresolvedShown, unresolvedFilteredOut, exactScope, newRow);
    }

    /**
     * As {@link #buildIfMatches(Marker, MarkerSeverity, String, Set, ICheckRepository, IFixRepository, int[],
     * int[], boolean, Supplier)}, plus the {@code modulePaths} filter: with non-empty
     * {@code modulePathKeys} (see {@link #modulePathKeys(Collection)}) only a marker whose BSL module
     * path is one of them is kept, and a marker without a module is excluded. The module path is a map
     * read on the marker, so the filter runs BEFORE the object presentation is resolved - an excluded
     * marker costs no model read and is counted in neither unresolved counter.
     *
     * @param modulePathKeys the module-path keys to keep; empty keeps every marker
     */
    public static <R extends Row> R buildIfMatches(Marker marker, MarkerSeverity severityFilter, String checkId,
        Set<String> objects, Set<String> modulePathKeys, ICheckRepository checkRepository,
        IFixRepository fixRepository, int[] unresolvedShown, int[] unresolvedFilteredOut, boolean exactScope,
        Supplier<R> newRow)
    {
        // Severity filter
        if (severityFilter != null && marker.getSeverity() != severityFilter)
        {
            return null;
        }

        // modulePaths filter: a map read on the marker, decided before any repository or model read.
        if (modulePathKeys != null && !modulePathKeys.isEmpty()
            && !modulePathMatches(moduleLocationOf(marker), modulePathKeys))
        {
            return null;
        }

        // Resolve the check UID once; reused for the symbolic check id (display + checkId filter)
        // AND for the hasQuickFix flag (does this check have a registered EDT auto-fix?).
        String shortUid = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
        CheckUid checkUid = resolveCheckUid(marker, shortUid, checkRepository);
        String symbolicCheckId = checkUid != null ? checkUid.getCheckId() : null;
        // Best-effort, like the object-presentation resolution below: a repository hiccup on ONE
        // marker (stale registration, transient service state) must not abort the whole listing -
        // just leave this row's flag unset.
        boolean hasQuickFix = false;
        if (checkUid != null && fixRepository != null)
        {
            try
            {
                hasQuickFix = fixRepository.hasFixes(checkUid);
            }
            catch (Exception e)
            {
                hasQuickFix = false;
            }
        }

        // checkId filter: match either the short UID (e.g. "SU23") or the symbolic id
        // (e.g. "semicolon-missing"). The short UID alone is rarely what callers type.
        if (checkId != null && !checkId.isEmpty() && !checkIdMatches(shortUid, symbolicCheckId, checkId))
        {
            return null;
        }

        // Resolve the object presentation once; reused for the objects filter and the row.
        // Failure handling differs by context (see below), so we only record the outcome here.
        String objectPresentation = null;
        boolean presentationResolved;
        try
        {
            String p = marker.getObjectPresentation();
            objectPresentation = p != null ? p : ""; //$NON-NLS-1$
            presentationResolved = true;
        }
        catch (Exception e)
        {
            presentationResolved = false;
        }

        // Objects filter (FQN matching against the resolved object presentation)
        if (excludedByObjectsFilter(objects, presentationResolved, objectPresentation, unresolvedFilteredOut,
            exactScope))
        {
            return null;
        }

        // Build the row, reusing the already resolved symbolic check id and presentation.
        R error = newRow.get();
        IProject project = marker.getProject();
        error.project = project != null ? project.getName() : null;
        error.checkCode = shortUid;
        error.checkId = symbolicCheckId;
        error.contributorId = checkUid != null ? checkUid.getContributorId() : null;
        error.hasDocumentation = symbolicCheckId != null && !symbolicCheckId.isEmpty()
            && CheckDescriptionLoader.has(symbolicCheckId);
        error.hasQuickFix = hasQuickFix;
        error.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$

        // Copy saved text coordinates inside the same read boundary as the rest of the row.
        populateModuleLocation(marker, error);
        if (presentationResolved)
        {
            error.objectPresentation = objectPresentation;
        }
        else
        {
            // No objects filter was active (otherwise we would have returned above): keep the
            // marker with a placeholder location instead of dropping it, and count it.
            unresolvedShown[0]++;
            error.objectPresentation = unresolvedPlaceholder(marker);
        }
        return error;
    }

    /**
     * Decides whether the marker is excluded by an explicit objects filter, matching the resolved
     * object presentation against the FQN variants. Returns {@code false} when no objects filter
     * is active. As a side effect, increments {@code unresolvedFilteredOut} for a marker whose
     * presentation could not be resolved while a filter is active (the marker is excluded but
     * counted separately so the caller can warn about it).
     * <p>
     * When {@code exactScope} is {@code false} (the default get_project_errors behavior) a variant
     * matches by SUBSTRING ({@code contains}) - loose on purpose, so a caller filtering by
     * {@code Catalog.Order} also sees markers on its members. When {@code true} a variant matches
     * only at a SEGMENT BOUNDARY: the presentation must EQUAL the variant or start with
     * {@code variant + "."} (the object itself or a member strictly under it) - so
     * {@code XDTOPackage.P} no longer matches {@code XDTOPackage.P2}'s markers. Used by
     * validate_xdto_package, which needs exact per-package scoping (a substring match across
     * prefix-sharing package names is a false failure).
     */
    public static boolean excludedByObjectsFilter(Set<String> objects, boolean presentationResolved,
        String objectPresentation, int[] unresolvedFilteredOut, boolean exactScope)
    {
        if (objects.isEmpty())
        {
            return false;
        }
        if (!presentationResolved)
        {
            // Cannot resolve the location, so we cannot decide membership for an
            // explicit object filter. The marker is excluded from the result; count it
            // separately so the caller is warned that it was filtered out, not shown.
            unresolvedFilteredOut[0]++;
            return true;
        }
        if (objectPresentation.isEmpty())
        {
            return true;
        }

        String presentationLower = objectPresentation.toLowerCase(Locale.ROOT);
        for (String fqnVariant : objects)
        {
            boolean matches = exactScope
                ? presentationLower.equals(fqnVariant) || presentationLower.startsWith(fqnVariant + ".") //$NON-NLS-1$
                : presentationLower.contains(fqnVariant);
            if (matches)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a marker's full {@link CheckUid} (e.g. {@code semicolon-missing}) from its short UID
     * (e.g. {@code SU23}) via the check repository, or {@code null} when it cannot be resolved. The
     * UID is the key both for the symbolic check id (display) and for {@code IFixRepository.hasFixes}
     * (the quick-fix flag), so it is resolved once and reused.
     */
    public static CheckUid resolveCheckUid(Marker marker, String shortUid, ICheckRepository checkRepository)
    {
        if (checkRepository == null || shortUid == null || shortUid.isEmpty() || marker.getProject() == null)
        {
            return null;
        }
        try
        {
            return checkRepository.getUidForShortUid(shortUid, marker.getProject());
        }
        catch (Exception e)
        {
            // Ignore - caller falls back to the short UID / no fix flag
            return null;
        }
    }

    /**
     * Returns true when the user supplied checkId substring matches either the marker
     * short UID or its already resolved symbolic check id.
     */
    public static boolean checkIdMatches(String shortUid, String symbolicCheckId, String checkId)
    {
        String needle = checkId.toLowerCase(Locale.ROOT);
        if (shortUid != null && shortUid.toLowerCase(Locale.ROOT).contains(needle))
        {
            return true;
        }
        return symbolicCheckId != null && symbolicCheckId.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Placeholder location for a marker whose {@link Marker#getObjectPresentation()} could not
     * be resolved, so the marker is reported instead of being dropped.
     */
    public static String unresolvedPlaceholder(Marker marker)
    {
        IProject project = marker.getProject();
        return "<unresolved: " + (project != null ? project.getName() : "?") + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Copies saved text coordinates and the structural BSL locator into the detached row.
     *
     * <p>The locator is read straight from the marker's {@link Marker#getExtraInfo()} map —
     * {@link StandardExtraInfo#TEXT_URI_TO_PROBLEM} (the EMF platform URI of the problem) and
     * {@link StandardExtraInfo#TEXT_LINE} (1-based line), {@link StandardExtraInfo#TEXT_OFFSET}
     * and {@link StandardExtraInfo#TEXT_LENGTH}. Call inside the marker's BM read boundary.
     * Coordinates are independent optional values; no source read, normalization or bounds
     * correction is performed. Missing, malformed or negative values remain {@code null}.</p>
     *
     * <p>The module path is only set when the URI genuinely resolves to a {@code .bsl} module
     * under the source folder, so it matches the {@code modulePath} shape accepted by
     * {@code read_module_source} / {@code set_breakpoint}. The line is only set when the path
     * is set, so a caller never gets a line without a module to apply it to.</p>
     */
    public static void populateModuleLocation(Marker marker, Row error)
    {
        IExtraInfoMap extraInfo = marker.getExtraInfo();
        if (extraInfo == null)
        {
            return;
        }

        error.offset = nonNegativeTextCoordinate(extraInfo, StandardExtraInfo.TEXT_OFFSET);
        error.length = nonNegativeTextCoordinate(extraInfo, StandardExtraInfo.TEXT_LENGTH);

        String uriToProblem = extraInfo.get(StandardExtraInfo.TEXT_URI_TO_PROBLEM);
        String modulePath = resolveBslModulePath(uriToProblem);
        if (modulePath == null)
        {
            // No BSL module location: leave both null rather than inventing a path.
            return;
        }
        error.modulePath = modulePath;

        Integer line = extraInfo.get(StandardExtraInfo.TEXT_LINE);
        if (line != null && line.intValue() >= 1)
        {
            error.line = line;
        }
    }

    private static Integer nonNegativeTextCoordinate(IExtraInfoMap extraInfo, StandardExtraInfo key)
    {
        try
        {
            Integer value = extraInfo.get(key);
            return value != null && value.intValue() >= 0 ? value : null;
        }
        catch (NumberFormatException e)
        {
            // A malformed optional coordinate must not discard the marker or the report.
            return null;
        }
    }

    /**
     * Derives a source-folder-relative BSL module path (the shape
     * {@code read_module_source} / {@code set_breakpoint} accept, e.g.
     * {@code "CommonModules/MyModule/Module.bsl"}) from an EMF problem URI string, or
     * {@code null} when the URI is absent, unparseable, or does not point at a {@code .bsl}
     * module under the source folder.
     *
     * <p>The URI is a platform resource URI like
     * {@code platform:/resource/<Project>/src/<modulePath>.bsl#<fragment>}. The fragment is
     * trimmed and the {@code <Project>/src/} prefix is stripped via
     * {@link BslModuleUtils#extractModulePath(String)} (the single source of truth for the
     * {@code /src/} assumption). A URI whose platform path contains no {@code /src/} segment,
     * or whose file extension is not {@code bsl}, yields {@code null} — never a guessed path.</p>
     */
    public static String resolveBslModulePath(String uriToProblem)
    {
        if (uriToProblem == null || uriToProblem.isEmpty())
        {
            return null;
        }
        try
        {
            URI uri = URI.createURI(uriToProblem).trimFragment();
            // Only BSL module problems carry a path read_module_source/set_breakpoint can use.
            if (!"bsl".equalsIgnoreCase(uri.fileExtension())) //$NON-NLS-1$
            {
                return null;
            }
            // platform:/resource/<Project>/src/<modulePath>.bsl -> <Project>/src/<modulePath>.bsl
            String platformString = uri.isPlatformResource() ? uri.toPlatformString(true) : null;
            if (platformString == null || platformString.isEmpty())
            {
                return null;
            }
            // extractModulePath returns the part after "/src/"; require the segment to be
            // present so we never hand back a project-relative or unrelated path.
            String marker = "/" + BslModuleUtils.SOURCE_FOLDER + "/"; //$NON-NLS-1$ //$NON-NLS-2$
            if (!platformString.contains(marker))
            {
                return null;
            }
            String modulePath = BslModuleUtils.extractModulePath(platformString);
            return modulePath != null && !modulePath.isEmpty() ? modulePath : null;
        }
        catch (Exception e)
        {
            // A malformed URI is not actionable as a locator; fall back to no location.
            return null;
        }
    }

    /**
     * The BSL module path of a marker, read from its extra info exactly as
     * {@link #populateModuleLocation(Marker, Row)} does, or {@code null} for a metadata-only marker.
     */
    private static String moduleLocationOf(Marker marker)
    {
        IExtraInfoMap extraInfo = marker.getExtraInfo();
        return extraInfo != null ? resolveBslModulePath(extraInfo.get(StandardExtraInfo.TEXT_URI_TO_PROBLEM))
            : null;
    }

    /**
     * Normalises a caller's module path to the shape of the {@code Module path} column: trimmed,
     * {@code \} turned into {@code /}, a leading {@code /} and a leading {@code src/} (any case)
     * dropped. The case of the programmatic names is kept; matching is case-insensitive through
     * {@link #modulePathKeys(Collection)}. No other rewriting - in particular no ё/е fallback: a module
     * path names a file, and a file name is matched as written.
     *
     * @param modulePath the caller's path, may be {@code null}
     * @return the normalised path, or {@code null} when nothing is left
     */
    public static String normalizeModulePath(String modulePath)
    {
        if (modulePath == null)
        {
            return null;
        }
        String path = modulePath.trim().replace('\\', '/');
        while (path.startsWith("/")) //$NON-NLS-1$
        {
            path = path.substring(1);
        }
        String sourcePrefix = BslModuleUtils.SOURCE_FOLDER + "/"; //$NON-NLS-1$
        if (path.regionMatches(true, 0, sourcePrefix, 0, sourcePrefix.length()))
        {
            path = path.substring(sourcePrefix.length());
        }
        return path.isEmpty() ? null : path;
    }

    /**
     * The match keys of the requested module paths: each normalised ({@link #normalizeModulePath})
     * and lower-cased with {@link Locale#ROOT}; blank entries are dropped.
     *
     * @param modulePaths the requested paths, may be {@code null}
     * @return the keys in request order (never {@code null}; empty means "no filter")
     */
    public static Set<String> modulePathKeys(Collection<String> modulePaths)
    {
        Set<String> keys = new LinkedHashSet<>();
        if (modulePaths != null)
        {
            for (String path : modulePaths)
            {
                String normalized = normalizeModulePath(path);
                if (normalized != null)
                {
                    keys.add(normalized.toLowerCase(Locale.ROOT));
                }
            }
        }
        return keys;
    }

    /**
     * Whether a row's module path passes the {@code modulePaths} filter. Empty keys keep everything;
     * with keys, a row without a module never matches.
     *
     * @param modulePath the row's module path, may be {@code null}
     * @param modulePathKeys the keys from {@link #modulePathKeys(Collection)}
     * @return {@code true} when the row is kept
     */
    public static boolean modulePathMatches(String modulePath, Set<String> modulePathKeys)
    {
        if (modulePathKeys == null || modulePathKeys.isEmpty())
        {
            return true;
        }
        String normalized = normalizeModulePath(modulePath);
        return normalized != null && modulePathKeys.contains(normalized.toLowerCase(Locale.ROOT));
    }

    /**
     * Groups all markers by their owning project in a single pass, honoring an optional
     * {@code projectName} filter. {@link Marker#getProject()} does not touch
     * {@code resolvedDataCache}, so this is safe outside a BM transaction. Grouping once avoids
     * re-streaming all markers per project (previously O(markers x projects)). Marker
     * presentation must still be resolved inside a BM read transaction bound to a single
     * project's model, so the subsequent processing stays project by project. A marker whose
     * project is gone ({@code !exists()}) or unknown is skipped.
     *
     * @param markerManager the marker manager supplying the markers
     * @param projectName the project name filter, may be {@code null}/empty for all projects
     * @return markers grouped by project, in encounter order
     */
    public static Map<IProject, List<Marker>> groupMarkersByProject(IMarkerManager markerManager,
        String projectName)
    {
        Map<IProject, List<Marker>> markersByProject = new LinkedHashMap<>();
        markerManager.markers().forEach(marker -> {
            IProject markerProject = marker.getProject();
            if (markerProject == null || !markerProject.exists())
            {
                return;
            }
            if (projectName != null && !projectName.isEmpty()
                && !projectName.equals(markerProject.getName()))
            {
                return;
            }
            markersByProject.computeIfAbsent(markerProject, k -> new ArrayList<>()).add(marker);
        });
        return markersByProject;
    }

    /**
     * The requested module paths that name no module file in {@code project}, resolved with the shared
     * {@link BslModuleUtils#resolveModuleFile(IProject, String)}. A path whose lookup cannot be decided
     * (an ambiguous case-insensitive match, an unreadable resource tree) is NOT reported: only a proven
     * miss is.
     *
     * @param project the project the paths are about
     * @param modulePaths the normalised requested paths ({@link #normalizeModulePath})
     * @return the paths with no module file, in request order (never {@code null})
     */
    public static List<String> missingModulePaths(IProject project, List<String> modulePaths)
    {
        List<String> missing = new ArrayList<>();
        if (project == null || modulePaths == null)
        {
            return missing;
        }
        for (String path : modulePaths)
        {
            try
            {
                IFile file = BslModuleUtils.resolveModuleFile(project, path);
                if (file == null || !file.exists())
                {
                    missing.add(path);
                }
            }
            catch (IllegalArgumentException | IllegalStateException e)
            {
                // Ambiguous or unreadable: not proven missing, so say nothing.
            }
        }
        return missing;
    }

    /**
     * Appends the note that some requested module paths name no module in the project, so they matched
     * nothing - without it an unknown path reads exactly like a clean module. Nothing is appended for an
     * empty list.
     *
     * @param md the Markdown builder
     * @param projectName the project the paths were looked up in
     * @param missing the paths with no module file
     */
    public static void appendModulePathsNotFoundWarning(StringBuilder md, String projectName, List<String> missing)
    {
        if (missing == null || missing.isEmpty())
        {
            return;
        }
        md.append("\n> ⚠️ modulePaths not found in ").append(projectName).append(": ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(String.join(", ", missing)) //$NON-NLS-1$
            .append(" - no such module file, so they matched nothing. Copy the path from the Module path ") //$NON-NLS-1$
            .append("column of get_project_errors, or list modules with list_modules."); //$NON-NLS-1$
    }
}
