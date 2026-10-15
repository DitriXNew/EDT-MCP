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
import java.util.function.Predicate;
import java.util.function.Supplier;

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
     * What a root file lacks of the wizard's shape: the {@code ConfigurationExtension} block and the
     * mapping flag set to {@code true}.
     *
     * @param text the file's text, or {@code null} when there is no file
     * @return what was not found, in that order; empty when the file shows both
     */
    public static List<String> missingOnDisk(String text)
    {
        if (text == null)
        {
            return Collections.singletonList("the file itself"); //$NON-NLS-1$
        }
        List<String> missing = new ArrayList<>(2);
        if (!text.contains(EXTENSION_BLOCK_MARK))
        {
            missing.add("the ConfigurationExtension block"); //$NON-NLS-1$
        }
        if (!text.contains(KEEP_MAPPING_MARK))
        {
            missing.add("keepMappingToExtendedConfigurationObjectsByIDs=true"); //$NON-NLS-1$
        }
        return missing;
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
        /** Whether one read of the root file showed the wizard's shape; the files beside it never change this. */
        public final boolean confirmed;
        /** What the last read did not show; empty when confirmed. */
        public final List<String> missing;
        /** Whether the last read found the file. */
        public final boolean fileFound;
        /** How EDT's export queue answered. */
        public final DiskExportState exportState;
        /** Why the last read failed, or {@code null}. */
        public final String readFailure;
        /** How long the look was allowed to take. */
        public final long budgetMs;
        /** The files looked for beside the root that the look did not see; empty when all were seen or none was asked. */
        public final List<String> missingFiles;

        private RootOnDisk(boolean confirmed, List<String> missing, boolean fileFound, DiskExportState exportState, // NOSONAR one immutable value per field
            String readFailure, long budgetMs, List<String> missingFiles)
        {
            this.confirmed = confirmed;
            this.missing = Collections.unmodifiableList(missing);
            this.fileFound = fileFound;
            this.exportState = exportState;
            this.readFailure = readFailure;
            this.budgetMs = budgetMs;
            this.missingFiles = Collections.unmodifiableList(missingFiles);
        }

        /** @return what the look saw, as a clause naming {@link #ROOT_FILE} */
        public String describe()
        {
            if (confirmed)
            {
                return ROOT_FILE + " shows the ConfigurationExtension block and " //$NON-NLS-1$
                    + "keepMappingToExtendedConfigurationObjectsByIDs=true"; //$NON-NLS-1$
            }
            if (readFailure != null)
            {
                return ROOT_FILE + " could not be read (" + readFailure + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!fileFound)
            {
                return ROOT_FILE + " was not found on disk"; //$NON-NLS-1$
            }
            return ROOT_FILE + " does not show " + String.join(" or ", missing) + " yet"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /**
     * Confirms a new root on disk, bounded: waits for EDT's export queue ({@code exportWait}, which the caller
     * bounds by {@code budgetMs} too), then reads the file until it shows the wizard's shape or the budget is
     * spent. The FILE is the evidence, not the queue: a drained queue also drains a write that failed, so
     * "drained" alone never confirms anything. Never throws for a read that fails. The budget bounds the export
     * wait and the pauses between reads, not one read: a read the file system never answers blocks the caller
     * (see {@link #readRootFile}).
     *
     * @param exportWait the bounded wait for the project's export queue
     * @param reader reads the root file
     * @param budgetMs how long the whole look may take
     * @param pollMs the pause between two reads
     * @return what was found
     */
    public static RootOnDisk confirmOnDisk(Supplier<DiskExportState> exportWait, RootFileReader reader, long budgetMs,
        long pollMs)
    {
        return confirmOnDisk(exportWait, reader, Collections.emptyList(), path -> true, budgetMs, pollMs);
    }

    /**
     * {@link #confirmOnDisk(Supplier, RootFileReader, long, long)} that also looks, within the SAME budget, for the files
     * saved beside the root - a new extension's two command interface files ({@link #COMMAND_INTERFACE_FILES}). The root
     * file is read until one read shows the wizard's shape and is not read again after that; the files beside it are
     * looked for on every round, until all are there or the budget is spent. Only the root decides
     * {@link RootOnDisk#confirmed}; a file still missing at the end is named in {@link RootOnDisk#missingFiles}, and a
     * file whose check fails counts as not seen. Never throws for a read or a check that fails.
     *
     * @param exportWait the bounded wait for the project's export queue
     * @param reader reads the root file
     * @param files the project-relative paths to look for beside the root; empty for none
     * @param fileExists whether a project-relative path is a file on disk ({@link #projectFileExists})
     * @param budgetMs how long the whole look may take
     * @param pollMs the pause between two rounds
     * @return what was found
     */
    public static RootOnDisk confirmOnDisk(Supplier<DiskExportState> exportWait, RootFileReader reader, // NOSONAR the root, the files beside it and the bounds, one each
        List<String> files, Predicate<String> fileExists, long budgetMs, long pollMs)
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        DiskExportState exportState = exportWait.get();
        boolean rootConfirmed = false;
        List<String> missing = Collections.emptyList();
        boolean fileFound = false;
        String failure = null;
        while (true)
        {
            if (!rootConfirmed)
            {
                String text = null;
                failure = null;
                try
                {
                    text = reader.read();
                }
                catch (IOException | RuntimeException e)
                {
                    failure = reason(e);
                }
                missing = missingOnDisk(text);
                fileFound = text != null;
                rootConfirmed = missing.isEmpty();
            }
            List<String> missingFiles = notOnDisk(files, fileExists);
            if (rootConfirmed && missingFiles.isEmpty())
            {
                return new RootOnDisk(true, missing, true, exportState, null, budgetMs, missingFiles);
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0)
            {
                return new RootOnDisk(rootConfirmed, missing, fileFound, exportState, failure, budgetMs, missingFiles);
            }
            try
            {
                Thread.sleep(Math.max(1L, Math.min(pollMs, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return new RootOnDisk(rootConfirmed, missing, fileFound, exportState, failure, budgetMs, missingFiles);
            }
        }
    }

    /** The {@code files} that {@code fileExists} does not see, in their order; a check that throws counts as not seen. */
    private static List<String> notOnDisk(List<String> files, Predicate<String> fileExists)
    {
        List<String> notSeen = new ArrayList<>(files.size());
        for (String file : files)
        {
            boolean seen;
            try
            {
                seen = fileExists.test(file);
            }
            catch (RuntimeException e)
            {
                seen = false;
            }
            if (!seen)
            {
                notSeen.add(file);
            }
        }
        return notSeen;
    }

    /**
     * Whether a project-relative path is a regular file under the project's location, checked straight on the file
     * system, past the workspace's resource cache - as {@link #readRootFile} reads the root. A project with no local
     * location has no files.
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
     * Reads a project's root file straight from the file system, past the workspace's resource cache. The read is
     * one synchronous {@link Files#readAllBytes} and is NOT bounded: on a file system that does not answer it
     * blocks the calling thread, whatever budget the caller set for its loop.
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
