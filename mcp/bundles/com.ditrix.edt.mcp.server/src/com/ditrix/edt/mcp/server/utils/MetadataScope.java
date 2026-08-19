/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com.ditrix.edt.mcp.server.Activator;

/**
 * The ROOT a metadata FQN resolves against for one EDT project.
 *
 * <p>For a configuration project - and for a configuration EXTENSION - that root is the
 * {@link Configuration}: every top-level object lives in one of its collections
 * ({@code catalogs}, {@code documents}, ...), which is what {@link MetadataTypeUtils#findObject}
 * and {@link MetadataNodeResolver} navigate.</p>
 *
 * <p>An EXTERNAL-OBJECTS project ({@code V8ExternalObjectsNature}, {@link IExternalObjectProject})
 * has NO such root. Its {@code ExternalDataProcessor} / {@code ExternalReport} objects are
 * standalone BM top objects held by the project itself, and
 * {@code IConfigurationProvider.getConfiguration(project)} answers with the PARENT configuration
 * the project is linked to - a different project's model entirely. Resolving an FQN against that
 * answer is how {@code create_metadata} / {@code get_metadata_details} came to look for an
 * external data processor's form inside the base configuration and report "Form not found", and
 * how {@code get_metadata_objects} listed the base configuration's objects for an external-objects
 * project (issue #309).</p>
 *
 * <p>This class is the one place that tells the two apart, so a tool asks the SCOPE for a
 * top-level object instead of asking a Configuration that may not be the right root at all.
 * A scope built from a plain {@link Configuration} behaves exactly as the previous direct calls
 * did, so existing configuration/extension behaviour is unchanged by construction.</p>
 *
 * <h2>Language facts</h2>
 * An external-objects project without a base configuration still declares a script variant and
 * languages - in its {@code DT-INF/PROJECT.PMF} manifest, surfaced by {@link IV8Project}. The
 * language accessors here answer from the {@link Configuration} whenever there IS one (identical
 * to the previous behaviour) and fall back to the {@link IV8Project} only when there is not.
 */
public final class MetadataScope
{
    /** The EClass name of an external data processor - a standalone external-objects root. */
    static final String ECLASS_EXTERNAL_DATA_PROCESSOR = "ExternalDataProcessor"; //$NON-NLS-1$

    /** The EClass name of an external report - a standalone external-objects root. */
    static final String ECLASS_EXTERNAL_REPORT = "ExternalReport"; //$NON-NLS-1$

    private final IProject project;
    private final Configuration configuration;
    private final IExternalObjectProject externalObjectProject;
    private final IV8Project v8Project;

    private MetadataScope(IProject project, Configuration configuration,
        IExternalObjectProject externalObjectProject, IV8Project v8Project)
    {
        this.project = project;
        this.configuration = configuration;
        this.externalObjectProject = externalObjectProject;
        this.v8Project = v8Project;
    }

    /**
     * The scope of a bare {@link Configuration} - the previous, implicit root. Used by the
     * {@code Configuration}-typed resolver overloads and by unit tests that build an in-memory
     * configuration with no workspace project behind it.
     *
     * @param configuration the configuration (may be {@code null}: an empty scope resolves nothing)
     * @return the scope, never {@code null}
     */
    public static MetadataScope ofConfiguration(Configuration configuration)
    {
        return new MetadataScope(null, configuration, null, null);
    }

    /**
     * Resolves the scope of a workspace project: the external-objects root set when the project is
     * an {@link IExternalObjectProject}, otherwise its {@link Configuration}.
     *
     * @param project the workspace project (may be {@code null})
     * @param configuration the configuration already resolved for {@code project} (may be
     *     {@code null}); for an external-objects project this is the PARENT configuration, kept for
     *     the language / script-variant facts it provides but never used as a resolution root
     * @return the scope, never {@code null}
     */
    public static MetadataScope of(IProject project, Configuration configuration)
    {
        IV8Project v8Project = resolveV8Project(project);
        if (v8Project instanceof IExternalObjectProject)
        {
            return new MetadataScope(project, configuration, (IExternalObjectProject)v8Project,
                v8Project);
        }
        return new MetadataScope(project, configuration, null, v8Project);
    }

    /**
     * Whether {@code project} is an external-objects project - answered WITHOUT needing a
     * configuration, so a caller can tell "this project has no Configuration root" apart from
     * "the configuration is not loaded yet".
     *
     * @param project the workspace project (may be {@code null})
     * @return {@code true} for an {@link IExternalObjectProject}
     */
    public static boolean isExternalObjectsProject(IProject project)
    {
        return resolveV8Project(project) instanceof IExternalObjectProject;
    }

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
        IV8ProjectManager manager = activator.getV8ProjectManager();
        if (manager == null)
        {
            return null;
        }
        try
        {
            return manager.getProject(project);
        }
        catch (RuntimeException e)
        {
            // A project mid-close / not registered: unknowable, and guessing "configuration" here
            // would send an external-objects FQN back to the base configuration.
            return null;
        }
    }

    /** @return {@code true} when this scope's root is an external-objects project's own objects. */
    public boolean isExternalObjects()
    {
        return externalObjectProject != null;
    }

    /**
     * @return the configuration - the resolution root for a configuration / extension project, and
     *     for an external-objects project the linked PARENT configuration (or {@code null} when it
     *     has none). NEVER the resolution root of an external-objects scope.
     */
    public Configuration configuration()
    {
        return configuration;
    }

    /** @return the workspace project this scope was resolved from; {@code null} for a bare-configuration scope. */
    public IProject project()
    {
        return project;
    }

    /**
     * The top-level objects of one TYPE, from whichever root this scope has.
     *
     * @param typeToken the leading FQN type token (English/Russian, singular/plural)
     * @return the objects, or {@code null} when the type is unknown to this root - the same
     *     "unrecognized type" answer {@link MetadataTypeUtils#getObjects} gives
     */
    public List<? extends MdObject> objects(String typeToken)
    {
        if (typeToken == null || typeToken.isEmpty())
        {
            return null;
        }
        if (externalObjectProject == null)
        {
            return MetadataTypeUtils.getObjects(configuration, typeToken);
        }
        String eClassName = externalEClassName(typeToken);
        if (eClassName == null)
        {
            // A configuration type token addressed inside an external-objects project: this root
            // holds no such collection at all. Not an empty list - "unknown here", which is what
            // lets the caller say so instead of reporting a plausible "no objects found".
            return null;
        }
        List<MdObject> result = new ArrayList<>();
        for (MdObject candidate : externalObjects())
        {
            if (candidate != null && candidate.eClass() != null
                && eClassName.equals(candidate.eClass().getName()))
            {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * Finds one top-level object by type token and programmatic Name (case-insensitive), from
     * whichever root this scope has.
     *
     * @param typeToken the leading FQN type token (English/Russian, singular/plural)
     * @param objectName the programmatic Name (never the synonym)
     * @return the object, or {@code null} when the type or the name does not resolve here
     */
    public MdObject findObject(String typeToken, String objectName)
    {
        if (externalObjectProject == null)
        {
            return MetadataTypeUtils.findObject(configuration, typeToken, objectName);
        }
        List<? extends MdObject> candidates = objects(typeToken);
        if (candidates == null || objectName == null)
        {
            return null;
        }
        for (MdObject candidate : candidates)
        {
            if (objectName.equalsIgnoreCase(candidate.getName()))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * EVERY top-level object this scope holds - used to build an actionable "not found" hint that
     * names what the project DOES contain.
     *
     * @return the objects; empty for a configuration scope (which has no cheap all-types walk) and
     *     for an external-objects project with nothing in it
     */
    public Collection<MdObject> allExternalObjects()
    {
        return externalObjectProject == null ? Collections.<MdObject> emptyList() : externalObjects();
    }

    private Collection<MdObject> externalObjects()
    {
        try
        {
            // The platform opens its own read transaction when none is running (see
            // ExternalObjectProject.getExternalObjectsInternal), so this is a read boundary either
            // way - the tools' read/write transaction rule is satisfied by the platform call.
            Collection<MdObject> objects = externalObjectProject.getExternalObjects();
            return objects == null ? Collections.<MdObject> emptyList() : objects;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not read the external objects of the project", e); //$NON-NLS-1$
            return Collections.emptyList();
        }
    }

    /**
     * The EClass name an external-objects root recognizes for a type token, or {@code null} when
     * the token names something an external-objects project cannot hold.
     *
     * @param typeToken the leading FQN type token (English/Russian, singular/plural)
     * @return {@code "ExternalDataProcessor"} / {@code "ExternalReport"}, or {@code null}
     */
    static String externalEClassName(String typeToken)
    {
        String english = MetadataTypeUtils.toEnglishSingular(typeToken);
        if (ECLASS_EXTERNAL_DATA_PROCESSOR.equals(english) || ECLASS_EXTERNAL_REPORT.equals(english))
        {
            return english;
        }
        return null;
    }

    /**
     * The language codes this scope's project declares - the configuration's when it has one,
     * otherwise the external-objects project's own (manifest) languages.
     *
     * <p>Returned as an OVERRIDE for {@code MetadataLanguageUtils}' {@code declaredOverride}
     * parameter: {@code null} whenever a {@link Configuration} is present, so the configuration
     * path stays byte-identical to what it was before this class existed.</p>
     *
     * @return the declared codes, or {@code null} when the configuration already answers
     */
    public List<String> declaredLanguageOverride()
    {
        if (configuration != null || v8Project == null)
        {
            return null;
        }
        List<String> codes = new ArrayList<>();
        try
        {
            for (Language language : v8Project.getLanguages())
            {
                String code = language == null ? null : language.getLanguageCode();
                if (code != null && !code.isEmpty() && !codes.contains(code))
                {
                    codes.add(code);
                }
            }
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not read the project's declared languages", e); //$NON-NLS-1$
        }
        return codes;
    }

    /**
     * The language CODE to read synonyms under in this scope: an explicitly requested one when
     * given, otherwise this scope's default. The scope-aware twin of
     * {@link MetadataLanguageUtils#resolveLanguageCode(Configuration, String)}.
     *
     * @param explicit an explicitly requested language code, or {@code null}/empty
     * @return the code, or {@code null} when none can be determined
     */
    public String resolveLanguageCode(String explicit)
    {
        if (explicit != null && !explicit.isEmpty())
        {
            return explicit;
        }
        return defaultLanguageCode();
    }

    /**
     * The language codes this scope declares - the configuration's, or the external-objects
     * project's own when it has no base configuration.
     *
     * <p>An EMPTY result means "declares none", which callers must treat as <em>cannot
     * validate</em>, exactly as {@link MetadataLanguageUtils#declaredLanguageCodes} specifies.</p>
     *
     * @return the declared codes, never {@code null}
     */
    public List<String> declaredLanguageCodes()
    {
        if (configuration != null)
        {
            return MetadataLanguageUtils.declaredLanguageCodes(configuration);
        }
        List<String> own = declaredLanguageOverride();
        return own == null ? Collections.<String> emptyList() : own;
    }

    /**
     * The default language CODE for localized values written in this scope: the configuration's
     * when there is one, otherwise the external-objects project's own default language.
     *
     * @return the code (e.g. {@code "ru"}), or {@code null} when none can be determined
     */
    public String defaultLanguageCode()
    {
        String fromConfiguration = MetadataLanguageUtils.resolveLanguageCode(configuration, null);
        if (fromConfiguration != null || v8Project == null)
        {
            return fromConfiguration;
        }
        try
        {
            Language defaultLanguage = v8Project.getDefaultLanguage();
            if (defaultLanguage != null && defaultLanguage.getLanguageCode() != null
                && !defaultLanguage.getLanguageCode().isEmpty())
            {
                return defaultLanguage.getLanguageCode();
            }
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not read the project's default language", e); //$NON-NLS-1$
        }
        List<String> declared = declaredLanguageOverride();
        return declared == null || declared.isEmpty() ? null : declared.get(0);
    }

    /**
     * The sentence to append to a "not found" / "cannot resolve" error when the reason is that the
     * FQN names a type this ROOT cannot hold - the mismatch that made {@code #309} read as a
     * missing object rather than as the wrong project.
     *
     * <p>Two directions, both silent failures before: a CONFIGURATION type addressed in an
     * external-objects project (which holds only external data processors / reports), and an
     * EXTERNAL-OBJECTS type addressed in a configuration project (which holds neither). Anything
     * else returns an empty string, so a caller appends it unconditionally.</p>
     *
     * @param fqn the address that did not resolve (may be {@code null})
     * @return the hint sentence with a leading space, or an empty string
     */
    public String addressingHint(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        int dot = fqn.indexOf('.');
        String typeToken = dot > 0 ? fqn.substring(0, dot) : fqn;
        MetadataTypeUtils.MetadataTypeInfo info = MetadataTypeUtils.resolve(typeToken);
        if (info == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (isExternalObjects() && !info.isStandalone())
        {
            return " Project '" + projectLabel() + "' is an EXTERNAL-OBJECTS project: it holds only " //$NON-NLS-1$ //$NON-NLS-2$
                + "ExternalDataProcessor / ExternalReport objects" + externalObjectSummary() //$NON-NLS-1$
                + ", never a " + info.getEnglishSingular() //$NON-NLS-1$
                + ". Address that type in the configuration project it belongs to."; //$NON-NLS-1$
        }
        if (!isExternalObjects() && info.isStandalone())
        {
            return " '" + info.getEnglishSingular() + "' is an EXTERNAL-OBJECTS type: it lives in a " //$NON-NLS-1$ //$NON-NLS-2$
                + "project with the external-objects nature, not in a configuration. Use " //$NON-NLS-1$
                + "list_projects to find that project and pass its name as projectName."; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    private String projectLabel()
    {
        return project == null ? "?" : project.getName(); //$NON-NLS-1$
    }

    /** " (Name, Name, ...)" for an external-objects root with content; empty when it has none. */
    private String externalObjectSummary()
    {
        StringBuilder names = new StringBuilder();
        for (MdObject object : allExternalObjects())
        {
            if (object == null || object.getName() == null)
            {
                continue;
            }
            names.append(names.length() == 0 ? " (" : ", ").append(object.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return names.length() == 0 ? "" : names.append(')').toString(); //$NON-NLS-1$
    }

    /**
     * Resolves the language CODE a localized value (synonym / title) must be stored under in this
     * scope, with the same validation and the same error texts
     * {@link MetadataLanguageUtils#resolveSynonymLanguage(Configuration, String, String, String)}
     * applies.
     *
     * <p>With a {@link Configuration} present this IS that call, unchanged. Without one - an
     * external-objects project linked to no base configuration - the project's own declared
     * languages take the configuration's place, both as the set an explicit code is validated
     * against and as the source of the default. Falling through to the configuration path there
     * would refuse every localized value in a project that does declare languages, just not in a
     * Configuration.</p>
     *
     * @param value the localized value being set (may be {@code null}/empty)
     * @param explicitLanguage an explicitly requested language code, or {@code null}/empty
     * @param subject what is being localized, for the error message (e.g. {@code "the synonym"})
     * @return the resolved language code, or {@code null} when {@code value} is absent
     * @throws IllegalArgumentException when a code is needed but cannot be determined
     */
    public String resolveSynonymLanguage(String value, String explicitLanguage, String subject)
    {
        if (configuration != null)
        {
            return MetadataLanguageUtils.resolveSynonymLanguage(configuration, value,
                explicitLanguage, subject);
        }
        // No configuration to answer from: validate against - and default to - what the project
        // itself declares. An omitted code becomes the project's default BEFORE the shared call, so
        // the shared validation and its wording still decide the outcome.
        String effective = explicitLanguage != null && !explicitLanguage.isEmpty()
            ? explicitLanguage : defaultLanguageCode();
        return MetadataLanguageUtils.resolveSynonymLanguage(null, value, effective, subject,
            declaredLanguageOverride());
    }

    /**
     * The script variant that decides whether the designer's auto-generated child names are
     * Russian: the configuration's when there is one, otherwise the project's own.
     *
     * @return the script variant, or {@code null} when it cannot be determined
     */
    public ScriptVariant scriptVariant()
    {
        if (configuration != null)
        {
            return configuration.getScriptVariant();
        }
        if (v8Project == null)
        {
            return null;
        }
        try
        {
            return v8Project.getScriptVariant();
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not read the project's script variant", e); //$NON-NLS-1$
            return null;
        }
    }
}
