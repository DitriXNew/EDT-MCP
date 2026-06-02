/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
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
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;

/**
 * Tool to add a new attribute to a metadata object.
 * Creates the attribute with default properties via BM write transaction.
 */
public class AddMetadataAttributeTool extends AbstractMetadataWriteTool
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
                "The metadata TYPE token may be English or Russian; the object name is the " + //$NON-NLS-1$
                "programmatic Name (not the synonym / display name).", true) //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Name for the new attribute (required)", true) //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional synonym (display name) for the attribute. Set for the configuration " + //$NON-NLS-1$
                "default language unless 'language' is specified.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for the synonym (e.g. 'ru', 'en'). " + //$NON-NLS-1$
                "If not specified, uses the configuration default language.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String parentFqn = JsonUtils.extractStringArgument(params, "parentFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', parentFqn: 'Catalog.Products', attributeName: 'Weight'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "parentFqn", //$NON-NLS-1$
            ". Examples: 'Catalog.Products', 'Document.SalesOrder'. " //$NON-NLS-1$
            + "Usage: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "attributeName", //$NON-NLS-1$
            ". Usage: {parentFqn: 'Catalog.Products', attributeName: 'Weight'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        if (!isValidIdentifier(attributeName))
        {
            return ToolResult.error("Invalid attribute name '" + attributeName + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "A name must start with a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, parentFqn, attributeName, synonym, language);
    }

    private String executeInternal(String projectName, String parentFqn, String attributeName,
        String synonym, String language)
    {
        // Get project and configuration
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

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

        // Resolve synonym language (only required when a synonym is supplied).
        // Keyed by the language CODE, never the language NAME (see MetadataLanguageUtils).
        final String synonymLanguage;
        if (synonym != null && !synonym.isEmpty())
        {
            synonymLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
            if (synonymLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the synonym " + //$NON-NLS-1$
                    "in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            synonymLanguage = null;
        }

        // Execute write task
        final String normalizedParentFqn = parentFqn;
        try
        {
            BmTransactions.<Void>write(bmModel, "AddMetadataAttribute", (tx, pm) -> //$NON-NLS-1$
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
                if (synonym != null && !synonym.isEmpty())
                {
                    newAttribute.getSynonym().put(synonymLanguage, synonym);
                }
                newAttribute.setUuid(UUID.randomUUID());

                addAttribute(parent, newAttribute);
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding attribute", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add attribute: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put("parentFqn", normalizedParentFqn) //$NON-NLS-1$
            .put("attributeName", attributeName); //$NON-NLS-1$
        // Echo back the synonym actually written so callers can confirm the localized
        // name without a second get. synonymLanguage is the resolved language CODE
        // (see MetadataLanguageUtils); both are non-null together.
        if (synonymLanguage != null)
        {
            result.put("synonym", synonym) //$NON-NLS-1$
                .put("language", synonymLanguage); //$NON-NLS-1$
        }
        return result
            .put("message", "Attribute '" + attributeName + "' added successfully to " + normalizedParentFqn) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    /**
     * Checks that a name is a valid 1C identifier: starts with a letter or
     * underscore and contains only letters, digits and underscores.
     * <p>
     * Mirrors {@code CreateMetadataObjectTool.isValidIdentifier}; replicated here
     * because that method is private to its tool and not yet extracted into a
     * shared util. The two must stay in sync until the shared helper exists.
     *
     * @param name the candidate attribute name
     * @return {@code true} if the name is a valid identifier
     */
    static boolean isValidIdentifier(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_')
            {
                return false;
            }
        }
        return true;
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
