/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.UpdateChecker;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * Dialog showing release notes of the latest available version.
 * Has an "Open on GitHub" button to navigate to the release page.
 */
public class ReleaseNotesDialog extends TitleAreaDialog
{
    /** Custom button ID for "Open on GitHub" */
    private static final int OPEN_GITHUB_ID = IDialogConstants.CLIENT_ID + 1;

    private final String version;
    private final String notes;
    private final String url;

    /**
     * Creates the dialog.
     *
     * @param parentShell parent shell
     * @param version     new version string, e.g. {@code "1.25.0"}
     * @param notes       release notes (markdown text from GitHub)
     * @param url         URL of the GitHub release page
     */
    public ReleaseNotesDialog(Shell parentShell, String version, String notes, String url)
    {
        super(parentShell);
        this.version = version;
        this.notes   = notes != null ? notes : ""; //$NON-NLS-1$
        this.url     = url != null ? url : UpdateChecker.RELEASES_PAGE_URL;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    public void create()
    {
        super.create();
        setTitle("New release: " + version); //$NON-NLS-1$
        setMessage("Current version: " + McpConstants.PLUGIN_VERSION //$NON-NLS-1$
            + "  \u2192  New version: " + version); //$NON-NLS-1$
        getShell().setText("EDT MCP Server \u2014 Update Available"); //$NON-NLS-1$
        getShell().setSize(640, 520);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout(1, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Text notesText = new Text(container, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        notesText.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        notesText.setText(notes.isEmpty() ? "(no release notes provided)" : notes); //$NON-NLS-1$
        GridDataFactory.fillDefaults().grab(true, true).applyTo(notesText);

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, OPEN_GITHUB_ID, "Open on GitHub", false); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId)
    {
        if (buttonId == OPEN_GITHUB_ID)
        {
            org.eclipse.swt.program.Program.launch(url);
            // Keep the dialog open so the user can still read the notes
            return;
        }
        super.buttonPressed(buttonId);
    }

    @Override
    protected boolean isResizable()
    {
        return true;
    }
}
