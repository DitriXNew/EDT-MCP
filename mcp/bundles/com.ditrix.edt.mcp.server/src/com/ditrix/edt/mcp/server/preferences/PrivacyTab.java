/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRule;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleCodec;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleSet;

/**
 * Privacy settings tab for MCP Server preferences: the 152-FZ PII-redaction controls.
 * <p>
 * It holds the master on/off toggle (relocated here from the General tab; its
 * preference key {@link PreferenceConstants#PREF_PII_REDACTION_ENABLED} is unchanged
 * and still defaults OFF), the pseudonym salt
 * ({@link PreferenceConstants#PREF_PII_SALT}), and a table of user-configurable
 * detection rules with Add/Edit/Remove (via {@link PiiRuleDialog}), plus two population
 * buttons:
 * <ul>
 *   <li><b>Load defaults</b> - reads the bundled offline rule set
 *       ({@link PiiRuleCodec#loadBundledDefaults()});</li>
 *   <li><b>Update from repo</b> - fetches the latest rule set from the online
 *       repository ({@link PiiDefaultsFetcher#fetchDefaultsAsync}) on a background
 *       thread.</li>
 * </ul>
 * Both population buttons only STAGE their result into the in-memory table; nothing is
 * persisted until the user clicks Apply/OK (which runs {@link #performOk()}), so a repo
 * fetch never overwrites the stored rules without an explicit Apply. The current rules
 * and salt are resolved through {@link PiiRuleSettings}; the rule table is persisted as
 * JSON via {@link PiiRuleCodec}. The enabled flag of each row is the table checkbox.
 */
public class PrivacyTab
{
    private final Composite composite;
    private final IPreferenceStore store;

    private Button masterCheck;
    private Text saltText;
    private CheckboxTableViewer rulesViewer;
    private Label statusLabel;
    private Button updateButton;

    /** The working (in-memory, staged) rule set backing the table. */
    private List<PiiRule> rules = new ArrayList<>();

    /** Guards async repo-fetch callbacks after the tab is disposed. */
    private boolean disposed;

    public PrivacyTab(Composite parent)
    {
        this.store = Activator.getDefault() != null ? Activator.getDefault().getPreferenceStore() : null;

        composite = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(5, 5).numColumns(1).applyTo(composite);

        createMasterSection();
        createSaltSection();
        createRulesSection();
        createStatusSection();

        loadFromStore();
    }

    public Composite getControl()
    {
        return composite;
    }

    private void createMasterSection()
    {
        masterCheck = new Button(composite, SWT.CHECK);
        masterCheck.setText(Messages.PrivacyTab_MasterToggle);
        masterCheck.setToolTipText(Messages.PrivacyTab_MasterToggle_Tooltip);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(masterCheck);
    }

    private void createSaltSection()
    {
        Composite row = new Composite(composite, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(row);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(row);

        Label saltLabel = new Label(row, SWT.NONE);
        saltLabel.setText(Messages.PrivacyTab_Salt);
        saltLabel.setToolTipText(Messages.PrivacyTab_Salt_Tooltip);

        saltText = new Text(row, SWT.BORDER);
        saltText.setToolTipText(Messages.PrivacyTab_Salt_Tooltip);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(saltText);
    }

    private void createRulesSection()
    {
        Group group = new Group(composite, SWT.NONE);
        group.setText(Messages.PrivacyTab_RulesGroup);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(group);
        GridLayoutFactory.fillDefaults().margins(5, 5).numColumns(2).applyTo(group);

        Table table = new Table(group,
            SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(420, 200).applyTo(table);

        rulesViewer = new CheckboxTableViewer(table);
        rulesViewer.setContentProvider(ArrayContentProvider.getInstance());

        addColumn(Messages.PrivacyTab_ColumnEnabled, 45, e -> ""); //$NON-NLS-1$
        addColumn(Messages.PrivacyTab_ColumnRegex, 200, PiiRule::getRegex);
        addColumn(Messages.PrivacyTab_ColumnSuffix, 120, PiiRule::getRepresentation);
        addColumn(Messages.PrivacyTab_ColumnCountable, 80, e -> e.isCountable() ? "\u2713" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        addColumn(Messages.PrivacyTab_ColumnScope, 90, e -> e.getScope().name());

        createRuleButtons(group);
    }

    private void createRuleButtons(Group group)
    {
        Composite buttons = new Composite(group, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.TOP).applyTo(buttons);
        GridLayoutFactory.fillDefaults().applyTo(buttons);

        Button addButton = new Button(buttons, SWT.PUSH);
        addButton.setText(Messages.PrivacyTab_Add);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(addButton);
        addButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                addRule();
            }
        });

        Button editButton = new Button(buttons, SWT.PUSH);
        editButton.setText(Messages.PrivacyTab_Edit);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(editButton);
        editButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                editRule();
            }
        });

        Button removeButton = new Button(buttons, SWT.PUSH);
        removeButton.setText(Messages.PrivacyTab_Remove);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(removeButton);
        removeButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                removeRule();
            }
        });

        Label separator = new Label(buttons, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(separator);

        Button loadDefaultsButton = new Button(buttons, SWT.PUSH);
        loadDefaultsButton.setText(Messages.PrivacyTab_LoadDefaults);
        loadDefaultsButton.setToolTipText(Messages.PrivacyTab_LoadDefaults_Tooltip);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(loadDefaultsButton);
        loadDefaultsButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                loadDefaults();
            }
        });

        updateButton = new Button(buttons, SWT.PUSH);
        updateButton.setText(Messages.PrivacyTab_UpdateFromRepo);
        updateButton.setToolTipText(Messages.PrivacyTab_UpdateFromRepo_Tooltip);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(updateButton);
        updateButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                updateFromRepo();
            }
        });
    }

    private void createStatusSection()
    {
        statusLabel = new Label(composite, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(statusLabel);
    }

    /** Shows an informational status message in the default text colour (clears any error styling). */
    private void setStatus(String text)
    {
        statusLabel.setForeground(null);
        statusLabel.setText(text);
    }

    /**
     * Shows a failure message inline in the status label, styled red. Review criterion #7 requires
     * the repo-fetch failure to be reported non-modally (never a modal popup), so an unattended or
     * automated preferences flow can never block on it.
     */
    private void setErrorStatus(String text)
    {
        statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_RED));
        statusLabel.setText(text);
    }

    /** Adds one label-provider column driven by a rule-to-text accessor. */
    private void addColumn(String header, int width, RuleText text)
    {
        TableViewerColumn column = new TableViewerColumn(rulesViewer, SWT.NONE);
        column.getColumn().setText(header);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof PiiRule ? text.of((PiiRule)element) : ""; //$NON-NLS-1$
            }
        });
    }

    private void addRule()
    {
        PiiRuleDialog dialog = new PiiRuleDialog(composite.getShell(), null);
        if (dialog.open() == Dialog.OK && dialog.getResult() != null)
        {
            List<PiiRule> current = collectRules();
            current.add(dialog.getResult());
            setRules(current);
        }
    }

    private void editRule()
    {
        PiiRule selected = selectedRule();
        if (selected == null)
        {
            return;
        }
        int index = rules.indexOf(selected);
        PiiRuleDialog dialog = new PiiRuleDialog(composite.getShell(), selected);
        if (dialog.open() == Dialog.OK && dialog.getResult() != null && index >= 0)
        {
            List<PiiRule> current = collectRules();
            current.set(index, dialog.getResult());
            setRules(current);
        }
    }

    private void removeRule()
    {
        PiiRule selected = selectedRule();
        if (selected == null)
        {
            return;
        }
        int index = rules.indexOf(selected);
        if (index < 0)
        {
            return;
        }
        List<PiiRule> current = collectRules();
        current.remove(index);
        setRules(current);
    }

    private void loadDefaults()
    {
        List<PiiRule> defaults = PiiRuleCodec.loadBundledDefaults().getRules();
        setRules(defaults);
        setStatus(NLS.bind(Messages.PrivacyTab_DefaultsLoaded, Integer.valueOf(defaults.size())));
        composite.layout(true, true);
    }

    /**
     * Fetches the latest rule set from the online repository off the UI thread (the
     * fetcher owns the background daemon thread and the untrusted-content validation)
     * and, on success, STAGES it into the in-memory table. Nothing is persisted here:
     * the user must click Apply/OK. The fetcher invokes the callback on its background
     * thread, so it is marshalled back to the UI thread here.
     */
    private void updateFromRepo()
    {
        updateButton.setEnabled(false);
        setStatus(Messages.PrivacyTab_Updating);
        composite.layout(true, true);

        Display display = composite.getDisplay();
        PiiDefaultsFetcher.<PiiRuleSet>fetchDefaultsAsync(null, PiiRuleCodec::decode, fetchResult -> {
            if (display == null || display.isDisposed())
            {
                return;
            }
            display.asyncExec(() -> {
                if (!disposed && !composite.isDisposed())
                {
                    applyFetchResult(fetchResult);
                }
            });
        });
    }

    private void applyFetchResult(PiiDefaultsFetcher.FetchResult<PiiRuleSet> fetchResult)
    {
        if (fetchResult.isOk())
        {
            PiiRuleSet fetched = fetchResult.getRuleset();
            setRules(fetched != null ? fetched.getRules() : new ArrayList<>());
            setStatus(NLS.bind(Messages.PrivacyTab_UpdateStaged, Integer.valueOf(fetchResult.getRuleCount())));
        }
        else
        {
            setErrorStatus(NLS.bind(Messages.PrivacyTab_UpdateFailedMessage, fetchResult.getErrorMessage()));
        }
        updateButton.setEnabled(true);
        composite.layout(true, true);
    }

    /**
     * Persists the master toggle (existing preference key), the salt, and the rule table
     * (serialized to JSON via {@link PiiRuleCodec#encode}).
     */
    public void performOk()
    {
        if (store == null)
        {
            return;
        }
        store.setValue(PreferenceConstants.PREF_PII_REDACTION_ENABLED, masterCheck.getSelection());
        store.setValue(PreferenceConstants.PREF_PII_SALT, saltText.getText());
        store.setValue(PreferenceConstants.PREF_PII_RULES_JSON,
            PiiRuleCodec.encode(new PiiRuleSet(collectRules())));
    }

    /**
     * Resets to defaults: master toggle OFF, the default salt, and the bundled rule set.
     */
    public void performDefaults()
    {
        masterCheck.setSelection(PreferenceConstants.DEFAULT_PII_REDACTION_ENABLED);
        saltText.setText(PreferenceConstants.DEFAULT_PII_SALT);
        setRules(PiiRuleCodec.loadBundledDefaults().getRules());
        setStatus(""); //$NON-NLS-1$
        composite.layout(true, true);
    }

    public void dispose()
    {
        disposed = true;
    }

    private void loadFromStore()
    {
        masterCheck.setSelection(store != null
            && store.getBoolean(PreferenceConstants.PREF_PII_REDACTION_ENABLED));
        saltText.setText(PiiRuleSettings.currentSalt());
        setRules(PiiRuleSettings.currentRuleSet().getRules());
    }

    /** Replaces the working rule set and syncs the table (checkbox = each rule's enabled flag). */
    private void setRules(List<PiiRule> newRules)
    {
        rules = new ArrayList<>(newRules != null ? newRules : new ArrayList<>());
        rulesViewer.setInput(rules);
        for (PiiRule rule : rules)
        {
            rulesViewer.setChecked(rule, rule.isEnabled());
        }
    }

    /**
     * Rebuilds the rule list from the current table state, folding the live checkbox
     * state into each rule's enabled flag (the checkbox is the source of truth for it).
     */
    private List<PiiRule> collectRules()
    {
        List<PiiRule> out = new ArrayList<>(rules.size());
        for (PiiRule rule : rules)
        {
            boolean enabled = rulesViewer.getChecked(rule);
            out.add(new PiiRule(enabled, rule.getRegex(), rule.getScope(),
                rule.getRepresentation(), rule.isCountable()));
        }
        return out;
    }

    private PiiRule selectedRule()
    {
        Object element = rulesViewer.getStructuredSelection().getFirstElement();
        return element instanceof PiiRule ? (PiiRule)element : null;
    }

    /** Accessor from a rule to a display string for one table column. */
    @FunctionalInterface
    private interface RuleText
    {
        String of(PiiRule rule);
    }
}
