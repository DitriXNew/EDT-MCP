/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver.CreateTarget;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Creates a metadata node addressed by a 1C full-name FQN: a top-level object
 * ({@code Catalog.Products}) or a subordinate member
 * ({@code Catalog.Products.Attribute.Weight}, {@code InformationRegister.Prices.Dimension.Product},
 * {@code Enum.Colors.EnumValue.Red}). Unifies the former {@code create_metadata_object} (top-level)
 * and {@code add_metadata_attribute} (member) tools behind one FQN-addressed surface.
 */
public class CreateMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "create_metadata"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a metadata node addressed by a 1C full-name FQN: a top-level object " //$NON-NLS-1$
            + "(Catalog.Products) or a subordinate member (Catalog.Products.Attribute.Weight, " //$NON-NLS-1$
            + "InformationRegister.Prices.Dimension.Product, Enum.Colors.EnumValue.Red). The kind " //$NON-NLS-1$
            + "is inferred from the FQN; type and kind tokens may be English or Russian. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('create_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to create (required). Top object: 'Type.Name' " //$NON-NLS-1$
                + "(e.g. 'Catalog.Products'). Member: 'Type.Name.Kind.Name' " //$NON-NLS-1$
                + "(e.g. 'Catalog.Products.Attribute.Weight'). The trailing Name is the new node's " //$NON-NLS-1$
                + "programmatic Name; type / kind tokens may be English or Russian.", true) //$NON-NLS-1$
            .objectArrayProperty("properties", //$NON-NLS-1$
                "Optional properties to apply at creation, as [{name, value, language?}]. This " //$NON-NLS-1$
                + "version applies 'synonym' (with optional 'language' code) and 'comment'; other " //$NON-NLS-1$
                + "property names are rejected (set them via modify_metadata).") //$NON-NLS-1$
            .booleanProperty("expectedNotExists", //$NON-NLS-1$
                "Optional stale-intent guard (default false): assert the node does not yet exist for " //$NON-NLS-1$
                + "a sharper precondition error. A real duplicate is always rejected anyway.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the node was created", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "'created' on success") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("fqn", "Normalized full-name FQN of the created node") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("kind", "EClass of the created node (e.g. 'Catalog', 'CatalogAttribute')") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Programmatic name of the created node") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was exported to disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("synonym", "Display name written, when a synonym property was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language code the synonym was written for") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, "projectName", "fqn"); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean expectedNotExists = JsonUtils.extractBooleanArgument(params, "expectedNotExists", false); //$NON-NLS-1$
        List<JsonObject> properties = JsonUtils.extractObjectArray(params, "properties"); //$NON-NLS-1$

        // A FQN that addresses a FORM's content (e.g. Catalog.X.Form.F.Command.C) is handled by a
        // dedicated branch: form members live on the editable Form content model (a cross-model hop),
        // not the mdclass tree, and take 'title'/'parent' properties rather than synonym/comment.
        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        FormElementWriter.FormMemberRef formRef = FormElementWriter.parse(normFqn);
        if (formRef != null)
        {
            if (FormElementWriter.isHandlerToken(formRef.kindToken))
            {
                return createFormHandler(projectName, normFqn, formRef, properties);
            }
            return createFormMember(projectName, normFqn, formRef, properties);
        }

        // Parse the supported properties (synonym/comment); reject anything else early.
        Props props = new Props();
        String propErr = parseProperties(properties, props);
        if (propErr != null)
        {
            return propErr;
        }

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        CreateTarget target = MetadataNodeResolver.resolveForCreate(config, normFqn);
        if (target == null)
        {
            return ToolResult.error("Cannot resolve a create target for FQN '" + fqn + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use 'Type.Name' for a top object or 'Type.Name.Kind.Name' for a member " //$NON-NLS-1$
                + "(Kind = Attribute/TabularSection/Dimension/Resource/EnumValue/Command or a " //$NON-NLS-1$
                + "type-specific child such as AccountingFlag/AddressingAttribute/Column; see " //$NON-NLS-1$
                + "get_tool_guide('create_metadata') for the full list). The parent must already " //$NON-NLS-1$
                + "exist; use get_metadata_objects to check.").toJson(); //$NON-NLS-1$
        }
        if (!isValidIdentifier(target.childName))
        {
            return ToolResult.error("Invalid name '" + target.childName + "'. A name must start with " //$NON-NLS-1$ //$NON-NLS-2$
                + "a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        // Resolve the synonym language now (needs the configuration); only when a synonym was given.
        final String synonymLanguage;
        if (props.synonym != null && !props.synonym.isEmpty())
        {
            synonymLanguage = MetadataLanguageUtils.resolveLanguageCode(config, props.language);
            if (synonymLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the synonym in this " //$NON-NLS-1$
                    + "configuration. Specify a 'language' code (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            synonymLanguage = null;
        }

        // Uniform duplicate / stale-intent check for both top-level and members.
        if (MetadataNodeResolver.resolveExisting(config, normFqn) != null)
        {
            if (expectedNotExists)
            {
                return ToolResult.error("Precondition failed: you set expectedNotExists, but " + normFqn //$NON-NLS-1$
                    + " already exists. Your snapshot is stale - re-read with get_metadata_objects, " //$NON-NLS-1$
                    + "then update the existing node instead of creating a duplicate.").toJson(); //$NON-NLS-1$
            }
            return ToolResult.error("Node already exists: " + normFqn).toJson(); //$NON-NLS-1$
        }

        if (target.topLevel)
        {
            return createTopLevel(project, config, projectName, target, normFqn, props, synonymLanguage);
        }
        return createMember(project, projectName, target, normFqn, props, synonymLanguage);
    }

    // ---- top-level creation (mirrors the former create_metadata_object) -------------------------

    private String createTopLevel(IProject project, Configuration config, String projectName,
        CreateTarget target, String normFqn, Props props, String synonymLanguage)
    {
        // Any configuration top-level type resolved by MetadataTypeUtils is attempted: the EDT
        // model-object factory produces the EDT "New"-wizard default content. A type the factory
        // cannot instantiate fails gracefully below (clean error, no crash) rather than via a
        // hand-maintained allow-list.
        EStructuralFeature collection = config.eClass().getEStructuralFeature(target.configFeatureName);
        if (collection == null || !(collection.getEType() instanceof EClass))
        {
            return ToolResult.error("Could not resolve configuration collection '" //$NON-NLS-1$
                + target.configFeatureName + "'").toJson(); //$NON-NLS-1$
        }
        final EClass eClass = (EClass)collection.getEType();

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (v8ProjectManager == null || factory == null || bmModelManager == null)
        {
            return ToolResult.error("Required EDT services not available").toJson(); //$NON-NLS-1$
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            return ToolResult.error("Could not resolve V8 project for: " + projectName).toJson(); //$NON-NLS-1$
        }
        final Version version = v8Project.getVersion();
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }
        if (!(config instanceof IBmObject))
        {
            return ToolResult.error("Configuration is not a BM object").toJson(); //$NON-NLS-1$
        }
        final long configBmId = ((IBmObject)config).bmGetId();
        final String configFqn = ((IBmObject)config).bmGetFqn();
        final String name = target.childName;
        final String configFeatureName = target.configFeatureName;

        final EClass createdKind;
        try
        {
            createdKind = BmTransactions.<EClass>write(bmModel, "CreateMetadataObject", (tx, pm) -> //$NON-NLS-1$
            {
                Configuration cfg = (Configuration)tx.getObjectById(configBmId);
                if (cfg == null)
                {
                    throw new RuntimeException("Configuration not found in transaction"); //$NON-NLS-1$
                }
                MdObject newObject = (MdObject)factory.create(eClass, version);
                if (newObject == null)
                {
                    throw new RuntimeException("the EDT factory cannot create a '" + eClass.getName() //$NON-NLS-1$
                        + "' object"); //$NON-NLS-1$
                }
                newObject.setName(name);
                applyScalarProps(newObject, props, synonymLanguage);
                tx.attachTopObject((IBmObject)newObject, normFqn);
                addToCollection(cfg, configFeatureName, newObject);
                factory.fillDefaultReferences(newObject);
                return newObject.eClass();
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating metadata object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create object: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        java.util.List<String> dirty = new java.util.ArrayList<>();
        dirty.add(normFqn);
        if (configFqn != null && !configFqn.isEmpty())
        {
            dirty.add(configFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(project, dirty);
        return success(normFqn, createdKind, name, persisted, props, synonymLanguage);
    }

    // ---- member creation (mirrors the former add_metadata_attribute, generalized) ---------------

    private String createMember(IProject project, String projectName, CreateTarget target,
        String normFqn, Props props, String synonymLanguage)
    {
        // Members are created inside a write transaction. Only TOP objects are re-fetchable by
        // bmId, so we re-fetch the TOP object and re-navigate to the leaf's owner BY NAME inside the
        // transaction - this is what lets a member of a NESTED object (e.g. a tabular-section
        // attribute) be created, not just a direct member of the top object.
        if (!(target.topObject instanceof IBmObject))
        {
            return ToolResult.error("Top object is not a BM object").toJson(); //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (bmModelManager == null || factory == null || v8ProjectManager == null)
        {
            return ToolResult.error("Required EDT services not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            return ToolResult.error("Could not resolve V8 project for: " + projectName).toJson(); //$NON-NLS-1$
        }
        final Version version = v8Project.getVersion();

        final long topBmId = ((IBmObject)target.topObject).bmGetId();
        final String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        final EStructuralFeature feature = target.feature;
        final EClass elementType = target.elementType;
        final String name = target.childName;
        // Template / Recalculation / Form need the model-object factory (not a bare EcoreUtil.create)
        // so the type's default content is wired (e.g. a Recalculation's produced types). They are
        // still contained members, serialized inline in the owner's .mdo. See isFactoryInitializedChild.
        final boolean factoryInitialized = isFactoryInitializedChild(elementType);
        // The top object that owns the member's .mdo file (members live inside the top object's file).
        final String topFqn = topFqn(normFqn);

        final EClass createdKind;
        try
        {
            createdKind = BmTransactions.<EClass>write(bmModel, "CreateMetadataMember", (tx, pm) -> //$NON-NLS-1$
            {
                EObject top = (EObject)tx.getObjectById(topBmId);
                if (top == null)
                {
                    throw new RuntimeException("Owner object not found in transaction"); //$NON-NLS-1$
                }
                EObject owner = MetadataNodeResolver.resolveOwnerInTx(top, parts);
                if (owner == null)
                {
                    throw new RuntimeException("Could not re-navigate to the owner inside the transaction"); //$NON-NLS-1$
                }
                if (childByName(owner, feature, name) != null)
                {
                    throw new RuntimeException("Member already exists: " + name); //$NON-NLS-1$
                }
                MdObject child;
                if (factoryInitialized)
                {
                    // The parent-aware factory wires the type's default content (produced types,
                    // form/template type); fall back to a bare create only if the factory declines.
                    child = (MdObject)factory.create(elementType, owner, version);
                    if (child == null)
                    {
                        child = (MdObject)EcoreUtil.create(elementType);
                    }
                    child.setName(name);
                    if (child.getUuid() == null)
                    {
                        child.setUuid(UUID.randomUUID());
                    }
                    // The factory does not default a template's type; set the platform default.
                    if (child instanceof BasicTemplate && ((BasicTemplate)child).getTemplateType() == null)
                    {
                        ((BasicTemplate)child).setTemplateType(TemplateType.SPREADSHEET_DOCUMENT);
                    }
                    applyScalarProps(child, props, synonymLanguage);
                    addToFeature(owner, feature, child);
                    factory.fillDefaultReferences(child);
                }
                else
                {
                    child = (MdObject)EcoreUtil.create(elementType);
                    child.setName(name);
                    child.setUuid(UUID.randomUUID());
                    applyScalarProps(child, props, synonymLanguage);
                    addToFeature(owner, feature, child);
                }
                return child.eClass();
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating metadata member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = BmTransactions.forceExportToDisk(project, topFqn);
        return success(normFqn, createdKind, name, persisted, props, synonymLanguage);
    }

    // ---- form-content member creation (the cross-model hop into the editable Form) ---------------

    /**
     * Creates a member of a form's CONTENT model (a form attribute / command / visual item) addressed
     * by a form FQN. Forms are a separate top object reached from the {@code BasicForm} mdo via
     * {@code getForm()}; the mutation runs on the re-fetched content form inside a write transaction
     * and the content form's OWN FQN is force-exported (it serializes to {@code Form.form}).
     */
    private String createFormMember(String projectName, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        FormElementWriter.Kind kind = FormElementWriter.kindForToken(ref.kindToken);
        if (kind == null)
        {
            return ToolResult.error("Unsupported form element kind '" + ref.kindToken + "' in '" //$NON-NLS-1$ //$NON-NLS-2$
                + normFqn + "'. Supported form kinds: Attribute, Command, Group, Decoration, Field, " //$NON-NLS-1$
                + "Button (and Handler for events).").toJson(); //$NON-NLS-1$
        }
        if (!isValidIdentifier(ref.name))
        {
            return ToolResult.error("Invalid name '" + ref.name + "'. A name must start with a letter " //$NON-NLS-1$ //$NON-NLS-2$
                + "or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        // Form-member properties: title (+ language), parent (nest a visual item), the binding
        // target for a Field (dataPath/attribute -> the form attribute) or a Button (command), and
        // a Group's explicit type (Popup/Pages/Page/CommandBar/ButtonGroup/ColumnGroup/UsualGroup).
        String titleVal = null;
        String titleLang = null;
        String parentName = null;
        String bindTarget = null;
        for (JsonObject prop : properties)
        {
            String pName = asString(prop.get("name")); //$NON-NLS-1$
            if (pName == null || pName.isEmpty())
            {
                return ToolResult.error("Each entry in 'properties' needs a non-empty 'name'.").toJson(); //$NON-NLS-1$
            }
            switch (pName.toLowerCase())
            {
                case "title": //$NON-NLS-1$
                    titleVal = asString(prop.get("value")); //$NON-NLS-1$
                    titleLang = asString(prop.get("language")); //$NON-NLS-1$
                    break;
                case "parent": //$NON-NLS-1$
                    parentName = asString(prop.get("value")); //$NON-NLS-1$
                    break;
                case "type": //$NON-NLS-1$
                    if (kind != FormElementWriter.Kind.GROUP)
                    {
                        return ToolResult.error("Property 'type' is supported at creation only for " //$NON-NLS-1$
                            + "a form Group (the group kind, e.g. Popup or Pages). Set other " //$NON-NLS-1$
                            + "elements' types via modify_metadata.").toJson(); //$NON-NLS-1$
                    }
                    bindTarget = asString(prop.get("value")); //$NON-NLS-1$
                    break;
                case "datapath": //$NON-NLS-1$
                case "attribute": //$NON-NLS-1$
                case "command": //$NON-NLS-1$
                    bindTarget = asString(prop.get("value")); //$NON-NLS-1$
                    break;
                default:
                    return ToolResult.error("Property '" + pName + "' is not supported for a form " //$NON-NLS-1$ //$NON-NLS-2$
                        + "element. This version applies: title (with optional language), parent " //$NON-NLS-1$
                        + "(nest a visual item), dataPath/attribute (a Field's bound attribute), " //$NON-NLS-1$
                        + "command (a Button's bound command), type (a Group's kind). Set other " //$NON-NLS-1$
                        + "properties via modify_metadata.").toJson(); //$NON-NLS-1$
            }
        }

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        MdObject mdForm = FormStructureReader.resolveMdForm(config, ref.formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found for '" + normFqn + "'. Address a form as " //$NON-NLS-1$ //$NON-NLS-2$
                + "'Type.Object.Form.FormName' or 'CommonForm.FormName'; check with " //$NON-NLS-1$
                + "get_metadata_objects and get_metadata_details.").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form is not a BM object").toJson(); //$NON-NLS-1$
        }

        final String titleLanguage;
        if (titleVal != null && !titleVal.isEmpty())
        {
            titleLanguage = MetadataLanguageUtils.resolveLanguageCode(config, titleLang);
            if (titleLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the title in this " //$NON-NLS-1$
                    + "configuration. Specify a 'language' code (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            titleLanguage = null;
        }

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

        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final FormElementWriter.Kind fKind = kind;
        final String name = ref.name;
        final String parent = parentName;
        final String bind = bindTarget;
        final String titleText = titleVal;
        // The designer's auto-children (extended tooltip / context menu) get script-variant
        // localized name suffixes, like FormObjectDefaultNameProvider.
        final boolean russianAutoNames = config.getScriptVariant() == ScriptVariant.RUSSIAN;
        final String[] createdKind = new String[1];

        final String contentFormFqn;
        try
        {
            contentFormFqn = BmTransactions.<String>write(bmModel, "CreateFormMember", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txMdForm = (EObject)tx.getObjectById(mdFormBmId);
                if (txMdForm == null)
                {
                    throw new RuntimeException("Form object not found in transaction"); //$NON-NLS-1$
                }
                EObject formModel = FormElementWriter.getEditableForm(txMdForm);
                if (formModel == null)
                {
                    throw new RuntimeException("the form has no editable content model (it may be " //$NON-NLS-1$
                        + "empty, an ordinary/legacy form, or not yet built)"); //$NON-NLS-1$
                }
                String err = FormElementWriter.createMember(formModel, fKind, name, parent, bind,
                    titleLanguage, titleText, russianAutoNames, createdKind);
                if (err != null)
                {
                    throw new RuntimeException(err);
                }
                // The content Form is a separate top object serialized to Form.form - export ITS fqn.
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create form element: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = contentFormFqn != null && !contentFormFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, contentFormFqn);

        return ToolResult.success()
            .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("kind", createdKind[0] != null ? createdKind[0] : fKind.name()) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("persisted", persisted) //$NON-NLS-1$
            .put("message", "Created " + normFqn).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Binds an event handler to a form root or to a form ITEM (the leaf is the EVENT name; the BSL
     * procedure name comes from a {@code procedure} property, defaulting to the event name). For an
     * item-level FQN ({@code ...Form.F.Field.Item.Handler.Event}) the handler attaches to the named
     * item. An unknown event is rejected with the list of AVAILABLE events (the union of the element's
     * base type and its extInfo sub-type) localized to the configuration language.
     */
    private String createFormHandler(String projectName, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        String procName = null;
        for (JsonObject prop : properties)
        {
            String pName = asString(prop.get("name")); //$NON-NLS-1$
            if (pName == null || pName.isEmpty())
            {
                return ToolResult.error("Each entry in 'properties' needs a non-empty 'name'.").toJson(); //$NON-NLS-1$
            }
            switch (pName.toLowerCase())
            {
                case "procedure": //$NON-NLS-1$
                case "handler": //$NON-NLS-1$
                    procName = asString(prop.get("value")); //$NON-NLS-1$
                    break;
                default:
                    return ToolResult.error("Property '" + pName + "' is not supported for a form " //$NON-NLS-1$ //$NON-NLS-2$
                        + "handler. Use 'procedure' (the BSL handler procedure name; defaults to the " //$NON-NLS-1$
                        + "event name).").toJson(); //$NON-NLS-1$
            }
        }

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        MdObject mdForm = FormStructureReader.resolveMdForm(config, ref.formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found for '" + normFqn + "'. Address a form as " //$NON-NLS-1$ //$NON-NLS-2$
                + "'Type.Object.Form.FormName' or 'CommonForm.FormName'.").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form is not a BM object").toJson(); //$NON-NLS-1$
        }

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;
        if (version == null)
        {
            return ToolResult.error("Cannot resolve the platform version needed to validate the form " //$NON-NLS-1$
                + "event.").toJson(); //$NON-NLS-1$
        }
        final String langCode = MetadataLanguageUtils.resolveLanguageCode(config, null);

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

        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final String eventName = ref.name;
        final String fProc = procName;
        final boolean commandOwner =
            FormElementWriter.kindForToken(ref.itemKindToken) == FormElementWriter.Kind.COMMAND;
        final String[] createdKind = new String[1];

        final String contentFormFqn;
        try
        {
            contentFormFqn = BmTransactions.<String>write(bmModel, "CreateFormHandler", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txMdForm = (EObject)tx.getObjectById(mdFormBmId);
                if (txMdForm == null)
                {
                    throw new RuntimeException("Form object not found in transaction"); //$NON-NLS-1$
                }
                EObject formModel = FormElementWriter.getEditableForm(txMdForm);
                if (formModel == null)
                {
                    throw new RuntimeException("the form has no editable content model (it may be " //$NON-NLS-1$
                        + "empty, an ordinary/legacy form, or not yet built)"); //$NON-NLS-1$
                }
                // Form-level handlers attach to the form root; item-level handlers
                // (Type.Object.Form.F.Field.Item.Handler.Event) attach to the named item; a COMMAND
                // ref (Type.Object.Form.F.Command.C.Handler.Action) attaches to the form command.
                EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
                if (container == null)
                {
                    throw new RuntimeException(commandOwner
                        ? "Form command not found: " + ref.itemName //$NON-NLS-1$
                            + ". Create the command first, then add the handler." //$NON-NLS-1$
                        : "Form item not found: " + ref.itemName //$NON-NLS-1$
                            + ". Create the item first, then add the handler."); //$NON-NLS-1$
                }
                String err = FormElementWriter.createHandler(container, eventName, fProc, version,
                    langCode, createdKind);
                if (err != null)
                {
                    throw new RuntimeException(err);
                }
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating form handler", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create form handler: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = contentFormFqn != null && !contentFormFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, contentFormFqn);

        return ToolResult.success()
            .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("kind", createdKind[0] != null ? createdKind[0] : "EventHandler") //$NON-NLS-1$ //$NON-NLS-2$
            .put("name", eventName) //$NON-NLS-1$
            .put("persisted", persisted) //$NON-NLS-1$
            .put("message", "Created handler for event '" + eventName + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                + (ref.isItemLevel() ? ref.formPath + "." + ref.itemName : ref.formPath)) //$NON-NLS-1$
            .toJson();
    }

    /**
     * A child whose valid default content must be wired by the model-object factory (Form, Template,
     * Recalculation) rather than by a bare {@code EcoreUtil.create}: a Recalculation needs its
     * produced types, a Form its form type, a Template its template type. These are CONTAINED
     * objects - the platform serializes them inline in the owner's {@code .mdo}, like other members
     * (empirically: a Recalculation lands as {@code <recalculations><producedTypes/><name/></...>}
     * inside the register file) - but creating them with {@code EcoreUtil.create} would leave them
     * ill-formed. Plain members (Attribute, Command, ...) are everything else. The classification
     * keys off the three platform base types, so it is robust to the concrete owner-specific
     * element subtypes.
     */
    private static boolean isFactoryInitializedChild(EClass elementType)
    {
        return MdClassPackage.Literals.BASIC_FORM.isSuperTypeOf(elementType)
            || MdClassPackage.Literals.BASIC_TEMPLATE.isSuperTypeOf(elementType)
            || MdClassPackage.Literals.RECALCULATION.isSuperTypeOf(elementType);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The supported, parsed properties. */
    private static final class Props
    {
        String synonym;
        String language;
        String comment;
    }

    /**
     * Parses the {@code properties} array into the supported {@link Props}. Returns a JSON error
     * string when a property is malformed or unsupported, or {@code null} on success.
     */
    private String parseProperties(List<JsonObject> properties, Props out)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (name == null || name.isEmpty())
            {
                return ToolResult.error("Each entry in 'properties' needs a non-empty 'name'.").toJson(); //$NON-NLS-1$
            }
            String value = asString(prop.get("value")); //$NON-NLS-1$
            switch (name.toLowerCase())
            {
                case "synonym": //$NON-NLS-1$
                    out.synonym = value;
                    out.language = asString(prop.get("language")); //$NON-NLS-1$
                    break;
                case "comment": //$NON-NLS-1$
                    out.comment = value;
                    break;
                default:
                    return ToolResult.error("Property '" + name + "' is not supported yet in " //$NON-NLS-1$ //$NON-NLS-2$
                        + "create_metadata. This version applies only: synonym, comment. Set other " //$NON-NLS-1$
                        + "properties (including type) via modify_metadata.").toJson(); //$NON-NLS-1$
            }
        }
        return null;
    }

    private static String asString(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    /** Applies the synonym (keyed by language CODE) and comment to a freshly created node. */
    private static void applyScalarProps(MdObject obj, Props props, String synonymLanguage)
    {
        if (props.synonym != null && !props.synonym.isEmpty() && synonymLanguage != null)
        {
            obj.getSynonym().put(synonymLanguage, props.synonym);
        }
        if (props.comment != null && !props.comment.isEmpty())
        {
            obj.setComment(props.comment);
        }
    }

    private static EObject childByName(EObject owner, EStructuralFeature feature, String name)
    {
        Object value = owner.eGet(feature);
        if (value instanceof EList<?>)
        {
            for (Object element : (EList<?>)value)
            {
                if (element instanceof MdObject child && name.equalsIgnoreCase(child.getName()))
                {
                    return child;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void addToFeature(EObject owner, EStructuralFeature feature, EObject child)
    {
        Object value = owner.eGet(feature);
        if (!(value instanceof EList))
        {
            throw new RuntimeException("Containment feature '" + feature.getName() + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<EObject>)value).add(child);
    }

    @SuppressWarnings("unchecked")
    private static void addToCollection(Configuration cfg, String refName, MdObject newObject)
    {
        Object collection = cfg.eGet(cfg.eClass().getEStructuralFeature(refName));
        if (!(collection instanceof EList))
        {
            throw new RuntimeException("Configuration feature '" + refName + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<MdObject>)collection).add(newObject);
    }

    /** Extracts the {@code Type.Name} top-object FQN from a (normalized) full-name FQN. */
    private static String topFqn(String normFqn)
    {
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        return parts.length >= 2 ? parts[0] + "." + parts[1] : normFqn; //$NON-NLS-1$
    }

    private String success(String fqn, EClass kind, String name, boolean persisted, Props props,
        String synonymLanguage)
    {
        ToolResult result = ToolResult.success()
            .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", fqn) //$NON-NLS-1$
            .put("kind", kind != null ? kind.getName() : null) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
        if (props.synonym != null && !props.synonym.isEmpty() && synonymLanguage != null)
        {
            result.put("synonym", props.synonym).put("language", synonymLanguage); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result
            .put("message", "Created " + fqn) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Checks that a name is a valid 1C identifier: starts with a letter or underscore, then letters,
     * digits and underscores only. Cyrillic letters are valid.
     */
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
