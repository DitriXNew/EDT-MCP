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
        return "Create a new top-level metadata object (Catalog, Document, register, etc.) with " + //$NON-NLS-1$
               "EDT default content, the same as the 'New' wizard. Use when adding an object to a " + //$NON-NLS-1$
               "configuration; supported types: " + SUPPORTED_TYPES_LIST + " (Russian type names " + //$NON-NLS-1$ //$NON-NLS-2$
               "also accepted). Full parameters and examples: call get_tool_guide('create_metadata_object')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name.", true) //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Type to create: " + SUPPORTED_TYPES_QUOTED + " (Russian names also accepted).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", //$NON-NLS-1$
                "Name for the new object; must be a valid 1C identifier (e.g. 'Products').", true) //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional display name; written for the config default language unless 'language' is set.") //$NON-NLS-1$
            .stringProperty("comment", //$NON-NLS-1$
                "Optional comment for the new object.") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the synonym (e.g. 'ru', 'en'); defaults to config default language.") //$NON-NLS-1$
            .booleanProperty("expectedNotExists", //$NON-NLS-1$
                "Optional stale-intent guard (default false): assert the object does not yet exist " + //$NON-NLS-1$
                "for a sharper precondition error. A real duplicate is always rejected anyway.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Creates one new top-level metadata object using the EDT model object factory, so it " //$NON-NLS-1$
            + "gets a freshly generated UUID and the same default content as the EDT 'New' wizard. " //$NON-NLS-1$
            + "The object is attached as a BM top object and added to the matching Configuration " //$NON-NLS-1$
            + "collection, then force-exported so its own .mdo file and the Configuration.mdo are " //$NON-NLS-1$
            + "written to disk (not just held in memory).\n\n" //$NON-NLS-1$
            + "## When to use\n" //$NON-NLS-1$
            + "Use to add a brand-new object to a configuration. To extend an existing object use " //$NON-NLS-1$
            + "add_metadata_attribute; to rename use rename_metadata_object. This is a write tool — " //$NON-NLS-1$
            + "the mutation runs inside a BM write transaction.\n\n" //$NON-NLS-1$
            + "## Supported types\n" //$NON-NLS-1$
            + SUPPORTED_TYPES_LIST + ". Pass the English singular (e.g. 'Catalog') or the Russian " //$NON-NLS-1$
            + "type name; both resolve to the same canonical type. Any other type is rejected.\n\n" //$NON-NLS-1$
            + "## Parameters\n" //$NON-NLS-1$
            + "- projectName (required): EDT project name.\n" //$NON-NLS-1$
            + "- metadataType (required): one of the supported types above (English or Russian).\n" //$NON-NLS-1$
            + "- name (required): a valid 1C identifier — starts with a letter or underscore, then " //$NON-NLS-1$
            + "letters, digits or underscores only. This is the programmatic Name, not the synonym.\n" //$NON-NLS-1$
            + "- synonym (optional): the localized display name.\n" //$NON-NLS-1$
            + "- comment (optional): a free-text comment on the object.\n" //$NON-NLS-1$
            + "- language (optional): the language CODE for the synonym, e.g. 'en' or 'ru'.\n" //$NON-NLS-1$
            + "- expectedNotExists (optional, default false): stale-intent guard, see Gotchas.\n\n" //$NON-NLS-1$
            + "## Bilingual notes (ru/en)\n" //$NON-NLS-1$
            + "- The object's identity is its Name; only the metadataType TYPE token may be bilingual " //$NON-NLS-1$
            + "(Catalog vs the Russian equivalent). Two objects differing only by synonym are still " //$NON-NLS-1$
            + "the same Name and collide.\n" //$NON-NLS-1$
            + "- The synonym is stored keyed by the language CODE (e.g. 'en'/'ru'), never by the " //$NON-NLS-1$
            + "language display name. If you omit 'language', the configuration's default language " //$NON-NLS-1$
            + "code is used; on a multi-language configuration set 'language' explicitly to target " //$NON-NLS-1$
            + "the right entry.\n" //$NON-NLS-1$
            + "- 'language' is only consulted when a non-empty synonym is supplied.\n\n" //$NON-NLS-1$
            + "## Examples\n" //$NON-NLS-1$
            + "- Minimal: {projectName: 'MyProject', metadataType: 'Catalog', name: 'Products'}\n" //$NON-NLS-1$
            + "- With synonym: {projectName: 'MyProject', metadataType: 'Document', name: 'Invoice', " //$NON-NLS-1$
            + "synonym: 'Invoice', language: 'en'}\n" //$NON-NLS-1$
            + "- Russian type token + synonym in ru: {projectName: 'MyProject', metadataType: " //$NON-NLS-1$
            + "'\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A', name: 'Goods', synonym: " //$NON-NLS-1$
            + "'\u0422\u043E\u0432\u0430\u0440\u044B', language: 'ru'}\n\n" //$NON-NLS-1$
            + "## Result\n" //$NON-NLS-1$
            + "Returns JSON with fqn (e.g. 'Catalog.Products'), metadataType, name, persisted, and — " //$NON-NLS-1$
            + "when a synonym was written — the echoed synonym and resolved language code. After a " //$NON-NLS-1$
            + "create, run get_project_errors to verify, or revalidate_objects if needed.\n\n" //$NON-NLS-1$
            + "## Gotchas\n" //$NON-NLS-1$
            + "- Creating an object whose Name already exists is always rejected as a duplicate.\n" //$NON-NLS-1$
            + "- expectedNotExists is an opt-in optimistic-lock guard: set it true to assert that, " //$NON-NLS-1$
            + "per the state you last read, the object does NOT yet exist. If it actually does, the " //$NON-NLS-1$
            + "create is rejected with a precondition error that steers you to re-read with " //$NON-NLS-1$
            + "get_metadata_objects (then add to / rename the existing object) instead of the generic " //$NON-NLS-1$
            + "duplicate message. It only sharpens the diagnostic; it never lets a duplicate through.\n" //$NON-NLS-1$
            + "- persisted=false means the in-memory mutation committed but the forced disk export " //$NON-NLS-1$
            + "did not confirm — re-read and verify the .mdo before relying on it."; //$NON-NLS-1$
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
        boolean expectedNotExists = JsonUtils.extractBooleanArgument(params, "expectedNotExists", false); //$NON-NLS-1$

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

        return executeInternal(projectName, metadataType, name, synonym, comment, language,
            expectedNotExists);
    }

    private String executeInternal(String projectName, String metadataType, String name,
        String synonym, String comment, String language, boolean expectedNotExists)
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

        // Check duplicate. A real duplicate is always rejected; when the caller set
        // expectedNotExists (it asserted, from the state it last read, that this object
        // did NOT exist) the rejection is reframed as a stale-intent precondition with a
        // re-read steer, matching the optimistic-lock pattern of the write tools.
        if (MetadataTypeUtils.findObject(config, canonicalType, name) != null)
        {
            if (expectedNotExists)
            {
                return ToolResult.error("Precondition failed: you set expectedNotExists, but " //$NON-NLS-1$
                    + canonicalType + "." + name + " already exists. Your snapshot is stale — " //$NON-NLS-1$ //$NON-NLS-2$
                    + "re-read with get_metadata_objects, then add to / rename the existing object " //$NON-NLS-1$
                    + "instead of creating a duplicate.").toJson(); //$NON-NLS-1$
            }
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
        // The Configuration's own FQN - it is dirtied too (its child collection gains
        // the new object), so it must be force-exported alongside the new object.
        final String configFqn = ((IBmObject)config).bmGetFqn();
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

        // Persist BOTH the new top object AND the Configuration (whose child
        // collection changed) to disk. A bare BM write only mutates the in-memory model
        // and enqueues the async export; without forcing it the object's own .mdo is
        // never written (half-create) and the Configuration.mdo reference lags, leaving
        // the object orphaned on refresh / restart. Runs AFTER the write commit.
        java.util.List<String> dirtyFqns = new java.util.ArrayList<>();
        dirtyFqns.add(fqn);
        if (configFqn != null && !configFqn.isEmpty())
        {
            dirtyFqns.add(configFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(project, dirtyFqns);

        ToolResult result = ToolResult.success()
            .put("fqn", fqn) //$NON-NLS-1$
            .put("metadataType", canonicalType) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
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
