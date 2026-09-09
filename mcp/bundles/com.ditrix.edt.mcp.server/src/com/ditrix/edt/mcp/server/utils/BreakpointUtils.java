/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Helpers for resolving 1C BSL modules to {@link IFile}s, autodetecting
 * EDT module-path vs absolute paths, and creating/removing line breakpoints
 * via the Eclipse breakpoint framework.
 *
 * <p>1C breakpoints are normally created by the EDT-specific
 * {@code com._1c.g5.v8.dt.debug.core} bundle. Since we cannot reference its
 * internal classes at compile time without taking on a heavy bundle dependency,
 * this util takes a layered approach:
 * <ol>
 *   <li>Try to instantiate the EDT BSL line breakpoint via reflection.</li>
 *   <li>Fallback: create a generic {@link IMarker} of the EDT marker type and
 *       let the EDT breakpoint manager pick it up — this is the standard
 *       Eclipse pattern for breakpoint extension contributors.</li>
 *   <li>Last-resort fallback: create a marker of type
 *       {@code org.eclipse.debug.core.lineBreakpointMarker}, which gives a
 *       degraded experience but never fails compilation.</li>
 * </ol>
 *
 * <p>The actual class/marker names are best-effort — if 1C ships them under
 * different ids on a particular EDT version, the call will fail at runtime
 * and the tool will surface a clear error message instead of crashing.
 */
public final class BreakpointUtils
{
    /**
     * Bundle that owns the BSL breakpoint class. We load classes via
     * {@link Bundle#loadClass(String)} to bypass OSGi {@code Import-Package}
     * restrictions on the {@code internal.*} package.
     */
    private static final String BSL_DEBUG_CORE_BUNDLE = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$

    /** Exported EDT service interface used to create workspace-wide exception breakpoints. */
    public static final String BSL_BREAKPOINT_FACTORY_SERVICE =
        "com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory"; //$NON-NLS-1$

    /** EDT marker type for a workspace-wide BSL exception breakpoint. */
    public static final String BSL_EXCEPTION_BREAKPOINT_MARKER =
        "com._1c.g5.v8.dt.debug.core.bslExceptionBreakpointMarker"; //$NON-NLS-1$

    /** EDT marker attributes used when an older breakpoint implementation lacks public setters. */
    public static final String CONDITION_ATTRIBUTE =
        "com._1c.g5.v8.dt.debug.core.condition"; //$NON-NLS-1$
    public static final String HIT_COUNT_ATTRIBUTE =
        "com._1c.g5.v8.dt.debug.core.hitCount"; //$NON-NLS-1$
    public static final String HIT_CONDITION_ATTRIBUTE =
        "com._1c.g5.v8.dt.debug.core.hitCondition"; //$NON-NLS-1$

    /**
     * Exception-breakpoint marker attributes, spelled exactly as the platform declares them in
     * {@code IBslExceptionBreakpoint} ({@code ALL_EXCEPTIONS} / {@code EXCEPTION_MESSAGE}). The
     * flag's id deliberately does NOT mirror its setter {@code setCatchAllExceptions}.
     */
    public static final String ALL_EXCEPTIONS_ATTRIBUTE =
        "com._1c.g5.v8.dt.debug.core.allExceptions"; //$NON-NLS-1$
    public static final String EXCEPTION_MESSAGE_ATTRIBUTE =
        "com._1c.g5.v8.dt.debug.core.exceptionMessage"; //$NON-NLS-1$

    private static final String DEFAULT_HIT_CONDITION = "EQUALS"; //$NON-NLS-1$

    private static final List<String> VALID_HIT_CONDITIONS = Collections.unmodifiableList(
        java.util.Arrays.asList(DEFAULT_HIT_CONDITION, "EQUAL_OR_LESS", //$NON-NLS-1$
            "EQUAL_OR_HIGHER", "MULTIPLIER")); //$NON-NLS-1$ //$NON-NLS-2$

    /** Candidate fully-qualified class names for the BSL line breakpoint. */
    private static final String[] BSL_BREAKPOINT_CLASSES = {
        // Real class as of EDT 2025.2 / 2026.1 — found in com._1c.g5.v8.dt.debug.core/plugin.xml
        "com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslLineBreakpoint", //$NON-NLS-1$
        // Historical fallbacks
        "com._1c.g5.v8.dt.debug.core.model.BslLineBreakpoint", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.bsl.model.BslLineBreakpoint", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.core.BslLineBreakpoint" //$NON-NLS-1$
    };

    /** Candidate marker types EDT registers via {@code org.eclipse.debug.core.breakpoints}. */
    private static final String[] BSL_MARKER_TYPES = {
        "com._1c.g5.v8.dt.debug.core.bslLineBreakpointMarker", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.bslLineBreakpointMarker", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug.bsl.bslLineBreakpointMarker" //$NON-NLS-1$
    };

    /** Eclipse-generic line breakpoint marker — minimal fallback.
     *  Value matches {@code IBreakpoint.LINE_BREAKPOINT_MARKER}. */
    private static final String GENERIC_LINE_MARKER = "org.eclipse.debug.core.lineBreakpoint"; //$NON-NLS-1$

    /** BSL debug model identifier (best effort — verified at runtime). */
    private static final String BSL_MODEL_ID = "com._1c.g5.v8.dt.debug"; //$NON-NLS-1$

    private BreakpointUtils()
    {
    }

    /**
     * Resolves a "module" parameter — either an EDT module-relative path or an
     * absolute filesystem path — to a workspace {@link IFile}.
     *
     * @param projectName project name (used when path is module-relative)
     * @param module      either {@code "CommonModules/Foo/Module.bsl"} or
     *                    {@code "C:/full/path/to/Module.bsl"}
     * @return resolved IFile (may not exist; caller should check)
     */
    public static IFile resolveModuleFile(String projectName, String module)
    {
        if (module == null || module.isEmpty())
        {
            return null;
        }
        // Single shared module resolver (BslModuleUtils.resolveModuleFile) handles
        // BOTH an absolute filesystem path and a src/-relative path. For the
        // absolute case the project is not needed (resolution is by location); // NOSONAR explanatory comment, not commented-out code
        // for the src/-relative case resolve the project and pass it through.
        IProject project = null;
        if (!BslModuleUtils.looksLikeAbsolutePath(module))
        {
            if (projectName == null || projectName.isEmpty())
            {
                return null;
            }
            project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists())
            {
                return null;
            }
        }
        return BslModuleUtils.resolveModuleFile(project, module);
    }

    /**
     * Heuristic: a string is treated as an absolute path if it starts with a slash,
     * a backslash, or matches a Windows drive prefix like {@code C:}. Delegates to
     * the single implementation in {@link BslModuleUtils#looksLikeAbsolutePath(String)}.
     */
    public static boolean looksLikeAbsolutePath(String s)
    {
        return BslModuleUtils.looksLikeAbsolutePath(s);
    }

    /**
     * Creates a line breakpoint on the given file/line. Tries EDT-specific class
     * first, then EDT marker types, finally a generic Eclipse marker.
     *
     * @return the created breakpoint
     * @throws Exception if every strategy fails
     */
    public static IBreakpoint createLineBreakpoint(IFile file, int lineNumber) throws Exception
    {
        if (file == null)
        {
            throw new IllegalArgumentException("file is null"); //$NON-NLS-1$
        }
        if (lineNumber < 1)
        {
            throw new IllegalArgumentException("lineNumber must be >= 1"); //$NON-NLS-1$
        }

        IBreakpointManager bpManager = DebugPlugin.getDefault().getBreakpointManager();

        // Strategy 1: load EDT-specific BslLineBreakpoint via the owning bundle's class loader.
        IBreakpoint edtBreakpoint = tryCreateEdtBreakpoint(file, lineNumber, bpManager);
        if (edtBreakpoint != null)
        {
            return edtBreakpoint;
        }

        // Strategy 2: create marker of EDT type and wrap as generic ILineBreakpoint via DebugPlugin
        IBreakpoint markerBreakpoint = tryCreateMarkerBreakpoint(file, lineNumber, bpManager);
        if (markerBreakpoint != null)
        {
            return markerBreakpoint;
        }

        // Strategy 3: generic Eclipse line marker (least useful, but compiles & runs)
        IMarker marker = file.createMarker(GENERIC_LINE_MARKER);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(IMarker.LINE_NUMBER, Integer.valueOf(lineNumber));
        attrs.put(IBreakpoint.ENABLED, Boolean.TRUE);
        marker.setAttributes(attrs);
        MarkerOnlyBreakpoint fallback = new MarkerOnlyBreakpoint(marker);
        bpManager.addBreakpoint(fallback);
        fallback.registered = true;
        return fallback;
    }

    /**
     * Finds the first registered line breakpoint at the exact resource and 1-based line.
     * Reusing it is important when a caller changes a condition: EDT expects one breakpoint
     * per source position, and creating another would leave two independently firing markers.
     *
     * @return the existing breakpoint, or {@code null} when the position is unused
     */
    public static IBreakpoint findLineBreakpoint(IFile file, int lineNumber) throws Exception
    {
        List<IBreakpoint> found = findLineBreakpoints(file, lineNumber);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Every BSL line breakpoint registered at the exact resource and 1-based line.
     * <p>
     * More than one can exist on an upgraded workspace: an earlier build of {@code set_breakpoint}
     * created a new marker on every call instead of reusing the one already there. Configuring
     * only the first would leave a legacy duplicate firing unconditionally and quietly defeat the
     * condition the caller just asked for, so the caller configures them ALL.
     *
     * @param file the module file
     * @param lineNumber the 1-based line
     * @return the matching BSL breakpoints, in registration order; never {@code null}
     * @throws Exception when a breakpoint cannot be inspected
     */
    public static List<IBreakpoint> findLineBreakpoints(IFile file, int lineNumber) throws Exception
    {
        List<IBreakpoint> found = new ArrayList<>();
        IBreakpointManager bpManager = DebugPlugin.getDefault().getBreakpointManager();
        for (IBreakpoint bp : bpManager.getBreakpoints())
        {
            if (!(bp instanceof ILineBreakpoint))
            {
                continue;
            }
            // Only a BSL breakpoint may be reused. Another Eclipse debug model can hold a line
            // breakpoint on the very same file and line; writing BSL condition attributes into it
            // would hijack a foreign breakpoint and leave the BSL session with none at that line,
            // while the answer said "updated".
            if (!isBslLineBreakpoint(bp))
            {
                continue;
            }
            IMarker marker = bp.getMarker();
            if (marker != null && file.equals(marker.getResource())
                && ((ILineBreakpoint)bp).getLineNumber() == lineNumber)
            {
                found.add(bp);
            }
        }
        return found;
    }

    /**
     * Reports whether a line breakpoint belongs to EDT's BSL debug model (or to this plugin's own
     * degraded stand-in, which reports the same model id).
     *
     * @param breakpoint the candidate breakpoint
     * @return {@code true} when the breakpoint is a BSL one
     */
    public static boolean isBslLineBreakpoint(IBreakpoint breakpoint)
    {
        if (!(breakpoint instanceof ILineBreakpoint))
        {
            return false;
        }
        try
        {
            return BSL_MODEL_ID.equals(breakpoint.getModelIdentifier());
        }
        catch (RuntimeException e) // NOSONAR a breakpoint we cannot identify is not ours
        {
            return false;
        }
    }

    /** Returns the four literals accepted by EDT's hit-condition enum. */
    public static String[] getValidHitConditions()
    {
        return VALID_HIT_CONDITIONS.toArray(new String[0]);
    }

    /** Headless-testable validation for EDT's exact, case-sensitive hit-condition literals. */
    public static boolean isValidHitCondition(String value)
    {
        return VALID_HIT_CONDITIONS.contains(value);
    }

    /**
     * Applies line-breakpoint options through the native breakpoint object's public interface
     * methods, resolved on the instance so this bundle does not import EDT's breakpoint package.
     * A missing individual method falls back to the corresponding verified marker attribute.
     * Marker-only degraded breakpoints deliberately accept none of these options.
     *
     * @param breakpoint the native or degraded breakpoint
     * @param condition condition text; {@code null} and blank both clear it
     * @param hitCount positive count, or a non-positive value to clear it
     * @param hitCondition one of {@link #getValidHitConditions()}; normally {@code EQUALS}
     * @return details needed to report whether marker-attribute fallback was used
     */
    public static LineBreakpointConfiguration configureLineBreakpoint(IBreakpoint breakpoint,
        String condition, int hitCount, String hitCondition) throws Exception
    {
        if (breakpoint instanceof MarkerOnlyBreakpoint)
        {
            return LineBreakpointConfiguration.notApplied();
        }

        IMarker marker = breakpoint.getMarker();
        List<String> markerFallbacks = new ArrayList<>();
        setStringOption(breakpoint, marker, "setCondition", CONDITION_ATTRIBUTE, //$NON-NLS-1$
            condition == null ? "" : condition, "condition", markerFallbacks); //$NON-NLS-1$ //$NON-NLS-2$
        setIntegerOption(breakpoint, marker, "setHitCount", HIT_COUNT_ATTRIBUTE, //$NON-NLS-1$
            hitCount > 0 ? hitCount : -1, "hitCount", markerFallbacks); //$NON-NLS-1$
        setEnumOption(breakpoint, marker, "setHitCondition", HIT_CONDITION_ATTRIBUTE, //$NON-NLS-1$
            hitCondition == null ? DEFAULT_HIT_CONDITION : hitCondition,
            "hitCondition", markerFallbacks); //$NON-NLS-1$
        return LineBreakpointConfiguration.appliedWith(markerFallbacks);
    }

    /**
     * Reads the line options actually held by the breakpoint, using the native getters first and
     * their verified marker attributes only when a getter is absent. Unset values are omitted.
     */
    public static Map<String, Object> readLineBreakpointConfiguration(IBreakpoint breakpoint,
        IMarker marker) throws Exception
    {
        Map<String, Object> configured = new LinkedHashMap<>();
        Object condition = getOption(breakpoint, marker, "getCondition", CONDITION_ATTRIBUTE); //$NON-NLS-1$
        if (condition != null && !condition.toString().isEmpty())
        {
            configured.put("condition", condition.toString()); //$NON-NLS-1$
        }

        Object rawHitCount = getOption(breakpoint, marker, "getHitCount", HIT_COUNT_ATTRIBUTE); //$NON-NLS-1$
        int hitCount = rawHitCount instanceof Number ? ((Number)rawHitCount).intValue() : -1;
        if (hitCount > 0)
        {
            configured.put("hitCount", hitCount); //$NON-NLS-1$
            Object rawHitCondition = getOption(breakpoint, marker, "getHitCondition", //$NON-NLS-1$
                HIT_CONDITION_ATTRIBUTE);
            String hitCondition = enumName(rawHitCondition);
            configured.put("hitCondition", hitCondition == null ? DEFAULT_HIT_CONDITION : hitCondition); //$NON-NLS-1$
        }
        return configured;
    }

    /**
     * Creates, updates, enables, or disables the single workspace-wide BSL exception breakpoint.
     * A {@code null} message preserves an existing filter, an empty message explicitly selects
     * catch-all, and non-empty text replaces the filter; creating with no message is catch-all.
     * The factory is looked up by its interface NAME in the OSGi service registry and is
     * released after creation; no EDT debug-breakpoint type is linked at compile time.
     */
    public static ExceptionBreakpointChange setExceptionBreakpoint(boolean enabled,
        String exceptionMessage) throws Exception
    {
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin == null)
        {
            throw new IllegalStateException("DebugPlugin is unavailable; start this tool inside a running EDT workbench"); //$NON-NLS-1$
        }
        IBreakpointManager manager = debugPlugin.getBreakpointManager();
        List<IBreakpoint> existing = findExceptionBreakpoints(manager);
        if (!enabled)
        {
            TouchedBreakpoints begun = new TouchedBreakpoints();
            for (IBreakpoint breakpoint : existing)
            {
                try
                {
                    // Inside the guard, because getMarker() itself can throw on a stale marker
                    // - isExceptionBreakpoint admits such a breakpoint through its interface
                    // fallback - and outside it that throw would escape carrying nothing.
                    begun.add(breakpoint);
                    breakpoint.setEnabled(false);
                }
                catch (Exception failure)
                {
                    throw new PartialExceptionUpdateException(begun, failure);
                }
            }
            return ExceptionBreakpointChange.disabled(existing.size(), markerIdsOf(existing));
        }

        boolean updateFilter = exceptionMessage != null;
        boolean catchAll = exceptionMessage == null || exceptionMessage.isEmpty();
        String configuredMessage = catchAll ? null : exceptionMessage;
        if (!existing.isEmpty())
        {
            TouchedBreakpoints begun = new TouchedBreakpoints();
            for (IBreakpoint breakpoint : existing)
            {
                try
                {
                    // Recorded BEFORE the mutation and INSIDE the guard: a member that throws
                    // halfway may already carry the new filter, so the fully done ones alone
                    // would hide the very entry the caller has to look at - and getMarker()
                    // itself can throw on a stale marker, which outside the guard would escape
                    // carrying nothing.
                    begun.add(breakpoint);
                    if (updateFilter)
                    {
                        configureExceptionBreakpoint(breakpoint, catchAll, configuredMessage);
                    }
                    breakpoint.setEnabled(true);
                }
                catch (Exception failure)
                {
                    throw new PartialExceptionUpdateException(begun, failure);
                }
            }
            // Every one of them was configured, and the caller is told so: a workspace that
            // holds several (legacy state) is not served by an answer naming one id, since
            // removing that id would leave the others breaking on error.
            return describe("updated", existing.get(0), existing); //$NON-NLS-1$
        }

        IBreakpoint created = createExceptionBreakpointFromService(configuredMessage);
        try
        {
            configureExceptionBreakpoint(created, catchAll, configuredMessage);
            created.setEnabled(true);
            manager.addBreakpoint(created);
            return describe("created", created, List.of(created)); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            try
            {
                created.delete();
            }
            catch (Exception cleanupFailure)
            {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    /** True for EDT's workspace-root BSL exception breakpoint marker/interface. */
    public static boolean isExceptionBreakpoint(IBreakpoint breakpoint)
    {
        if (breakpoint == null)
        {
            return false;
        }
        try
        {
            IMarker marker = breakpoint.getMarker();
            if (marker != null && BSL_EXCEPTION_BREAKPOINT_MARKER.equals(marker.getType()))
            {
                return true;
            }
        }
        catch (Exception e)
        {
            // Fall through to the interface-name check; a stale marker may be inaccessible.
        }
        return implementsInterfaceNamed(breakpoint.getClass(),
            "com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslExceptionBreakpoint"); //$NON-NLS-1$
    }

    /**
     * Describes an exception breakpoint from its MARKER, after the write, never from what the
     * caller asked for.
     * <p>
     * The reason is a defect this code had: the answer echoed the request, so a setter whose
     * effect never reached the marker was still reported as applied - "the filter is cleared"
     * while the filter was still there. An answer computed from the request cannot fail; an
     * answer read back can, and that is the point.
     *
     * @param action what happened, {@code created} or {@code updated}
     * @param breakpoint the registered exception breakpoint
     * @return the change carrying the state the marker actually holds
     * @throws Exception when the marker cannot be read
     */
    private static ExceptionBreakpointChange describe(String action, IBreakpoint breakpoint,
        List<IBreakpoint> configured) throws Exception
    {
        List<Long> ids = markerIdsOf(configured);
        FilterAgreement agreement = filterAgreement(configured);
        // The same read the agreement check just made, and it may fail the same way. Every
        // breakpoint has already been configured and enabled by now, so letting it throw would
        // report a failure for a mutation that happened - and would drop the ids that undo it.
        Map<String, Object> state;
        try
        {
            state = readExceptionBreakpointConfiguration(breakpoint.getMarker());
        }
        catch (Exception unreadable) // NOSONAR: see above
        {
            state = Map.of();
        }
        Object message = state.get("exceptionMessage"); //$NON-NLS-1$
        String storedMessage = message == null || message.toString().isEmpty()
            ? null
            : message.toString();
        return ExceptionBreakpointChange.enabled(action, breakpoint,
            Boolean.TRUE.equals(state.get("catchAllExceptions")), storedMessage, ids, //$NON-NLS-1$
            configured.size(), agreement);
    }

    /**
     * Marker ids of the given breakpoints, in order. A breakpoint with no marker contributes
     * nothing - it has no id to give - which is why the COUNT of configured breakpoints travels
     * beside the list: a caller comparing the two can see when the list is short instead of
     * believing it complete.
     *
     * @param breakpoints the configured breakpoints
     * @return their marker ids
     */
    private static List<Long> markerIdsOf(List<IBreakpoint> breakpoints)
    {
        List<Long> ids = new ArrayList<>(breakpoints.size());
        for (IBreakpoint each : breakpoints)
        {
            IMarker marker = each.getMarker();
            if (marker != null)
            {
                ids.add(Long.valueOf(marker.getId()));
            }
        }
        return ids;
    }

    /** What a read-back could establish about the filters of the affected breakpoints. */
    public enum FilterAgreement
    {
        /** Every affected breakpoint carries the same filter. */
        SAME,
        /** They carry different filters - the set has no single filter to report. */
        DIFFERENT,
        /** At least one could not be read back, so nothing about the set is established. */
        UNVERIFIED
    }

    /**
     * A mutation of several exception breakpoints that failed after touching some of them.
     * <p>
     * Carries their marker ids, because nothing withdraws them: the members the loop reached
     * keep whatever the setters managed to write, and an error that named none of them would
     * leave a caller without {@code list_breakpoints} unable to find what moved.
     * </p>
     */
    public static class PartialExceptionUpdateException
        extends Exception
    {
        private static final long serialVersionUID = 1L;

        private final List<Long> changed;

        private final int begun;

        /**
         * @param touched what the loop had begun changing when it failed
         * @param cause the failure that stopped the loop
         */
        PartialExceptionUpdateException(TouchedBreakpoints touched, Throwable cause)
        {
            super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
                cause);
            this.changed = List.copyOf(touched.ids);
            this.begun = touched.begun;
        }

        /**
         * @return the marker ids this call had begun changing
         */
        public List<Long> getChangedIds()
        {
            return changed;
        }

        /**
         * @return how many breakpoints this call had begun changing, named or not
         */
        public int getBegunCount()
        {
            return begun;
        }

        /**
         * Describes what was touched, counting the members that have no marker to name them by.
         * <p>
         * A list of ids alone would understate the damage exactly where it matters: a markerless
         * member was changed like the others and cannot be reached by any returned id, so the
         * caller has to be told it exists rather than left to infer it from a shorter list.
         * </p>
         *
         * @return the phrase, always naming a number the caller can act on
         */
        public String describeTouched()
        {
            int unnamed = begun - changed.size();
            if (changed.isEmpty())
            {
                return begun + " breakpoint(s), none of which has a marker to name it by"; //$NON-NLS-1$
            }
            StringBuilder ids = new StringBuilder("id(s) "); //$NON-NLS-1$
            for (int i = 0; i < changed.size(); i++)
            {
                ids.append(i == 0 ? "" : ", ").append(changed.get(i)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return unnamed > 0
                ? ids.append(" and ").append(unnamed) //$NON-NLS-1$
                    .append(" more with no marker to name it by").toString() //$NON-NLS-1$
                : ids.toString();
        }
    }

    /**
     * What a mutation loop has begun changing: the ids it can name, and how many members it
     * reached in total - which is larger whenever one of them has no marker.
     */
    static final class TouchedBreakpoints
    {
        private final List<Long> ids = new ArrayList<>();

        private int begun;

        /**
         * Records a breakpoint about to be mutated. Counted whether or not it can be named:
         * a markerless member is changed like the rest and reachable by no returned id.
         *
         * @param breakpoint the breakpoint about to change
         */
        void add(IBreakpoint breakpoint)
        {
            begun++;
            IMarker marker = breakpoint.getMarker();
            if (marker != null)
            {
                ids.add(Long.valueOf(marker.getId()));
            }
        }
    }

    /**
     * Whether every configured breakpoint carries the same filter.
     * <p>
     * Legacy duplicates may hold DIFFERENT filters, and an enable call that omits
     * {@code exceptionMessage} preserves each of them - so describing the set by its first
     * member would report "errors are filtered" while another one catches everything. When they
     * disagree the caller is told that instead of being given one member's state as the answer.
     * </p>
     *
     * @param breakpoints the configured breakpoints
     * @return SAME, DIFFERENT, or UNVERIFIED when any member could not be read; UNVERIFIED
     *         outranks DIFFERENT, because "nothing was established" is the weaker claim the
     *         caller is owed when part of the set was never read
     */
    static FilterAgreement filterAgreement(List<IBreakpoint> breakpoints)
    {
        Map<String, Object> first = null;
        boolean differ = false;
        for (IBreakpoint each : breakpoints)
        {
            IMarker marker = each.getMarker();
            if (marker == null)
            {
                return FilterAgreement.UNVERIFIED;
            }
            Map<String, Object> state;
            try
            {
                state = readExceptionBreakpointConfiguration(marker);
            }
            catch (Exception unreadable) // NOSONAR: see below
            {
                // Deliberately swallowed, and ONLY here: this runs after every breakpoint has
                // already been configured and enabled, so letting a stale marker throw would
                // report a failure for a mutation that happened - and would drop the ids the
                // caller needs to undo it. An unreadable entry simply means the set cannot be
                // described as uniform - and the caller is told it is UNVERIFIED rather than
                // being left to read "the filters disagree" into a read that never happened.
                return FilterAgreement.UNVERIFIED;
            }
            if (first == null)
            {
                first = state;
            }
            else if (!first.equals(state))
            {
                // Remembered, not returned: a later member may still be unreadable, and
                // "nothing was established" outranks "they differ" - the caller is owed the
                // weaker claim when part of the set was never read.
                differ = true;
            }
        }
        return differ ? FilterAgreement.DIFFERENT : FilterAgreement.SAME;
    }
    /** Reads the configured exception filter for list_breakpoints without linking its interface. */
    public static Map<String, Object> readExceptionBreakpointConfiguration(IMarker marker)
        throws Exception
    {
        Map<String, Object> configured = new LinkedHashMap<>();
        // The verified public exception interface exposes setters, not a getter contract we can
        // safely link or guess. Read the backing marker EDT actually persists instead, by the
        // EXACT attribute ids the platform declares as IBslExceptionBreakpoint.ALL_EXCEPTIONS and
        // .EXCEPTION_MESSAGE. Two traps this spelling avoids: the flag's id is NOT the setter's
        // name (setCatchAllExceptions writes '...allExceptions'), and a suffix match would also
        // answer with a foreign attribute such as 'vendor.saved.exceptionMessage', picking one or
        // the other by map iteration order.
        Object catchAll = markerAttribute(marker, ALL_EXCEPTIONS_ATTRIBUTE);
        Object message = markerAttribute(marker, EXCEPTION_MESSAGE_ATTRIBUTE);
        boolean catchesAll = catchAll instanceof Boolean
            ? ((Boolean)catchAll).booleanValue()
            : message == null || message.toString().isEmpty();
        configured.put("catchAllExceptions", catchesAll); //$NON-NLS-1$
        // An absent filter is left OUT rather than rendered as an empty string: the same rule the
        // line-breakpoint reader follows, and the difference a caller acts on - "" would read as
        // "there is a filter, and it matches everything".
        if (message != null && !message.toString().isEmpty())
        {
            configured.put("exceptionMessage", message.toString()); //$NON-NLS-1$
        }
        return configured;
    }

    /**
     * Strategy 1: loads the EDT-specific {@code BslLineBreakpoint} via the owning bundle's class loader
     * and instantiates it. The class lives in an {@code internal.*} package that OSGi will not export
     * through Import-Package, so a plain {@code Class.forName()} from this bundle would fail with
     * {@code ClassNotFoundException} — but {@code Bundle.loadClass()} bypasses the export restriction and
     * returns the class directly. Registers the created breakpoint with the manager (EDT's constructor
     * creates the marker but does not register). Behaviour-identical to the former inline Strategy 1.
     *
     * @param file the resource the breakpoint is on
     * @param lineNumber the 1-based line number
     * @param bpManager the breakpoint manager to register with
     * @return the created EDT breakpoint, or {@code null} when the bundle/class is unavailable
     */
    private static IBreakpoint tryCreateEdtBreakpoint(IFile file, int lineNumber,
            IBreakpointManager bpManager)
    {
        Bundle debugCoreBundle = Platform.getBundle(BSL_DEBUG_CORE_BUNDLE);
        if (debugCoreBundle == null)
        {
            Activator.logError("Bundle " + BSL_DEBUG_CORE_BUNDLE //$NON-NLS-1$
                    + " not found — falling back to marker", new IllegalStateException("bundle missing")); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        for (String className : BSL_BREAKPOINT_CLASSES)
        {
            IBreakpoint bp = tryCreateEdtBreakpointFor(debugCoreBundle, className, file, lineNumber, bpManager);
            if (bp != null)
            {
                return bp;
            }
        }
        return null;
    }

    /**
     * Attempts a single candidate class: loads it from {@code debugCoreBundle}, finds an
     * {@code (IResource/IFile, int)} constructor, instantiates the breakpoint and registers it with the
     * manager (EDT's constructor creates the marker but does not register). Returns the created
     * breakpoint, or {@code null} when the class/constructor is unavailable or instantiation fails — so
     * the caller falls through to the next candidate. {@link ClassNotFoundException} is silent (try next
     * name); any other failure is logged, exactly as in the former inline loop body.
     *
     * @param debugCoreBundle the owning bundle whose class loader resolves {@code className}
     * @param className        the candidate fully-qualified breakpoint class name
     * @param file             the resource the breakpoint is on
     * @param lineNumber       the 1-based line number
     * @param bpManager        the breakpoint manager to register with
     * @return the created EDT breakpoint, or {@code null} to try the next candidate
     */
    private static IBreakpoint tryCreateEdtBreakpointFor(Bundle debugCoreBundle, String className,
            IFile file, int lineNumber, IBreakpointManager bpManager)
    {
        try
        {
            Class<?> cls = debugCoreBundle.loadClass(className);
            Constructor<?> ctor = findConstructor(cls);
            if (ctor == null)
            {
                return null;
            }
            Object instance = ctor.newInstance(file, lineNumber);
            if (instance instanceof IBreakpoint)
            {
                IBreakpoint bp = (IBreakpoint) instance;
                // EDT's constructor creates the marker but does not register
                // with the breakpoint manager — do it explicitly.
                bpManager.addBreakpoint(bp);
                return bp;
            }
        }
        catch (ClassNotFoundException cnf)
        {
            // try next class name
        }
        catch (Exception ex)
        {
            Activator.logError("Failed to instantiate " + className, ex); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Strategy 2: creates a marker of an EDT breakpoint type and wraps it as a generic breakpoint via the
     * {@link DebugPlugin}. When EDT is loaded it picks the marker up via its lifecycle listener; otherwise
     * the marker is kept and reported as a degraded {@link MarkerOnlyBreakpoint}. Behaviour-identical to
     * the former inline Strategy 2: returns on the first marker type that does not throw, falling through
     * (returning {@code null}) only when every marker type fails.
     *
     * @param file the resource the breakpoint is on
     * @param lineNumber the 1-based line number
     * @param bpManager the breakpoint manager to register with
     * @return the created/looked-up breakpoint, or {@code null} when every marker type throws
     */
    private static IBreakpoint tryCreateMarkerBreakpoint(IFile file, int lineNumber,
            IBreakpointManager bpManager)
    {
        for (String markerType : BSL_MARKER_TYPES)
        {
            try
            {
                IMarker marker = file.createMarker(markerType);
                Map<String, Object> attrs = new HashMap<>();
                attrs.put(IMarker.LINE_NUMBER, Integer.valueOf(lineNumber));
                attrs.put(IBreakpoint.ENABLED, Boolean.TRUE);
                attrs.put(IBreakpoint.ID, BSL_MODEL_ID);
                marker.setAttributes(attrs);
                // Find the breakpoint that EDT registers for this marker — if EDT is loaded
                // it will pick the marker up via its lifecycle listener.
                IBreakpoint bp = bpManager.getBreakpoint(marker);
                if (bp != null)
                {
                    return bp;
                }
                // No registered breakpoint type — keep the marker but report a degraded result.
                MarkerOnlyBreakpoint fallback = new MarkerOnlyBreakpoint(marker);
                bpManager.addBreakpoint(fallback);
                fallback.registered = true;
                return fallback;
            }
            catch (Exception ex)
            {
                // try next marker type
            }
        }
        return null;
    }

    /**
     * Tries to find an {@code (IResource, int)} constructor (or {@code (IFile, int)}).
     */
    private static Constructor<?> findConstructor(Class<?> cls)
    {
        for (Constructor<?> c : cls.getConstructors())
        {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 2 && params[1] == int.class
                && (IFile.class.isAssignableFrom(params[0]) || IResource.class.isAssignableFrom(params[0])))
            {
                return c;
            }
        }
        return null;
    }

    private static void setStringOption(Object target, IMarker marker, String methodName,
        String attributeName, String value, String fieldName, List<String> markerFallbacks)
        throws Exception
    {
        Method method = findMethod(target.getClass(), methodName, 1);
        if (method != null)
        {
            invoke(method, target, value);
            return;
        }
        setMarkerAttribute(marker, attributeName, value, fieldName, markerFallbacks);
    }

    private static void setIntegerOption(Object target, IMarker marker, String methodName,
        String attributeName, int value, String fieldName, List<String> markerFallbacks)
        throws Exception
    {
        Method method = findMethod(target.getClass(), methodName, 1);
        if (method != null)
        {
            invoke(method, target, Integer.valueOf(value));
            return;
        }
        setMarkerAttribute(marker, attributeName, Integer.valueOf(value), fieldName, markerFallbacks);
    }

    private static void setEnumOption(Object target, IMarker marker, String methodName,
        String attributeName, String value, String fieldName, List<String> markerFallbacks)
        throws Exception
    {
        Method method = findMethod(target.getClass(), methodName, 1);
        if (method != null)
        {
            Class<?> parameterType = method.getParameterTypes()[0];
            Object enumValue = enumConstant(parameterType, value);
            invoke(method, target, enumValue);
            return;
        }
        setMarkerAttribute(marker, attributeName, value, fieldName, markerFallbacks);
    }

    private static void setMarkerAttribute(IMarker marker, String attributeName, Object value,
        String fieldName, List<String> markerFallbacks) throws Exception
    {
        if (marker == null)
        {
            throw new IllegalStateException("Cannot apply " + fieldName //$NON-NLS-1$
                + ": the EDT setter is absent and the breakpoint has no marker"); //$NON-NLS-1$
        }
        marker.setAttribute(attributeName, value);
        markerFallbacks.add(fieldName);
    }

    private static Object getOption(Object target, IMarker marker, String methodName,
        String attributeName) throws Exception
    {
        Method method = findMethod(target.getClass(), methodName, 0);
        if (method != null)
        {
            return invoke(method, target);
        }
        return marker == null ? null : marker.getAttribute(attributeName);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount)
    {
        for (Method method : type.getMethods())
        {
            if (name.equals(method.getName()) && method.getParameterCount() == parameterCount)
            {
                return method;
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception
    {
        try
        {
            return method.invoke(target, arguments);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw e;
        }
    }

    private static Object enumConstant(Class<?> enumType, String value)
    {
        if (!enumType.isEnum())
        {
            throw new IllegalStateException("EDT setHitCondition parameter is not an enum: " //$NON-NLS-1$
                + enumType.getName());
        }
        for (Object constant : enumType.getEnumConstants())
        {
            if (value.equals(((Enum<?>)constant).name()))
            {
                return constant;
            }
        }
        throw new IllegalArgumentException("EDT hit condition enum has no value '" + value + "'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String enumName(Object value)
    {
        if (value instanceof Enum<?>)
        {
            return ((Enum<?>)value).name();
        }
        return value == null ? null : value.toString();
    }

    private static List<IBreakpoint> findExceptionBreakpoints(IBreakpointManager manager)
    {
        List<IBreakpoint> result = new ArrayList<>();
        for (IBreakpoint breakpoint : manager.getBreakpoints())
        {
            if (isExceptionBreakpoint(breakpoint))
            {
                result.add(breakpoint);
            }
        }
        return result;
    }

    private static IBreakpoint createExceptionBreakpointFromService(String exceptionMessage)
        throws Exception
    {
        Bundle owner = FrameworkUtil.getBundle(BreakpointUtils.class);
        BundleContext context = owner == null ? null : owner.getBundleContext();
        ServiceReference<?> reference = context == null
            ? null
            : context.getServiceReference(BSL_BREAKPOINT_FACTORY_SERVICE);
        if (reference == null)
        {
            throw new IllegalStateException(breakpointFactoryUnavailableMessage());
        }
        Object factory = context.getService(reference);
        if (factory == null)
        {
            context.ungetService(reference);
            throw new IllegalStateException(breakpointFactoryUnavailableMessage());
        }
        try
        {
            int parameterCount = exceptionMessage == null ? 0 : 1;
            Method create = findMethod(factory.getClass(), "createExceptionBreakpoint", parameterCount); //$NON-NLS-1$
            if (create == null)
            {
                throw new IllegalStateException("OSGi service '" + BSL_BREAKPOINT_FACTORY_SERVICE //$NON-NLS-1$
                    + "' does not expose createExceptionBreakpoint(" //$NON-NLS-1$
                    + (parameterCount == 0 ? "" : "String") + "); update EDT and retry"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            Object value = parameterCount == 0
                ? invoke(create, factory)
                : invoke(create, factory, exceptionMessage);
            if (!(value instanceof IBreakpoint))
            {
                throw new IllegalStateException("OSGi service '" + BSL_BREAKPOINT_FACTORY_SERVICE //$NON-NLS-1$
                    + "' returned a non-breakpoint from createExceptionBreakpoint; update EDT and retry"); //$NON-NLS-1$
            }
            return (IBreakpoint)value;
        }
        finally
        {
            context.ungetService(reference);
        }
    }

    /** Actionable text used when EDT has not registered its breakpoint factory service. */
    public static String breakpointFactoryUnavailableMessage()
    {
        return "OSGi service '" + BSL_BREAKPOINT_FACTORY_SERVICE //$NON-NLS-1$
            + "' is unavailable. Ensure bundle '" + BSL_DEBUG_CORE_BUNDLE //$NON-NLS-1$
            + "' is installed and active, then retry."; //$NON-NLS-1$
    }

    private static void configureExceptionBreakpoint(IBreakpoint breakpoint, boolean catchAll,
        String exceptionMessage) throws Exception
    {
        Method setMessage = findMethod(breakpoint.getClass(), "setExceptionMessage", 1); //$NON-NLS-1$
        Method setCatchAll = findMethod(breakpoint.getClass(), "setCatchAllExceptions", 1); //$NON-NLS-1$
        if (setMessage == null || setCatchAll == null)
        {
            throw new IllegalStateException("EDT exception breakpoint does not expose " //$NON-NLS-1$
                + "setExceptionMessage(String) and setCatchAllExceptions(boolean); update EDT and retry"); //$NON-NLS-1$
        }
        invoke(setMessage, breakpoint, exceptionMessage == null ? "" : exceptionMessage); //$NON-NLS-1$
        invoke(setCatchAll, breakpoint, Boolean.valueOf(catchAll));
        if (exceptionMessage == null || exceptionMessage.isEmpty())
        {
            clearMarkerAttribute(breakpoint.getMarker(), EXCEPTION_MESSAGE_ATTRIBUTE);
        }
    }

    /**
     * Removes one marker attribute that the platform setter did NOT remove.
     * <p>
     * Measured on EDT 2026.2: {@code setCatchAllExceptions(true)} reaches the marker, while
     * {@code setExceptionMessage("")} - which the platform turns into a null passed to the ARRAY
     * form of {@code setAttributes} - leaves the old filter in place. Read back after the setter,
     * the filter was still there, so the clear is finished here through the single-attribute form.
     * <p>
     * The name is the platform's exact id, never a suffix: a suffix would also delete a foreign
     * attribute such as {@code vendor.saved.exceptionMessage} that this code has no business
     * touching.
     *
     * @param marker the breakpoint's marker, may be {@code null}
     * @param attributeName the exact attribute id to remove
     * @throws Exception when the marker cannot be read or written
     */
    private static void clearMarkerAttribute(IMarker marker, String attributeName)
        throws Exception
    {
        if (marker == null || !marker.exists() || marker.getAttribute(attributeName) == null)
        {
            return;
        }
        marker.setAttribute(attributeName, null);
    }

    private static Object markerAttribute(IMarker marker, String attributeName) throws Exception
    {
        if (marker == null || !marker.exists())
        {
            return null;
        }
        return marker.getAttribute(attributeName);
    }

    private static boolean implementsInterfaceNamed(Class<?> type, String interfaceName)
    {
        if (type == null)
        {
            return false;
        }
        for (Class<?> iface : type.getInterfaces())
        {
            if (interfaceName.equals(iface.getName()) || implementsInterfaceNamed(iface, interfaceName))
            {
                return true;
            }
        }
        return implementsInterfaceNamed(type.getSuperclass(), interfaceName);
    }

    /**
     * Removes a breakpoint by id (marker id) on the breakpoint manager.
     *
     * @return {@code true} if a breakpoint was removed
     */
    public static boolean removeBreakpointById(long markerId) throws Exception
    {
        IBreakpointManager bpManager = DebugPlugin.getDefault().getBreakpointManager();
        for (IBreakpoint bp : bpManager.getBreakpoints())
        {
            IMarker m = bp.getMarker();
            if (m != null && m.getId() == markerId)
            {
                bpManager.removeBreakpoint(bp, true);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the breakpoint matching the given file + line, if any.
     */
    public static boolean removeBreakpointAt(IFile file, int line) throws Exception
    {
        IBreakpointManager bpManager = DebugPlugin.getDefault().getBreakpointManager();
        for (IBreakpoint bp : bpManager.getBreakpoints())
        {
            if (bp instanceof ILineBreakpoint)
            {
                ILineBreakpoint lb = (ILineBreakpoint) bp;
                IMarker m = bp.getMarker();
                if (m != null && file.equals(m.getResource()) && lb.getLineNumber() == line)
                {
                    bpManager.removeBreakpoint(bp, true);
                    return true;
                }
            }
        }
        return false;
    }

    /** Outcome of applying native line-breakpoint options. */
    public static final class LineBreakpointConfiguration
    {
        private final boolean applied;
        private final List<String> markerFallbacks;

        private LineBreakpointConfiguration(boolean applied, List<String> markerFallbacks)
        {
            this.applied = applied;
            this.markerFallbacks = Collections.unmodifiableList(new ArrayList<>(markerFallbacks));
        }

        public static LineBreakpointConfiguration appliedWith(List<String> markerFallbacks)
        {
            return new LineBreakpointConfiguration(true, markerFallbacks);
        }

        public static LineBreakpointConfiguration notApplied()
        {
            return new LineBreakpointConfiguration(false, Collections.emptyList());
        }

        public boolean isApplied()
        {
            return applied;
        }

        public List<String> getMarkerFallbacks()
        {
            return markerFallbacks;
        }
    }

    /** Outcome of changing the workspace-wide exception breakpoint. */
    public static final class ExceptionBreakpointChange
    {
        private final String action;
        private final IBreakpoint breakpoint;
        private final int disabledCount;
        private final boolean catchAll;
        private final String exceptionMessage;
        private final List<Long> configuredIds;
        private final int configuredCount;
        private final FilterAgreement filterAgreement;

        private ExceptionBreakpointChange(String action, IBreakpoint breakpoint, int disabledCount,
            boolean catchAll, String exceptionMessage, List<Long> configuredIds,
            int configuredCount, FilterAgreement filterAgreement)
        {
            this.action = action;
            this.breakpoint = breakpoint;
            this.disabledCount = disabledCount;
            this.catchAll = catchAll;
            this.exceptionMessage = exceptionMessage;
            this.configuredIds = configuredIds;
            this.configuredCount = configuredCount;
            this.filterAgreement = filterAgreement;
        }

        static ExceptionBreakpointChange enabled(String action, IBreakpoint breakpoint,
            boolean catchAll, String exceptionMessage, List<Long> configuredIds,
            int configuredCount, FilterAgreement filterAgreement)
        {
            return new ExceptionBreakpointChange(action, breakpoint, 0, catchAll, exceptionMessage,
                configuredIds, configuredCount, filterAgreement);
        }

        public static ExceptionBreakpointChange disabled(int disabledCount, List<Long> ids)
        {
            return new ExceptionBreakpointChange(disabledCount > 0 ? "disabled" : "notFound", //$NON-NLS-1$ //$NON-NLS-2$
                null, disabledCount, true, null, ids, disabledCount, FilterAgreement.SAME);
        }

        /**
         * The marker ids of every workspace-wide exception breakpoint this call configured.
         * <p>
         * Normally one. A workspace can hold several (legacy state), and then ALL of them are
         * configured while {@link #getBreakpoint()} names only the first - so a caller that
         * removed that one id would leave the others breaking on error. Handing back every id
         * keeps the cleanup self-contained: it does not depend on list_breakpoints being
         * enabled in the caller's toolset.
         * </p>
         *
         * @return the marker ids configured, empty when the call disabled them instead
         */
        public List<Long> getConfiguredIds()
        {
            return configuredIds;
        }

        /**
         * How many breakpoints the call configured (or disabled). Equal to the id list size
         * unless one of them has no marker to name.
         *
         * @return the number affected
         */
        public int getConfiguredCount()
        {
            return configuredCount;
        }

        /**
         * What the read-back established about the filters: whether they agree, differ, or
         * could not be read at all - three different answers a caller acts on differently.
         *
         * @return the agreement
         */
        public FilterAgreement getFilterAgreement()
        {
            return filterAgreement;
        }

        public String getAction()
        {
            return action;
        }

        public IBreakpoint getBreakpoint()
        {
            return breakpoint;
        }

        public int getDisabledCount()
        {
            return disabledCount;
        }

        public boolean isCatchAll()
        {
            return catchAll;
        }

        public String getExceptionMessage()
        {
            return exceptionMessage;
        }
    }

    /**
     * Tiny adapter that makes an {@link IMarker} usable as an {@link ILineBreakpoint}
     * when the EDT-specific class isn't available. The breakpoint manager won't
     * actually trigger debug events for it, but we still get list/remove semantics
     * driven by the marker.
     */
    public static final class MarkerOnlyBreakpoint implements ILineBreakpoint
    {
        private final IMarker marker;
        private boolean registered;

        MarkerOnlyBreakpoint(IMarker marker)
        {
            this.marker = marker;
        }

        @Override
        public IMarker getMarker()
        {
            return marker;
        }

        @Override
        public void setMarker(IMarker marker)
        {
            // immutable
        }

        @Override
        public String getModelIdentifier()
        {
            return BSL_MODEL_ID;
        }

        @Override
        public boolean isEnabled() throws org.eclipse.core.runtime.CoreException
        {
            return marker.getAttribute(IBreakpoint.ENABLED, true);
        }

        @Override
        public void setEnabled(boolean enabled) throws org.eclipse.core.runtime.CoreException
        {
            marker.setAttribute(IBreakpoint.ENABLED, enabled);
        }

        @Override
        public boolean isRegistered() throws org.eclipse.core.runtime.CoreException
        {
            return registered;
        }

        @Override
        public void setRegistered(boolean reg) throws org.eclipse.core.runtime.CoreException
        {
            this.registered = reg;
        }

        @Override
        public boolean isPersisted() throws org.eclipse.core.runtime.CoreException
        {
            return marker.getAttribute(IBreakpoint.PERSISTED, true);
        }

        @Override
        public void setPersisted(boolean persisted) throws org.eclipse.core.runtime.CoreException
        {
            marker.setAttribute(IBreakpoint.PERSISTED, persisted);
        }

        @Override
        public void delete() throws org.eclipse.core.runtime.CoreException
        {
            marker.delete();
        }

        @Override
        public int getLineNumber() throws org.eclipse.core.runtime.CoreException
        {
            return marker.getAttribute(IMarker.LINE_NUMBER, -1);
        }

        @Override
        public int getCharStart() throws org.eclipse.core.runtime.CoreException
        {
            return marker.getAttribute(IMarker.CHAR_START, -1);
        }

        @Override
        public int getCharEnd() throws org.eclipse.core.runtime.CoreException
        {
            return marker.getAttribute(IMarker.CHAR_END, -1);
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            return null;
        }

        @Override
        public int hashCode()
        {
            return marker == null ? 0 : Long.hashCode(marker.getId());
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) return true;
            if (!(obj instanceof MarkerOnlyBreakpoint)) return false;
            MarkerOnlyBreakpoint other = (MarkerOnlyBreakpoint) obj;
            return marker != null && other.marker != null && marker.getId() == other.marker.getId();
        }
    }
}
