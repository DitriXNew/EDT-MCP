/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.EMap;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;

/**
 * Builds the root {@link Configuration} of a new configuration extension the way EDT's own "New
 * configuration extension" wizard builds it, and recognises that root in its file on disk (issue #706).
 * <p>
 * <b>The recipe.</b> The wizard ({@code ExtensionWizard.adopt}, an internal UI class) does not create the
 * root: it ADOPTS the base configuration's root through the exported
 * {@link IModelObjectAdopter#adopt(org.eclipse.emf.ecore.EObject, Version, org.eclipse.core.runtime.IProgressMonitor)}
 * and then overrides exactly the name, the comment, the synonym (cleared, then filled), the name prefix and
 * {@code keepMappingToExtendedConfigurationObjectsByIDs = true} (it also copies a wizard-generated uuid, which
 * is cosmetic and not copied here). Everything else comes from the adopter's participant for a configuration
 * root ({@code ConfigurationAdopterParticipant}): the {@code ConfigurationExtension} block with the property
 * states EDT expects (default run mode, purposes, interface and platform compatibility modes Checked, both
 * command interfaces Extended, default roles Extended above 8.3.13), the base's script variant, the extension
 * compatibility mode equal to the base's compatibility mode, purpose Customization, the base's default
 * language adopted and linked to it by ID, and the contained objects. A root made by the model object factory
 * instead carries none of that and serializes properties only a configuration has - the #706 defect.
 * <p>
 * Two parameters of {@code create_project} go beyond the wizard and are applied last: the purpose (the wizard
 * keeps Customization) and an explicit extension compatibility mode (the wizard always keeps the base's). An
 * explicit mode of 8.3.13 or lower over a base above 8.3.13 is refused before anything is adopted: the adopter
 * derives the default-roles state from the BASE mode, and that pair is one EDT's wizard never produces.
 * <p>
 * The wizard's other related models are not part of the root: the two command interface files
 * ({@link CommandInterfaceSupport#ensureConfigurationCommandInterfaces}), a default role, and v8codestyle's
 * module templates.
 */
public final class ExtensionRootBuilder
{
    /** Project-relative path of a configuration root's file. */
    public static final String ROOT_FILE = "src/Configuration/Configuration.mdo"; //$NON-NLS-1$

    /**
     * Project-relative paths of the two command interface files EDT's new-extension wizard creates beside the root
     * ({@link CommandInterfaceSupport#ensureConfigurationCommandInterfaces}), where EDT saves them.
     */
    public static final List<String> COMMAND_INTERFACE_FILES = List.of(
        "src/Configuration/CommandInterface.cmi", "src/Configuration/MainSectionCommandInterface.cmi"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Top-object FQN of a configuration's root. */
    private static final String CONFIGURATION_FQN = "Configuration"; //$NON-NLS-1$

    /**
     * The serialized type of the root's block of property states. {@code mdclassExtension} is the prefix EDT
     * declares for the mdclass extension namespace on every extension {@code .mdo}.
     */
    static final String EXTENSION_BLOCK_MARK = "xsi:type=\"mdclassExtension:ConfigurationExtension\""; //$NON-NLS-1$

    /** The serialized mapping flag; EDT writes nothing for {@code false}, its default. */
    static final String KEEP_MAPPING_MARK =
        "<keepMappingToExtendedConfigurationObjectsByIDs>true</keepMappingToExtendedConfigurationObjectsByIDs>"; //$NON-NLS-1$

    /**
     * The part of the look's budget it keeps from EDT's export wait for the root file ({@link #confirmOnDisk}): the wait
     * is given the rest, so a wait that used all it was given still leaves the root file a bounded look after it - a
     * queue EDT did not drain in time cannot alone keep a root that is on disk from being confirmed - and the look gives
     * it that look within its budget, not past it.
     */
    static final long FIRST_LOOK_MIN_MS = 1_000L;

    private ExtensionRootBuilder()
    {
        // utility class
    }

    // ===== the root ===================================================================================

    /** What the caller asks for, besides the base. */
    public static final class Spec
    {
        final String baseProjectName;
        final String name;
        final String prefix;
        final ConfigurationExtensionPurpose purpose;
        final CompatibilityMode compatibilityMode;
        final String comment;
        final String synonym;

        /**
         * @param baseProjectName the base project, named by every refusal
         * @param name the extension's programmatic name
         * @param prefix the name prefix, kept as given ({@code ""} included)
         * @param purpose the purpose, or {@code null} to keep the adopter's Customization
         * @param compatibilityMode an explicit extension compatibility mode, or {@code null} for the base's
         * @param comment the comment, or {@code null}/empty for none: the root's comment is then {@code null}, what EDT's
         *     wizard writes for an untouched comment field, whatever the adopted root carried
         * @param synonym the synonym, or {@code null}/empty to use {@code name}
         */
        public Spec(String baseProjectName, String name, String prefix, ConfigurationExtensionPurpose purpose, // NOSONAR the wizard's overrides plus this tool's two parameters, one each
            CompatibilityMode compatibilityMode, String comment, String synonym)
        {
            this.baseProjectName = baseProjectName;
            this.name = name;
            this.prefix = prefix;
            this.purpose = purpose;
            this.compatibilityMode = compatibilityMode;
            this.comment = comment;
            this.synonym = synonym;
        }
    }

    /** The built root, or why none was built; exactly one of {@link #root} and {@link #error} is non-null. */
    public static final class Result
    {
        /** The adopted root with the wizard's overrides applied, detached; {@code null} on error. */
        public final Configuration root;
        /** The actionable refusal (nothing was created), or {@code null}. */
        public final String error;
        /** Whether the synonym was written (a language code was found). */
        public final boolean synonymApplied;
        /** The effective extension compatibility mode. */
        public final CompatibilityMode compatibilityMode;
        /** The base configuration's compatibility mode. */
        public final CompatibilityMode baseCompatibilityMode;
        /** The script variant the root inherited from the base. */
        public final ScriptVariant scriptVariant;
        /** The warning for an explicit mode above the base's, or {@code null}. */
        public final String compatibilityNote;
        /** What a file of {@link #root} must show to be its file ({@link RootIdentity#of}); {@code null} on error. */
        public final RootIdentity identity;

        private Result(Configuration root, String error, boolean synonymApplied, CompatibilityMode compatibilityMode, // NOSONAR one immutable value per field
            CompatibilityMode baseCompatibilityMode, ScriptVariant scriptVariant, String compatibilityNote)
        {
            this.root = root;
            this.error = error;
            this.synonymApplied = synonymApplied;
            this.compatibilityMode = compatibilityMode;
            this.baseCompatibilityMode = baseCompatibilityMode;
            this.scriptVariant = scriptVariant;
            this.compatibilityNote = compatibilityNote;
            this.identity = root != null ? RootIdentity.of(root) : null;
        }

        static Result failure(String error)
        {
            return new Result(null, error, false, null, null, null, null);
        }
    }

    /**
     * Reads the base root in a READ transaction of the base's model and builds the new root from it with
     * {@link #build}. A missing adopter is refused before the model is touched; a read that fails is a
     * refusal too. Nothing is created either way.
     *
     * @param baseModel the base project's model
     * @param adopter EDT's model object adopter, or {@code null} when the service is not available
     * @param version the base project's platform version, which EDT's wizard adopts with
     * @param spec what the caller asks for
     * @return the root or the refusal
     */
    public static Result buildFromBase(IBmModel baseModel, IModelObjectAdopter adopter, Version version, Spec spec)
    {
        if (adopter == null)
        {
            return Result.failure(adopterUnavailable(spec.baseProjectName));
        }
        if (baseModel == null)
        {
            return Result.failure(baseNotLoaded(spec.baseProjectName));
        }
        try
        {
            // The adopter only reads the base and builds a DETACHED copy holding enum and uuid values and a new
            // language - no reference into the base - so the copy outlives this read transaction.
            return BmTransactions.read(baseModel, "create_project: adopt the base root", (tx, monitor) -> { //$NON-NLS-1$
                IBmObject top = tx.getTopObjectByFqn(CONFIGURATION_FQN);
                return build(adopter, top instanceof Configuration ? (Configuration)top : null, version, spec);
            });
        }
        catch (RuntimeException e)
        {
            return Result.failure("Could not read the root of base project '" + spec.baseProjectName + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + reason(e) + ". Nothing was created."); //$NON-NLS-1$
        }
    }

    /**
     * Adopts {@code baseRoot} and applies the wizard's overrides, then this tool's purpose and explicit
     * compatibility mode. Never touches the extension block, the languages, the default language, the native
     * compatibility mode, the script variant or the contained objects the adopter made.
     *
     * @param adopter EDT's model object adopter, or {@code null}
     * @param baseRoot the base root, read in the caller's transaction, or {@code null} when not loaded
     * @param version the base project's platform version
     * @param spec what the caller asks for
     * @return the root or the refusal
     */
    public static Result build(IModelObjectAdopter adopter, Configuration baseRoot, Version version, Spec spec)
    {
        if (adopter == null)
        {
            return Result.failure(adopterUnavailable(spec.baseProjectName));
        }
        if (baseRoot == null)
        {
            return Result.failure(baseNotLoaded(spec.baseProjectName));
        }
        CompatibilityMode baseMode = baseRoot.getCompatibilityMode();
        String refusal = compatibilityRefusal(spec, baseMode);
        if (refusal != null)
        {
            return Result.failure(refusal);
        }
        // The synonym is keyed by the language CODE of the base's default language - never its name.
        String languageCode = MetadataLanguageUtils.resolveLanguageCode(baseRoot, null);

        Configuration root;
        try
        {
            root = adopter.adopt(baseRoot, version, null);
        }
        catch (RuntimeException e)
        {
            return Result.failure(adopterRefused(spec.baseProjectName, reason(e)));
        }
        if (root == null)
        {
            return Result.failure(adopterRefused(spec.baseProjectName, "it returned no root")); //$NON-NLS-1$
        }

        // The wizard's overrides, in its order (ExtensionWizard.adopt). It writes the comment UNCONDITIONALLY, from its
        // placeholder model, whose comment stays null while the comment field is untouched: so a comment nobody asked
        // for is written as null - never left as whatever the adopted root carried.
        root.setName(spec.name);
        root.setComment(spec.comment != null && !spec.comment.isEmpty() ? spec.comment : null);
        boolean synonymApplied = applySynonym(root, languageCode, spec);
        root.setNamePrefix(spec.prefix);
        root.setKeepMappingToExtendedConfigurationObjectsByIDs(true);

        // This tool's own parameters.
        if (spec.purpose != null)
        {
            root.setConfigurationExtensionPurpose(spec.purpose);
        }
        if (spec.compatibilityMode != null)
        {
            root.setConfigurationExtensionCompatibilityMode(spec.compatibilityMode);
        }
        return new Result(root, null, synonymApplied, root.getConfigurationExtensionCompatibilityMode(), baseMode,
            root.getScriptVariant(), compatibilityNote(spec.compatibilityMode, baseMode));
    }

    /** Clears the synonym and writes the value under the language code; {@code false} when there is no code. */
    private static boolean applySynonym(Configuration root, String baseLanguageCode, Spec spec)
    {
        EMap<String, String> synonym = root.getSynonym();
        if (synonym == null)
        {
            return false;
        }
        synonym.clear();
        String code = baseLanguageCode != null ? baseLanguageCode : MetadataLanguageUtils.resolveLanguageCode(root, null);
        if (code == null)
        {
            return false;
        }
        synonym.put(code, spec.synonym != null && !spec.synonym.isEmpty() ? spec.synonym : spec.name);
        return true;
    }

    /**
     * Whether EDT's new-extension wizard creates a default role for a root of this extension compatibility
     * mode: above 8.3.13 only ({@code ConfigurationExtensionNewWizardRelatedModelsFactory}), the same threshold
     * at which the adopter marks the root's default roles Extended.
     *
     * @param extensionMode the extension compatibility mode, or {@code null}
     * @return {@code true} above 8.3.13
     */
    public static boolean wizardCreatesDefaultRole(CompatibilityMode extensionMode)
    {
        return extensionMode != null && !atMostDefaultRolesThreshold(extensionMode);
    }

    /**
     * Whether, at this platform version, EDT's adopter maps adopted objects to the base by NAME whatever the
     * extension root's {@code keepMappingToExtendedConfigurationObjectsByIDs} says: its copier writes an adopted
     * object's {@code extendedConfigurationObject} link only above 8.3.14 ({@code Version.V8_3_14.isLessThan(version)}).
     * The one copy of that rule, for both {@code create_project} and {@code adopt_metadata_object}.
     *
     * @param version the extension's platform version - its base's - or {@code null}
     * @return {@code true} at 8.3.14 or lower; {@code false} above it or when the version is unknown
     */
    public static boolean versionMapsByNameOnly(Version version)
    {
        return version != null && !Version.V8_3_14.isLessThan(version);
    }

    /** {@code mode <= 8.3.13}, as EDT's {@code ValidationUtil.checkCompatibilityMode(mode, 8.3.13)} decides it. */
    private static boolean atMostDefaultRolesThreshold(CompatibilityMode mode)
    {
        return mode.getValue() <= CompatibilityMode.VERSION8_313.getValue();
    }

    /** The refusal for an explicit mode at or below 8.3.13 over a base above it, or {@code null}. */
    private static String compatibilityRefusal(Spec spec, CompatibilityMode baseMode)
    {
        CompatibilityMode requested = spec.compatibilityMode;
        if (requested == null || baseMode == null || !atMostDefaultRolesThreshold(requested)
            || atMostDefaultRolesThreshold(baseMode))
        {
            return null;
        }
        return "compatibilityMode '" + requested.getName() + "' cannot be used over base project '" //$NON-NLS-1$ //$NON-NLS-2$
            + spec.baseProjectName + "', whose compatibility mode is '" + baseMode.getName() //$NON-NLS-1$
            + "': EDT's adopter derives the new root's default-roles state from the base's mode, which is above " //$NON-NLS-1$
            + "8.3.13, so an extension mode of 8.3.13 or lower over this base is a combination EDT's " //$NON-NLS-1$
            + "new-extension wizard never produces. Nothing was created. Omit compatibilityMode to use the " //$NON-NLS-1$
            + "base's mode, or pass one above 8.3.13."; //$NON-NLS-1$
    }

    /** The warning for an explicit mode above the base's, or {@code null}. */
    private static String compatibilityNote(CompatibilityMode requested, CompatibilityMode baseMode)
    {
        if (requested == null || baseMode == null || requested.getValue() <= baseMode.getValue())
        {
            return null;
        }
        String note = "compatibilityMode '" + requested.getName() + "' is above the base configuration's '" //$NON-NLS-1$ //$NON-NLS-2$
            + baseMode.getName() + "'. EDT's new-extension wizard always uses the base's mode, and whether the " //$NON-NLS-1$
            + "platform accepts an extension whose mode is above its configuration's was not verified: if " //$NON-NLS-1$
            + "applying the extension is refused, recreate it without compatibilityMode."; //$NON-NLS-1$
        if (atMostDefaultRolesThreshold(baseMode) && !atMostDefaultRolesThreshold(requested))
        {
            note += " The root's default-roles state follows the base's mode (8.3.13 or lower), not the requested one."; //$NON-NLS-1$
        }
        return note;
    }

    /**
     * The refusal when EDT's model object adopter is not available.
     *
     * @param baseProjectName the base project
     * @return the message
     */
    public static String adopterUnavailable(String baseProjectName)
    {
        return "Model object adopter service not available (the md.extension bundle may be inactive): a new " //$NON-NLS-1$
            + "extension's root is adopted from the root of base project '" + baseProjectName + "', as EDT's " //$NON-NLS-1$ //$NON-NLS-2$
            + "new-extension wizard does it, and cannot be built without it. Nothing was created. Retry once EDT " //$NON-NLS-1$
            + "has finished starting."; //$NON-NLS-1$
    }

    private static String baseNotLoaded(String baseProjectName)
    {
        return "The configuration model of base project '" + baseProjectName + "' is not loaded yet (the project " //$NON-NLS-1$ //$NON-NLS-2$
            + "may still be indexing). Nothing was created. Wait until the project is ready and retry."; //$NON-NLS-1$
    }

    private static String adopterRefused(String baseProjectName, String reason)
    {
        return "EDT's model object adopter refused to adopt the root of base project '" + baseProjectName //$NON-NLS-1$
            + "': " + reason + ". Nothing was created."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String reason(Throwable e)
    {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    // ===== the root on disk ===========================================================================

    /**
     * What a file must show to be the file of the root ONE create built, beyond the wizard's shape: the root's name, name
     * prefix, purpose and extension compatibility mode. Taken from the built root itself ({@link #of}), so it is what
     * EDT's create was handed. An earlier extension's {@code Configuration.mdo} - extension block and mapping flag
     * included - is not left in a new project's directory by a normal create: create_project refuses a project whose
     * directory already exists on disk ({@code CreateProjectTool.takenProjectRefusal}), which is where
     * {@code delete_project} without {@code deleteContent} leaves one. This identity is hardening beyond that refusal: a
     * file that shows another name, prefix, purpose or mode, or lacks one, is not confirmed. A file that shows the same
     * values but was not written by this create - written by hand, or left by a directory made after the refusal's check
     * - is not told apart (its synonym, comment and UUID are not compared).
     */
    public static final class RootIdentity
    {
        final String name;
        final String prefix;
        final ConfigurationExtensionPurpose purpose;
        final CompatibilityMode compatibilityMode;

        /**
         * @param name the root's name
         * @param prefix the root's name prefix; {@code null} or {@code ""} for none
         * @param purpose the root's purpose, or {@code null} when it has none
         * @param compatibilityMode the root's extension compatibility mode, or {@code null} when it has none
         */
        public RootIdentity(String name, String prefix, ConfigurationExtensionPurpose purpose,
            CompatibilityMode compatibilityMode)
        {
            this.name = name;
            this.prefix = prefix;
            this.purpose = purpose;
            this.compatibilityMode = compatibilityMode;
        }

        /**
         * @param root a built root (detached: read without a transaction)
         * @return what that root's file must show
         */
        public static RootIdentity of(Configuration root)
        {
            return new RootIdentity(root.getName(), root.getNamePrefix(), root.getConfigurationExtensionPurpose(),
                root.getConfigurationExtensionCompatibilityMode());
        }
    }

    /**
     * What a root file lacks to be the file of the root {@code expected} names: the wizard's shape - the
     * {@code ConfigurationExtension} block and the mapping flag set to {@code true} - then the root's name (the file's
     * FIRST {@code name} element: a language's comes later), its name prefix (none or an empty one for an empty prefix),
     * its purpose and its extension compatibility mode. Absence is no evidence for the purpose or the mode except where
     * EDT's writer omits the value: for an adopted root (an extension's), EDT's formatters ({@code MdExtXmlFormatter},
     * {@code MdXmlFormatter}) always write a CompatibilityMode-typed feature - the mode at its model's default (8.5.1)
     * too, as the EDT-written extension root of
     * the test fixture shows - and leave every other attribute to {@code XmlFormatter}, which writes it only when it is
     * set, that is, not its model's default; MdClass.xcore gives the purpose no default, so EMF's is its first literal,
     * Patch (the fixture's English configuration omits its scriptVariant, English, the same way). So only a Patch purpose
     * may be absent. Each value the file shows instead is said.
     *
     * @param text the file's text, or {@code null} when there is no file
     * @param expected the root this create built, or {@code null} when it is not known - then no file is its file
     * @return what was not found, in that order; empty when the file shows all of it
     */
    public static List<String> missingOnDisk(String text, RootIdentity expected)
    {
        if (text == null)
        {
            return Collections.singletonList("the file itself"); //$NON-NLS-1$
        }
        List<String> missing = new ArrayList<>(4);
        if (!text.contains(EXTENSION_BLOCK_MARK))
        {
            missing.add("the ConfigurationExtension block"); //$NON-NLS-1$
        }
        if (!text.contains(KEEP_MAPPING_MARK))
        {
            missing.add("keepMappingToExtendedConfigurationObjectsByIDs=true"); //$NON-NLS-1$
        }
        if (expected == null)
        {
            missing.add("the name and namePrefix of the root this create built (not known to the look)"); //$NON-NLS-1$
            return missing;
        }
        String name = elementText(text, "name"); //$NON-NLS-1$
        if (!String.valueOf(expected.name).equals(name))
        {
            missing.add(shows("name '" + expected.name + "'", name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String prefix = elementText(text, "namePrefix"); //$NON-NLS-1$
        if (expected.prefix == null || expected.prefix.isEmpty())
        {
            if (prefix != null && !prefix.isEmpty())
            {
                missing.add(shows("an empty namePrefix", prefix)); //$NON-NLS-1$
            }
        }
        else if (!expected.prefix.equals(prefix))
        {
            missing.add(shows("namePrefix '" + expected.prefix + "'", prefix)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (expected.purpose != null)
        {
            mustShow(missing, text, "configurationExtensionPurpose", expected.purpose.getLiteral(), //$NON-NLS-1$
                expected.purpose == ConfigurationExtensionPurpose.PATCH);
        }
        if (expected.compatibilityMode != null)
        {
            mustShow(missing, text, "configurationExtensionCompatibilityMode", //$NON-NLS-1$
                expected.compatibilityMode.getLiteral(), false);
        }
        return missing;
    }

    /**
     * Adds {@code element}'s expected value to {@code missing} when the file names another value for it, or names none
     * while EDT's writer would have written it ({@code mayBeOmitted} is {@code false}).
     */
    private static void mustShow(List<String> missing, String text, String element, String literal,
        boolean mayBeOmitted)
    {
        String value = elementText(text, element);
        if (value == null ? !mayBeOmitted : !value.equals(literal))
        {
            missing.add(shows(element + " '" + literal + "'", value)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** {@code what}, and what the file shows instead when it shows something. */
    private static String shows(String what, String found)
    {
        return found == null ? what : what + " (the file shows '" + found + "')"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The text of the first {@code <element>} of a root file, its XML entities resolved; {@code ""} for an empty one,
     * {@code null} when the file has none.
     */
    static String elementText(String text, String element)
    {
        String open = "<" + element + ">"; //$NON-NLS-1$ //$NON-NLS-2$
        String empty = "<" + element + "/>"; //$NON-NLS-1$ //$NON-NLS-2$
        int start = text.indexOf(open);
        int emptyAt = text.indexOf(empty);
        if (emptyAt >= 0 && (start < 0 || emptyAt < start))
        {
            return ""; //$NON-NLS-1$
        }
        if (start < 0)
        {
            return null;
        }
        int from = start + open.length();
        int end = text.indexOf("</" + element + ">", from); //$NON-NLS-1$ //$NON-NLS-2$
        if (end < 0)
        {
            return null;
        }
        return text.substring(from, end).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            .replace("&apos;", "'").replace("&amp;", "&"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Reads a root file; {@code null} means the file does not exist. */
    @FunctionalInterface
    public interface RootFileReader
    {
        /**
         * @return the file's text, or {@code null} when it does not exist
         * @throws IOException when it exists but cannot be read
         */
        String read() throws IOException;
    }

    /** What a bounded look at a new root's file - and at the files saved beside it - found. */
    public static final class RootOnDisk
    {
        /**
         * Whether one read of the root file showed the wizard's shape and this create's root ({@link #missingOnDisk});
         * the files beside it never change this.
         */
        public final boolean confirmed;
        /**
         * What the last completed read of the root file did not show - all of it when no read of it completed in time;
         * empty when confirmed.
         */
        public final List<String> missing;
        /** Whether the last completed read of the root file found it; {@code false} when no read of it completed. */
        public final boolean fileFound;
        /** How EDT's export queue answered - PENDING, not seen draining, when the budget left it no wait. */
        public final DiskExportState exportState;
        /** Why the last completed read of the root file failed, or {@code null}. */
        public final String readFailure;
        /**
         * Whether a read of the root file completed in time: {@code false} only when none did - each did not answer in
         * time, or was not started because nothing of the budget was left or an earlier read of the project's files had
         * not returned: reads that say nothing either way. A read that did not complete changes nothing that an
         * earlier, completed one found: the root is described by its last completed read, and the description says
         * when a later read did not complete ({@link #lastReadCompleted}). Always {@code true} once confirmed.
         */
        public final boolean readInTime;
        /**
         * Whether the root file's last read - the last one the look made while the root was not confirmed - completed
         * in time; {@code false} when it did not, or no read of it was made. When a read completed but a later one did
         * not, the root is described by a read made before that later one - not at the end of the budget - and
         * {@link #describe()} says so. Always {@code true} once confirmed.
         */
        public final boolean lastReadCompleted;
        /** How long the look was allowed to take. */
        public final long budgetMs;
        /**
         * The files looked for beside the root that no read of the look saw - not seen on disk, or not looked at in time;
         * empty when all were seen or none was asked.
         */
        public final List<String> missingFiles;

        private RootOnDisk(boolean confirmed, List<String> missing, boolean fileFound, DiskExportState exportState, // NOSONAR one immutable value per field
            String readFailure, boolean readInTime, boolean lastReadCompleted, long budgetMs,
            List<String> missingFiles)
        {
            this.confirmed = confirmed;
            this.missing = Collections.unmodifiableList(missing);
            this.fileFound = fileFound;
            this.exportState = exportState;
            this.readFailure = readFailure;
            this.readInTime = readInTime;
            this.lastReadCompleted = lastReadCompleted;
            this.budgetMs = budgetMs;
            this.missingFiles = Collections.unmodifiableList(missingFiles);
        }

        /** @return what the look saw, as a clause naming {@link #ROOT_FILE} */
        public String describe()
        {
            if (confirmed)
            {
                return ROOT_FILE + " shows the ConfigurationExtension block, " //$NON-NLS-1$
                    + "keepMappingToExtendedConfigurationObjectsByIDs=true and the name and namePrefix this create set"; //$NON-NLS-1$
            }
            if (!readInTime)
            {
                return ROOT_FILE + " could not be read in time (the read did not complete, or an earlier read of the " //$NON-NLS-1$
                    + "project's files had not returned)"; //$NON-NLS-1$
            }
            String found;
            if (readFailure != null)
            {
                found = ROOT_FILE + " could not be read (" + readFailure + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (!fileFound)
            {
                found = ROOT_FILE + " was not found on disk"; //$NON-NLS-1$
            }
            else
            {
                found = ROOT_FILE + " does not show " + String.join(" or ", missing) + " yet"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            // What a completed read found, made before a later read that did not complete: that later read is said too.
            return lastReadCompleted ? found : found + "; a later read of it did not complete"; //$NON-NLS-1$
        }
    }

    /**
     * Confirms a new root on disk, bounded: waits for EDT's export queue ({@code exportWait}), then reads the root file
     * until one read shows the wizard's shape and the root {@code expected} names ({@link #missingOnDisk}) - not the
     * wizard's shape alone, which a root file an earlier extension of that project name left on disk shows too - and it
     * is not read again after that - and looks, within the SAME budget, for
     * the files saved beside it - a new extension's two command interface files ({@link #COMMAND_INTERFACE_FILES}) -
     * until each has been seen once. The FILE is the evidence, not the queue: a drained queue also drains a write that
     * failed, so "drained" alone never confirms anything. Only the root decides {@link RootOnDisk#confirmed}; a file no
     * read saw is named in {@link RootOnDisk#missingFiles}, and a file whose check fails counts as not seen.
     * <p>
     * Every look at the file system is bounded, and is a read of its own: each round reads the root file while it is not
     * confirmed, then checks each file not seen yet, one after another, each read off the caller's thread through the one
     * bounded read of a project's files ({@link AdoptedModuleState#readBounded(AdoptedModuleState.BoundedRunner, String,
     * String, long, java.util.function.Supplier, Object)}), under the project's one claim on such reads, which the reads
     * of #743's module state lines share. A read that completed in time is an answer whatever a later read does: a
     * later read of the root that does not complete erases nothing it found, as the root is described by its last
     * completed read - with a note that a later read did not complete ({@link RootOnDisk#lastReadCompleted}) - and a
     * check of a file beside the root that does not answer discards neither the root's read nor
     * the check of another file. A read that did not complete in time - it did not answer, or it was not started
     * because nothing was left of the budget or an earlier read of the project's files had not returned - is no
     * evidence either way: the root reads as one that could not be read in time ({@link RootOnDisk#readInTime}) only
     * while no read of it completed, a file it was to check is not seen, and the poll goes on while the budget lasts. A
     * read that does not answer keeps its job, and the project's claim, until it answers or the claim lapses, and every
     * later read of the project's files - the root's too, while it is not confirmed - is refused at once meanwhile: a
     * file system that does not answer costs a project one hung job, not one per read.
     * <p>
     * The export wait is given the budget less {@link #FIRST_LOOK_MIN_MS}, which the look keeps for the root file: a wait
     * that used all it was given still leaves the file one bounded look - that second, less BoundedJob's grace (0.5s)
     * when the wait's own job had not started by its deadline. A budget no larger than that second leaves the wait
     * nothing: it is not asked, and the queue reads as {@link DiskExportState#PENDING}, not seen draining. Each read is
     * given what is left of the budget, and none starts with nothing left. So the whole look, the wait included, ends
     * within its budget - plus BoundedJob's grace for a last read whose job had not started by its deadline - with a wait
     * that keeps to what it is given but for that same grace, as BuildUtils' does. Never throws for a read or a check
     * that fails.
     *
     * @param projectName the project whose files are read: the key of the reads' claim
     * @param expected the root this create built ({@link Result#identity}); {@code null} confirms no file
     * @param exportWait the bounded wait for the project's export queue, given how long it may take
     * @param reader reads the root file
     * @param files the project-relative paths to look for beside the root; empty for none
     * @param fileExists whether a project-relative path is a file on disk ({@link #projectFileExists})
     * @param budgetMs how long the whole look may take, the export wait included
     * @param pollMs the pause between two rounds
     * @return what was found
     */
    public static RootOnDisk confirmOnDisk(String projectName, RootIdentity expected, // NOSONAR the project, the root, its file, the files beside it and the bounds, one each
        LongFunction<DiskExportState> exportWait, RootFileReader reader, List<String> files,
        Predicate<String> fileExists, long budgetMs, long pollMs)
    {
        return confirmOnDisk(BoundedJob::run, projectName, expected, exportWait, reader, files, fileExists, budgetMs,
            pollMs);
    }

    /**
     * {@link #confirmOnDisk(String, RootIdentity, LongFunction, RootFileReader, List, Predicate, long, long)} with what
     * runs each read under its deadline as a parameter: BoundedJob in production, a fake in a test. Package-visible for
     * tests.
     *
     * @param runner what runs a read under its deadline
     * @param projectName the project whose files are read: the key of the reads' claim
     * @param expected the root this create built; {@code null} confirms no file
     * @param exportWait the bounded wait for the project's export queue, given how long it may take
     * @param reader reads the root file
     * @param files the project-relative paths to look for beside the root; empty for none
     * @param fileExists whether a project-relative path is a file on disk
     * @param budgetMs how long the whole look may take, the export wait included
     * @param pollMs the pause between two rounds
     * @return what was found
     */
    static RootOnDisk confirmOnDisk(AdoptedModuleState.BoundedRunner runner, String projectName, // NOSONAR the runner, the project, the root, its file, the files beside it and the bounds, one each
        RootIdentity expected, LongFunction<DiskExportState> exportWait, RootFileReader reader, List<String> files,
        Predicate<String> fileExists, long budgetMs, long pollMs)
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        // The wait is given the budget less the second the look keeps for the root file; a budget no larger, nothing.
        long waitMs = budgetMs - FIRST_LOOK_MIN_MS;
        DiskExportState exportState = waitMs > 0 ? exportWait.apply(waitMs) : DiskExportState.PENDING;
        String rootJob = "Looking for the root of new extension " + projectName + " on disk"; //$NON-NLS-1$ //$NON-NLS-2$
        // What the look knows of the root is what its last completed read found. Until a read of it completes, nothing:
        // the file itself is what no read showed, and the root reads as not read in time.
        boolean rootConfirmed = false;
        List<String> missing = missingOnDisk(null, expected);
        boolean fileFound = false;
        String failure = null;
        boolean readInTime = false;
        boolean lastReadCompleted = false;
        List<String> notSeen = new ArrayList<>(files);
        // What the root's read of a round is given, measured once: the first round's after the wait, every later
        // round's by the guard that lets it start. Measured again at the read, a guard's 1ms could be 0 there, and the
        // round the guard let start would start no read.
        long rootShareMs = millisLeft(deadline);
        while (true)
        {
            if (!rootConfirmed)
            {
                // The root first, in a read of its own: no check of a file beside it shares that read's job.
                RootRead read = AdoptedModuleState.readBounded(runner, projectName, rootJob, rootShareMs,
                    () -> RootRead.of(reader), null);
                // Only a read that completed in time replaces what the look knows of the root. One that did not - most
                // often a later round's sliver of the budget, given up on before a worker ran it - found nothing either
                // way, and erases nothing that an earlier, completed read found; the answer still says it did not
                // complete, as what the look knows of the root is then older than that read.
                lastReadCompleted = read != null;
                if (read != null)
                {
                    missing = missingOnDisk(read.text, expected);
                    fileFound = read.text != null;
                    failure = read.failure;
                    rootConfirmed = missing.isEmpty();
                    readInTime = true;
                }
            }
            lookForFiles(runner, projectName, notSeen, fileExists, deadline);
            if (rootConfirmed && notSeen.isEmpty())
            {
                return new RootOnDisk(true, missing, true, exportState, null, true, true, budgetMs, notSeen);
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0)
            {
                return new RootOnDisk(rootConfirmed, missing, fileFound, exportState, failure, readInTime,
                    lastReadCompleted, budgetMs, notSeen);
            }
            try
            {
                Thread.sleep(Math.max(1L, Math.min(pollMs, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return new RootOnDisk(rootConfirmed, missing, fileFound, exportState, failure, readInTime,
                    lastReadCompleted, budgetMs, notSeen);
            }
            rootShareMs = millisLeft(deadline);
            if (rootShareMs <= 0)
            {
                // The pause used what was left: no round starts with nothing left of the budget.
                return new RootOnDisk(rootConfirmed, missing, fileFound, exportState, failure, readInTime,
                    lastReadCompleted, budgetMs, notSeen);
            }
        }
    }

    /** What is left until {@code deadline}, a {@link System#nanoTime()} reading, in whole milliseconds; 0 or less: none. */
    private static long millisLeft(long deadline)
    {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    /**
     * Checks each file of {@code notSeen}, each in a bounded read of its own given what is left of the budget, one after
     * another, and drops from {@code notSeen} each file a check saw: a check that answered counts whatever the check after
     * it does. Starts no check once the caller's thread is interrupted: the call is being cancelled.
     *
     * @param runner what runs a check under its deadline
     * @param projectName the project whose files are checked: the key of the checks' claim
     * @param notSeen the files not seen yet; the ones a check sees now are removed
     * @param fileExists whether a project-relative path is a file on disk
     * @param deadline when the look's budget ends, a {@link System#nanoTime()} reading
     */
    private static void lookForFiles(AdoptedModuleState.BoundedRunner runner, String projectName, List<String> notSeen,
        Predicate<String> fileExists, long deadline)
    {
        for (String file : new ArrayList<>(notSeen))
        {
            if (Thread.currentThread().isInterrupted())
            {
                return;
            }
            Boolean seen = AdoptedModuleState.readBounded(runner, projectName,
                "Looking for " + file + " of new extension " + projectName + " on disk", millisLeft(deadline), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                () -> seenOnDisk(file, fileExists), null);
            if (Boolean.TRUE.equals(seen))
            {
                notSeen.remove(file);
            }
        }
    }

    /**
     * What one read of the root file found, built on the read's own job thread: a read handed to the caller completed in
     * time, and one that did not is {@code null}.
     */
    private static final class RootRead
    {
        /** The root file's text, or {@code null}: not there, or not readable. */
        final String text;
        /** Why the root file could not be read, or {@code null}. */
        final String failure;

        private RootRead(String text, String failure)
        {
            this.text = text;
            this.failure = failure;
        }

        /**
         * The read itself, unbounded: it runs inside the bounded job. Never throws for a read that fails: a root that
         * cannot be read carries why.
         *
         * @param reader reads the root file
         * @return what the read found
         */
        static RootRead of(RootFileReader reader)
        {
            try
            {
                return new RootRead(reader.read(), null);
            }
            catch (IOException | RuntimeException e)
            {
                return new RootRead(null, reason(e));
            }
        }
    }

    /** Whether {@code fileExists} sees {@code file} - the check itself, unbounded; one that throws does not see it. */
    private static boolean seenOnDisk(String file, Predicate<String> fileExists)
    {
        try
        {
            return fileExists.test(file);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Whether a project-relative path is a regular file under the project's location, checked straight on the file
     * system, past the workspace's resource cache - as {@link #readRootFile} reads the root. A project with no local
     * location has no files. The check itself is unbounded - a stat of the file, which blocks on a file system that does
     * not answer just as a read does - so the look makes it only inside a bounded read of its own
     * ({@link #confirmOnDisk}).
     *
     * @param project the project
     * @return the check
     */
    public static Predicate<String> projectFileExists(IProject project)
    {
        IPath location = project.getLocation();
        return relativePath -> location != null
            && Files.isRegularFile(Paths.get(location.toOSString(), relativePath.split("/"))); //$NON-NLS-1$
    }

    /**
     * Reads a project's root file straight from the file system, past the workspace's resource cache. The read itself
     * is unbounded - one synchronous {@link Files#readAllBytes}, which blocks on a file system that does not answer -
     * so the look makes it only inside a bounded read of its own, off the caller's thread ({@link #confirmOnDisk}).
     *
     * @param project the project
     * @return the text, or {@code null} when the project has no local location or the file does not exist
     * @throws IOException when the file exists but cannot be read
     */
    public static String readRootFile(IProject project) throws IOException
    {
        IPath location = project.getLocation();
        if (location == null)
        {
            return null;
        }
        Path file = Paths.get(location.toOSString(), ROOT_FILE.split("/")); //$NON-NLS-1$
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
