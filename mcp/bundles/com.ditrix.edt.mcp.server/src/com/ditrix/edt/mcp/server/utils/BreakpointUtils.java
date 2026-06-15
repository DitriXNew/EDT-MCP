/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Constructor;
import java.util.HashMap;
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
        // absolute case the project is not needed (resolution is by location);
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
        if (debugCoreBundle != null)
        {
            for (String className : BSL_BREAKPOINT_CLASSES)
            {
                try
                {
                    Class<?> cls = debugCoreBundle.loadClass(className);
                    Constructor<?> ctor = findConstructor(cls);
                    if (ctor != null)
                    {
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
                }
                catch (ClassNotFoundException cnf)
                {
                    // try next class name
                }
                catch (Exception ex)
                {
                    Activator.logError("Failed to instantiate " + className, ex); //$NON-NLS-1$
                }
            }
        }
        else
        {
            Activator.logError("Bundle " + BSL_DEBUG_CORE_BUNDLE //$NON-NLS-1$
                    + " not found — falling back to marker", new IllegalStateException("bundle missing")); //$NON-NLS-1$ //$NON-NLS-2$
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
