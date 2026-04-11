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
import org.eclipse.core.runtime.Path;
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
        if (looksLikeAbsolutePath(module))
        {
            // Find IFile by location among workspace files
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                .findFilesForLocationURI(new java.io.File(module).toURI());
            if (files.length > 0)
            {
                return files[0];
            }
            return null;
        }
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return null;
        }
        return project.getFile(new Path("src").append(module)); //$NON-NLS-1$
    }

    /**
     * Heuristic: a string is treated as an absolute path if it starts with a slash,
     * a backslash, or matches a Windows drive prefix like {@code C:}.
     */
    public static boolean looksLikeAbsolutePath(String s)
    {
        if (s == null || s.isEmpty())
        {
            return false;
        }
        char c0 = s.charAt(0);
        if (c0 == '/' || c0 == '\\')
        {
            return true;
        }
        if (s.length() >= 2 && s.charAt(1) == ':')
        {
            return true;
        }
        return false;
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

        // Strategy 1: load EDT-specific BslLineBreakpoint via the owning bundle's
        // class loader. The class lives in an `internal.*` package that OSGi will
        // not export through Import-Package, so a plain Class.forName() from this
        // bundle would fail with ClassNotFoundException — but Bundle.loadClass()
        // bypasses the export restriction and returns the class directly.
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

        // Strategy 2: create marker of EDT type and wrap as generic ILineBreakpoint via DebugPlugin
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

        @SuppressWarnings("rawtypes")
        @Override
        public Object getAdapter(Class adapter)
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
