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
import com._1c.g5.v8.bm.core.IBmTransaction;
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
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver.CreateTarget;
import com.ditrix.edt.mcp.server.utils.MetadataTypeBuilder;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.XdtoWriteException;
import com.ditrix.edt.mcp.server.utils.XdtoWriter;
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

    /** Schema / param key: extension event call type. */
    private static final String KEY_CALL_TYPE = "callType"; //$NON-NLS-1$

    /** Schema / param key: stale-intent precondition guard. */
    private static final String KEY_EXPECTED_NOT_EXISTS = "expectedNotExists"; //$NON-NLS-1$

    /** Schema / param key: register the new form as the owner's default form. */
    private static final String KEY_SET_AS_DEFAULT = "setAsDefault"; //$NON-NLS-1$

    /** Schema / param key: seed the new object form's main Object attribute. */
    private static final String KEY_GENERATE_CONTENT = "generateContent"; //$NON-NLS-1$

    /** Schema / param key: the owner attribute names to render as bound fields on a generated form. */
    private static final String KEY_OBJECT_FIELDS = "objectFields"; //$NON-NLS-1$

    /** Schema / param key: CommonModule kind selector. */
    private static final String KEY_COMMON_MODULE_KIND = "commonModuleKind"; //$NON-NLS-1$

    /** Schema / param key: XDTOPackage target namespace. */
    private static final String KEY_TARGET_NAMESPACE = "targetNamespace"; //$NON-NLS-1$

    /** Output key: whether the change was exported to disk. */
    private static final String KEY_PERSISTED = "persisted"; //$NON-NLS-1$

    /** Output key: names of the optional attributes applied (XDTO member create only). */
    private static final String KEY_APPLIED = "applied"; //$NON-NLS-1$

    /** Property / output key: the synonym display name. */
    private static final String KEY_SYNONYM = "synonym"; //$NON-NLS-1$

    /** Property / output key: the language code. */
    private static final String KEY_LANGUAGE = "language"; //$NON-NLS-1$

    /** Property entry key: the property value. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** Output value: the action a successful create reports. */
    private static final String VAL_CREATED = "created"; //$NON-NLS-1$

    /** Error: required EDT services are not available. */
    private static final String ERR_SERVICES_UNAVAILABLE = "Required EDT services not available"; //$NON-NLS-1$

    /** Error prefix: could not resolve the V8 project. */
    private static final String ERR_NO_V8_PROJECT = "Could not resolve V8 project for: "; //$NON-NLS-1$

    /** Error prefix: BM model not available for the project. */
    private static final String ERR_NO_BM_MODEL = "BM model not available for project: "; //$NON-NLS-1$

    /** Error: a properties entry is missing a non-empty name. */
    private static final String ERR_PROPERTY_NEEDS_NAME =
        "Each entry in 'properties' needs a non-empty 'name'."; //$NON-NLS-1$

    /** Error prefix: a property name is not supported. */
    private static final String ERR_PROPERTY_PREFIX = "Property '"; //$NON-NLS-1$

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
            + "is inferred from the FQN; type and kind tokens may be English or Russian. Also creates " //$NON-NLS-1$
            + "an XDTO package MEMBER: 'XDTOPackage.<Package>.ObjectType.<Name>' (an ObjectType), " //$NON-NLS-1$
            + "'XDTOPackage.<Package>.Property.<Name>' (a package-global property) or " //$NON-NLS-1$
            + "'XDTOPackage.<Package>.ObjectType.<Type>.Property.<Name>' (a property nested in an " //$NON-NLS-1$
            + "ObjectType) - see 'properties' for the XDTO-specific attribute vocabulary. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('create_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to create (required). Top object: 'Type.Name' " //$NON-NLS-1$
                + "(e.g. 'Catalog.Products'). Member: 'Type.Name.Kind.Name' " //$NON-NLS-1$
                + "(e.g. 'Catalog.Products.Attribute.Weight'). The trailing Name is the new node's " //$NON-NLS-1$
                + "programmatic Name; type / kind tokens may be English or Russian.", true) //$NON-NLS-1$
            .objectArrayProperty("properties", //$NON-NLS-1$
                "Optional properties to apply at creation, as [{name, value, language?}]. For most " //$NON-NLS-1$
                + "kinds this applies 'synonym' (with optional 'language' code) and 'comment'; other " //$NON-NLS-1$
                + "property names are rejected (set them via modify_metadata). XDTO PACKAGE MEMBERS " //$NON-NLS-1$
                + "('XDTOPackage.<Package>.ObjectType.<Name>' / '...Property.<Name>' / " //$NON-NLS-1$
                + "'...ObjectType.<Type>.Property.<Name>') use a DIFFERENT vocabulary instead: an " //$NON-NLS-1$
                + "ObjectType takes the optional boolean flags 'open' / 'abstract' / 'mixed' / " //$NON-NLS-1$
                + "'ordered' / 'sequenced'; a Property REQUIRES 'type' (a built-in XSD type name like " //$NON-NLS-1$
                + "'string', the EXACT name of an ObjectType already in the same package for a " //$NON-NLS-1$
                + "same-package reference, or an object {nsUri, name}) plus the optional 'lowerBound' / " //$NON-NLS-1$
                + "'upperBound' (integers, ObjectType-nested properties only), 'nillable' / 'fixed' " //$NON-NLS-1$
                + "(booleans, 'fixed'=true needs a 'default') and 'default' (string).") //$NON-NLS-1$
            .booleanProperty(KEY_EXPECTED_NOT_EXISTS,
                "Optional stale-intent guard (default false): assert the node does not yet exist for " //$NON-NLS-1$
                + "a sharper precondition error. A real duplicate is always rejected anyway.") //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "Normalize the Russian letter 'ё'->'е' / 'Ё'->'Е' in the new node's NAME (the trailing " //$NON-NLS-1$
                + "FQN segment) and in any synonym / comment value (default true). 'ё' in a Name is " //$NON-NLS-1$
                + "flagged by the 1C standard mdo-ru-name-unallowed-letter, so normalizing on input " //$NON-NLS-1$
                + "stores a compliant name. Set false to keep 'ё' exactly as supplied.") //$NON-NLS-1$
            .booleanProperty(KEY_SET_AS_DEFAULT,
                "Form OBJECT create only (FQN 'Type.Object.Form.FormName'). When true, registers the " //$NON-NLS-1$
                + "new form as the owner's default object form (default: false). Ignored for other " //$NON-NLS-1$
                + "create kinds.") //$NON-NLS-1$
            .booleanProperty(KEY_GENERATE_CONTENT,
                "Form OBJECT create only (FQN 'Type.Object.Form.FormName'), and only for an OBJECT-form " //$NON-NLS-1$
                + "owner: Catalog / Document / ChartOfCharacteristicTypes / ChartOfAccounts / " //$NON-NLS-1$
                + "ChartOfCalculationTypes / ExchangePlan / BusinessProcess / Task / Report / " //$NON-NLS-1$
                + "DataProcessor. When true, seeds the new form with the main Object attribute (type " //$NON-NLS-1$
                + "'<Type>Object.<Name>', main + savedData + view/edit) like the designer's 'New form' " //$NON-NLS-1$
                + "wizard - so agents never need to edit the .form outside MCP. Default: false (an empty " //$NON-NLS-1$
                + "form). Ignored (no seeding) for a register / Constant / other non-object owner and " //$NON-NLS-1$
                + "for other create kinds - the result then echoes generateContent=false.") //$NON-NLS-1$
            .stringArrayProperty(KEY_OBJECT_FIELDS,
                "Form OBJECT create only, and only with generateContent=true: the owner attribute names " //$NON-NLS-1$
                + "to render as bound input fields (dataPath 'Object.<name>'), like the designer's " //$NON-NLS-1$
                + "checked attribute list. Omitted -> the kind defaults (documents: Number, Date; " //$NON-NLS-1$
                + "catalogs: Code, Description; other object kinds: none). An explicit non-empty list -> " //$NON-NLS-1$
                + "exactly those names. An empty array [] -> only the main Object attribute (no fields). " //$NON-NLS-1$
                + "Ignored for other create kinds.") //$NON-NLS-1$
            .enumProperty(KEY_CALL_TYPE,
                "Form event handler ONLY (item-level '...Form.F.<ItemKind>.Item.Handler.<Event>' or " //$NON-NLS-1$
                + "form-level '...Form.F.Handler.<Event>'), in a " //$NON-NLS-1$
                + "configuration EXTENSION project. Selects EXTENSION event interception: binds a " //$NON-NLS-1$
                + "form:EventHandlerExtension with this call type instead of a plain base handler, so the " //$NON-NLS-1$
                + "extension reacts Before / After / Instead of the base element's event (works even when " //$NON-NLS-1$
                + "the base element has no handler of its own). Omit for a normal base handler. The BSL " //$NON-NLS-1$
                + "handler procedure itself is added separately via write_module_source. Rejected on a " //$NON-NLS-1$
                + "base configuration or a non-handler FQN.", //$NON-NLS-1$
                "Before", "After", "Instead") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .enumProperty(KEY_COMMON_MODULE_KIND,
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
            .stringProperty(KEY_TARGET_NAMESPACE,
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
            .stringProperty(McpKeys.ACTION, "'created' on success") //$NON-NLS-1$
            .stringProperty("fqn", "Normalized full-name FQN of the created node") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("kind", "EClass of the created node (e.g. 'Catalog', 'CatalogAttribute')") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Programmatic name of the created node") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_PERSISTED, "Whether the change was exported to disk") //$NON-NLS-1$
            .stringArrayProperty(KEY_APPLIED,
                "Names of the optional attributes actually applied (XDTO package member create only)") //$NON-NLS-1$
            .stringProperty(KEY_SYNONYM, "Display name written, when a synonym property was provided") //$NON-NLS-1$
            .stringProperty(KEY_LANGUAGE, "Language code the synonym was written for") //$NON-NLS-1$
            .stringArrayProperty("normalized", //$NON-NLS-1$
                "Fields whose value was rewritten by the 'ё'->'е' normalization (when any)") //$NON-NLS-1$
            .stringProperty(KEY_COMMON_MODULE_KIND,
                "Resolved CommonModule kind, when a CommonModule was created") //$NON-NLS-1$
            .stringProperty(KEY_TARGET_NAMESPACE,
                "XDTO namespace written, when an XDTOPackage was created") //$NON-NLS-1$
            .booleanProperty(KEY_SET_AS_DEFAULT,
                "Whether the new form was registered as the owner's default object form " //$NON-NLS-1$
                + "(form-object create only)") //$NON-NLS-1$
            .booleanProperty(KEY_GENERATE_CONTENT,
                "Whether the new form was seeded with the main Object attribute " //$NON-NLS-1$
                + "(form-object create only)") //$NON-NLS-1$
            .stringProperty(KEY_CALL_TYPE,
                "Extension event call type written (Before/After/Instead), when an extension event " //$NON-NLS-1$
                + "handler (form:EventHandlerExtension) was created") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable confirmation message") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean expectedNotExists = JsonUtils.extractBooleanArgument(params, KEY_EXPECTED_NOT_EXISTS, false);
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        List<JsonObject> properties = JsonUtils.extractObjectArray(params, "properties"); //$NON-NLS-1$
        String callType = JsonUtils.extractStringArgument(params, KEY_CALL_TYPE);

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
        // callType is meaningful ONLY for a form event handler FQN; reject it loudly elsewhere rather
        // than silently dropping the interception intent.
        boolean isHandlerFqn = formRef != null && FormElementWriter.isHandlerToken(formRef.kindToken);
        if (callType != null && !callType.trim().isEmpty() && !isHandlerFqn)
        {
            return ToolResult.error("callType applies only to a form EVENT HANDLER FQN " //$NON-NLS-1$
                + "('...Form.F.<ItemKind>.Item.Handler.<Event>' or '...Form.F.Handler.<Event>'). " //$NON-NLS-1$
                + "The FQN '" + fqn + "' is not a form event handler; omit callType.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String formResult = tryDispatchFormFqn(projectName, normFqn, formRef, properties, params,
            normReport, callType);
        if (formResult != null)
        {
            return formResult;
        }

        // A FQN that addresses an XDTO PACKAGE MEMBER (an ObjectType or a Property - issue #183 stream 1)
        // is handled by a dedicated branch: an ObjectType/Property lives on the package's editable
        // xdto.model content (a cross-model hop, the same @ExternalProperty shape a report's DCS uses),
        // not the mdclass tree, and is addressed by its own small FQN grammar (XdtoWriter.parseMemberRef)
        // rather than MetadataNodeResolver (which does not know "ObjectType"/"Property" as mdclass child
        // kinds). Dispatched BEFORE the generic 'properties' parse (synonym/comment only) because an XDTO
        // member's assignable attributes (type/bounds/nillable/flags) are a completely different
        // vocabulary reusing the SAME 'properties' parameter shape.
        XdtoWriter.MemberRef xdtoRef = XdtoWriter.parseMemberRef(normFqn);
        if (xdtoRef != null)
        {
            return createXdtoMember(projectName, normFqn, xdtoRef, properties);
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
        try
        {
            synonymLanguage = MetadataLanguageUtils.resolveSynonymLanguage(config, props.synonym,
                props.language, "the synonym"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
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
            return createTopLevel(new TopLevelRequest(project, config, projectName, target, normFqn,
                props, synonymLanguage, typeSpecific, normReport));
        }
        return createMember(project, projectName, target, normFqn, props, synonymLanguage, normReport);
    }

    /**
     * Dispatches a FORM-targeted FQN to the dedicated form writers, extracted verbatim from
     * {@link #executeOnUiThread}. A 6+-part FQN that is a form member is created as a handler or a
     * plain member; a 4-part {@code Type.Object.Form.FormName} FQN addresses the FORM OBJECT itself
     * (the BasicForm mdo plus a renderable content Form). Returns {@code null} when {@code normFqn}
     * is not a form-targeted FQN, so the caller falls through to mdclass-member creation.
     *
     * @return the form-creation result JSON, or {@code null} when this is not a form FQN
     */
    private String tryDispatchFormFqn(String projectName, String normFqn,
        FormElementWriter.FormMemberRef formRef, List<JsonObject> properties, Map<String, String> params,
        MdNameNormalizer.Report normReport, String callType)
    {
        if (formRef != null)
        {
            if (FormElementWriter.isHandlerToken(formRef.kindToken))
            {
                return createFormHandler(projectName, normFqn, formRef, properties, callType);
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
        return null;
    }

    // ---- XDTO package member creation (issue #183 stream 1) --------------------------------------
    //
    // An ObjectType / Property lives on the package's lazily-materialized xdto.model content (a
    // cross-model hop, the SAME transient @ExternalProperty shape a report's DCS uses), not the
    // mdclass tree - MetadataNodeResolver cannot see it. The duplicate check runs INSIDE the write
    // transaction (mirrors resolveMemberOwnerInTx's own "Member already exists" check for a generic
    // mdclass member), because the package's content is lazy: only materializing it (inside the tx)
    // proves whether a same-named member already exists on disk.

    /** Groups a successfully created XDTO member's result fields for {@link #xdtoMemberSuccess}. */
    private static final class XdtoCreateResult
    {
        final String eClassName;
        final String name;
        final List<String> applied;

        XdtoCreateResult(String eClassName, String name, List<String> applied)
        {
            this.eClassName = eClassName;
            this.name = name;
            this.applied = applied;
        }
    }

    /**
     * Creates an XDTO PACKAGE MEMBER (an ObjectType or a Property, package-global or nested in an
     * ObjectType) addressed by {@code ref}. Resolves the owning XDTOPackage top object, materializes +
     * attaches its {@code Package} content inside ONE write transaction ({@link XdtoWriter#resolvePackageContent}),
     * appends the new member after asserting no duplicate exists by name, and applies any optional
     * {@code properties} (ObjectType flags, or a Property's REQUIRED {@code type} plus optional
     * bounds/nillable/ref/fixed/default) via {@link XdtoWriter}. Force-exports the owning package's FQN
     * (dual-export with the content's own resource FQN when it is a distinct top object, guarding the
     * #239-class silent-false-success).
     */
    private String createXdtoMember(String projectName, String normFqn, XdtoWriter.MemberRef ref,
        List<JsonObject> properties)
    {
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        // The leaf name is written verbatim into Package.xdto as an XML name - validate it with the
        // same identifier rule the mdclass member create enforces (a 1C identifier is a safe subset
        // of an XML NCName), or 'ObjectType.123Bad' would succeed yet produce an invalid file.
        String newMemberName = ref.kind == XdtoWriter.Kind.OBJECT_TYPE ? ref.objectTypeName : ref.propertyName;
        if (!isValidIdentifier(newMemberName))
        {
            return ToolResult.error("Invalid XDTO member name '" + newMemberName + "': a name must start " //$NON-NLS-1$ //$NON-NLS-2$
                + "with a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }
        MetadataNodeResolver.MetadataNode pkgNode =
            MetadataNodeResolver.resolveExistingWithYoFallback(ctx.config, ref.packageFqn).node;
        if (pkgNode == null || !(pkgNode.object instanceof XDTOPackage)
            || !(pkgNode.object instanceof IBmObject))
        {
            return ToolResult.error("XDTOPackage not found: " + ref.packageFqn + ". Create it first " //$NON-NLS-1$ //$NON-NLS-2$
                + "with create_metadata on 'XDTOPackage.<Name>'.").toJson(); //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (bmModelManager == null || fqnGenerator == null)
        {
            return ToolResult.error(ERR_SERVICES_UNAVAILABLE).toJson();
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error(ERR_NO_BM_MODEL + projectName).toJson();
        }

        final long pkgBmId = ((IBmObject)pkgNode.object).bmGetId();
        final String[] contentFqnHolder = { null };
        XdtoCreateResult result;
        try
        {
            result = BmTransactions.<XdtoCreateResult> write(bmModel, "CreateXdtoMember", (tx, pm) -> //$NON-NLS-1$
                createXdtoMemberInTx(tx, pkgBmId, fqnGenerator, ref, properties, contentFqnHolder));
        }
        catch (Exception e)
        {
            String ready = XdtoWriteException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error creating XDTO member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create XDTO member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        List<String> exportFqns = new java.util.ArrayList<>();
        // Export by the RESOLVED package's canonical FQN: with the yo (ё->е) fallback the
        // caller-typed ref.packageFqn may differ from the stored name, and force-export must
        // target the stored top object (never the caller-supplied spelling).
        String pkgExportFqn = "XDTOPackage." + ((XDTOPackage)pkgNode.object).getName(); //$NON-NLS-1$
        exportFqns.add(pkgExportFqn);
        String contentFqn = contentFqnHolder[0];
        if (contentFqn != null && !contentFqn.equals(pkgExportFqn))
        {
            exportFqns.add(contentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return xdtoMemberSuccess(normFqn, result, persisted, VAL_CREATED, "Created "); //$NON-NLS-1$
    }

    /**
     * The write-transaction body for {@link #createXdtoMember}: re-fetches the XDTOPackage, materializes
     * its content, records the content's own export FQN (captured by {@link XdtoWriter#resolvePackageContent}
     * itself - NEVER re-derived here via a post-attach {@code bmGetFqn()}, which throws on a just-attached,
     * not-yet-settled object; a live-stand-caught regression) into {@code contentFqnHolder}, then
     * dispatches to the ObjectType / Property creation by {@code ref.kind}. Throws
     * {@link XdtoWriteException} (a ready JSON error) on a resolution / duplicate / validation failure,
     * rolling the whole write back.
     */
    private static XdtoCreateResult createXdtoMemberInTx(IBmTransaction tx, long pkgBmId,
        ITopObjectFqnGenerator fqnGenerator, XdtoWriter.MemberRef ref, List<JsonObject> properties,
        String[] contentFqnHolder)
    {
        Object inTx = tx.getObjectById(pkgBmId);
        if (!(inTx instanceof XDTOPackage))
        {
            throw new XdtoWriteException(ToolResult.error("The XDTO package could not be resolved " //$NON-NLS-1$
                + "inside the transaction.").toJson()); //$NON-NLS-1$
        }
        XDTOPackage txPkg = (XDTOPackage)inTx;
        XdtoWriter.ContentResolution resolved = XdtoWriter.resolvePackageContent(txPkg, tx, fqnGenerator);
        if (resolved.error != null)
        {
            throw new XdtoWriteException(resolved.error);
        }
        Package content = resolved.content;
        contentFqnHolder[0] = resolved.contentFqn;

        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            return createObjectTypeInTx(content, ref, properties);
        }
        if (ref.kind == XdtoWriter.Kind.PACKAGE_PROPERTY)
        {
            return createPropertyInTx(content.getProperties(), content, ref, properties);
        }
        // OBJECT_TYPE_PROPERTY: the owning ObjectType must already exist (the parent must exist, like
        // every other create_metadata member kind).
        ObjectType owner = XdtoWriter.findObjectType(content, ref.objectTypeName);
        if (owner == null)
        {
            throw new XdtoWriteException(ToolResult.error("ObjectType not found: '" + ref.objectTypeName //$NON-NLS-1$
                + "' in package " + ref.packageFqn + ". Create it first with " //$NON-NLS-1$ //$NON-NLS-2$
                + "'" + ref.packageFqn + ".ObjectType." + ref.objectTypeName + "'.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return createPropertyInTx(owner.getProperties(), content, ref, properties);
    }

    private static XdtoCreateResult createObjectTypeInTx(Package content, XdtoWriter.MemberRef ref,
        List<JsonObject> properties)
    {
        if (XdtoWriter.findObjectType(content, ref.objectTypeName) != null)
        {
            throw new XdtoWriteException(ToolResult.error("ObjectType already exists: '" + ref.objectTypeName //$NON-NLS-1$
                + "' in package " + ref.packageFqn).toJson()); //$NON-NLS-1$
        }
        ObjectType type = XdtoWriter.createObjectType(content, ref.objectTypeName);
        // The optional flags (open/abstract/mixed/ordered/sequenced) come from the SAME 'properties'
        // array a Property's attributes do - applied here too, else a caller's open=true / etc. is
        // silently dropped (codex review finding #7).
        XdtoWriter.Result applied = XdtoWriter.applyObjectTypeProperties(type, properties);
        if (applied.hasError())
        {
            throw new XdtoWriteException(applied.error);
        }
        return new XdtoCreateResult(type.eClass().getName(), ref.objectTypeName, applied.applied);
    }

    private static XdtoCreateResult createPropertyInTx(EList<Property> owner, Package content,
        XdtoWriter.MemberRef ref, List<JsonObject> properties)
    {
        String name = ref.propertyName;
        if (XdtoWriter.findProperty(owner, name) != null)
        {
            throw new XdtoWriteException(ToolResult.error("Property already exists: '" + name + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                + normFqnOfProperty(ref)).toJson());
        }
        Property property = XdtoWriter.createProperty(owner, name);
        XdtoWriter.Result applied = XdtoWriter.applyPropertyProperties(property, content, properties, true);
        if (applied.hasError())
        {
            throw new XdtoWriteException(applied.error);
        }
        return new XdtoCreateResult(property.eClass().getName(), name, applied.applied);
    }

    /** Describes where a Property member lives, for an actionable duplicate error. */
    private static String normFqnOfProperty(XdtoWriter.MemberRef ref)
    {
        return ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY
            ? ref.packageFqn + ".ObjectType." + ref.objectTypeName //$NON-NLS-1$
            : ref.packageFqn;
    }

    /**
     * Builds the success JSON for a created/modified XDTO member: {@code fqn} / {@code kind} /
     * {@code name} / {@code persisted} / {@code applied} (the optional attributes actually set) plus a
     * confirmation message. Shared by {@link #createXdtoMember} (this tool) and reusable verbatim by the
     * modify path's own success builder shape.
     */
    private static String xdtoMemberSuccess(String normFqn, XdtoCreateResult result, boolean persisted,
        String action, String verb)
    {
        return ToolResult.success()
            .put(McpKeys.ACTION, action)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("kind", result.eClassName) //$NON-NLS-1$
            .put("name", result.name) //$NON-NLS-1$
            .put(KEY_PERSISTED, persisted)
            .put(KEY_APPLIED, result.applied)
            .put(McpKeys.MESSAGE, verb + normFqn + (result.applied.isEmpty() ? "" //$NON-NLS-1$
                : " (" + String.join(", ", result.applied) + ")")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    // ---- top-level creation (mirrors the former create_metadata_object) -------------------------

    /**
     * Immutable inputs for a top-level object create, grouping the request so
     * {@link #createTopLevel(TopLevelRequest)} takes one holder instead of a long argument list.
     */
    private static final class TopLevelRequest
    {
        final IProject project;
        final Configuration config;
        final String projectName;
        final CreateTarget target;
        final String normFqn;
        final Props props;
        final String synonymLanguage;
        final TypeSpecific typeSpecific;
        final MdNameNormalizer.Report normReport;

        TopLevelRequest(IProject project, Configuration config, String projectName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            CreateTarget target, String normFqn, Props props, String synonymLanguage,
            TypeSpecific typeSpecific, MdNameNormalizer.Report normReport)
        {
            this.project = project;
            this.config = config;
            this.projectName = projectName;
            this.target = target;
            this.normFqn = normFqn;
            this.props = props;
            this.synonymLanguage = synonymLanguage;
            this.typeSpecific = typeSpecific;
            this.normReport = normReport;
        }
    }

    private String createTopLevel(TopLevelRequest req)
    {
        // Any configuration top-level type resolved by MetadataTypeUtils is attempted: the EDT
        // model-object factory produces the EDT "New"-wizard default content. A type the factory
        // cannot instantiate fails gracefully below (clean error, no crash) rather than via a
        // hand-maintained allow-list.
        EClass eClass = resolveCollectionElementType(req.config, req.target.configFeatureName);
        if (eClass == null)
        {
            return ToolResult.error("Could not resolve configuration collection '" //$NON-NLS-1$
                + req.target.configFeatureName + "'").toJson(); //$NON-NLS-1$
        }

        BmContext bm = resolveBmContext(req.project, req.projectName);
        if (bm.hasError())
        {
            return bm.error;
        }
        if (!(req.config instanceof IBmObject))
        {
            return ToolResult.error("Configuration is not a BM object").toJson(); //$NON-NLS-1$
        }
        final long configBmId = ((IBmObject)req.config).bmGetId();
        final String configFqn = ((IBmObject)req.config).bmGetFqn();
        final String name = req.target.childName;
        final String configFeatureName = req.target.configFeatureName;
        final IModelObjectFactory factory = bm.factory;
        final Version version = bm.version;
        // Only needed for an XDTOPackage create (below); resolved up front like the rest of the BM
        // services this method uses, not inside the transaction.
        final ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();

        final String[] xdtoContentFqnHolder = { null };
        final EClass createdKind;
        try
        {
            createdKind = BmTransactions.<EClass>write(bm.bmModel, "CreateMetadataObject", (tx, pm) -> //$NON-NLS-1$
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
                applyScalarProps(newObject, req.props, req.synonymLanguage);
                // Type-specific defaults applied on top of the factory's default content (sets the
                // namespace for an XDTOPackage - always non-empty, defaulted when omitted).
                req.typeSpecific.applyTo(newObject);
                tx.attachTopObject((IBmObject)newObject, req.normFqn);
                addToCollection(cfg, configFeatureName, newObject);
                factory.fillDefaultReferences(newObject);
                // An XDTOPackage's content (Package.xdto) is a lazy @ExternalProperty; a live-stand
                // finding showed the platform does NOT durably serialize it when it is first
                // materialized in a LATER, separate transaction (the first member-edit call) - each
                // subsequent edit re-materialized an empty content, discarding the previous one.
                // Materializing it HERE instead, in the SAME transaction that creates and attaches the
                // owning XDTOPackage, makes Package.xdto exist from creation, so every later member
                // edit hits the proven-working EXISTING-content path. Best-effort: a resolution
                // failure here is NOT fatal to the top-object create itself (the content still
                // materializes lazily on the first member edit, same as before this change) - it is
                // just not force-exported in THIS commit.
                if (newObject instanceof XDTOPackage && fqnGenerator != null)
                {
                    XdtoWriter.ContentResolution resolved =
                        XdtoWriter.resolvePackageContent((XDTOPackage)newObject, tx, fqnGenerator);
                    if (resolved.error == null)
                    {
                        xdtoContentFqnHolder[0] = resolved.contentFqn;
                    }
                }
                return newObject.eClass();
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating metadata object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create object: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        List<String> exportFqns = dirtyFqns(req.normFqn, configFqn);
        String xdtoContentFqn = xdtoContentFqnHolder[0];
        if (xdtoContentFqn != null && !exportFqns.contains(xdtoContentFqn))
        {
            exportFqns.add(xdtoContentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(req.project, exportFqns);
        return success(new SuccessInfo(req.normFqn, createdKind, name, persisted, req.props,
            req.synonymLanguage, req.typeSpecific, req.normReport));
    }

    /**
     * Resolves the EClass of the configuration containment collection named {@code featureName},
     * or {@code null} when the feature is absent or not an EClass-typed collection. Side-effect-free.
     */
    private static EClass resolveCollectionElementType(Configuration config, String featureName)
    {
        EStructuralFeature collection = config.eClass().getEStructuralFeature(featureName);
        if (collection == null || !(collection.getEType() instanceof EClass))
        {
            return null;
        }
        return (EClass)collection.getEType();
    }

    /**
     * Builds the force-export dirty list for a top-object create: the new object's FQN plus the
     * configuration FQN (which registers the object) when present. Side-effect-free.
     */
    private static List<String> dirtyFqns(String normFqn, String configFqn)
    {
        java.util.List<String> dirty = new java.util.ArrayList<>();
        dirty.add(normFqn);
        if (configFqn != null && !configFqn.isEmpty())
        {
            dirty.add(configFqn);
        }
        return dirty;
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
        BmContext bm = resolveBmContext(project, projectName);
        if (bm.hasError())
        {
            return bm.error;
        }

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
        final IModelObjectFactory factory = bm.factory;
        final Version version = bm.version;

        final EClass createdKind;
        try
        {
            createdKind = BmTransactions.<EClass>write(bm.bmModel, "CreateMetadataMember", (tx, pm) -> //$NON-NLS-1$
            {
                EObject owner = resolveMemberOwnerInTx(tx, topBmId, parts, feature, name);
                MemberChildSpec spec =
                    new MemberChildSpec(factory, elementType, owner, version, name, props,
                        synonymLanguage, feature);
                MdObject child = createMemberChild(spec, factoryInitialized);
                return child.eClass();
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error creating metadata member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = BmTransactions.forceExportToDisk(project, topFqn);
        return success(new SuccessInfo(normFqn, createdKind, name, persisted, props, synonymLanguage,
            null, normReport));
    }

    /**
     * Re-fetches the member's TOP object by {@code topBmId} inside the transaction and re-navigates
     * to the leaf's owner BY NAME (per the FQN {@code parts}), then asserts the new member does not
     * already exist on {@code feature}. Returns the resolved owner. Runs inside the write
     * transaction; performs no mutation.
     *
     * @throws RuntimeException if the top object or owner cannot be re-resolved, or the member exists
     */
    private static EObject resolveMemberOwnerInTx(IBmTransaction tx, long topBmId, String[] parts,
        EStructuralFeature feature, String name)
    {
        EObject top = (EObject)tx.getObjectById(topBmId);
        if (top == null)
        {
            throw new RuntimeException("Owner object not found in transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        EObject owner = MetadataNodeResolver.resolveOwnerInTx(top, parts);
        if (owner == null)
        {
            throw new RuntimeException("Could not re-navigate to the owner inside the transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        if (childByName(owner, feature, name) != null)
        {
            throw new RuntimeException("Member already exists: " + name); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        return owner;
    }

    /**
     * Immutable inputs for building one member child inside the write transaction, grouping the
     * arguments so the child-creation helpers stay within the parameter limit.
     */
    private static final class MemberChildSpec
    {
        final IModelObjectFactory factory;
        final EClass elementType;
        final EObject owner;
        final Version version;
        final String name;
        final Props props;
        final String synonymLanguage;
        final EStructuralFeature feature;

        MemberChildSpec(IModelObjectFactory factory, EClass elementType, EObject owner, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            Version version, String name, Props props, String synonymLanguage,
            EStructuralFeature feature)
        {
            this.factory = factory;
            this.elementType = elementType;
            this.owner = owner;
            this.version = version;
            this.name = name;
            this.props = props;
            this.synonymLanguage = synonymLanguage;
            this.feature = feature;
        }
    }

    /**
     * Creates and attaches one member child to its (tx-fetched) owner, dispatching to the
     * factory-initialized or the plain construction path. Performs the irreversible BM mutation; runs
     * inside the write transaction at the same point the inline code did.
     *
     * @return the created child
     */
    private static MdObject createMemberChild(MemberChildSpec spec, boolean factoryInitialized)
    {
        return factoryInitialized ? createFactoryInitializedChild(spec) : createPlainChild(spec);
    }

    /**
     * Factory-initialized member path (Form / Template / Recalculation): the parent-aware factory
     * wires the type's default content; a bare create is the fallback when the factory declines.
     * Performs the BM mutation inside the write transaction.
     *
     * @return the created and attached child
     */
    private static MdObject createFactoryInitializedChild(MemberChildSpec spec)
    {
        MdObject child = (MdObject)spec.factory.create(spec.elementType, spec.owner, spec.version);
        if (child == null)
        {
            child = (MdObject)EcoreUtil.create(spec.elementType);
        }
        child.setName(spec.name);
        if (child.getUuid() == null)
        {
            child.setUuid(UUID.randomUUID());
        }
        // The factory does not default a template's type; set the platform default.
        if (child instanceof BasicTemplate && ((BasicTemplate)child).getTemplateType() == null)
        {
            ((BasicTemplate)child).setTemplateType(TemplateType.SPREADSHEET_DOCUMENT);
        }
        applyScalarProps(child, spec.props, spec.synonymLanguage);
        addToFeature(spec.owner, spec.feature, child);
        spec.factory.fillDefaultReferences(child);
        return child;
    }

    /**
     * Plain member path (Attribute, Command, ...): a bare {@code EcoreUtil.create}, named, given a
     * UUID and attached. Performs the BM mutation inside the write transaction.
     *
     * @return the created and attached child
     */
    private static MdObject createPlainChild(MemberChildSpec spec)
    {
        MdObject child = (MdObject)EcoreUtil.create(spec.elementType);
        child.setName(spec.name);
        child.setUuid(UUID.randomUUID());
        applyScalarProps(child, spec.props, spec.synonymLanguage);
        addToFeature(spec.owner, spec.feature, child);
        return child;
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
                + "Button, Table (and Handler for events).").toJson(); //$NON-NLS-1$
        }
        if (!isValidIdentifier(ref.name))
        {
            return ToolResult.error("Invalid name '" + ref.name + "'. A name must start with a letter " //$NON-NLS-1$ //$NON-NLS-2$
                + "or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        FormMemberProps fmProps = new FormMemberProps();
        String propErr = parseFormMemberProperties(properties, kind, normReport, fmProps);
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

        final FormElementWriter.Kind fKind = kind;
        final String parent = fmProps.parentName;
        final String bind = fmProps.bindTarget;
        final String titleText = fmProps.titleVal;
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
            try // NOSONAR nested try is intentional (distinct resource/exception scopes)
            {
                titleLanguage = MetadataLanguageUtils.resolveSynonymLanguage(config, fmProps.titleVal,
                    fmProps.titleLang, "the title"); //$NON-NLS-1$
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error(e.getMessage()).toJson();
            }

            // A Table auto-generates one column per tabular-section attribute; the column names come
            // from the metadata owner (the form model alone cannot enumerate them), resolved here.
            final List<String> tableColumns = fKind == FormElementWriter.Kind.TABLE
                ? resolveTabularSectionColumns(config, ref.formPath, bind) : null;
            persisted = FormElementWriter.writeEditableForm(fctx, "CreateFormMember", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    String err = fKind == FormElementWriter.Kind.TABLE
                        ? FormElementWriter.createTable(formModel, ref.name, parent, bind, tableColumns,
                            titleLanguage, titleText, russianAutoNames, createdKind)
                        : FormElementWriter.createMember(formModel, fKind, ref.name, parent, bind,
                            titleLanguage, titleText, russianAutoNames, createdKind);
                    if (err != null)
                    {
                        throw new IllegalStateException(err);
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
            .put(McpKeys.ACTION, VAL_CREATED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("kind", createdKind[0] != null ? createdKind[0] : fKind.name()) //$NON-NLS-1$
            .put("name", ref.name) //$NON-NLS-1$
            .put(KEY_PERSISTED, persisted);
        normReport.addTo(formResult);
        return formResult.put(McpKeys.MESSAGE, "Created " + normFqn).toJson(); //$NON-NLS-1$
    }

    /**
     * Resolves the attribute names of the tabular section a table's {@code dataPath} addresses, so the
     * table can auto-generate one column per attribute - the way the designer does on a TS drop. Reads
     * the form owner (parsed from {@code formPath} = {@code Type.Object.forms.FormName}) and its tabular
     * section named by the {@code dataPath} tail ({@code Object.<TS>}). Best-effort: returns an empty
     * list when the owner / tabular section cannot be resolved (the table is still created, with just
     * the standard LineNumber column).
     */
    private static List<String> resolveTabularSectionColumns(Configuration config, String formPath,
        String dataPath)
    {
        List<String> columns = new java.util.ArrayList<>();
        if (dataPath == null || dataPath.isEmpty() || formPath == null)
        {
            return columns;
        }
        String[] seg = formPath.split("\\."); //$NON-NLS-1$
        if (seg.length < 2)
        {
            return columns;
        }
        MdObject owner = MetadataTypeUtils.findObject(config, seg[0], seg[1]);
        if (!(owner instanceof EObject))
        {
            return columns;
        }
        int dot = dataPath.indexOf('.');
        String tsName = dot >= 0 ? dataPath.substring(dot + 1) : dataPath;
        EStructuralFeature tsFeat = ((EObject)owner).eClass().getEStructuralFeature("tabularSections"); //$NON-NLS-1$
        if (tsFeat == null || !(((EObject)owner).eGet(tsFeat) instanceof List<?>))
        {
            return columns;
        }
        for (Object ts : (List<?>)((EObject)owner).eGet(tsFeat))
        {
            if (ts instanceof MdObject && tsName.equalsIgnoreCase(((MdObject)ts).getName()))
            {
                collectAttributeNames((EObject)ts, columns);
                break;
            }
        }
        return columns;
    }

    /** Appends the names of {@code ts}'s {@code attributes} to {@code out} (reflective, best-effort). */
    private static void collectAttributeNames(EObject ts, List<String> out)
    {
        EStructuralFeature attrFeat = ts.eClass().getEStructuralFeature("attributes"); //$NON-NLS-1$
        if (attrFeat != null && ts.eGet(attrFeat) instanceof List<?>)
        {
            for (Object attr : (List<?>)ts.eGet(attrFeat))
            {
                if (attr instanceof MdObject)
                {
                    out.add(((MdObject)attr).getName());
                }
            }
        }
    }

    /**
     * The parsed creation-time properties of a form member: the title (+ language), the parent to
     * nest under, and the binding target (a Field's dataPath/attribute, a Button's command, or a
     * Group's kind).
     */
    private static final class FormMemberProps
    {
        String titleVal;
        String titleLang;
        String parentName;
        String bindTarget;
    }

    /**
     * Parses the form-member {@code properties} array into {@code out}, applying the 'ё'->'е'
     * report to the title. Side-effect-free apart from filling {@code out}: returns a JSON error
     * string when a property is malformed, unsupported, or (for {@code type}) used on a non-Group,
     * or {@code null} on success.
     */
    private String parseFormMemberProperties(List<JsonObject> properties, FormElementWriter.Kind kind,
        MdNameNormalizer.Report normReport, FormMemberProps out)
    {
        for (JsonObject prop : properties)
        {
            String pName = asString(prop.get("name")); //$NON-NLS-1$
            if (pName == null || pName.isEmpty())
            {
                return ToolResult.error(ERR_PROPERTY_NEEDS_NAME).toJson();
            }
            switch (pName.toLowerCase())
            {
                case "title": //$NON-NLS-1$
                    out.titleVal = normReport.apply("title", asString(prop.get(KEY_VALUE))); //$NON-NLS-1$
                    out.titleLang = asString(prop.get(KEY_LANGUAGE));
                    break;
                case "parent": //$NON-NLS-1$
                    out.parentName = asString(prop.get(KEY_VALUE));
                    break;
                case "type": //$NON-NLS-1$
                    if (kind != FormElementWriter.Kind.GROUP)
                    {
                        return ToolResult.error("Property 'type' is supported at creation only for " //$NON-NLS-1$
                            + "a form Group (the group kind, e.g. Popup or Pages). Set other " //$NON-NLS-1$
                            + "elements' types via modify_metadata.").toJson(); //$NON-NLS-1$
                    }
                    out.bindTarget = asString(prop.get(KEY_VALUE));
                    break;
                case "datapath": //$NON-NLS-1$
                case "attribute": //$NON-NLS-1$
                case "command": //$NON-NLS-1$
                    out.bindTarget = asString(prop.get(KEY_VALUE));
                    break;
                default:
                    return ToolResult.error(ERR_PROPERTY_PREFIX + pName + "' is not supported for a form " //$NON-NLS-1$
                        + "element. This version applies: title (with optional language), parent " //$NON-NLS-1$
                        + "(nest a visual item), dataPath/attribute (a Field's bound attribute), " //$NON-NLS-1$
                        + "command (a Button's bound command), type (a Group's kind). Set other " //$NON-NLS-1$
                        + "properties via modify_metadata.").toJson(); //$NON-NLS-1$
            }
        }
        return null;
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
        boolean setAsDefault = JsonUtils.extractBooleanArgument(params, KEY_SET_AS_DEFAULT, false);
        boolean generateContent = JsonUtils.extractBooleanArgument(params, KEY_GENERATE_CONTENT, false);
        // The bound object fields to seed: null = omitted (-> the kind defaults, resolved downstream
        // where the owner TYPE token is known), an explicit (possibly empty) list = taken verbatim.
        // extractArrayArgument preserves that distinction (null for absent, empty list for []). #208.
        List<String> objectFields = JsonUtils.extractArrayArgument(params, KEY_OBJECT_FIELDS);
        boolean expectedNotExists = JsonUtils.extractBooleanArgument(params, KEY_EXPECTED_NOT_EXISTS, false);

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

        String existsErr = checkFormObjectNotExists(owner, ref.formName, normFqn, expectedNotExists);
        if (existsErr != null)
        {
            return existsErr;
        }

        // Resolve the synonym language now (needs the configuration); only when a synonym was given.
        final String synonymLanguage;
        try
        {
            synonymLanguage = MetadataLanguageUtils.resolveSynonymLanguage(config, props.synonym,
                props.language, "the synonym"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        FormObjectServices svc = resolveFormObjectServices(project, projectName);
        if (svc.hasError())
        {
            return svc.error;
        }

        final long ownerBmId = ((IBmObject)owner).bmGetId();
        final String ownerFqn = ((IBmObject)owner).bmGetFqn();
        final String formName = ref.formName;
        final String synonym = props.synonym;
        final String comment = props.comment;
        final boolean fSetAsDefault = setAsDefault;
        // The main Object attribute's value type is '<EnglishType>Object.<Name>' (e.g.
        // DocumentObject.Invoice); resolve the owner type token to its canonical English singular so the
        // token matches regardless of the FQN's (bilingual) type spelling.
        final String ownerEnglishType = MetadataTypeUtils.toEnglishSingular(ref.ownerType);
        // generateContent only applies to an object-form owner (Catalog / Document / ChartOf* /
        // ExchangePlan / BusinessProcess / Task / Report / DataProcessor) whose object form carries a
        // main Object attribute of type <Type>Object.<Name>. For a record-based owner (registers),
        // Constant, etc. there is no such attribute, so the seed is a no-op - reflect that in the result
        // (echo generateContent=false) so the caller learns the seed was not applicable (issue #208).
        final boolean effectiveGenerateContent =
            generateContent && MetadataTypeBuilder.hasObjectFormMainAttribute(ownerEnglishType);
        final boolean fGenerateContent = effectiveGenerateContent;
        // The bound object fields ride along to createForm, which resolves an omitted (null) list to the
        // per-kind defaults; ignored downstream when the seed itself does not apply. #208.
        final List<String> fObjectFields = objectFields;
        // The fallback predefined command-bar name follows the configuration script variant, like
        // the designer's default-name provider (FormObjectDefaultNameProvider).
        final boolean russianAutoNames = config.getScriptVariant() == ScriptVariant.RUSSIAN;

        final String contentFormFqn;
        try
        {
            contentFormFqn = BmTransactions.<String>write(svc.bmModel, "CreateFormObject", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txOwner = (EObject)tx.getObjectById(ownerBmId);
                if (!(txOwner instanceof MdObject))
                {
                    throw new RuntimeException("Owner object not found in transaction"); //$NON-NLS-1$
                }
                return FormElementWriter.createForm(tx, (MdObject)txOwner, formName, synonymLanguage,
                    synonym, comment, fSetAsDefault, svc.mdFactory, svc.formFactory, svc.fqnGenerator,
                    svc.version, russianAutoNames, fGenerateContent, ownerEnglishType, fObjectFields);
            });
        }
        catch (Exception e)
        {
            // A FormValidationException carries a READY actionable JSON error (e.g. an unknown
            // objectFields name listing the available sub-attributes - issue #208 round 2); surface it
            // verbatim, like the form-member / form-handler handlers, instead of the generic wrap.
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error creating form object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to create form: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist BOTH the content form's own Form.form (its FQN, generated inside the tx) and the owner
        // .mdo (which registers the new form in its <forms> and, when setAsDefault, the default-form ref).
        List<String> dirty = formObjectDirtyFqns(contentFormFqn, ownerFqn);
        boolean persisted = !dirty.isEmpty() && BmTransactions.forceExportToDisk(project, dirty);

        return buildFormObjectResult(normFqn, formName, persisted, setAsDefault,
            effectiveGenerateContent, props, synonymLanguage, normReport);
    }

    /**
     * Applies the duplicate / stale-intent precondition for a form OBJECT create (a 4-part form FQN
     * resolves on the owner's forms collection). Side-effect-free: returns a JSON error string when
     * the form already exists (a sharper message when {@code expectedNotExists}), or {@code null}
     * when it does not.
     */
    private static String checkFormObjectNotExists(MdObject owner, String formName, String normFqn,
        boolean expectedNotExists)
    {
        if (FormElementWriter.findOwnedForm(owner, formName) == null)
        {
            return null;
        }
        if (expectedNotExists)
        {
            return ToolResult.error("Precondition failed: you set expectedNotExists, but " + normFqn //$NON-NLS-1$
                + " already exists. Your snapshot is stale - re-read with get_metadata_objects, " //$NON-NLS-1$
                + "then update the existing node instead of creating a duplicate.").toJson(); //$NON-NLS-1$
        }
        return ToolResult.error("Node already exists: " + normFqn).toJson(); //$NON-NLS-1$
    }

    /**
     * The EDT services a form OBJECT create needs (the mdclass and form model-object factories, the
     * top-object FQN generator, the BM model and the platform version), or a ready-to-return JSON
     * error when any required service is unavailable.
     */
    private static final class FormObjectServices
    {
        IModelObjectFactory mdFactory;
        IModelObjectFactory formFactory;
        ITopObjectFqnGenerator fqnGenerator;
        IBmModel bmModel;
        Version version;
        /** Non-null when resolution failed: a JSON error to return verbatim. */
        String error;

        boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Resolves the services needed to create a form OBJECT for {@code project} (named
     * {@code projectName} for error messages), or returns a holder carrying a JSON error when any
     * required service is unavailable. Side-effect-free.
     */
    private static FormObjectServices resolveFormObjectServices(IProject project, String projectName)
    {
        FormObjectServices svc = new FormObjectServices();
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        svc.mdFactory = Activator.getDefault().getModelObjectFactory();
        svc.formFactory = Activator.getDefault().getFormModelObjectFactory();
        svc.fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (v8ProjectManager == null || svc.mdFactory == null || bmModelManager == null)
        {
            svc.error = ToolResult.error(ERR_SERVICES_UNAVAILABLE).toJson();
            return svc;
        }
        if (svc.fqnGenerator == null)
        {
            svc.error = ToolResult.error("ITopObjectFqnGenerator not available (needed to attach the " //$NON-NLS-1$
                + "content form under its canonical FQN)").toJson(); //$NON-NLS-1$
            return svc;
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            svc.error = ToolResult.error(ERR_NO_V8_PROJECT + projectName).toJson();
            return svc;
        }
        svc.version = v8Project.getVersion();
        svc.bmModel = bmModelManager.getModel(project);
        if (svc.bmModel == null)
        {
            svc.error = ToolResult.error(ERR_NO_BM_MODEL + projectName).toJson();
            return svc;
        }
        return svc;
    }

    /**
     * Builds the force-export dirty list for a form OBJECT create: the content form's own Form.form
     * (its FQN, generated inside the tx) and the owner .mdo (which registers the form), each when
     * present. Side-effect-free.
     */
    private static List<String> formObjectDirtyFqns(String contentFormFqn, String ownerFqn)
    {
        java.util.List<String> dirty = new java.util.ArrayList<>();
        if (contentFormFqn != null && !contentFormFqn.isEmpty())
        {
            dirty.add(contentFormFqn);
        }
        if (ownerFqn != null && !ownerFqn.isEmpty())
        {
            dirty.add(ownerFqn);
        }
        return dirty;
    }

    /** Builds the success JSON for a created form OBJECT. Side-effect-free: pure formatting. */
    private String buildFormObjectResult(String normFqn, String formName, boolean persisted, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        boolean setAsDefault, boolean generateContent, Props props, String synonymLanguage,
        MdNameNormalizer.Report normReport)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("kind", "Form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("name", formName) //$NON-NLS-1$
            .put(KEY_PERSISTED, persisted)
            .put(KEY_SET_AS_DEFAULT, setAsDefault)
            .put(KEY_GENERATE_CONTENT, generateContent);
        if (props.synonym != null && !props.synonym.isEmpty() && synonymLanguage != null)
        {
            result.put(KEY_SYNONYM, props.synonym).put(KEY_LANGUAGE, synonymLanguage);
        }
        normReport.addTo(result);
        return result.put(McpKeys.MESSAGE, "Created form " + normFqn //$NON-NLS-1$
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
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties, String callType)
    {
        String[] procNameHolder = new String[1];
        String propError = parseHandlerProperties(properties, procNameHolder);
        if (propError != null)
        {
            return propError;
        }
        String procName = procNameHolder[0];

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        final boolean extensionHandler = callType != null && !callType.trim().isEmpty();
        if (extensionHandler && !ExtensionOriginUtils.isExtensionProject(project))
        {
            return ToolResult.error("callType (extension event interception) is only valid in a " //$NON-NLS-1$
                + "configuration EXTENSION project. '" + projectName + "' is a base configuration: " //$NON-NLS-1$ //$NON-NLS-2$
                + "create a plain handler without callType, or target the extension project (and adopt " //$NON-NLS-1$
                + "the form there first via adopt_metadata_object).").toJson(); //$NON-NLS-1$
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

        final String eventName = ref.name;
        final String[] createdKind = new String[1];
        final boolean commandOwner =
            FormElementWriter.kindForToken(ref.itemKindToken) == FormElementWriter.Kind.COMMAND;
        final HandlerWriteSpec spec = new HandlerWriteSpec(ref, eventName, procName, version,
            langCode, callType, commandOwner, createdKind);
        final String formNotFound = handlerFormNotFound(normFqn, extensionHandler);

        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(project, config,
                ref.formPath, formNotFound);
            persisted = FormElementWriter.writeEditableForm(fctx, "CreateFormHandler", //$NON-NLS-1$
                (formModel, tx) -> writeHandler(formModel, spec));
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

        return buildHandlerResult(new HandlerResultInfo(ref, normFqn, eventName, procName, callType,
            extensionHandler, createdKind[0], persisted));
    }

    /**
     * Immutable inputs for writing a form event handler inside the write transaction, grouping the
     * arguments so {@link #writeHandler(EObject, HandlerWriteSpec)} stays within the parameter limit.
     */
    private static final class HandlerWriteSpec
    {
        final FormElementWriter.FormMemberRef ref;
        final String eventName;
        final String procName;
        final Version version;
        final String langCode;
        final String callType;
        final boolean commandOwner;
        final String[] createdKind;

        HandlerWriteSpec(FormElementWriter.FormMemberRef ref, String eventName, String procName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            Version version, String langCode, String callType, boolean commandOwner,
            String[] createdKind)
        {
            this.ref = ref;
            this.eventName = eventName;
            this.procName = procName;
            this.version = version;
            this.langCode = langCode;
            this.callType = callType;
            this.commandOwner = commandOwner;
            this.createdKind = createdKind;
        }
    }

    /**
     * Builds the "form not found" advisory for a handler create. For an EXTENSION event handler the
     * most likely cause is that the base form was never adopted, so the message points at
     * adopt_metadata_object. Side-effect-free.
     */
    private static String handlerFormNotFound(String normFqn, boolean extensionHandler)
    {
        String formNotFound = "Form not found for '" + normFqn + "'. Address a form as " //$NON-NLS-1$ //$NON-NLS-2$
            + "'Type.Object.Form.FormName' or 'CommonForm.FormName'."; //$NON-NLS-1$
        if (extensionHandler)
        {
            formNotFound += " If this is a base form, adopt it into the extension first via " //$NON-NLS-1$
                + "adopt_metadata_object."; //$NON-NLS-1$
        }
        return formNotFound;
    }

    /**
     * Resolves the handler's container (form root, the named item, or the form command) on the
     * re-fetched content form and writes the handler. Form-level handlers attach to the form root;
     * item-level handlers ({@code ...Form.F.Field.Item.Handler.Event}) to the named item; a COMMAND
     * ref ({@code ...Form.F.Command.C.Handler.Action}) to the form command. Performs the BM mutation
     * inside the write transaction.
     *
     * @throws IllegalStateException if the container is missing or the writer rejects the event
     */
    private static void writeHandler(EObject formModel, HandlerWriteSpec spec)
    {
        EObject container = FormElementWriter.resolveHandlerContainer(formModel, spec.ref);
        if (container == null)
        {
            throw new IllegalStateException(spec.commandOwner
                ? "Form command not found: " + spec.ref.itemName //$NON-NLS-1$
                    + ". Create the command first, then add the handler." //$NON-NLS-1$
                : "Form item not found: " + spec.ref.itemName //$NON-NLS-1$
                    + ". Create the item first, then add the handler."); //$NON-NLS-1$
        }
        String err = FormElementWriter.createHandler(container, spec.eventName, spec.procName,
            spec.version, spec.langCode, spec.callType, spec.createdKind);
        if (err != null)
        {
            throw new IllegalStateException(err);
        }
    }

    /**
     * Parses the handler {@code properties} array, resolving the optional BSL procedure name from a
     * {@code procedure} / {@code handler} property into {@code procNameHolder[0]}. Side-effect-free:
     * returns a JSON error string when a property is malformed or unsupported, or {@code null} on
     * success (the same error JSON the caller would otherwise have returned inline).
     */
    private String parseHandlerProperties(List<JsonObject> properties, String[] procNameHolder)
    {
        for (JsonObject prop : properties)
        {
            String pName = asString(prop.get("name")); //$NON-NLS-1$
            if (pName == null || pName.isEmpty())
            {
                return ToolResult.error(ERR_PROPERTY_NEEDS_NAME).toJson();
            }
            switch (pName.toLowerCase())
            {
                case "procedure": //$NON-NLS-1$
                case "handler": //$NON-NLS-1$
                    procNameHolder[0] = asString(prop.get(KEY_VALUE));
                    break;
                default:
                    return ToolResult.error(ERR_PROPERTY_PREFIX + pName + "' is not supported for a form " //$NON-NLS-1$
                        + "handler. Use 'procedure' (the BSL handler procedure name; defaults to the " //$NON-NLS-1$
                        + "event name).").toJson(); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Immutable description of a created form event handler, grouping the result inputs so
     * {@link #buildHandlerResult(HandlerResultInfo)} takes one holder instead of a long argument
     * list.
     */
    private static final class HandlerResultInfo
    {
        final FormElementWriter.FormMemberRef ref;
        final String normFqn;
        final String eventName;
        final String fProc;
        final String callType;
        final boolean extensionHandler;
        /** The kind reported by the writer ({@code null} =&gt; {@code "EventHandler"}). */
        final String createdKind;
        final boolean persisted;

        HandlerResultInfo(FormElementWriter.FormMemberRef ref, String normFqn, String eventName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String fProc, String callType, boolean extensionHandler, String createdKind,
            boolean persisted)
        {
            this.ref = ref;
            this.normFqn = normFqn;
            this.eventName = eventName;
            this.fProc = fProc;
            this.callType = callType;
            this.extensionHandler = extensionHandler;
            this.createdKind = createdKind;
            this.persisted = persisted;
        }
    }

    /**
     * Builds the success JSON for a created form event handler (location string, message and result
     * fields). Side-effect-free: pure formatting of the already-applied change.
     */
    private String buildHandlerResult(HandlerResultInfo info)
    {
        String location = info.ref.isItemLevel()
            ? info.ref.formPath + "." + info.ref.itemName : info.ref.formPath; //$NON-NLS-1$
        String effectiveProc =
            (info.fProc == null || info.fProc.isEmpty()) ? info.eventName : info.fProc;
        String message;
        if (info.extensionHandler)
        {
            message = "Created extension (" + info.callType + ") handler for event '" + info.eventName //$NON-NLS-1$ //$NON-NLS-2$
                + "' on " + location + ". Add the BSL procedure '" + effectiveProc //$NON-NLS-1$ //$NON-NLS-2$
                + "' to the extension form module via write_module_source."; //$NON-NLS-1$
        }
        else
        {
            message = "Created handler for event '" + info.eventName + "' on " + location; //$NON-NLS-1$ //$NON-NLS-2$
        }
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put("fqn", info.normFqn) //$NON-NLS-1$
            .put("kind", info.createdKind != null ? info.createdKind : "EventHandler") //$NON-NLS-1$ //$NON-NLS-2$
            .put("name", info.eventName) //$NON-NLS-1$
            .put(KEY_PERSISTED, info.persisted)
            .put(McpKeys.MESSAGE, message);
        if (info.extensionHandler)
        {
            result.put(KEY_CALL_TYPE, info.callType);
        }
        return result.toJson();
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

    /**
     * The EDT services a BM-backed create needs (the model-object factory, the BM model, the platform
     * version), or a ready-to-return JSON error when any is unavailable. Resolved once by
     * {@link #resolveBmContext} so the creation methods stay free of the repeated service-lookup
     * guards.
     */
    private static final class BmContext
    {
        IModelObjectFactory factory;
        IBmModel bmModel;
        Version version;
        /** Non-null when resolution failed: a JSON error to return verbatim. */
        String error;

        boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Resolves the model-object factory, BM model and platform version for {@code project} (named
     * {@code projectName} for error messages), or returns a holder carrying a JSON error when any
     * service is unavailable. Side-effect-free.
     */
    private static BmContext resolveBmContext(IProject project, String projectName)
    {
        BmContext ctx = new BmContext();
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        ctx.factory = Activator.getDefault().getModelObjectFactory();
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (v8ProjectManager == null || ctx.factory == null || bmModelManager == null)
        {
            ctx.error = ToolResult.error(ERR_SERVICES_UNAVAILABLE).toJson();
            return ctx;
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            ctx.error = ToolResult.error(ERR_NO_V8_PROJECT + projectName).toJson();
            return ctx;
        }
        ctx.version = v8Project.getVersion();
        ctx.bmModel = bmModelManager.getModel(project);
        if (ctx.bmModel == null)
        {
            ctx.error = ToolResult.error(ERR_NO_BM_MODEL + projectName).toJson();
            return ctx;
        }
        return ctx;
    }

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
                return ToolResult.error(ERR_PROPERTY_NEEDS_NAME).toJson();
            }
            String value = asString(prop.get(KEY_VALUE));
            switch (name.toLowerCase())
            {
                case KEY_SYNONYM:
                    out.synonym = normReport.apply(KEY_SYNONYM, value);
                    out.language = asString(prop.get(KEY_LANGUAGE));
                    break;
                case "comment": //$NON-NLS-1$
                    out.comment = normReport.apply("comment", value); //$NON-NLS-1$
                    break;
                default:
                    return ToolResult.error(ERR_PROPERTY_PREFIX + name + "' is not supported yet in " //$NON-NLS-1$
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
            throw new IllegalStateException("Containment feature '" + feature.getName() + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<EObject>)value).add(child);
    }

    @SuppressWarnings("unchecked")
    private static void addToCollection(Configuration cfg, String refName, MdObject newObject)
    {
        Object collection = cfg.eGet(cfg.eClass().getEStructuralFeature(refName));
        if (!(collection instanceof EList))
        {
            throw new IllegalStateException("Configuration feature '" + refName + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<MdObject>)collection).add(newObject);
    }

    /** Extracts the {@code Type.Name} top-object FQN from a (normalized) full-name FQN. */
    private static String topFqn(String normFqn)
    {
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        return parts.length >= 2 ? parts[0] + "." + parts[1] : normFqn; //$NON-NLS-1$
    }

    /**
     * Immutable description of a successfully created mdclass node, grouping the success-result
     * inputs so {@link #success(SuccessInfo)} takes one holder instead of a long argument list.
     */
    private static final class SuccessInfo
    {
        final String fqn;
        final EClass kind;
        final String name;
        final boolean persisted;
        final Props props;
        final String synonymLanguage;
        /** Type-specific options to echo back; {@code null} for a member create. */
        final TypeSpecific typeSpecific;
        final MdNameNormalizer.Report normReport;

        SuccessInfo(String fqn, EClass kind, String name, boolean persisted, Props props, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String synonymLanguage, TypeSpecific typeSpecific, MdNameNormalizer.Report normReport)
        {
            this.fqn = fqn;
            this.kind = kind;
            this.name = name;
            this.persisted = persisted;
            this.props = props;
            this.synonymLanguage = synonymLanguage;
            this.typeSpecific = typeSpecific;
            this.normReport = normReport;
        }
    }

    private String success(SuccessInfo info)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put("fqn", info.fqn) //$NON-NLS-1$
            .put("kind", info.kind != null ? info.kind.getName() : null) //$NON-NLS-1$
            .put("name", info.name) //$NON-NLS-1$
            .put(KEY_PERSISTED, info.persisted);
        if (info.props.synonym != null && !info.props.synonym.isEmpty() && info.synonymLanguage != null)
        {
            result.put(KEY_SYNONYM, info.props.synonym).put(KEY_LANGUAGE, info.synonymLanguage);
        }
        if (info.typeSpecific != null)
        {
            if (info.typeSpecific.commonModuleFlags != null)
            {
                result.put(KEY_COMMON_MODULE_KIND, info.typeSpecific.commonModuleFlags.kind.token());
            }
            if (info.typeSpecific.xdtoNamespace != null)
            {
                result.put(KEY_TARGET_NAMESPACE, info.typeSpecific.xdtoNamespace);
            }
        }
        info.normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, "Created " + info.fqn) //$NON-NLS-1$
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
        if (leaf.equals(normalizedLeaf))
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
                String requested = JsonUtils.extractStringArgument(params, KEY_TARGET_NAMESPACE);
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

        /**
         * Immutable bundle of the eight CommonModule flag features, in the canonical order
         * clientManaged, clientOrdinary, server, serverCall, externalConnection, global, privileged,
         * reuse. Groups them into a single value so the {@link CommonModuleFlags} constructor takes
         * one holder instead of a long boolean argument list.
         */
        static final class FlagSet
        {
            final boolean clientManagedApplication;
            final boolean clientOrdinaryApplication;
            final boolean server;
            final boolean serverCall;
            final boolean externalConnection;
            final boolean global;
            final boolean privileged;
            final ReturnValuesReuse returnValuesReuse;

            FlagSet(boolean clientManagedApplication, boolean clientOrdinaryApplication, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                boolean server, boolean serverCall, boolean externalConnection, boolean global,
                boolean privileged, ReturnValuesReuse returnValuesReuse)
            {
                this.clientManagedApplication = clientManagedApplication;
                this.clientOrdinaryApplication = clientOrdinaryApplication;
                this.server = server;
                this.serverCall = serverCall;
                this.externalConnection = externalConnection;
                this.global = global;
                this.privileged = privileged;
                this.returnValuesReuse = returnValuesReuse;
            }
        }

        private CommonModuleFlags(CommonModuleKind kind, FlagSet flags)
        {
            this.kind = kind;
            this.clientManagedApplication = flags.clientManagedApplication;
            this.clientOrdinaryApplication = flags.clientOrdinaryApplication;
            this.server = flags.server;
            this.serverCall = flags.serverCall;
            this.externalConnection = flags.externalConnection;
            this.global = flags.global;
            this.privileged = flags.privileged;
            this.returnValuesReuse = flags.returnValuesReuse;
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
            CommonModuleKind kind =
                resolveKind(JsonUtils.extractStringArgument(params, KEY_COMMON_MODULE_KIND));

            boolean serverCall = JsonUtils.extractBooleanArgument(params, "serverCall", false); //$NON-NLS-1$
            boolean privileged = JsonUtils.extractBooleanArgument(params, "privileged", false); //$NON-NLS-1$
            ReturnValuesReuse reuse = parseReuse(JsonUtils.extractStringArgument(params, "returnValuesReuse")); //$NON-NLS-1$

            // ServerCall kind is shorthand for the Server kind + the server-call flag.
            if (kind == CommonModuleKind.SERVER_CALL)
            {
                serverCall = true;
            }

            validateModifiers(kind, serverCall, privileged, reuse);

            boolean cached = reuse == ReturnValuesReuse.DURING_SESSION;
            return toCanonicalFlags(kind, serverCall, privileged, cached);
        }

        /**
         * Resolves the {@code commonModuleKind} token (defaulting to {@code Server} when blank) to
         * its {@link CommonModuleKind}. Side-effect-free.
         *
         * @throws IllegalArgumentException if the token is non-blank but unknown
         */
        private static CommonModuleKind resolveKind(String kindToken)
        {
            if (kindToken == null || kindToken.trim().isEmpty())
            {
                return CommonModuleKind.SERVER;
            }
            CommonModuleKind kind = CommonModuleKind.fromToken(kindToken.trim());
            if (kind == null)
            {
                throw new IllegalArgumentException("Unknown commonModuleKind '" + kindToken //$NON-NLS-1$
                    + "'. Supported: " + CommonModuleKind.quotedList() + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return kind;
        }

        /**
         * Cross-flag validation with clear, actionable messages. Rejects modifier/kind combinations
         * that have no standards-compliant (validator-accepted) flag set. Side-effect-free.
         *
         * @throws IllegalArgumentException if the requested combination is invalid
         */
        private static void validateModifiers(CommonModuleKind kind, boolean serverCall,
            boolean privileged, ReturnValuesReuse reuse)
        {
            validateServerCall(kind, serverCall);
            validatePrivileged(kind, serverCall, privileged, reuse);
            validateReuse(kind, reuse);
        }

        /**
         * Rejects {@code serverCall} on a non-server kind. Side-effect-free.
         *
         * @throws IllegalArgumentException if serverCall is set on a kind that is not server-side
         */
        private static void validateServerCall(CommonModuleKind kind, boolean serverCall)
        {
            boolean serverSideKind = kind == CommonModuleKind.SERVER
                || kind == CommonModuleKind.SERVER_CALL
                || kind == CommonModuleKind.CLIENT_SERVER;
            if (serverCall && !serverSideKind)
            {
                // Covers every non-server kind, including 'Global' (a global module is a
                // client module here and can never be a server-call target); the message
                // names the offending kind, with an extra hint for the Global case.
                throw new IllegalArgumentException("serverCall requires a server kind " //$NON-NLS-1$
                    + "('Server', 'ServerCall' or 'ClientServer'); it is not valid for kind '" //$NON-NLS-1$
                    + kind.token() + "'." //$NON-NLS-1$
                    + (kind == CommonModuleKind.GLOBAL
                        ? " A 'Global' module is a client module and cannot be a server-call target." //$NON-NLS-1$
                        : "")); //$NON-NLS-1$
            }
        }

        /**
         * Rejects {@code privileged} unless it is the lone modifier on the {@code Server} kind.
         * Side-effect-free.
         *
         * @throws IllegalArgumentException if privileged is combined with a non-Server kind,
         *             serverCall or returnValuesReuse
         */
        private static void validatePrivileged(CommonModuleKind kind, boolean serverCall,
            boolean privileged, ReturnValuesReuse reuse)
        {
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
        }

        /**
         * Rejects a {@code returnValuesReuse} that has no validator-accepted module: only DontUse,
         * or DuringSession on a kind with a cached variant (Server, ServerCall, ClientManaged,
         * ClientOrdinary), is allowed. DuringRequest and reuse on Global/ClientServer have no
         * canonical combo. Side-effect-free.
         *
         * @throws IllegalArgumentException if the reuse/kind combination has no canonical combo
         */
        private static void validateReuse(CommonModuleKind kind, ReturnValuesReuse reuse)
        {
            if (reuse == ReturnValuesReuse.DONT_USE)
            {
                return;
            }
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

        /**
         * Maps a validated {@code (kind, modifiers)} combination to its canonical,
         * validator-accepted flag set. Side-effect-free.
         *
         * <p>Flags order: clientManaged, clientOrdinary, server, serverCall, externalConnection,
         * global, privileged, reuse.
         */
        private static CommonModuleFlags toCanonicalFlags(CommonModuleKind kind, boolean serverCall,
            boolean privileged, boolean cached)
        {
            ReturnValuesReuse reuse =
                cached ? ReturnValuesReuse.DURING_SESSION : ReturnValuesReuse.DONT_USE;
            switch (kind)
            {
            case SERVER:
            case SERVER_CALL:
                if (privileged)
                {
                    // SERVER_FULL_ACCESS: server-only, privileged.
                    return new CommonModuleFlags(kind, new FlagSet(false, false, true, false, false,
                        false, true, ReturnValuesReuse.DONT_USE));
                }
                if (serverCall)
                {
                    // SERVER_CALL / SERVER_CALL_CACHED: server + server call, no client flags.
                    return new CommonModuleFlags(kind, new FlagSet(false, false, true, true, false,
                        false, false, reuse));
                }
                // SERVER / SERVER_CACHED: client ordinary + external connection + server.
                return new CommonModuleFlags(kind, new FlagSet(false, true, true, false, true, false,
                    false, reuse));

            case CLIENT_MANAGED:
            case CLIENT_ORDINARY:
                // CLIENT / CLIENT_CACHED: both client flags set (the canonical client module).
                return new CommonModuleFlags(kind, new FlagSet(true, true, false, false, false, false,
                    false, reuse));

            case CLIENT_SERVER:
                // CLIENT_SERVER: both client flags + server + external connection.
                return new CommonModuleFlags(kind, new FlagSet(true, true, true, false, true, false,
                    false, ReturnValuesReuse.DONT_USE));

            case GLOBAL:
                // CLIENT_GLOBAL: both client flags + global.
                return new CommonModuleFlags(kind, new FlagSet(true, true, false, false, false, true,
                    false, ReturnValuesReuse.DONT_USE));

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
