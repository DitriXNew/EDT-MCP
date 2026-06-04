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
        return "Add a new attribute to a metadata object (Catalog, Document, register, etc.) " + //$NON-NLS-1$
               "with default properties, persisted to disk. Use to extend an object's data model; " + //$NON-NLS-1$
               "the parentFqn TYPE token may be English or Russian. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('add_metadata_attribute')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("parentFqn", //$NON-NLS-1$
                "FQN of the parent object, e.g. 'Catalog.Products' (TYPE token may be en/ru; " + //$NON-NLS-1$
                "object part is the programmatic Name, not the synonym)", true) //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Name for the new attribute (required)", true) //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional display name; written for 'language' or the config default language") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the synonym, e.g. 'ru'/'en' (default: config default)") //$NON-NLS-1$
            .booleanProperty("expectedNotExists", //$NON-NLS-1$
                "Optional stale-intent guard (default false): assert no such attribute exists yet; " + //$NON-NLS-1$
                "if it does, fail with a re-read steer instead of the generic duplicate error") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the attribute was added", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("parentFqn", "Normalized FQN of the parent object") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("attributeName", "Programmatic name of the added attribute") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was saved to disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("synonym", "Display name written, when a synonym was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language code the synonym was written for") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# add_metadata_attribute\n\n" //$NON-NLS-1$
            + "Adds one attribute to an existing metadata object via a BM write transaction, then " //$NON-NLS-1$
            + "force-exports the parent object to its `.mdo` on disk so the change survives a " //$NON-NLS-1$
            + "refresh / clean_project / EDT restart. The attribute is created with default " //$NON-NLS-1$
            + "properties (type, length, etc.); tune those afterwards in EDT or via other tools.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "Use to extend an object's data model with a new attribute. To rename or change an " //$NON-NLS-1$
            + "existing attribute, read it first with get_metadata_details and edit it instead of " //$NON-NLS-1$
            + "adding a duplicate (a duplicate name is always rejected).\n\n" //$NON-NLS-1$
            + "## Supported parent types\n\n" //$NON-NLS-1$
            + "Catalog, Document, ExchangePlan, ChartOfCharacteristicTypes, ChartOfAccounts, " //$NON-NLS-1$
            + "ChartOfCalculationTypes, BusinessProcess, Task, DataProcessor, Report, " //$NON-NLS-1$
            + "InformationRegister, AccumulationRegister, AccountingRegister. An unsupported type " //$NON-NLS-1$
            + "(or one without an attribute collection) is rejected.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `parentFqn` (required) - FQN of the parent object as `Type.Name`. The TYPE token " //$NON-NLS-1$
            + "may be English or Russian (e.g. `Catalog.Products` or the Russian Catalog token " //$NON-NLS-1$
            // The escape below spells the Russian Catalog token (Spravochnik).
            + "`\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Products`); the object " //$NON-NLS-1$
            + "part is the programmatic Name, NOT the synonym / display name.\n" //$NON-NLS-1$
            + "- `attributeName` (required) - new attribute name. Must be a valid 1C identifier: " //$NON-NLS-1$
            + "start with a letter or `_`, then letters / digits / `_` only. Cyrillic letters are " //$NON-NLS-1$
            + "valid. Case-insensitive duplicate of an existing attribute is rejected.\n" //$NON-NLS-1$
            + "- `synonym` (optional) - localized display name. Written for `language`, or the " //$NON-NLS-1$
            + "configuration default language when `language` is omitted.\n" //$NON-NLS-1$
            + "- `language` (optional) - language CODE for the synonym (`ru`, `en`, ...). Only " //$NON-NLS-1$
            + "consulted when `synonym` is supplied. Defaults to the configuration's first / " //$NON-NLS-1$
            + "default language code.\n" //$NON-NLS-1$
            + "- `expectedNotExists` (optional, default false) - stale-intent guard, see below.\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en) notes\n\n" //$NON-NLS-1$
            + "The synonym EMap is keyed by the language CODE (`ru`/`en`), never the language " //$NON-NLS-1$
            + "name. If you pass `language`, pass the code. The parent object is resolved by its " //$NON-NLS-1$
            + "programmatic Name; only the TYPE token in `parentFqn` is dialect-aware.\n\n" //$NON-NLS-1$
            + "## expectedNotExists (stale-intent guard)\n\n" //$NON-NLS-1$
            + "Set `true` to assert that, per the snapshot you last read, the parent has NO " //$NON-NLS-1$
            + "attribute with this name. If one already exists, the add is rejected with a " //$NON-NLS-1$
            + "precondition error that steers you to re-read (get_metadata_details) and update the " //$NON-NLS-1$
            + "existing attribute, instead of returning the generic duplicate error. A duplicate is " //$NON-NLS-1$
            + "rejected regardless of this flag; the flag only sharpens the diagnostic for a stale " //$NON-NLS-1$
            + "snapshot. The authoritative duplicate check still runs inside the write " //$NON-NLS-1$
            + "transaction (TOCTOU guard).\n\n" //$NON-NLS-1$
            + "## Result\n\n" //$NON-NLS-1$
            + "JSON with `parentFqn`, `attributeName`, `persisted` (true once the parent `.mdo` was " //$NON-NLS-1$
            + "exported to disk), and a `message`. When a synonym was written, `synonym` and the " //$NON-NLS-1$
            + "resolved `language` code are echoed back so you can confirm the localized name " //$NON-NLS-1$
            + "without a second read.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "Minimal: `{projectName: 'MyProject', parentFqn: 'Catalog.Products', " //$NON-NLS-1$
            + "attributeName: 'Weight'}`\n\n" //$NON-NLS-1$
            + "With a localized synonym: `{projectName: 'MyProject', " //$NON-NLS-1$
            + "parentFqn: 'Document.SalesOrder', attributeName: 'Discount', synonym: 'Discount', " //$NON-NLS-1$
            + "language: 'en'}`\n\n" //$NON-NLS-1$
            + "Guarded add: `{projectName: 'MyProject', parentFqn: 'Catalog.Products', " //$NON-NLS-1$
            + "attributeName: 'Weight', expectedNotExists: true}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `parentFqn` not found -> error pointing to get_metadata_objects; check `Type.Name` " //$NON-NLS-1$
            + "and that you used the Name, not the synonym.\n" //$NON-NLS-1$
            + "- Attribute is created with DEFAULT type/length; adjust afterwards.\n" //$NON-NLS-1$
            + "- If `persisted` is false the in-memory model changed but the `.mdo` write did not " //$NON-NLS-1$
            + "complete - re-check before relying on it on disk."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String parentFqn = JsonUtils.extractStringArgument(params, "parentFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        boolean expectedNotExists = JsonUtils.extractBooleanArgument(params, "expectedNotExists", false); //$NON-NLS-1$

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

        return executeInternal(projectName, parentFqn, attributeName, synonym, language,
            expectedNotExists);
    }

    private String executeInternal(String projectName, String parentFqn, String attributeName,
        String synonym, String language, boolean expectedNotExists)
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

        // Stale-intent precondition: when the caller asserted expectedNotExists, an
        // attribute that is in fact already present means the snapshot it read is stale.
        // Reject early with a re-read steer; the in-transaction dup-check below remains the
        // authoritative TOCTOU guard with its own generic "already exists" message.
        if (expectedNotExists && hasAttribute(parentObject, attributeName))
        {
            return ToolResult.error("Precondition failed: you set expectedNotExists, but attribute '" //$NON-NLS-1$
                + attributeName + "' already exists on " + parentFqn + ". Your snapshot is stale — " //$NON-NLS-1$ //$NON-NLS-2$
                + "re-read with get_metadata_details, then update the existing attribute instead of " //$NON-NLS-1$
                + "adding a duplicate.").toJson(); //$NON-NLS-1$
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

        // Persist the mutated TOP object (the PARENT - the attribute is not a top
        // object) to its .mdo on disk. A bare BM write only updates the in-memory
        // model and enqueues the async export, so without this the new attribute is
        // lost on refresh / clean_project / EDT restart. Runs AFTER the write commit.
        boolean persisted = BmTransactions.forceExportToDisk(project, normalizedParentFqn);

        ToolResult result = ToolResult.success()
            .put("parentFqn", normalizedParentFqn) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
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
