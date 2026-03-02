/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.io.IOException;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.UpdateChecker;
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
    private DirectoryFieldEditor checksFolderEditor;

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
        checksFolderEditor = new DirectoryFieldEditor(
            PreferenceConstants.PREF_CHECKS_FOLDER,
            "Check descriptions folder:",
            parent);
        checksFolderEditor.setEmptyStringAllowed(true);
        addField(checksFolderEditor);
        
        // Default limit for results
        IntegerFieldEditor defaultLimitEditor = new IntegerFieldEditor(
            PreferenceConstants.PREF_DEFAULT_LIMIT,
            "Default result limit:",
            parent);
        defaultLimitEditor.setValidRange(1, 10000);
        defaultLimitEditor.getLabelControl(parent).setToolTipText(
            "Default number of results returned by get_project_errors, get_bookmarks, get_tasks tools");
        defaultLimitEditor.getTextControl(parent).setToolTipText(
            "Default number of results returned by get_project_errors, get_bookmarks, get_tasks tools");
        addField(defaultLimitEditor);
        
        // Maximum limit for results
        IntegerFieldEditor maxLimitEditor = new IntegerFieldEditor(
            PreferenceConstants.PREF_MAX_LIMIT,
            "Maximum result limit:",
            parent);
        maxLimitEditor.setValidRange(1, 100000);
        maxLimitEditor.getLabelControl(parent).setToolTipText(
            "Maximum number of results that can be requested. Prevents returning too much data.");
        maxLimitEditor.getTextControl(parent).setToolTipText(
            "Maximum number of results that can be requested. Prevents returning too much data.");
        addField(maxLimitEditor);
        
        // Plain text mode (Cursor compatibility)
        BooleanFieldEditor plainTextModeEditor = new BooleanFieldEditor(
            PreferenceConstants.PREF_PLAIN_TEXT_MODE,
            "Plain text mode (Cursor compatibility)",
            parent);
        plainTextModeEditor.getDescriptionControl(parent).setToolTipText(
            "When enabled, returns results as plain text instead of embedded resources. " +
            "Enable this if your AI client (e.g., Cursor) doesn't support MCP resources.");
        addField(plainTextModeEditor);

        // === Tag decoration preferences ===
        
        // Show tags in navigator
        BooleanFieldEditor showTagsEditor = new BooleanFieldEditor(
            PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR,
            "Show tags in Navigator",
            parent);
        addField(showTagsEditor);
        
        // Tag decoration style
        ComboFieldEditor tagStyleEditor = new ComboFieldEditor(
            PreferenceConstants.PREF_TAGS_DECORATION_STYLE,
            "Tag decoration style:",
            new String[][] {
                {"All tags (suffix)", PreferenceConstants.TAGS_STYLE_SUFFIX},
                {"First tag only", PreferenceConstants.TAGS_STYLE_FIRST_TAG},
                {"Tag count", PreferenceConstants.TAGS_STYLE_COUNT}
            },
            parent);
        addField(tagStyleEditor);

        // Update check interval
        ComboFieldEditor updateCheckEditor = new ComboFieldEditor(
            PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL,
            "Check for updates:",
            new String[][] {
                {"On every startup", PreferenceConstants.UPDATE_CHECK_ON_STARTUP},
                {"Hourly",          PreferenceConstants.UPDATE_CHECK_HOURLY},
                {"Daily",           PreferenceConstants.UPDATE_CHECK_DAILY},
                {"Never",           PreferenceConstants.UPDATE_CHECK_NEVER}
            },
            parent);
        addField(updateCheckEditor);

        // "Check now" row
        createCheckNowRow(parent);

        // Server control group
        createServerControlGroup(parent);
    }

    private void createCheckNowRow(Composite parent)
    {
        // Empty label in column 1 to align with combo column 2
        new Label(parent, SWT.NONE);

        // Composite in column 2: button + result label + "What's new?" button
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout rowLayout = new GridLayout(3, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        row.setLayout(rowLayout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button checkNowButton = new Button(row, SWT.PUSH);
        checkNowButton.setText("Check now"); //$NON-NLS-1$

        Label checkResultLabel = new Label(row, SWT.WRAP);
        checkResultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button whatsNewButton = new Button(row, SWT.PUSH);
        whatsNewButton.setText("What's new?"); //$NON-NLS-1$
        GridData whatsNewGd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        whatsNewGd.exclude = true;
        whatsNewButton.setLayoutData(whatsNewGd);
        whatsNewButton.setVisible(false);
        whatsNewButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                UpdateChecker checker = UpdateChecker.getInstance();
                new com.ditrix.edt.mcp.server.ui.ReleaseNotesDialog(
                    getShell(),
                    checker.getLatestVersion(),
                    checker.getReleaseNotes(),
                    checker.getReleaseUrl()).open();
            }
        });

        // Show current state immediately
        updateCheckResultLabel(checkResultLabel, whatsNewButton);

        checkNowButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                checkResultLabel.setText("Checking..."); //$NON-NLS-1$
                setButtonVisible(whatsNewButton, false);
                checkResultLabel.getParent().layout(true);

                // Run in background, then update label on UI thread
                Thread t = new Thread(() -> {
                    UpdateChecker.getInstance().checkNow();
                    // Give the check thread a moment to finish (it's also async)
                    try { Thread.sleep(5_000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    org.eclipse.swt.widgets.Display display = checkResultLabel.getDisplay();
                    if (display != null && !display.isDisposed())
                    {
                        display.asyncExec(() -> {
                            if (!checkResultLabel.isDisposed())
                            {
                                updateCheckResultLabel(checkResultLabel, whatsNewButton);
                                checkResultLabel.getParent().layout(true);
                            }
                        });
                    }
                }, "MCP-CheckNow-UI"); //$NON-NLS-1$
                t.setDaemon(true);
                t.start();
            }
        });
    }

    private void updateCheckResultLabel(Label label, Button whatsNewButton)
    {
        UpdateChecker checker = UpdateChecker.getInstance();
        String latest = checker.getLatestVersion();
        if (latest.isEmpty())
        {
            label.setText(""); //$NON-NLS-1$
            setButtonVisible(whatsNewButton, false);
        }
        else if (checker.isUpdateAvailable())
        {
            label.setText("New release available: " + latest); //$NON-NLS-1$
            label.setForeground(label.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
            setButtonVisible(whatsNewButton, true);
        }
        else
        {
            label.setText("Up to date (" + McpConstants.PLUGIN_VERSION + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            setButtonVisible(whatsNewButton, false);
        }
    }

    private static void setButtonVisible(Button button, boolean visible)
    {
        button.setVisible(visible);
        ((GridData) button.getLayoutData()).exclude = !visible;
    }

    private void createServerControlGroup(Composite parent)
    {
        // Separator line before server control section
        Label separator = new Label(parent, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData separatorGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        separatorGd.horizontalSpan = 2;
        separatorGd.verticalIndent = 10;
        separator.setLayoutData(separatorGd);
        
        // Server Control section title
        Label sectionTitle = new Label(parent, SWT.NONE);
        sectionTitle.setText("Server Control");
        GridData titleGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        titleGd.horizontalSpan = 2;
        sectionTitle.setLayoutData(titleGd);
        
        // Container for controls
        Composite controlComposite = new Composite(parent, SWT.NONE);
        controlComposite.setLayout(new GridLayout(4, false));
        GridData compositeGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        compositeGd.horizontalSpan = 2;
        controlComposite.setLayoutData(compositeGd);

        // Status
        Label statusTitleLabel = new Label(controlComposite, SWT.NONE);
        statusTitleLabel.setText("Status:");
        
        statusLabel = new Label(controlComposite, SWT.NONE);
        GridData statusGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusGd.horizontalSpan = 3;
        statusLabel.setLayoutData(statusGd);
        updateStatusLabel();

        // Control buttons - load custom PNG icons
        ImageDescriptor startIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/start.png");
        ImageDescriptor stopIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/stop.png");
        ImageDescriptor restartIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/restart.png");
        
        startButton = new Button(controlComposite, SWT.PUSH);
        startButton.setText("Start");
        if (startIcon != null)
        {
            startButton.setImage(startIcon.createImage());
        }
        startButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                startServer();
            }
        });

        stopButton = new Button(controlComposite, SWT.PUSH);
        stopButton.setText("Stop");
        if (stopIcon != null)
        {
            stopButton.setImage(stopIcon.createImage());
        }
        stopButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                stopServer();
            }
        });

        restartButton = new Button(controlComposite, SWT.PUSH);
        restartButton.setText("Restart");
        if (restartIcon != null)
        {
            restartButton.setImage(restartIcon.createImage());
        }
        restartButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                restartServer();
            }
        });

        // Empty placeholder for alignment
        new Label(controlComposite, SWT.NONE);

        // Connection info
        Label infoLabel = new Label(controlComposite, SWT.NONE);
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
            // Save current values from editors before starting
            portEditor.store();
            checksFolderEditor.store();
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
            // Save current values from editors before restarting
            portEditor.store();
            checksFolderEditor.store();
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