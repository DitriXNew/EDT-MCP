/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.DeletionForbiddenProblem;
import com._1c.g5.v8.dt.refactoring.core.EditingForbiddenProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Refuses a write into an object that vendor support does not allow to change.
 *
 * <p>EDT enforces support only in its UI (read-only editors, hidden actions) and in refactorings
 * (as bypassable problems); a BM write below that succeeds silently. The verdict is EDT's own
 * {@link IModelEditingSupport}, asked in {@link EditingMode#DIRECT} about the nearest existing
 * object the operation changes. It FAILS CLOSED: no service, or a service that throws, refuses.</p>
 *
 * <p>An external-objects project is never asked: it has no support settings, and the
 * configuration its context carries is the linked BASE one. A configuration extension can be
 * under vendor support of its own and is asked like a configuration; its refusal does not
 * suggest an extension as the way out.</p>
 */
public final class VendorSupportGuard
{
    /** What an FQN-addressed write does to its target. */
    public enum Intent
    {
        /** Not guarded. */
        NONE,
        /** Adds a node: the nearest existing ancestor (or the configuration) changes. */
        CREATE,
        /** Changes the addressed node (or its nearest existing ancestor). */
        MODIFY,
        /** Removes the addressed node. */
        DELETE
    }

    /** The configuration pseudo-address used by command-interface and module paths. */
    private static final String CONFIGURATION = "Configuration"; //$NON-NLS-1$

    /** The shared remedy of every lock refusal. */
    private static final String REMEDY = " Nothing was changed. Make the change in a configuration " //$NON-NLS-1$
        + "extension instead (adopt the object with adopt_metadata_object and edit the adopted " //$NON-NLS-1$
        + "copy, or create new objects in the extension project), or ask the user to allow " //$NON-NLS-1$
        + "changes in EDT's support settings (Configuration > Support > Support settings); this " //$NON-NLS-1$
        + "server does not change support settings."; //$NON-NLS-1$

    /** The remedy of a lock refusal inside a configuration extension, which cannot adopt into itself. */
    private static final String EXTENSION_REMEDY = " Nothing was changed. The extension is itself under " //$NON-NLS-1$
        + "vendor support, so adopting into it is no way out: ask the user to allow changes in EDT's " //$NON-NLS-1$
        + "support settings of the extension (Configuration > Support > Support settings); this server " //$NON-NLS-1$
        + "does not change support settings."; //$NON-NLS-1$

    /** Test seam: replaces the platform service lookup when set. */
    private static final AtomicReference<Supplier<IModelEditingSupport>> SERVICE = new AtomicReference<>();

    private VendorSupportGuard()
    {
        // Utility class
    }

    /**
     * Test seam: answers {@link #service()} from {@code supplier}; {@code null} restores the platform
     * service.
     *
     * @param supplier the replacement, or {@code null}
     */
    public static void setServiceForTests(Supplier<IModelEditingSupport> supplier)
    {
        SERVICE.set(supplier);
    }

    /** @return EDT's editing-support service, or {@code null} when it is not available */
    static IModelEditingSupport service()
    {
        Supplier<IModelEditingSupport> override = SERVICE.get();
        if (override != null)
        {
            return override.get();
        }
        try
        {
            return ServiceAccess.get(IModelEditingSupport.class);
        }
        catch (RuntimeException e) // NOSONAR a missing service is a verdict (refuse), not a crash
        {
            return null;
        }
    }

    /**
     * The refusal for an FQN-addressed write, with the verb its intent implies.
     *
     * @param scope the project's resolution root
     * @param normFqn the normalized FQN the call addresses
     * @param intent what the call does to it
     * @return the refusal message, or {@code null} when the write may proceed
     */
    public static String refusalForFqn(MetadataScope scope, String normFqn, Intent intent)
    {
        String verb = intent == Intent.CREATE ? "created" //$NON-NLS-1$
            : intent == Intent.DELETE ? "deleted" : "changed"; //$NON-NLS-1$ //$NON-NLS-2$
        return refusalForFqn(scope, normFqn, intent, verb);
    }

    /**
     * The refusal for an FQN-addressed write: resolves the nearest existing object the operation
     * changes (inside a read of the project's model) and asks EDT whether it may change.
     *
     * @param scope the project's resolution root
     * @param normFqn the normalized FQN the call addresses
     * @param intent what the call does to it
     * @param verb the past participle naming the operation, e.g. {@code "renamed"}
     * @return the refusal message, or {@code null} when the write may proceed
     */
    public static String refusalForFqn(MetadataScope scope, String normFqn, Intent intent, String verb)
    {
        if (intent == null || intent == Intent.NONE || scope == null || scope.isExternalObjects()
            || normFqn == null || normFqn.isEmpty())
        {
            return null;
        }
        return inModelRead(scope.project(), normFqn, verb, () -> {
            Target target = resolveTarget(scope, normFqn, intent);
            if (target == null)
            {
                return null;
            }
            return check(target.object, intent == Intent.DELETE && target.exact, target.label,
                scope.configuration(), normFqn, verb);
        });
    }

    /**
     * The refusal for a change to an object the caller already resolved - inside its own read or
     * write transaction when it has one.
     *
     * @param object the object the operation changes
     * @param scope the project's resolution root (an external-objects scope is never asked)
     * @param label how to name {@code object} in the refusal
     * @param callerAddress the address the caller used
     * @param verb the past participle naming the operation
     * @return the refusal message, or {@code null} when the write may proceed
     */
    public static String refusalFor(EObject object, MetadataScope scope, String label, String callerAddress,
        String verb)
    {
        if (object == null || scope == null || scope.isExternalObjects())
        {
            return null;
        }
        return check(object, false, label, scope.configuration(), callerAddress, verb);
    }

    /**
     * The refusal for an operation that may edit OR delete {@code object} and does not say which
     * (a quick-fix variant): both of EDT's verdicts must allow it.
     *
     * @param object the object the operation acts on
     * @param scope the project's resolution root (an external-objects scope is never asked)
     * @param label how to name {@code object} in the refusal
     * @param callerAddress the address the caller used
     * @param verb the past participle naming the operation
     * @return the refusal message, or {@code null} when the operation may proceed
     */
    public static String refusalForEditOrDelete(EObject object, MetadataScope scope, String label,
        String callerAddress, String verb)
    {
        if (object == null || scope == null || scope.isExternalObjects())
        {
            return null;
        }
        String refusal = check(object, false, label, scope.configuration(), callerAddress, verb);
        return refusal != null ? refusal : check(object, true, label, scope.configuration(), callerAddress, verb);
    }

    /**
     * The refusal for writing a BSL module: the module itself (what EDT's BSL editor asks) and the
     * object that owns it, resolved from the path, so the verdict does not depend on the parsed
     * module's owner link resolving.
     *
     * @param project the project holding the module
     * @param scope the project's resolution root (an external-objects scope is never asked)
     * @param projectRelativePath the module's project-relative path, e.g. {@code src/CommonModules/X/Module.bsl}
     * @param module the loaded module, or {@code null} for a module file that does not exist yet
     * @return the refusal message, or {@code null} when the write may proceed
     */
    public static String refusalForModule(IProject project, MetadataScope scope, String projectRelativePath,
        EObject module)
    {
        if (scope == null || scope.isExternalObjects())
        {
            return null;
        }
        String verb = "written"; //$NON-NLS-1$
        return inModelRead(project, projectRelativePath, verb, () -> {
            Configuration configuration = scope.configuration();
            String ownerFqn = moduleOwnerFqn(projectRelativePath);
            EObject owner = ownerFqn == null ? null
                : CONFIGURATION.equals(ownerFqn) ? configuration : resolveExact(scope, ownerFqn);
            String label = ownerFqn;
            if (owner == null || owner == configuration)
            {
                // An owner the path does not name: the configuration is the object it belongs to.
                owner = configuration;
                label = configurationLabel(configuration);
            }
            String refusal = owner == null ? null
                : check(owner, false, label, configuration, projectRelativePath, verb);
            if (refusal != null || module == null)
            {
                return refusal;
            }
            return check(module, false, "the module", configuration, projectRelativePath, verb); //$NON-NLS-1$
        });
    }

    /**
     * The refusal for writing a module file: {@link #refusalForModule} with the module loaded when
     * the file exists.
     *
     * @param project the project that owns {@code file}
     * @param scope that project's resolution root (an external-objects scope is never asked)
     * @param file the module file
     * @return the refusal message, or {@code null} when the write may proceed
     */
    public static String refusalForModuleFile(IProject project, MetadataScope scope, IFile file)
    {
        if (scope == null || scope.isExternalObjects())
        {
            return null;
        }
        EObject module = file.exists() ? BslModuleUtils.loadModule(file) : null;
        return refusalForModule(project, scope, file.getProjectRelativePath().toString(), module);
    }

    /**
     * The refusal for a write whose target could not be judged at all (fail closed).
     *
     * @param callerAddress the address the call named
     * @param verb the past participle naming the operation
     * @param cause why the check could not be made
     * @return the refusal message
     */
    public static String uncheckedRefusal(String callerAddress, String verb, String cause)
    {
        return checkFailed(callerAddress, verb, cause);
    }

    /**
     * The refusal for a write whose CASCADE would change objects vendor support locks: the caller
     * lists them before changing anything.
     *
     * @param callerAddress the address the call named
     * @param verb the past participle naming the operation
     * @param cascade what the cascade would do to them, e.g. "its namespace change would rewrite XDTO packages"
     * @param lockedNames the locked objects the cascade would change (at least one)
     * @return the refusal message
     */
    public static String cascadeRefusal(String callerAddress, String verb, String cascade, List<String> lockedNames)
    {
        return "'" + callerAddress + "' cannot be " + verb + ": " + cascade + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + String.join(", ", lockedNames) //$NON-NLS-1$
            + ", which vendor support does not allow changing (or that could not be checked)." + REMEDY; //$NON-NLS-1$
    }

    /**
     * The refusal for adopting into an extension: any vendor support on the extension refuses, since
     * EDT's adopter may also change objects there (a receiving parent, a dependency) that cannot be
     * listed before it runs. Unanswerable counts as supported.
     *
     * @param extensionName the target extension project's name
     * @param callerAddress the address the call named
     * @param underSupport whether the extension is under vendor support (may throw)
     * @return the refusal message, or {@code null} when the adoption may proceed
     */
    public static String adoptionRefusal(String extensionName, String callerAddress, BooleanSupplier underSupport)
    {
        boolean supported;
        try
        {
            supported = underSupport.getAsBoolean();
        }
        catch (RuntimeException e) // NOSONAR fail closed: an unanswerable question is a "yes"
        {
            Log.log(new Status(IStatus.WARNING, Log.pluginId(),
                "Vendor-support check failed for adopting " + callerAddress, e)); //$NON-NLS-1$
            return checkFailed(callerAddress, "adopted", //$NON-NLS-1$
                "the support settings of the extension '" + extensionName + "' could not be read: " //$NON-NLS-1$ //$NON-NLS-2$
                    + PlatformFailures.describe(e));
        }
        if (!supported)
        {
            return null;
        }
        return "'" + callerAddress + "' cannot be adopted: the extension '" + extensionName //$NON-NLS-1$ //$NON-NLS-2$
            + "' is under vendor support, and an adoption can also change objects in it that cannot be " //$NON-NLS-1$
            + "listed before it runs (the adopted parent that receives the copy, a command group or other " //$NON-NLS-1$
            + "object the adopted one depends on), so no adoption into a supported extension is attempted." //$NON-NLS-1$
            + EXTENSION_REMEDY;
    }

    /**
     * Whether a configuration (or extension) root carries vendor support: its support settings are
     * set. A superset of EDT's own test, which also ignores a non-native root and an unresolved link.
     *
     * @param root the configuration root, read inside a transaction
     * @return {@code true} when support settings are present
     * @throws IllegalStateException when there is no root to ask
     */
    public static boolean underVendorSupport(Configuration root)
    {
        if (root == null)
        {
            throw new IllegalStateException("the extension has no configuration root"); //$NON-NLS-1$
        }
        return root.getDistributionSettings() != null;
    }

    /**
     * Whether EDT allows editing {@code object}; {@code false} also when that cannot be established.
     *
     * @param object the object to ask about
     * @return {@code true} only on the platform's explicit yes
     */
    public static boolean allowsEdit(EObject object)
    {
        IModelEditingSupport support = service();
        if (support == null || object == null)
        {
            return false;
        }
        try
        {
            return support.canEdit(object, EditingMode.DIRECT);
        }
        catch (RuntimeException e) // NOSONAR fail closed: an unanswerable question is a "no"
        {
            return false;
        }
    }

    /**
     * Whether EDT's editing support confirms that {@code object} is locked - the test that tells a
     * support-derived refactoring prohibition from any other. Unanswerable counts as locked.
     *
     * @param object the object a refactoring problem names (may be {@code null})
     * @param deletion whether the problem forbids a deletion rather than an edit
     * @return {@code true} when the object may not be edited (or, for a deletion, deleted)
     */
    public static boolean confirmsLock(EObject object, boolean deletion)
    {
        IModelEditingSupport support = service();
        if (support == null || object == null)
        {
            return true;
        }
        try
        {
            return !support.canEdit(object, EditingMode.DIRECT)
                || deletion && !support.canDelete(object, EditingMode.DIRECT);
        }
        catch (RuntimeException e) // NOSONAR fail closed
        {
            return true;
        }
    }

    /**
     * Whether a refactoring problem is a vendor-support lock. EDT raises
     * {@link EditingForbiddenProblem} / {@link DeletionForbiddenProblem} from its editing-support
     * check - and still performs the edit - and once from a file-read failure; asking that check
     * again about the problem's object tells the two apart.
     *
     * @param problem the refactoring problem
     * @return {@code true} for a support lock (including one the check cannot answer)
     */
    public static boolean isSupportLockProblem(IRefactoringProblem problem)
    {
        boolean deletion = problem instanceof DeletionForbiddenProblem;
        if (!deletion && !(problem instanceof EditingForbiddenProblem))
        {
            return false;
        }
        EObject object;
        try
        {
            object = problem.getObject();
        }
        catch (RuntimeException e) // NOSONAR an unreadable object cannot prove the lock away
        {
            object = null;
        }
        return confirmsLock(object, deletion);
    }

    /**
     * The {@code get_metadata_details} line for a locked object; empty when it is editable and
     * deletable, or when that cannot be established (a read tool must not fail on it).
     *
     * @param object the rendered object
     * @param scope the project's resolution root
     * @return the markdown line with its trailing newline, or {@code ""}
     */
    public static String detailsLine(MdObject object, MetadataScope scope)
    {
        IModelEditingSupport support = service();
        if (object == null || scope == null || scope.isExternalObjects() || support == null)
        {
            return ""; //$NON-NLS-1$
        }
        boolean editable;
        boolean deletable;
        boolean configurationLocked = false;
        try
        {
            editable = support.canEdit(object, EditingMode.DIRECT);
            deletable = support.canDelete(object, EditingMode.DIRECT);
            Configuration configuration = scope.configuration();
            if ((!editable || !deletable) && configuration != null && configuration != object)
            {
                configurationLocked = !support.canEdit(configuration, EditingMode.DIRECT);
            }
        }
        catch (RuntimeException e) // NOSONAR a read tool degrades to saying nothing
        {
            return ""; //$NON-NLS-1$
        }
        if (editable && deletable)
        {
            return ""; //$NON-NLS-1$
        }
        return "**Vendor support:** " + (editable ? "editable" : "not editable") + ", " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + (deletable ? "deletable" : "not deletable") //$NON-NLS-1$ //$NON-NLS-2$
            + (configurationLocked ? " (the configuration itself is locked)" : "") + "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The FQN of the metadata object that owns a module, read off its project-relative path:
     * {@code Configuration} for a configuration module, {@code Type.Name}, {@code Type.Name.Form.F}
     * or {@code Type.Name.Command.C}. Pure.
     *
     * @param projectRelativePath the module path, with or without the leading {@code src/}
     * @return the owner FQN, or {@code null} when the path names no known metadata directory
     */
    public static String moduleOwnerFqn(String projectRelativePath)
    {
        if (projectRelativePath == null)
        {
            return null;
        }
        String[] parts = projectRelativePath.replace('\\', '/').split("/"); //$NON-NLS-1$
        int start = parts.length > 0 && BslModuleUtils.SOURCE_FOLDER.equals(parts[0]) ? 1 : 0;
        String[] rest = Arrays.copyOfRange(parts, start, parts.length);
        if (rest.length >= 2 && CONFIGURATION.equals(rest[0]))
        {
            return CONFIGURATION;
        }
        if (rest.length < 3)
        {
            return null;
        }
        String type = MetadataTypeUtils.getTypeByDirectoryName(rest[0]);
        if (type == null)
        {
            return null;
        }
        String fqn = type + '.' + rest[1];
        if (rest.length >= 5 && "Forms".equals(rest[2])) //$NON-NLS-1$
        {
            return fqn + ".Form." + rest[3]; //$NON-NLS-1$
        }
        if (rest.length >= 5 && "Commands".equals(rest[2])) //$NON-NLS-1$
        {
            return fqn + ".Command." + rest[3]; //$NON-NLS-1$
        }
        return fqn;
    }

    /** The object a write is judged on and the address that named it. */
    static final class Target
    {
        final EObject object;
        final String label;
        final boolean exact;

        Target(EObject object, String label, boolean exact)
        {
            this.object = object;
            this.label = label;
            this.exact = exact;
        }
    }

    /**
     * The nearest existing object an FQN-addressed write changes. A CREATE starts from the
     * parent (the node itself does not exist yet) and falls back to the configuration for a
     * top-level {@code Type.Name}; a MODIFY / DELETE starts from the node itself. Package-visible
     * for tests.
     *
     * @param scope the resolution root
     * @param normFqn the normalized FQN
     * @param intent the operation
     * @return the target, or {@code null} when nothing the call could change resolves
     */
    static Target resolveTarget(MetadataScope scope, String normFqn, Intent intent)
    {
        CommandInterfaceAddress commandInterface = CommandInterfaceAddress.parse(normFqn);
        if (commandInterface != null)
        {
            String owner = commandInterface.ownerFqn();
            EObject object = CONFIGURATION.equals(owner) ? scope.configuration() : resolveExact(scope, owner);
            return object == null ? null
                : new Target(object, CONFIGURATION.equals(owner) ? configurationLabel(scope.configuration())
                    : owner, false);
        }
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        int longest = intent == Intent.CREATE ? parts.length - 1 : parts.length;
        for (int length = longest; length >= 2; length--)
        {
            String prefix = String.join(".", Arrays.copyOf(parts, length)); //$NON-NLS-1$
            MdObject found = resolveExact(scope, prefix);
            if (found != null)
            {
                return new Target(found, prefix, length == parts.length);
            }
        }
        if (intent == Intent.CREATE && parts.length == 2 && scope.configuration() != null)
        {
            return new Target(scope.configuration(), configurationLabel(scope.configuration()), false);
        }
        return null;
    }

    /**
     * Resolves one address - a subsystem chain, a form, or any node the shared resolver knows -
     * through the shared bilingual resolvers. A node also resolves through the yo fallback that
     * modify/delete use, so a yo-spelled address of an existing object is judged, not skipped.
     */
    private static MdObject resolveExact(MetadataScope scope, String fqn)
    {
        String[] chain = SubsystemUtils.parseSubsystemPath(fqn);
        if (chain != null)
        {
            return SubsystemUtils.resolveByPath(scope.configuration(), chain, chain.length);
        }
        String formPath = FormElementWriter.parseFormPath(fqn);
        if (formPath != null)
        {
            MdObject form = FormStructureReader.resolveMdForm(scope, formPath);
            if (form != null)
            {
                return form;
            }
        }
        if (!MetadataNodeResolver.isValidArity(fqn.split("\\.").length)) //$NON-NLS-1$
        {
            return null;
        }
        MetadataNodeResolver.MetadataNode node =
            MetadataNodeResolver.resolveExistingWithYoFallback(scope, fqn).node;
        return node == null ? null : node.object;
    }

    /** Asks EDT about one object and words the refusal. */
    private static String check(EObject object, boolean delete, String label, Configuration configuration,
        String callerAddress, String verb)
    {
        IModelEditingSupport support = service();
        if (support == null)
        {
            return "Cannot check whether '" + callerAddress + "' may be " + verb //$NON-NLS-1$ //$NON-NLS-2$
                + ": EDT's editing-support service (IModelEditingSupport) is not available, and a " //$NON-NLS-1$
                + "write that cannot be checked against vendor support is refused. Nothing was " //$NON-NLS-1$
                + "changed. Retry once list_projects reports the project ready."; //$NON-NLS-1$
        }
        boolean allowed;
        try
        {
            allowed = delete ? support.canDelete(object, EditingMode.DIRECT)
                : support.canEdit(object, EditingMode.DIRECT);
        }
        catch (RuntimeException e)
        {
            Log.log(new Status(IStatus.WARNING, Log.pluginId(),
                "Vendor-support check failed for " + callerAddress, e)); //$NON-NLS-1$
            return checkFailed(callerAddress, verb, PlatformFailures.describe(e));
        }
        if (allowed)
        {
            return null;
        }
        String subject = label == null || label.equals(callerAddress) ? "it" : label; //$NON-NLS-1$
        String reason = delete
            ? "vendor support does not allow deleting " + subject //$NON-NLS-1$
                + " (it, one of its members, or the object that contains it is locked by its support rule)" //$NON-NLS-1$
            : subject + " is under vendor support and its support rule does not allow changes"; //$NON-NLS-1$
        return "'" + callerAddress + "' cannot be " + verb + ": " + reason + "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + configurationClause(support, object, configuration) + remedyFor(configuration);
    }

    /** The way out of a lock: an extension for a configuration, the support settings for an extension. */
    private static String remedyFor(Configuration configuration)
    {
        Activator activator = configuration == null ? null : Activator.getDefault();
        IV8ProjectManager manager = activator == null ? null : activator.getV8ProjectManager();
        try
        {
            return manager != null && manager.getProject(configuration) instanceof IExtensionProject
                ? EXTENSION_REMEDY : REMEDY;
        }
        catch (RuntimeException e) // NOSONAR the remedy is advice; the refusal stands without the right one
        {
            return REMEDY;
        }
    }

    /** The "configuration itself is locked" sentence, computed only for a refusal. */
    private static String configurationClause(IModelEditingSupport support, EObject object,
        Configuration configuration)
    {
        if (configuration == null || configuration == object)
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            return support.canEdit(configuration, EditingMode.DIRECT) ? "" //$NON-NLS-1$
                : " The configuration '" + configuration.getName() + "' itself is locked as well."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e) // NOSONAR the clause is decoration; the refusal stands without it
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static String configurationLabel(Configuration configuration)
    {
        return "the configuration '" + (configuration == null ? "?" : configuration.getName()) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String checkFailed(String callerAddress, String verb, String cause)
    {
        return "Cannot check whether '" + callerAddress + "' may be " + verb //$NON-NLS-1$ //$NON-NLS-2$
            + ": EDT's vendor-support check failed (" + cause + "), and a write that cannot be " //$NON-NLS-1$ //$NON-NLS-2$
            + "checked is refused. Nothing was changed. Retry once list_projects reports the " //$NON-NLS-1$
            + "project ready; if it keeps failing, check the EDT log."; //$NON-NLS-1$
    }

    /**
     * Runs {@code work} inside a read of the project's model when there is one (the platform check
     * joins that transaction); a bare scope without a project runs it directly.
     */
    private static String inModelRead(IProject project, String callerAddress, String verb, Supplier<String> work)
    {
        IBmModel model = modelOf(project);
        if (model == null)
        {
            return work.get();
        }
        try
        {
            return BmTransactions.read(model, "VendorSupportCheck", (tx, monitor) -> work.get()); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Log.log(new Status(IStatus.WARNING, Log.pluginId(),
                "Vendor-support check failed for " + callerAddress, e)); //$NON-NLS-1$
            return checkFailed(callerAddress, verb, PlatformFailures.describe(e));
        }
    }

    private static IBmModel modelOf(IProject project)
    {
        Activator activator = project == null ? null : Activator.getDefault();
        IBmModelManager manager = activator == null ? null : activator.getBmModelManager();
        if (manager == null)
        {
            return null;
        }
        try
        {
            return manager.getModel(project);
        }
        catch (RuntimeException e) // NOSONAR no model: the platform check opens its own read
        {
            return null;
        }
    }
}
