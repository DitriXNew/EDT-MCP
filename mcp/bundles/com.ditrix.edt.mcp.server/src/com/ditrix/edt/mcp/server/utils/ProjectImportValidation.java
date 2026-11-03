/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.DumpKind;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.SourceKind;

/** Shared argument validation for binary-file and XML project imports. */
public final class ProjectImportValidation
{
    public static final String NATURE_CONFIGURATION = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$
    public static final String NATURE_EXTENSION = "com._1c.g5.v8.dt.core.V8ExtensionNature"; //$NON-NLS-1$
    public static final String NATURE_EXTERNAL_OBJECTS = "com._1c.g5.v8.dt.core.V8ExternalObjectsNature"; //$NON-NLS-1$

    private ProjectImportValidation()
    {
    }

    /** @return the trimmed value, or {@code null} for an omitted or blank argument */
    public static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Applies the base-project rules of a binary file.
     *
     * @param kind what the file holds
     * @param baseProjectName the requested base, or {@code null}
     * @param resolved receives the base project when one is valid
     * @return {@code null} when valid, otherwise an actionable refusal
     */
    public static String validateBaseProject(SourceKind kind, String baseProjectName,
        AtomicReference<IProject> resolved)
    {
        return validateBaseProject(kind == SourceKind.CONFIGURATION, kind == SourceKind.EXTENSION,
            kind.label(), "." + kind.extension() + " file", baseProjectName, resolved); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String validateBaseProject(boolean configuration, boolean extension, String label,
        String source, String baseProjectName, AtomicReference<IProject> resolved)
    {
        if (configuration && baseProjectName != null)
        {
            return "baseProjectName does not apply to a " + source + ": a configuration depends on no " //$NON-NLS-1$ //$NON-NLS-2$
                + "other project. Omit baseProjectName."; //$NON-NLS-1$
        }
        if (extension && baseProjectName == null)
        {
            return "baseProjectName is required for a " + source + ": an extension project is linked to " //$NON-NLS-1$ //$NON-NLS-2$
                + "the configuration project it extends. Use list_projects to find it."; //$NON-NLS-1$
        }
        if (baseProjectName == null)
        {
            return null;
        }
        if (WorkspacePaths.projectNameProblem(baseProjectName) != null
            || !ProjectContext.of(baseProjectName).exists())
        {
            return ProjectContext.notFoundMessage(baseProjectName);
        }
        ProjectContext base = ProjectContext.of(baseProjectName);
        if (!base.isOpen())
        {
            return "Base project '" + baseProjectName + "' is closed. Open it in EDT first."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        Set<String> natures = ProjectContext.naturesOf(base.project());
        if (natures == null || !natures.contains(NATURE_CONFIGURATION))
        {
            return "Base project '" + baseProjectName + "' is not a configuration project. Pass the " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration project the " + label //$NON-NLS-1$
                + " belongs to, not an extension or external-objects project."; //$NON-NLS-1$
        }
        resolved.set(base.project());
        return null;
    }

    /**
     * Checks the requested nature and base against the same dump kind EDT's CLI detects.
     *
     * @param importPath the source directory
     * @param projectNature an optional nature assertion, never an override
     * @param baseProjectName the requested base, or {@code null}
     * @param resolved receives the validated base project, if any
     * @return {@code null} when valid, otherwise an actionable refusal
     */
    public static String validateXmlDump(Path importPath, String projectNature, String baseProjectName,
        AtomicReference<IProject> resolved)
    {
        if (projectNature != null && !Set.of(NATURE_CONFIGURATION, NATURE_EXTENSION, NATURE_EXTERNAL_OBJECTS)
            .contains(projectNature))
        {
            return "Unknown projectNature '" + projectNature + "'. Use one of: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", NATURE_CONFIGURATION, NATURE_EXTENSION, NATURE_EXTERNAL_OBJECTS) //$NON-NLS-1$
                + ". Omit projectNature to auto-detect."; //$NON-NLS-1$
        }
        DumpKind kind = BinaryToXmlConverter.dumpKind(importPath);
        if (kind == DumpKind.UNREADABLE)
        {
            if (projectNature == null && baseProjectName == null)
            {
                // EDT's quick scan may accept XML that our DOM reader cannot parse.
                return null;
            }
            return "Configuration.xml could not be read, so the dump kind cannot be verified: " //$NON-NLS-1$
                + importPath.resolve("Configuration.xml") //$NON-NLS-1$
                + ". Pass the root dump directory from export_configuration_to_xml, or omit " //$NON-NLS-1$
                + "projectNature and baseProjectName to let EDT detect it."; //$NON-NLS-1$
        }
        String detectedNature;
        String label;
        switch (kind)
        {
            case EXTENSION:
                detectedNature = NATURE_EXTENSION;
                label = "extension"; //$NON-NLS-1$
                break;
            case EXTERNAL:
                detectedNature = NATURE_EXTERNAL_OBJECTS;
                label = "external objects"; //$NON-NLS-1$
                break;
            default:
                detectedNature = NATURE_CONFIGURATION;
                label = "main configuration"; //$NON-NLS-1$
                break;
        }
        if (projectNature != null && !projectNature.equals(detectedNature))
        {
            return "projectNature '" + projectNature + "' does not match the " //$NON-NLS-1$ //$NON-NLS-2$
                + label + " dump in " + importPath + ". Omit projectNature to auto-detect, or pass '" //$NON-NLS-1$ //$NON-NLS-2$
                + detectedNature + "'."; //$NON-NLS-1$
        }
        String sourceLabel = kind == DumpKind.CONFIGURATION ? "configuration" //$NON-NLS-1$
            : kind == DumpKind.EXTENSION ? "configuration extension" : label; //$NON-NLS-1$
        return validateBaseProject(kind == DumpKind.CONFIGURATION, kind == DumpKind.EXTENSION,
            sourceLabel, sourceLabel + " XML dump", baseProjectName, resolved); //$NON-NLS-1$
    }

    /**
     * Validates an explicit XML-import runtime without allowing EDT to silently ignore it or
     * install platform support inside the import call. An unavailable registry skips only the
     * support check, never syntax validation.
     *
     * @param platformVersion the trimmed runtime, or {@code null} for auto-detection
     * @return an actionable refusal, or {@code null} when valid
     */
    public static String validateRuntimeVersion(String platformVersion)
    {
        if (platformVersion == null)
        {
            return null;
        }
        Version version;
        try
        {
            // Version(String) also accepts incomplete versions; the CLI runtime needs all three parts.
            if (!platformVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) //$NON-NLS-1$
            {
                throw new IllegalArgumentException();
            }
            version = new Version(platformVersion);
        }
        catch (IllegalArgumentException e)
        {
            return "Invalid platformVersion '" + platformVersion //$NON-NLS-1$
                + "'. Use a three-part runtime version such as '8.3.27', without a build number."; //$NON-NLS-1$
        }
        Activator activator = Activator.getDefault();
        IRuntimeRegistry registry = activator == null ? null : activator.getRuntimeRegistry();
        if (registry == null || registry.getRuntime(version) != null)
        {
            return null;
        }
        Set<String> supported = new TreeSet<>();
        for (IRuntime runtime : registry.getRuntimes())
        {
            supported.add(runtime.getVersion().toString());
        }
        return "platformVersion '" + platformVersion + "' is not supported by this EDT; supported: " //$NON-NLS-1$ //$NON-NLS-2$
            + (supported.isEmpty() ? "(none)" : String.join(", ", supported)) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Omit platformVersion to derive it from the dump, or pass a supported version."; //$NON-NLS-1$
    }

    /**
     * @param baseProject the configuration project the import depends on
     * @return its runtime version, so the new project matches it; {@code null} lets EDT derive one
     *     from the XML
     */
    public static String runtimeVersionOf(IProject baseProject)
    {
        try
        {
            Activator activator = Activator.getDefault();
            IV8ProjectManager manager = activator == null ? null : activator.getV8ProjectManager();
            IV8Project v8Project = manager == null ? null : manager.getProject(baseProject);
            return v8Project == null || v8Project.getVersion() == null ? null
                : v8Project.getVersion().toString();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
