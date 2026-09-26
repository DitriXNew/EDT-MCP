/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Group;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Item;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Panel;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Plan;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Visibility;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the transaction flow of {@link CommandInterfaceSupport#edit}: the batch is validated in a
 * read, and the write applies a plan rebuilt from its OWN transaction, so a section that changed in
 * between is never written from the stale read. Also pins the FQN a written command interface reports.
 */
public class CommandInterfaceSupportEditTest
{
    private static final String OWNER = "Subsystem.Sales"; //$NON-NLS-1$
    private static final String SECTION_FQN = OWNER + ".CommandInterface"; //$NON-NLS-1$
    private static final String ORDINARY = "NavigationPanelOrdinary"; //$NON-NLS-1$
    private static final String PRINT = "CommonCommand.Print"; //$NON-NLS-1$
    private static final String EXPORT = "CommonCommand.Export"; //$NON-NLS-1$
    private static final String ARCHIVE = "CommonCommand.Archive"; //$NON-NLS-1$

    private final IBmTransaction readTx = mock(IBmTransaction.class);
    private final IBmTransaction writeTx = mock(IBmTransaction.class);
    private final IBmModel model = mock(IBmModel.class);
    private final List<Plan> applied = new ArrayList<>();
    /** What the write's staleness check reports; {@code null} means the computed view agrees. */
    private String stale;

    public CommandInterfaceSupportEditTest()
    {
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(readTx, null);
        });
        when(model.execute(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(writeTx, null);
        });
    }

    @Test
    public void testARefusedBatchNeverOpensAWrite()
    {
        WriteScope scope = new WriteScope();
        CommandInterfaceSupport.EditResult[] result = new CommandInterfaceSupport.EditResult[1];
        WriteScope.runWithScope(scope, () -> result[0] = edit(section(EXPORT), section(PRINT, EXPORT),
            "[{command:'CommonCommand.Print', visible:false}]")); //$NON-NLS-1$

        assertTrue(result[0].error, result[0].error.contains("'CommonCommand.Print' is not in the command interface")); //$NON-NLS-1$
        verify(model, never()).execute(any());
        assertTrue(applied.isEmpty());
        assertFalse("a refusal must not be reported as a committed write", scope.hasRecordedWrite()); //$NON-NLS-1$
    }

    @Test
    public void testABatchTheWriteNoLongerValidatesRollsBackAndSaysSo()
    {
        WriteScope scope = new WriteScope();
        CommandInterfaceSupport.EditResult[] result = new CommandInterfaceSupport.EditResult[1];
        // Valid when read; by the time the write runs, Print has left the section.
        WriteScope.runWithScope(scope, () -> result[0] = edit(section(PRINT, EXPORT), section(EXPORT),
            "[{command:'CommonCommand.Print', visible:false}]")); //$NON-NLS-1$

        String error = result[0].error;
        assertTrue(error, error.startsWith("The command interface of Subsystem.Sales changed while this call " //$NON-NLS-1$
            + "was writing it, and the batch no longer applies: commands[0].command 'CommonCommand.Print' is " //$NON-NLS-1$
            + "not in the command interface")); //$NON-NLS-1$
        assertTrue(error, error.endsWith(" Nothing was changed; read it again with get_metadata_details and retry.")); //$NON-NLS-1$
        assertNull(result[0].plan);
        assertNull(result[0].writtenFqn);
        verify(model, times(1)).execute(any());
        assertTrue("nothing may be applied from the stale read", applied.isEmpty()); //$NON-NLS-1$
        assertFalse("the write threw, so it did not commit", scope.hasRecordedWrite()); //$NON-NLS-1$
    }

    @Test
    public void testTheWriteAppliesThePlanRebuiltFromItsOwnTransaction()
    {
        CommandInterfaceSection read = section(PRINT, EXPORT);
        CommandInterfaceSection written = section(PRINT, EXPORT, ARCHIVE);
        CommandInterfaceSupport.EditResult result = edit(read, written,
            "[{command:'CommonCommand.Print', after:'CommonCommand.Export'}]"); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertEquals(1, applied.size());
        Plan plan = applied.get(0);
        assertSame("the reported plan is the applied one", plan, result.plan); //$NON-NLS-1$
        Group group = plan.order().keySet().iterator().next();
        assertSame("the group handle comes from the write transaction", //$NON-NLS-1$
            written.findGroup(ORDINARY).handle(), group.handle());
        List<String> order = new ArrayList<>();
        for (Item item : plan.order().get(group))
        {
            order.add(item.fqn());
            assertSame("every command handle comes from the write transaction", //$NON-NLS-1$
                written.findItem(item.fqn()).handle(), item.handle());
        }
        // The read's order would have dropped Archive, which joined the group in between.
        assertEquals(Arrays.asList(EXPORT, PRINT, ARCHIVE), order);
        assertEquals(SECTION_FQN, result.writtenFqn);
    }

    @Test
    public void testAComputedViewThatLagsTheStoredSectionRollsBackAndSaysSo()
    {
        stale = "EDT has not yet recomputed its view of this section after another change to the order of " //$NON-NLS-1$
            + ORDINARY + ", so writing from it would undo that change."; //$NON-NLS-1$
        WriteScope scope = new WriteScope();
        CommandInterfaceSupport.EditResult[] result = new CommandInterfaceSupport.EditResult[1];
        WriteScope.runWithScope(scope, () -> result[0] = edit(section(PRINT, EXPORT), section(PRINT, EXPORT),
            "[{command:'CommonCommand.Print', after:'CommonCommand.Export'}]")); //$NON-NLS-1$

        assertEquals("The command interface of Subsystem.Sales changed while this call was writing it, and the " //$NON-NLS-1$
            + "batch no longer applies: " + stale + " Nothing was changed; read it again with " //$NON-NLS-1$ //$NON-NLS-2$
            + "get_metadata_details and retry.", result[0].error); //$NON-NLS-1$
        assertNull(result[0].plan);
        assertNull(result[0].writtenFqn);
        assertTrue("nothing may be applied from a lagging view", applied.isEmpty()); //$NON-NLS-1$
        assertFalse("the write threw, so it did not commit", scope.hasRecordedWrite()); //$NON-NLS-1$
    }

    @Test
    public void testAPlanThatChangesNothingIsNotCheckedForStaleness()
    {
        stale = "must not be asked"; //$NON-NLS-1$
        CommandInterfaceSupport.EditResult result = edit(section(PRINT, EXPORT), sectionHiding(PRINT, PRINT, EXPORT),
            "[{command:'CommonCommand.Print', visible:false}]"); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertTrue(result.plan.isEmpty());
        assertTrue(applied.isEmpty());
    }

    @Test
    public void testAWriteThatFindsTheChangeAlreadyMadeWritesNothing()
    {
        CommandInterfaceSection written = sectionHiding(PRINT, PRINT, EXPORT);
        CommandInterfaceSupport.EditResult result = edit(section(PRINT, EXPORT), written,
            "[{command:'CommonCommand.Print', visible:false}]"); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertTrue(applied.isEmpty());
        assertNull(result.writtenFqn);
        assertTrue(result.plan.isEmpty());
        assertEquals(Collections.singletonList(PRINT), result.plan.unchanged());
    }

    @Test
    public void testANoOpBatchNeverOpensAWrite()
    {
        CommandInterfaceSupport.EditResult result = edit(section(PRINT, EXPORT), section(EXPORT),
            "[{command:'CommonCommand.Print', visible:true}]"); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertTrue(result.plan.isEmpty());
        verify(model, never()).execute(any());
    }

    @Test
    public void testTheReadBackCoversEveryNamedCommandNotOnlyTheChangedOnes()
    {
        // Archive is only reordered; Export asks for what it has, in the group Archive's entry reorders.
        List<JsonObject> entries = entries("[{command:'CommonCommand.Archive', before:'CommonCommand.Print'}, " //$NON-NLS-1$
            + "{command:'CommonCommand.Export', visible:true}]"); //$NON-NLS-1$
        Plan plan = section(PRINT, EXPORT, ARCHIVE).plan(entries, ref -> null).plan;
        assertTrue(plan.visibility().isEmpty());
        assertTrue(plan.placement().isEmpty());
        assertTrue(plan.unchanged().isEmpty());
        assertEquals(Arrays.asList(ARCHIVE, EXPORT), plan.touched());

        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getTopObjectByFqn(SECTION_FQN)).thenReturn((IBmObject)CmiFactory.eINSTANCE.createCommandInterface());
        JsonObject stored = CommandInterfaceSupport.storedState(tx, SECTION_FQN, plan);

        assertEquals(JsonParser.parseString("{'CommonCommand.Archive':{visible:'default',group:'default'}," //$NON-NLS-1$
            + "'CommonCommand.Export':{visible:'default',group:'default'}}"), stored.get("commands")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stored.getAsJsonObject("order").has(ORDINARY)); //$NON-NLS-1$
    }

    @Test
    public void testANewCommandInterfaceReportsTheFqnItWasAttachedUnder()
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        CommandInterfaceSupport.Target target = CommandInterfaceSupport.registeredOrAttached(tx, SECTION_FQN);

        ArgumentCaptor<IBmObject> attached = ArgumentCaptor.forClass(IBmObject.class);
        verify(tx).attachTopObject(attached.capture(), eq(SECTION_FQN));
        assertSame(attached.getValue(), target.commandInterface);
        assertEquals(SECTION_FQN, target.fqn);
    }

    @Test
    public void testARegisteredCommandInterfaceIsReusedAndNotAttachedAgain()
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        CommandInterface stored = CmiFactory.eINSTANCE.createCommandInterface();
        when(tx.getTopObjectByFqn(SECTION_FQN)).thenReturn((IBmObject)stored);
        CommandInterfaceSupport.Target target = CommandInterfaceSupport.registeredOrAttached(tx, SECTION_FQN);

        assertSame(stored, target.commandInterface);
        assertEquals(SECTION_FQN, target.fqn);
        verify(tx, never()).attachTopObject(any(), any());
    }

    private CommandInterfaceSupport.EditResult edit(CommandInterfaceSection read, CommandInterfaceSection written,
        String json)
    {
        List<JsonObject> entries = entries(json);
        CommandInterfaceSupport.Planner planner = (tx, e) -> {
            if (tx == readTx)
            {
                return read.plan(e, ref -> null);
            }
            if (tx == writeTx)
            {
                return written.plan(e, ref -> null);
            }
            fail("planned outside the edit's own transactions"); //$NON-NLS-1$
            return null;
        };
        CommandInterfaceSupport.Verifier verifier = (tx, plan) -> {
            assertSame("verified inside the write transaction", writeTx, tx); //$NON-NLS-1$
            return stale;
        };
        CommandInterfaceSupport.Applier applier = (tx, pm, plan) -> {
            assertSame("applied inside the write transaction", writeTx, tx); //$NON-NLS-1$
            applied.add(plan);
            return SECTION_FQN;
        };
        return CommandInterfaceSupport.edit(model, entries, planner, verifier, applier, OWNER);
    }

    private static List<JsonObject> entries(String json)
    {
        List<JsonObject> entries = new ArrayList<>();
        JsonParser.parseString(json).getAsJsonArray().forEach(e -> entries.add(e.getAsJsonObject()));
        return entries;
    }

    /** A section whose NavigationPanelOrdinary lists {@code commands} in order, each with its own handle. */
    private static CommandInterfaceSection section(String... commands)
    {
        return sectionHiding(null, commands);
    }

    private static CommandInterfaceSection sectionHiding(String hidden, String... commands)
    {
        Group important = new Group("NavigationPanelImportant", null, Panel.NAVIGATION, false, new Object(), //$NON-NLS-1$
            false);
        Group ordinary = new Group(ORDINARY, null, Panel.NAVIGATION, false, new Object(), false);
        for (String command : commands)
        {
            ordinary.items.add(new Item(command, null, new Object(),
                new Visibility(!command.equals(hidden), Collections.emptyMap()),
                false, false, true));
        }
        return new CommandInterfaceSection(OWNER, SECTION_FQN, Arrays.asList(important, ordinary));
    }
}
