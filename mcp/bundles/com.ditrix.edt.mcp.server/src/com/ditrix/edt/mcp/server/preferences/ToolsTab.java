/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/**
 * Tools management tab for MCP Server preferences.
 * Shows a tree of tool groups with checkboxes to enable/disable tools and groups.
 */
public class ToolsTab
{
    private final Composite composite;
    private CheckboxTreeViewer treeViewer;
    private Combo presetCombo;
    private Text descriptionText;
    private Label countLabel;

    /** Local copy of disabled tools for editing (committed on performOk) */
    private final Set<String> disabledTools;

    /** Flag to avoid recursion when updating check states (SWT is single-threaded) */
    private boolean updatingChecks = false;

    /** Track created images for disposal */
    private final List<org.eclipse.swt.graphics.Image> managedImages = new ArrayList<>();

    public ToolsTab(Composite parent)
    {
        // Load current state
        disabledTools = new HashSet<>(ToolSettingsService.getInstance().getDisabledTools());

        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        composite.setLayout(layout);

        createPresetBar();
        createToolTree();
        createDescriptionPanel();
        createCountLabel();
    }

    public Composite getControl()
    {
        return composite;
    }

    private void createPresetBar()
    {
        Composite bar = new Composite(composite, SWT.NONE);
        GridLayout barLayout = new GridLayout(5, false);
        barLayout.marginWidth = 0;
        barLayout.marginHeight = 0;
        bar.setLayout(barLayout);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Preset label
        Label presetLabel = new Label(bar, SWT.NONE);
        presetLabel.setText("Preset:"); //$NON-NLS-1$

        // Preset combo
        presetCombo = new Combo(bar, SWT.DROP_DOWN | SWT.READ_ONLY);
        for (ToolPreset preset : ToolPreset.values())
        {
            presetCombo.add(preset.getDisplayName());
        }
        presetCombo.setToolTipText("Select a preset configuration or customize manually"); //$NON-NLS-1$
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

        // Check All button
        Button checkAllButton = new Button(bar, SWT.PUSH);
        checkAllButton.setToolTipText("Enable all tools"); //$NON-NLS-1$
        ImageDescriptor checkAllIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/check_all.png"); //$NON-NLS-1$
        if (checkAllIcon != null)
        {
            org.eclipse.swt.graphics.Image img = checkAllIcon.createImage();
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
            org.eclipse.swt.graphics.Image img = uncheckAllIcon.createImage();
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

        // Spacer to push buttons right
        Label spacer = new Label(bar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createToolTree()
    {
        treeViewer = new CheckboxTreeViewer(composite, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        treeViewer.setContentProvider(new ToolTreeContentProvider());
        treeViewer.setLabelProvider(new ToolTreeLabelProvider());
        treeViewer.setInput(ToolGroup.values());

        // Expand all groups
        treeViewer.expandAll();

        // Set initial check states
        refreshCheckStates();

        // Handle check state changes
        treeViewer.addCheckStateListener(new ICheckStateListener()
        {
            @Override
            public void checkStateChanged(CheckStateChangedEvent event)
            {
                if (updatingChecks)
                {
                    return;
                }
                Object element = event.getElement();
                boolean checked = event.getChecked();

                if (element instanceof ToolGroup group)
                {
                    // Toggle all tools in the group
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

                refreshCheckStates();
                selectMatchingPreset();
                updateCountLabel();
            }
        });

        // Show description on selection
        treeViewer.addSelectionChangedListener(new ISelectionChangedListener()
        {
            @Override
            public void selectionChanged(SelectionChangedEvent event)
            {
                IStructuredSelection selection = (IStructuredSelection) event.getSelection();
                Object element = selection.getFirstElement();
                updateDescription(element);
            }
        });
    }

    private void createDescriptionPanel()
    {
        Label descLabel = new Label(composite, SWT.NONE);
        descLabel.setText("Description:"); //$NON-NLS-1$

        descriptionText = new Text(composite, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        GridData descGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        descGd.heightHint = 60;
        descriptionText.setLayoutData(descGd);
        descriptionText.setText("Select a tool or group to see its description."); //$NON-NLS-1$
    }

    private void createCountLabel()
    {
        countLabel = new Label(composite, SWT.NONE);
        countLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        updateCountLabel();
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
                    treeViewer.setChecked(toolName, enabled);
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
        int total = ToolGroup.getTotalToolCount();
        int enabled = total - disabledTools.size();
        countLabel.setText(enabled + " of " + total + " tools enabled"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void updateDescription(Object element)
    {
        if (element instanceof ToolGroup group)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(group.getDisplayName()).append("\n"); //$NON-NLS-1$
            sb.append(group.getDescription()).append("\n\n"); //$NON-NLS-1$
            sb.append("Tools (").append(group.getToolNames().size()).append("):"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String toolName : group.getToolNames())
            {
                sb.append("\n  - ").append(toolName); //$NON-NLS-1$
            }
            descriptionText.setText(sb.toString());
        }
        else if (element instanceof String toolName)
        {
            IMcpTool tool = McpToolRegistry.getInstance().getTool(toolName);
            if (tool != null)
            {
                StringBuilder sb = new StringBuilder();
                sb.append(tool.getName()).append("\n\n"); //$NON-NLS-1$
                sb.append(tool.getDescription());
                descriptionText.setText(sb.toString());
            }
            else
            {
                descriptionText.setText(toolName);
            }
        }
        else
        {
            descriptionText.setText("Select a tool or group to see its description."); //$NON-NLS-1$
        }
    }

    /**
     * Saves the tool enablement state to preferences.
     */
    public void performOk()
    {
        ToolSettingsService.getInstance().setDisabledTools(disabledTools);
    }

    /**
     * Resets to defaults (all tools enabled).
     */
    public void performDefaults()
    {
        disabledTools.clear();
        refreshCheckStates();
        selectMatchingPreset();
        updateCountLabel();
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
        for (org.eclipse.swt.graphics.Image image : managedImages)
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
                IMcpTool tool = McpToolRegistry.getInstance().getTool(toolName);
                if (tool != null)
                {
                    return toolName + " — " + truncate(tool.getDescription(), 80); //$NON-NLS-1$
                }
                return (String) element;
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

        private static String truncate(String text, int maxLen)
        {
            if (text == null)
            {
                return ""; //$NON-NLS-1$
            }
            if (text.length() <= maxLen)
            {
                return text;
            }
            return text.substring(0, maxLen - 3) + "..."; //$NON-NLS-1$
        }
    }
}
