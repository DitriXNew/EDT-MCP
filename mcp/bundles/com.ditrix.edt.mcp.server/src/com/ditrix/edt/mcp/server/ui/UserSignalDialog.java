/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.ui;

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

import com.ditrix.edt.mcp.server.UserSignal;
import com.ditrix.edt.mcp.server.UserSignal.SignalType;

/**
 * Dialog for sending a signal to the agent with preview and custom message option.
 */
public class UserSignalDialog extends Dialog
{
    private final SignalType signalType;
    private final String title;
    private Text messageText;
    private String message;
    
    /**
     * Creates a new signal dialog.
     * 
     * @param parentShell the parent shell
     * @param signalType the signal type
     * @param title the dialog title
     */
    public UserSignalDialog(Shell parentShell, SignalType signalType, String title)
    {
        super(parentShell);
        this.signalType = signalType;
        this.title = title;
        this.message = UserSignal.getDefaultMessage(signalType);
    }
    
    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(title);
    }
    
    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite) super.createDialogArea(parent);
        container.setLayout(new GridLayout(1, false));
        
        // Description label
        Label descLabel = new Label(container, SWT.WRAP);
        descLabel.setText(getDescriptionForType(signalType));
        GridData descGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        descGd.widthHint = 400;
        descLabel.setLayoutData(descGd);
        
        // Separator
        Label separator = new Label(container, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        
        // Message to agent label
        Label msgLabel = new Label(container, SWT.NONE);
        msgLabel.setText("Message to send to agent:");
        
        // Message text area
        messageText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        messageText.setText(message);
        GridData textGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        textGd.heightHint = 100;
        textGd.widthHint = 400;
        messageText.setLayoutData(textGd);
        
        // Preview label
        Label previewLabel = new Label(container, SWT.NONE);
        previewLabel.setText("This will be returned to agent:");
        previewLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        
        // Preview (read-only)
        Text previewText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        previewText.setBackground(container.getDisplay().getSystemColor(SWT.COLOR_INFO_BACKGROUND));
        GridData previewGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        previewGd.heightHint = 80;
        previewText.setLayoutData(previewGd);
        
        // Update preview when message changes
        Runnable updatePreview = () -> {
            String msg = messageText.getText();
            UserSignal signal = new UserSignal(signalType, msg);
            previewText.setText(signal.toJson());
        };
        messageText.addModifyListener(e -> updatePreview.run());
        updatePreview.run();
        
        return container;
    }
    
    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Send to Agent", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
    
    @Override
    protected void okPressed()
    {
        message = messageText.getText();
        super.okPressed();
    }
    
    /**
     * Gets the message entered by user.
     * 
     * @return the message
     */
    public String getMessage()
    {
        return message;
    }
    
    /**
     * Gets the signal type.
     * 
     * @return the signal type
     */
    public SignalType getSignalType()
    {
        return signalType;
    }
    
    private String getDescriptionForType(SignalType type)
    {
        switch (type)
        {
            case CANCEL:
                return "Cancel the current operation. The agent will be notified that you manually stopped the operation.";
            case RETRY:
                return "Ask the agent to retry the last operation. Use this when an EDT error occurred and you want the agent to try again.";
            case BACKGROUND:
                return "Notify the agent that this is a long-running operation. The agent should check the status periodically instead of waiting.";
            case EXPERT:
                return "Stop the current action and ask the agent to consult with you (the expert) before continuing.";
            case CUSTOM:
            default:
                return "Send a custom message to the agent.";
        }
    }
}
