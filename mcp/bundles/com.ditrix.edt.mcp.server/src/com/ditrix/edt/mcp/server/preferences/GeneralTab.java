/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.UpdateChecker;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * General settings tab for MCP Server preferences.
 * Contains port, auto-start, limits, plain text, tag decoration, update check, and server control.
 */
public class GeneralTab
{
    private final Composite composite;
    private final IPreferenceStore store;

    private Spinner portSpinner;
    private Button autoStartCheck;
    private Text checksFolderText;
    private Button plainTextCheck;
    private Button showTagsCheck;
    private Combo tagStyleCombo;
    private Combo updateCheckCombo;
    private Label statusLabel;
    private Button startButton;
    private Button stopButton;
    private Button restartButton;

    /** Track created images for disposal */
    private final List<org.eclipse.swt.graphics.Image> managedImages = new ArrayList<>();

    private static final String[][] TAG_STYLES = {
        {"All tags (suffix)", PreferenceConstants.TAGS_STYLE_SUFFIX}, //$NON-NLS-1$
        {"First tag only", PreferenceConstants.TAGS_STYLE_FIRST_TAG}, //$NON-NLS-1$
        {"Tag count", PreferenceConstants.TAGS_STYLE_COUNT} //$NON-NLS-1$
    };

    private static final String[][] UPDATE_INTERVALS = {
        {"On every startup", PreferenceConstants.UPDATE_CHECK_ON_STARTUP}, //$NON-NLS-1$
        {"Hourly", PreferenceConstants.UPDATE_CHECK_HOURLY}, //$NON-NLS-1$
        {"Daily", PreferenceConstants.UPDATE_CHECK_DAILY}, //$NON-NLS-1$
        {"Never", PreferenceConstants.UPDATE_CHECK_NEVER} //$NON-NLS-1$
    };

    public GeneralTab(Composite parent)
    {
        this.store = Activator.getDefault().getPreferenceStore();

        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        composite.setLayout(layout);

        createServerSection();
        createLimitsSection();
        createTagsSection();
        createUpdateSection();
        createServerControlSection();
    }

    public Composite getControl()
    {
        return composite;
    }

    private void createServerSection()
    {
        // Port
        createLabel("Server Port:"); //$NON-NLS-1$
        portSpinner = new Spinner(composite, SWT.BORDER);
        portSpinner.setMinimum(1024);
        portSpinner.setMaximum(65535);
        portSpinner.setSelection(store.getInt(PreferenceConstants.PREF_PORT));
        portSpinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createLabel(""); // spacer //$NON-NLS-1$

        // Auto-start
        autoStartCheck = new Button(composite, SWT.CHECK);
        autoStartCheck.setText("Automatically start with EDT"); //$NON-NLS-1$
        autoStartCheck.setSelection(store.getBoolean(PreferenceConstants.PREF_AUTO_START));
        GridData autoStartGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        autoStartGd.horizontalSpan = 3;
        autoStartCheck.setLayoutData(autoStartGd);

        // Checks folder
        createLabel("Check descriptions folder:"); //$NON-NLS-1$
        checksFolderText = new Text(composite, SWT.BORDER);
        checksFolderText.setText(store.getString(PreferenceConstants.PREF_CHECKS_FOLDER));
        checksFolderText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button browseButton = new Button(composite, SWT.PUSH);
        browseButton.setText("Browse..."); //$NON-NLS-1$
        browseButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                DirectoryDialog dialog = new DirectoryDialog(composite.getShell());
                dialog.setMessage("Select check descriptions folder"); //$NON-NLS-1$
                String path = dialog.open();
                if (path != null)
                {
                    checksFolderText.setText(path);
                }
            }
        });
    }

    private void createLimitsSection()
    {
        // Plain text mode
        plainTextCheck = new Button(composite, SWT.CHECK);
        plainTextCheck.setText("Plain text mode (Cursor compatibility)"); //$NON-NLS-1$
        plainTextCheck.setToolTipText(
            "When enabled, returns results as plain text instead of embedded resources. " + //$NON-NLS-1$
            "Enable this if your AI client (e.g., Cursor) doesn't support MCP resources."); //$NON-NLS-1$
        plainTextCheck.setSelection(store.getBoolean(PreferenceConstants.PREF_PLAIN_TEXT_MODE));
        GridData ptGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        ptGd.horizontalSpan = 3;
        plainTextCheck.setLayoutData(ptGd);
    }

    private void createTagsSection()
    {
        // Separator
        Label separator = new Label(composite, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData sepGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sepGd.horizontalSpan = 3;
        sepGd.verticalIndent = 5;
        separator.setLayoutData(sepGd);

        // Show tags in navigator
        showTagsCheck = new Button(composite, SWT.CHECK);
        showTagsCheck.setText("Show tags in Navigator"); //$NON-NLS-1$
        showTagsCheck.setSelection(store.getBoolean(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR));
        GridData stGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        stGd.horizontalSpan = 3;
        showTagsCheck.setLayoutData(stGd);

        // Tag decoration style
        createLabel("Tag decoration style:"); //$NON-NLS-1$
        tagStyleCombo = new Combo(composite, SWT.DROP_DOWN | SWT.READ_ONLY);
        String currentStyle = store.getString(PreferenceConstants.PREF_TAGS_DECORATION_STYLE);
        int styleIndex = 0;
        for (int i = 0; i < TAG_STYLES.length; i++)
        {
            tagStyleCombo.add(TAG_STYLES[i][0]);
            if (TAG_STYLES[i][1].equals(currentStyle))
            {
                styleIndex = i;
            }
        }
        tagStyleCombo.select(styleIndex);
        tagStyleCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createLabel(""); //$NON-NLS-1$
    }

    private void createUpdateSection()
    {
        // Update check interval
        createLabel("Check for updates:"); //$NON-NLS-1$
        updateCheckCombo = new Combo(composite, SWT.DROP_DOWN | SWT.READ_ONLY);
        String currentInterval = store.getString(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL);
        int intervalIndex = 0;
        for (int i = 0; i < UPDATE_INTERVALS.length; i++)
        {
            updateCheckCombo.add(UPDATE_INTERVALS[i][0]);
            if (UPDATE_INTERVALS[i][1].equals(currentInterval))
            {
                intervalIndex = i;
            }
        }
        updateCheckCombo.select(intervalIndex);
        updateCheckCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createLabel(""); //$NON-NLS-1$

        // Check now row
        createLabel(""); //$NON-NLS-1$
        Composite checkNowRow = new Composite(composite, SWT.NONE);
        GridLayout rowLayout = new GridLayout(2, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        checkNowRow.setLayout(rowLayout);
        checkNowRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button checkNowButton = new Button(checkNowRow, SWT.PUSH);
        checkNowButton.setText("Check now"); //$NON-NLS-1$

        Link checkResultLink = new Link(checkNowRow, SWT.NONE);
        checkResultLink.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        checkResultLink.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                UpdateChecker checker = UpdateChecker.getInstance();
                new com.ditrix.edt.mcp.server.ui.ReleaseNotesDialog(
                    composite.getShell(),
                    checker.getLatestVersion(),
                    checker.getReleaseNotes(),
                    checker.getReleaseUrl()).open();
            }
        });
        updateCheckResultLink(checkResultLink);

        checkNowButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                checkResultLink.setText("Checking..."); //$NON-NLS-1$
                checkResultLink.getParent().layout(true, true);
                Thread t = new Thread(() -> {
                    UpdateChecker.getInstance().checkNow();
                    org.eclipse.swt.widgets.Display display = checkResultLink.getDisplay();
                    if (display != null && !display.isDisposed())
                    {
                        display.asyncExec(() -> {
                            if (!checkResultLink.isDisposed())
                            {
                                updateCheckResultLink(checkResultLink);
                                checkResultLink.getParent().layout(true, true);
                            }
                        });
                    }
                }, "MCP-CheckNow-UI"); //$NON-NLS-1$
                t.setDaemon(true);
                t.start();
            }
        });

        createLabel(""); // spacer //$NON-NLS-1$
    }

    private void updateCheckResultLink(Link link)
    {
        UpdateChecker checker = UpdateChecker.getInstance();
        String latest = checker.getLatestVersion();
        if (latest.isEmpty())
        {
            link.setText(""); //$NON-NLS-1$
        }
        else if (checker.isUpdateAvailable())
        {
            link.setText("New release available: <a>" + latest + " — What's new?</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            link.setText("Up to date (" + McpConstants.PLUGIN_VERSION + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void createServerControlSection()
    {
        // Separator
        Label separator = new Label(composite, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData separatorGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        separatorGd.horizontalSpan = 3;
        separatorGd.verticalIndent = 10;
        separator.setLayoutData(separatorGd);

        // Section title
        Label sectionTitle = new Label(composite, SWT.NONE);
        sectionTitle.setText("Server Control"); //$NON-NLS-1$
        GridData titleGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        titleGd.horizontalSpan = 3;
        sectionTitle.setLayoutData(titleGd);

        // Container for controls
        Composite controlComposite = new Composite(composite, SWT.NONE);
        controlComposite.setLayout(new GridLayout(4, false));
        GridData compositeGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        compositeGd.horizontalSpan = 3;
        controlComposite.setLayoutData(compositeGd);

        // Status
        Label statusTitleLabel = new Label(controlComposite, SWT.NONE);
        statusTitleLabel.setText("Status:"); //$NON-NLS-1$

        statusLabel = new Label(controlComposite, SWT.NONE);
        GridData statusGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusGd.horizontalSpan = 3;
        statusLabel.setLayoutData(statusGd);
        updateStatusLabel();

        // Control buttons
        ImageDescriptor startIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/start.png"); //$NON-NLS-1$
        ImageDescriptor stopIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/stop.png"); //$NON-NLS-1$
        ImageDescriptor restartIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/restart.png"); //$NON-NLS-1$

        startButton = new Button(controlComposite, SWT.PUSH);
        startButton.setText("Start"); //$NON-NLS-1$
        setManagedImage(startButton, startIcon);
        startButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                startServer();
            }
        });

        stopButton = new Button(controlComposite, SWT.PUSH);
        stopButton.setText("Stop"); //$NON-NLS-1$
        setManagedImage(stopButton, stopIcon);
        stopButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                stopServer();
            }
        });

        restartButton = new Button(controlComposite, SWT.PUSH);
        restartButton.setText("Restart"); //$NON-NLS-1$
        setManagedImage(restartButton, restartIcon);
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
        infoLabel.setText("Endpoint: http://localhost:<port>/mcp"); //$NON-NLS-1$
        GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        infoGd.horizontalSpan = 4;
        infoLabel.setLayoutData(infoGd);

        updateButtons();
    }

    /**
     * Saves all values to the preference store.
     */
    public void performOk()
    {
        store.setValue(PreferenceConstants.PREF_PORT, portSpinner.getSelection());
        store.setValue(PreferenceConstants.PREF_AUTO_START, autoStartCheck.getSelection());
        store.setValue(PreferenceConstants.PREF_CHECKS_FOLDER, checksFolderText.getText());
        store.setValue(PreferenceConstants.PREF_PLAIN_TEXT_MODE, plainTextCheck.getSelection());
        store.setValue(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR, showTagsCheck.getSelection());

        int styleIdx = tagStyleCombo.getSelectionIndex();
        if (styleIdx >= 0 && styleIdx < TAG_STYLES.length)
        {
            store.setValue(PreferenceConstants.PREF_TAGS_DECORATION_STYLE, TAG_STYLES[styleIdx][1]);
        }

        int intervalIdx = updateCheckCombo.getSelectionIndex();
        if (intervalIdx >= 0 && intervalIdx < UPDATE_INTERVALS.length)
        {
            store.setValue(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL, UPDATE_INTERVALS[intervalIdx][1]);
        }
    }

    /**
     * Resets all values to defaults.
     */
    public void performDefaults()
    {
        portSpinner.setSelection(PreferenceConstants.DEFAULT_PORT);
        autoStartCheck.setSelection(PreferenceConstants.DEFAULT_AUTO_START);
        checksFolderText.setText(PreferenceConstants.DEFAULT_CHECKS_FOLDER);
        plainTextCheck.setSelection(PreferenceConstants.DEFAULT_PLAIN_TEXT_MODE);
        showTagsCheck.setSelection(PreferenceConstants.DEFAULT_TAGS_SHOW_IN_NAVIGATOR);

        // Find index for default style
        for (int i = 0; i < TAG_STYLES.length; i++)
        {
            if (TAG_STYLES[i][1].equals(PreferenceConstants.DEFAULT_TAGS_DECORATION_STYLE))
            {
                tagStyleCombo.select(i);
                break;
            }
        }

        // Find index for default update interval
        for (int i = 0; i < UPDATE_INTERVALS.length; i++)
        {
            if (UPDATE_INTERVALS[i][1].equals(PreferenceConstants.DEFAULT_UPDATE_CHECK_INTERVAL))
            {
                updateCheckCombo.select(i);
                break;
            }
        }
    }

    /**
     * Returns the current port value from the UI.
     */
    public int getPort()
    {
        return portSpinner.getSelection();
    }

    private void updateStatusLabel()
    {
        McpServer server = Activator.getDefault().getMcpServer();
        Shell shell = composite.getShell();
        if (server != null && server.isRunning())
        {
            statusLabel.setText("Running on port " + server.getPort()); //$NON-NLS-1$
            statusLabel.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
        }
        else
        {
            statusLabel.setText("Stopped"); //$NON-NLS-1$
            statusLabel.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
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
        McpServer server = Activator.getDefault().getMcpServer();
        if (server == null)
        {
            return;
        }
        try
        {
            performOk();
            server.start(portSpinner.getSelection());
            updateStatusLabel();
            updateButtons();
        }
        catch (IOException e)
        {
            Activator.logError("Failed to start MCP Server", e); //$NON-NLS-1$
            MessageDialog.openError(composite.getShell(),
                "Start Failed", "Failed to start MCP Server: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void stopServer()
    {
        McpServer server = Activator.getDefault().getMcpServer();
        if (server == null)
        {
            return;
        }
        server.stop();
        updateStatusLabel();
        updateButtons();
    }

    private void restartServer()
    {
        McpServer server = Activator.getDefault().getMcpServer();
        if (server == null)
        {
            return;
        }
        try
        {
            performOk();
            server.restart(portSpinner.getSelection());
            updateStatusLabel();
            updateButtons();
        }
        catch (IOException e)
        {
            Activator.logError("Failed to restart MCP Server", e); //$NON-NLS-1$
            MessageDialog.openError(composite.getShell(),
                "Restart Failed", "Failed to restart MCP Server: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Creates an Image from the descriptor, sets it on the button, and tracks it for disposal.
     */
    private void setManagedImage(Button button, ImageDescriptor descriptor)
    {
        if (descriptor != null)
        {
            org.eclipse.swt.graphics.Image image = descriptor.createImage();
            button.setImage(image);
            managedImages.add(image);
        }
    }

    /**
     * Disposes all managed SWT images. Must be called when the tab is disposed.
     */
    public void dispose()
    {
        for (org.eclipse.swt.graphics.Image image : managedImages)
        {
            if (image != null && !image.isDisposed())
            {
                image.dispose();
            }
        }
        managedImages.clear();
    }

    private Label createLabel(String text)
    {
        Label label = new Label(composite, SWT.NONE);
        label.setText(text);
        return label;
    }
}
