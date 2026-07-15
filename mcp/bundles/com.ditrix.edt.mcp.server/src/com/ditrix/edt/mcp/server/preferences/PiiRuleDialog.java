/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.utils.privacy.PiiRule;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleScope;

/**
 * Add/edit dialog for a single {@link PiiRule} in the Privacy preferences tab. It
 * exposes every field of a rule: the enabled flag, the detection regular expression,
 * the replacement suffix ({@link PiiRule#getRepresentation() representation}), the
 * countable flag, and the {@link PiiRuleScope} (NAME / VALUE / BOTH).
 * <p>
 * The dialog validates the regular expression (non-empty and compilable via
 * {@link Pattern#compile(String)}) before accepting the input, so a malformed rule can
 * never be committed to the table.
 */
public class PiiRuleDialog extends Dialog
{
    private final PiiRule original;

    private Button enabledCheck;
    private Text regexText;
    private Text suffixText;
    private Button countableCheck;
    private Combo scopeCombo;

    private PiiRule result;

    /**
     * Creates the dialog.
     *
     * @param parentShell the parent shell
     * @param rule the rule to edit, or {@code null} to add a new rule
     */
    public PiiRuleDialog(Shell parentShell, PiiRule rule)
    {
        super(parentShell);
        this.original = rule;
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(original == null ? Messages.PiiRuleDialog_TitleAdd : Messages.PiiRuleDialog_TitleEdit);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite)super.createDialogArea(parent);
        GridLayoutFactory.fillDefaults().margins(10, 10).numColumns(2).applyTo(container);

        // Enabled
        enabledCheck = new Button(container, SWT.CHECK);
        enabledCheck.setText(Messages.PiiRuleDialog_Enabled);
        enabledCheck.setSelection(original == null || original.isEnabled());
        GridDataFactory.fillDefaults().span(2, 1).applyTo(enabledCheck);

        // Regular expression
        Label regexLabel = new Label(container, SWT.NONE);
        regexLabel.setText(Messages.PiiRuleDialog_Regex);
        regexText = new Text(container, SWT.BORDER);
        regexText.setText(original != null ? original.getRegex() : ""); //$NON-NLS-1$
        GridDataFactory.fillDefaults().grab(true, false).hint(320, SWT.DEFAULT).applyTo(regexText);

        // Replacement suffix (the representation stem)
        Label suffixLabel = new Label(container, SWT.NONE);
        suffixLabel.setText(Messages.PiiRuleDialog_Suffix);
        suffixText = new Text(container, SWT.BORDER);
        suffixText.setText(original != null ? original.getRepresentation() : ""); //$NON-NLS-1$
        GridDataFactory.fillDefaults().grab(true, false).applyTo(suffixText);

        // Scope
        Label scopeLabel = new Label(container, SWT.NONE);
        scopeLabel.setText(Messages.PiiRuleDialog_Scope);
        scopeCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        for (PiiRuleScope scope : PiiRuleScope.values())
        {
            scopeCombo.add(scope.name());
        }
        scopeCombo.select((original != null ? original.getScope() : PiiRuleScope.BOTH).ordinal());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(scopeCombo);

        // Countable
        countableCheck = new Button(container, SWT.CHECK);
        countableCheck.setText(Messages.PiiRuleDialog_Countable);
        countableCheck.setSelection(original != null && original.isCountable());
        GridDataFactory.fillDefaults().span(2, 1).applyTo(countableCheck);

        return container;
    }

    @Override
    protected void okPressed()
    {
        String regex = regexText.getText().trim();
        String error = validateRegex(regex);
        if (error != null)
        {
            MessageDialog.openError(getShell(), Messages.PiiRuleDialog_InvalidRegexTitle, error);
            return;
        }
        int scopeIndex = scopeCombo.getSelectionIndex();
        PiiRuleScope scope = scopeIndex >= 0 ? PiiRuleScope.values()[scopeIndex] : PiiRuleScope.BOTH;
        result = new PiiRule(
            enabledCheck.getSelection(),
            regex,
            scope,
            suffixText.getText().trim(),
            countableCheck.getSelection());
        super.okPressed();
    }

    /**
     * Validates a detection regular expression: it must be non-empty and compile
     * cleanly. Pure and side-effect-free so it is unit-testable headlessly.
     *
     * @param regex the regular expression to validate
     * @return a localized error message, or {@code null} when the regex is valid
     */
    public static String validateRegex(String regex)
    {
        if (regex == null || regex.isEmpty())
        {
            return Messages.PiiRuleDialog_EmptyRegexMessage;
        }
        try
        {
            Pattern.compile(regex);
            return null;
        }
        catch (PatternSyntaxException e)
        {
            return NLS.bind(Messages.PiiRuleDialog_InvalidRegexMessage, e.getMessage());
        }
    }

    /**
     * Returns the rule built from the dialog fields, or {@code null} if the dialog was
     * cancelled.
     *
     * @return the edited/created rule, or {@code null}
     */
    public PiiRule getResult()
    {
        return result;
    }
}
