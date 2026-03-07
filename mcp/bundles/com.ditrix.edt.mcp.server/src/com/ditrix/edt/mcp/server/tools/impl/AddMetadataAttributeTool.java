/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to add a new attribute to a metadata object.
 * Creates the attribute with default properties via BM write transaction.
 */
public class AddMetadataAttributeTool implements IMcpTool
{
    public static final String NAME = "add_metadata_attribute"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a new attribute to a metadata object. " + //$NON-NLS-1$
               "Supports: Catalog, Document, ExchangePlan, ChartOfCharacteristicTypes, " + //$NON-NLS-1$
               "ChartOfAccounts, ChartOfCalculationTypes, BusinessProcess, Task, " + //$NON-NLS-1$
               "DataProcessor, Report, InformationRegister, AccumulationRegister, AccountingRegister. " + //$NON-NLS-1$
               "The attribute is created with default properties. " + //$NON-NLS-1$
               "Russian type names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("parentFqn", //$NON-NLS-1$
                "FQN of the parent object " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder'). " + //$NON-NLS-1$
                "Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Name for the new attribute (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String parentFqn = JsonUtils.extractStringArgument(params, "parentFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', parentFqn: 'Catalog.Products', attributeName: 'Weight'}").toJson(); //$NON-NLS-1$
        }
        if (parentFqn == null || parentFqn.isEmpty())
        {
            return ToolResult.error("parentFqn is required. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products', 'Document.SalesOrder'. " + //$NON-NLS-1$
                "Usage: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}").toJson(); //$NON-NLS-1$
        }
        if (attributeName == null || attributeName.isEmpty())
        {
            return ToolResult.error("attributeName is required. " + //$NON-NLS-1$
                "Usage: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}").toJson(); //$NON-NLS-1$
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(executeInternal(projectName, parentFqn, attributeName));
            }
            catch (Exception e)
            {
                Activator.logError("Error in add_metadata_attribute", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    private String executeInternal(String projectName, String parentFqn, String attributeName)
    {
        // Get project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Get configuration
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Get BM model
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Normalize and find the parent object
        parentFqn = MetadataTypeUtils.normalizeFqn(parentFqn);
        String[] parts = parentFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid FQN: " + parentFqn).toJson(); //$NON-NLS-1$
        }
        MdObject parentObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (parentObject == null)
        {
            return ToolResult.error("Parent object not found: " + parentFqn + ". " + //$NON-NLS-1$
                "Check the FQN format: 'Type.Name' (e.g. 'Catalog.Products', 'Document.SalesOrder'). " + //$NON-NLS-1$
                "Use get_metadata_objects tool to list available objects.").toJson(); //$NON-NLS-1$
        }

        // Check parent type supports attributes
        if (!supportsAttributes(parentObject))
        {
            return ToolResult.error("Object type '" + parentObject.eClass().getName() + //$NON-NLS-1$
                "' does not support attributes. Supported types: " + //$NON-NLS-1$
                "Catalog, Document, ExchangePlan, ChartOfCharacteristicTypes, ChartOfAccounts, " + //$NON-NLS-1$
                "ChartOfCalculationTypes, BusinessProcess, Task, DataProcessor, Report, " + //$NON-NLS-1$
                "InformationRegister, AccumulationRegister, AccountingRegister.").toJson(); //$NON-NLS-1$
        }

        // Get bmId of parent for BM task
        if (!(parentObject instanceof IBmObject))
        {
            return ToolResult.error("Parent object is not a BM object").toJson(); //$NON-NLS-1$
        }
        long parentBmId = ((IBmObject) parentObject).bmGetId();

        // Execute write task
        final String normalizedParentFqn = parentFqn;
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("AddMetadataAttribute") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject parent = (MdObject) tx.getObjectById(parentBmId);
                    if (parent == null)
                    {
                        throw new RuntimeException("Parent object not found in transaction"); //$NON-NLS-1$
                    }

                    // Check if attribute with this name already exists
                    if (hasAttribute(parent, attributeName))
                    {
                        throw new RuntimeException("Attribute already exists: " + attributeName); //$NON-NLS-1$
                    }

                    // Create and add attribute
                    MdObject newAttribute = createAttribute(parent);
                    if (newAttribute == null)
                    {
                        throw new RuntimeException(
                            "Cannot create attribute for: " + parent.eClass().getName()); //$NON-NLS-1$
                    }
                    newAttribute.setName(attributeName);
                    newAttribute.setUuid(UUID.randomUUID());

                    addAttribute(parent, newAttribute);
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding attribute", e); //$NON-NLS-1$
            String msg = e.getMessage();
            if (e.getCause() != null && e.getCause().getMessage() != null)
            {
                msg = e.getCause().getMessage();
            }
            return ToolResult.error("Failed to add attribute: " + msg).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("parentFqn", normalizedParentFqn) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("message", "Attribute '" + attributeName + "' added successfully to " + normalizedParentFqn) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    private boolean supportsAttributes(MdObject obj)
    {
        return obj instanceof Catalog
            || obj instanceof Document
            || obj instanceof ExchangePlan
            || obj instanceof ChartOfCharacteristicTypes
            || obj instanceof ChartOfAccounts
            || obj instanceof ChartOfCalculationTypes
            || obj instanceof BusinessProcess
            || obj instanceof Task
            || obj instanceof DataProcessor
            || obj instanceof Report
            || obj instanceof InformationRegister
            || obj instanceof AccumulationRegister
            || obj instanceof AccountingRegister;
    }

    @SuppressWarnings("unchecked")
    private boolean hasAttribute(MdObject parent, String name)
    {
        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                EList<? extends MdObject> attrs = (EList<? extends MdObject>) result;
                for (MdObject attr : attrs)
                {
                    if (name.equalsIgnoreCase(attr.getName()))
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Type may not have getAttributes
        }
        return false;
    }

    private MdObject createAttribute(MdObject parent)
    {
        MdClassFactory factory = MdClassFactory.eINSTANCE;

        if (parent instanceof Catalog)
        {
            return factory.createCatalogAttribute();
        }
        if (parent instanceof Document)
        {
            return factory.createDocumentAttribute();
        }
        if (parent instanceof ExchangePlan)
        {
            return factory.createExchangePlanAttribute();
        }
        if (parent instanceof ChartOfCharacteristicTypes)
        {
            return factory.createChartOfCharacteristicTypesAttribute();
        }
        if (parent instanceof ChartOfAccounts)
        {
            return factory.createChartOfAccountsAttribute();
        }
        if (parent instanceof ChartOfCalculationTypes)
        {
            return factory.createChartOfCalculationTypesAttribute();
        }
        if (parent instanceof BusinessProcess)
        {
            return factory.createBusinessProcessAttribute();
        }
        if (parent instanceof Task)
        {
            return factory.createTaskAttribute();
        }
        if (parent instanceof DataProcessor)
        {
            return factory.createDataProcessorAttribute();
        }
        if (parent instanceof Report)
        {
            return factory.createReportAttribute();
        }
        if (parent instanceof InformationRegister)
        {
            return factory.createInformationRegisterAttribute();
        }
        if (parent instanceof AccumulationRegister)
        {
            return factory.createAccumulationRegisterAttribute();
        }
        if (parent instanceof AccountingRegister)
        {
            return factory.createAccountingRegisterAttribute();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addAttribute(MdObject parent, MdObject attribute)
    {
        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = method.invoke(parent);
            if (result instanceof EList)
            {
                ((EList<MdObject>) result).add(attribute);
            }
            else
            {
                throw new RuntimeException("getAttributes() did not return EList"); //$NON-NLS-1$
            }
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to add attribute via reflection", e); //$NON-NLS-1$
        }
    }
}
