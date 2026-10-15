/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.cmi.model.CmiFactory;
import com._1c.g5.v8.dt.cmi.model.CommandInterface;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * Pins {@link CommandInterfaceSupport#ensureConfigurationCommandInterfaces}: the two command
 * interfaces EDT's new-configuration wizards attach to a new root ({@code CommandInterface.cmi} and
 * {@code MainSectionCommandInterface.cmi}), under the external-property FQNs EDT's own
 * {@code CmiModelUtil} names them by (issue #706).
 */
public class CommandInterfaceSupportEnsureTest
{
    private static final String CI_FQN = "Configuration.CommandInterface"; //$NON-NLS-1$
    private static final String MAIN_FQN = "Configuration.MainSectionCommandInterface"; //$NON-NLS-1$

    private final IBmTransaction tx = mock(IBmTransaction.class);
    private final ITopObjectFqnGenerator generator = mock(ITopObjectFqnGenerator.class);
    private final Configuration root = MdClassFactory.eINSTANCE.createConfiguration();

    public CommandInterfaceSupportEnsureTest()
    {
        when(tx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)root); //$NON-NLS-1$
        when(generator.generateExternalPropertyFqn(root, MdClassPackage.Literals.CONFIGURATION__COMMAND_INTERFACE))
            .thenReturn(CI_FQN);
        when(generator.generateExternalPropertyFqn(root,
            MdClassPackage.Literals.CONFIGURATION__MAIN_SECTION_COMMAND_INTERFACE)).thenReturn(MAIN_FQN);
    }

    @Test
    public void testBothCommandInterfacesAreAttachedOnceUnderThePlatformsFqns()
    {
        CommandInterfaceSupport.Ensured ensured = CommandInterfaceSupport.ensureConfigurationCommandInterfaces(tx,
            generator);

        assertNull(ensured.skipped);
        assertEquals(Arrays.asList(CI_FQN, MAIN_FQN), ensured.fqns);
        ArgumentCaptor<IBmObject> attached = ArgumentCaptor.forClass(IBmObject.class);
        verify(tx, times(1)).attachTopObject(attached.capture(), eq(CI_FQN));
        assertTrue(attached.getValue() instanceof CommandInterface);
        verify(tx, times(1)).attachTopObject(any(), eq(MAIN_FQN));
        verify(tx, times(2)).attachTopObject(any(), anyString());
    }

    @Test
    public void testAnExistingCommandInterfaceIsNotAttachedAgain()
    {
        when(tx.getTopObjectByFqn(CI_FQN)).thenReturn((IBmObject)CmiFactory.eINSTANCE.createCommandInterface());

        CommandInterfaceSupport.Ensured ensured = CommandInterfaceSupport.ensureConfigurationCommandInterfaces(tx,
            generator);

        assertEquals("both are reported, so both are exported", Arrays.asList(CI_FQN, MAIN_FQN), ensured.fqns); //$NON-NLS-1$
        // Only the missing one is attached.
        verify(tx, never()).attachTopObject(any(), eq(CI_FQN));
        verify(tx, times(1)).attachTopObject(any(), eq(MAIN_FQN));
    }

    @Test
    public void testARootNotYetInTheModelIsSkippedNotThrown()
    {
        IBmTransaction early = mock(IBmTransaction.class);

        CommandInterfaceSupport.Ensured ensured = CommandInterfaceSupport.ensureConfigurationCommandInterfaces(early,
            generator);

        assertNotNull("a slow create can reach this before EDT attached the root", ensured.skipped); //$NON-NLS-1$
        assertTrue(ensured.fqns.isEmpty());
        verify(early, never()).attachTopObject(any(), anyString());
        verifyZeroInteractions(generator);
    }

    @Test
    public void testAMissingFqnGeneratorIsSkippedNotThrown()
    {
        CommandInterfaceSupport.Ensured ensured = CommandInterfaceSupport.ensureConfigurationCommandInterfaces(tx,
            null);

        assertNotNull(ensured.skipped);
        assertTrue(ensured.fqns.isEmpty());
        verify(tx, never()).attachTopObject(any(), anyString());
    }
}
