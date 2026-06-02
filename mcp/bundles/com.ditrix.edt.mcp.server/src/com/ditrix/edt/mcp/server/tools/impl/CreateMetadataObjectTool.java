/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;

/**
 * Tool to create a new top-level metadata object (Catalog, Document, etc.).
 * <p>
 * Uses the EDT standard object factory ({@link IModelObjectFactory}) to create
 * the object with the same default content as the "New" wizard, then registers
 * it as a BM top object via {@link IBmTransaction#attachTopObject} and adds it
 * to the corresponding Configuration collection. EDT persists the object into a
 * new {@code .mdo} file.
 */
public class CreateMetadataObjectTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "create_metadata_object"; //$NON-NLS-1$

    /** Canonical English singular type names supported for creation in this version. */
    private static final Set<String> SUPPORTED_TYPES = new LinkedHashSet<>(Arrays.asList(
        "Catalog", "Document", "InformationRegister", "AccumulationRegister", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "Enum", "CommonModule", "Report", "DataProcessor")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** Comma-separated list for prose/error messages: {@code "Catalog, Document, …"}. */
    private static final String SUPPORTED_TYPES_LIST = String.join(", ", SUPPORTED_TYPES); //$NON-NLS-1$

    /** Quoted, comma-separated list for the JSON schema hint: {@code "'Catalog', 'Document', …"}. */
    private static final String SUPPORTED_TYPES_QUOTED = "'" + String.join("', '", SUPPORTED_TYPES) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a new top-level metadata object with EDT default content. " + //$NON-NLS-1$
               "Supported types: " + SUPPORTED_TYPES_LIST + ". The object is created with a properly " + //$NON-NLS-1$ //$NON-NLS-2$
               "generated UUID and default properties (same as the EDT 'New' wizard). " + //$NON-NLS-1$
               "Optionally sets synonym and comment. Russian type names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Metadata type to create (required): " + SUPPORTED_TYPES_QUOTED + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Russian type names are also supported.", true) //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name for the new object (required). Must be a valid 1C identifier.", true) //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional synonym (display name). Set for the configuration default language " + //$NON-NLS-1$
                "unless 'language' is specified.") //$NON-NLS-1$
            .stringProperty("comment", //$NON-NLS-1$
                "Optional comment for the new object.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for the synonym (e.g. 'ru', 'en'). " + //$NON-NLS-1$
                "If not specified, uses the configuration default language.") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String comment = JsonUtils.extractStringArgument(params, "comment"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', metadataType: 'Catalog', name: 'Products'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "metadataType", //$NON-NLS-1$
            ". Supported: " + SUPPORTED_TYPES_LIST + "."); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "name", //$NON-NLS-1$
            ". Usage: {metadataType: 'Catalog', name: 'Products'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        if (!isValidIdentifier(name))
        {
            return ToolResult.error("Invalid object name '" + name + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "A name must start with a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, metadataType, name, synonym, comment, language);
    }

    private String executeInternal(String projectName, String metadataType, String name,
        String synonym, String comment, String language)
    {
        // Resolve and validate the metadata type
        String canonicalType = MetadataTypeUtils.toEnglishSingular(metadataType);
        if (canonicalType == null)
        {
            return ToolResult.error("Unknown metadata type: " + metadataType).toJson(); //$NON-NLS-1$
        }
        if (!SUPPORTED_TYPES.contains(canonicalType))
        {
            return ToolResult.error("Metadata type '" + canonicalType + "' is not supported for creation. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Supported: " + SUPPORTED_TYPES_LIST + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String refName = MetadataTypeUtils.getConfigReferenceName(canonicalType);
        if (refName == null)
        {
            return ToolResult.error("No configuration collection mapping for type: " + canonicalType).toJson(); //$NON-NLS-1$
        }

        // Get project and configuration
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        // Resolve the target collection reference and its element type
        EStructuralFeature feature = config.eClass().getEStructuralFeature(refName);
        if (feature == null || !(feature.getEType() instanceof EClass))
        {
            return ToolResult.error("Could not resolve configuration collection '" + refName + "'").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        final EClass eClass = (EClass)feature.getEType();

        // Check duplicate
        if (MetadataTypeUtils.findObject(config, canonicalType, name) != null)
        {
            return ToolResult.error("Object already exists: " + canonicalType + "." + name).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Get IV8Project and platform version
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (v8ProjectManager == null)
        {
            return ToolResult.error("IV8ProjectManager not available").toJson(); //$NON-NLS-1$
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            return ToolResult.error("Could not resolve V8 project for: " + projectName).toJson(); //$NON-NLS-1$
        }
        final Version version = v8Project.getVersion();

        // Get the model object factory
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        if (factory == null)
        {
            return ToolResult.error("IModelObjectFactory not available").toJson(); //$NON-NLS-1$
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

        // Resolve synonym language (only required when a synonym is supplied)
        final String synonymLanguage;
        if (synonym != null && !synonym.isEmpty())
        {
            synonymLanguage = resolveLanguage(config, language);
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

        // bmId of the configuration to re-fetch inside the transaction
        if (!(config instanceof IBmObject))
        {
            return ToolResult.error("Configuration is not a BM object").toJson(); //$NON-NLS-1$
        }
        final long configBmId = ((IBmObject)config).bmGetId();
        final String fqn = canonicalType + "." + name; //$NON-NLS-1$

        try
        {
            // Mutation MUST run inside a write transaction (CLAUDE.md don't #1);
            // BmTransactions.write makes that boundary explicit at the call site.
            BmTransactions.<Void>write(bmModel, "CreateMetadataObject", (tx, pm) -> //$NON-NLS-1$
            {
                Configuration cfg = (Configuration)tx.getObjectById(configBmId);
                if (cfg == null)
                {
                    throw new RuntimeException("Configuration not found in transaction"); //$NON-NLS-1$
                }

                MdObject newObject = (MdObject)factory.create(eClass, version);
                if (newObject == null)
                {
                    throw new RuntimeException("Factory returned null for type: " + eClass.getName()); //$NON-NLS-1$
                }

                newObject.setName(name);
                if (synonym != null && !synonym.isEmpty())
                {
                    newObject.getSynonym().put(synonymLanguage, synonym);
                }
                if (comment != null && !comment.isEmpty())
                {
                    newObject.setComment(comment);
                }

                // Register as a BM top object so EDT persists it into its own .mdo file
                tx.attachTopObject((IBmObject)newObject, fqn);

                // Add to the configuration collection
                addToConfigurationCollection(cfg, refName, newObject);

                factory.fillDefaultReferences(newObject);
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating metadata object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create object: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put("fqn", fqn) //$NON-NLS-1$
            .put("metadataType", canonicalType) //$NON-NLS-1$
            .put("name", name); //$NON-NLS-1$
        // Echo back the synonym actually written, so callers can confirm the
        // localized name without a second get. synonymLanguage is the resolved
        // language CODE (see MetadataLanguageUtils); both are non-null together.
        if (synonymLanguage != null)
        {
            result.put("synonym", synonym) //$NON-NLS-1$
                .put("language", synonymLanguage); //$NON-NLS-1$
        }
        return result
            .put("message", "Object '" + fqn + "' created successfully. " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Run get_project_errors to verify, or revalidate_objects if needed.") //$NON-NLS-1$
            .toJson();
    }

    private static String resolveLanguage(Configuration config, String language)
    {
        // Delegates to the shared resolver so reads and writes agree on the same
        // language CODE key (see MetadataLanguageUtils).
        return MetadataLanguageUtils.resolveLanguageCode(config, language);
    }

    /**
     * Adds the freshly created object to the Configuration's collection for its
     * metadata type. Kept as a separate method so the unchecked cast to the typed
     * {@link EList} (the EMF feature is reflectively typed via {@code eGet}) lives
     * in one {@code @SuppressWarnings} place, leaving the write lambda clean.
     *
     * @param cfg the configuration re-fetched inside the transaction
     * @param refName the structural feature name for the type collection
     * @param newObject the object to add
     */
    @SuppressWarnings("unchecked")
    private static void addToConfigurationCollection(Configuration cfg, String refName, MdObject newObject)
    {
        Object collection = cfg.eGet(cfg.eClass().getEStructuralFeature(refName));
        if (!(collection instanceof EList))
        {
            throw new RuntimeException("Configuration feature '" + refName //$NON-NLS-1$
                + "' is not a list"); //$NON-NLS-1$
        }
        ((EList<MdObject>)collection).add(newObject);
    }

    private static boolean isValidIdentifier(String name)
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
}
