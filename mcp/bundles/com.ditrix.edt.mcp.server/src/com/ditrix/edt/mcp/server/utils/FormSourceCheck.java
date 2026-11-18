/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.IllegalValueException;
import org.eclipse.emf.ecore.xmi.XMLResource;

import com._1c.g5.modeling.xml.AbstractXmlResource;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.provider.IResourceContentImporter;
import com._1c.g5.v8.dt.core.provider.NoopLazyLinkerProvider;
import com._1c.g5.v8.dt.core.resource.IDtProjectAware;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Re-reads a form's {@code Form.form} the way EDT's form importer does and returns what the parser
 * reported. EDT's import records those problems on a throwaway resource and drops them: a value the
 * parser rejects (an unknown enum literal) silently becomes the model default, which a re-save then
 * omits from the file. This is the only place that problem becomes visible.
 *
 * <p>Read-only: the file is parsed into a fresh resource set that never reaches the BM model.</p>
 */
public final class FormSourceCheck
{
    /** What kind of problem the parser reported. */
    public enum Kind
    {
        /** A value the parser rejected; the model holds the feature's default instead. */
        INVALID_VALUE,
        /** Any other load error: that part of the file is not in the model. */
        UNREADABLE,
        /** A load warning. */
        WARNING,
        /** The file could not be checked at all. */
        NOT_CHECKED
    }

    /**
     * One problem in the source file.
     *
     * @param kind what the parser reported
     * @param location {@code <project-relative file>:<line>:<column>}, or the file alone
     * @param message one sentence naming the value and the consequence
     */
    public record Problem(Kind kind, String location, String message)
    {
    }

    private FormSourceCheck()
    {
    }

    /**
     * The project-relative path of the file EDT keeps {@code formModel} in; requires an active read.
     *
     * @param project the project owning the form
     * @param formModel the content form
     * @return the path, or {@code null} when EDT names none
     */
    public static String sourcePathOf(IProject project, EObject formModel)
    {
        try
        {
            IProjectFileSystemSupportProvider provider = ServiceAccess.get(IProjectFileSystemSupportProvider.class);
            IProjectFileSystemSupport fs = provider == null ? null : provider.getProjectFileSystemSupport(project);
            IFile file = fs == null ? null : fs.getFile(formModel);
            return file == null ? null : file.getProjectRelativePath().toPortableString();
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not locate the source file of a form", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Parses the form file with EDT's registered {@code .form} resource factory and the importer's
     * load options (the factory defaults plus the project's platform version, which selects the
     * 8.5.1 feature mapping).
     *
     * @param project the project owning the form
     * @param model the project's BM model; the load runs in a read of it, as the importer's does
     * @param relativePath the project-relative path from {@link #sourcePathOf}, or {@code null}
     * @param version the project's platform version, or {@code null} when unknown
     * @return the problems, empty when the file loads cleanly
     */
    public static List<Problem> check(IProject project, IBmModel model, String relativePath, Version version)
    {
        if (relativePath == null)
        {
            return List.of(notChecked("(form)", "EDT names no source file for this form")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IPath location = project.getLocation();
        File file = location == null ? null : location.append(relativePath).toFile();
        if (file == null || !file.isFile())
        {
            return List.of(notChecked(relativePath, "the file does not exist on disk")); //$NON-NLS-1$
        }
        Resource resource = new ResourceSetImpl().createResource(
            URI.createPlatformResourceURI("/" + project.getName() + "/" + relativePath, true)); //$NON-NLS-1$ //$NON-NLS-2$
        if (resource == null)
        {
            return List.of(notChecked(relativePath, "no .form parser is registered")); //$NON-NLS-1$
        }
        try (InputStream in = new FileInputStream(file))
        {
            prepare(project, resource);
            Map<Object, Object> options = new HashMap<>();
            if (resource instanceof XMLResource xml)
            {
                options.putAll(xml.getDefaultLoadOptions());
            }
            if (version != null)
            {
                options.put(IResourceContentImporter.PLATFORM_VERSION, version);
            }
            String failure = BmTransactions.read(model, "CheckFormSource", (tx, monitor) -> //$NON-NLS-1$
            {
                try
                {
                    resource.load(in, options);
                    return null;
                }
                catch (IOException e)
                {
                    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            });
            List<Problem> problems = new ArrayList<>();
            for (Resource.Diagnostic diagnostic : resource.getErrors())
            {
                problems.add(problemOf(diagnostic, true, relativePath));
            }
            for (Resource.Diagnostic diagnostic : resource.getWarnings())
            {
                problems.add(problemOf(diagnostic, false, relativePath));
            }
            if (problems.isEmpty() && failure != null)
            {
                problems.add(notChecked(relativePath, "the parser failed: " + failure)); //$NON-NLS-1$
            }
            return problems;
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError("Could not re-read the form source " + relativePath, e); //$NON-NLS-1$
            return List.of(notChecked(relativePath, "it could not be read: " //$NON-NLS-1$
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
        finally
        {
            resource.unload();
        }
    }

    /** Mirrors the importer: no linking during the load, and the resource knows its project. */
    private static void prepare(IProject project, Resource resource)
    {
        if (resource instanceof AbstractXmlResource xml)
        {
            xml.setLinker(NoopLazyLinkerProvider.get());
        }
        if (resource instanceof IDtProjectAware aware)
        {
            Activator plugin = Activator.getDefault();
            IDtProjectManager manager = plugin == null ? null : plugin.getDtProjectManager();
            IDtProject dtProject = manager == null ? null : manager.getDtProject(project);
            if (dtProject != null)
            {
                aware.setDtProject(dtProject);
            }
        }
    }

    /**
     * Turns one parser diagnostic into a problem. Package-visible for tests.
     *
     * @param diagnostic the diagnostic, usually EDT's wrapper around the parser's exception
     * @param error whether it came from {@code getErrors()}
     * @param relativePath the file, for the location
     * @return the problem
     */
    static Problem problemOf(Resource.Diagnostic diagnostic, boolean error, String relativePath)
    {
        Object cause = diagnostic instanceof Throwable thrown && thrown.getCause() != null
            ? thrown.getCause() : diagnostic;
        int line = diagnostic.getLine();
        int column = diagnostic.getColumn();
        if (line <= 0 && cause instanceof Resource.Diagnostic located)
        {
            line = located.getLine();
            column = located.getColumn();
        }
        String location = line > 0 ? relativePath + ":" + line + ":" + column : relativePath; //$NON-NLS-1$ //$NON-NLS-2$
        if (!error)
        {
            return new Problem(Kind.WARNING, location, messageOf(diagnostic, cause) + "."); //$NON-NLS-1$
        }
        if (cause instanceof IllegalValueException illegal && illegal.getFeature() != null)
        {
            return new Problem(Kind.INVALID_VALUE, location, invalidValueMessage(illegal.getObject(),
                illegal.getFeature(), illegal.getValue()));
        }
        return new Problem(Kind.UNREADABLE, location, messageOf(diagnostic, cause)
            + ". EDT could not load this part of the file, so it is not in the model and a re-save of the " //$NON-NLS-1$
            + "form drops it."); //$NON-NLS-1$
    }

    /**
     * The sentence for a rejected value: what was written, where, what is allowed, and what EDT did.
     *
     * @param owner the object the value was meant for (may be {@code null})
     * @param feature the feature
     * @param value the rejected value
     * @return the message
     */
    static String invalidValueMessage(EObject owner, EStructuralFeature feature, Object value)
    {
        StringBuilder sb = new StringBuilder("'").append(value).append("' is not a valid ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(feature.getName());
        if (owner != null)
        {
            sb.append(" of ").append(owner.eClass().getName()); //$NON-NLS-1$
            String item = nearestName(owner);
            if (item != null)
            {
                sb.append(" in '").append(item).append('\''); //$NON-NLS-1$
            }
        }
        boolean enumerated = feature instanceof EAttribute attribute && attribute.getEAttributeType() instanceof EEnum;
        if (enumerated && owner != null)
        {
            sb.append("; allowed: ").append(FormElementWriter.enumLiteralsOf(owner, feature.getName())); //$NON-NLS-1$
        }
        Object fallback = feature.getDefaultValue();
        sb.append(". EDT loaded the default"); //$NON-NLS-1$
        if (fallback instanceof Enumerator literal)
        {
            sb.append(" (").append(literal.getLiteral()).append(')'); //$NON-NLS-1$
        }
        else if (fallback != null)
        {
            sb.append(" (").append(fallback).append(')'); //$NON-NLS-1$
        }
        return sb.append(" instead, and a re-save of this form drops the value from the file.").toString(); //$NON-NLS-1$
    }

    /** The name of {@code object} or of its nearest named container, or {@code null}. */
    private static String nearestName(EObject object)
    {
        for (EObject current = object; current != null; current = current.eContainer())
        {
            EStructuralFeature name = current.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
            Object value = name == null || name.isMany() ? null : current.eGet(name);
            if (value instanceof String text && !text.isEmpty())
            {
                return text;
            }
        }
        return null;
    }

    private static String messageOf(Resource.Diagnostic diagnostic, Object cause)
    {
        String message = cause instanceof Throwable thrown && thrown.getMessage() != null ? thrown.getMessage()
            : diagnostic.getMessage();
        String text = message == null ? "the parser reported a problem" : message.strip(); //$NON-NLS-1$
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text; //$NON-NLS-1$
    }

    private static Problem notChecked(String location, String reason)
    {
        return new Problem(Kind.NOT_CHECKED, location, "The source file was not checked: " + reason //$NON-NLS-1$
            + ". Values EDT dropped while loading it cannot be reported."); //$NON-NLS-1$
    }
}
