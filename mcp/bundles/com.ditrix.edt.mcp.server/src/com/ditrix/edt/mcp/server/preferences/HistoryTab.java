/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.history.HistoryConfig;
import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.history.McpCallHistoryFileLog;

/**
 * History settings tab for MCP Server preferences. Controls the request/response
 * history recorder wrapped around {@code McpProtocolHandler}: whether recording is
 * on (default ON), the in-memory ring-buffer size, and an optional JSONL file log
 * (default OFF, for privacy) with a configurable target folder.
 *
 * <p>The preference keys and defaults are owned by {@link HistoryConfig} (the recorder's
 * config holder) so the UI and the recorder stay in sync without churning
 * {@link PreferenceConstants}.</p>
 */
public class HistoryTab
{
    private final Composite composite;
    private final IPreferenceStore store;

    private Button recordCheck;
    private Spinner bufferSizeSpinner;
    private Button fileLogCheck;
    private Text logFolderText;
    private Button browseButton;

    public HistoryTab(Composite parent)
    {
        this.store = Activator.getDefault().getPreferenceStore();

        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        composite.setLayout(layout);

        createRecordSection();
        createFileLogSection();

        updateFileLogEnablement();
    }

    public Composite getControl()
    {
        return composite;
    }

    private void createRecordSection()
    {
        // Record on/off (default ON)
        recordCheck = new Button(composite, SWT.CHECK);
        recordCheck.setText(Messages.HistoryTab_RecordEnabled);
        recordCheck.setToolTipText(Messages.HistoryTab_RecordEnabled_Tooltip);
        recordCheck.setSelection(store.getBoolean(HistoryConfig.KEY_RECORD));
        GridData recordGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        recordGd.horizontalSpan = 3;
        recordCheck.setLayoutData(recordGd);

        // Ring-buffer size N
        createLabel(Messages.HistoryTab_BufferSize);
        bufferSizeSpinner = new Spinner(composite, SWT.BORDER);
        bufferSizeSpinner.setToolTipText(Messages.HistoryTab_BufferSize_Tooltip);
        bufferSizeSpinner.setMinimum(HistoryConfig.MIN_BUFFER_SIZE);
        bufferSizeSpinner.setMaximum(HistoryConfig.MAX_BUFFER_SIZE);
        bufferSizeSpinner.setSelection(store.getInt(HistoryConfig.KEY_BUFFER_SIZE));
        bufferSizeSpinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createLabel(""); // spacer //$NON-NLS-1$
    }

    private void createFileLogSection()
    {
        // Separator
        Label separator = new Label(composite, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData sepGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sepGd.horizontalSpan = 3;
        sepGd.verticalIndent = 5;
        separator.setLayoutData(sepGd);

        // File-log on/off (default OFF, for privacy)
        fileLogCheck = new Button(composite, SWT.CHECK);
        fileLogCheck.setText(Messages.HistoryTab_FileLog);
        fileLogCheck.setToolTipText(Messages.HistoryTab_FileLog_Tooltip);
        fileLogCheck.setSelection(store.getBoolean(HistoryConfig.KEY_FILE_LOG));
        GridData fileLogGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        fileLogGd.horizontalSpan = 3;
        fileLogCheck.setLayoutData(fileLogGd);
        fileLogCheck.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                updateFileLogEnablement();
            }
        });

        // Log folder + Browse (DirectoryDialog)
        createLabel(Messages.HistoryTab_LogFolder);
        logFolderText = new Text(composite, SWT.BORDER);
        logFolderText.setText(store.getString(HistoryConfig.KEY_FILE_PATH));
        logFolderText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        browseButton = new Button(composite, SWT.PUSH);
        browseButton.setText(Messages.GeneralTab_Browse);
        browseButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                DirectoryDialog dialog = new DirectoryDialog(composite.getShell());
                dialog.setMessage(Messages.HistoryTab_SelectLogFolder);
                String path = dialog.open();
                if (path != null)
                {
                    logFolderText.setText(path);
                }
            }
        });

        // Privacy note: history may contain infobase data returned by tools.
        Label privacyNote = new Label(composite, SWT.WRAP);
        privacyNote.setText(Messages.HistoryTab_PrivacyNote);
        GridData noteGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        noteGd.horizontalSpan = 3;
        noteGd.verticalIndent = 5;
        noteGd.widthHint = 480;
        privacyNote.setLayoutData(noteGd);
    }

    private void updateFileLogEnablement()
    {
        boolean enabled = fileLogCheck.getSelection();
        logFolderText.setEnabled(enabled);
        browseButton.setEnabled(enabled);
    }

    /**
     * Saves all values to the preference store.
     */
    public void performOk()
    {
        store.setValue(HistoryConfig.KEY_RECORD, recordCheck.getSelection());
        store.setValue(HistoryConfig.KEY_BUFFER_SIZE, bufferSizeSpinner.getSelection());
        store.setValue(HistoryConfig.KEY_FILE_LOG, fileLogCheck.getSelection());
        store.setValue(HistoryConfig.KEY_FILE_PATH, logFolderText.getText());

        // Push the record toggle and buffer size to the live recorder so a preference
        // change takes effect on the running singleton immediately (record + buffer
        // size are cached fields there), read back from the store so the values are the
        // same clamped ones that were just persisted.
        McpCallHistory.getInstance().applyPreferences(store);

        // Re-evaluate the file-log sink from the just-saved preferences so enabling or
        // disabling the log, or changing its folder, takes effect immediately without
        // an EDT restart (unregisters + closes the old sink, installs a new one).
        McpCallHistoryFileLog.reconfigure();
    }

    /**
     * Resets all values to defaults.
     */
    public void performDefaults()
    {
        recordCheck.setSelection(HistoryConfig.DEFAULT_RECORD);
        bufferSizeSpinner.setSelection(HistoryConfig.DEFAULT_BUFFER_SIZE);
        fileLogCheck.setSelection(HistoryConfig.DEFAULT_FILE_LOG);
        logFolderText.setText(HistoryConfig.DEFAULT_FILE_PATH);
        updateFileLogEnablement();
    }

    /**
     * Disposes tab-owned resources. This tab owns no SWT images; kept for symmetry with the
     * other tabs' lifecycle so {@link McpServerPreferencePage} can delegate uniformly.
     */
    public void dispose()
    {
        // No managed resources to dispose.
    }

    private Label createLabel(String text)
    {
        Label label = new Label(composite, SWT.NONE);
        label.setText(text);
        return label;
    }
}
