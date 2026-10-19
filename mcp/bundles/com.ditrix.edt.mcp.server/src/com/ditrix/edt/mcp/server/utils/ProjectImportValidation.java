/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.SourceKind;

/** Shared argument validation for binary-file and XML project imports. */
public final class ProjectImportValidation
{
    private static final String NATURE_CONFIGURATION = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$
    private static final String NATURE_EXTENSION = "com._1c.g5.v8.dt.core.V8ExtensionNature"; //$NON-NLS-1$

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
        return validateBaseProject(kind, baseProjectName, resolved, "." + kind.extension() + " file"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String validateBaseProject(SourceKind kind, String baseProjectName,
        AtomicReference<IProject> resolved, String source)
    {
        if (kind == SourceKind.CONFIGURATION && baseProjectName != null)
        {
            return "baseProjectName does not apply to a " + source + ": a configuration depends on no " //$NON-NLS-1$ //$NON-NLS-2$
                + "other project. Omit baseProjectName."; //$NON-NLS-1$
        }
        if (kind == SourceKind.EXTENSION && baseProjectName == null)
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
                + "configuration project the " + kind.label() //$NON-NLS-1$
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
     * @return {@code null} when valid, otherwise an actionable refusal
     */
    public static String validateXmlDump(Path importPath, String projectNature, String baseProjectName)
    {
        Path configurationXml = importPath.resolve("Configuration.xml"); //$NON-NLS-1$
        if (!Files.exists(configurationXml) && projectNature == null && baseProjectName == null)
        {
            // Preserve CLI auto-detection for directories without a configuration root.
            return null;
        }
        Boolean extension = BinaryToXmlConverter.isExtensionDump(configurationXml);
        if (extension == null)
        {
            return "Cannot check projectNature or baseProjectName: " + configurationXml //$NON-NLS-1$
                + " is missing or unreadable. Pass the directory containing a valid Configuration.xml " //$NON-NLS-1$
                + "from export_configuration_to_xml."; //$NON-NLS-1$
        }
        String detectedNature = extension.booleanValue() ? NATURE_EXTENSION : NATURE_CONFIGURATION;
        if (projectNature != null && !projectNature.equals(detectedNature))
        {
            return "projectNature '" + projectNature + "' does not match the " //$NON-NLS-1$ //$NON-NLS-2$
                + (extension.booleanValue() ? "extension" : "main configuration") //$NON-NLS-1$ //$NON-NLS-2$
                + " dump in " + configurationXml + ". Omit projectNature to auto-detect, or pass '" //$NON-NLS-1$ //$NON-NLS-2$
                + detectedNature + "'."; //$NON-NLS-1$
        }
        SourceKind kind = extension.booleanValue() ? SourceKind.EXTENSION : SourceKind.CONFIGURATION;
        return validateBaseProject(kind, baseProjectName, new AtomicReference<>(), kind.label() + " XML dump"); //$NON-NLS-1$
    }
}
