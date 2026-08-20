/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Per-launch attribute overrides for a debug launch: the {@code /C} startup option, and the
 * external data processor / report to run on startup ({@code /Execute}).
 *
 * <p><b>Nothing here is persisted.</b> The overrides are applied to an
 * {@link ILaunchConfigurationWorkingCopy}, which is itself an {@link ILaunchConfiguration} and can
 * be launched directly, so the saved EDT configuration is never modified. {@code doSave()} is
 * deliberately never called - the working copy is created, stamped and launched.</p>
 *
 * <h2>Why the external object is named, not pathed</h2>
 *
 * <p>EDT's {@code RuntimeClientLaunchDelegate} resolves the object inside an
 * {@code IExternalObjectProject}, has the platform BUILD its dump, and passes the dump as
 * {@code /Execute}. There is no launch attribute that accepts a prebuilt {@code .epf}, and adding
 * one outside EDT would not help: the debugger maps breakpoints through the external object's
 * PROJECT ({@code RuntimeDebugClientTarget.getExternalObjectProject}), so a file with no sources in
 * the workspace could be executed but never stepped through. Import such a file into an
 * external-objects project first.</p>
 *
 * <h2>The silent-success trap this class exists to close</h2>
 *
 * <p>When the object cannot be resolved, or the project's dump generation is switched off, the
 * delegate does NOT fail the launch - it writes to the EDT log and starts the session with no
 * {@code /Execute} at all. The caller would see a perfectly successful launch in which the
 * processor simply never ran. So everything the delegate will need is verified HERE, before the
 * launch, and a failure is a refusal with a reason.</p>
 */
public final class LaunchOverrides
{
    /** How many sibling names a "not found" refusal lists before it stops. */
    private static final int MAX_LISTED_OBJECTS = 20;

    private final String startupOption;

    private final String externalObjectProjectName;

    private final String externalObjectName;

    private LaunchOverrides(String startupOption, String externalObjectProjectName,
        String externalObjectName)
    {
        this.startupOption = startupOption;
        this.externalObjectProjectName = externalObjectProjectName;
        this.externalObjectName = externalObjectName;
    }

    /**
     * The overrides a caller asked for.
     *
     * <p>Takes VALUES, not the argument map: the wire names of the parameters belong to the tool
     * that declares them (and its schema/execute parity scan reads only that tool's own source),
     * while this class owns what the values MEAN.</p>
     *
     * @param startupOption the {@code /C} value, may be {@code null}
     * @param externalObjectProjectName the external-objects project, may be {@code null}
     * @param externalObjectName the object inside it, may be {@code null}
     * @return the overrides (possibly {@link #isEmpty() empty})
     */
    public static LaunchOverrides of(String startupOption, String externalObjectProjectName,
        String externalObjectName)
    {
        return new LaunchOverrides(startupOption, externalObjectProjectName, externalObjectName);
    }

    /** @return the {@code /C} startup option, or {@code null} / blank when unset. */
    public String startupOption()
    {
        return startupOption;
    }

    /** @return the external-objects project name, or {@code null} / blank when unset. */
    public String externalObjectProjectName()
    {
        return externalObjectProjectName;
    }

    /** @return the external object name, or {@code null} / blank when unset. */
    public String externalObjectName()
    {
        return externalObjectName;
    }

    /**
     * Whether a value is absent or whitespace-only - the same emptiness test the whole class uses.
     *
     * @param value the value to test
     * @return {@code true} when there is nothing to apply
     */
    public static boolean blank(String value)
    {
        return isBlank(value);
    }

    /**
     * Whether the caller asked for no override at all - the launch then proceeds on the saved
     * configuration exactly as before.
     *
     * @return {@code true} when every override is absent or blank
     */
    public boolean isEmpty()
    {
        return isBlank(startupOption) && isBlank(externalObjectProjectName)
            && isBlank(externalObjectName);
    }

    /**
     * Checks everything about the ARGUMENTS themselves, before any launch machinery runs.
     *
     * <p>Deliberately early. The launch paths that consume the result may terminate an existing
     * client session and update the infobase on their way to the launch, and a typo in an object
     * name must not cost the caller either of those: a malformed or unresolvable request is
     * refused while nothing has happened yet. What is left for {@link Prepared#applyTo} is only
     * what genuinely needs the resolved configuration - the Attach refusal - and the stamping.</p>
     *
     * @return the prepared overrides, or a ready error JSON inside them
     */
    public Prepared prepare()
    {
        if (isEmpty())
        {
            return new Prepared(this, null, null);
        }
        boolean hasProject = !isBlank(externalObjectProjectName);
        boolean hasObject = !isBlank(externalObjectName);
        if (hasProject != hasObject)
        {
            return new Prepared(this, null, ToolResult.error(
                "externalObjectProjectName and externalObjectName go together: " //$NON-NLS-1$
                    + (hasProject ? "externalObjectName is missing" //$NON-NLS-1$
                        : "externalObjectProjectName is missing") //$NON-NLS-1$
                    + ". An external object is addressed by its NAME inside an external-objects " //$NON-NLS-1$
                    + "PROJECT (list them with list_projects / get_metadata_objects), never by a " //$NON-NLS-1$
                    + "path to a built .epf.").toJson()); //$NON-NLS-1$
        }
        if (!hasObject)
        {
            return new Prepared(this, null, null);
        }
        Resolution resolved = resolveExternalObject();
        return new Prepared(this, resolved.object, resolved.errorJson);
    }

    /**
     * Applies prepared overrides to the resolved configuration.
     *
     * @param config the resolved saved configuration
     * @param isAttach whether {@code config} is an Attach configuration
     * @param externalObject the object resolved by {@link #prepare()}, or {@code null}
     * @return the outcome: either the configuration to launch, or a ready error JSON
     */
    private Applied applyTo(ILaunchConfiguration config, boolean isAttach, MdObject externalObject)
    {
        if (isEmpty())
        {
            return Applied.ok(config);
        }
        // Only the runtime-client delegate reads these attributes. On an Attach configuration they
        // would be stored and ignored, and the caller would be left believing the processor ran -
        // the same silent success this class exists to prevent, one layer up.
        if (isAttach)
        {
            return Applied.error("startupOption / externalObjectName apply to a RUNTIME-CLIENT " //$NON-NLS-1$
                + "launch, and '" + config.getName() + "' is an Attach configuration, which " //$NON-NLS-1$ //$NON-NLS-2$
                + "ignores them (it attaches to an already-running server rather than starting a " //$NON-NLS-1$
                + "client). Name a runtime-client configuration, or drop these arguments."); //$NON-NLS-1$
        }

        try
        {
            ILaunchConfigurationWorkingCopy workingCopy = config.getWorkingCopy();
            if (!isBlank(startupOption))
            {
                workingCopy.setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, startupOption);
            }
            if (externalObject != null)
            {
                workingCopy.setAttribute(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_PROJECT_NAME,
                    externalObjectProjectName);
                workingCopy.setAttribute(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_NAME,
                    externalObjectName);
                // Spelled exactly as EDT's ExternalObjectHelper.getClassName does, because the
                // delegate re-resolves the object by string-comparing this value.
                workingCopy.setAttribute(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_TYPE,
                    externalObject.getClass().getName());
            }
            // NOT doSave(): a working copy launches like any configuration, and saving would
            // rewrite the user's stored launch configuration with this one call's arguments.
            return Applied.ok(workingCopy);
        }
        catch (Exception e) // NOSONAR a broken working copy must surface as a tool error
        {
            return Applied.error("Could not prepare the launch overrides for '" + config.getName() //$NON-NLS-1$
                + "': " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves {@link #externalObjectName} in {@link #externalObjectProjectName} and verifies that
     * the launch will actually be able to build its dump.
     *
     * @return the resolved object, or an error JSON
     */
    private Resolution resolveExternalObject()
    {
        ProjectContext.ConfigurationResult root =
            ProjectContext.resolveMetadataRoot(externalObjectProjectName);
        if (!root.ok())
        {
            return Resolution.error(root.errorJson());
        }
        MetadataScope scope = root.scope();
        if (!scope.isExternalObjects())
        {
            return Resolution.error(ToolResult.error("Project '" + externalObjectProjectName //$NON-NLS-1$
                + "' is not an external-objects project, so it holds no external data processors " //$NON-NLS-1$
                + "or reports to run. externalObjectProjectName names the project whose NATURE is " //$NON-NLS-1$
                + "external objects; the configuration being debugged stays in projectName.").toJson()); //$NON-NLS-1$
        }

        Resolution named = matchByName(scope);
        if (named.errorJson != null)
        {
            return named;
        }

        String dumpRefusal = dumpRefusalOrNull(root);
        if (dumpRefusal != null)
        {
            return Resolution.error(ToolResult.error(dumpRefusal).toJson());
        }
        return named;
    }

    /**
     * Finds the requested object, keyed the way the PLATFORM keys it.
     *
     * <p>EDT's {@code ExternalObjectHelper.getExternalObject} filters on name AND type, and the
     * launch stores the type as its own attribute - so (name, type) is the key, not the name. A
     * data processor and a report are separate roots in separate folders and nothing stops them
     * sharing a programmatic name; matching on the name alone would then pick whichever came
     * first out of the model and run the other object under the requested name.</p>
     *
     * <p>So a bare name is accepted only while it is UNAMBIGUOUS, and a caller who hits the
     * collision qualifies it - {@code ExternalDataProcessor.Runner} - which goes through the same
     * bilingual resolver every other tool uses, Russian type tokens included.</p>
     *
     * @param scope the external-objects project's scope
     * @return the matched object, or the refusal explaining which way it failed
     */
    private Resolution matchByName(MetadataScope scope)
    {
        int dot = externalObjectName.indexOf('.');
        if (dot > 0)
        {
            String token = externalObjectName.substring(0, dot);
            String bare = externalObjectName.substring(dot + 1);
            if (MetadataScope.externalEClassName(token) == null)
            {
                return Resolution.error(ToolResult.error("'" + token + "' is not an external " //$NON-NLS-1$ //$NON-NLS-2$
                    + "object kind. Qualify the name with ExternalDataProcessor or ExternalReport " //$NON-NLS-1$
                    + "(the Russian tokens work too), or pass the bare name when only one object " //$NON-NLS-1$
                    + "bears it.").toJson()); //$NON-NLS-1$
            }
            MdObject qualified = scope.findObject(token, bare);
            if (qualified == null)
            {
                return Resolution.error(ToolResult.error("External object not found: '" + bare //$NON-NLS-1$
                    + "' of kind '" + token + "' in project '" + externalObjectProjectName //$NON-NLS-1$ //$NON-NLS-2$
                    + "'." + availableSuffix(namesOf(scope))).toJson()); //$NON-NLS-1$
            }
            return Resolution.ok(qualified);
        }

        List<MdObject> matches = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MdObject object : scope.allExternalObjects())
        {
            String name = object.getName();
            if (name == null)
            {
                continue;
            }
            names.add(name);
            if (name.equals(externalObjectName))
            {
                matches.add(object);
            }
        }
        if (matches.isEmpty())
        {
            return Resolution.error(ToolResult.error("External object not found: '" //$NON-NLS-1$
                + externalObjectName + "' in project '" + externalObjectProjectName + "'." //$NON-NLS-1$ //$NON-NLS-2$
                + availableSuffix(names)).toJson());
        }
        if (matches.size() > 1)
        {
            return Resolution.error(ToolResult.error("'" + externalObjectName + "' names " //$NON-NLS-1$ //$NON-NLS-2$
                + matches.size() + " external objects in project '" + externalObjectProjectName //$NON-NLS-1$
                + "' - a data processor and a report may share a programmatic name, and the two " //$NON-NLS-1$
                + "are launched differently. Qualify it: " + qualifiedForms(matches) + ".").toJson()); //$NON-NLS-1$
        }
        return Resolution.ok(matches.get(0));
    }

    /**
     * Every external object name in the project, for a not-found refusal.
     *
     * @param scope the external-objects project's scope
     * @return the names, unsorted
     */
    private static List<String> namesOf(MetadataScope scope)
    {
        List<String> names = new ArrayList<>();
        for (MdObject object : scope.allExternalObjects())
        {
            if (object.getName() != null)
            {
                names.add(object.getName());
            }
        }
        return names;
    }

    /**
     * The qualified addresses of same-named objects, so the ambiguity refusal hands back values
     * the caller can paste straight back into {@code externalObjectName}.
     *
     * @param matches the objects sharing a name
     * @return e.g. {@code "ExternalDataProcessor.Runner or ExternalReport.Runner"}
     */
    private static String qualifiedForms(List<MdObject> matches)
    {
        List<String> forms = new ArrayList<>();
        for (MdObject object : matches)
        {
            forms.add(object.eClass().getName() + "." + object.getName()); //$NON-NLS-1$
        }
        forms.sort(String::compareTo);
        return String.join(" or ", forms); //$NON-NLS-1$
    }

    /**
     * Why the launch would not be able to build the object's dump, or {@code null} when it can.
     *
     * <p>This is the pre-check for the silent success: EDT's delegate asks the same service and,
     * on a negative answer, logs and launches WITHOUT {@code /Execute}.</p>
     *
     * @param root the resolved external-objects project
     * @return the refusal text, or {@code null}
     */
    private static String dumpRefusalOrNull(ProjectContext.ConfigurationResult root)
    {
        return dumpRefusalOrNull(ExternalObjectDumpSupport.resolveDumpSupport(), root.project());
    }

    /**
     * The same question with the service supplied - the seam the unit tests drive, because the
     * live one is pulled out of EDT's Guice injector and cannot be stood up headlessly. This is
     * the branch that decides whether a launch runs the processor or silently does not, so it is
     * worth testing rather than reading.
     *
     * @param support EDT's dump support, or {@code null} when it could not be resolved
     * @param project the external-objects project
     * @return the refusal text, or {@code null} when the dump can be built
     */
    static String dumpRefusalOrNull(IExternalObjectDumpSupport support, IProject project)
    {
        if (support == null)
        {
            return "Cannot verify that EDT can build the external object's .epf: the " //$NON-NLS-1$
                + "platform-services dump service is unavailable. Without that check the session " //$NON-NLS-1$
                + "could start with the processor silently not running, so the launch is refused " //$NON-NLS-1$
                + "rather than started blind."; //$NON-NLS-1$
        }
        if (!support.isEnabled(project))
        {
            return "External object dump generation is switched OFF for project '" //$NON-NLS-1$
                + project.getName() + "', so EDT would start the session WITHOUT running " //$NON-NLS-1$
                + "the processor (its launch path builds the .epf through that setting and only " //$NON-NLS-1$
                + "logs when it is off). Turn it on in the project's properties, or run the " //$NON-NLS-1$
                + "processor from a session you start yourself. Note build_external_objects is " //$NON-NLS-1$
                + "unaffected by this setting - it dumps directly."; //$NON-NLS-1$
        }
        IStatus validation = support.validateDumpGeneration(project);
        if (validation != null && !validation.isOK())
        {
            return "EDT cannot build the external object's .epf for project '" //$NON-NLS-1$
                + project.getName() + "': " + validation.getMessage(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The " Available: a, b, c" tail of a not-found refusal, bounded so a large project does not
     * turn one error into a listing.
     *
     * @param names every external object name in the project
     * @return the suffix, empty when there is nothing to list
     */
    private static String availableSuffix(List<String> names)
    {
        if (names.isEmpty())
        {
            return " The project declares no external objects."; //$NON-NLS-1$
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        StringBuilder sb = new StringBuilder(" Available: "); //$NON-NLS-1$
        int shown = Math.min(MAX_LISTED_OBJECTS, sorted.size());
        for (int i = 0; i < shown; i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(sorted.get(i));
        }
        if (sorted.size() > shown)
        {
            sb.append(" and ").append(sorted.size() - shown).append(" more"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.append('.').toString();
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Overrides whose arguments have been checked, ready to stamp onto a configuration.
     *
     * <p>Exists so the two halves of the work happen at the two different points they belong to:
     * argument validation before any launch machinery, stamping right before the launch.</p>
     */
    public static final class Prepared
    {
        private final LaunchOverrides overrides;

        private final MdObject externalObject;

        /** A ready error JSON when the arguments do not hold up, else {@code null}. */
        public final String errorJson;

        /**
         * Package-private rather than private: the test fragment builds one directly with an
         * already-resolved object, because resolving a real one needs a live workspace while the
         * STAMPING it feeds is pure and worth pinning.
         *
         * @param overrides what the caller asked for
         * @param externalObject the resolved object, or {@code null}
         * @param errorJson the refusal, or {@code null}
         */
        Prepared(LaunchOverrides overrides, MdObject externalObject, String errorJson)
        {
            this.overrides = overrides;
            this.externalObject = externalObject;
            this.errorJson = errorJson;
        }

        /**
         * Stamps the overrides onto a working copy of {@code config}.
         *
         * @param config the resolved saved configuration
         * @param isAttach whether {@code config} is an Attach configuration
         * @return the configuration to launch, or an error
         */
        public Applied applyTo(ILaunchConfiguration config, boolean isAttach)
        {
            return overrides.applyTo(config, isAttach, externalObject);
        }
    }

    /** The outcome of {@link Prepared#applyTo}: a configuration to launch, or an error. */
    public static final class Applied
    {
        /** The configuration to launch - the input one, or a stamped working copy. */
        public final ILaunchConfiguration config;

        /** A ready {@code ToolResult.error(...).toJson()}, or {@code null}. */
        public final String errorJson;

        private Applied(ILaunchConfiguration config, String errorJson)
        {
            this.config = config;
            this.errorJson = errorJson;
        }

        static Applied ok(ILaunchConfiguration config)
        {
            return new Applied(config, null);
        }

        static Applied error(String message)
        {
            return new Applied(null, ToolResult.error(message).toJson());
        }
    }

    /** Internal: a resolved external object, or the refusal explaining why there is none. */
    private static final class Resolution
    {
        final MdObject object;

        final String errorJson;

        private Resolution(MdObject object, String errorJson)
        {
            this.object = object;
            this.errorJson = errorJson;
        }

        static Resolution ok(MdObject object)
        {
            return new Resolution(object, null);
        }

        static Resolution error(String errorJson)
        {
            return new Resolution(null, errorJson);
        }
    }
}
