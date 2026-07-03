/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ITreeViewerListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreeExpansionEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings.ParameterDef;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;

/**
 * Tools management tab for MCP Server preferences.
 * Left side: tree of tool groups with checkboxes to enable/disable tools and groups.
 * Right side: description and configurable parameter settings for the selected item.
 */
public class ToolsTab
{
    private final Composite composite;
    private CheckboxTreeViewer treeViewer;
    private Combo presetCombo;
    private Label countLabel;
    private Composite detailPanel;

    /** Local copy of disabled tools for editing (committed on performOk) */
    private final Set<String> disabledTools;

    /** Local copy of destructive-allowed tools for editing (committed on performOk) */
    private final Set<String> destructiveAllowedTools;

    /** Flag to avoid recursion when updating check states (SWT is single-threaded) */
    private boolean updatingChecks = false;

    /** Track created images for disposal */
    private final List<Image> managedImages = new ArrayList<>();

    // Parameter management (merged from ToolSettingsTab)
    private final ToolParameterSettings paramSettings = ToolParameterSettings.getInstance();
    /** Pending values: tool.param -> value */
    private final Map<String, Integer> pendingValues = new LinkedHashMap<>();
    /** Currently displayed spinners for the selected tool */
    private final List<Spinner> currentSpinners = new ArrayList<>();
    private String selectedTool;

    /**
     * Sibling General tab, used to read the PENDING (not-yet-committed) consent level so the
     * per-tool allow checkbox reflects the user's current combo choice, not the persisted store.
     * May be {@code null} (then the persisted level is used).
     */
    private GeneralTab generalTab;

    public ToolsTab(Composite parent)
    {
        disabledTools = new HashSet<>(ToolSettingsService.getInstance().getDisabledTools());
        destructiveAllowedTools = new HashSet<>(ConsentSettingsService.getInstance().getAllowedTools());
        loadAllValues();

        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);

        SashForm sash = new SashForm(composite, SWT.HORIZONTAL);
        GridData sashGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        sashGd.widthHint = 550;
        sashGd.heightHint = 350;
        sash.setLayoutData(sashGd);

        // Left side: preset bar + checkbox tree + count label
        Composite left = new Composite(sash, SWT.NONE);
        GridLayout leftLayout = new GridLayout(1, false);
        leftLayout.marginWidth = 5;
        leftLayout.marginHeight = 5;
        left.setLayout(leftLayout);
        createPresetBar(left);
        createToolTree(left);
        createCountLabel(left);

        // Right side: detail panel (description + settings)
        createDetailPanel(sash);

        sash.setWeights(new int[]{40, 60});
    }

    public Composite getControl()
    {
        return composite;
    }

    /**
     * Links the sibling General tab so the per-tool destructive-consent checkbox can reflect the
     * PENDING consent level selected on that tab (before it is committed on {@code performOk}),
     * instead of the persisted store value.
     *
     * @param generalTab the sibling General tab, or {@code null} to fall back to the persisted level
     */
    public void setGeneralTab(GeneralTab generalTab)
    {
        this.generalTab = generalTab;
    }

    private void createPresetBar(Composite parent)
    {
        Composite bar = new Composite(parent, SWT.NONE);
        // Layout: [✓] [✗] [Preset: label] [Preset combo]
        GridLayout barLayout = new GridLayout(4, false);
        barLayout.marginWidth = 0;
        barLayout.marginHeight = 0;
        bar.setLayout(barLayout);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Check All button (leftmost)
        Button checkAllButton = new Button(bar, SWT.PUSH);
        checkAllButton.setToolTipText("Enable all tools"); //$NON-NLS-1$
        ImageDescriptor checkAllIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/check_all.png"); //$NON-NLS-1$
        if (checkAllIcon != null)
        {
            Image img = checkAllIcon.createImage();
            checkAllButton.setImage(img);
            managedImages.add(img);
        }
        else
        {
            checkAllButton.setText("All"); //$NON-NLS-1$
        }
        checkAllButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                disabledTools.clear();
                refreshCheckStates();
                selectMatchingPreset();
                updateCountLabel();
            }
        });

        // Uncheck All button
        Button uncheckAllButton = new Button(bar, SWT.PUSH);
        uncheckAllButton.setToolTipText("Disable all tools"); //$NON-NLS-1$
        ImageDescriptor uncheckAllIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/uncheck_all.png"); //$NON-NLS-1$
        if (uncheckAllIcon != null)
        {
            Image img = uncheckAllIcon.createImage();
            uncheckAllButton.setImage(img);
            managedImages.add(img);
        }
        else
        {
            uncheckAllButton.setText("None"); //$NON-NLS-1$
        }
        uncheckAllButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                disabledTools.clear();
                for (ToolGroup group : ToolGroup.values())
                {
                    disabledTools.addAll(group.getToolNames());
                }
                refreshCheckStates();
                selectMatchingPreset();
                updateCountLabel();
            }
        });

        // Preset label + combo (after buttons)
        Label presetLabel = new Label(bar, SWT.NONE);
        presetLabel.setText("Preset:"); //$NON-NLS-1$

        presetCombo = new Combo(bar, SWT.DROP_DOWN | SWT.READ_ONLY);
        for (ToolPreset preset : ToolPreset.values())
        {
            presetCombo.add(preset.getDisplayName());
        }
        presetCombo.setToolTipText("Select a preset configuration or customize manually"); //$NON-NLS-1$
        GridData comboGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        presetCombo.setLayoutData(comboGd);
        selectMatchingPreset();

        presetCombo.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                int idx = presetCombo.getSelectionIndex();
                if (idx >= 0)
                {
                    ToolPreset preset = ToolPreset.values()[idx];
                    if (preset != ToolPreset.CUSTOM && preset.getDisabledTools() != null)
                    {
                        disabledTools.clear();
                        disabledTools.addAll(preset.getDisabledTools());
                        refreshCheckStates();
                        updateCountLabel();
                    }
                }
            }
        });
    }

    private void createToolTree(Composite parent)
    {
        treeViewer = new CheckboxTreeViewer(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        treeViewer.setContentProvider(new ToolTreeContentProvider());
        treeViewer.setLabelProvider(new ToolTreeLabelProvider());
        treeViewer.setInput(ToolGroup.values());

        refreshCheckStates();

        treeViewer.addCheckStateListener(new ICheckStateListener()
        {
            @Override
            public void checkStateChanged(CheckStateChangedEvent event)
            {
                if (updatingChecks)
                {
                    return;
                }
                applyCheckChange(event.getElement(), event.getChecked());

                refreshCheckStates();
                selectMatchingPreset();
                updateCountLabel();
            }
        });

        // Set tool check states lazily when a group is expanded (avoids forcing expansion at startup)
        treeViewer.addTreeListener(new ITreeViewerListener()
        {
            @Override
            public void treeCollapsed(TreeExpansionEvent event)
            {
                // No action needed on collapse; check states are only set lazily on expand.
            }

            @Override
            public void treeExpanded(TreeExpansionEvent event)
            {
                if (event.getElement() instanceof ToolGroup group)
                {
                    updatingChecks = true;
                    try
                    {
                        for (String toolName : group.getToolNames())
                        {
                            treeViewer.setChecked(toolName, !disabledTools.contains(toolName));
                        }
                    }
                    finally
                    {
                        updatingChecks = false;
                    }
                }
            }
        });

        treeViewer.addSelectionChangedListener(new ISelectionChangedListener()
        {
            @Override
            public void selectionChanged(SelectionChangedEvent event)
            {
                IStructuredSelection selection = (IStructuredSelection) event.getSelection();
                showDetailsForElement(selection.getFirstElement());
            }
        });
    }

    /**
     * Applies a single checkbox change to {@link #disabledTools}: a checked element
     * enables (removes from disabled) and an unchecked element disables (adds to
     * disabled). A {@link ToolGroup} element fans the change out to all its tools;
     * a {@link String} element is a single tool. Any other element type is ignored.
     *
     * @param element the changed tree element (a {@link ToolGroup} or tool-name {@link String})
     * @param checked {@code true} when the element was checked (enabled)
     */
    private void applyCheckChange(Object element, boolean checked)
    {
        if (element instanceof ToolGroup group)
        {
            for (String toolName : group.getToolNames())
            {
                if (checked)
                {
                    disabledTools.remove(toolName);
                }
                else
                {
                    disabledTools.add(toolName);
                }
            }
        }
        else if (element instanceof String toolName)
        {
            if (checked)
            {
                disabledTools.remove(toolName);
            }
            else
            {
                disabledTools.add(toolName);
            }
        }
    }

    private void createCountLabel(Composite parent)
    {
        countLabel = new Label(parent, SWT.NONE);
        countLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        updateCountLabel();
    }

    private void createDetailPanel(Composite parent)
    {
        detailPanel = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        detailPanel.setLayout(layout);

        Label hint = new Label(detailPanel, SWT.WRAP);
        hint.setText("Select a tool or group to see its description."); //$NON-NLS-1$
        hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void showDetailsForElement(Object element)
    {
        savePendingSpinnerValues();
        currentSpinners.clear();
        selectedTool = (element instanceof String s) ? s : null;

        for (Control child : detailPanel.getChildren())
        {
            child.dispose();
        }

        if (element instanceof ToolGroup group)
        {
            showGroupDetails(group);
        }
        else if (element instanceof String toolName)
        {
            showToolDetails(toolName);
        }
        else
        {
            Label hint = new Label(detailPanel, SWT.WRAP);
            hint.setText("Select a tool or group to see its description."); //$NON-NLS-1$
            hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        }

        detailPanel.layout(true, true);
    }

    private void showGroupDetails(ToolGroup group)
    {
        Label nameLabel = new Label(detailPanel, SWT.NONE);
        nameLabel.setText(group.getDisplayName());
        nameLabel.setFont(JFaceResources.getBannerFont());
        nameLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Text descText = new Text(detailPanel, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        descText.setText(group.getDescription());
        descText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    private void showToolDetails(String toolName)
    {
        IMcpTool tool = McpToolRegistry.getInstance().getTool(toolName);

        Label nameLabel = new Label(detailPanel, SWT.NONE);
        nameLabel.setText(toolName);
        nameLabel.setFont(JFaceResources.getBannerFont());
        nameLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Text descText = new Text(detailPanel, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        descText.setText(tool != null ? tool.getDescription() : ""); //$NON-NLS-1$
        descText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createDestructiveConsentControl(toolName);

        List<ParameterDef> params = paramSettings.getParametersForTool(toolName);
        if (params.isEmpty())
        {
            return;
        }

        Group settingsGroup = new Group(detailPanel, SWT.NONE);
        settingsGroup.setText("Settings"); //$NON-NLS-1$
        GridLayout groupLayout = new GridLayout(2, false);
        groupLayout.marginWidth = 8;
        groupLayout.marginHeight = 8;
        settingsGroup.setLayout(groupLayout);
        GridData groupGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        groupGd.verticalIndent = 8;
        settingsGroup.setLayoutData(groupGd);

        for (ParameterDef param : params)
        {
            Label paramLabel = new Label(settingsGroup, SWT.NONE);
            paramLabel.setText(param.getDisplayName() + ":"); //$NON-NLS-1$
            paramLabel.setToolTipText(param.getDescription());

            Spinner spinner = new Spinner(settingsGroup, SWT.BORDER);
            spinner.setMinimum(param.getMinValue());
            spinner.setMaximum(param.getMaxValue());

            String key = ToolParameterSettings.buildKey(toolName, param.getName());
            Integer pending = pendingValues.get(key);
            spinner.setSelection(pending != null ? pending
                : paramSettings.getParameterValue(toolName, param.getName(), param.getDefaultValue()));
            spinner.setToolTipText(param.getDescription()
                + " (default: " + param.getDefaultValue() //$NON-NLS-1$
                + ", range: " + param.getMinValue() + "-" + param.getMaxValue() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            spinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
            spinner.setData("key", key); //$NON-NLS-1$
            currentSpinners.add(spinner);
        }

        Button restoreButton = new Button(settingsGroup, SWT.PUSH);
        restoreButton.setText("Restore Defaults"); //$NON-NLS-1$
        GridData btnGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        btnGd.horizontalSpan = 2;
        btnGd.verticalIndent = 5;
        restoreButton.setLayoutData(btnGd);
        restoreButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                restoreDefaultsForTool(toolName);
            }
        });
    }

    /**
     * Renders the per-tool "allow destructive without consent" checkbox, but only for a
     * {@link DestructiveConsentGate#GATED_TOOLS} tool. The checkbox is enabled only at the
     * {@code PER_TOOL} consent level (it is the per-tool allow-set for that level); at any
     * other level it is shown disabled with an explanatory tooltip. The selection is held
     * in {@link #destructiveAllowedTools} and committed on {@link #performOk()}.
     *
     * @param toolName the selected tool name
     */
    private void createDestructiveConsentControl(String toolName)
    {
        if (!DestructiveConsentGate.GATED_TOOLS.contains(toolName))
        {
            return;
        }

        boolean perToolLevel = currentConsentLevel() == ConsentSettingsService.Level.PER_TOOL;

        Button allowCheck = new Button(detailPanel, SWT.CHECK);
        allowCheck.setText("Allow this destructive operation without consent"); //$NON-NLS-1$
        allowCheck.setSelection(destructiveAllowedTools.contains(toolName));
        allowCheck.setEnabled(perToolLevel);
        allowCheck.setToolTipText(perToolLevel
            ? "When checked, this destructive tool runs without asking for consent." //$NON-NLS-1$
            : "Set 'Destructive operations' to 'Ask, except allowed tools' on the General tab, then re-select this tool to edit this."); //$NON-NLS-1$
        GridData checkGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        checkGd.verticalIndent = 8;
        allowCheck.setLayoutData(checkGd);
        allowCheck.addSelectionListener(destructiveAllowSelectionListener(allowCheck, toolName));
    }

    /**
     * Resolves the consent level that governs whether the per-tool allow checkbox is editable.
     * Prefers the PENDING selection on the sibling {@link GeneralTab} (so a level change made on
     * that tab takes effect without pressing Apply first); falls back to the persisted store level
     * when no General tab is linked (e.g. a headless/standalone construction).
     */
    private ConsentSettingsService.Level currentConsentLevel()
    {
        if (generalTab != null)
        {
            return generalTab.getPendingConsentLevel();
        }
        return ConsentSettingsService.getInstance().getLevel();
    }

    private SelectionAdapter destructiveAllowSelectionListener(Button allowCheck, String toolName)
    {
        return new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                if (allowCheck.getSelection())
                {
                    destructiveAllowedTools.add(toolName);
                }
                else
                {
                    destructiveAllowedTools.remove(toolName);
                }
            }
        };
    }

    private void refreshCheckStates()
    {
        updatingChecks = true;
        try
        {
            for (ToolGroup group : ToolGroup.values())
            {
                boolean allEnabled = true;
                boolean anyEnabled = false;

                for (String toolName : group.getToolNames())
                {
                    boolean enabled = !disabledTools.contains(toolName);
                    // Only update tool widget if group is already expanded (avoids forcing expansion)
                    if (treeViewer.getExpandedState(group))
                    {
                        treeViewer.setChecked(toolName, enabled);
                    }
                    if (enabled)
                    {
                        anyEnabled = true;
                    }
                    else
                    {
                        allEnabled = false;
                    }
                }

                treeViewer.setChecked(group, allEnabled || anyEnabled);
                treeViewer.setGrayed(group, anyEnabled && !allEnabled);
            }
        }
        finally
        {
            updatingChecks = false;
        }
    }

    private void selectMatchingPreset()
    {
        ToolPreset matched = ToolPreset.matchPreset(disabledTools);
        ToolPreset[] presets = ToolPreset.values();
        for (int i = 0; i < presets.length; i++)
        {
            if (presets[i] == matched)
            {
                presetCombo.select(i);
                break;
            }
        }
    }

    private void updateCountLabel()
    {
        int total = 0;
        int enabled = 0;
        for (ToolGroup group : ToolGroup.values())
        {
            for (String toolName : group.getToolNames())
            {
                total++;
                if (!disabledTools.contains(toolName))
                {
                    enabled++;
                }
            }
        }
        countLabel.setText(enabled + " of " + total + " tools enabled"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void savePendingSpinnerValues()
    {
        for (Spinner spinner : currentSpinners)
        {
            if (!spinner.isDisposed())
            {
                String key = (String) spinner.getData("key"); //$NON-NLS-1$
                if (key != null)
                {
                    pendingValues.put(key, spinner.getSelection());
                }
            }
        }
    }

    private void loadAllValues()
    {
        for (Map.Entry<String, List<ParameterDef>> entry : paramSettings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                String key = ToolParameterSettings.buildKey(entry.getKey(), param.getName());
                pendingValues.put(key, paramSettings.getParameterValue(
                    entry.getKey(), param.getName(), param.getDefaultValue()));
            }
        }
    }

    private void restoreDefaultsForTool(String toolName)
    {
        List<ParameterDef> params = paramSettings.getParametersForTool(toolName);
        int spinnerIdx = 0;
        for (ParameterDef param : params)
        {
            String key = ToolParameterSettings.buildKey(toolName, param.getName());
            pendingValues.put(key, param.getDefaultValue());
            if (spinnerIdx < currentSpinners.size())
            {
                currentSpinners.get(spinnerIdx).setSelection(param.getDefaultValue());
            }
            spinnerIdx++;
        }
    }

    /**
     * Saves tool enablement state and parameter values to preferences.
     */
    public void performOk()
    {
        savePendingSpinnerValues();
        ToolSettingsService.getInstance().setDisabledTools(disabledTools);
        ConsentSettingsService.getInstance().setAllowedTools(destructiveAllowedTools);
        for (Map.Entry<String, Integer> entry : pendingValues.entrySet())
        {
            String key = entry.getKey();
            String[] parts = key.split("\\.", 3); //$NON-NLS-1$
            if (parts.length == 3)
            {
                paramSettings.setParameterValue(parts[1], parts[2], entry.getValue());
            }
        }
    }

    /**
     * Resets tool enablement and all parameter values to defaults.
     */
    public void performDefaults()
    {
        disabledTools.clear();
        destructiveAllowedTools.clear();
        refreshCheckStates();
        selectMatchingPreset();
        updateCountLabel();

        for (Map.Entry<String, List<ParameterDef>> entry : paramSettings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                String key = ToolParameterSettings.buildKey(entry.getKey(), param.getName());
                pendingValues.put(key, param.getDefaultValue());
            }
        }
        if (selectedTool != null)
        {
            currentSpinners.clear();
            showDetailsForElement(selectedTool);
        }
    }

    /**
     * Returns true if the disabled tools set has changed from the stored value.
     */
    public boolean hasChanges()
    {
        return !disabledTools.equals(ToolSettingsService.getInstance().getDisabledTools());
    }

    /**
     * Disposes all managed SWT images. Must be called when the tab is disposed.
     */
    public void dispose()
    {
        for (Image image : managedImages)
        {
            if (image != null && !image.isDisposed())
            {
                image.dispose();
            }
        }
        managedImages.clear();
    }

    // === Tree content provider ===

    private static class ToolTreeContentProvider implements ITreeContentProvider
    {
        @Override
        public Object[] getElements(Object inputElement)
        {
            if (inputElement instanceof ToolGroup[])
            {
                return (ToolGroup[]) inputElement;
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof ToolGroup group)
            {
                return group.getToolNames().toArray();
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element)
        {
            if (element instanceof String toolName)
            {
                return ToolGroup.getGroupForTool(toolName);
            }
            return null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return element instanceof ToolGroup;
        }
    }

    // === Tree label provider ===

    private static class ToolTreeLabelProvider extends LabelProvider
    {
        private Image groupImage;

        @Override
        public String getText(Object element)
        {
            if (element instanceof ToolGroup group)
            {
                return group.getDisplayName() + " (" + group.getToolNames().size() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (element instanceof String toolName)
            {
                return toolName;
            }
            return super.getText(element);
        }

        @Override
        public Image getImage(Object element)
        {
            if (element instanceof ToolGroup)
            {
                if (groupImage == null)
                {
                    ImageDescriptor desc = AbstractUIPlugin.imageDescriptorFromPlugin(
                        Activator.PLUGIN_ID, "icons/group.png"); //$NON-NLS-1$
                    if (desc != null)
                    {
                        groupImage = desc.createImage();
                    }
                }
                return groupImage;
            }
            return null;
        }

        @Override
        public void dispose()
        {
            if (groupImage != null)
            {
                groupImage.dispose();
                groupImage = null;
            }
            super.dispose();
        }

    }
}
