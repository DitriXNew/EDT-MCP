/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.Collections;

import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Group;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Item;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Panel;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Visibility;

/** A model-free section for tests outside this package, which cannot fill a group's items. */
public final class CommandInterfaceTestSections
{
    private CommandInterfaceTestSections()
    {
        // fixture
    }

    /**
     * @return {@code Subsystem.Sales}'s section: {@code CommonCommand.Print} (visible) in
     *     NavigationPanelOrdinary, and an empty NavigationPanelImportant
     */
    public static CommandInterfaceSection salesSection()
    {
        Group important = new Group("NavigationPanelImportant", null, Panel.NAVIGATION, false, new Object(), //$NON-NLS-1$
            false);
        Group ordinary = new Group("NavigationPanelOrdinary", null, Panel.NAVIGATION, false, new Object(), false); //$NON-NLS-1$
        ordinary.items.add(new Item("CommonCommand.Print", null, new Object(), //$NON-NLS-1$
            new Visibility(true, Collections.emptyMap()), false, false, true));
        return new CommandInterfaceSection("Subsystem.Sales", "Subsystem.Sales.CommandInterface", //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList(important, ordinary));
    }
}
