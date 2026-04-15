/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;

import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings.ParameterDef;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/**
 * Tool Settings tab for MCP Server preferences.
 * Allows configuring per-tool parameter defaults (limits, depth, etc.).
 */
public class ToolSettingsTab
{
    private final Composite composite;
    private final ToolParameterSettings paramSettings = ToolParameterSettings.getInstance();

    private TreeViewer toolListViewer;
    private Composite paramPanel;
    private String selectedTool;

    /** Pending values: tool.param -> value */
    private final Map<String, Integer> pendingValues = new LinkedHashMap<>();

    /** Currently displayed spinners for the selected tool */
    private final List<Spinner> currentSpinners = new ArrayList<>();

    public ToolSettingsTab(Composite parent)
    {
        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        composite.setLayout(layout);

        createToolList();
        createParamPanel();

        // Load initial values
        loadAllValues();
    }

    public Composite getControl()
    {
        return composite;
    }

    private void createToolList()
    {
        Label listLabel = new Label(composite, SWT.NONE);
        listLabel.setText("Tools with configurable parameters:"); //$NON-NLS-1$
        GridData listLabelGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        listLabelGd.horizontalSpan = 2;
        listLabel.setLayoutData(listLabelGd);

        toolListViewer = new TreeViewer(composite, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        GridData listGd = new GridData(SWT.FILL, SWT.FILL, false, true);
        listGd.widthHint = 220;
        toolListViewer.getTree().setLayoutData(listGd);

        toolListViewer.setContentProvider(new ToolParamContentProvider());
        toolListViewer.setLabelProvider(new ToolParamLabelProvider());
        toolListViewer.setInput(buildGroupedInput());
        toolListViewer.expandAll();

        toolListViewer.addSelectionChangedListener(new ISelectionChangedListener()
        {
            @Override
            public void selectionChanged(SelectionChangedEvent event)
            {
                IStructuredSelection selection = (IStructuredSelection) event.getSelection();
                Object element = selection.getFirstElement();
                if (element instanceof String toolName)
                {
                    showParamsForTool(toolName);
                }
            }
        });
    }

    private void createParamPanel()
    {
        paramPanel = new Composite(composite, SWT.NONE);
        paramPanel.setLayout(new GridLayout(2, false));
        paramPanel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label hint = new Label(paramPanel, SWT.WRAP);
        hint.setText("Select a tool to configure its default parameters.\n" + //$NON-NLS-1$
            "These defaults are used when the AI client does not specify the parameter."); //$NON-NLS-1$
        GridData hintGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintGd.horizontalSpan = 2;
        hint.setLayoutData(hintGd);
    }

    private void showParamsForTool(String toolName)
    {
        // Save any pending spinner values before switching
        savePendingSpinnerValues();

        selectedTool = toolName;
        currentSpinners.clear();

        // Clear and rebuild param panel
        for (org.eclipse.swt.widgets.Control child : paramPanel.getChildren())
        {
            child.dispose();
        }

        List<ParameterDef> params = paramSettings.getParametersForTool(toolName);
        if (params.isEmpty())
        {
            Label noParams = new Label(paramPanel, SWT.NONE);
            noParams.setText("No configurable parameters for this tool."); //$NON-NLS-1$
            GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            gd.horizontalSpan = 2;
            noParams.setLayoutData(gd);
            paramPanel.layout(true, true);
            return;
        }

        // Tool name header
        IMcpTool tool = McpToolRegistry.getInstance().getTool(toolName);
        Label nameLabel = new Label(paramPanel, SWT.NONE);
        nameLabel.setText(toolName);
        nameLabel.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());
        GridData nameGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        nameGd.horizontalSpan = 2;
        nameLabel.setLayoutData(nameGd);

        if (tool != null)
        {
            Label descLabel = new Label(paramPanel, SWT.WRAP);
            descLabel.setText(tool.getDescription());
            GridData descGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            descGd.horizontalSpan = 2;
            descGd.widthHint = 300;
            descLabel.setLayoutData(descGd);
        }

        // Separator
        Label sep = new Label(paramPanel, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData sepGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sepGd.horizontalSpan = 2;
        sepGd.verticalIndent = 5;
        sep.setLayoutData(sepGd);

        // Parameter editors
        for (ParameterDef param : params)
        {
            Label paramLabel = new Label(paramPanel, SWT.NONE);
            paramLabel.setText(param.getDisplayName() + ":"); //$NON-NLS-1$
            paramLabel.setToolTipText(param.getDescription());

            Spinner spinner = new Spinner(paramPanel, SWT.BORDER);
            spinner.setMinimum(param.getMinValue());
            spinner.setMaximum(param.getMaxValue());

            String key = ToolParameterSettings.buildKey(toolName, param.getName());
            Integer pending = pendingValues.get(key);
            if (pending != null)
            {
                spinner.setSelection(pending);
            }
            else
            {
                spinner.setSelection(paramSettings.getParameterValue(
                    toolName, param.getName(), param.getDefaultValue()));
            }
            spinner.setToolTipText(param.getDescription()
                + " (default: " + param.getDefaultValue() //$NON-NLS-1$
                + ", range: " + param.getMinValue() + "-" + param.getMaxValue() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            spinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
            spinner.setData("key", key); //$NON-NLS-1$
            currentSpinners.add(spinner);
        }

        // Restore Defaults button
        Button restoreButton = new Button(paramPanel, SWT.PUSH);
        restoreButton.setText("Restore Defaults"); //$NON-NLS-1$
        GridData btnGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        btnGd.horizontalSpan = 2;
        btnGd.verticalIndent = 10;
        restoreButton.setLayoutData(btnGd);
        restoreButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                restoreDefaultsForTool(toolName);
            }
        });

        paramPanel.layout(true, true);
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
        // Pre-load all configurable values
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

    /**
     * Saves all pending parameter values to the preference store.
     */
    public void performOk()
    {
        savePendingSpinnerValues();
        for (Map.Entry<String, Integer> entry : pendingValues.entrySet())
        {
            String key = entry.getKey();
            // Parse tool name and param name from key: "tool.<toolName>.<paramName>"
            String[] parts = key.split("\\.", 3); //$NON-NLS-1$
            if (parts.length == 3)
            {
                paramSettings.setParameterValue(parts[1], parts[2], entry.getValue());
            }
        }
    }

    /**
     * Resets all parameters to defaults.
     */
    public void performDefaults()
    {
        pendingValues.clear();
        loadAllValues();
        // Reset pending to defaults
        for (Map.Entry<String, List<ParameterDef>> entry : paramSettings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                String key = ToolParameterSettings.buildKey(entry.getKey(), param.getName());
                pendingValues.put(key, param.getDefaultValue());
            }
        }
        // Refresh UI if a tool is selected
        if (selectedTool != null)
        {
            showParamsForTool(selectedTool);
        }
    }

    /**
     * Builds grouped input: ToolGroup nodes containing tool names that have parameters.
     */
    private Object[] buildGroupedInput()
    {
        List<String> configurableTools = paramSettings.getConfigurableToolNames();
        List<Object> result = new ArrayList<>();

        for (ToolGroup group : ToolGroup.values())
        {
            List<String> toolsInGroup = new ArrayList<>();
            for (String toolName : group.getToolNames())
            {
                if (configurableTools.contains(toolName))
                {
                    toolsInGroup.add(toolName);
                }
            }
            if (!toolsInGroup.isEmpty())
            {
                result.add(new GroupWithTools(group, toolsInGroup));
            }
        }
        return result.toArray();
    }

    /** Wrapper for groups that have configurable tools */
    private static class GroupWithTools
    {
        final ToolGroup group;
        final List<String> tools;

        GroupWithTools(ToolGroup group, List<String> tools)
        {
            this.group = group;
            this.tools = tools;
        }
    }

    private static class ToolParamContentProvider implements ITreeContentProvider
    {
        @Override
        public Object[] getElements(Object inputElement)
        {
            if (inputElement instanceof Object[])
            {
                return (Object[]) inputElement;
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof GroupWithTools gwt)
            {
                return gwt.tools.toArray();
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element)
        {
            return null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return element instanceof GroupWithTools;
        }
    }

    private static class ToolParamLabelProvider extends LabelProvider
    {
        @Override
        public String getText(Object element)
        {
            if (element instanceof GroupWithTools gwt)
            {
                return gwt.group.getDisplayName();
            }
            if (element instanceof String)
            {
                return (String) element;
            }
            return super.getText(element);
        }
    }
}
