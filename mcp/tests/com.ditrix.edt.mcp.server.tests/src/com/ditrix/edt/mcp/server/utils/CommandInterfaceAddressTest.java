/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link CommandInterfaceAddress}: the three command-interface addresses, their English
 * and Russian spellings, and the FQNs that must NOT parse as one.
 */
public class CommandInterfaceAddressTest
{
    @Test
    public void testEnglishSubsystemSection()
    {
        CommandInterfaceAddress address = CommandInterfaceAddress.parse("Subsystem.Sales.CommandInterface"); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals(CommandInterfaceAddress.Kind.SUBSYSTEM, address.kind());
        assertArrayEquals(new String[] { "Sales" }, address.subsystemChain()); //$NON-NLS-1$
        assertEquals("Subsystem.Sales", address.ownerFqn()); //$NON-NLS-1$
        assertEquals("Subsystem.Sales.CommandInterface", address.canonicalFqn()); //$NON-NLS-1$
    }

    @Test
    public void testRussianSubsystemSectionCanonicalizesTokensKeepsName()
    {
        CommandInterfaceAddress address =
            CommandInterfaceAddress.parse("Подсистема.Продажи.КомандныйИнтерфейс"); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals(CommandInterfaceAddress.Kind.SUBSYSTEM, address.kind());
        assertArrayEquals(new String[] { "Продажи" }, address.subsystemChain()); //$NON-NLS-1$
        assertEquals("Subsystem.Продажи.CommandInterface", address.canonicalFqn()); //$NON-NLS-1$
    }

    @Test
    public void testNestedSubsystemSectionMixedLanguages()
    {
        CommandInterfaceAddress address =
            CommandInterfaceAddress.parse("Subsystem.Sales.Подсистема.Orders.commandinterface"); //$NON-NLS-1$
        assertNotNull(address);
        assertArrayEquals(new String[] { "Sales", "Orders" }, address.subsystemChain()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Subsystem.Sales.Subsystem.Orders", address.ownerFqn()); //$NON-NLS-1$
        assertEquals("Subsystem.Sales.Subsystem.Orders.CommandInterface", address.canonicalFqn()); //$NON-NLS-1$
    }

    @Test
    public void testMainSectionEnglishAndRussian()
    {
        CommandInterfaceAddress english =
            CommandInterfaceAddress.parse("Configuration.MainSectionCommandInterface"); //$NON-NLS-1$
        CommandInterfaceAddress russian =
            CommandInterfaceAddress.parse("Конфигурация.КомандныйИнтерфейсОсновногоРаздела"); //$NON-NLS-1$
        assertNotNull(english);
        assertNotNull(russian);
        assertEquals(CommandInterfaceAddress.Kind.MAIN_SECTION, english.kind());
        assertEquals(CommandInterfaceAddress.Kind.MAIN_SECTION, russian.kind());
        assertNull(english.subsystemChain());
        assertEquals("Configuration", english.ownerFqn()); //$NON-NLS-1$
        assertEquals("Configuration.MainSectionCommandInterface", russian.canonicalFqn()); //$NON-NLS-1$
    }

    @Test
    public void testSectionsPanel()
    {
        CommandInterfaceAddress address = CommandInterfaceAddress.parse(" Configuration.CommandInterface "); //$NON-NLS-1$
        assertNotNull(address);
        assertEquals(CommandInterfaceAddress.Kind.SECTIONS_PANEL, address.kind());
        assertEquals("Configuration.CommandInterface", address.canonicalFqn()); //$NON-NLS-1$
    }

    @Test
    public void testNotACommandInterface()
    {
        assertNull(CommandInterfaceAddress.parse(null));
        assertNull(CommandInterfaceAddress.parse("")); //$NON-NLS-1$
        assertNull(CommandInterfaceAddress.parse("Subsystem.Sales")); //$NON-NLS-1$
        assertNull(CommandInterfaceAddress.parse("Subsystem.Sales.")); //$NON-NLS-1$
        // An object whose Name is CommandInterface is not a command interface address.
        assertNull(CommandInterfaceAddress.parse("Subsystem.CommandInterface")); //$NON-NLS-1$
        assertNull(CommandInterfaceAddress.parse("Catalog.CommandInterface")); //$NON-NLS-1$
        // Only subsystems and the configuration have one.
        assertNull(CommandInterfaceAddress.parse("Catalog.Products.CommandInterface")); //$NON-NLS-1$
        assertNull(CommandInterfaceAddress.parse("Configuration.Sales.CommandInterface")); //$NON-NLS-1$
        assertNull(CommandInterfaceAddress.parse("Subsystem.Sales.MainSectionCommandInterface")); //$NON-NLS-1$
        assertNull(CommandInterfaceAddress.parse("Configuration.Synonym")); //$NON-NLS-1$
    }

    @Test
    public void testHasCommandInterfaceTail()
    {
        assertTrue(CommandInterfaceAddress.hasCommandInterfaceTail("Catalog.Products.CommandInterface")); //$NON-NLS-1$
        assertTrue(CommandInterfaceAddress.hasCommandInterfaceTail("Справочник.Товары.КомандныйИнтерфейс")); //$NON-NLS-1$
        assertTrue(CommandInterfaceAddress.hasCommandInterfaceTail("Catalog.MainSectionCommandInterface")); //$NON-NLS-1$
        assertFalse(CommandInterfaceAddress.hasCommandInterfaceTail("Catalog.Products")); //$NON-NLS-1$
        assertFalse(CommandInterfaceAddress.hasCommandInterfaceTail("CommandInterface")); //$NON-NLS-1$
        assertFalse(CommandInterfaceAddress.hasCommandInterfaceTail(null));
    }
}
