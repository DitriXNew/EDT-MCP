/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;

/**
 * Confirmation dialog for a gated destructive MCP write. Shows the tool name and a
 * compact {@link ConsentPreview} (title, subtitle, an "N affected item(s)" count, the
 * top affected names and an "and M more" remainder) and offers three verdicts, mirroring
 * {@code FilterByTagDialog}'s three-button pattern:
 * <ul>
 *   <li><b>Allow</b> — {@link IDialogConstants#OK_ID} (the default button);</li>
 *   <li><b>Allow for session</b> — the custom
 *       {@link DestructiveConsentGate#ALLOW_FOR_SESSION_ID} button (the gate records
 *       the tool in its in-memory session-allow set on this verdict);</li>
 *   <li><b>Reject</b> — {@link IDialogConstants#CANCEL_ID}.</li>
 * </ul>
 *
 * <p>The dialog only renders and returns a button id; the {@link DestructiveConsentGate}
 * maps that id to a decision. All labels are English, consistent with the rest of the
 * plugin UI.
 */
public class DestructiveConsentDialog extends Dialog
{
    /** How many preview names to list before collapsing the rest into "and M more". */
    private static final int MAX_LISTED_NAMES = 10;

    private final String toolName;
    private final ConsentPreview preview;

    /**
     * Creates the dialog.
     *
     * @param parentShell the parent shell (must be non-{@code null} — the gate only
     *            opens the dialog on a live UI session)
     * @param toolName the gated tool's name
     * @param preview the compact preview to render (may be {@code null})
     */
    public DestructiveConsentDialog(Shell parentShell, String toolName, ConsentPreview preview)
    {
        super(parentShell);
        this.toolName = toolName;
        this.preview = preview;
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText("Confirm destructive operation"); //$NON-NLS-1$
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite)super.createDialogArea(parent);
        container.setLayout(new GridLayout(1, false));

        // Heading: the preview title (or a generic fallback).
        Label titleLabel = new Label(container, SWT.WRAP);
        String title = preview != null && preview.getTitle() != null
            ? preview.getTitle()
            : "Destructive operation"; //$NON-NLS-1$
        titleLabel.setText(title);
        GridData titleGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        titleGd.widthHint = 460;
        titleLabel.setLayoutData(titleGd);

        // The tool that requested the operation.
        Label toolLabel = new Label(container, SWT.WRAP);
        toolLabel.setText("Requested by MCP tool: " + toolName); //$NON-NLS-1$
        toolLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Subtitle: a one-line effect description.
        if (preview != null && preview.getSubtitle() != null)
        {
            Label subtitleLabel = new Label(container, SWT.WRAP);
            subtitleLabel.setText(preview.getSubtitle());
            GridData subtitleGd = new GridData(SWT.FILL, SWT.TOP, true, false);
            subtitleGd.widthHint = 460;
            subtitleLabel.setLayoutData(subtitleGd);
        }

        // Separator.
        Label separator = new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Compact preview body: "N affected item(s)" + top-N names + "and M more".
        Text body = new Text(container,
            SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        body.setText(buildPreviewText());
        GridData bodyGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        bodyGd.heightHint = 120;
        bodyGd.widthHint = 460;
        body.setLayoutData(bodyGd);

        return container;
    }

    /**
     * Builds the compact preview body text: an "N affected item(s)" line, the top
     * listed names (up to {@link #MAX_LISTED_NAMES}), and an "and M more" remainder
     * line for the names that were collected but not listed.
     *
     * <p>The remainder is keyed off the number of collected names, not the total
     * count. A tool's total count and its top-names list can carry different
     * semantics — {@code rename_metadata_object}, for example, reports the total
     * number of change items (which can be large) but collects only one title per
     * refactoring — so keying "and M more" off {@code names.size()} keeps the line
     * from claiming more <em>names</em> than were ever collected. When no names are
     * collected the count line stands alone (its meaning is carried by the header
     * and subtitle above), so a "0 affected items" body no longer reads as an empty
     * "0 objects:" list.
     *
     * @return the preview body (never {@code null})
     */
    String buildPreviewText()
    {
        if (preview == null)
        {
            return "This operation is irreversible."; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        int total = preview.getTotalCount();
        sb.append(total).append(total == 1 ? " affected item:" : " affected items:"); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> names = preview.getTopNames();
        int listed = Math.min(names.size(), MAX_LISTED_NAMES);
        for (int i = 0; i < listed; i++)
        {
            sb.append("\n  • ").append(names.get(i)); //$NON-NLS-1$
        }
        // Remainder is the collected-but-unlisted names, never (total - listed):
        // a tool may report a large total while collecting only a few names.
        int remainder = names.size() - listed;
        if (remainder > 0)
        {
            sb.append("\n  … and ").append(remainder).append(" more"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Allow", true); //$NON-NLS-1$
        createButton(parent, DestructiveConsentGate.ALLOW_FOR_SESSION_ID, "Allow for session", false); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, "Reject", false); //$NON-NLS-1$
    }

    @Override
    protected void buttonPressed(int buttonId)
    {
        // Close the dialog with the pressed id as the return code so the gate can map
        // it: OK -> Allow, ALLOW_FOR_SESSION_ID -> Allow-for-session, CANCEL -> Reject.
        if (buttonId == DestructiveConsentGate.ALLOW_FOR_SESSION_ID)
        {
            setReturnCode(DestructiveConsentGate.ALLOW_FOR_SESSION_ID);
            close();
            return;
        }
        super.buttonPressed(buttonId);
    }
}
