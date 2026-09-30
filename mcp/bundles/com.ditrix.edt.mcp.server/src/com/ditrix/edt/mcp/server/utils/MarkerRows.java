/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
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
        /** Short UID like "SU23". */
        public String checkCode;
        /** Symbolic ID like "bsl-legacy-check-expression-type", or {@code null}. */
        public String checkId;
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
        // Severity filter
        if (severityFilter != null && marker.getSeverity() != severityFilter)
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
        error.checkCode = shortUid;
        error.checkId = symbolicCheckId;
        error.hasDocumentation = symbolicCheckId != null && !symbolicCheckId.isEmpty()
            && CheckDescriptionLoader.has(symbolicCheckId);
        error.hasQuickFix = hasQuickFix;
        error.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$

        // Structural locator: for a marker that points at a BSL text position the
        // module path + 1-based line live in the marker's own extraInfo map (no model
        // read), so they are safe to read regardless of the transaction boundary. Both
        // stay null for markers that do not resolve to a BSL module location.
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
     * Populates the structural BSL locator ({@code modulePath} + {@code line}) on the row when the
     * marker points at a position inside a BSL module, leaving both {@code null} otherwise.
     *
     * <p>The locator is read straight from the marker's {@link Marker#getExtraInfo()} map —
     * {@link StandardExtraInfo#TEXT_URI_TO_PROBLEM} (the EMF platform URI of the problem) and
     * {@link StandardExtraInfo#TEXT_LINE} (1-based line). EDT fills these for text/Xtext
     * issues (e.g. BSL editor markers; see {@code BmAwareResourceValidatorListener}). Because
     * the values are plain strings already stored on the marker, reading them touches NO
     * model state and is therefore safe with respect to the BM read-transaction boundary.</p>
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
}
