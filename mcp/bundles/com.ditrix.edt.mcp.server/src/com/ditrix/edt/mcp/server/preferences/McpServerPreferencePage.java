/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.io.IOException;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * MCP Server preference page with tabbed layout.
 * Tab 1: General - port, auto-start, checks folder, plain text, tags, updates, server control
 * Tab 2: Tools - tree of tool groups with enable/disable, description, and parameter settings
 */
public class McpServerPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private GeneralTab generalTab;
    private ToolsTab toolsTab;

    public McpServerPreferencePage()
    {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("MCP Server settings for 1C:EDT v" + McpConstants.PLUGIN_VERSION + " @" + McpConstants.AUTHOR); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public void init(IWorkbench workbench)
    {
        // Initialization
    }

    @Override
    protected Control createContents(Composite parent)
    {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout containerLayout = new GridLayout(1, false);
        containerLayout.marginWidth = 0;
        containerLayout.marginHeight = 0;
        container.setLayout(containerLayout);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        CTabFolder tabFolder = new CTabFolder(container, SWT.BORDER);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(true);

        // Tab 1: General
        CTabItem generalItem = new CTabItem(tabFolder, SWT.NONE);
        generalItem.setText("General"); //$NON-NLS-1$
        generalTab = new GeneralTab(tabFolder);
        generalItem.setControl(generalTab.getControl());

        // Tab 2: Tools
        CTabItem toolsItem = new CTabItem(tabFolder, SWT.NONE);
        toolsItem.setText("Tools"); //$NON-NLS-1$
        toolsTab = new ToolsTab(tabFolder);
        toolsItem.setControl(toolsTab.getControl());

        // Select the first tab
        tabFolder.setSelection(0);

        return container;
    }

    @Override
    public boolean performOk()
    {
        boolean toolsChanged = toolsTab.hasChanges();

        generalTab.performOk();
        toolsTab.performOk();

        // If tool enablement changed and server is running, restart to apply
        if (toolsChanged)
        {
            McpServer server = Activator.getDefault().getMcpServer();
            if (server != null && server.isRunning())
            {
                try
                {
                    server.restart(generalTab.getPort());
                    Activator.logInfo("MCP Server restarted after tool configuration change"); //$NON-NLS-1$
                }
                catch (IOException e)
                {
                    Activator.logError("Failed to restart MCP Server after tool change", e); //$NON-NLS-1$
                }
            }
        }

        return super.performOk();
    }

    @Override
    protected void performDefaults()
    {
        generalTab.performDefaults();
        toolsTab.performDefaults();
        super.performDefaults();
    }

    @Override
    public void dispose()
    {
        if (generalTab != null)
        {
            generalTab.dispose();
        }
        if (toolsTab != null)
        {
            toolsTab.dispose();
        }
        super.dispose();
    }
}
