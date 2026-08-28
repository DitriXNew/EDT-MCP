/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.InputStream;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.platform.ProjectManifest;
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

    /** Origin of an object owned by an external-objects project - neither core nor extension. */
    public static final String ORIGIN_EXTERNAL = "external object"; //$NON-NLS-1$

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
     * Resolves the BASE (parent) configuration project a dependent project derives
     * from. External-objects projects ({@code V8ExternalObjectsNature}) and
     * configuration extensions ({@code V8ExtensionNature}) both implement the EDT
     * supertype {@link IDependentProject}, whose {@code getParentProject()} returns the
     * base configuration project they depend on. Configuration projects are never
     * {@link IDependentProject} and therefore always resolve to {@code null}.
     *
     * @param project the workspace project (may be {@code null})
     * @return the base/parent {@link IProject} for a dependent project (may itself be
     *         {@code null} when the parent is unset), or {@code null} when the project
     *         is not dependent or cannot be resolved
     */
    public static IProject resolveBaseProject(IProject project)
    {
        try
        {
            IV8Project v8Project = resolveV8Project(project);
            if (v8Project instanceof IDependentProject)
            {
                return ((IDependentProject)v8Project).getParentProject();
            }
            return null;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Error resolving base project for: " //$NON-NLS-1$
                + (project == null ? "<null>" : project.getName()), e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Whether a dependent project PERMANENTLY declares a base project, read from its
     * {@code DT-INF/PROJECT.PMF} manifest. See {@link #readDeclaredBaseProject(IProject)} for why the
     * runtime parent cannot answer this.
     */
    public enum DeclaredBaseProject
    {
        /** The manifest exists and declares no {@code Base-Project} - the project is genuinely unlinked. */
        NONE,
        /** The manifest declares a {@code Base-Project}. */
        DECLARED,
        /** The manifest could not be read, so neither answer is proven. */
        UNREADABLE
    }

    /**
     * Reads the base project a dependent project PERMANENTLY declares in {@code DT-INF/PROJECT.PMF}.
     * <p>
     * {@link #resolveBaseProject(IProject)} cannot answer "is this project linked?": EDT's
     * {@code AbstractDependentProject.getParent()} returns {@code null} when the parent field is not
     * yet wired, when the parent has no workspace project, AND when the parent project is merely not
     * {@code isAccessible()} - so a LINKED project reports a null parent while its base is closed or
     * still opening, indistinguishable from a genuinely unlinked one. The manifest is the permanent
     * artifact that makes a project linked (EDT itself reads {@code Base-Project} from it), which is
     * the same reason this file prefers permanent natures over runtime registrations elsewhere.
     * <p>
     * Every DT project kind carries this manifest - it also holds the mandatory {@code Manifest-Version}
     * and {@code Runtime-Version} headers - so a missing or unparseable file is a malformed project,
     * reported as {@link DeclaredBaseProject#UNREADABLE} rather than as "declares none".
     *
     * @param project the workspace project (may be {@code null})
     * @return whether a base project is declared, or {@link DeclaredBaseProject#UNREADABLE}
     */
    public static DeclaredBaseProject readDeclaredBaseProject(IProject project)
    {
        if (project == null)
        {
            return DeclaredBaseProject.UNREADABLE;
        }
        try
        {
            IFile manifestFile = project.getFile(ProjectManifest.DT_PROJECT_MANIFEST);
            if (manifestFile == null || !manifestFile.exists())
            {
                return DeclaredBaseProject.UNREADABLE;
            }
            // A manifest being rewritten (save, refresh, git checkout) can be read back truncated,
            // and a truncation that lands past the mandatory headers but before Base-Project parses
            // cleanly into a map that merely LOOKS unlinked. Bracket the read with the resource
            // modification stamp - the same change-detection this search already applies to the Xtext
            // index - so a concurrently written manifest proves nothing instead of proving "unlinked".
            long stampBefore = manifestFile.getModificationStamp();
            if (stampBefore == IResource.NULL_STAMP)
            {
                return DeclaredBaseProject.UNREADABLE;
            }
            Map<String, String> headers = parseManifest(manifestFile);
            if (manifestFile.getModificationStamp() != stampBefore)
            {
                return DeclaredBaseProject.UNREADABLE;
            }
            // The workspace stamp only moves for a workspace-mediated write. A rewrite performed
            // OUTSIDE Eclipse (a checkout, an external editor) is invisible to it until a refresh,
            // and getContents(true) reads the local file - so a single read can land mid-write.
            // Reading a second time and requiring identical headers catches a manifest still in
            // flight. A manifest that is STABLY truncated is a corrupt project, which no cheap check
            // can tell apart from a legitimately unlinked one; that residual case is why the caller
            // additionally requires the project to have SETTLED before it acts on NONE.
            if (headers == null || !headers.equals(parseManifest(manifestFile)))
            {
                return DeclaredBaseProject.UNREADABLE;
            }
            // Manifest-Version and Runtime-Version are mandatory in every DT project kind (verified
            // on configuration, extension, external-objects and a freshly created external-objects
            // project). Their absence means this is not a complete manifest, however well it parsed.
            if (isBlank(headers.get(ProjectManifest.MANIFEST_VERSION))
                || isBlank(headers.get(ProjectManifest.RUNTIME_VERSION)))
            {
                return DeclaredBaseProject.UNREADABLE;
            }
            return isBlank(headers.get(ProjectManifest.BASE_PROJECT))
                ? DeclaredBaseProject.NONE : DeclaredBaseProject.DECLARED;
        }
        catch (Exception e)
        {
            // Deliberately broad: parsing, I/O and workspace access each fail differently and EVERY
            // failure must reach the same fail-closed answer. Nothing here is swallowed into a
            // cheerful default - UNREADABLE is what callers treat as "not proven".
            Activator.logError("Error reading project manifest for: " //$NON-NLS-1$
                + project.getName(), e);
            return DeclaredBaseProject.UNREADABLE;
        }
    }

    /** One parse of the project manifest; {@code null} when it yields no headers. */
    private static Map<String, String> parseManifest(IFile manifestFile) throws Exception
    {
        try (InputStream contents = manifestFile.getContents(true))
        {
            return ProjectManifest.parseProjectManifest(contents);
        }
    }

    /** A header is present only when it carries a non-blank value. */
    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
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
        return originLabel(belonging, isExtensionProject, false);
    }

    /**
     * The origin label, told which KIND of project the object came from.
     *
     * <p>An external data processor / report is owned by its external-objects project. It is not
     * a configuration object at all, so the two-valued core/extension question does not apply to
     * it - and answering {@code core} states that it belongs to a base configuration, which is
     * the very confusion this whole area exists to remove (issue #309).</p>
     *
     * @param belonging the object's belonging, or {@code null}
     * @param isExtensionProject whether the owning project is a configuration EXTENSION
     * @param isExternalObjectsProject whether the owning project is an EXTERNAL-OBJECTS project
     * @return one of {@link #ORIGIN_CORE}, {@link #ORIGIN_ADOPTED}, {@link #ORIGIN_EXTENSION},
     *     {@link #ORIGIN_EXTERNAL}
     */
    public static String originLabel(ObjectBelonging belonging, boolean isExtensionProject,
        boolean isExternalObjectsProject)
    {
        if (isExternalObjectsProject)
        {
            return ORIGIN_EXTERNAL;
        }
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
