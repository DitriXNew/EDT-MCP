/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.cmi.model.deriveddata.ActionPanel;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandContainer;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandInterfaceRoot;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandItem;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandItemGroup;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CustomCommandItemGroup;
import com._1c.g5.v8.dt.cmi.model.deriveddata.NavigationPanel;
import com._1c.g5.v8.dt.cmi.model.deriveddata.SubsystemCommandInterface;
import com._1c.g5.v8.dt.cmi.model.deriveddata.UnresolvedCommand;
import com._1c.g5.v8.dt.cmi.tasks.SetCommandOrderTask;
import com._1c.g5.v8.dt.cmi.tasks.SetCommandPlacementTask;
import com._1c.g5.v8.dt.cmi.tasks.SetCommandVisibilityTask;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ForRoleType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Group;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Item;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Panel;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Plan;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.PlanResult;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Visibility;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reads and edits a section's command interface through EDT's own model: the effective state comes
 * from the platform-computed command interface, and every change runs the platform's customization
 * tasks - the ones the command interface editor runs - inside one BM write transaction.
 */
public final class CommandInterfaceSupport
{
    /** Top-object FQN of EDT's computed command interface. */
    static final String ROOT_FQN = "command_interface_root"; //$NON-NLS-1$

    /** Derived-data segment that computes {@link #ROOT_FQN}. */
    static final String CMI_SEGMENT = "CMI"; //$NON-NLS-1$

    /** Read-back value for a command the section stores nothing for. */
    static final String DEFAULT = "default"; //$NON-NLS-1$

    private static final String CONFIGURATION_FQN = "Configuration"; //$NON-NLS-1$

    private static final String STANDARD_COMMAND_INFIX = ".StandardCommand."; //$NON-NLS-1$

    private static final String ROLE_PREFIX = "Role."; //$NON-NLS-1$

    private static final String COMMAND_GROUP_PREFIX = "CommandGroup."; //$NON-NLS-1$

    private CommandInterfaceSupport()
    {
        // utility class
    }

    /** A section snapshot, or an actionable error; exactly one is non-null. */
    public static final class Snapshot
    {
        /** The section, or {@code null} on error. */
        public final CommandInterfaceSection section;
        /** The actionable error, or {@code null}. */
        public final String error;

        Snapshot(CommandInterfaceSection section, String error)
        {
            this.section = section;
            this.error = error;
        }
    }

    /** The outcome of an edit. */
    public static final class EditResult
    {
        /** The actionable error, or {@code null} on success. */
        public String error;
        /** The validated plan (on success). */
        public Plan plan;
        /** The FQN of the command interface that was written, or {@code null} when nothing was. */
        public String writtenFqn;
        /** Read back after the write: every command an entry named, and the ordered groups. */
        public JsonObject stored;
    }

    // ===== read =====================================================================================

    /**
     * Reads a section's command interface in one BM read transaction.
     *
     * @param model the project's BM model
     * @param address the parsed address
     * @return the snapshot or an actionable error
     */
    public static Snapshot read(IBmModel model, CommandInterfaceAddress address)
    {
        return BmTransactions.read(model, "Read command interface", //$NON-NLS-1$
            (tx, pm) -> snapshotInTx(tx, address));
    }

    /**
     * Whether EDT has finished computing the command interface.
     *
     * @param project the workspace project
     * @return the computed state, or {@code null} when it cannot be asked
     */
    public static Boolean isComputed(IProject project)
    {
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IDerivedDataManagerProvider provider = Activator.getDefault().getDerivedDataManagerProvider();
            IDtProject dtProject = dtProjectManager == null ? null : dtProjectManager.getDtProject(project);
            IDerivedDataManager ddManager = dtProject == null || provider == null ? null : provider.get(dtProject);
            if (ddManager == null)
            {
                return null; // NOSONAR tri-state: null means "cannot be asked"
            }
            return ddManager.isComputed(Collections.singletonList(CMI_SEGMENT));
        }
        catch (RuntimeException e)
        {
            // An EDT that does not register the segment asserts here: the state is unknown, not bad.
            Activator.logError("Cannot probe the command interface computation state", e); //$NON-NLS-1$
            return null; // NOSONAR tri-state: null means "cannot be asked"
        }
    }

    /**
     * Waits, bounded, until the command interface has been computed. Never call on the UI thread.
     *
     * @param project the workspace project
     * @param timeoutMs the bound
     * @return {@code false} only when the computation is known to be still running at the deadline
     */
    public static boolean awaitComputed(IProject project, long timeoutMs)
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true)
        {
            Boolean computed = isComputed(project);
            if (computed == null || computed.booleanValue())
            {
                return true;
            }
            if (System.currentTimeMillis() >= deadline)
            {
                return false;
            }
            try
            {
                Thread.sleep(200L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return true;
            }
        }
    }

    /** @return the note a read carries while the computed view may lag the model */
    public static String staleNote()
    {
        return "EDT is still recomputing the command interface, so this view may not show the latest " //$NON-NLS-1$
            + "change yet - read it again in a few seconds."; //$NON-NLS-1$
    }

    /** @return the refusal for {@code Configuration.CommandInterface}, which holds sections, not commands */
    public static String sectionsPanelRefusal()
    {
        return "'Configuration.CommandInterface' is the sections panel (the order and visibility of " //$NON-NLS-1$
            + "top-level subsystems), which this tool does not read or edit. Command visibility, " //$NON-NLS-1$
            + "placement and order live in a section: 'Subsystem.<Name>.CommandInterface', or " //$NON-NLS-1$
            + "'Configuration.MainSectionCommandInterface' for the main (desktop) section."; //$NON-NLS-1$
    }

    /**
     * @param projectName the extension project
     * @return the refusal for a command interface addressed in a configuration extension
     */
    public static String extensionRefusal(String projectName)
    {
        return "Project '" + projectName + "' is a configuration EXTENSION, whose command interface " //$NON-NLS-1$ //$NON-NLS-2$
            + "is stored as changes to the adopted base section; this tool reads and edits only a " //$NON-NLS-1$
            + "configuration's own command interface. Address the section in the base configuration " //$NON-NLS-1$
            + "project, or use EDT's command interface editor for the extension."; //$NON-NLS-1$
    }

    static Snapshot snapshotInTx(IBmTransaction tx, CommandInterfaceAddress address)
    {
        if (address.kind() == CommandInterfaceAddress.Kind.SECTIONS_PANEL)
        {
            return new Snapshot(null, sectionsPanelRefusal());
        }
        Configuration config = (Configuration)tx.getTopObjectByFqn(CONFIGURATION_FQN);
        if (config == null)
        {
            return new Snapshot(null, "The project has no configuration model loaded yet; retry once " //$NON-NLS-1$
                + "list_projects reports it ready."); //$NON-NLS-1$
        }
        IBmObject root = tx.getTopObjectByFqn(ROOT_FQN);
        CommandContainer container;
        if (address.kind() == CommandInterfaceAddress.Kind.SUBSYSTEM)
        {
            String[] chain = address.subsystemChain();
            Subsystem subsystem = SubsystemUtils.resolveByPath(config, chain, chain.length);
            if (subsystem == null)
            {
                return new Snapshot(null, "Subsystem not found: " + address.ownerFqn() + ". Address a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "section as 'Subsystem.<Name>.CommandInterface' or " //$NON-NLS-1$
                    + "'Subsystem.<Parent>.Subsystem.<Child>.CommandInterface' by programmatic Names; " //$NON-NLS-1$
                    + "list_subsystems lists them."); //$NON-NLS-1$
            }
            if (!isIncludedInCommandInterface(subsystem))
            {
                return new Snapshot(null, address.ownerFqn() + " has no section: it, or a parent subsystem, " //$NON-NLS-1$
                    + "is not included in the command interface. Set includeInCommandInterface to true " //$NON-NLS-1$
                    + "with modify_metadata(fqn='" + address.ownerFqn() + "', properties=[{name:" //$NON-NLS-1$ //$NON-NLS-2$
                    + "'includeInCommandInterface', value:true}]) first."); //$NON-NLS-1$
            }
            container = root instanceof CommandInterfaceRoot
                ? findSubsystemContainer((CommandInterfaceRoot)root, subsystem) : null;
        }
        else
        {
            container = root instanceof CommandInterfaceRoot
                ? ((CommandInterfaceRoot)root).getMainSectionCommandInterface() : null;
        }
        if (container == null)
        {
            return new Snapshot(null, "EDT has not computed the command interface of " //$NON-NLS-1$
                + address.ownerFqn() + " yet (it is part of the project's derived data). Retry in a " //$NON-NLS-1$
                + "few seconds, once list_projects reports the project ready."); //$NON-NLS-1$
        }
        return new Snapshot(buildSection(container, address), null);
    }

    private static boolean isIncludedInCommandInterface(Subsystem subsystem)
    {
        for (Subsystem s = subsystem; s != null; s = s.getParentSubsystem())
        {
            if (!s.isIncludeInCommandInterface())
            {
                return false;
            }
        }
        return true;
    }

    private static CommandContainer findSubsystemContainer(CommandInterfaceRoot root, Subsystem subsystem)
    {
        long id = ((IBmObject)subsystem).bmGetId();
        for (SubsystemCommandInterface sci : root.getAllSubsystemCommandInterfaces())
        {
            Subsystem candidate = sci.getSubsystem();
            if (candidate == subsystem || (candidate instanceof IBmObject && !candidate.eIsProxy()
                && ((IBmObject)candidate).bmGetId() == id))
            {
                return sci;
            }
        }
        return null;
    }

    static CommandInterfaceSection buildSection(CommandContainer container, CommandInterfaceAddress address)
    {
        List<Group> groups = new ArrayList<>();
        NavigationPanel navigation = container.getNavigationPanel();
        if (navigation != null)
        {
            addStandard(groups, navigation.getImportantGroup(), "NavigationPanelImportant", Panel.NAVIGATION); //$NON-NLS-1$
            addStandard(groups, navigation.getOrdinaryGroup(), "NavigationPanelOrdinary", Panel.NAVIGATION); //$NON-NLS-1$
            addStandard(groups, navigation.getSeeAlsoGroup(), "NavigationPanelSeeAlso", Panel.NAVIGATION); //$NON-NLS-1$
            addCustom(groups, navigation.getCustomGroups(), Panel.NAVIGATION);
        }
        ActionPanel actions = container.getActionPanel();
        if (actions != null)
        {
            addStandard(groups, actions.getCreateGroup(), "ActionsPanelCreate", Panel.ACTIONS); //$NON-NLS-1$
            addStandard(groups, actions.getReportsGroup(), "ActionsPanelReports", Panel.ACTIONS); //$NON-NLS-1$
            addStandard(groups, actions.getToolsGroup(), "ActionsPanelTools", Panel.ACTIONS); //$NON-NLS-1$
            addCustom(groups, actions.getCustomGroups(), Panel.ACTIONS);
        }
        return new CommandInterfaceSection(address.ownerFqn(), address.canonicalFqn(), groups);
    }

    private static void addStandard(List<Group> groups, CommandItemGroup itemGroup, String id, Panel panel)
    {
        if (itemGroup == null || itemGroup.getCommandGroup() == null)
        {
            return;
        }
        CommandGroup commandGroup = itemGroup.getCommandGroup();
        String russian = commandGroup instanceof StandardCommandGroup && !commandGroup.eIsProxy()
            ? ((StandardCommandGroup)commandGroup).getNameRu() : null;
        Group group = new Group(id, russian, panel, false, commandGroup, itemGroup.isItemsOrderCustomized());
        group.derived = itemGroup;
        addItems(group, itemGroup);
        groups.add(group);
    }

    private static void addCustom(List<Group> groups, List<CustomCommandItemGroup> customGroups, Panel panel)
    {
        for (CustomCommandItemGroup itemGroup : customGroups)
        {
            CommandGroup commandGroup = itemGroup.getCommandGroup();
            // An unresolved group is not a target anyone can address.
            if (!(commandGroup instanceof com._1c.g5.v8.dt.metadata.mdclass.CommandGroup) || commandGroup.eIsProxy())
            {
                continue;
            }
            Group group = new Group(COMMAND_GROUP_PREFIX + ((MdObject)commandGroup).getName(), null, panel, true,
                commandGroup, itemGroup.isItemsOrderCustomized());
            group.derived = itemGroup;
            addItems(group, itemGroup);
            groups.add(group);
        }
    }

    private static void addItems(Group group, CommandItemGroup itemGroup)
    {
        for (CommandItem commandItem : itemGroup.getItems())
        {
            Command command = commandItem.getCommand();
            if (command == null)
            {
                continue;
            }
            Visibility visibility = visibilityOf(commandItem.getVisibility());
            if (command instanceof UnresolvedCommand)
            {
                // The stored proxy, as the editor hands it to the tasks, never the derived stand-in.
                UnresolvedCommand unresolved = (UnresolvedCommand)command;
                Item item = new Item(String.valueOf(unresolved.getProxyName()), null,
                    unresolved.getProxyCommand(), visibility, commandItem.isVisibilityCustomized(),
                    commandItem.isGroupCustomized(), false);
                item.derived = commandItem;
                group.items.add(item);
                continue;
            }
            String[] names = commandNames(command);
            if (names != null)
            {
                Item item = new Item(names[0], names[1], command, visibility,
                    commandItem.isVisibilityCustomized(), commandItem.isGroupCustomized(), true);
                item.derived = commandItem;
                group.items.add(item);
            }
        }
    }

    static Visibility visibilityOf(AdjustableBoolean value)
    {
        Map<String, Boolean> roles = new LinkedHashMap<>();
        if (value == null)
        {
            return new Visibility(false, roles);
        }
        for (ForRoleType forRole : value.getFor())
        {
            Role role = forRole.getRole();
            if (role != null && !role.eIsProxy() && role.getName() != null)
            {
                roles.put(ROLE_PREFIX + role.getName(), forRole.isValue());
            }
        }
        return new Visibility(value.isCommon(), roles);
    }

    /**
     * The canonical English FQN of a command and, for a standard command, the same address with its
     * Russian name.
     *
     * @param command the command
     * @return {fqn, russianFqnOrNull}, or {@code null} for a command this cannot address
     */
    static String[] commandNames(Command command)
    {
        if (command == null || command.eIsProxy())
        {
            return null; // NOSONAR null signals "not addressable"
        }
        if (command instanceof StandardCommand)
        {
            StandardCommand standard = (StandardCommand)command;
            EObject owner = standard.eContainer();
            if (!(owner instanceof MdObject) || standard.getName() == null)
            {
                return null; // NOSONAR null signals "not addressable"
            }
            String prefix = mdObjectFqn((MdObject)owner) + STANDARD_COMMAND_INFIX;
            String russian = standard.getNameRu();
            return new String[] { prefix + standard.getName(),
                russian == null || russian.isEmpty() ? null : prefix + russian };
        }
        if (command instanceof MdObject)
        {
            return new String[] { mdObjectFqn((MdObject)command), null };
        }
        return null; // NOSONAR null signals "not addressable"
    }

    private static String mdObjectFqn(MdObject object)
    {
        if (object instanceof IBmObject && ((IBmObject)object).bmIsTop())
        {
            return ((IBmObject)object).bmGetFqn();
        }
        EObject parent = object.eContainer();
        if (parent instanceof MdObject)
        {
            return mdObjectFqn((MdObject)parent) + '.' + kindToken(object) + '.' + object.getName();
        }
        return object.eClass().getName() + '.' + object.getName();
    }

    /** The singular kind token of a nested object, from its containment ({@code commands} -> {@code Command}). */
    private static String kindToken(MdObject object)
    {
        EStructuralFeature feature = object.eContainingFeature();
        String name = feature == null ? object.eClass().getName() : feature.getName();
        if (feature != null && name.length() > 1 && name.endsWith("s")) //$NON-NLS-1$
        {
            name = name.substring(0, name.length() - 1);
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ===== edit =====================================================================================

    /**
     * Validates a {@code commands} payload against the section and, when it changes anything,
     * re-plans and applies it in ONE BM write transaction through the platform's customization
     * tasks, then reads back what the command interface stores. The caller force-exports
     * {@link EditResult#writtenFqn}.
     *
     * @param model the project's BM model
     * @param address the parsed address
     * @param entries the payload entries
     * @return the outcome
     */
    public static EditResult edit(IBmModel model, CommandInterfaceAddress address, List<JsonObject> entries)
    {
        EditResult result = edit(model, entries, (tx, e) -> planInTx(tx, address, e),
            (tx, plan) -> staleReason(storedInTx(tx, address), plan),
            (tx, pm, plan) -> applyInTx(tx, pm, address, plan), address.ownerFqn());
        if (result.writtenFqn != null)
        {
            String writtenFqn = result.writtenFqn;
            Plan plan = result.plan;
            result.stored = BmTransactions.read(model, "Read back command interface", //$NON-NLS-1$
                (tx, pm) -> storedState(tx, writtenFqn, plan));
        }
        return result;
    }

    /** Validates and plans a batch against the section as one transaction sees it. */
    @FunctionalInterface
    interface Planner
    {
        PlanResult plan(IBmTransaction tx, List<JsonObject> entries);
    }

    /** Why the write's plan is built on a computed view that lags the stored section, or {@code null}. */
    @FunctionalInterface
    interface Verifier
    {
        String staleReason(IBmTransaction tx, Plan plan);
    }

    /** Applies a plan inside a write transaction and returns the written command interface's FQN. */
    @FunctionalInterface
    interface Applier
    {
        String apply(IBmTransaction tx, IProgressMonitor pm, Plan plan);
    }

    /**
     * The edit without the read-back. A refused batch never opens a write; an accepted one is planned
     * again inside the write, verified against the stored section, and that plan is the one applied.
     */
    static EditResult edit(IBmModel model, List<JsonObject> entries, Planner planner, Verifier verifier,
        Applier applier, String ownerFqn)
    {
        EditResult result = new EditResult();
        String[] staleNoOp = new String[1];
        PlanResult planned = BmTransactions.read(model, "Plan command interface edit", (tx, pm) -> { //$NON-NLS-1$
            PlanResult p = planner.plan(tx, entries);
            // A no-op never reaches the write, so it is checked against the stored section here.
            if (p.error == null && p.plan.isEmpty())
            {
                staleNoOp[0] = verifier.staleReason(tx, p.plan);
            }
            return p;
        });
        if (planned.error != null)
        {
            result.error = planned.error;
            return result;
        }
        if (staleNoOp[0] != null)
        {
            result.error = sectionChangedError(ownerFqn, staleNoOp[0]);
            return result;
        }
        result.plan = planned.plan;
        if (planned.plan.isEmpty())
        {
            return result;
        }
        try
        {
            // The earlier read may be stale by now, and a deadlock retry re-runs this body afresh.
            return BmTransactions.write(model, "Edit command interface", (tx, pm) -> { //$NON-NLS-1$
                PlanResult fresh = planner.plan(tx, entries);
                if (fresh.error != null)
                {
                    throw new SectionChangedException(fresh.error);
                }
                String stale = verifier.staleReason(tx, fresh.plan);
                if (stale != null)
                {
                    throw new SectionChangedException(stale);
                }
                EditResult applied = new EditResult();
                applied.plan = fresh.plan;
                applied.writtenFqn = fresh.plan.isEmpty() ? null : applier.apply(tx, pm, fresh.plan);
                return applied;
            });
        }
        catch (SectionChangedException e)
        {
            result.plan = null;
            result.error = sectionChangedError(ownerFqn, e.getMessage());
            return result;
        }
    }

    /** The refusal when the section changed between validation and the write. */
    static String sectionChangedError(String ownerFqn, String why)
    {
        return "The command interface of " + ownerFqn + " changed while this call was writing it, and the " //$NON-NLS-1$ //$NON-NLS-2$
            + "batch no longer applies: " + why + " Nothing was changed; read it again with " //$NON-NLS-1$ //$NON-NLS-2$
            + "get_metadata_details and retry."; //$NON-NLS-1$
    }

    /** Thrown inside the write to roll it back when the re-planned batch no longer validates. */
    static final class SectionChangedException
        extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        SectionChangedException(String message)
        {
            super(message);
        }
    }

    private static PlanResult planInTx(IBmTransaction tx, CommandInterfaceAddress address, List<JsonObject> entries)
    {
        Snapshot snapshot = snapshotInTx(tx, address);
        if (snapshot.error != null)
        {
            return CommandInterfaceSection.failed(snapshot.error);
        }
        Configuration config = (Configuration)tx.getTopObjectByFqn(CONFIGURATION_FQN);
        return snapshot.section.plan(entries, ref -> canonicalRole(config, ref));
    }

    /** Maps a requested role FQN to {@code Role.<Name>}, or {@code null} when the configuration has none. */
    static String canonicalRole(Configuration config, String ref)
    {
        if (config == null || ref == null)
        {
            return null;
        }
        String normalized = MetadataTypeUtils.normalizeFqn(ref.trim());
        int dot = normalized.indexOf('.');
        if (dot <= 0 || !"Role".equals(normalized.substring(0, dot))) //$NON-NLS-1$
        {
            return null;
        }
        Role role = roleByName(config, normalized.substring(dot + 1).trim());
        return role == null ? null : ROLE_PREFIX + role.getName();
    }

    private static Role roleByName(Configuration config, String name)
    {
        for (Role role : config.getRoles())
        {
            if (role != null && name.equalsIgnoreCase(role.getName()))
            {
                return role;
            }
        }
        return null;
    }

    private static String applyInTx(IBmTransaction tx, IProgressMonitor pm, CommandInterfaceAddress address,
        Plan plan)
    {
        Configuration config = (Configuration)tx.getTopObjectByFqn(CONFIGURATION_FQN);
        Target target = commandInterfaceInTx(tx, config, address);
        CommandInterface commandInterface = target.commandInterface;
        for (Map.Entry<Item, Visibility> e : plan.visibility().entrySet())
        {
            SetCommandVisibilityTask.create(commandInterface, (Command)e.getKey().handle(),
                toAdjustableBoolean(config, e.getValue())).execute(tx, pm);
        }
        for (Map.Entry<Item, Group> e : plan.placement().entrySet())
        {
            SetCommandPlacementTask.create(commandInterface, (Command)e.getKey().handle(),
                (CommandGroup)e.getValue().handle()).execute(tx, pm);
        }
        for (Map.Entry<Group, List<Item>> e : plan.order().entrySet())
        {
            List<Command> commands = new ArrayList<>();
            for (Item item : e.getValue())
            {
                commands.add((Command)item.handle());
            }
            SetCommandOrderTask.create(commandInterface, (CommandGroup)e.getKey().handle(), commands)
                .execute(tx, pm);
        }
        return target.fqn;
    }

    /** The section's command interface in a write transaction and the FQN it is registered under. */
    static final class Target
    {
        final CommandInterface commandInterface;
        final String fqn;

        Target(CommandInterface commandInterface, String fqn)
        {
            this.commandInterface = commandInterface;
            this.fqn = fqn;
        }
    }

    /**
     * The section's command interface as a transaction object. A section that was never customized
     * has no stored object yet; it is attached under the platform's own external-property FQN, as
     * the editor's tasks do.
     */
    private static Target commandInterfaceInTx(IBmTransaction tx, Configuration config,
        CommandInterfaceAddress address)
    {
        EObject owner = ownerInTx(config, address);
        if (owner == null)
        {
            throw new IllegalStateException(address.ownerFqn() + " disappeared before the write"); //$NON-NLS-1$
        }
        EReference feature = featureOf(address);
        Object value = owner.eGet(feature, true);
        if (value instanceof CommandInterface && !((EObject)value).eIsProxy())
        {
            CommandInterface stored = tx.toTransactionObject((CommandInterface)value);
            return new Target(stored, ((IBmObject)stored).bmGetFqn());
        }
        return registeredOrAttached(tx, externalPropertyFqn(owner, feature, value));
    }

    /** The section's stored command interface in this transaction, or {@code null}; never attaches one. */
    private static CommandInterface storedInTx(IBmTransaction tx, CommandInterfaceAddress address)
    {
        EObject owner = ownerInTx((Configuration)tx.getTopObjectByFqn(CONFIGURATION_FQN), address);
        if (owner == null)
        {
            return null;
        }
        EReference feature = featureOf(address);
        Object value = owner.eGet(feature, true);
        if (value instanceof CommandInterface && !((EObject)value).eIsProxy())
        {
            return tx.toTransactionObject((CommandInterface)value);
        }
        IBmObject registered = tx.getTopObjectByFqn(externalPropertyFqn(owner, feature, value));
        return registered instanceof CommandInterface ? (CommandInterface)registered : null;
    }

    private static EObject ownerInTx(Configuration config, CommandInterfaceAddress address)
    {
        if (config == null || address.kind() != CommandInterfaceAddress.Kind.SUBSYSTEM)
        {
            return config;
        }
        String[] chain = address.subsystemChain();
        return SubsystemUtils.resolveByPath(config, chain, chain.length);
    }

    private static EReference featureOf(CommandInterfaceAddress address)
    {
        return address.kind() == CommandInterfaceAddress.Kind.SUBSYSTEM
            ? MdClassPackage.Literals.SUBSYSTEM__COMMAND_INTERFACE
            : MdClassPackage.Literals.CONFIGURATION__MAIN_SECTION_COMMAND_INTERFACE;
    }

    // ===== computed view vs stored section ==========================================================

    /**
     * Why a plan built from EDT's computed command interface would overwrite a newer stored
     * customization, or {@code null}. The computed view is recomputed asynchronously, and it keeps
     * copies of the stored fragments it was computed from, so a copy that differs from the stored
     * fragment in the same transaction proves the view lags. Checked for everything the plan writes:
     * the order of each reordered group and the placement of its commands, each placed command, and
     * each command whose visibility changes. Package-visible for tests.
     *
     * @param stored the section's stored command interface, or {@code null} when it stores nothing
     * @param plan the plan built in the same transaction
     * @return the reason, or {@code null} when the view agrees
     */
    static String staleReason(CommandInterface stored, Plan plan)
    {
        for (Group group : plan.order().keySet())
        {
            String reason = staleOrder(stored, plan, group);
            if (reason != null)
            {
                return reason;
            }
        }
        for (Item item : plan.placement().keySet())
        {
            if (!placementAgrees(stored, item.derived))
            {
                return staleView("the group of " + item.fqn()); //$NON-NLS-1$
            }
        }
        for (Item item : plan.visibility().keySet())
        {
            if (!visibilityAgrees(stored, item.derived))
            {
                return staleView("the visibility of " + item.fqn()); //$NON-NLS-1$
            }
        }
        // Every command an entry named, so a lagging view cannot pass a request off as already done.
        for (String fqn : plan.touched())
        {
            String reason = staleTouched(stored, plan, fqn);
            if (reason != null)
            {
                return reason;
            }
        }
        return null;
    }

    private static String staleTouched(CommandInterface stored, Plan plan, String fqn)
    {
        if (plan.section == null)
        {
            return null;
        }
        for (Group group : plan.section.groups())
        {
            for (Item item : group.items)
            {
                if (!item.fqn().equals(fqn))
                {
                    continue;
                }
                if (!visibilityAgrees(stored, item.derived))
                {
                    return staleView("the visibility of " + fqn); //$NON-NLS-1$
                }
                return staleOrder(stored, plan, group);
            }
        }
        return null;
    }

    private static String staleOrder(CommandInterface stored, Plan plan, Group group)
    {
        if (!(group.derived instanceof CommandItemGroup))
        {
            return null;
        }
        CommandItemGroup derived = (CommandItemGroup)group.derived;
        CommandsOrderFragment storedOrder = orderFragment(stored, derived.getCommandGroup());
        CommandsOrderFragment derivedOrder = derived.isItemsOrderCustomized() ? derived.getOrderFragment() : null;
        if (!sameCommands(storedOrder, derivedOrder))
        {
            return staleView("the order of " + group.id()); //$NON-NLS-1$
        }
        for (Item item : group.items)
        {
            if (!placementAgrees(stored, item.derived))
            {
                return staleView("the group of " + item.fqn()); //$NON-NLS-1$
            }
        }
        // A command the stored section places here must not be shown in another group.
        if (stored != null && stored.getCommandsPlacement() != null)
        {
            for (CommandsPlacementFragment fragment : stored.getCommandsPlacement().getPlacementFragments())
            {
                if (!sameGroup(fragment.getGroup(), derived.getCommandGroup()))
                {
                    continue;
                }
                for (Command command : fragment.getCommands())
                {
                    if (effectivePlacement(stored, command) != fragment)
                    {
                        continue; // a later fragment places it elsewhere
                    }
                    Item elsewhere = itemOutside(plan.section, group, command);
                    if (elsewhere != null)
                    {
                        return staleView("the group of " + elsewhere.fqn()); //$NON-NLS-1$
                    }
                }
            }
        }
        return null;
    }

    private static String staleView(String what)
    {
        return "EDT has not yet recomputed its view of this section after another change to " + what //$NON-NLS-1$
            + ", so this batch was checked against an outdated state."; //$NON-NLS-1$
    }

    private static Item itemOutside(CommandInterfaceSection section, Group group, Command command)
    {
        if (section == null)
        {
            return null;
        }
        for (Group other : section.groups())
        {
            if (other == group)
            {
                continue;
            }
            for (Item item : other.items)
            {
                if (item.derived instanceof CommandItem && sameObject(storedCommand((CommandItem)item.derived), command))
                {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * Whether the item's computed group agrees with the stored placement: a placed command sits in its
     * effective fragment's group, an unplaced one is not customized. A fragment naming the group the
     * command already sits in leaves it uncustomized, and the managed main section stores such fragments.
     */
    private static boolean placementAgrees(CommandInterface stored, Object derivedItem)
    {
        if (!(derivedItem instanceof CommandItem))
        {
            return true;
        }
        CommandItem item = (CommandItem)derivedItem;
        CommandsPlacementFragment fragment = effectivePlacement(stored, storedCommand(item));
        if (fragment == null)
        {
            return !item.isGroupCustomized();
        }
        return item.getGroup() != null && sameGroup(fragment.getGroup(), item.getGroup().getCommandGroup());
    }

    /**
     * The fragment that decides where a command sits: EDT applies the fragments in stored order, each
     * moving the command into its group, so the last one listing it wins.
     */
    private static CommandsPlacementFragment effectivePlacement(CommandInterface stored, Command command)
    {
        CommandsPlacementFragment effective = null;
        if (stored != null && stored.getCommandsPlacement() != null)
        {
            for (CommandsPlacementFragment fragment : stored.getCommandsPlacement().getPlacementFragments())
            {
                if (containsObject(fragment.getCommands(), command))
                {
                    effective = fragment;
                }
            }
        }
        return effective;
    }

    /** Whether the item's computed visibility agrees with the stored one: customized exactly when stored, same value. */
    private static boolean visibilityAgrees(CommandInterface stored, Object derivedItem)
    {
        if (!(derivedItem instanceof CommandItem))
        {
            return true;
        }
        CommandItem item = (CommandItem)derivedItem;
        Command command = storedCommand(item);
        if (stored != null && stored.getCommandsVisibility() != null)
        {
            for (CommandsVisibilityFragment fragment : stored.getCommandsVisibility().getVisibilityFragments())
            {
                if (sameObject(fragment.getCommand(), command))
                {
                    return item.isVisibilityCustomized()
                        && visibilityOf(fragment.getVisible()).equals(visibilityOf(item.getVisibility()));
                }
            }
        }
        return !item.isVisibilityCustomized();
    }

    private static CommandsOrderFragment orderFragment(CommandInterface stored, CommandGroup group)
    {
        if (stored != null && stored.getCommandsOrder() != null)
        {
            for (CommandsOrderFragment fragment : stored.getCommandsOrder().getOrderFragments())
            {
                if (sameGroup(fragment.getGroup(), group))
                {
                    return fragment;
                }
            }
        }
        return null;
    }

    private static boolean sameCommands(CommandsOrderFragment a, CommandsOrderFragment b)
    {
        if (a == null || b == null)
        {
            return a == b;
        }
        List<Command> left = a.getCommands();
        List<Command> right = b.getCommands();
        if (left.size() != right.size())
        {
            return false;
        }
        for (int i = 0; i < left.size(); i++)
        {
            if (!sameObject(left.get(i), right.get(i)))
            {
                return false;
            }
        }
        return true;
    }

    /** The command as the stored section references it: an unresolved one by its stored proxy. */
    private static Command storedCommand(CommandItem item)
    {
        Command command = item.getCommand();
        return command instanceof UnresolvedCommand ? ((UnresolvedCommand)command).getProxyCommand() : command;
    }

    private static boolean containsObject(List<? extends EObject> list, EObject object)
    {
        for (EObject candidate : list)
        {
            if (sameObject(candidate, object))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Identity of command interface references: same object, proxy URI, a standard command by owner
     * and name, else BM id.
     */
    static boolean sameObject(EObject a, EObject b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null || a.eIsProxy() != b.eIsProxy())
        {
            return false;
        }
        if (a.eIsProxy())
        {
            return EcoreUtil.getURI(a).equals(EcoreUtil.getURI(b));
        }
        if (a instanceof StandardCommand && b instanceof StandardCommand)
        {
            // Transient children of their owner; the .cmi names them by owner and name.
            String name = ((StandardCommand)a).getName();
            return name != null && name.equals(((StandardCommand)b).getName()) && a.eContainer() != null
                && sameObject(a.eContainer(), b.eContainer());
        }
        if (a instanceof IBmObject && b instanceof IBmObject)
        {
            // A detached object has no id yet (-1), so two of them are never the same by id.
            long id = ((IBmObject)a).bmGetId();
            return id != -1L && id == ((IBmObject)b).bmGetId();
        }
        return a.equals(b);
    }

    /** The platform's group identity: a standard group by name, anything else as {@link #sameObject}. */
    static boolean sameGroup(CommandGroup a, CommandGroup b)
    {
        if (a instanceof StandardCommandGroup && b instanceof StandardCommandGroup && !a.eIsProxy() && !b.eIsProxy())
        {
            String name = ((StandardCommandGroup)a).getName();
            return name != null && name.equals(((StandardCommandGroup)b).getName());
        }
        return sameObject(a, b);
    }

    /**
     * The command interface registered under {@code fqn}, or a new one attached there. Either way the
     * FQN reported is {@code fqn} itself, never {@code bmGetFqn()} of an object this transaction attached.
     */
    static Target registeredOrAttached(IBmTransaction tx, String fqn)
    {
        IBmObject existing = tx.getTopObjectByFqn(fqn);
        if (existing instanceof CommandInterface)
        {
            return new Target((CommandInterface)existing, fqn);
        }
        CommandInterface created = CmiFactory.eINSTANCE.createCommandInterface();
        tx.attachTopObject((IBmObject)created, fqn);
        return new Target(created, fqn);
    }

    private static String externalPropertyFqn(EObject owner, EReference feature, Object proxy)
    {
        ITopObjectFqnGenerator generator = Activator.getDefault().getTopObjectFqnGenerator();
        if (generator != null)
        {
            return generator.generateExternalPropertyFqn(owner, feature);
        }
        if (proxy instanceof EObject)
        {
            return BmUriUtil.extractTopObjectFqn(EcoreUtil.getURI((EObject)proxy));
        }
        throw new IllegalStateException("Cannot name the command interface of " //$NON-NLS-1$
            + ((IBmObject)owner).bmGetFqn() + ": the FQN generator is unavailable"); //$NON-NLS-1$
    }

    private static AdjustableBoolean toAdjustableBoolean(Configuration config, Visibility visibility)
    {
        AdjustableBoolean value = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        value.setCommon(visibility.common());
        for (Map.Entry<String, Boolean> e : visibility.roles().entrySet())
        {
            Role role = roleByName(config, e.getKey().substring(ROLE_PREFIX.length()));
            if (role == null)
            {
                throw new IllegalStateException(e.getKey() + " disappeared before the write"); //$NON-NLS-1$
            }
            ForRoleType forRole = MdClassFactory.eINSTANCE.createForRoleType();
            forRole.setRole(role);
            forRole.setValue(Boolean.TRUE.equals(e.getValue()));
            value.getFor().add(forRole);
        }
        return value;
    }

    /**
     * What the command interface stores for every command an entry named and for the ordered groups,
     * read after the write. Package-visible for tests.
     */
    static JsonObject storedState(IBmTransaction tx, String fqn, Plan plan)
    {
        IBmObject top = fqn == null ? null : tx.getTopObjectByFqn(fqn);
        CommandInterface commandInterface = top instanceof CommandInterface ? (CommandInterface)top : null;
        JsonObject perCommand = new JsonObject();
        for (String command : plan.touched())
        {
            JsonObject entry = new JsonObject();
            entry.add("visible", storedVisibility(commandInterface, command)); //$NON-NLS-1$
            entry.addProperty("group", storedGroup(commandInterface, command)); //$NON-NLS-1$
            perCommand.add(command, entry);
        }
        JsonObject orders = new JsonObject();
        for (Group group : plan.order().keySet())
        {
            orders.add(group.id(), storedOrder(commandInterface, group.id()));
        }
        JsonObject stored = new JsonObject();
        stored.add("commands", perCommand); //$NON-NLS-1$
        stored.add("order", orders); //$NON-NLS-1$
        return stored;
    }

    private static JsonElement storedVisibility(CommandInterface commandInterface, String command)
    {
        if (commandInterface != null && commandInterface.getCommandsVisibility() != null)
        {
            for (CommandsVisibilityFragment fragment : commandInterface.getCommandsVisibility()
                .getVisibilityFragments())
            {
                if (command.equals(nameOf(fragment.getCommand())))
                {
                    Visibility v = visibilityOf(fragment.getVisible());
                    JsonObject roles = new JsonObject();
                    for (Map.Entry<String, Boolean> e : v.roles().entrySet())
                    {
                        roles.addProperty(e.getKey(), e.getValue());
                    }
                    JsonObject value = new JsonObject();
                    value.addProperty("common", v.common()); //$NON-NLS-1$
                    value.add("roles", roles); //$NON-NLS-1$
                    return value;
                }
            }
        }
        return new JsonPrimitive(DEFAULT);
    }

    private static String storedGroup(CommandInterface commandInterface, String command)
    {
        if (commandInterface != null && commandInterface.getCommandsPlacement() != null)
        {
            for (CommandsPlacementFragment fragment : commandInterface.getCommandsPlacement()
                .getPlacementFragments())
            {
                for (Command c : fragment.getCommands())
                {
                    if (command.equals(nameOf(c)))
                    {
                        return groupName(fragment.getGroup());
                    }
                }
            }
        }
        return DEFAULT;
    }

    private static JsonArray storedOrder(CommandInterface commandInterface, String groupId)
    {
        JsonArray order = new JsonArray();
        if (commandInterface != null && commandInterface.getCommandsOrder() != null)
        {
            for (CommandsOrderFragment fragment : commandInterface.getCommandsOrder().getOrderFragments())
            {
                if (groupId.equals(groupName(fragment.getGroup())))
                {
                    for (Command c : fragment.getCommands())
                    {
                        order.add(nameOf(c));
                    }
                }
            }
        }
        return order;
    }

    private static String nameOf(Command command)
    {
        String[] names = commandNames(command);
        if (names != null)
        {
            return names[0];
        }
        return command == null ? "" : String.valueOf(EcoreUtil.getURI(command)); //$NON-NLS-1$
    }

    private static String groupName(CommandGroup group)
    {
        if (group instanceof StandardCommandGroup && !group.eIsProxy())
        {
            return ((StandardCommandGroup)group).getName();
        }
        if (group instanceof com._1c.g5.v8.dt.metadata.mdclass.CommandGroup && !group.eIsProxy())
        {
            return COMMAND_GROUP_PREFIX + ((MdObject)group).getName();
        }
        return group == null ? "" : String.valueOf(EcoreUtil.getURI(group)); //$NON-NLS-1$
    }
}
