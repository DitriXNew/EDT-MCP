/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.preferences;

import java.io.IOException;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * MCP Server preference page.
 * Allows managing port and server state.
 */
public class McpServerPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    private Label statusLabel;
    private Button startButton;
    private Button stopButton;
    private Button restartButton;
    private IntegerFieldEditor portEditor;

    public McpServerPreferencePage()
    {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("MCP Server settings for 1C:EDT v" + McpConstants.PLUGIN_VERSION + " @" + McpConstants.AUTHOR);
    }

    @Override
    public void init(IWorkbench workbench)
    {
        // Initialization
    }

    @Override
    protected void createFieldEditors()
    {
        Composite parent = getFieldEditorParent();

        // Port
        portEditor = new IntegerFieldEditor(
            PreferenceConstants.PREF_PORT,
            "Server Port:",
            parent);
        portEditor.setValidRange(1024, 65535);
        addField(portEditor);

        // Auto-start
        BooleanFieldEditor autoStartEditor = new BooleanFieldEditor(
            PreferenceConstants.PREF_AUTO_START,
            "Automatically start with EDT",
            parent);
        addField(autoStartEditor);
        
        // Check descriptions folder
        DirectoryFieldEditor checksFolderEditor = new DirectoryFieldEditor(
            PreferenceConstants.PREF_CHECKS_FOLDER,
            "Check descriptions folder:",
            parent);
        checksFolderEditor.setEmptyStringAllowed(true);
        addField(checksFolderEditor);

        // Server control group
        createServerControlGroup(parent);
    }

    private void createServerControlGroup(Composite parent)
    {
        Group controlGroup = new Group(parent, SWT.NONE);
        controlGroup.setText("Server Control");
        controlGroup.setLayout(new GridLayout(4, false));
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        controlGroup.setLayoutData(gd);

        // Status
        Label statusTitleLabel = new Label(controlGroup, SWT.NONE);
        statusTitleLabel.setText("Status:");
        
        statusLabel = new Label(controlGroup, SWT.NONE);
        GridData statusGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusGd.horizontalSpan = 3;
        statusLabel.setLayoutData(statusGd);
        updateStatusLabel();

        // Control buttons
        ISharedImages sharedImages = PlatformUI.getWorkbench().getSharedImages();
        
        startButton = new Button(controlGroup, SWT.PUSH);
        startButton.setText("Start");
        // Use green "go" style icon
        try
        {
            ImageDescriptor runDesc = Activator.getDefault().getImageRegistry()
                .getDescriptor("icons/run.png");
            if (runDesc == null)
            {
                // Fallback to shared image
                startButton.setImage(sharedImages.getImage(ISharedImages.IMG_ELCL_SYNCED));
            }
        }
        catch (Exception e)
        {
            // Ignore
        }
        startButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                startServer();
            }
        });

        stopButton = new Button(controlGroup, SWT.PUSH);
        stopButton.setText("Stop");
        // Use stop image
        stopButton.setImage(sharedImages.getImage(ISharedImages.IMG_ELCL_STOP));
        stopButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                stopServer();
            }
        });

        restartButton = new Button(controlGroup, SWT.PUSH);
        restartButton.setText("Restart");
        // Use refresh image for restart
        restartButton.setImage(sharedImages.getImage(ISharedImages.IMG_ELCL_SYNCED_DISABLED));
        restartButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                restartServer();
            }
        });

        // Empty placeholder for alignment
        new Label(controlGroup, SWT.NONE);

        // Connection info
        Label infoLabel = new Label(controlGroup, SWT.NONE);
        infoLabel.setText("Endpoint: http://localhost:<port>/mcp");
        GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        infoGd.horizontalSpan = 4;
        infoLabel.setLayoutData(infoGd);

        updateButtons();
    }

    private void updateStatusLabel()
    {
        McpServer server = Activator.getDefault().getMcpServer();
        if (server != null && server.isRunning())
        {
            statusLabel.setText("Running on port " + server.getPort());
            statusLabel.setForeground(getShell().getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
        }
        else
        {
            statusLabel.setText("Stopped");
            statusLabel.setForeground(getShell().getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        }
    }

    private void updateButtons()
    {
        McpServer server = Activator.getDefault().getMcpServer();
        boolean running = server != null && server.isRunning();
        
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        restartButton.setEnabled(running);
    }

    private void startServer()
    {
        try
        {
            int port = getPreferenceStore().getInt(PreferenceConstants.PREF_PORT);
            Activator.getDefault().getMcpServer().start(port);
            updateStatusLabel();
            updateButtons();
        }
        catch (IOException e)
        {
            Activator.logError("Failed to start MCP Server", e);
            setErrorMessage("Start error: " + e.getMessage());
        }
    }

    private void stopServer()
    {
        Activator.getDefault().getMcpServer().stop();
        updateStatusLabel();
        updateButtons();
    }

    private void restartServer()
    {
        try
        {
            int port = getPreferenceStore().getInt(PreferenceConstants.PREF_PORT);
            Activator.getDefault().getMcpServer().restart(port);
            updateStatusLabel();
            updateButtons();
        }
        catch (IOException e)
        {
            Activator.logError("Failed to restart MCP Server", e);
            setErrorMessage("Restart error: " + e.getMessage());
        }
    }
}
