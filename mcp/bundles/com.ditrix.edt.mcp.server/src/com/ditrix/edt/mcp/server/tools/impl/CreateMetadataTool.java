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
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
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

    /** Canonical English singular type name for the CommonModule object. */
    private static final String TYPE_COMMON_MODULE = "CommonModule"; //$NON-NLS-1$

    /** Canonical English singular type name for the XDTOPackage object. */
    private static final String TYPE_XDTO_PACKAGE = "XDTOPackage"; //$NON-NLS-1$

    /** Quoted, comma-separated list of CommonModule kinds for schema hints. */
    private static final String COMMON_MODULE_KINDS = CommonModuleKind.quotedList();

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
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "Normalize the Russian letter 'ё'->'е' / 'Ё'->'Е' in the new node's NAME (the trailing " //$NON-NLS-1$
                + "FQN segment) and in any synonym / comment value (default true). 'ё' in a Name is " //$NON-NLS-1$
                + "flagged by the 1C standard mdo-ru-name-unallowed-letter, so normalizing on input " //$NON-NLS-1$
                + "stores a compliant name. Set false to keep 'ё' exactly as supplied.") //$NON-NLS-1$
            .booleanProperty("setAsDefault", //$NON-NLS-1$
                "Form OBJECT create only (FQN 'Type.Object.Form.FormName'). When true, registers the " //$NON-NLS-1$
                + "new form as the owner's default object form (default: false). Ignored for other " //$NON-NLS-1$
                + "create kinds.") //$NON-NLS-1$
            .enumProperty("commonModuleKind", //$NON-NLS-1$
                "CommonModule top-object only. Selects a standards-compliant flag combination the " //$NON-NLS-1$
                + "common-module-type validator accepts (no warning), instead of a bare module: " //$NON-NLS-1$
                + COMMON_MODULE_KINDS + ". Defaults to 'Server'. Ignored for other types. Combine " //$NON-NLS-1$
                + "with 'serverCall' / 'privileged' / 'returnValuesReuse'. These are create-time-only " //$NON-NLS-1$
                + "(the flag set cannot be re-derived post-hoc).", //$NON-NLS-1$
                CommonModuleKind.SERVER.token(), CommonModuleKind.SERVER_CALL.token(),
                CommonModuleKind.CLIENT_MANAGED.token(), CommonModuleKind.CLIENT_ORDINARY.token(),
                CommonModuleKind.CLIENT_SERVER.token(), CommonModuleKind.GLOBAL.token())
            .booleanProperty("serverCall", //$NON-NLS-1$
                "CommonModule top-object only. When true, the server module is callable from the " //$NON-NLS-1$
                + "client (server call). Valid only with a server kind and incompatible with " //$NON-NLS-1$
                + "'Global'. Ignored for other types.") //$NON-NLS-1$
            .booleanProperty("privileged", //$NON-NLS-1$
                "CommonModule top-object only. When true, the module runs with full (privileged) " //$NON-NLS-1$
                + "access. Valid only with the 'Server' kind (not a server call). Ignored for other " //$NON-NLS-1$
                + "types.") //$NON-NLS-1$
            .enumProperty("returnValuesReuse", //$NON-NLS-1$
                "CommonModule top-object only. Reuse of return values: 'DontUse' (default), " //$NON-NLS-1$
                + "'DuringRequest' or 'DuringSession'. 'DuringSession' yields a cached module accepted " //$NON-NLS-1$
                + "by the common-module-type validator. Ignored for other types.", //$NON-NLS-1$
                "DontUse", "DuringRequest", "DuringSession") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .stringProperty("targetNamespace", //$NON-NLS-1$
                "XDTOPackage top-object only. URI namespace for the new package; a non-empty " //$NON-NLS-1$
                + "namespace is required for the package to be valid. Defaults to " //$NON-NLS-1$
                + "'http://example.org/<Name>' when omitted. Create-time-only. Ignored for other " //$NON-NLS-1$
                + "types.") //$NON-NLS-1$
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
            .stringArrayProperty("normalized", //$NON-NLS-1$
                "Fields whose value was rewritten by the 'ё'->'е' normalization (when any)") //$NON-NLS-1$
            .stringProperty("commonModuleKind", //$NON-NLS-1$
                "Resolved CommonModule kind, when a CommonModule was created") //$NON-NLS-1$
            .stringProperty("targetNamespace", //$NON-NLS-1$
                "XDTO namespace written, when an XDTOPackage was created") //$NON-NLS-1$
            .booleanProperty("setAsDefault", //$NON-NLS-1$
                "Whether the new form was registered as the owner's default object form " //$NON-NLS-1$
                + "(form-object create only)") //$NON-NLS-1$
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
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        List<JsonObject> properties = JsonUtils.extractObjectArray(params, "properties"); //$NON-NLS-1$

        // Normalize 'ё'->'е' at the PARSE step, BEFORE identifier validation, so a Name carrying the
        // letter 'ё' (which the 1C standard mdo-ru-name-unallowed-letter rejects) is stored compliant.
        // Only the NAME (the trailing FQN segment) and synonym / comment values are touched here; the
        // type / kind tokens of the FQN are left exactly as supplied.
        MdNameNormalizer.Report normReport = new MdNameNormalizer.Report(normalizeYo);

        // A FQN that addresses a FORM's content (e.g. Catalog.X.Form.F.Command.C) is handled by a
        // dedicated branch: form members live on the editable Form content model (a cross-model hop),
        // not the mdclass tree, and take 'title'/'parent' properties rather than synonym/comment.
        String normFqn = normalizeLeafName(MetadataTypeUtils.normalizeFqn(fqn), normReport);
        FormElementWriter.FormMemberRef formRef = FormElementWriter.parse(normFqn);
        if (formRef != null)
        {
            if (FormElementWriter.isHandlerToken(formRef.kindToken))
            {
                return createFormHandler(projectName, normFqn, formRef, properties);
            }
            return createFormMember(projectName, normFqn, formRef, properties, normReport);
        }

        // A 4-part form FQN (Type.Object.Form.FormName) addresses the FORM OBJECT itself - neither a
        // form member (6+ parts, handled above) nor an mdclass member (Form is not a child-kind token).
        // It creates a working managed form (the BasicForm mdo + a renderable content Form).
        FormElementWriter.FormObjectRef formObjectRef = FormElementWriter.parseFormObjectCreate(normFqn);
        if (formObjectRef != null)
        {
            return createFormObject(projectName, normFqn, formObjectRef, properties, params, normReport);
        }

        // Parse the supported properties (synonym/comment); reject anything else early. The synonym /
        // comment values are 'ё'->'е' normalized through the same report as the Name.
        Props props = new Props();
        String propErr = parseProperties(properties, props, normReport);
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

        // Resolve the create-time-only, type-specific options (CommonModule flag combination and
        // XDTOPackage namespace) up front so an invalid request fails fast, before any BM work.
        // These only apply to the matching TOP-object type; they are ignored for everything else.
        final TypeSpecific typeSpecific;
        try
        {
            typeSpecific = TypeSpecific.resolve(target, params);
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
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
            return createTopLevel(project, config, projectName, target, normFqn, props, synonymLanguage,
                typeSpecific, normReport);
        }
        return createMember(project, projectName, target, normFqn, props, synonymLanguage, normReport);
    }

    // ---- top-level creation (mirrors the former create_metadata_object) -------------------------

    private String createTopLevel(IProject project, Configuration config, String projectName,
        CreateTarget target, String normFqn, Props props, String synonymLanguage,
        TypeSpecific typeSpecific, MdNameNormalizer.Report normReport)
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
                // Type-specific defaults applied on top of the factory's default content.
                typeSpecific.applyTo(newObject);
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
        return success(normFqn, createdKind, name, persisted, props, synonymLanguage, typeSpecific,
            normReport);
    }

    // ---- member creation (mirrors the former add_metadata_attribute, generalized) ---------------

    private String createMember(IProject project, String projectName, CreateTarget target,
        String normFqn, Props props, String synonymLanguage, MdNameNormalizer.Report normReport)
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
        return success(normFqn, createdKind, name, persisted, props, synonymLanguage, null, normReport);
    }

    // ---- form-content member creation (the cross-model hop into the editable Form) ---------------

    /**
     * Creates a member of a form's CONTENT model (a form attribute / command / visual item) addressed
     * by a form FQN. Forms are a separate top object reached from the {@code BasicForm} mdo via
     * {@code getForm()}; the mutation runs on the re-fetched content form inside a write transaction
     * and the content form's OWN FQN is force-exported (it serializes to {@code Form.form}).
     */
    private String createFormMember(String projectName, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
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
                    titleVal = normReport.apply("title", asString(prop.get("value"))); //$NON-NLS-1$ //$NON-NLS-2$
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

        final FormElementWriter.Kind fKind = kind;
        final String parent = parentName;
        final String bind = bindTarget;
        final String titleText = titleVal;
        // The designer's auto-children (extended tooltip / context menu) get script-variant
        // localized name suffixes, like FormObjectDefaultNameProvider.
        final boolean russianAutoNames = config.getScriptVariant() == ScriptVariant.RUSSIAN;
        final String[] createdKind = new String[1];

        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(project, config,
                ref.formPath, "Form not found for '" + normFqn + "'. Address a form as " //$NON-NLS-1$ //$NON-NLS-2$
                    + "'Type.Object.Form.FormName' or 'CommonForm.FormName'; check with " //$NON-NLS-1$
                    + "get_metadata_objects and get_metadata_details."); //$NON-NLS-1$

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

            persisted = FormElementWriter.writeEditableForm(fctx, "CreateFormMember", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    String err = FormElementWriter.createMember(formModel, fKind, ref.name, parent,
                        bind, titleLanguage, titleText, russianAutoNames, createdKind);
                    if (err != null)
                    {
                        throw new RuntimeException(err);
                    }
                });
        }
        catch (Exception e)
        {
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error creating form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create form element: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        ToolResult formResult = ToolResult.success()
            .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("kind", createdKind[0] != null ? createdKind[0] : fKind.name()) //$NON-NLS-1$
            .put("name", ref.name) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
        normReport.addTo(formResult);
        return formResult.put("message", "Created " + normFqn).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- form-OBJECT creation (the BasicForm mdo + its renderable content Form) ------------------

    /**
     * Creates a managed form OBJECT addressed by a 4-part form FQN
     * ({@code Type.Object.Form.FormName}): the MD-form ({@code BasicForm}, on the owner's {@code forms}
     * collection) AND a renderable, empty content {@code Form} (serialized to {@code Form.form}), linked
     * both ways. The owner is re-fetched inside a write transaction; the form authoring is delegated to
     * {@link FormElementWriter#createForm} (which seeds the render-critical {@code autoCommandBar} and
     * the form defaults, and attaches the content form under the canonical external-property FQN). Both
     * the content form's own FQN and the owner {@code .mdo} (which registers the form) are force-exported.
     */
    private String createFormObject(String projectName, String normFqn,
        FormElementWriter.FormObjectRef ref, List<JsonObject> properties, Map<String, String> params,
        MdNameNormalizer.Report normReport)
    {
        if (!isValidIdentifier(ref.formName))
        {
            return ToolResult.error("Invalid form name '" + ref.formName + "'. A name must start with " //$NON-NLS-1$ //$NON-NLS-2$
                + "a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        // A form object takes only synonym (with language); parent/title/dataPath are form-MEMBER props.
        Props props = new Props();
        String propErr = parseProperties(properties, props, normReport);
        if (propErr != null)
        {
            return propErr;
        }
        boolean setAsDefault = JsonUtils.extractBooleanArgument(params, "setAsDefault", false); //$NON-NLS-1$
        boolean expectedNotExists = JsonUtils.extractBooleanArgument(params, "expectedNotExists", false); //$NON-NLS-1$

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        MdObject owner = MetadataTypeUtils.findObject(config, ref.ownerType, ref.ownerName);
        if (owner == null)
        {
            return ToolResult.error("Owner object not found: " + ref.ownerFqn() + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use get_metadata_objects to list available objects.").toJson(); //$NON-NLS-1$
        }
        if (!(owner instanceof IBmObject))
        {
            return ToolResult.error("Owner object is not a BM object").toJson(); //$NON-NLS-1$
        }

        // The same duplicate / stale-intent precondition every other create applies (the main branch
        // checks via resolveExisting; a 4-part form FQN resolves on the owner's forms collection).
        // The in-transaction "Form already exists" guard below stays as the race-safety net.
        if (FormElementWriter.findOwnedForm(owner, ref.formName) != null)
        {
            if (expectedNotExists)
            {
                return ToolResult.error("Precondition failed: you set expectedNotExists, but " + normFqn //$NON-NLS-1$
                    + " already exists. Your snapshot is stale - re-read with get_metadata_objects, " //$NON-NLS-1$
                    + "then update the existing node instead of creating a duplicate.").toJson(); //$NON-NLS-1$
            }
            return ToolResult.error("Node already exists: " + normFqn).toJson(); //$NON-NLS-1$
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

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IModelObjectFactory mdFactory = Activator.getDefault().getModelObjectFactory();
        IModelObjectFactory formFactory = Activator.getDefault().getFormModelObjectFactory();
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (v8ProjectManager == null || mdFactory == null || bmModelManager == null)
        {
            return ToolResult.error("Required EDT services not available").toJson(); //$NON-NLS-1$
        }
        if (fqnGenerator == null)
        {
            return ToolResult.error("ITopObjectFqnGenerator not available (needed to attach the content " //$NON-NLS-1$
                + "form under its canonical FQN)").toJson(); //$NON-NLS-1$
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

        final long ownerBmId = ((IBmObject)owner).bmGetId();
        final String ownerFqn = ((IBmObject)owner).bmGetFqn();
        final String formName = ref.formName;
        final String synonym = props.synonym;
        final String comment = props.comment;
        final boolean fSetAsDefault = setAsDefault;
        // The fallback predefined command-bar name follows the configuration script variant, like
        // the designer's default-name provider (FormObjectDefaultNameProvider).
        final boolean russianAutoNames = config.getScriptVariant() == ScriptVariant.RUSSIAN;

        final String contentFormFqn;
        try
        {
            contentFormFqn = BmTransactions.<String>write(bmModel, "CreateFormObject", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txOwner = (EObject)tx.getObjectById(ownerBmId);
                if (!(txOwner instanceof MdObject))
                {
                    throw new RuntimeException("Owner object not found in transaction"); //$NON-NLS-1$
                }
                return FormElementWriter.createForm(tx, (MdObject)txOwner, formName, synonymLanguage,
                    synonym, comment, fSetAsDefault, mdFactory, formFactory, fqnGenerator, version,
                    russianAutoNames);
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating form object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create form: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist BOTH the content form's own Form.form (its FQN, generated inside the tx) and the owner
        // .mdo (which registers the new form in its <forms> and, when setAsDefault, the default-form ref).
        java.util.List<String> dirty = new java.util.ArrayList<>();
        if (contentFormFqn != null && !contentFormFqn.isEmpty())
        {
            dirty.add(contentFormFqn);
        }
        if (ownerFqn != null && !ownerFqn.isEmpty())
        {
            dirty.add(ownerFqn);
        }
        boolean persisted = !dirty.isEmpty() && BmTransactions.forceExportToDisk(project, dirty);

        ToolResult result = ToolResult.success()
            .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("kind", "Form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("name", formName) //$NON-NLS-1$
            .put("persisted", persisted) //$NON-NLS-1$
            .put("setAsDefault", setAsDefault); //$NON-NLS-1$
        if (props.synonym != null && !props.synonym.isEmpty() && synonymLanguage != null)
        {
            result.put("synonym", props.synonym).put("language", synonymLanguage); //$NON-NLS-1$ //$NON-NLS-2$
        }
        normReport.addTo(result);
        return result.put("message", "Created form " + normFqn //$NON-NLS-1$ //$NON-NLS-2$
            + ". Add structure with create_metadata on a form-member FQN " //$NON-NLS-1$
            + "(e.g. " + normFqn + ".Attribute.<Name>).").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Binds an event handler to a form root or to a form ITEM (the leaf is the EVENT name; the BSL
     * procedure name comes from a {@code procedure} property, defaulting to the event name). For an
     * item-level FQN ({@code ...Form.F.Field.Item.Handler.Event}) the handler attaches to the named
     * item; a COMMAND-level FQN ({@code ...Command.C.Handler.Action}) binds the command's single
     * Action (its procedure defaults to the COMMAND name). An unknown event is rejected with the
     * list of AVAILABLE events (the union of the element's base type and its extInfo sub-type)
     * localized to the configuration language.
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

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;
        if (version == null)
        {
            return ToolResult.error("Cannot resolve the platform version needed to validate the form " //$NON-NLS-1$
                + "event.").toJson(); //$NON-NLS-1$
        }
        final String langCode = MetadataLanguageUtils.resolveLanguageCode(config, null);

        final String eventName = ref.name;
        final String fProc = procName;
        final boolean commandOwner =
            FormElementWriter.kindForToken(ref.itemKindToken) == FormElementWriter.Kind.COMMAND;
        final String[] createdKind = new String[1];

        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(project, config,
                ref.formPath, "Form not found for '" + normFqn + "'. Address a form as " //$NON-NLS-1$ //$NON-NLS-2$
                    + "'Type.Object.Form.FormName' or 'CommonForm.FormName'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "CreateFormHandler", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // Form-level handlers attach to the form root; item-level handlers
                    // (Type.Object.Form.F.Field.Item.Handler.Event) attach to the named item; a
                    // COMMAND ref (Type.Object.Form.F.Command.C.Handler.Action) attaches to the
                    // form command.
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
                });
        }
        catch (Exception e)
        {
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error creating form handler", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create form handler: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

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
    private String parseProperties(List<JsonObject> properties, Props out,
        MdNameNormalizer.Report normReport)
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
                    out.synonym = normReport.apply("synonym", value); //$NON-NLS-1$
                    out.language = asString(prop.get("language")); //$NON-NLS-1$
                    break;
                case "comment": //$NON-NLS-1$
                    out.comment = normReport.apply("comment", value); //$NON-NLS-1$
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
        String synonymLanguage, TypeSpecific typeSpecific, MdNameNormalizer.Report normReport)
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
        if (typeSpecific != null)
        {
            if (typeSpecific.commonModuleFlags != null)
            {
                result.put("commonModuleKind", typeSpecific.commonModuleFlags.kind.token()); //$NON-NLS-1$
            }
            if (typeSpecific.xdtoNamespace != null)
            {
                result.put("targetNamespace", typeSpecific.xdtoNamespace); //$NON-NLS-1$
            }
        }
        normReport.addTo(result);
        return result
            .put("message", "Created " + fqn) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Normalizes 'ё'->'е' / 'Ё'->'Е' in the LEAF segment of a (normalized) FQN - the trailing
     * segment that becomes the new node's programmatic Name - leaving every preceding segment (the
     * type / kind tokens and the owner Names) untouched. Records the change as the "name" field on the
     * report. For a single-token FQN (malformed, handled downstream) the whole token is the leaf.
     */
    private static String normalizeLeafName(String normFqn, MdNameNormalizer.Report normReport)
    {
        if (normFqn == null || normFqn.isEmpty())
        {
            return normFqn;
        }
        int dot = normFqn.lastIndexOf('.');
        String leaf = dot >= 0 ? normFqn.substring(dot + 1) : normFqn;
        String normalizedLeaf = normReport.apply("name", leaf); //$NON-NLS-1$
        if (normalizedLeaf == leaf)
        {
            return normFqn;
        }
        return dot >= 0 ? normFqn.substring(0, dot + 1) + normalizedLeaf : normalizedLeaf;
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

    // ---- type-specific, create-time-only options (CommonModule flags / XDTO namespace) ----------

    /**
     * Resolved, create-time-only options that depend on the concrete TOP-object type: a
     * validator-approved CommonModule flag combination and/or an XDTOPackage namespace. Both are
     * applied on top of the EDT factory's default content and are NOT addressable post-hoc through
     * modify_metadata (a CommonModule's flag set cannot be re-derived from a single property; an
     * XDTOPackage needs a non-empty namespace to be valid at all), which is why they are top-level
     * create arguments rather than entries in the {@code properties} array.
     */
    private static final class TypeSpecific
    {
        final CommonModuleFlags commonModuleFlags;
        final String xdtoNamespace;

        private TypeSpecific(CommonModuleFlags commonModuleFlags, String xdtoNamespace)
        {
            this.commonModuleFlags = commonModuleFlags;
            this.xdtoNamespace = xdtoNamespace;
        }

        /** Applies the resolved options to a freshly created top object (no-op for other types). */
        void applyTo(MdObject newObject)
        {
            if (commonModuleFlags != null && newObject instanceof CommonModule)
            {
                commonModuleFlags.applyTo((CommonModule)newObject);
            }
            if (xdtoNamespace != null && newObject instanceof XDTOPackage)
            {
                ((XDTOPackage)newObject).setNamespace(xdtoNamespace);
            }
        }

        /**
         * Resolves the type-specific options from the tool parameters for a TOP-object create. For a
         * member create (or any other top-type) it returns an empty holder; the CommonModule /
         * XDTOPackage modifiers in {@code params} are simply ignored.
         *
         * @param target the resolved create target
         * @param params the tool parameters
         * @return the resolved holder (never null)
         * @throws IllegalArgumentException with a clear English message if a CommonModule
         *             kind/modifier combination is unknown or has no validator-accepted flag set
         */
        static TypeSpecific resolve(CreateTarget target, Map<String, String> params)
        {
            if (target == null || !target.topLevel)
            {
                return new TypeSpecific(null, null);
            }
            if (TYPE_COMMON_MODULE.equals(target.topLevelType))
            {
                return new TypeSpecific(CommonModuleFlags.resolve(params), null);
            }
            if (TYPE_XDTO_PACKAGE.equals(target.topLevelType))
            {
                String requested = JsonUtils.extractStringArgument(params, "targetNamespace"); //$NON-NLS-1$
                String ns = (requested != null && !requested.trim().isEmpty())
                    ? requested.trim()
                    : "http://example.org/" + target.childName; //$NON-NLS-1$
                return new TypeSpecific(null, ns);
            }
            return new TypeSpecific(null, null);
        }
    }

    /**
     * Standards-compliant CommonModule kinds. Each kind corresponds to a flag combination that the
     * EDT {@code common-module-type} validator accepts. The validator compares the eight flag
     * features against a fixed set of canonical combinations and reports a BLOCKER issue when none
     * matches, so the tool must pick exactly one of those combinations rather than an arbitrary
     * subset.
     */
    enum CommonModuleKind
    {
        /** Server-side module (the default): client ordinary + external connection + server. */
        SERVER("Server"), //$NON-NLS-1$
        /** Server module callable from the client (server call). */
        SERVER_CALL("ServerCall"), //$NON-NLS-1$
        /** Managed-application client module. */
        CLIENT_MANAGED("ClientManaged"), //$NON-NLS-1$
        /** Ordinary-application client module. */
        CLIENT_ORDINARY("ClientOrdinary"), //$NON-NLS-1$
        /** Combined client and server module. */
        CLIENT_SERVER("ClientServer"), //$NON-NLS-1$
        /** Global client module (its exports are available without the module prefix). */
        GLOBAL("Global"); //$NON-NLS-1$

        private final String token;

        CommonModuleKind(String token)
        {
            this.token = token;
        }

        String token()
        {
            return token;
        }

        static CommonModuleKind fromToken(String value)
        {
            for (CommonModuleKind k : values())
            {
                if (k.token.equalsIgnoreCase(value))
                {
                    return k;
                }
            }
            return null;
        }

        static String quotedList()
        {
            StringBuilder sb = new StringBuilder();
            for (CommonModuleKind k : values())
            {
                if (sb.length() > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append('\'').append(k.token).append('\'');
            }
            return sb.toString();
        }
    }

    /**
     * Resolved, validator-approved flag combination for a new CommonModule. Built from the
     * {@code commonModuleKind} plus the {@code serverCall}, {@code privileged} and
     * {@code returnValuesReuse} modifiers. Every combination produced here is one of the canonical
     * combinations recognized by the {@code common-module-type} check, so a freshly created module
     * never raises that warning.
     */
    static final class CommonModuleFlags
    {
        final CommonModuleKind kind;
        final boolean clientManagedApplication;
        final boolean clientOrdinaryApplication;
        final boolean server;
        final boolean serverCall;
        final boolean externalConnection;
        final boolean global;
        final boolean privileged;
        final ReturnValuesReuse returnValuesReuse;

        private CommonModuleFlags(CommonModuleKind kind, boolean clientManagedApplication,
            boolean clientOrdinaryApplication, boolean server, boolean serverCall,
            boolean externalConnection, boolean global, boolean privileged,
            ReturnValuesReuse returnValuesReuse)
        {
            this.kind = kind;
            this.clientManagedApplication = clientManagedApplication;
            this.clientOrdinaryApplication = clientOrdinaryApplication;
            this.server = server;
            this.serverCall = serverCall;
            this.externalConnection = externalConnection;
            this.global = global;
            this.privileged = privileged;
            this.returnValuesReuse = returnValuesReuse;
        }

        void applyTo(CommonModule module)
        {
            module.setClientManagedApplication(clientManagedApplication);
            module.setClientOrdinaryApplication(clientOrdinaryApplication);
            module.setServer(server);
            module.setServerCall(serverCall);
            module.setExternalConnection(externalConnection);
            module.setGlobal(global);
            module.setPrivileged(privileged);
            module.setReturnValuesReuse(returnValuesReuse);
        }

        /**
         * Resolves the flag combination from the tool parameters, validating that the requested
         * kind/modifier combination has a standards-compliant (validator-accepted) flag combination.
         *
         * @param params the tool parameters
         * @return the resolved flags
         * @throws IllegalArgumentException with a clear English message if the requested combination
         *             is unknown or invalid
         */
        static CommonModuleFlags resolve(Map<String, String> params)
        {
            String kindToken = JsonUtils.extractStringArgument(params, "commonModuleKind"); //$NON-NLS-1$
            CommonModuleKind kind;
            if (kindToken == null || kindToken.trim().isEmpty())
            {
                kind = CommonModuleKind.SERVER;
            }
            else
            {
                kind = CommonModuleKind.fromToken(kindToken.trim());
                if (kind == null)
                {
                    throw new IllegalArgumentException("Unknown commonModuleKind '" + kindToken //$NON-NLS-1$
                        + "'. Supported: " + CommonModuleKind.quotedList() + "."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            boolean serverCall = JsonUtils.extractBooleanArgument(params, "serverCall", false); //$NON-NLS-1$
            boolean privileged = JsonUtils.extractBooleanArgument(params, "privileged", false); //$NON-NLS-1$
            ReturnValuesReuse reuse = parseReuse(JsonUtils.extractStringArgument(params, "returnValuesReuse")); //$NON-NLS-1$

            // ServerCall kind is shorthand for the Server kind + the server-call flag.
            if (kind == CommonModuleKind.SERVER_CALL)
            {
                serverCall = true;
            }

            // --- Cross-flag validation (clear, actionable messages) ---

            boolean serverSideKind = kind == CommonModuleKind.SERVER
                || kind == CommonModuleKind.SERVER_CALL
                || kind == CommonModuleKind.CLIENT_SERVER;

            if (serverCall && !serverSideKind)
            {
                throw new IllegalArgumentException("serverCall requires a server kind " //$NON-NLS-1$
                    + "('Server', 'ServerCall' or 'ClientServer'); it is not valid for kind '" //$NON-NLS-1$
                    + kind.token() + "'."); //$NON-NLS-1$
            }
            if (serverCall && kind == CommonModuleKind.GLOBAL)
            {
                throw new IllegalArgumentException("serverCall is incompatible with the 'Global' kind."); //$NON-NLS-1$
            }
            if (privileged && kind != CommonModuleKind.SERVER)
            {
                throw new IllegalArgumentException("privileged requires the 'Server' kind " //$NON-NLS-1$
                    + "(a privileged server module that is not a server call); it is not valid for kind '" //$NON-NLS-1$
                    + kind.token() + "'."); //$NON-NLS-1$
            }
            if (privileged && serverCall)
            {
                throw new IllegalArgumentException("privileged is not valid together with serverCall."); //$NON-NLS-1$
            }
            if (privileged && reuse != ReturnValuesReuse.DONT_USE)
            {
                throw new IllegalArgumentException("privileged is not valid together with returnValuesReuse."); //$NON-NLS-1$
            }

            // returnValuesReuse only produces a validator-accepted module when it is either DontUse,
            // or DuringSession on a kind that has a cached variant (Server, ServerCall, ClientManaged,
            // ClientOrdinary). DuringRequest and reuse on Global/ClientServer have no canonical combo.
            if (reuse != ReturnValuesReuse.DONT_USE)
            {
                if (reuse == ReturnValuesReuse.DURING_REQUEST)
                {
                    throw new IllegalArgumentException("returnValuesReuse 'DuringRequest' has no " //$NON-NLS-1$
                        + "standards-compliant common-module combination; use 'DuringSession' for a " //$NON-NLS-1$
                        + "cached module, or 'DontUse'."); //$NON-NLS-1$
                }
                boolean reuseKind = kind == CommonModuleKind.SERVER
                    || kind == CommonModuleKind.SERVER_CALL
                    || kind == CommonModuleKind.CLIENT_MANAGED
                    || kind == CommonModuleKind.CLIENT_ORDINARY;
                if (!reuseKind)
                {
                    throw new IllegalArgumentException("returnValuesReuse 'DuringSession' is only valid " //$NON-NLS-1$
                        + "for the 'Server', 'ServerCall', 'ClientManaged' or 'ClientOrdinary' kinds; " //$NON-NLS-1$
                        + "it is not valid for kind '" + kind.token() + "'."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // --- Map (kind, modifiers) to a canonical, validator-accepted combo ---
            // Flags order: clientManaged, clientOrdinary, server, serverCall, externalConnection,
            // global, privileged, reuse.

            boolean cached = reuse == ReturnValuesReuse.DURING_SESSION;

            switch (kind)
            {
            case SERVER:
            case SERVER_CALL:
                if (privileged)
                {
                    // SERVER_FULL_ACCESS: server-only, privileged.
                    return new CommonModuleFlags(kind, false, false, true, false, false, false, true,
                        ReturnValuesReuse.DONT_USE);
                }
                if (serverCall)
                {
                    // SERVER_CALL / SERVER_CALL_CACHED: server + server call, no client flags.
                    return new CommonModuleFlags(kind, false, false, true, true, false, false, false,
                        cached ? ReturnValuesReuse.DURING_SESSION : ReturnValuesReuse.DONT_USE);
                }
                // SERVER / SERVER_CACHED: client ordinary + external connection + server.
                return new CommonModuleFlags(kind, false, true, true, false, true, false, false,
                    cached ? ReturnValuesReuse.DURING_SESSION : ReturnValuesReuse.DONT_USE);

            case CLIENT_MANAGED:
            case CLIENT_ORDINARY:
                // CLIENT / CLIENT_CACHED: both client flags set (the canonical client module).
                return new CommonModuleFlags(kind, true, true, false, false, false, false, false,
                    cached ? ReturnValuesReuse.DURING_SESSION : ReturnValuesReuse.DONT_USE);

            case CLIENT_SERVER:
                // CLIENT_SERVER: both client flags + server + external connection.
                return new CommonModuleFlags(kind, true, true, true, false, true, false, false,
                    ReturnValuesReuse.DONT_USE);

            case GLOBAL:
                // CLIENT_GLOBAL: both client flags + global.
                return new CommonModuleFlags(kind, true, true, false, false, false, true, false,
                    ReturnValuesReuse.DONT_USE);

            default:
                throw new IllegalArgumentException("Unsupported commonModuleKind: " + kind.token()); //$NON-NLS-1$
            }
        }

        private static ReturnValuesReuse parseReuse(String value)
        {
            if (value == null || value.trim().isEmpty())
            {
                return ReturnValuesReuse.DONT_USE;
            }
            String normalized = value.trim();
            if ("DontUse".equalsIgnoreCase(normalized)) //$NON-NLS-1$
            {
                return ReturnValuesReuse.DONT_USE;
            }
            if ("DuringRequest".equalsIgnoreCase(normalized)) //$NON-NLS-1$
            {
                return ReturnValuesReuse.DURING_REQUEST;
            }
            if ("DuringSession".equalsIgnoreCase(normalized)) //$NON-NLS-1$
            {
                return ReturnValuesReuse.DURING_SESSION;
            }
            throw new IllegalArgumentException("Unknown returnValuesReuse '" + value //$NON-NLS-1$
                + "'. Supported: 'DontUse', 'DuringRequest', 'DuringSession'."); //$NON-NLS-1$
        }
    }
}
