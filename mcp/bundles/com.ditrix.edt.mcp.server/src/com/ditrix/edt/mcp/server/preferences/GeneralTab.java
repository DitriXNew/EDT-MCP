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
import org.eclipse.osgi.util.NLS;
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
 * Contains server port, auto-start, checks folder, plain text, tag decoration,
 * update check, and server control settings.
 */
public class GeneralTab
{
    private final Composite composite;
    private final IPreferenceStore store;

    private Spinner portSpinner;
    private Button autoStartCheck;
    private Text checksFolderText;
    private Button allowRemoteCheck;
    private Text authTokenText;
    private Button plainTextCheck;
    private Button showTagsCheck;
    private Combo tagStyleCombo;
    private Combo consentLevelCombo;
    private Combo updateCheckCombo;
    private Label statusLabel;
    private Button startButton;
    private Button stopButton;
    private Button restartButton;

    /** Track created images for disposal */
    private final List<org.eclipse.swt.graphics.Image> managedImages = new ArrayList<>();

    private static final String[][] TAG_STYLES = {
        {Messages.GeneralTab_TagStyle_AllTagsSuffix, PreferenceConstants.TAGS_STYLE_SUFFIX},
        {Messages.GeneralTab_TagStyle_FirstTagOnly, PreferenceConstants.TAGS_STYLE_FIRST_TAG},
        {Messages.GeneralTab_TagStyle_TagCount, PreferenceConstants.TAGS_STYLE_COUNT}
    };

    private static final String[][] CONSENT_LEVELS = {
        {Messages.GeneralTab_ConsentLevel_AskAlways, PreferenceConstants.CONSENT_LEVEL_ASK_ALWAYS},
        {Messages.GeneralTab_ConsentLevel_AllowAll, PreferenceConstants.CONSENT_LEVEL_ALLOW_ALL},
        {Messages.GeneralTab_ConsentLevel_PerTool, PreferenceConstants.CONSENT_LEVEL_PER_TOOL}
    };

    private static final String[][] UPDATE_INTERVALS = {
        {Messages.GeneralTab_UpdateInterval_OnStartup, PreferenceConstants.UPDATE_CHECK_ON_STARTUP},
        {Messages.GeneralTab_UpdateInterval_Hourly, PreferenceConstants.UPDATE_CHECK_HOURLY},
        {Messages.GeneralTab_UpdateInterval_Daily, PreferenceConstants.UPDATE_CHECK_DAILY},
        {Messages.GeneralTab_UpdateInterval_Never, PreferenceConstants.UPDATE_CHECK_NEVER}
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
        createConsentSection();
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
        createLabel(Messages.GeneralTab_ServerPort);
        portSpinner = new Spinner(composite, SWT.BORDER);
        portSpinner.setMinimum(1024);
        portSpinner.setMaximum(65535);
        portSpinner.setSelection(store.getInt(PreferenceConstants.PREF_PORT));
        portSpinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createLabel(""); // spacer //$NON-NLS-1$

        // Auto-start
        autoStartCheck = new Button(composite, SWT.CHECK);
        autoStartCheck.setText(Messages.GeneralTab_AutoStart);
        autoStartCheck.setSelection(store.getBoolean(PreferenceConstants.PREF_AUTO_START));
        GridData autoStartGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        autoStartGd.horizontalSpan = 3;
        autoStartCheck.setLayoutData(autoStartGd);

        // Allow remote (non-loopback) access — security
        allowRemoteCheck = new Button(composite, SWT.CHECK);
        allowRemoteCheck.setText(Messages.GeneralTab_AllowRemote);
        allowRemoteCheck.setSelection(store.getBoolean(PreferenceConstants.PREF_ALLOW_REMOTE_ACCESS));
        GridData allowRemoteGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        allowRemoteGd.horizontalSpan = 3;
        allowRemoteCheck.setLayoutData(allowRemoteGd);

        // Auth token (empty = authentication disabled)
        createLabel(Messages.GeneralTab_AuthToken);
        authTokenText = new Text(composite, SWT.BORDER | SWT.PASSWORD);
        authTokenText.setText(store.getString(PreferenceConstants.PREF_AUTH_TOKEN));
        GridData authTokenGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        authTokenGd.horizontalSpan = 2;
        authTokenText.setLayoutData(authTokenGd);

        // Checks folder
        createLabel(Messages.GeneralTab_ChecksFolder);
        checksFolderText = new Text(composite, SWT.BORDER);
        checksFolderText.setText(store.getString(PreferenceConstants.PREF_CHECKS_FOLDER));
        checksFolderText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button browseButton = new Button(composite, SWT.PUSH);
        browseButton.setText(Messages.GeneralTab_Browse);
        browseButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                DirectoryDialog dialog = new DirectoryDialog(composite.getShell());
                dialog.setMessage(Messages.GeneralTab_SelectChecksFolder);
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
        plainTextCheck.setText(Messages.GeneralTab_PlainTextMode);
        plainTextCheck.setToolTipText(Messages.GeneralTab_PlainTextMode_Tooltip);
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
        showTagsCheck.setText(Messages.GeneralTab_ShowTags);
        showTagsCheck.setSelection(store.getBoolean(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR));
        GridData stGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        stGd.horizontalSpan = 3;
        showTagsCheck.setLayoutData(stGd);

        // Tag decoration style
        createLabel(Messages.GeneralTab_TagDecorationStyle);
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

    private void createConsentSection()
    {
        // Separator
        Label separator = new Label(composite, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData sepGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sepGd.horizontalSpan = 3;
        sepGd.verticalIndent = 5;
        separator.setLayoutData(sepGd);

        // Destructive operations consent level
        createLabel(Messages.GeneralTab_DestructiveOperations);
        consentLevelCombo = new Combo(composite, SWT.DROP_DOWN | SWT.READ_ONLY);
        consentLevelCombo.setToolTipText(Messages.GeneralTab_DestructiveOperations_Tooltip);
        String currentLevel = store.getString(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_LEVEL);
        int levelIndex = 0;
        for (int i = 0; i < CONSENT_LEVELS.length; i++)
        {
            consentLevelCombo.add(CONSENT_LEVELS[i][0]);
            if (CONSENT_LEVELS[i][1].equals(currentLevel))
            {
                levelIndex = i;
            }
        }
        consentLevelCombo.select(levelIndex);
        consentLevelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createLabel(""); //$NON-NLS-1$

        // The 152-FZ PII redaction master toggle lives on the dedicated Privacy tab
        // (see PrivacyTab); it is not duplicated here.
    }

    private void createUpdateSection()
    {
        // Update check interval
        createLabel(Messages.GeneralTab_CheckForUpdates);
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
        checkNowButton.setText(Messages.GeneralTab_CheckNow);

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
                checkResultLink.setText(Messages.GeneralTab_Checking);
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
            link.setText(NLS.bind(Messages.GeneralTab_NewReleaseAvailable, latest));
        }
        else
        {
            link.setText(NLS.bind(Messages.GeneralTab_UpToDate, McpConstants.PLUGIN_VERSION));
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
        sectionTitle.setText(Messages.GeneralTab_ServerControl);
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
        statusTitleLabel.setText(Messages.GeneralTab_Status);

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
        startButton.setText(Messages.GeneralTab_Start);
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
        stopButton.setText(Messages.GeneralTab_Stop);
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
        restartButton.setText(Messages.GeneralTab_Restart);
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
        infoLabel.setText(Messages.GeneralTab_Endpoint);
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
        store.setValue(PreferenceConstants.PREF_ALLOW_REMOTE_ACCESS, allowRemoteCheck.getSelection());
        store.setValue(PreferenceConstants.PREF_AUTH_TOKEN, authTokenText.getText());
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

        int consentIdx = consentLevelCombo.getSelectionIndex();
        if (consentIdx >= 0 && consentIdx < CONSENT_LEVELS.length)
        {
            store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_LEVEL, CONSENT_LEVELS[consentIdx][1]);
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
        allowRemoteCheck.setSelection(PreferenceConstants.DEFAULT_ALLOW_REMOTE_ACCESS);
        authTokenText.setText(PreferenceConstants.DEFAULT_AUTH_TOKEN);
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

        // Find index for default consent level
        for (int i = 0; i < CONSENT_LEVELS.length; i++)
        {
            if (CONSENT_LEVELS[i][1].equals(PreferenceConstants.DEFAULT_DESTRUCTIVE_CONSENT_LEVEL))
            {
                consentLevelCombo.select(i);
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

    /**
     * Returns the consent level currently SELECTED in the combo (the pending, not-yet-committed
     * value), so a sibling tab can react to the user's choice before {@link #performOk()} persists
     * it. Falls back to {@link ConsentSettingsService.Level#ASK_ALWAYS} when nothing is selected.
     */
    public ConsentSettingsService.Level getPendingConsentLevel()
    {
        int idx = consentLevelCombo.getSelectionIndex();
        if (idx >= 0 && idx < CONSENT_LEVELS.length)
        {
            return ConsentSettingsService.Level.fromPreferenceValue(CONSENT_LEVELS[idx][1]);
        }
        return ConsentSettingsService.Level.ASK_ALWAYS;
    }

    private void updateStatusLabel()
    {
        McpServer server = Activator.getDefault().getMcpServer();
        Shell shell = composite.getShell();
        if (server != null && server.isRunning())
        {
            statusLabel.setText(NLS.bind(Messages.GeneralTab_RunningOnPort, server.getPort()));
            statusLabel.setForeground(shell.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
        }
        else
        {
            statusLabel.setText(Messages.GeneralTab_Stopped);
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
                Messages.GeneralTab_StartFailedTitle,
                NLS.bind(Messages.GeneralTab_StartFailedMessage, e.getMessage()));
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
                Messages.GeneralTab_RestartFailedTitle,
                NLS.bind(Messages.GeneralTab_RestartFailedMessage, e.getMessage()));
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
