/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Resolves the ORIGIN of a metadata object: is it a native object of the base
 * configuration ("core"), an object ADOPTED (заимствован) from the base by a
 * configuration extension, or an object the extension itself OWNS.
 *
 * <h2>Why this exists</h2>
 * A 1C configuration EXTENSION ({@link IExtensionProject}, {@code V8ExtensionNature})
 * lists, alongside its own new objects, the base-configuration objects it has
 * adopted in order to override/intercept them. Listing an extension's metadata
 * therefore mixes two very different kinds of object, and the tools previously gave
 * the caller no way to tell them apart. The single discriminator is the EMF
 * {@code MdObject.getObjectBelonging()} flag ({@link ObjectBelonging#NATIVE} vs
 * {@link ObjectBelonging#ADOPTED}) read in the context of the project type:
 * <ul>
 *   <li>In a base configuration project every object is {@code NATIVE} → "core".</li>
 *   <li>In an extension project an {@code ADOPTED} object is borrowed from the base
 *       configuration → "core (adopted)"; a {@code NATIVE} object is the extension's
 *       own → "extension".</li>
 * </ul>
 *
 * <p>Only extensions ever hold {@code ADOPTED} objects, so the project-type check is
 * what disambiguates a {@code NATIVE} object (base-config-native vs extension-own).
 *
 * <p>The discriminator is the EMF {@code MdObject.getObjectBelonging()} flag.
 */
public final class ExtensionOriginUtils
{
    /** A native object of the base configuration. */
    public static final String ORIGIN_CORE = "core"; //$NON-NLS-1$

    /** A base-configuration object adopted (заимствован) by an extension to override/intercept it. */
    public static final String ORIGIN_ADOPTED = "core (adopted)"; //$NON-NLS-1$

    /** An object the extension itself defines (its own, not from the base configuration). */
    public static final String ORIGIN_EXTENSION = "extension"; //$NON-NLS-1$

    private ExtensionOriginUtils()
    {
        // Utility class
    }

    /**
     * @param project a workspace project handle (may be {@code null})
     * @return {@code true} when the project is a configuration EXTENSION
     *         ({@link IExtensionProject}); {@code false} for a base configuration,
     *         a non-1C project, or when the project managers are unavailable
     */
    public static boolean isExtensionProject(IProject project)
    {
        return resolveV8Project(project) instanceof IExtensionProject;
    }

    /**
     * The origin label for an object of the given belonging listed in a project of
     * the given type. Pure decision (no workspace access) so callers compute
     * {@code isExtensionProject} once per request via
     * {@link #isExtensionProject(IProject)} and reuse it for every row, reading each
     * object's {@code MdObject.getObjectBelonging()} as they iterate.
     *
     * @param belonging the object's {@link ObjectBelonging} (may be {@code null} →
     *        treated as {@code NATIVE})
     * @param isExtensionProject whether the owning project is a configuration extension
     * @return one of {@link #ORIGIN_CORE}, {@link #ORIGIN_ADOPTED}, {@link #ORIGIN_EXTENSION}
     */
    public static String originLabel(ObjectBelonging belonging, boolean isExtensionProject)
    {
        if (!isExtensionProject)
        {
            // A base configuration only ever holds native objects.
            return ORIGIN_CORE;
        }
        return belonging == ObjectBelonging.ADOPTED
            ? ORIGIN_ADOPTED
            : ORIGIN_EXTENSION;
    }

    /**
     * Resolves a workspace {@link IProject} to its {@link IV8Project} (configuration
     * or extension) via the EDT project managers, mirroring the resolution used by
     * {@code GetConfigurationPropertiesTool}.
     *
     * @param project the workspace project (may be {@code null})
     * @return the IV8Project, or {@code null} when it cannot be resolved
     */
    private static IV8Project resolveV8Project(IProject project)
    {
        if (project == null)
        {
            return null;
        }
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IDtProjectManager dtProjectManager = activator.getDtProjectManager();
        IV8ProjectManager v8ProjectManager = activator.getV8ProjectManager();
        if (dtProjectManager == null || v8ProjectManager == null)
        {
            return null;
        }
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return null;
        }
        return v8ProjectManager.getProject(dtProject);
    }
}
