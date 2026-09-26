/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.cmi.model.CommandsOrderFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsPlacementFragment;
import com._1c.g5.v8.dt.cmi.model.CommandsVisibilityFragment;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CmiDerivedDataFactory;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandItem;
import com._1c.g5.v8.dt.cmi.model.deriveddata.CommandItemGroup;
import com._1c.g5.v8.dt.mcore.Command;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.ForRoleType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Group;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Item;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Panel;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Plan;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link CommandInterfaceSupport#staleReason}: a write planned from EDT's computed command
 * interface is refused when that view lags the stored section it would overwrite, and allowed when
 * the view's copies of the stored fragments agree with the stored section.
 */
public class CommandInterfaceStaleViewTest
{
    private static final String TOOLS = "CommandGroup.Tools"; //$NON-NLS-1$
    private static final String OTHER = "CommandGroup.Other"; //$NON-NLS-1$
    private static final String PRINT = "CommonCommand.Print"; //$NON-NLS-1$
    private static final String ARCHIVE = "CommonCommand.Archive"; //$NON-NLS-1$
    private static final String MOVE_PRINT = "[{command:'CommonCommand.Print', after:'CommonCommand.Export'}]"; //$NON-NLS-1$

    private final CommandGroup toolsGroup = group("Tools"); //$NON-NLS-1$
    private final CommandGroup otherGroup = group("Other"); //$NON-NLS-1$
    private final CommonCommand print = command("Print"); //$NON-NLS-1$
    private final CommonCommand export = command("Export"); //$NON-NLS-1$
    private final CommonCommand archive = command("Archive"); //$NON-NLS-1$
    private final CommandItemGroup derivedTools = derivedGroup(toolsGroup);
    private final CommandItemGroup derivedOther = derivedGroup(otherGroup);

    @Test
    public void testANewerStoredOrderThanAnUncustomizedViewIsRefused()
    {
        items(derivedTools, print, export, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeOrder(stored, toolsGroup, archive, print, export);

        assertEquals(staleView("the order of " + TOOLS), //$NON-NLS-1$
            CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testANewerStoredOrderThanACustomizedViewIsRefused()
    {
        items(derivedTools, print, export, archive);
        viewOrder(derivedTools, print, export, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeOrder(stored, toolsGroup, archive, print, export);

        assertEquals(staleView("the order of " + TOOLS), //$NON-NLS-1$
            CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testAStoredOrderTheViewNoLongerHasIsRefused()
    {
        items(derivedTools, print, export, archive);
        viewOrder(derivedTools, print, export, archive);

        assertEquals(staleView("the order of " + TOOLS), //$NON-NLS-1$
            CommandInterfaceSupport.staleReason(CmiFactory.eINSTANCE.createCommandInterface(), plan(MOVE_PRINT)));
    }

    @Test
    public void testAViewWhoseOrderCopyMatchesTheStoredFragmentIsWritten()
    {
        items(derivedTools, archive, print, export);
        viewOrder(derivedTools, archive, print, export);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeOrder(stored, toolsGroup, archive, print, export);

        assertNull(CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testASectionThatStoresNothingAgreesWithAnUncustomizedView()
    {
        items(derivedTools, print, export, archive);

        assertNull(CommandInterfaceSupport.staleReason(null, plan(MOVE_PRINT)));
        assertNull(CommandInterfaceSupport.staleReason(CmiFactory.eINSTANCE.createCommandInterface(),
            plan(MOVE_PRINT)));
    }

    @Test
    public void testACommandANewerWritePlacedInTheReorderedGroupIsRefused()
    {
        items(derivedTools, print, export);
        items(derivedOther, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storePlacement(stored, toolsGroup, archive);

        assertEquals(staleView("the group of " + ARCHIVE), //$NON-NLS-1$
            CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testAManagedMainSectionReorderIsWritten()
    {
        // The managed main section places every command, most in the group they already sit in, which
        // EDT leaves uncustomized.
        items(derivedTools, print, export, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storePlacement(stored, toolsGroup, print, export, archive);

        assertNull(CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testAManagedMainSectionMoveIsWritten()
    {
        items(derivedTools, print, export);
        items(derivedOther, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storePlacement(stored, toolsGroup, print, export);
        storePlacement(stored, otherGroup, archive);

        assertNull(CommandInterfaceSupport.staleReason(stored,
            plan("[{command:'CommonCommand.Print', group:'CommandGroup.Other', after:'CommonCommand.Archive'}]"))); //$NON-NLS-1$
    }

    @Test
    public void testAManagedMainSectionCommandANewerWriteMovedIsRefused()
    {
        items(derivedTools, print, export);
        items(derivedOther, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        // A newer write moved Export to Other; the view still shows it in Tools.
        storePlacement(stored, toolsGroup, print);
        storePlacement(stored, otherGroup, archive, export);

        assertEquals(staleView("the group of CommonCommand.Export"), //$NON-NLS-1$
            CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testACommandALaterFragmentPlacesElsewhereIsNotRequiredHere()
    {
        // EDT applies placement fragments in stored order, so the last one listing a command wins.
        items(derivedTools, print, export);
        items(derivedOther, archive).get(0).setGroupCustomized(true);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storePlacement(stored, toolsGroup, archive);
        storePlacement(stored, otherGroup, archive);

        assertNull(CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testACommandTheLastFragmentPlacesHereButTheViewShowsElsewhereIsRefused()
    {
        items(derivedTools, print, export);
        items(derivedOther, archive).get(0).setGroupCustomized(true);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storePlacement(stored, otherGroup, archive);
        storePlacement(stored, toolsGroup, archive);

        assertEquals(staleView("the group of " + ARCHIVE), //$NON-NLS-1$
            CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testACommandALaterFragmentPlacesInAnUnresolvedGroupIsNotRequiredHere()
    {
        // The unresolved group is not part of the section, so the command is in none of its groups.
        items(derivedTools, print, export);
        CommandGroup missing = group("Missing"); //$NON-NLS-1$
        ((InternalEObject)missing).eSetProxyURI(URI.createURI("bm://TestConfiguration/CommandGroup.Missing")); //$NON-NLS-1$
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storePlacement(stored, toolsGroup, archive);
        storePlacement(stored, missing, archive);

        assertNull(CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testANoOpTheStoredVisibilityContradictsIsRefused()
    {
        // The view still shows Print visible; the stored section has hidden it since.
        items(derivedTools, print, export);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeVisibility(stored, print, visibility(false, null, false));

        Plan plan = plan("[{command:'CommonCommand.Print', visible:true}]"); //$NON-NLS-1$
        assertEquals(true, plan.isEmpty());
        assertEquals(staleView("the visibility of " + PRINT), CommandInterfaceSupport.staleReason(stored, plan)); //$NON-NLS-1$
    }

    @Test
    public void testANoOpTheStoredSectionAgreesWithPasses()
    {
        items(derivedTools, print, export);

        Plan plan = plan("[{command:'CommonCommand.Print', visible:true}, {command:'CommonCommand.Export', " //$NON-NLS-1$
            + "after:'CommonCommand.Print'}]"); //$NON-NLS-1$
        assertEquals(true, plan.isEmpty());
        assertNull(CommandInterfaceSupport.staleReason(CmiFactory.eINSTANCE.createCommandInterface(), plan));
    }

    @Test
    public void testAVisibilityEntryIsNotRefusedForItsGroupsOrder()
    {
        // The view lags a stored order of Tools, which a visibility write does not touch.
        items(derivedTools, print, export, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeOrder(stored, toolsGroup, archive, print, export);

        assertNull(CommandInterfaceSupport.staleReason(stored,
            plan("[{command:'CommonCommand.Print', visible:false}]"))); //$NON-NLS-1$
        assertNull(CommandInterfaceSupport.staleReason(stored,
            plan("[{command:'CommonCommand.Print', visible:true}]"))); //$NON-NLS-1$
    }

    @Test
    public void testAnAnchorNoOpIsStillCheckedAgainstItsGroupsOrder()
    {
        items(derivedTools, print, export, archive);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeOrder(stored, toolsGroup, archive, print, export);

        Plan plan = plan("[{command:'CommonCommand.Export', after:'CommonCommand.Print'}]"); //$NON-NLS-1$
        assertEquals(true, plan.isEmpty());
        assertEquals(staleView("the order of " + TOOLS), CommandInterfaceSupport.staleReason(stored, plan)); //$NON-NLS-1$
    }

    @Test
    public void testAPlacementTheViewAlreadyShowsIsWritten()
    {
        items(derivedTools, print, export, archive).get(2).setGroupCustomized(true);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storePlacement(stored, toolsGroup, archive);

        assertNull(CommandInterfaceSupport.staleReason(stored, plan(MOVE_PRINT)));
    }

    @Test
    public void testANewerRoleOverrideThanTheViewIsRefused()
    {
        items(derivedTools, print, export);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeVisibility(stored, print, visibility(true, role("Manager"), false)); //$NON-NLS-1$

        // Hiding Print from the view would write {common:false} and drop the stored Manager override.
        assertEquals(staleView("the visibility of " + PRINT), //$NON-NLS-1$
            CommandInterfaceSupport.staleReason(stored, plan("[{command:'CommonCommand.Print', visible:false}]"))); //$NON-NLS-1$
    }

    @Test
    public void testAVisibilityTheViewAlreadyShowsIsWritten()
    {
        Role manager = role("Manager"); //$NON-NLS-1$
        CommandItem item = items(derivedTools, print, export).get(0);
        item.setVisibility(visibility(true, manager, false));
        item.setVisibilityCustomized(true);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        storeVisibility(stored, print, visibility(true, manager, false));

        assertNull(CommandInterfaceSupport.staleReason(stored,
            plan("[{command:'CommonCommand.Print', visible:false}]"))); //$NON-NLS-1$
    }

    @Test
    public void testDistinctStandardCommandObjectsAreTheSameByOwnerAndName()
    {
        Catalog products = catalog("Products"); //$NON-NLS-1$
        Catalog orders = catalog("Orders"); //$NON-NLS-1$
        StandardCommand stored = standardCommand(products, "OpenList"); //$NON-NLS-1$
        StandardCommand derived = standardCommand(products, "OpenList"); //$NON-NLS-1$

        assertEquals(true, CommandInterfaceSupport.sameObject(stored, derived));
        assertEquals(false, CommandInterfaceSupport.sameObject(stored, standardCommand(products, "Create"))); //$NON-NLS-1$
        assertEquals(false, CommandInterfaceSupport.sameObject(stored, standardCommand(orders, "OpenList"))); //$NON-NLS-1$
    }

    @Test
    public void testAStandardCommandOrderReadThroughDistinctObjectsIsWritten()
    {
        Catalog products = catalog("Products"); //$NON-NLS-1$
        StandardCommand openList = standardCommand(products, "OpenList"); //$NON-NLS-1$
        items(derivedTools, openList, print);
        viewOrder(derivedTools, openList, print);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        // The stored fragment references its own object for the same standard command.
        storeOrder(stored, toolsGroup, standardCommand(products, "OpenList"), print); //$NON-NLS-1$

        assertNull(CommandInterfaceSupport.staleReason(stored,
            plan("[{command:'CommonCommand.Print', before:'Catalog.Products.StandardCommand.OpenList'}]"))); //$NON-NLS-1$
    }

    @Test
    public void testDetachedObjectsAreNotTheSameByTheirMissingId()
    {
        assertEquals(false, CommandInterfaceSupport.sameObject(print, export));
        assertEquals(true, CommandInterfaceSupport.sameObject(print, print));
    }

    // ===== fixtures =================================================================================

    private static String staleView(String what)
    {
        return "EDT has not yet recomputed its view of this section after another change to " + what //$NON-NLS-1$
            + ", so this batch was checked against an outdated state."; //$NON-NLS-1$
    }

    private Plan plan(String json)
    {
        List<Group> groups = Arrays.asList(sectionGroup(TOOLS, toolsGroup, derivedTools),
            sectionGroup(OTHER, otherGroup, derivedOther));
        CommandInterfaceSection section = new CommandInterfaceSection("Subsystem.Sales", //$NON-NLS-1$
            "Subsystem.Sales.CommandInterface", groups); //$NON-NLS-1$
        List<JsonObject> entries = new ArrayList<>();
        JsonParser.parseString(json).getAsJsonArray().forEach(e -> entries.add(e.getAsJsonObject()));
        CommandInterfaceSection.PlanResult result = section.plan(entries, ref -> null);
        assertNull(result.error, result.error);
        return result.plan;
    }

    /** A section group read from the computed group, as {@code buildSection} reads it. */
    private static Group sectionGroup(String id, CommandGroup handle, CommandItemGroup derived)
    {
        Group group = new Group(id, null, Panel.ACTIONS, true, handle, derived.isItemsOrderCustomized());
        group.derived = derived;
        for (CommandItem commandItem : derived.getItems())
        {
            Item item = new Item(fqn(commandItem.getCommand()), null, commandItem.getCommand(),
                CommandInterfaceSupport.visibilityOf(commandItem.getVisibility()),
                commandItem.isVisibilityCustomized(), commandItem.isGroupCustomized(), true);
            item.derived = commandItem;
            group.items.add(item);
        }
        return group;
    }

    /** The section address of a fixture command (detached objects have no BM FQN). */
    private static String fqn(Command command)
    {
        if (command instanceof StandardCommand)
        {
            return "Catalog." + ((Catalog)command.eContainer()).getName() + ".StandardCommand." //$NON-NLS-1$ //$NON-NLS-2$
                + ((StandardCommand)command).getName();
        }
        return "CommonCommand." + ((CommonCommand)command).getName(); //$NON-NLS-1$
    }

    private static CommandGroup group(String name)
    {
        CommandGroup group = MdClassFactory.eINSTANCE.createCommandGroup();
        group.setName(name);
        return group;
    }

    private static CommonCommand command(String name)
    {
        CommonCommand command = MdClassFactory.eINSTANCE.createCommonCommand();
        command.setName(name);
        return command;
    }

    private static Catalog catalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    /** A standard command of {@code owner}, a new object each call, as a separate resolution would give. */
    private static StandardCommand standardCommand(Catalog owner, String name)
    {
        StandardCommand command = MdClassFactory.eINSTANCE.createStandardCommand();
        command.setName(name);
        owner.getStandardCommands().add(command);
        return command;
    }

    private static CommandItemGroup derivedGroup(CommandGroup group)
    {
        CommandItemGroup derived = CmiDerivedDataFactory.eINSTANCE.createCustomCommandItemGroup();
        derived.setCommandGroup(group);
        return derived;
    }

    private static List<CommandItem> items(CommandItemGroup group, Command... commands)
    {
        List<CommandItem> added = new ArrayList<>();
        for (Command command : commands)
        {
            CommandItem item = CmiDerivedDataFactory.eINSTANCE.createCommandItem();
            item.setCommand(command);
            item.setVisibility(visibility(true, null, false));
            group.getItems().add(item);
            added.add(item);
        }
        return added;
    }

    private static void viewOrder(CommandItemGroup group, Command... commands)
    {
        CommandsOrderFragment copy = CmiFactory.eINSTANCE.createCommandsOrderFragment();
        copy.setGroup(group.getCommandGroup());
        copy.getCommands().addAll(Arrays.asList(commands));
        group.setOrderFragment(copy);
        group.setItemsOrderCustomized(true);
    }

    private static void storeOrder(CommandInterface stored, CommandGroup group, Command... commands)
    {
        if (stored.getCommandsOrder() == null)
        {
            stored.setCommandsOrder(CmiFactory.eINSTANCE.createCommandsOrder());
        }
        CommandsOrderFragment fragment = CmiFactory.eINSTANCE.createCommandsOrderFragment();
        fragment.setGroup(group);
        fragment.getCommands().addAll(Arrays.asList(commands));
        stored.getCommandsOrder().getOrderFragments().add(fragment);
    }

    private static void storePlacement(CommandInterface stored, CommandGroup group, Command... commands)
    {
        if (stored.getCommandsPlacement() == null)
        {
            stored.setCommandsPlacement(CmiFactory.eINSTANCE.createCommandsPlacement());
        }
        CommandsPlacementFragment fragment = CmiFactory.eINSTANCE.createCommandsPlacementFragment();
        fragment.setGroup(group);
        fragment.getCommands().addAll(Arrays.asList(commands));
        stored.getCommandsPlacement().getPlacementFragments().add(fragment);
    }

    private static void storeVisibility(CommandInterface stored, Command command, AdjustableBoolean value)
    {
        if (stored.getCommandsVisibility() == null)
        {
            stored.setCommandsVisibility(CmiFactory.eINSTANCE.createCommandsVisibility());
        }
        CommandsVisibilityFragment fragment = CmiFactory.eINSTANCE.createCommandsVisibilityFragment();
        fragment.setCommand(command);
        fragment.setVisible(value);
        stored.getCommandsVisibility().getVisibilityFragments().add(fragment);
    }

    private static Role role(String name)
    {
        Role role = MdClassFactory.eINSTANCE.createRole();
        role.setName(name);
        return role;
    }

    private static AdjustableBoolean visibility(boolean common, Role role, boolean roleValue)
    {
        AdjustableBoolean value = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        value.setCommon(common);
        if (role != null)
        {
            ForRoleType forRole = MdClassFactory.eINSTANCE.createForRoleType();
            forRole.setRole(role);
            forRole.setValue(roleValue);
            value.getFor().add(forRole);
        }
        return value;
    }
}
