/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.CommonAttributeContentWriter;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DcsWriter;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.ExchangePlanContentWriter;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.MetadataTypeBuilder;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ReferenceMembershipWriter;
import com.ditrix.edt.mcp.server.utils.RoleRightsWriter;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter;
import com.ditrix.edt.mcp.server.utils.StyleValueBuilder;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Sets one or more properties of a metadata node (a top-level object or a member) addressed by a 1C
 * full-name FQN. Every property is VALIDATED before any write: an unknown / non-assignable property
 * is rejected with the list of assignable properties, and an out-of-range value (e.g. an enum value
 * that is not a valid literal) is rejected with the allowed values - so the error is actionable.
 * Replaces the former {@code set_metadata_property} (which set only Comment / Synonym).
 *
 * <p>Renaming is out of scope: setting the {@code name} property is refused with a pointer to
 * {@code rename_metadata_object}, because a Name change must cascade across all references.</p>
 */
public class ModifyMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "modify_metadata"; //$NON-NLS-1$

    /** Output result key: names of the properties that were set. */
    private static final String KEY_APPLIED = "applied"; //$NON-NLS-1$

    /** Output result key: whether the change was exported to disk. */
    private static final String KEY_PERSISTED = "persisted"; //$NON-NLS-1$

    /** Output value for {@link McpKeys#ACTION}: the node was modified. */
    private static final String VAL_MODIFIED = "modified"; //$NON-NLS-1$

    /** Property/JSON key: the value of a property entry. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** Error message prefix for an unresolved form FQN. */
    private static final String ERR_FORM_NOT_FOUND_PREFIX = "Form not found for '"; //$NON-NLS-1$

    /** Payload / output key: the ROLE rights array. */
    private static final String KEY_RIGHTS = "rights"; //$NON-NLS-1$

    /** Payload / output key: the ROLE RLS templates array. */
    private static final String KEY_TEMPLATES = "templates"; //$NON-NLS-1$

    /** Payload / output key: the ROLE properties object. */
    private static final String KEY_ROLE_PROPERTIES = "roleProperties"; //$NON-NLS-1$

    /** Payload / output key: the membership content array / counts object. */
    private static final String KEY_CONTENT = "content"; //$NON-NLS-1$

    /** Payload / output key: the SpreadsheetDocument template content spec / applied-counts object. */
    private static final String KEY_TEMPLATE = "template"; //$NON-NLS-1$

    /** Actual-kind stem in the "payload only for X FQN" refusals (java:S1192). */
    private static final String ERR_IS_A = "is a "; //$NON-NLS-1$

    /** Shared BM bootstrap failure messages (java:S1192). */
    private static final String ERR_NO_BM_MANAGER = "IBmModelManager not available"; //$NON-NLS-1$
    private static final String ERR_NO_BM_MODEL = "BM model not available for project: "; //$NON-NLS-1$

    /** Payload / output key: the Report Data Composition Schema (СКД) content spec / applied-counts object. */
    private static final String KEY_DCS = "dcs"; //$NON-NLS-1$

    /**
     * The 1C platform's default name for a Report's main Data Composition Schema template (the name the
     * designer pre-fills when a report gains a DCS), used when the FIRST {@code dcs} write must lazily
     * materialize a report's missing DCS template so the result matches a designer-created report.
     */
    // ОсновнаяСхемаКомпоновкиДанных - a persisted/matched Cyrillic identifier is written with Java Unicode
    // escapes (below) so a non-UTF-8 Tycho build cannot corrupt it (matches MetadataTypeUtils' /
    // BslSyntaxChecker's convention).
    private static final String DEFAULT_DCS_TEMPLATE_NAME =
        "\u041E\u0441\u043D\u043E\u0432\u043D\u0430\u044F\u0421\u0445\u0435\u043C\u0430\u041A\u043E" //$NON-NLS-1$
            + "\u043C\u043F\u043E\u043D\u043E\u0432\u043A\u0438\u0414\u0430\u043D\u043D\u044B\u0445"; //$NON-NLS-1$

    /** Output count key: members attached. */
    private static final String KEY_ADDED = "added"; //$NON-NLS-1$

    /** Output count key: members detached. */
    private static final String KEY_REMOVED = "removed"; //$NON-NLS-1$

    /** The form attribute's value-type feature / property alias. */
    private static final String PROP_VALUE_TYPE = "valueType"; //$NON-NLS-1$

    /** Confirmation-message prefix for a completed modify. */
    private static final String MSG_MODIFIED_PREFIX = "Modified "; //$NON-NLS-1$

    /** Error-message fragment between an FQN and its EClass name (e.g. "'X' is a Catalog"). */
    private static final String MSG_IS_A = "' is a "; //$NON-NLS-1$

    /** Confirmation-message fragment before a removed count. */
    private static final String MSG_REMOVED_COUNT = ", removed: "; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set properties of a metadata node (object or member, including a FORM member - item / " //$NON-NLS-1$
            + "attribute / command) addressed by a 1C full-name FQN, as " //$NON-NLS-1$
            + "properties=[{name, value, language?}]. Each property is validated (it must be " //$NON-NLS-1$
            + "assignable, and an enum value must be one of the allowed literals) with an actionable " //$NON-NLS-1$
            + "error. Move/reorder a FORM ITEM with the 'parent' (a group name, 'AutoCommandBar' for " //$NON-NLS-1$
            + "the form's command bar, or the form name for the form root) and/or 'position' " //$NON-NLS-1$
            + "('first'/'last'/'before:<name>'/'after:<name>'/index) " //$NON-NLS-1$
            + "properties. REBIND a form event handler's procedure with a 'procedure' property on a " //$NON-NLS-1$
            + "Handler FQN, or re-point a Button at a different form command with a 'command' property. " //$NON-NLS-1$
            + "Set a StyleItem's value with a 'value' property: a Color " //$NON-NLS-1$
            + "{value:{color:{red:255,green:0,blue:0}}} (or {color:'auto'}) or a Font " //$NON-NLS-1$
            + "{value:{font:{faceName:'Arial',height:12,bold:true}}}. " //$NON-NLS-1$
            + "Give a form list FORM ATTRIBUTE a custom dynamic-list query with a 'queryText' " //$NON-NLS-1$
            + "property (and 'customQuery' true/false, plus an optional 'mainTable' object FQN): this " //$NON-NLS-1$
            + "turns the attribute into a DynamicList and lets EDT auto-fill the available fields from " //$NON-NLS-1$
            + "the query (no manual XML; output a column with create_metadata Field dataPath " //$NON-NLS-1$
            + "'List.<field>'). " //$NON-NLS-1$
            + "For a ROLE FQN ('Role.Name'), set access rights instead of 'properties': 'rights' " //$NON-NLS-1$
            + "(per-object right VALUES + optional per-field RLS restriction conditions), 'templates' " //$NON-NLS-1$
            + "(RLS restriction templates: add/edit/delete) and 'roleProperties' (the three role " //$NON-NLS-1$
            + "booleans). Read a role's rights matrix with get_metadata_details on the Role FQN. " //$NON-NLS-1$
            + "Edit a structured membership LIST with 'content' instead of 'properties', dispatched by " //$NON-NLS-1$
            + "the FQN's kind: a COMMON ATTRIBUTE's owners ('CommonAttribute.Name'), an EXCHANGE PLAN's " //$NON-NLS-1$
            + "content objects ('ExchangePlan.Name'), a CATALOG's owners ('Catalog.Name'), a " //$NON-NLS-1$
            + "DOCUMENT's register records / движения ('Document.Name') or a SUBSYSTEM's content " //$NON-NLS-1$
            + "objects ('Subsystem.Name', including a nested 'Subsystem.Parent.Subsystem.Child'). " //$NON-NLS-1$
            + "'content'=[{op?:'add'|'remove' " //$NON-NLS-1$
            + "(default add), metadata:'Catalog.X', use?, autoRecord?}] adds a member (idempotent) or " //$NON-NLS-1$
            + "removes one by its metadata FQN; a CommonAttribute entry takes 'use' " //$NON-NLS-1$
            + "('Use'|'DontUse'|'Auto'), an ExchangePlan entry takes 'autoRecord' ('Allow'|'Deny'), and " //$NON-NLS-1$
            + "a Catalog owner / Document register record / Subsystem content object is a plain " //$NON-NLS-1$
            + "reference (no flag). " //$NON-NLS-1$
            + "AUTHOR a SpreadsheetDocument (print form / макет) TEMPLATE's content with a 'template' " //$NON-NLS-1$
            + "payload instead of 'properties' on a template FQN (a common template " //$NON-NLS-1$
            + "'CommonTemplate.<Name>' or an object-owned template '<Type>.<Owner>.Template.<Name>'): " //$NON-NLS-1$
            + "'template'={cells:[{row, col, text?|parameter?, bold?, fontSize?, hAlign?, vAlign?, " //$NON-NLS-1$
            + "wrap?}], merges:[{fromRow, fromCol, toRow, toCol}], areas:[{name, fromRow, fromCol, " //$NON-NLS-1$
            + "toRow, toCol}], columnWidths:[{col, width}], rowHeights:[{row, height}]} writes the " //$NON-NLS-1$
            + "cells (text or a print-time parameter) with formatting, merged ranges, named areas and " //$NON-NLS-1$
            + "column / row sizes into the template's spreadsheet content; render the result with " //$NON-NLS-1$
            + "get_template_screenshot. " //$NON-NLS-1$
            + "AUTHOR a REPORT's Data Composition Schema (СКД / .dcs) with a 'dcs' payload instead of " //$NON-NLS-1$
            + "'properties' on a Report FQN ('Report.<Name>'): 'dcs'={dataSources:[{name, type?}], " //$NON-NLS-1$
            + "dataSets:[{name, type:'query', query, dataSource?, autoFillFields?, fields:[{name?, " //$NON-NLS-1$
            + "dataPath, title?, role?}]}], parameters:[{name, valueType?, title?, use?}]} builds the " //$NON-NLS-1$
            + "report's main schema (query data sets + fields + schema parameters), creating the DCS if " //$NON-NLS-1$
            + "the report has none. " //$NON-NLS-1$
            + "Discover assignable properties + allowed values with " //$NON-NLS-1$
            + "get_metadata_details(assignable:true). To rename, use rename_metadata_object. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('modify_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to modify (required), e.g. 'Catalog.Products' or " //$NON-NLS-1$
                + "'Catalog.Products.Attribute.Weight' (type / kind tokens may be English or Russian; " //$NON-NLS-1$
                + "the Name parts are the programmatic Name).", true) //$NON-NLS-1$
            .objectArrayProperty("properties", //$NON-NLS-1$
                "Properties to set, as [{name, value, language?}]. 'name' is " //$NON-NLS-1$
                + "the property name (e.g. 'comment', 'synonym', 'indexing'); 'value' is the new " //$NON-NLS-1$
                + "value; 'language' is the code for a synonym (default: config default). Required " //$NON-NLS-1$
                + "unless the FQN is a Role and a role payload (rights / templates / roleProperties) " //$NON-NLS-1$
                + "is given.") //$NON-NLS-1$
            .objectArrayProperty(KEY_RIGHTS,
                "ROLE only: per-object access rights to set, as [{object, right, value?, rls?, " //$NON-NLS-1$
                + "rlsFields?}]. 'object' is a metadata FQN (e.g. 'Catalog.Products' or the Russian " //$NON-NLS-1$
                + "'Справочник.Товары'); 'right' is a bilingual right name (e.g. 'Read'/'Чтение', " //$NON-NLS-1$
                + "'Update'/'Изменение'); 'value' is 'set' (allowed, default) / 'unset' (denied) / " //$NON-NLS-1$
                + "'provided' (default/inherited), or a boolean (true=set, false=unset). 'rls' is an " //$NON-NLS-1$
                + "optional Row-Level-Security restriction condition (1C query text); 'rlsFields' is " //$NON-NLS-1$
                + "an optional array of field names the RLS applies to (omit / empty = whole-object " //$NON-NLS-1$
                + "restriction).") //$NON-NLS-1$
            .objectArrayProperty(KEY_TEMPLATES,
                "ROLE only: RLS restriction templates to change, as [{op?, name, condition?}]. 'op' is " //$NON-NLS-1$
                + "'add' (default) / 'edit' / 'delete'; 'name' is the template name; 'condition' is " //$NON-NLS-1$
                + "the RLS restriction text (required for add/edit).") //$NON-NLS-1$
            .objectProperty(KEY_ROLE_PROPERTIES,
                "ROLE only: the three role properties, as optional booleans {setForNewObjects, " //$NON-NLS-1$
                + "setForAttributesByDefault, independentRightsOfChildObjects}. Only supplied flags " //$NON-NLS-1$
                + "are changed.") //$NON-NLS-1$
            .objectArrayProperty(KEY_CONTENT,
                "Members to attach / detach in a structured membership list, dispatched by the FQN's " //$NON-NLS-1$
                + "kind (a COMMON ATTRIBUTE's owners, an EXCHANGE PLAN's content objects, a CATALOG's " //$NON-NLS-1$
                + "owners, a DOCUMENT's register records, a SUBSYSTEM's content objects), as [{op?, " //$NON-NLS-1$
                + "metadata, use?, autoRecord?}]. 'op' " //$NON-NLS-1$
                + "is 'add' (default) / 'remove'; 'metadata' is the member object FQN (e.g. " //$NON-NLS-1$
                + "'Catalog.Products' or the Russian 'Справочник.Товары' - only the type token is " //$NON-NLS-1$
                + "bilingual). 'use' (CommonAttribute only, add only, default 'Use') is 'Use' / " //$NON-NLS-1$
                + "'DontUse' / 'Auto'; 'autoRecord' (ExchangePlan only, add only) is 'Allow' / 'Deny' " //$NON-NLS-1$
                + "(omit to keep the platform default). A Catalog owner, a Document register record and " //$NON-NLS-1$
                + "a Subsystem content object are plain references (no flag). Adding is idempotent (a " //$NON-NLS-1$
                + "re-added CommonAttribute owner " //$NON-NLS-1$
                + "has its 'use' updated, a re-added ExchangePlan object its 'autoRecord'; a re-added " //$NON-NLS-1$
                + "plain reference is a no-op). Valid only for a CommonAttribute / ExchangePlan / " //$NON-NLS-1$
                + "Catalog / Document / Subsystem FQN (a Subsystem FQN may be nested); cannot be " //$NON-NLS-1$
                + "combined with 'properties'.") //$NON-NLS-1$
            .objectProperty(KEY_TEMPLATE,
                "SpreadsheetDocument (print form / макет) TEMPLATE FQN only: the spreadsheet content to " //$NON-NLS-1$
                + "author, instead of 'properties'. An object with any of: 'cells' [{row, col (both " //$NON-NLS-1$
                + "0-based, required), text? OR parameter? (a print-time parameter name), bold?, " //$NON-NLS-1$
                + "fontSize?, hAlign? ('Left'/'Center'/'Right'/'Auto'/'Width'), vAlign? " //$NON-NLS-1$
                + "('Top'/'Center'/'Bottom'), wrap? (true word-wraps the cell text)}]; 'merges' " //$NON-NLS-1$
                + "[{fromRow, fromCol, toRow, toCol}] merged cell ranges; 'areas' [{name, fromRow, " //$NON-NLS-1$
                + "fromCol, toRow, toCol}] named areas (for ПолучитьОбласть / Вывести output); " //$NON-NLS-1$
                + "'columnWidths' [{col, width}] and 'rowHeights' [{row, height}] column / row sizes. " //$NON-NLS-1$
                + "Setting a cell overwrites that (row, col); the rest of the content is kept. Valid " //$NON-NLS-1$
                + "only for a SpreadsheetDocument template FQN; cannot be combined with 'properties' / " //$NON-NLS-1$
                + "'content' / a Role payload.") //$NON-NLS-1$
            .objectProperty(KEY_DCS,
                "REPORT FQN only ('Report.<Name>'): the Data Composition Schema (СКД) content to author, " //$NON-NLS-1$
                + "instead of 'properties'. Authors the report's main DCS (creating it if the report has " //$NON-NLS-1$
                + "none yet). An object with: 'dataSources' [{name, type?}] (a data source, default type " //$NON-NLS-1$
                + "a local query source); 'dataSets' [{name, type:'query', query (the 1C query text, " //$NON-NLS-1$
                + "bilingual keywords), dataSource?, autoFillFields? (default true - EDT derives the " //$NON-NLS-1$
                + "fields from the query), fields? [{name?, dataPath, title?, role?}]}] a query data set; " //$NON-NLS-1$
                + "'parameters' [{name, valueType?, title?, use?}] schema parameters. Valid only for a " //$NON-NLS-1$
                + "Report FQN; cannot be combined with 'properties' / 'content' / 'template' / a Role " //$NON-NLS-1$
                + "payload.") //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "Normalize the Russian letter 'ё'->'е' / 'Ё'->'Е' in localized-string values (synonym / " //$NON-NLS-1$
                + "title) and in the 'comment' property (default true). Matches the 1C standard " //$NON-NLS-1$
                + "mdo-ru-name-unallowed-letter. Other free-text strings can be identifier-like (e.g. " //$NON-NLS-1$
                + "XDTOPackage.namespace is a URI) and always keep the supplied value. Set false to " //$NON-NLS-1$
                + "keep 'ё' exactly as supplied everywhere.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the properties were set", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "'modified' on success") //$NON-NLS-1$
            .stringProperty("fqn", "Normalized FQN of the modified node") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty(KEY_APPLIED, "Names of the properties that were set (for a Role " //$NON-NLS-1$
                + "rights change this is instead an object {rights, templates, roleProperties} with " //$NON-NLS-1$
                + "the applied counts)") //$NON-NLS-1$
            .objectProperty(KEY_CONTENT, "For a membership-list content change: the counts object. A " //$NON-NLS-1$
                + "CommonAttribute / ExchangePlan change reports {added, updated, removed} (members " //$NON-NLS-1$
                + "attached / had their per-entry flag - 'use' / 'autoRecord' - updated / detached); a " //$NON-NLS-1$
                + "Catalog owners / Document register records / Subsystem content change (a plain " //$NON-NLS-1$
                + "reference list, no per-entry flag) reports {added, removed}") //$NON-NLS-1$
            .objectProperty(KEY_TEMPLATE, "For a template content change: the applied counts object " //$NON-NLS-1$
                + "{cells, merges, areas, columnWidths, rowHeights}") //$NON-NLS-1$
            .objectProperty(KEY_DCS, "For a DCS (Report Data Composition Schema) content change: the " //$NON-NLS-1$
                + "applied counts object {dataSources, dataSets, fields, parameters}") //$NON-NLS-1$
            .booleanProperty(KEY_PERSISTED, "Whether the change was exported to disk") //$NON-NLS-1$
            .stringArrayProperty("normalized", //$NON-NLS-1$
                "Properties whose value was rewritten by the 'ё'->'е' normalization (when any)") //$NON-NLS-1$
            .stringProperty("destination", //$NON-NLS-1$
                "Where a moved form item ended up (when 'parent'/'position' moved a form item), e.g. " //$NON-NLS-1$
                + "\"group 'Main' at index 1\"") //$NON-NLS-1$
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
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        List<JsonObject> properties = JsonUtils.extractObjectArray(params, "properties"); //$NON-NLS-1$

        // Role payload (rights[] / templates[] / roleProperties): dispatched when the resolved FQN is a
        // Role. When present, 'properties' is optional (a role is modified through the rights surface,
        // not the generic property bag).
        List<JsonObject> rolePayloadRights = JsonUtils.extractObjectArray(params, KEY_RIGHTS);
        List<JsonObject> rolePayloadTemplates = JsonUtils.extractObjectArray(params, KEY_TEMPLATES);
        JsonObject roleProperties = parseRolePropertiesArg(params);
        boolean hasRolePayload =
            !rolePayloadRights.isEmpty() || !rolePayloadTemplates.isEmpty() || roleProperties != null;

        // Membership content payload (content[]): one generic list dispatched by the resolved FQN's
        // kind (a CommonAttribute's / a Catalog's owners, an ExchangePlan's content objects, a
        // Document's register records). When present, 'properties' is optional (the membership list is
        // edited through its own surface, not the generic property bag) - mirrors the Role rights[]
        // precedent.
        List<JsonObject> content = JsonUtils.extractObjectArray(params, KEY_CONTENT);
        boolean hasContentPayload = !content.isEmpty();

        // Template spreadsheet-content payload (template={cells/merges/areas/columnWidths/rowHeights}):
        // authored on a SpreadsheetDocument template FQN. When present, 'properties' is optional (the
        // template content is authored through its own surface, not the generic property bag) - mirrors
        // the Role rights[] / membership content[] precedents. A present-but-malformed 'template' (not a
        // JSON object) is rejected here rather than silently dropped: 'template' is the SOLE surface for
        // this feature, so dropping it would apply a stray 'properties' - or misreport 'properties is
        // required' - while the intended template authoring vanished.
        TemplateArg templateArg = parseTemplateArg(params);
        if (templateArg.error != null)
        {
            return templateArg.error;
        }
        JsonObject templateSpec = templateArg.spec;
        boolean hasTemplatePayload = templateSpec != null;

        // Report Data Composition Schema payload (dcs={dataSources/dataSets/parameters}): authored on a
        // Report FQN. When present, 'properties' is optional (the DCS is authored through its own surface,
        // not the generic property bag) - mirrors the template payload precedent. A present-but-malformed
        // 'dcs' (not a JSON object) is an actionable error, not a silent drop: 'dcs' is the SOLE surface
        // for authoring a report's schema.
        DcsArg dcsArg = parseDcsArg(params);
        if (dcsArg.error != null)
        {
            return dcsArg.error;
        }
        JsonObject dcsSpec = dcsArg.spec;
        boolean hasDcsPayload = dcsSpec != null;

        if (properties.isEmpty() && !hasRolePayload && !hasContentPayload && !hasTemplatePayload
            && !hasDcsPayload)
        {
            return ToolResult.error("properties is required: provide at least one {name, value} to " //$NON-NLS-1$
                + "set, e.g. [{name: 'comment', value: 'Goods'}]. For a Role FQN, provide 'rights', " //$NON-NLS-1$
                + "'templates' or 'roleProperties' instead; for a CommonAttribute / ExchangePlan / " //$NON-NLS-1$
                + "Catalog / Document / Subsystem FQN, provide 'content' instead; for a template FQN, " //$NON-NLS-1$
                + "provide 'template' instead; for a Report FQN, provide 'dcs' instead.").toJson(); //$NON-NLS-1$
        }

        // 'ё'->'е' normalization is applied at the parse step to every localized-string / free-text
        // value being set (synonym / comment / title / ...), matching mdo-ru-name-unallowed-letter.
        // Rename is out of scope here, so there is no Name to normalize.
        MdNameNormalizer.Report normReport = new MdNameNormalizer.Report(normalizeYo);

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);

        // A FQN that addresses a FORM member (item / attribute / command) is dispatched to its own
        // branch: form members live on the editable Form content model (a cross-model hop), not the
        // mdclass tree. A Role / content payload addressed to a form member is refused there.
        FormElementWriter.FormMemberRef formRef = FormElementWriter.parse(normFqn);
        if (formRef != null)
        {
            // A 'template' payload addressed to a FORM-member FQN is refused here (a form member is not a
            // spreadsheet template), symmetric with the Role / content refusal dispatchFormMember already
            // enforces, so the sibling payload is never silently dropped while the form branch reports
            // success. The guard is at the call site to keep dispatchFormMember byte-unchanged.
            if (hasTemplatePayload)
            {
                return templateOnlyForTemplateFqnError(normFqn, "addresses a FORM member"); //$NON-NLS-1$
            }
            // Symmetrically, a 'dcs' payload addressed to a FORM member is refused (a form member is not a
            // Report), so the sibling payload is never silently dropped while the form branch reports
            // success.
            if (hasDcsPayload)
            {
                return dcsOnlyForReportFqnError(normFqn, "addresses a FORM member"); //$NON-NLS-1$
            }
            return dispatchFormMember(ctx, normFqn, formRef, properties, normReport, hasRolePayload,
                hasContentPayload);
        }

        // A SUBSYSTEM-content payload (content[] on a Subsystem FQN, possibly NESTED - e.g.
        // 'Subsystem.Sales.Subsystem.Orders') is dispatched EARLY, before the generic single-segment
        // resolver below: a subsystem's content list is edited through its own membership surface, and
        // the shared SubsystemUtils.resolveByFqn is the only resolver that walks a nested (and bilingual)
        // subsystem path. Scoped to a content payload so a subsystem FQN carrying only 'properties' still
        // takes the normal generic-property path below (its 'content' generic property still REPLACES the
        // whole list; the content[] payload here ADDS / REMOVES one member).
        if (hasContentPayload && SubsystemUtils.isSubsystemTypeToken(firstToken(normFqn)))
        {
            Subsystem subsystem = SubsystemUtils.resolveByFqn(config, normFqn);
            if (subsystem == null)
            {
                return ToolResult.error("Subsystem not found: " + fqn + ". Use 'Subsystem.Name' for a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "top subsystem or 'Subsystem.Parent.Subsystem.Child' for a nested one (the type " //$NON-NLS-1$
                    + "token may be English or Russian). Use get_metadata_objects or list_subsystems to " //$NON-NLS-1$
                    + "find an FQN.").toJson(); //$NON-NLS-1$
            }
            // A 'template' payload addressed to a Subsystem FQN is refused here (a subsystem is not a
            // spreadsheet template), so a template payload combined with a subsystem content[] payload is
            // never silently dropped. The guard is at the call site to keep modifySubsystemContent
            // byte-unchanged.
            if (hasTemplatePayload)
            {
                return templateOnlyForTemplateFqnError(normFqn, ERR_IS_A + subsystem.eClass().getName());
            }
            // Symmetrically, a 'dcs' payload addressed to a Subsystem FQN is refused (a subsystem is not a
            // Report), so a dcs payload combined with a subsystem content[] payload is never silently
            // dropped.
            if (hasDcsPayload)
            {
                return dcsOnlyForReportFqnError(normFqn, ERR_IS_A + subsystem.eClass().getName());
            }
            return modifySubsystemContent(ctx, normFqn, subsystem, properties, content, hasRolePayload);
        }

        // Exact-first resolve with the yo-addressing fallback: create_metadata normalizes
        // 'yo'->'ye' in names by default, so a caller re-typing the original yo spelling
        // would miss the stored name — the resolver retries the normalized FQN.
        MetadataNodeResolver.ResolvedNode resolved =
            MetadataNodeResolver.resolveExistingWithYoFallback(config, normFqn);
        MetadataNodeResolver.MetadataNode node = resolved.node;
        if (node == null || node.object == null)
        {
            return ToolResult.error("Node not found: " + fqn + ". Use 'Type.Name' for a top object or " //$NON-NLS-1$ //$NON-NLS-2$
                + "'Type.Name.Kind.Name' for a member. Use get_metadata_objects to find an FQN." //$NON-NLS-1$
                + MetadataNodeResolver.yoNotFoundHint(normFqn)).toJson();
        }
        if (resolved.yoFallback)
        {
            Activator.logInfo("modify_metadata: '" + normFqn //$NON-NLS-1$
                + "' did not resolve exactly; proceeding with its yo-normalized form '" //$NON-NLS-1$
                + resolved.fqn + "'"); //$NON-NLS-1$
            normFqn = resolved.fqn;
        }
        MdObject target = node.object;

        // A `dcs` payload on a Report FQN authors the report's Data Composition Schema; the same payload on
        // a NON-Report FQN is refused. Dispatched BEFORE the template / role / content path so a dcs
        // payload combined with another payload is refused here (the dcsMixError guard) - never silently
        // dropped - and a dcs+template mix reports the dcs-centric error rather than the generic
        // template-not-valid one. Entered only when a dcs payload is present.
        if (hasDcsPayload)
        {
            if (!(target instanceof Report))
            {
                return dcsOnlyForReportFqnError(normFqn, ERR_IS_A + target.eClass().getName());
            }
            String mixError = dcsMixError(properties, content, hasRolePayload, hasTemplatePayload);
            if (mixError != null)
            {
                return mixError;
            }
            return modifyDcsContent(ctx, normFqn, (Report)target, dcsSpec);
        }

        // A `template` spreadsheet-content payload on a BasicTemplate FQN is authored through the moxel
        // content surface; the same payload on a NON-template FQN is refused. Dispatched BEFORE the role /
        // content path so a template payload combined with a role / content payload is refused here (on a
        // non-template FQN) or inside modifyTemplateContent (on a template FQN, the mixing guard) - never
        // silently dropped. Returns null when there is no template payload, so the role / content /
        // generic path below still runs.
        String templatePayloadResult = dispatchTemplatePayload(ctx, normFqn, target, properties, content,
            hasRolePayload, templateSpec);
        if (templatePayloadResult != null)
        {
            return templatePayloadResult;
        }

        // A ROLE FQN carrying a role payload (rights / templates / roleProperties) is dispatched to the
        // rights writer; the same payload on a NON-Role FQN is refused. Returns null only when there is
        // no role payload, so the content / generic property path below still runs.
        String rolePayloadResult = dispatchRolePayload(ctx, normFqn, target, properties,
            rolePayloadRights, rolePayloadTemplates, roleProperties, hasRolePayload);
        if (rolePayloadResult != null)
        {
            return rolePayloadResult;
        }

        // A FQN carrying a content payload (content[]) is dispatched by the resolved object's KIND to
        // its dedicated membership writer (or refused for an unsupported kind); the branch always
        // returns.
        if (hasContentPayload)
        {
            return dispatchContentPayload(ctx, normFqn, target, properties, content);
        }

        // The remaining case: a generic 'properties' change applied through the BM write boundary.
        return applyGenericPropertyChanges(ctx, projectName, normFqn, node, target, properties,
            normReport);
    }

    /**
     * Dispatches a FORM-member FQN (item / attribute / command): the member lives on the editable Form
     * content model (a cross-model hop), not the mdclass tree, and the validation + change pipeline is
     * reused as-is. A Role payload ('rights' / 'templates' / 'roleProperties') or a membership 'content'
     * payload addressed to a FORM-member FQN is refused here, BEFORE the form dispatch: a form member is
     * neither a Role nor a membership-list owner (CommonAttribute / ExchangePlan / Catalog / Document),
     * so those siblings do not apply to it. Without this guard the form branch would apply only
     * 'properties' (or nothing) and report success while the sibling payload vanished silently. Both
     * siblings are rejected together to keep them symmetric. Extracted verbatim from
     * {@link #executeOnUiThread}.
     */
    private String dispatchFormMember(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef formRef, List<JsonObject> properties,
        MdNameNormalizer.Report normReport, boolean hasRolePayload, boolean hasContentPayload)
    {
        if (hasRolePayload || hasContentPayload)
        {
            return ToolResult.error("'" + normFqn + "' addresses a FORM member, which cannot " //$NON-NLS-1$ //$NON-NLS-2$
                + "take a Role payload ('rights' / 'templates' / 'roleProperties') or a " //$NON-NLS-1$
                + "membership 'content' payload. 'rights' / 'templates' / 'roleProperties' " //$NON-NLS-1$
                + "are valid only for a Role.<Name> FQN, and 'content' only for a " //$NON-NLS-1$
                + "CommonAttribute / ExchangePlan / Catalog / Document / Subsystem FQN. Use " //$NON-NLS-1$
                + "'properties' to change a form member.").toJson(); //$NON-NLS-1$
        }
        return modifyFormMember(ctx, normFqn, formRef, properties, normReport);
    }

    /**
     * Dispatches a ROLE payload ('rights' / 'templates' / 'roleProperties'): a Role FQN carrying the
     * payload goes to {@link #modifyRoleRights} (the access-rights surface, not the generic property
     * bag; the mutation runs through the EDT-native rights tasks + a forceExport draining the sibling
     * Rights.rights sub-resource); the same payload on a NON-Role FQN is refused (it must not fall
     * through to the generic property path, which - with an empty 'properties' - would apply nothing yet
     * report a false success and silently drop the payload). Returns {@code null} when there is NO role
     * payload, so the caller continues to the content / generic path. Extracted verbatim from
     * {@link #executeOnUiThread}.
     */
    private String dispatchRolePayload(ProjectContext ctx, String normFqn, MdObject target, // NOSONAR cohesive role-payload dispatch helper extracted verbatim; the rights/templates/properties params forward as-is to modifyRoleRights
        List<JsonObject> properties, List<JsonObject> rolePayloadRights,
        List<JsonObject> rolePayloadTemplates, JsonObject roleProperties, boolean hasRolePayload)
    {
        if (target instanceof Role && hasRolePayload)
        {
            return modifyRoleRights(ctx, normFqn, (Role)target, properties, rolePayloadRights,
                rolePayloadTemplates, roleProperties);
        }
        if (hasRolePayload)
        {
            return ToolResult.error("'rights' / 'templates' / 'roleProperties' are only valid for a " //$NON-NLS-1$
                + "Role FQN; '" + normFqn + MSG_IS_A + target.eClass().getName() + ". Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "'properties' for its generic properties, or address a Role.<Name>.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Dispatches a content payload (content[]) by the resolved object's KIND to its dedicated membership
     * writer: a member of that kind's structured list (a common attribute's owner, an exchange plan's
     * content object, a catalog's owner, a document's register record) is attached / detached through
     * the list surface, not the generic property bag. Each per-kind writer runs a BM write tx + a single
     * forceExport of the resolved TOP FQN and refuses mixing the content payload with a generic
     * 'properties' change (the same policy the Role rights branch enforces). A content payload addressed
     * to an unsupported kind is refused here (it must not fall through to the generic property path,
     * which - with an empty 'properties' - would apply nothing yet report a false success and silently
     * drop the content payload). Extracted verbatim from {@link #executeOnUiThread} (entered only when a
     * content payload is present).
     */
    private String dispatchContentPayload(ProjectContext ctx, String normFqn, MdObject target,
        List<JsonObject> properties, List<JsonObject> content)
    {
        if (target instanceof CommonAttribute)
        {
            return modifyCommonAttributeContent(ctx, normFqn, (CommonAttribute)target, properties,
                content);
        }
        if (target instanceof ExchangePlan)
        {
            return modifyExchangePlanContent(ctx, normFqn, (ExchangePlan)target, properties, content);
        }
        if (target instanceof Catalog)
        {
            return modifyCatalogOwners(ctx, normFqn, (Catalog)target, properties, content);
        }
        if (target instanceof Document)
        {
            return modifyDocumentRegisterRecords(ctx, normFqn, (Document)target, properties, content);
        }
        return ToolResult.error("'content' is only valid for a CommonAttribute, ExchangePlan, " //$NON-NLS-1$
            + "Catalog, Document or Subsystem FQN; '" + normFqn + MSG_IS_A + target.eClass().getName() //$NON-NLS-1$
            + ". Use 'properties' for its generic properties, or address a CommonAttribute.<Name> " //$NON-NLS-1$
            + "(owners), ExchangePlan.<Name> (content objects), Catalog.<Name> (owners), " //$NON-NLS-1$
            + "Document.<Name> (register records) or Subsystem.<Name> (content objects).").toJson(); //$NON-NLS-1$
    }

    /**
     * Dispatches a {@code template} spreadsheet-content payload: a {@link BasicTemplate} FQN (a common
     * template {@code CommonTemplate.<Name>} or an object-owned template
     * {@code <Type>.<Owner>.Template.<Name>}) carrying the payload goes to {@link #modifyTemplateContent}
     * (the SpreadsheetDocument content surface, not the generic property bag); the same payload on a
     * NON-template FQN is refused (it must not fall through to the role / content / generic property path,
     * which - with an empty {@code properties} - would apply nothing yet report a false success and
     * silently drop the payload). Returns {@code null} when there is NO template payload, so the caller
     * continues to the role / content / generic path. Entered only after the resolver has produced the
     * target object.
     */
    private String dispatchTemplatePayload(ProjectContext ctx, String normFqn, MdObject target,
        List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload,
        JsonObject templateSpec)
    {
        // The payload presence is derivable from the spec itself (java:S107: 8 -> 7 params).
        boolean hasTemplatePayload = templateSpec != null;
        if (target instanceof BasicTemplate && hasTemplatePayload)
        {
            return modifyTemplateContent(ctx, normFqn, (BasicTemplate)target, properties, content,
                hasRolePayload, templateSpec);
        }
        if (hasTemplatePayload)
        {
            return templateOnlyForTemplateFqnError(normFqn, ERR_IS_A + target.eClass().getName());
        }
        return null;
    }

    /**
     * Populates a SpreadsheetDocument (.mxlx) template's content (the {@code template} payload) via
     * {@link SpreadsheetTemplateWriter}: writes cells (text / print-time parameter + formatting), merged
     * ranges, named areas and column / row sizes into the template's content SpreadsheetDocument. A
     * template's content is authored through this dedicated surface, not the generic property bag, so
     * mixing the template payload with a generic {@code properties} change, a membership {@code content}
     * payload or a Role payload in the same call is refused (the same policy the Role rights / membership
     * content branches enforce, so a sibling payload is never silently dropped while the tool reports
     * success). Only a SpreadsheetDocument-typed template can be authored; a text / binary-data / DCS
     * template is refused.
     *
     * <p>The write runs inside ONE {@link BmTransactions#write write} transaction on the template
     * re-fetched by its BM id (the BM gotcha: capture {@code bmGetId()} up front, re-fetch inside the tx -
     * a top object's {@code eContainer()} does not reliably climb); the content SpreadsheetDocument is
     * created via {@link SheetFactory} as a CANONICAL document (templateMode + languageSettings + the
     * platform's default format band, matching a designer template) when the template is still empty. A
     * payload validation failure
     * throws a {@link TemplateWriteException} carrying a ready JSON error BEFORE the commit, so the tx
     * rolls back with no partial mutation. After the commit the TOP object's canonical FQN
     * ({@code bmGetFqn()} - the template itself when it is a top object, else its OWNER via
     * {@code bmGetTopObject()} for an object-owned template inline in the owner's .mdo - the #239
     * canonical-FQN lesson) is force-exported so the sibling {@code .mxlx} content resource drains to disk;
     * should EDT model that content as a DISTINCT top BM object, its own FQN is exported alongside
     * (mirroring the way {@link #modifyRoleRights} also exports the separate {@code Rights.rights}
     * sub-resource), so the authored cells are never left in-memory only.</p>
     */
    private String modifyTemplateContent(ProjectContext ctx, String normFqn, BasicTemplate template,
        List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload,
        JsonObject templateSpec)
    {
        String mixError = templateMixError(properties, content, hasRolePayload);
        if (mixError != null)
        {
            return mixError;
        }

        // Only a SpreadsheetDocument template carries spreadsheet content; a text / binary-data / DCS /
        // graphical template cannot host cells. Rejected up front (fail fast, no transaction).
        String nonSpreadsheetError = nonSpreadsheetTemplateError(template, normFqn);
        if (nonSpreadsheetError != null)
        {
            return nonSpreadsheetError;
        }

        // A common template (Template.X / CommonTemplate.X) is its OWN top BM object; an object-owned
        // template (Catalog.Y.Template.Z) is INLINE in its owner's .mdo, so it is NOT a top object. Its
        // stable BM id still re-fetches inside the tx (getObjectById resolves any managed object, not only
        // top ones), but the force-export target must be the TOP object's canonical (all-English) FQN - the
        // template itself when it is top, else the OWNER top object (bmGetTopObject) whose .mdo + sibling
        // .mxlx carry the content. Mirrors RoleRightsWriter's top climb - self when already top, else
        // bmGetTopObject. A bmGetFqn read is legal only on a top object, so it happens on `topObject`, never on a non-top
        // template. A null top (should not happen for a resolved template) fails LOUD, nothing written.
        IBmObject templateBm = (IBmObject)template;
        final long templateBmId = templateBm.bmGetId();
        IBmObject topObject = templateBm.bmIsTop() ? templateBm : templateBm.bmGetTopObject();
        if (topObject == null)
        {
            return ToolResult.error("Cannot resolve the on-disk file to export for template '" + normFqn //$NON-NLS-1$
                + "': its owning top-level object could not be found; report it with the template FQN.") //$NON-NLS-1$
                    .toJson();
        }
        final String exportFqn = topObject.bmGetFqn();

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error(ERR_NO_BM_MANAGER).toJson();
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error(ERR_NO_BM_MODEL
                + ctx.project.getName()).toJson();
        }

        // Drives the canonical (project-aware) <languageSettings> block when an EMPTY template's content
        // is materialized inside the tx (a freshly created template has getTemplate()==null). Resolved
        // best-effort with the SAME manager the force-export below uses; a null project still yields a
        // templateMode=true document (only the languageSettings block is skipped, which the platform's
        // moxel reader null-guards). Effectively final so it is captured by the write lambda.
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        final IDtProject dtProject =
            dtProjectManager == null ? null : dtProjectManager.getDtProject(ctx.project);

        // The moxel content is a transient @ExternalProperty of the template - its own .mxlx resource, NOT
        // an inline BM reference. A freshly-materialized content doc must be ATTACHED as a BM top object
        // under its generated external-property FQN (the same machinery FormElementWriter uses for a form's
        // content), else committing the write fails with "Failed to persist reference value". Effectively
        // final so it is captured by the write lambda.
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (fqnGenerator == null)
        {
            return ToolResult.error("ITopObjectFqnGenerator not available").toJson(); //$NON-NLS-1$
        }

        // Captured inside the write: the moxel content's OWN canonical FQN (a template's content IS a
        // distinct top BM object once attached - pre-existing on disk, or freshly attached below), so it is
        // force-exported alongside the template so the sibling .mxlx drains to disk.
        final String[] contentFqnHolder = {null};
        SpreadsheetTemplateWriter.Result result;
        try
        {
            result = BmTransactions.write(bmModel, "ModifyTemplateContent", (tx, pm) -> //$NON-NLS-1$
            {
                Object inTx = tx.getObjectById(templateBmId);
                if (!(inTx instanceof BasicTemplate))
                {
                    throw new TemplateWriteException(ToolResult.error("The template could not be " //$NON-NLS-1$
                        + "resolved inside the transaction.").toJson());
                }
                BasicTemplate txTemplate = (BasicTemplate)inTx;
                SpreadsheetDocument doc =
                    resolveSpreadsheetContent(txTemplate, tx, dtProject, fqnGenerator, normFqn);
                // The content doc is now an attached BM top object (pre-existing on disk, or freshly
                // materialized + attachTopObject'd inside resolveSpreadsheetContent), so its own resource
                // FQN resolves and is force-exported alongside the template so the sibling .mxlx drains.
                contentFqnHolder[0] = contentResourceExportFqn(doc);
                SpreadsheetTemplateWriter.Result applied = SpreadsheetTemplateWriter.apply(doc, templateSpec);
                if (applied.hasError())
                {
                    // Roll the whole write back so a validation failure leaves nothing on disk.
                    throw new TemplateWriteException(applied.error);
                }
                return applied;
            });
        }
        catch (TemplateWriteException e)
        {
            return e.getErrorJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error modifying template content", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify template content: " //$NON-NLS-1$
                + unwrapCauseMessage(e)).toJson();
        }

        // The spreadsheet content serializes to the template's sibling .mxlx resource under the
        // template's OWN top-object .mdo, so force-exporting the template's canonical FQN drains it. If
        // EDT instead models that content as a distinct top BM object (the same shape as a Role's separate
        // Rights.rights sub-resource, which modifyRoleRights exports by its own FQN), the template FQN
        // alone would NOT drain it - so export the content resource's OWN FQN too, guarding against the
        // #239-class silent-false-success (persisted=true while the authored cells never reach disk).
        List<String> exportFqns = new ArrayList<>();
        exportFqns.add(exportFqn);
        String contentFqn = contentFqnHolder[0];
        if (contentFqn != null && !contentFqn.equals(exportFqn))
        {
            exportFqns.add(contentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return buildTemplateResult(normFqn, result, persisted);
    }

    /**
     * A template's external-property content (a moxel {@link SpreadsheetDocument} or a
     * {@link DataCompositionSchema})'s OWN canonical top-object FQN when EDT models it as a DISTINCT top BM
     * object (so force-exporting the template's FQN alone would NOT drain the sibling {@code .mxlx} /
     * {@code .dcs}, the same shape as a Role's separate {@code Rights.rights} sub-resource), else
     * {@code null} when the content is a contained child that the template's own export already serializes
     * (the export list is then unchanged). Generic over the {@code @ExternalProperty} content type so both
     * the moxel template and the DCS branch reuse it. MUST run inside the write boundary:
     * {@code bmGetFqn()} is legal only on a top object, so the call is guarded by {@code bmIsTop()}.
     */
    private static String contentResourceExportFqn(EObject content)
    {
        if (content instanceof IBmObject)
        {
            IBmObject contentBm = (IBmObject)content;
            if (contentBm.bmIsTop())
            {
                return contentBm.bmGetFqn();
            }
        }
        return null;
    }

    /**
     * The up-front refusal for a {@code template} payload aimed at a real {@link BasicTemplate} whose type
     * is NOT {@link TemplateType#SPREADSHEET_DOCUMENT} (a text / binary-data / DCS / graphical template
     * cannot host cells): a ready {@link ToolResult#error} JSON naming the FQN and the template's ACTUAL
     * type (or {@code "unset"} for a null type - no NPE), pointing at the only template kind {@code template}
     * can author. Returns {@code null} when the template IS a SpreadsheetDocument (the write may proceed).
     * Pure (no model mutation, no transaction) so the guard is unit-testable headlessly; called fail-fast
     * before any BM write, mirrored inside the tx by {@link #resolveSpreadsheetContent}'s content re-check.
     */
    static String nonSpreadsheetTemplateError(BasicTemplate template, String normFqn)
    {
        if (template.getTemplateType() == TemplateType.SPREADSHEET_DOCUMENT)
        {
            return null;
        }
        String actual = template.getTemplateType() == null
            ? "unset" : template.getTemplateType().getName(); //$NON-NLS-1$
        return ToolResult.error("Template '" + normFqn + "' is not a SpreadsheetDocument template " //$NON-NLS-1$ //$NON-NLS-2$
            + "(its type is '" + actual + "'). Only a SpreadsheetDocument (print form / макет) " //$NON-NLS-1$ //$NON-NLS-2$
            + "template can be authored with 'template'.").toJson(); //$NON-NLS-1$
    }

    /**
     * Resolves the {@link SpreadsheetDocument} content of an in-transaction template, creating an empty
     * CANONICAL one when the template has none usable yet. This "no content" branch is the PRIMARY path,
     * not an edge case: {@code BasicTemplate.template} is a transient {@code @ExternalProperty} reference
     * whose content lives in the separate, lazily-loaded {@code .mxlx}, so a template freshly made by
     * {@code create_metadata} ({@code fillDefaultReferences} does not materialize a transient external ref)
     * has NO usable content - either {@code getTemplate() == null} OR, on some EDT builds, a non-null
     * PLACEHOLDER {@code EObject} that is not yet a {@link SpreadsheetDocument}. Both are treated as empty
     * (the declared {@code templateType} is already verified {@code == SPREADSHEET_DOCUMENT} up front, so a
     * non-spreadsheet content here is a placeholder to replace, never a real other-typed template). The
     * empty content is therefore built with the platform
     * factory {@link SheetFactory#createSpreadsheetDocument()} - NOT a raw
     * {@code MoxelFactory.createSpreadsheetDocument()} - which seeds the print / view settings, the
     * shared format band (a neutral format at index 0, so {@code SpreadsheetTemplateWriter.ensureBaseFormat}
     * then no-ops on the non-empty pool) and {@code setDefaultFormatIndex(0)}; on top of that a print
     * form (макет) is marked {@link SpreadsheetDocument#setTemplateMode(boolean) templateMode=true} and
     * given the required {@code <languageSettings>} block (project-aware via
     * {@link SheetFactory#createLanguageSettings} when the {@code dtProject} resolves), so the authored
     * {@code .mxlx} matches a designer-created template rather than a non-canonical
     * {@code templateMode=false} / no-{@code languageSettings} document.
     *
     * <p>A non-{@link SpreadsheetDocument} content is treated as an empty/placeholder and REPLACED with a
     * fresh canonical document (it is not a genuine other-typed template - the up-front
     * {@code templateType} guard already rejected those before the tx). MUST run inside the write
     * boundary.</p>
     */
    private static SpreadsheetDocument resolveSpreadsheetContent(BasicTemplate txTemplate, IBmTransaction tx,
        IDtProject dtProject, ITopObjectFqnGenerator fqnGenerator, String normFqn)
    {
        EObject contentObj = txTemplate.getTemplate();
        if (contentObj instanceof SpreadsheetDocument)
        {
            return (SpreadsheetDocument)contentObj;
        }
        // No usable content yet - materialize a fresh canonical SpreadsheetDocument. This covers BOTH a null
        // ref AND a non-null, non-SpreadsheetDocument PLACEHOLDER: a template freshly made by create_metadata
        // does not always report getTemplate()==null - on some EDT builds the transient @ExternalProperty ref
        // resolves to a placeholder EObject (not null), so keying only off null would wrongly reject a
        // brand-new empty template ("its content is EObject"). The declared templateType is already verified
        // == SPREADSHEET_DOCUMENT up front (nonSpreadsheetTemplateError), so any content that is not a real
        // SpreadsheetDocument is an empty/placeholder to replace, never a genuine non-spreadsheet template.
        // Built with the platform SheetFactory (seeds print/view settings, the neutral format band at index
        // 0, setDefaultFormatIndex(0)); a print form is templateMode=true with the project-aware
        // <languageSettings>, so the authored .mxlx matches a designer-created template.
        SpreadsheetDocument doc = SheetFactory.createSpreadsheetDocument();
        doc.setTemplateMode(true);
        if (dtProject != null)
        {
            doc.setLanguageSettings(SheetFactory.createLanguageSettings(dtProject));
        }
        txTemplate.setTemplate(doc);
        // The content is a transient @ExternalProperty (a separate .mxlx resource, NOT an inline BM ref), so
        // the freshly-created doc must be ATTACHED as a BM top object under its canonical external-property
        // FQN - else committing the write fails with "Failed to persist reference value". Mirrors
        // FormElementWriter's content-form attach (generateExternalPropertyFqn + attachTopObject).
        String contentFqn = fqnGenerator.generateExternalPropertyFqn(txTemplate,
            MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            throw new TemplateWriteException(ToolResult.error("Could not generate the content resource FQN " //$NON-NLS-1$
                + "for template '" + normFqn + "'; report it with the template FQN.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        tx.attachTopObject((IBmObject)doc, contentFqn);
        return doc;
    }

    /**
     * Builds the success JSON for a completed template content change: the {@code template} counts object
     * ({@code cells} / {@code merges} / {@code areas} / {@code columnWidths} / {@code rowHeights}) plus
     * {@code persisted} and a confirmation message. Pure helper.
     */
    private static String buildTemplateResult(String normFqn, SpreadsheetTemplateWriter.Result result,
        boolean persisted)
    {
        JsonObject applied = new JsonObject();
        applied.addProperty("cells", result.cells); //$NON-NLS-1$
        applied.addProperty("merges", result.merges); //$NON-NLS-1$
        applied.addProperty("areas", result.areas); //$NON-NLS-1$
        applied.addProperty("columnWidths", result.columnWidths); //$NON-NLS-1$
        applied.addProperty("rowHeights", result.rowHeights); //$NON-NLS-1$
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_TEMPLATE, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified template " + normFqn + " content (cells: " + result.cells //$NON-NLS-1$ //$NON-NLS-2$
                + ", merges: " + result.merges + ", areas: " + result.areas + ", columnWidths: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + result.columnWidths + ", rowHeights: " + result.rowHeights + ")") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * The actionable error for a {@code template} payload addressed to a FQN that is not a
     * SpreadsheetDocument template (a form member, a subsystem, or any other non-template object): names
     * the offending FQN + what it is, and points at the valid template FQN shapes. {@code isClause}
     * describes the resolved target (e.g. {@code "is a Catalog"} or {@code "addresses a FORM member"}).
     * Package-visible for tests.
     */
    static String templateOnlyForTemplateFqnError(String normFqn, String isClause)
    {
        return ToolResult.error("'template' is only valid for a SpreadsheetDocument template FQN (a " //$NON-NLS-1$
            + "common template 'CommonTemplate.<Name>' or an object-owned template " //$NON-NLS-1$
            + "'<Type>.<Owner>.Template.<Name>'); '" + normFqn + "' " + isClause + ". 'template' " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "authors a spreadsheet template's cells / merges / areas; use 'properties' for a generic " //$NON-NLS-1$
            + "property change, or address a template FQN.").toJson(); //$NON-NLS-1$
    }

    /**
     * The refusal for a {@code template} payload combined with another payload in the same call: a
     * generic {@code properties} change, a membership {@code content} payload or a Role payload
     * ({@code rights} / {@code templates} / {@code roleProperties}). A template's content is authored
     * through its own dedicated surface, so mixing is rejected up front - the same no-mixing policy the
     * Role rights / membership content branches enforce, so a sibling payload is never silently dropped
     * while the tool reports success. Returns the ready JSON error, or {@code null} when the
     * {@code template} payload stands alone. Package-visible for tests.
     */
    static String templateMixError(List<JsonObject> properties, List<JsonObject> content,
        boolean hasRolePayload)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A template content change ('template') cannot be combined with a " //$NON-NLS-1$
                + "generic 'properties' change in one call. Set the template's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }
        if (!content.isEmpty() || hasRolePayload)
        {
            return ToolResult.error("A template content change ('template') cannot be combined with a " //$NON-NLS-1$
                + "membership 'content' payload or a Role payload ('rights' / 'templates' / " //$NON-NLS-1$
                + "'roleProperties') in one call. 'template' authors a SpreadsheetDocument template's " //$NON-NLS-1$
                + "cells / merges / areas only.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Carries a ready JSON error out of the template write transaction (a validation / resolution failure)
     * so {@link #modifyTemplateContent} returns it verbatim AND the throw rolls the write back (no partial
     * mutation persists). Unchecked so it crosses the BM task boundary; the message is a validated
     * {@link ToolResult#error} JSON string. Mirrors {@code ReferenceMembershipWriter.ContentWriteException}.
     */
    private static final class TemplateWriteException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final transient String errorJson;

        TemplateWriteException(String errorJson)
        {
            super(errorJson);
            this.errorJson = errorJson;
        }

        String getErrorJson()
        {
            return errorJson;
        }
    }

    /**
     * Parses the optional {@code template} argument (a single JSON object - the spreadsheet content spec)
     * from the raw params into a {@link TemplateArg}: {@link TemplateArg#absent()} when the argument is
     * absent / blank / JSON null; a ready {@link TemplateArg#invalid} error when it is present but is NOT a
     * JSON object (unparseable, or a string / number / array). Unlike {@link #parseRolePropertiesArg}
     * (whose sibling {@code rights} / {@code templates} arrays still drive a role change if it is
     * malformed), {@code template} is the SOLE surface for the template-authoring feature, so a
     * present-but-malformed value must be an actionable error, NOT a silent drop that would apply a stray
     * {@code properties} - or misreport {@code properties is required} - while the authoring vanished. An
     * invalid INNER shape of a well-formed object is surfaced later by {@link SpreadsheetTemplateWriter}'s
     * validation. Package-visible for tests.
     */
    static TemplateArg parseTemplateArg(Map<String, String> params)
    {
        String raw = params.get(KEY_TEMPLATE);
        if (raw == null || raw.trim().isEmpty())
        {
            return TemplateArg.absent();
        }
        JsonElement element;
        try
        {
            element = JsonParser.parseString(raw.trim());
        }
        catch (RuntimeException e)
        {
            return TemplateArg.invalid(malformedTemplateError());
        }
        if (element.isJsonNull())
        {
            return TemplateArg.absent();
        }
        if (!element.isJsonObject())
        {
            return TemplateArg.invalid(malformedTemplateError());
        }
        return TemplateArg.of(element.getAsJsonObject());
    }

    /**
     * The actionable error for a present-but-malformed {@code template} argument (unparseable JSON, or a
     * string / number / array rather than an object): the {@code template} payload authors a
     * SpreadsheetDocument template's content, so it must be a JSON object.
     */
    private static String malformedTemplateError()
    {
        return ToolResult.error("'template' must be a JSON object, e.g. " //$NON-NLS-1$
            + "{cells:[{row:0,col:0,text:'Total'}]}. It authors a SpreadsheetDocument template's cells / " //$NON-NLS-1$
            + "merges / areas / column & row sizes on a template FQN.").toJson(); //$NON-NLS-1$
    }

    /**
     * The parsed {@code template} argument: {@link #absent()} (no payload - both fields {@code null}), a
     * valid parsed {@link #spec}, or a ready {@link #error} JSON for a present-but-malformed value. At most
     * one of {@code spec} / {@code error} is non-null. Package-visible for tests.
     */
    static final class TemplateArg
    {
        /** The parsed content spec, or {@code null} when the argument is absent or malformed. */
        final JsonObject spec;
        /** A ready {@link ToolResult#error} JSON when the argument is present-but-malformed, else {@code null}. */
        final String error;

        private TemplateArg(JsonObject spec, String error)
        {
            this.spec = spec;
            this.error = error;
        }

        static TemplateArg absent()
        {
            return new TemplateArg(null, null);
        }

        static TemplateArg of(JsonObject spec)
        {
            return new TemplateArg(spec, null);
        }

        static TemplateArg invalid(String error)
        {
            return new TemplateArg(null, error);
        }
    }

    // ===== Report Data Composition Schema (СКД / .dcs) authoring (#241) ==============================
    //
    // A `dcs` payload on a Report FQN authors the report's main Data Composition Schema (data sets +
    // query text + fields + schema parameters). The persistence is a near-clone of the #245 template
    // machinery: a report's DCS content is a {@link DataCompositionSchema} stored in the report's DCS
    // BasicTemplate's transient @ExternalProperty (BASIC_TEMPLATE__TEMPLATE) - the SAME slot a
    // SpreadsheetDocument template uses - so the fresh content is attached via attachTopObject and the
    // sibling .dcs resource is drained by the same dual force-export. The typed DCS write itself lives in
    // {@link DcsWriter}; this tool owns the Report -> DCS-template resolution, the BM boundary and the
    // force-export.

    /**
     * Authors a Report's Data Composition Schema (the {@code dcs} payload) via {@link DcsWriter}. The
     * report's DCS content lives in its main DCS {@link BasicTemplate} (templateType
     * {@link TemplateType#DATA_COMPOSITION_SCHEMA}); designer / older reports may have NO DCS at all, so
     * the FIRST {@code dcs} write lazily materializes that template
     * ({@link #findOrCreateDcsTemplate}) and registers it as the report's
     * {@code mainDataCompositionSchema}. The content {@link DataCompositionSchema} is a transient
     * {@code @ExternalProperty} (its own {@code .dcs} resource), so a freshly-materialized one is attached
     * as a BM top object ({@link #resolveDcsContent}, mirroring
     * {@link #resolveSpreadsheetContent}) - else the commit fails "Failed to persist reference value".
     *
     * <p>The write runs inside ONE {@link BmTransactions#write write} transaction on the Report re-fetched
     * by its BM id (the #174 / #245 BM gotcha: capture {@code bmGetId()} up front, re-fetch inside the tx).
     * A validation failure throws a {@link TemplateWriteException} carrying a ready JSON error BEFORE the
     * commit, so the tx rolls back with no partial mutation. After the commit the DCS template's TOP object
     * (a report DCS template is INLINE in the report's {@code .mdo}, so its top is the Report itself) is
     * force-exported so the template registration reaches disk, and the DCS content's OWN resource FQN is
     * force-exported alongside so the sibling {@code .dcs} drains (the #245 dual force-export, guarding the
     * #239-class silent-false-success). The mixing / non-Report-FQN guards run at the call site (see
     * {@link #dcsMixError} / {@link #dcsOnlyForReportFqnError}), so this method is entered only for a Report
     * FQN with a lone {@code dcs} payload.</p>
     */
    private String modifyDcsContent(ProjectContext ctx, String normFqn, Report report, JsonObject dcsSpec)
    {
        // The Report is a top BM object - capture its bmGetId up front, re-fetch inside the tx (a top
        // object's eContainer() does not reliably climb).
        IBmObject reportBm = (IBmObject)report;
        final long reportBmId = reportBm.bmGetId();

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error(ERR_NO_BM_MANAGER).toJson();
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error(ERR_NO_BM_MODEL
                + ctx.project.getName()).toJson();
        }

        // The DCS content is a transient @ExternalProperty of the DCS template (its own .dcs resource); a
        // freshly-materialized content must be ATTACHED as a BM top object under its generated
        // external-property FQN, so the generator is needed inside the write.
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (fqnGenerator == null)
        {
            return ToolResult.error("ITopObjectFqnGenerator not available").toJson(); //$NON-NLS-1$
        }

        // The report may have NO DCS template yet - the first `dcs` write lazily materializes it through
        // the parent-aware model factory, so the factory + platform version are needed inside the write.
        final IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager == null ? null : v8ProjectManager.getProject(ctx.project);
        if (factory == null || v8Project == null)
        {
            return ToolResult.error("EDT services unavailable (model factory / project) for project: " //$NON-NLS-1$
                + ctx.project.getName()).toJson();
        }
        final Version version = v8Project.getVersion();
        // A parameter's `valueType` is built into an mcore TypeDescription through the shared S2 builder
        // (same path as the generic `type` property, prepareTypeDescription). Supplied to DcsWriter as a
        // TypeResolver so the pure writer never touches the platform type provider directly.
        final Configuration dcsConfig = ctx.config;
        final DcsWriter.TypeResolver typeResolver = valueTypeSpec -> {
            if (version == null)
            {
                return DcsWriter.TypeResolution.failed(
                    "Cannot resolve the platform version needed to build the parameter type."); //$NON-NLS-1$
            }
            MetadataTypeBuilder.Result tr = MetadataTypeBuilder.build(valueTypeSpec, dcsConfig, version);
            return tr.error != null
                ? DcsWriter.TypeResolution.failed(tr.error)
                : DcsWriter.TypeResolution.of(tr.typeDescription);
        };

        // Captured inside the write: the on-disk export targets. exportFqnHolder = the DCS template's TOP
        // object (the Report), contentFqnHolder = the DCS content's OWN resource FQN (the .dcs).
        final String[] exportFqnHolder = {null};
        final String[] contentFqnHolder = {null};
        DcsWriter.Result result;
        try
        {
            result = BmTransactions.write(bmModel, "ModifyDcsContent", (tx, pm) -> //$NON-NLS-1$
            {
                Object inTx = tx.getObjectById(reportBmId);
                if (!(inTx instanceof Report))
                {
                    throw new TemplateWriteException(ToolResult.error("The report could not be resolved " //$NON-NLS-1$
                        + "inside the transaction.").toJson()); //$NON-NLS-1$
                }
                Report txReport = (Report)inTx;
                BasicTemplate dcsTemplate = findOrCreateDcsTemplate(txReport, factory, version);
                // A report DCS template is inline in the report's .mdo (not a top object), so its export
                // target is the OWNER top object (the Report), the same top climb as modifyTemplateContent -
                // a bmGetFqn read is legal only on a top object.
                IBmObject templateBm = (IBmObject)dcsTemplate;
                IBmObject topObject = templateBm.bmIsTop() ? templateBm : templateBm.bmGetTopObject();
                if (topObject == null)
                {
                    throw new TemplateWriteException(ToolResult.error("Cannot resolve the on-disk file to " //$NON-NLS-1$
                        + "export for the DCS of report '" + normFqn //$NON-NLS-1$
                        + "'; report it with the report FQN.").toJson()); //$NON-NLS-1$
                }
                exportFqnHolder[0] = topObject.bmGetFqn();
                DataCompositionSchema schema = resolveDcsContent(dcsTemplate, tx, fqnGenerator, normFqn);
                // The content is now an attached BM top object (pre-existing, or freshly attached inside
                // resolveDcsContent), so its own resource FQN resolves and is force-exported alongside the
                // template so the sibling .dcs drains.
                contentFqnHolder[0] = contentResourceExportFqn(schema);
                DcsWriter.Result applied = DcsWriter.apply(schema, dcsSpec, typeResolver);
                if (applied.hasError())
                {
                    // Roll the whole write back so a validation failure leaves nothing on disk.
                    throw new TemplateWriteException(applied.error);
                }
                return applied;
            });
        }
        catch (TemplateWriteException e)
        {
            return e.getErrorJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error modifying DCS content", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify DCS content: " //$NON-NLS-1$
                + unwrapCauseMessage(e)).toJson();
        }

        // Dual force-export: the DCS template's top object (the Report - drains the .mdo + the template
        // registration) AND the DCS content's own resource (drains the .dcs), guarding the #239-class
        // silent-false-success (persisted=true while the authored schema never reaches disk).
        List<String> exportFqns = new ArrayList<>();
        String exportFqn = exportFqnHolder[0];
        if (exportFqn != null)
        {
            exportFqns.add(exportFqn);
        }
        String contentFqn = contentFqnHolder[0];
        if (contentFqn != null && !contentFqn.equals(exportFqn))
        {
            exportFqns.add(contentFqn);
        }
        boolean persisted =
            !exportFqns.isEmpty() && BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return buildDcsResult(normFqn, result, persisted);
    }

    /**
     * Resolves the Report's main DCS {@link BasicTemplate}, lazily creating it when the report has none
     * (designer / older reports have no DCS). A DCS template is a {@link Template} child of the report
     * (inline in the report's {@code .mdo}, NOT a top object), created through the parent-aware model
     * factory exactly like {@code create_metadata} makes a template (its factory-initialized-child path),
     * marked {@link TemplateType#DATA_COMPOSITION_SCHEMA} and registered as the report's
     * {@code mainDataCompositionSchema} so the report is well-formed. MUST run inside the write boundary.
     */
    private static BasicTemplate findOrCreateDcsTemplate(Report txReport, IModelObjectFactory factory,
        Version version)
    {
        BasicTemplate existing = txReport.getMainDataCompositionSchema();
        if (existing != null)
        {
            return existing;
        }
        MdObject child = (MdObject)factory.create(MdClassPackage.Literals.TEMPLATE, txReport, version);
        if (child == null)
        {
            child = (MdObject)EcoreUtil.create(MdClassPackage.Literals.TEMPLATE);
        }
        Template template = (Template)child;
        template.setName(DEFAULT_DCS_TEMPLATE_NAME);
        if (template.getUuid() == null)
        {
            template.setUuid(UUID.randomUUID());
        }
        template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        txReport.getTemplates().add(template);
        // Template IS-A BasicTemplate, so it binds the report's mainDataCompositionSchema cross-reference.
        txReport.setMainDataCompositionSchema(template);
        factory.fillDefaultReferences(template);
        return template;
    }

    /**
     * Resolves the {@link DataCompositionSchema} content of an in-transaction DCS template, creating an
     * empty one when the template has none usable yet. Mirrors {@link #resolveSpreadsheetContent}: a
     * template's content is a transient {@code @ExternalProperty} whose content lives in the separate,
     * lazily-loaded {@code .dcs}, so a freshly-materialized DCS template has NO usable content - either
     * {@code getTemplate() == null} OR a non-{@link DataCompositionSchema} placeholder. Both are treated as
     * empty and replaced with a fresh {@link DcsFactory}-built schema. The fresh content is a transient
     * external ref (a separate {@code .dcs} resource, NOT an inline BM ref), so it is ATTACHED as a BM top
     * object under its canonical external-property FQN ({@code BASIC_TEMPLATE__TEMPLATE}) - else committing
     * the write fails with "Failed to persist reference value". MUST run inside the write boundary.
     */
    private static DataCompositionSchema resolveDcsContent(BasicTemplate txTemplate, IBmTransaction tx,
        ITopObjectFqnGenerator fqnGenerator, String normFqn)
    {
        EObject contentObj = txTemplate.getTemplate();
        // Reuse the existing content ONLY when it is an ATTACHED BM top object. findOrCreateDcsTemplate calls
        // factory.fillDefaultReferences(template) after setting templateType=DATA_COMPOSITION_SCHEMA; if that
        // pre-materializes an UNATTACHED DataCompositionSchema in getTemplate(), returning it here would skip
        // attachTopObject, so contentResourceExportFqn(schema) yields null (bmIsTop()==false) and the sibling
        // .dcs would silently never drain (a #239-class false success). When it is not yet a top object, fall
        // through to setTemplate + generateExternalPropertyFqn + attachTopObject to (re-)attach it.
        if (contentObj instanceof DataCompositionSchema && contentObj instanceof IBmObject
            && ((IBmObject)contentObj).bmIsTop())
        {
            return (DataCompositionSchema)contentObj;
        }
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        txTemplate.setTemplate(schema);
        String contentFqn = fqnGenerator.generateExternalPropertyFqn(txTemplate,
            MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            throw new TemplateWriteException(ToolResult.error("Could not generate the content resource FQN " //$NON-NLS-1$
                + "for the DCS of report '" + normFqn + "'; report it with the report FQN.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        tx.attachTopObject((IBmObject)schema, contentFqn);
        return schema;
    }

    /**
     * Builds the success JSON for a completed DCS content change: the {@code dcs} counts object
     * ({@code dataSources} / {@code dataSets} / {@code fields} / {@code parameters}) plus {@code persisted}
     * and a confirmation message. Pure helper.
     */
    private static String buildDcsResult(String normFqn, DcsWriter.Result result, boolean persisted)
    {
        JsonObject applied = new JsonObject();
        applied.addProperty("dataSources", result.dataSources); //$NON-NLS-1$
        applied.addProperty("dataSets", result.dataSets); //$NON-NLS-1$
        applied.addProperty("fields", result.fields); //$NON-NLS-1$
        applied.addProperty("parameters", result.parameters); //$NON-NLS-1$
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_DCS, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified DCS of report " + normFqn + " (dataSources: " //$NON-NLS-1$ //$NON-NLS-2$
                + result.dataSources + ", dataSets: " + result.dataSets + ", fields: " + result.fields //$NON-NLS-1$ //$NON-NLS-2$
                + ", parameters: " + result.parameters + ")") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * The actionable error for a {@code dcs} payload addressed to a FQN that is not a Report (a form
     * member, a subsystem, or any other non-Report object): names the offending FQN + what it is, and
     * points at the valid Report FQN shape. {@code isClause} describes the resolved target (e.g.
     * {@code "is a Catalog"} or {@code "addresses a FORM member"}). Package-visible for tests.
     */
    static String dcsOnlyForReportFqnError(String normFqn, String isClause)
    {
        return ToolResult.error("'dcs' is only valid for a Report FQN ('Report.<Name>'); '" + normFqn //$NON-NLS-1$
            + "' " + isClause + ". 'dcs' authors a report's Data Composition Schema (data sets / query " //$NON-NLS-1$ //$NON-NLS-2$
            + "text / fields / parameters); use 'properties' for a generic property change, or address a " //$NON-NLS-1$
            + "Report.<Name>.").toJson(); //$NON-NLS-1$
    }

    /**
     * The refusal for a {@code dcs} payload combined with another payload in the same call: a generic
     * {@code properties} change, a membership {@code content} payload, a Role payload ({@code rights} /
     * {@code templates} / {@code roleProperties}) or a {@code template} payload. A report's DCS is authored
     * through its own dedicated surface, so mixing is rejected up front - the same no-mixing policy the
     * Role rights / membership content / template branches enforce, so a sibling payload is never silently
     * dropped while the tool reports success. Returns the ready JSON error, or {@code null} when the
     * {@code dcs} payload stands alone. Package-visible for tests.
     */
    static String dcsMixError(List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload,
        boolean hasTemplatePayload)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A DCS content change ('dcs') cannot be combined with a generic " //$NON-NLS-1$
                + "'properties' change in one call. Set the report's own properties (comment / synonym) " //$NON-NLS-1$
                + "separately.").toJson(); //$NON-NLS-1$
        }
        if (!content.isEmpty() || hasRolePayload || hasTemplatePayload)
        {
            return ToolResult.error("A DCS content change ('dcs') cannot be combined with a membership " //$NON-NLS-1$
                + "'content' payload, a Role payload ('rights' / 'templates' / 'roleProperties') or a " //$NON-NLS-1$
                + "'template' payload in one call. 'dcs' authors a report's Data Composition Schema " //$NON-NLS-1$
                + "(data sets / query text / fields / parameters) only.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Parses the optional {@code dcs} argument (a single JSON object - the Data Composition Schema spec)
     * from the raw params into a {@link DcsArg}: {@link DcsArg#absent()} when the argument is absent /
     * blank / JSON null; a ready {@link DcsArg#invalid} error when it is present but is NOT a JSON object.
     * Mirrors {@link #parseTemplateArg}: {@code dcs} is the SOLE surface for report-schema authoring, so a
     * present-but-malformed value must be an actionable error, NOT a silent drop that would apply a stray
     * {@code properties} - or misreport {@code properties is required} - while the authoring vanished. An
     * invalid INNER shape of a well-formed object is surfaced later by {@link DcsWriter}'s validation.
     * Package-visible for tests.
     */
    static DcsArg parseDcsArg(Map<String, String> params)
    {
        String raw = params.get(KEY_DCS);
        if (raw == null || raw.trim().isEmpty())
        {
            return DcsArg.absent();
        }
        JsonElement element;
        try
        {
            element = JsonParser.parseString(raw.trim());
        }
        catch (RuntimeException e)
        {
            return DcsArg.invalid(malformedDcsError());
        }
        if (element.isJsonNull())
        {
            return DcsArg.absent();
        }
        if (!element.isJsonObject())
        {
            return DcsArg.invalid(malformedDcsError());
        }
        return DcsArg.of(element.getAsJsonObject());
    }

    /**
     * The actionable error for a present-but-malformed {@code dcs} argument (unparseable JSON, or a string
     * / number / array rather than an object): the {@code dcs} payload authors a report's Data Composition
     * Schema, so it must be a JSON object.
     */
    private static String malformedDcsError()
    {
        return ToolResult.error("'dcs' must be a JSON object, e.g. " //$NON-NLS-1$
            + "{dataSets:[{name:'Main',type:'query',query:'SELECT ...'}]}. It authors a report's Data " //$NON-NLS-1$
            + "Composition Schema (data sets / query text / fields / parameters) on a Report FQN.").toJson(); //$NON-NLS-1$
    }

    /**
     * The parsed {@code dcs} argument: {@link #absent()} (no payload - both fields {@code null}), a valid
     * parsed {@link #spec}, or a ready {@link #error} JSON for a present-but-malformed value. At most one
     * of {@code spec} / {@code error} is non-null. Package-visible for tests. Mirrors {@link TemplateArg}.
     */
    static final class DcsArg
    {
        /** The parsed DCS spec, or {@code null} when the argument is absent or malformed. */
        final JsonObject spec;
        /** A ready {@link ToolResult#error} JSON when the argument is present-but-malformed, else {@code null}. */
        final String error;

        private DcsArg(JsonObject spec, String error)
        {
            this.spec = spec;
            this.error = error;
        }

        static DcsArg absent()
        {
            return new DcsArg(null, null);
        }

        static DcsArg of(JsonObject spec)
        {
            return new DcsArg(spec, null);
        }

        static DcsArg invalid(String error)
        {
            return new DcsArg(null, error);
        }
    }

    /**
     * Applies a generic 'properties' change to the resolved node through the BM write boundary (the
     * remaining case once the form / role / content branches are ruled out): resolves the BM re-fetch
     * strategy and the platform version, validates + prepares every change (fail fast, no partial
     * mutation), runs the destructive-consent gate for a retype, then applies the changes inside ONE BM
     * write transaction and force-exports the TOP object. Extracted verbatim from
     * {@link #executeOnUiThread}; the reject conditions, the consent-gate call and the force-export are
     * unchanged.
     */
    private String applyGenericPropertyChanges(ProjectContext ctx, String projectName, String normFqn,
        MetadataNodeResolver.MetadataNode node, MdObject target, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        Configuration config = ctx.config;

        // Resolve the BM re-fetch strategy (mutation must re-fetch inside the write tx). Only TOP
        // objects are re-fetchable by bmId, so for a member we re-fetch the TOP object and
        // re-navigate to the leaf's owner BY NAME inside the tx - this is what lets a member of a
        // NESTED object (e.g. a tabular-section attribute) be modified, not just a direct member.
        final String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        BmFetchPlan plan = resolveBmFetchPlan(config, node, target, parts);
        if (plan.error != null)
        {
            return plan.error;
        }
        final long topBmId = plan.topBmId;
        final EStructuralFeature memberFeature = plan.memberFeature;
        final String memberName = plan.memberName;

        // The platform version is needed only to build a 'type' value; resolve it best-effort (a
        // missing version is reported only if a 'type' property is actually set).
        final Version version = resolvePlatformVersion(ctx);

        // Validate every property against the introspected schema BEFORE any write (fail fast, no
        // partial mutation). On success, collect the prepared changes to apply inside the tx.
        List<PreparedChange> changes = new ArrayList<>();
        String prepErr = validateAndPrepare(config, version, target, properties, changes, normReport);
        if (prepErr != null)
        {
            return prepErr;
        }

        // Ask the human before RETYPING an attribute (the destructive case): the preview is built from
        // the already-prepared changes, so a benign edit never prompts. On REJECT the tool returns the
        // error and mutates nothing; the gate itself is a no-op headless / when env or preference allows.
        String consentErr = consentForTypeChanges(normFqn, changes);
        if (consentErr != null)
        {
            return consentErr;
        }

        // The top object that owns the node's .mdo file.
        final String topFqn = topFqn(normFqn);
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error(ERR_NO_BM_MANAGER).toJson();
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error(ERR_NO_BM_MODEL + projectName).toJson();
        }

        try
        {
            BmTransactions.<Void>write(bmModel, "ModifyMetadata", (tx, pm) -> //$NON-NLS-1$
            {
                EObject applyTo = resolveApplyTarget(tx, topBmId, memberFeature, memberName, parts);
                for (PreparedChange change : changes)
                {
                    change.applyTo(applyTo, tx);
                }
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error modifying metadata", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, topFqn);

        List<String> applied = appliedFeatureNames(changes);
        return buildModifiedResult(normFqn, applied, persisted, normReport);
    }

    /**
     * Resolves the platform {@link Version} for the project best-effort (used only to build a 'type'
     * value): {@code null} when the V8 project manager or project is unavailable. Side-effect-free
     * helper extracted from {@link #executeOnUiThread}.
     */
    private static Version resolvePlatformVersion(ProjectContext ctx)
    {
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        return v8Project != null ? v8Project.getVersion() : null;
    }

    /**
     * Re-navigates to the EObject that the prepared changes must be applied to, INSIDE the write
     * transaction: re-fetches the TOP object by {@code topBmId} and, for a member, re-navigates to
     * the leaf's owner and then to the leaf BY NAME. Read-only resolution (no eSet) - the actual
     * mutation stays in the caller's apply loop. Throws the SAME {@link RuntimeException}s as before
     * (target / owner / member not found), which propagate to the same catch and roll the tx back.
     * Extracted verbatim from {@link #executeOnUiThread}'s transaction body.
     */
    private static EObject resolveApplyTarget(IBmTransaction tx, long topBmId,
        EStructuralFeature memberFeature, String memberName, String[] parts)
    {
        EObject top = (EObject)tx.getObjectById(topBmId);
        if (top == null)
        {
            throw new RuntimeException("Target not found in transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        if (memberFeature == null)
        {
            return top;
        }
        EObject owner = MetadataNodeResolver.resolveOwnerInTx(top, parts);
        if (owner == null)
        {
            throw new RuntimeException("Could not re-navigate to the owner inside the transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        EObject applyTo = childByName(owner, memberFeature, memberName);
        if (applyTo == null)
        {
            throw new RuntimeException("Member not found in transaction: " + memberName); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        return applyTo;
    }

    /**
     * Collects the feature names of the applied changes, in order. Pure helper extracted from
     * {@link #executeOnUiThread}.
     */
    private static List<String> appliedFeatureNames(List<PreparedChange> changes)
    {
        List<String> applied = new ArrayList<>();
        for (PreparedChange change : changes)
        {
            applied.add(change.featureName());
        }
        return applied;
    }

    /**
     * Runs the destructive-consent gate for a modify BEFORE the mutation, but ONLY when at least one
     * prepared change RETYPES data (a {@code TYPE_DESCRIPTION} / form {@code valueType} set): retyping an
     * attribute can drop stored values on the next database update, so it is the destructive case a plain
     * property edit is not. A benign change list skips the gate entirely (no prompt, byte-identical path).
     *
     * <p>The gate itself decides whether to block on a UI dialog (env / headless / preference-driven — see
     * {@link DestructiveConsentGate}); this method just supplies the object FQN + the retyped features as
     * the preview. Returns a ready JSON error ({@code "Operation declined by user"}) when the human
     * REJECTS - the caller returns it and mutates NOTHING - or {@code null} to proceed.</p>
     */
    private static String consentForTypeChanges(String normFqn, List<PreparedChange> changes)
    {
        List<String> retyped = new ArrayList<>();
        for (PreparedChange change : changes)
        {
            if (change.isTypeChange())
            {
                retyped.add(change.featureName());
            }
        }
        if (retyped.isEmpty())
        {
            return null;
        }
        ConsentPreview preview = new ConsentPreview(
            "Change the data type of " + normFqn, //$NON-NLS-1$
            "Retyping stored data can drop existing values on the next database update.", //$NON-NLS-1$
            retyped.size(), retyped);
        if (DestructiveConsentGate.getInstance().requireConsent(NAME, preview) == ConsentDecision.REJECT)
        {
            return ToolResult.error("Operation declined by user").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Runs the destructive-consent gate for a FORM member modify BEFORE the write transaction, but ONLY
     * when it retypes a form ATTRIBUTE (a {@code type} / {@code valueType} property on an Attribute ref).
     * Scoped to the ATTRIBUTE kind (like the dynamic-list-query branch) so a decoration's benign enum
     * {@code type} never prompts, and run here - not inside the tx callback - because the gate may block
     * on a UI dialog and a transaction must not be held open across it. Returns a ready JSON error
     * ({@code "Operation declined by user"}) on REJECT, or {@code null} to proceed.
     */
    private static String consentForFormTypeChange(String normFqn, FormElementWriter.FormMemberRef ref,
        List<JsonObject> properties)
    {
        if (FormElementWriter.kindForToken(ref.kindToken) != FormElementWriter.Kind.ATTRIBUTE)
        {
            return null;
        }
        boolean retype = false;
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if ("type".equalsIgnoreCase(name) || PROP_VALUE_TYPE.equalsIgnoreCase(name)) //$NON-NLS-1$
            {
                retype = true;
                break;
            }
        }
        if (!retype)
        {
            return null;
        }
        ConsentPreview preview = new ConsentPreview(
            "Change the data type of " + normFqn, //$NON-NLS-1$
            "Retyping a form attribute can drop stored values on the next database update.", //$NON-NLS-1$
            1, java.util.Collections.singletonList(PROP_VALUE_TYPE));
        if (DestructiveConsentGate.getInstance().requireConsent(NAME, preview) == ConsentDecision.REJECT)
        {
            return ToolResult.error("Operation declined by user").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Builds the success JSON for a completed modify (action / fqn / applied / persisted, the
     * normalization report and the confirmation message). Pure helper extracted from
     * {@link #executeOnUiThread}; the same shape used by the form-member branch.
     */
    private static String buildModifiedResult(String normFqn, List<String> applied, boolean persisted,
        MdNameNormalizer.Report normReport)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, MSG_MODIFIED_PREFIX + normFqn + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    /**
     * Parses the optional {@code roleProperties} argument (a single JSON object) from the raw params.
     * Returns {@code null} when the argument is absent or is not a JSON object (an invalid shape is
     * surfaced later by {@link RoleRightsWriter}'s validation). Kept separate from the array parsing
     * because {@link JsonUtils} has no single-object extractor.
     */
    private static JsonObject parseRolePropertiesArg(Map<String, String> params)
    {
        String raw = params.get(KEY_ROLE_PROPERTIES);
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }
        try
        {
            JsonElement element = JsonParser.parseString(raw.trim());
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Modifies a ROLE's access rights (the {@code rights} / {@code templates} / {@code roleProperties}
     * payload) via {@link RoleRightsWriter}. A role is modified through its rights surface, not the
     * generic property bag, so mixing the role payload with a generic {@code properties} change in the
     * same call is refused (the same policy the move / handler / command form branches enforce). The
     * writer mutates only through the EDT-native rights tasks; this branch then force-exports BOTH the
     * Role FQN AND the {@code RoleDescription}'s own top-object FQN, OUTSIDE the writer, because the
     * rights matrix lives in its OWN BM resource ({@code Rights.rights}) that the role FQN alone does
     * not drain.
     */
    private String modifyRoleRights(ProjectContext ctx, String normFqn, Role role,
        List<JsonObject> properties, List<JsonObject> rights, List<JsonObject> templates,
        JsonObject roleProperties)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A role rights change ('rights' / 'templates' / 'roleProperties') " //$NON-NLS-1$
                + "cannot be combined with a generic 'properties' change in one call. Set the role's " //$NON-NLS-1$
                + "own properties (comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        RoleRightsWriter.Result result =
            RoleRightsWriter.apply(ctx.project, ctx.config, role, rights, templates, roleProperties);
        if (result.hasError())
        {
            return result.error;
        }

        // The rights matrix (the Rights.rights file) is a SEPARATE BM resource from Role.mdo: the
        // RoleDescription is its own top BM object (its impl extends com._1c.g5.v8.bm.core.BmObject)
        // with its own EClass-keyed exporter (RightsExporter supports ROLE_DESCRIPTION). Exporting only
        // the role FQN drains Role.mdo but never Rights.rights, so force-export its OWN FQN too.
        // 'persisted' stays honest: true only when the rights resource FQN resolved and was exported.
        String rightsFqn = RoleRightsWriter.resolveRightsDescriptionFqn(ctx.project, role);
        List<String> exportFqns = new ArrayList<>();
        exportFqns.add(normFqn);
        if (rightsFqn != null && !rightsFqn.equals(normFqn))
        {
            exportFqns.add(rightsFqn);
        }
        boolean exported = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        boolean persisted = exported && rightsFqn != null;

        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_RIGHTS, result.rights);
        applied.addProperty(KEY_TEMPLATES, result.templates);
        applied.addProperty(KEY_ROLE_PROPERTIES, result.roleProperties);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified role " + normFqn + " (rights: " + result.rights //$NON-NLS-1$ //$NON-NLS-2$
                + ", templates: " + result.templates + ", roleProperties: " + result.roleProperties //$NON-NLS-1$ //$NON-NLS-2$
                + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Modifies a COMMON ATTRIBUTE's content list (the {@code content[]} payload) via
     * {@link CommonAttributeContentWriter}: attaches / detaches an owner object in the common
     * attribute's {@code <content>} list. A common attribute's content is edited through this dedicated
     * surface, not the generic property bag, so mixing the content payload with a generic
     * {@code properties} change in the same call is refused (the same policy the Role rights / move /
     * handler / command branches enforce). The writer mutates only through the BM write boundary; this
     * branch then force-exports the single CommonAttribute TOP FQN OUTSIDE the writer, once, after the
     * write has committed.
     */
    private String modifyCommonAttributeContent(ProjectContext ctx, String normFqn,
        CommonAttribute commonAttribute, List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A common attribute content change ('content') cannot be combined " //$NON-NLS-1$
                + "with a generic 'properties' change in one call. Set the common attribute's own " //$NON-NLS-1$
                + "properties (comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        CommonAttributeContentWriter.Result result =
            CommonAttributeContentWriter.apply(ctx.project, ctx.config, commonAttribute, content);
        if (result.hasError())
        {
            return result.error;
        }

        // The content list lives inside the CommonAttribute's own .mdo, so exporting the CommonAttribute
        // TOP FQN once drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_ADDED, result.added);
        applied.addProperty("updated", result.updated); //$NON-NLS-1$
        applied.addProperty(KEY_REMOVED, result.removed);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_CONTENT, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified common attribute " + normFqn + " content (added: " //$NON-NLS-1$ //$NON-NLS-2$
                + result.added + ", updated: " + result.updated + MSG_REMOVED_COUNT + result.removed //$NON-NLS-1$
                + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Modifies an EXCHANGE PLAN's content list (the {@code content[]} payload) via
     * {@link ExchangePlanContentWriter}: attaches / detaches an MdObject in the exchange plan's
     * {@code <content>} list, optionally with a per-object {@code autoRecord} (Allow / Deny) flag. An
     * exchange plan's content is edited through this dedicated surface, not the generic property bag, so
     * mixing the content payload with a generic {@code properties} change in the same call is refused
     * (the same policy the Role rights / CommonAttribute content branches enforce). The writer mutates
     * only through the BM write boundary; this branch then force-exports the single ExchangePlan TOP FQN
     * OUTSIDE the writer, once, after the write has committed.
     */
    private String modifyExchangePlanContent(ProjectContext ctx, String normFqn,
        ExchangePlan exchangePlan, List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("An exchange plan content change ('content') cannot be combined " //$NON-NLS-1$
                + "with a generic 'properties' change in one call. Set the exchange plan's own " //$NON-NLS-1$
                + "properties (comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        ExchangePlanContentWriter.Result result =
            ExchangePlanContentWriter.apply(ctx.project, ctx.config, exchangePlan, content);
        if (result.hasError())
        {
            return result.error;
        }

        // The content list lives inside the ExchangePlan's own .mdo, so exporting the ExchangePlan TOP
        // FQN once drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_ADDED, result.added);
        applied.addProperty("updated", result.updated); //$NON-NLS-1$
        applied.addProperty(KEY_REMOVED, result.removed);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_CONTENT, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified exchange plan " + normFqn + " content (added: " //$NON-NLS-1$ //$NON-NLS-2$
                + result.added + ", updated: " + result.updated + MSG_REMOVED_COUNT + result.removed //$NON-NLS-1$
                + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Modifies a CATALOG's owners list (the {@code content[]} payload) via
     * {@link ReferenceMembershipWriter} with {@link ReferenceMembershipWriter.Kind#CATALOG_OWNERS}:
     * attaches / detaches an owner object (a PLAIN reference, no per-entry flag) in the catalog's
     * {@code <owners>} list. A catalog's owners are edited through this dedicated surface, not the
     * generic property bag, so mixing the content payload with a generic {@code properties} change in
     * the same call is refused (the same policy the Role rights / CommonAttribute content branches
     * enforce). The writer mutates only through the BM write boundary; this branch then force-exports
     * the single Catalog TOP FQN OUTSIDE the writer, once, after the write has committed.
     */
    private String modifyCatalogOwners(ProjectContext ctx, String normFqn, Catalog catalog,
        List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A catalog owners change ('content') cannot be combined with a " //$NON-NLS-1$
                + "generic 'properties' change in one call. Set the catalog's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        ReferenceMembershipWriter.Result result = ReferenceMembershipWriter.apply(ctx.project, ctx.config,
            catalog, content, ReferenceMembershipWriter.Kind.CATALOG_OWNERS);
        if (result.hasError())
        {
            return result.error;
        }

        // The owners list lives inside the Catalog's own .mdo, so exporting the Catalog TOP FQN once
        // drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        return buildMembershipResult(normFqn, "catalog", "owners", result, persisted); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Modifies a DOCUMENT's register records list / движения (the {@code content[]} payload) via
     * {@link ReferenceMembershipWriter} with
     * {@link ReferenceMembershipWriter.Kind#DOCUMENT_REGISTER_RECORDS}: attaches / detaches a register
     * (a PLAIN reference, no per-entry flag) in the document's {@code <registerRecords>} list. A
     * document's register records are edited through this dedicated surface, not the generic property
     * bag, so mixing the content payload with a generic {@code properties} change in the same call is
     * refused (the same policy the Role rights / CommonAttribute content branches enforce). The writer
     * mutates only through the BM write boundary; this branch then force-exports the single Document TOP
     * FQN OUTSIDE the writer, once, after the write has committed.
     */
    private String modifyDocumentRegisterRecords(ProjectContext ctx, String normFqn, Document document,
        List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A document register records change ('content') cannot be combined " //$NON-NLS-1$
                + "with a generic 'properties' change in one call. Set the document's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        ReferenceMembershipWriter.Result result = ReferenceMembershipWriter.apply(ctx.project, ctx.config,
            document, content, ReferenceMembershipWriter.Kind.DOCUMENT_REGISTER_RECORDS);
        if (result.hasError())
        {
            return result.error;
        }

        // The register records list lives inside the Document's own .mdo, so exporting the Document TOP
        // FQN once drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        return buildMembershipResult(normFqn, "document", "register records", result, persisted); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Modifies a SUBSYSTEM's content list (the {@code content[]} payload) via
     * {@link ReferenceMembershipWriter} with {@link ReferenceMembershipWriter.Kind#SUBSYSTEM_CONTENT}:
     * attaches / detaches a top-level configuration object (a PLAIN reference, no per-entry flag) in the
     * subsystem's {@code <content>} list. A subsystem's content is edited through this dedicated surface,
     * not the generic property bag (the generic {@code content} property REPLACES the whole list; this
     * ADDS / REMOVES one member idempotently), so mixing the content payload with a generic
     * {@code properties} change - or with a Role payload ({@code rights} / {@code templates} /
     * {@code roleProperties}, which is valid only for a Role FQN) - in the same call is refused (the same
     * policy the Role rights / CommonAttribute content / Catalog owners branches enforce, so a sibling
     * payload is never silently dropped while the tool reports success). The writer mutates only through
     * the BM write boundary; this branch then force-exports the Subsystem's OWN top-object FQN OUTSIDE the
     * writer, once, after the write has committed.
     *
     * <p>The export target is derived from the RESOLVED subsystem's OWN BM identity
     * ({@code bmGetFqn()}), NOT the caller-supplied {@code normFqn}:
     * {@link MetadataTypeUtils#normalizeFqn} canonicalizes only the LEADING type token, so a nested
     * subsystem addressed with a Russian type token in a non-leading segment (e.g.
     * {@code Subsystem.Sales.Подсистема.Orders}) resolves + writes correctly in memory yet yields a
     * non-canonical FQN that {@code forceExport} - keyed by the all-English canonical FQN
     * ({@code Subsystem.Sales.Subsystem.Orders}) - cannot match, silently discarding the committed change
     * on the next refresh. Reading the resolved subsystem's own {@code bmGetFqn()} yields that canonical
     * English-token FQN regardless of how the caller addressed it (English or Russian, top-level or
     * nested).</p>
     *
     * <p>A subsystem - top-level OR nested - is ALWAYS its own BM top object: {@code Subsystem.getSubsystems()}
     * is a plain REFERENCE list (not a containment list) and the parent link is the settable
     * {@code parentSubsystem} reference, so every subsystem is the root of its own resource with its own
     * {@code .mdo} (a nested child at {@code src/Subsystems/<Parent>/Subsystems/<Child>/<Child>.mdo}) and its
     * own canonical FQN. The content change lives in that same {@code .mdo}, so force-exporting the
     * subsystem's own FQN drains it - a nested child's OWN file, not an ancestor's. The
     * {@code bmIsTop()} guard makes this fail LOUD (an honest error, nothing written) in the
     * model-invariant-violating event a subsystem were ever NOT a top object, so {@code persisted} is never
     * a false {@code true} over an ancestor {@code .mdo} while the child's own file goes unwritten
     * ({@code bmGetFqn()} is legal only on a top object).</p>
     */
    private String modifySubsystemContent(ProjectContext ctx, String normFqn, Subsystem subsystem,
        List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload)
    {
        // A Role payload on a Subsystem FQN is refused before anything is applied (a Subsystem is not a
        // Role), mirroring dispatchRolePayload: the sibling payload must never be silently dropped while
        // the content change reports success.
        if (hasRolePayload)
        {
            return ToolResult.error("'rights' / 'templates' / 'roleProperties' are only valid for a " //$NON-NLS-1$
                + "Role FQN; '" + normFqn + MSG_IS_A + subsystem.eClass().getName() + ". Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "'content' to edit the subsystem's content objects, or address a Role.<Name>.").toJson(); //$NON-NLS-1$
        }
        if (!properties.isEmpty())
        {
            return ToolResult.error("A subsystem content change ('content') cannot be combined with a " //$NON-NLS-1$
                + "generic 'properties' change in one call. Set the subsystem's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        // The resolved subsystem's OWN canonical (all-English) FQN is the force-export key. A subsystem -
        // top-level OR nested - is ALWAYS its own BM top object: Subsystem.getSubsystems() is a plain
        // REFERENCE list (NOT containment) and the parent link is the settable parentSubsystem reference,
        // so every subsystem is the root of its own resource with its own .mdo and its own canonical FQN,
        // where its content change lives. Reading bmGetFqn() on the subsystem itself - rather than reusing
        // normFqn, which normalizeFqn canonicalizes only in its leading token - keeps a nested subsystem
        // addressed with a Russian type token in a non-leading segment exportable (otherwise forceExport
        // cannot match the mixed-language FQN and the committed change is never drained to the .mdo).
        // Captured up front (a safe BM identity read), before the write transaction, mirroring how
        // ReferenceMembershipWriter.apply captures bmGetId(). The bmIsTop() guard makes this fail LOUD - an
        // honest error, nothing written - in the impossible event a subsystem were ever NOT a top object,
        // so persisted is never a false true over an ancestor .mdo while the child's own file goes
        // unwritten (bmGetFqn() is legal only on a top object).
        IBmObject subsystemBm = (IBmObject)subsystem;
        if (!subsystemBm.bmIsTop())
        {
            return ToolResult.error("Cannot resolve the on-disk file to export for subsystem '" //$NON-NLS-1$
                + normFqn + "': it is not an independent top-level metadata object. A subsystem is always " //$NON-NLS-1$
                + "its own object, so this should not happen; report it with the subsystem FQN.").toJson(); //$NON-NLS-1$
        }
        String exportFqn = subsystemBm.bmGetFqn();

        ReferenceMembershipWriter.Result result = ReferenceMembershipWriter.apply(ctx.project, ctx.config,
            subsystem, content, ReferenceMembershipWriter.Kind.SUBSYSTEM_CONTENT);
        if (result.hasError())
        {
            return result.error;
        }

        // The content list lives inside the Subsystem's own .mdo (a nested subsystem has its own .mdo),
        // so exporting the resolved subsystem's canonical top-object FQN once drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqn);

        return buildMembershipResult(normFqn, "subsystem", KEY_CONTENT, result, persisted); //$NON-NLS-1$
    }

    /**
     * The first dot-delimited token of an FQN (its type token, e.g. {@code Subsystem} in
     * {@code Subsystem.Sales.Subsystem.Orders}), or the whole string when there is no dot. Pure helper
     * used to scope the early subsystem-content path by its type token, before the generic resolver runs.
     */
    private static String firstToken(String normFqn)
    {
        int dot = normFqn.indexOf('.');
        return dot < 0 ? normFqn : normFqn.substring(0, dot);
    }

    /**
     * Builds the success JSON for a plain-reference membership change (Catalog owners / Document
     * register records / Subsystem content) applied via {@link ReferenceMembershipWriter}: the
     * {@code content} counts object ({@code added} / {@code removed}; a plain reference list has no
     * per-entry flag, so there is no {@code updated}) plus {@code persisted} and a confirmation message.
     * Pure helper shared by {@link #modifyCatalogOwners}, {@link #modifyDocumentRegisterRecords} and
     * {@link #modifySubsystemContent}.
     */
    private static String buildMembershipResult(String normFqn, String kindNoun, String listNoun,
        ReferenceMembershipWriter.Result result, boolean persisted)
    {
        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_ADDED, result.added);
        applied.addProperty(KEY_REMOVED, result.removed);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_CONTENT, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, MSG_MODIFIED_PREFIX + kindNoun + " " + normFqn + " " + listNoun + " (added: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + result.added + MSG_REMOVED_COUNT + result.removed + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Holds the BM re-fetch strategy resolved for the modify transaction: the {@code topBmId} of the
     * re-fetchable top object plus, for a member node, the owning {@code memberFeature} and the leaf's
     * {@code memberName} to re-navigate by name inside the tx. {@link #error} is non-null (a ready JSON
     * error) when the target / top object is not a BM object.
     */
    private static final class BmFetchPlan
    {
        private long topBmId;
        private EStructuralFeature memberFeature;
        private String memberName;
        private String error;
    }

    /**
     * Resolves the BM re-fetch strategy for the modify transaction (see {@link BmFetchPlan}). Only TOP
     * objects are re-fetchable by bmId; for a member the TOP object's bmId is captured and the leaf is
     * re-navigated by name inside the tx. Side-effect-free: it only reads ids / features. Extracted
     * verbatim from {@link #executeOnUiThread}; the caller re-checks {@link BmFetchPlan#error} and
     * returns it unchanged, preserving the original error cases.
     */
    private static BmFetchPlan resolveBmFetchPlan(Configuration config,
        MetadataNodeResolver.MetadataNode node, MdObject target, String[] parts)
    {
        BmFetchPlan plan = new BmFetchPlan();
        if (node.topLevel)
        {
            if (!(target instanceof IBmObject))
            {
                plan.error = ToolResult.error("Target is not a BM object").toJson(); //$NON-NLS-1$
                return plan;
            }
            plan.topBmId = ((IBmObject)target).bmGetId();
            plan.memberFeature = null;
            plan.memberName = null;
        }
        else
        {
            MdObject topObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
            if (!(topObject instanceof IBmObject))
            {
                plan.error = ToolResult.error("Top object is not a BM object").toJson(); //$NON-NLS-1$
                return plan;
            }
            plan.topBmId = ((IBmObject)topObject).bmGetId();
            plan.memberFeature = node.feature;
            plan.memberName = target.getName();
        }
        return plan;
    }

    /**
     * Validates every property against the introspected schema BEFORE any write (fail fast, no partial
     * mutation), appending a {@link PreparedChange} for each. Returns the first property's JSON error,
     * or {@code null} when all validated. Side-effect-free apart from populating {@code changes};
     * extracted verbatim from {@link #executeOnUiThread} so a failure returns the SAME error in the
     * SAME case, before the BM transaction runs.
     */
    private String validateAndPrepare(Configuration config, Version version, MdObject target,
        List<JsonObject> properties, List<PreparedChange> changes, MdNameNormalizer.Report normReport)
    {
        for (JsonObject prop : properties)
        {
            // The mdclass path has no <extInfo> (extInfo == null): findFeature then classifies only the
            // object's own features, so this stays byte-identical to the pre-extInfo behaviour.
            String pErr = prepare(config, version, target, null, prop, changes, normReport);
            if (pErr != null)
            {
                return pErr;
            }
        }
        return null;
    }

    /**
     * Modifies a FORM member (item / attribute / command) addressed by a form FQN. The member lives on
     * the editable Form content model (reached via the cross-model hop), so this branch resolves the
     * member there, reuses the shared {@link #prepare} validation + {@link PreparedChange} pipeline
     * (the introspector is EClass-driven, so an item's title / visible / readOnly and an attribute's
     * valueType / enums classify the same way mdclass properties do), then applies the changes inside a
     * BM write transaction and force-exports the CONTENT form to its {@code Form.form} on disk.
     *
     * <p>Validation and mutation run inside ONE BM write transaction: every property is validated
     * first and a failure throws {@link FormValidationException} (carrying a ready JSON error) BEFORE
     * any {@code eSet}, so the transaction rolls back with no partial mutation; building the change
     * values and setting them in the same transaction avoids any cross-transaction detached-object
     * concern. The member is re-navigated by name inside the transaction.</p>
     */
    private String modifyFormMember(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        // A handler FQN ('...Handler.Event' at form / item level) is not a property-bag member: the only
        // supported change is REBINDING its BSL procedure ('procedure' / 'handler' property). Binding a
        // NEW event stays in create_metadata, removing it in delete_metadata; any other property on a
        // handler FQN is refused with that pointer.
        if (FormElementWriter.isHandlerToken(ref.kindToken) || ref.isItemLevel())
        {
            String procName = handlerProcedureValue(properties);
            String rebindErr = validateHandlerRebind(properties, procName);
            if (rebindErr != null)
            {
                return rebindErr;
            }
            return rebindFormHandler(ctx, normFqn, ref, procName);
        }

        // A button's command targets a FormCommand (a form-model object, not an mdclass object), so it
        // is not introspector-assignable; a 'command' property on a Button FQN RE-POINTS it at an
        // existing form command.
        if (FormElementWriter.kindForToken(ref.kindToken) == FormElementWriter.Kind.BUTTON
            && hasCommandProperty(properties))
        {
            return rebindButtonCommand(ctx, normFqn, ref, properties);
        }

        // A MOVE / REORDER is expressed through the 'parent' and/or 'position' properties on a form
        // ITEM (a field / group / decoration / button / table - anything in the items tree). It is a
        // structural re-parent/reorder, not an eSet property change, so it is routed to its own branch
        // (and must not be mixed with ordinary property changes in the same call).
        if (hasMoveProperty(properties))
        {
            return moveFormItem(ctx, normFqn, ref, properties);
        }

        // A DynamicList custom query is set through 'queryText' / 'customQuery' on a form ATTRIBUTE.
        // These live on the attribute's DynamicListExtInfo - a sub-object the generic introspector does
        // not reach - so they route to their own branch that creates / configures the ext-info
        // reflectively, turning a plain form attribute into a custom-query dynamic list.
        if (isDynamicListQueryRequest(ref, properties))
        {
            return configureDynamicListQuery(ctx, normFqn, ref, properties, normReport);
        }

        // Ordinary property modify: the remaining (non-handler, non-command, non-move) case.
        return modifyFormMemberProperties(ctx, normFqn, ref, properties, normReport);
    }

    /**
     * Modifies the ordinary (non-handler, non-command, non-move) properties of a form member, the
     * remaining case of {@link #modifyFormMember} once those three structural branches are ruled out.
     * Resolves the platform version, then validates + applies every property inside ONE BM write
     * transaction (the member is re-navigated by name inside the tx; a validation failure throws
     * {@link FormValidationException} carrying its JSON error BEFORE any {@code eSet}, so the tx rolls
     * back with no partial mutation) and force-exports the CONTENT form to disk. Extracted verbatim
     * from {@link #modifyFormMember}: the BM write transaction (apply loop + forceExport via
     * {@code writeEditableForm}) stays INLINE here, and the early-returns return the SAME JSON in the
     * SAME case.
     */
    private String modifyFormMemberProperties(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        // Retyping a form ATTRIBUTE ('type' / 'valueType') is the destructive case for a form member -
        // ask the human BEFORE opening the write transaction (never block while a tx is held). A benign
        // property edit, and any 'type' feature on a non-attribute member (a decoration's enum 'type'),
        // do NOT prompt: the gate is scoped to a form attribute's data type, mirroring the ATTRIBUTE
        // guard the dynamic-list-query branch uses.
        String formConsentErr = consentForFormTypeChange(normFqn, ref, properties);
        if (formConsentErr != null)
        {
            return formConsentErr;
        }

        Configuration config = ctx.config;
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;

        final List<String> applied = new ArrayList<>();

        // Validate + apply inside ONE BM write transaction: resolve the member, validate every
        // property (a failure throws FormValidationException carrying the JSON error BEFORE any eSet,
        // so the tx rolls back with no partial mutation), then apply. The member is re-navigated by
        // name inside the tx (only the form top object is re-fetchable by bmId). Building the change
        // values and setting them in the SAME tx avoids any cross-transaction detached-object concern.
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a form member as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name' " //$NON-NLS-1$
                    + "(Kind = Attribute / Command / Field / Button / Group / Decoration / Table)."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "ModifyFormMember", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                    if (member == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form member not found: " //$NON-NLS-1$
                            + ref.name + " (kind '" + ref.kindToken + "') on " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                            + ". Use get_metadata_details to list the members.").toJson()); //$NON-NLS-1$
                    }
                    List<HolderChange> changes =
                        prepareFormMemberChanges(config, version, member, properties, normReport);
                    for (HolderChange hc : changes)
                    {
                        // A direct feature lands on the member; a property on the nested <extInfo> lands
                        // on the extInfo holder, created (or reused) here now that every property has
                        // validated. Mixing both in one call routes each change to its correct receiver.
                        EObject holder = hc.onExtInfo
                            ? FormElementWriter.ensureExtInfo(formModel, member) : member;
                        hc.change.applyTo(holder, tx);
                        applied.add(hc.change.featureName());
                    }
                });
        }
        catch (Exception e)
        {
            // A property-validation failure carries a ready JSON error (possibly wrapped by the tx
            // runner) - surface it directly; anything else is a genuine failure.
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error modifying form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify form member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, MSG_MODIFIED_PREFIX + normFqn + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    // --- DynamicList custom query (set on a form ATTRIBUTE) -----------------------------------------

    /** The dynamic-list query property names. {@code queryText} sets the custom query, {@code customQuery}
     * toggles whether the dynamic list uses it (vs the automatic main-table query). */
    private static final String PROP_QUERY_TEXT = "queryText"; //$NON-NLS-1$
    private static final String PROP_CUSTOM_QUERY = "customQuery"; //$NON-NLS-1$
    // ru TekstZaprosa (= queryText) / ProizvolnyjZapros (= customQuery) - pure-ASCII source (cp codepoints).
    private static final String RU_PROP_QUERY_TEXT = MetadataLanguageUtils.cp(0x0422, 0x0435, 0x043a,
        0x0441, 0x0442, 0x0417, 0x0430, 0x043f, 0x0440, 0x043e, 0x0441, 0x0430);
    private static final String RU_PROP_CUSTOM_QUERY = MetadataLanguageUtils.cp(0x041f, 0x0440, 0x043e,
        0x0438, 0x0437, 0x0432, 0x043e, 0x043b, 0x044c, 0x043d, 0x044b, 0x0439, 0x0417, 0x0430, 0x043f,
        0x0440, 0x043e, 0x0441);
    /** The dynamic-list main-table property: an object FQN whose main table the list reads from. */
    private static final String PROP_MAIN_TABLE = "mainTable"; //$NON-NLS-1$
    // ru OsnovnayaTablica (= mainTable) - pure-ASCII source (cp codepoints).
    private static final String RU_PROP_MAIN_TABLE = MetadataLanguageUtils.cp(0x041e, 0x0441, 0x043d,
        0x043e, 0x0432, 0x043d, 0x0430, 0x044f, 0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430);

    /** Whether a property NAME is the {@code queryText} dynamic-list property (English or Russian). */
    static boolean isQueryTextProp(String name)
    {
        return PROP_QUERY_TEXT.equalsIgnoreCase(name) || RU_PROP_QUERY_TEXT.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code customQuery} dynamic-list property (English or Russian). */
    static boolean isCustomQueryProp(String name)
    {
        return PROP_CUSTOM_QUERY.equalsIgnoreCase(name) || RU_PROP_CUSTOM_QUERY.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code mainTable} dynamic-list property (English or Russian). */
    static boolean isMainTableProp(String name)
    {
        return PROP_MAIN_TABLE.equalsIgnoreCase(name) || RU_PROP_MAIN_TABLE.equalsIgnoreCase(name);
    }

    /**
     * Whether this modify is a DynamicList custom-query request: a form ATTRIBUTE FQN carrying a
     * {@code queryText} and/or {@code customQuery} property. Those properties live on the attribute's
     * {@code DynamicListExtInfo} (not on the attribute itself), so they are routed to their own branch
     * instead of the generic introspector path. Reads only the property list (no model mutation).
     */
    private static boolean isDynamicListQueryRequest(FormElementWriter.FormMemberRef ref,
        List<JsonObject> properties)
    {
        if (FormElementWriter.kindForToken(ref.kindToken) != FormElementWriter.Kind.ATTRIBUTE)
        {
            return false;
        }
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isQueryTextProp(name) || isCustomQueryProp(name) || isMainTableProp(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Configures a form attribute as a custom-query dynamic list ({@code queryText} / {@code customQuery}).
     * Mirrors {@link #modifyFormMemberProperties}: resolves the platform version, then applies the change
     * inside ONE BM write transaction ({@link FormElementWriter#configureDynamicListQuery} creates /
     * configures the {@code DynamicListExtInfo} reflectively) and force-exports the content form. The
     * query properties are structural (they create the ext-info and the {@code DynamicList} value type),
     * so they must not be mixed with ordinary property changes in one call - the same policy the move /
     * handler / command branches enforce.
     */
    private String configureDynamicListQuery(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        DynListQueryRequest req = parseDynListQueryProps(properties);
        if (req.error != null)
        {
            return req.error;
        }
        final String queryText = req.queryText;
        final Boolean customQuery = req.customQuery;
        final String mainTable = req.mainTable;

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;

        final List<String> applied = new ArrayList<>();
        final String qt = queryText;
        final Boolean cq = customQuery;
        final String mt = mainTable;
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address the dynamic-list attribute as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Attribute.Name'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "ConfigureDynamicListQuery", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                    if (member == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form attribute not found: " //$NON-NLS-1$
                            + ref.name + " on " + ref.formPath //$NON-NLS-1$
                            + ". Create it with create_metadata, then set its query.").toJson()); //$NON-NLS-1$
                    }
                    applied.addAll(FormElementWriter.configureDynamicListQuery(
                        formModel, member, qt, cq, mt, ctx.config, version));
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error configuring dynamic-list query", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set the dynamic-list query: " //$NON-NLS-1$
                + unwrapCauseMessage(e)).toJson();
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, "Configured dynamic-list query on " + normFqn //$NON-NLS-1$
                + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    /**
     * The parsed dynamic-list query properties, or a ready JSON {@code error} when a value was malformed
     * or a query prop was mixed with another property change. Lets {@link #configureDynamicListQuery} stay
     * a thin orchestrator while the property loop and its early-return validations live in
     * {@link #parseDynListQueryProps}.
     */
    private static final class DynListQueryRequest
    {
        final String queryText;
        final Boolean customQuery;
        final String mainTable;
        final String error;

        private DynListQueryRequest(String queryText, Boolean customQuery, String mainTable, String error)
        {
            this.queryText = queryText;
            this.customQuery = customQuery;
            this.mainTable = mainTable;
            this.error = error;
        }

        static DynListQueryRequest of(String queryText, Boolean customQuery, String mainTable)
        {
            return new DynListQueryRequest(queryText, customQuery, mainTable, null);
        }

        static DynListQueryRequest failed(String error)
        {
            return new DynListQueryRequest(null, null, null, error);
        }
    }

    /**
     * Reads the dynamic-list query properties ({@code queryText} / {@code customQuery} / {@code mainTable})
     * from the property list into a {@link DynListQueryRequest}. Returns one carrying a ready JSON error
     * when a value is malformed or a query prop is mixed with another property change. Pure (reads only the
     * supplied list).
     */
    private DynListQueryRequest parseDynListQueryProps(List<JsonObject> properties)
    {
        DynListQueryProps acc = new DynListQueryProps();
        for (JsonObject prop : properties)
        {
            String error = readDynListQueryProp(prop, acc);
            if (error != null)
            {
                return DynListQueryRequest.failed(error);
            }
        }
        if (acc.queryTextGiven && (acc.queryText == null || acc.queryText.trim().isEmpty()))
        {
            return DynListQueryRequest.failed(ToolResult.error("'queryText' must be a non-empty 1C query, e.g. " //$NON-NLS-1$
                + "\"SELECT Ref, Description AS Description FROM Catalog.Products\". To switch the " //$NON-NLS-1$
                + "dynamic list back to its automatic query, pass 'customQuery' = false instead.").toJson()); //$NON-NLS-1$
        }
        return DynListQueryRequest.of(acc.queryText, acc.customQuery, acc.mainTable);
    }

    /** Mutable accumulator for the dynamic-list query properties read by {@link #readDynListQueryProp}. */
    private static final class DynListQueryProps
    {
        String queryText;
        Boolean customQuery;
        String mainTable;
        boolean queryTextGiven;
    }

    /**
     * Reads a single property into {@code acc}, returning a ready JSON error string when the value is
     * malformed or a query prop is mixed with another property change, or {@code null} to continue.
     * Pure apart from updating {@code acc}.
     */
    private String readDynListQueryProp(JsonObject prop, DynListQueryProps acc)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if (isQueryTextProp(name))
        {
            acc.queryText = asString(prop.get(KEY_VALUE));
            acc.queryTextGiven = true;
            return null;
        }
        if (isCustomQueryProp(name))
        {
            Boolean parsed = parseBooleanFlag(prop.get(KEY_VALUE));
            if (parsed == null)
            {
                return ToolResult.error("'customQuery' must be a boolean (true / false).").toJson(); //$NON-NLS-1$
            }
            acc.customQuery = parsed;
            return null;
        }
        if (isMainTableProp(name))
        {
            acc.mainTable = asString(prop.get(KEY_VALUE));
            if (acc.mainTable == null || acc.mainTable.trim().isEmpty())
            {
                return ToolResult.error("'mainTable' must be an object FQN, e.g. " //$NON-NLS-1$
                    + "'Catalog.Products' or 'Document.Order'.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        return ToolResult.error("Setting a dynamic-list query ('queryText' / 'customQuery' / " //$NON-NLS-1$
            + "'mainTable') cannot be combined with other property changes ('" + name //$NON-NLS-1$
            + "') in one call. Configure the query first, then make the other changes " //$NON-NLS-1$
            + "separately.").toJson(); //$NON-NLS-1$
    }

    /**
     * Parses a flag property value (a JSON boolean, or the string {@code "true"} / {@code "false"}),
     * or {@code null} when the value is not a recognizable boolean.
     */
    static Boolean parseBooleanFlag(JsonElement value)
    {
        if (value == null || !value.isJsonPrimitive())
        {
            return null; // NOSONAR tri-state: null means "not a recognizable boolean"; callers check it explicitly
        }
        JsonPrimitive prim = value.getAsJsonPrimitive();
        if (prim.isBoolean())
        {
            return Boolean.valueOf(prim.getAsBoolean());
        }
        String s = prim.getAsString().trim();
        if ("true".equalsIgnoreCase(s)) //$NON-NLS-1$
        {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) //$NON-NLS-1$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR tri-state: null means "not a recognizable boolean"; callers check it explicitly
    }

    /**
     * Validates a handler-rebind request on a handler / item-level FQN. A handler FQN supports only
     * REBINDING its BSL procedure, and that rebind is structural so it must not be mixed with other
     * property changes. Returns a JSON error to refuse the call - when no {@code procedure} value was
     * supplied ({@code procName == null}), or when the rebind is mixed with another property change -
     * or {@code null} when the rebind is valid and the caller may proceed to {@link #rebindFormHandler}.
     * Pure (no model mutation): reads only the supplied property list.
     */
    private static String validateHandlerRebind(List<JsonObject> properties, String procName)
    {
        if (procName != null)
        {
            // A handler rebind is structural and must not be mixed with other property changes in
            // one call - the same policy the move ('parent'/'position') and button-command
            // ('command') branches enforce. Reject BEFORE any mutation.
            String mixed = firstNonHandlerRebindProperty(properties);
            if (mixed != null)
            {
                return ToolResult.error("Rebinding a handler's procedure ('procedure') cannot be " //$NON-NLS-1$
                    + "combined with other property changes ('" + mixed + "') in one call. Rebind " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the procedure first, then make the other changes in a separate call.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        return ToolResult.error("On a form event-handler FQN, modify_metadata can only REBIND the " //$NON-NLS-1$
            + "bound procedure - pass a 'procedure' property (e.g. {name:'procedure', " //$NON-NLS-1$
            + "value:'NewProc'}). To bind a new event use create_metadata, to remove it " //$NON-NLS-1$
            + "delete_metadata.").toJson(); //$NON-NLS-1$
    }

    /**
     * Validates every property of a form-member modify against the introspected schema and builds the
     * ordered list of {@link HolderChange}s to apply - each pairing a {@link PreparedChange} with the
     * receiver it targets: the member itself for a direct feature, or the member's nested
     * {@code <extInfo>} holder for a layout / kind-specific property (a UsualGroup's grouping / united /
     * ... live under {@code <extInfo>}, not on the group element). Runs inside the BM write transaction
     * (called from the {@code writeEditableForm} callback) but performs NO model mutation itself - it
     * only reads {@code member}'s (and its extInfo's) schema and constructs the changes; a
     * structural-property guard or an invalid value throws {@link FormValidationException} BEFORE any
     * {@code eSet}, so the transaction rolls back with no partial mutation. The extInfo holder is
     * created (when absent) only at APPLY time by the caller, once every property has validated.
     */
    private List<HolderChange> prepareFormMemberChanges(Configuration config, Version version,
        EObject member, List<JsonObject> properties, MdNameNormalizer.Report normReport)
    {
        // Reject a classifier `type` change batched with a nested-extInfo layout prop BEFORE building any
        // change: the extInfo props are validated against the pre-change type's extInfo EClass, so
        // applying both in one tx is order-dependent and unsafe (see formTypeExtInfoComboError).
        String comboErr = formTypeExtInfoComboError(member, properties);
        if (comboErr != null)
        {
            throw new FormValidationException(comboErr);
        }
        List<HolderChange> changes = new ArrayList<>();
        for (JsonObject prop : properties)
        {
            String guard = guardFormProperty(prop);
            if (guard != null)
            {
                throw new FormValidationException(guard);
            }
            changes.add(prepareFormMemberChange(config, version, member, prop, normReport));
        }
        return changes;
    }

    /**
     * Rejects a form-member modify that UNSAFELY combines a classifier {@code type} change (a group's /
     * field's / decoration's {@code type} decides which concrete {@code <extInfo>} EClass applies) with a
     * property that lives on that nested {@code <extInfo>}, in the SAME call. The extInfo props are
     * classified / validated against the PRE-change type's extInfo EClass (in {@link #resolveFormHolder}),
     * so applying both in one transaction is order-dependent and unsafe:
     * <ul>
     * <li>{@code type} first &rarr; {@link FormElementWriter#ensureExtInfo} creates the NEW type's extInfo
     * EClass and the extInfo {@code eSet} throws {@link IllegalArgumentException} on a feature the new
     * EClass lacks (surfaced as an opaque "Failed to modify form member");</li>
     * <li>the extInfo prop first &rarr; a stale-typed extInfo is force-exported onto a now-differently
     * typed element (a silent inconsistency EDT serialization rejects).</li>
     * </ul>
     * The {@code type} change must be a SEPARATE call so the extInfo is re-resolved against the new type.
     * Detection is fully reflective (the direct-vs-extInfo routing from {@link #resolveFormHolder} plus the
     * normalized property name) - a form attribute's {@code type} is normalized to {@code valueType} and so
     * never counts here, and an mdclass object has no extInfo so this is a no-op. Package-visible so it is
     * unit-testable headlessly. Returns a ready JSON error to reject, or {@code null} when the batch is safe.
     */
    static String formTypeExtInfoComboError(EObject member, List<JsonObject> properties)
    {
        boolean hasDirectTypeChange = false;
        boolean hasExtInfoChange = false;
        for (JsonObject prop : properties)
        {
            String name = asString(normalizeFormProperty(member, prop).get("name")); //$NON-NLS-1$
            if (name == null || name.isEmpty())
            {
                continue;
            }
            if (resolveFormHolder(member, name).onExtInfo)
            {
                hasExtInfoChange = true;
            }
            else if ("type".equalsIgnoreCase(name)) //$NON-NLS-1$
            {
                hasDirectTypeChange = true;
            }
        }
        if (hasDirectTypeChange && hasExtInfoChange)
        {
            return ToolResult.error("Changing a form group's 'type' cannot be combined with a layout " //$NON-NLS-1$
                + "property that lives on its <extInfo> (e.g. 'group' / 'united' / 'showLeftMargin' / " //$NON-NLS-1$
                + "'throughAlign' / 'currentRowUse' / 'representation') in the same call, because the " //$NON-NLS-1$
                + "'type' decides which extInfo applies. Change the 'type' first, then set the layout " //$NON-NLS-1$
                + "properties in a separate call.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Validates ONE form-member property and pairs the resulting {@link PreparedChange} with the
     * receiver it must be applied to. The receiver is decided reflectively: a DIRECT feature of the
     * member stays on the member; a property that lives on the member's nested {@code <extInfo>} (a
     * layout / kind-specific sub-object) is flagged {@code onExtInfo} so the caller routes the
     * {@code eSet} to the extInfo holder. The change is BUILT against the same extInfo the receiver was
     * chosen from (so the enum / boolean / ... value is coerced to the correct feature); an invalid
     * value throws {@link FormValidationException} BEFORE any mutation.
     */
    private HolderChange prepareFormMemberChange(Configuration config, Version version, EObject member,
        JsonObject prop, MdNameNormalizer.Report normReport)
    {
        JsonObject normProp = normalizeFormProperty(member, prop);
        FormHolder holder = resolveFormHolder(member, asString(normProp.get("name"))); //$NON-NLS-1$
        List<PreparedChange> built = new ArrayList<>();
        String pErr = prepare(config, version, member, holder.classifyExtInfo, normProp, built, normReport);
        if (pErr != null)
        {
            throw new FormValidationException(pErr);
        }
        // prepare() appends exactly one change on success.
        return new HolderChange(holder.onExtInfo, built.get(0));
    }

    /**
     * Resolves the write receiver for a form-member property named {@code propName}: whether it lives
     * on the member directly or on the member's nested {@code <extInfo>}, plus the extInfo instance the
     * property is classified against. A DIRECT feature wins (mirroring {@link
     * MetadataPropertyIntrospector#findFeature(EObject, EObject, String)}). When the element carries no
     * extInfo instance yet but CAN (a form group's layout props live under an as-yet-uncreated
     * {@code <extInfo>}), the property is classified against a THROWAWAY (unattached) instance of the
     * element's concrete extInfo EClass, so an extInfo property is visible WITHOUT mutating the model
     * during validation - the real extInfo holder is created (and reused) at apply time via
     * {@link FormElementWriter#ensureExtInfo}. Fully reflective; a no-op for an element with no extInfo.
     */
    static FormHolder resolveFormHolder(EObject member, String propName)
    {
        EObject extInfo = extInfoOf(member);
        // A form group whose live extInfo no longer matches its `type` is STALE (the type was changed):
        // classify against the type-AUTHORITATIVE extInfo, not the stale holder, so a property is
        // validated against the class ensureExtInfo will actually (re)create at apply time (#235 review).
        EClass authoritative = FormElementWriter.resolveExtInfoEClass(member);
        boolean stale = extInfo != null && authoritative != null
            && !extInfo.eClass().getName().equals(authoritative.getName());
        EObject classifyAgainst = stale ? null : extInfo;
        PropertyInfo info = MetadataPropertyIntrospector.findFeature(member, classifyAgainst, propName);
        if (info == null && classifyAgainst == null && propName != null && !propName.isEmpty()
            && authoritative != null && !authoritative.isAbstract() && authoritative.getEPackage() != null)
        {
            EObject probe = authoritative.getEPackage().getEFactoryInstance().create(authoritative);
            PropertyInfo onProbe = MetadataPropertyIntrospector.findFeature(member, probe, propName);
            if (onProbe != null && onProbe.onExtInfo)
            {
                return new FormHolder(true, probe);
            }
        }
        return new FormHolder(info != null && info.onExtInfo, classifyAgainst);
    }

    /**
     * The element's nested {@code <extInfo>} EObject, read reflectively from the single-valued
     * {@code extInfo} containment reference, or {@code null} when the element has no such feature (an
     * mdclass object) or the slot is empty. Self-contained (no form-model import).
     */
    private static EObject extInfoOf(EObject element)
    {
        EStructuralFeature feature = element.eClass().getEStructuralFeature("extInfo"); //$NON-NLS-1$
        if (feature instanceof EReference && !feature.isMany())
        {
            Object value = element.eGet(feature);
            if (value instanceof EObject)
            {
                return (EObject)value;
            }
        }
        return null;
    }

    /**
     * The move property names (the structural re-parent / reorder of a form item): {@code parent}
     * (the destination container - a group, the {@code AutoCommandBar} token, a table - or the form
     * name / blank for the form root) and {@code position}
     * (the destination order: {@code first} / {@code last} / {@code before:<name>} / {@code after:<name>}
     * / a 0-based integer index). They are bilingual: ru {@code roditel} / ru {@code poziciya}.
     */
    private static final String PROP_PARENT = "parent"; //$NON-NLS-1$
    private static final String PROP_POSITION = "position"; //$NON-NLS-1$
    // ru "родитель" (roditel) / "позиция" (poziciya) - pure-ASCII source (matching the rest of the project).
    private static final String RU_PROP_PARENT =
        MetadataLanguageUtils.cp(0x0440, 0x043e, 0x0434, 0x0438, 0x0442, 0x0435, 0x043b, 0x044c);
    private static final String RU_PROP_POSITION =
        MetadataLanguageUtils.cp(0x043f, 0x043e, 0x0437, 0x0438, 0x0446, 0x0438, 0x044f);

    /** Whether a property NAME is the {@code parent} move property (English or Russian). */
    private static boolean isParentProp(String name)
    {
        return PROP_PARENT.equalsIgnoreCase(name) || RU_PROP_PARENT.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code position} move property (English or Russian). */
    private static boolean isPositionProp(String name)
    {
        return PROP_POSITION.equalsIgnoreCase(name) || RU_PROP_POSITION.equalsIgnoreCase(name);
    }

    /** Whether any property in the list is a move property ({@code parent} / {@code position}). */
    private static boolean hasMoveProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isParentProp(name) || isPositionProp(name))
            {
                return true;
            }
        }
        return false;
    }

    /** The rebind property names. {@code procedure} (alias {@code handler}) rebinds a handler's BSL
     * procedure; {@code command} (alias {@code commandName}) re-points a button at a form command. */
    private static final String PROP_PROCEDURE = "procedure"; //$NON-NLS-1$
    private static final String PROP_HANDLER = "handler"; //$NON-NLS-1$
    private static final String PROP_COMMAND = "command"; //$NON-NLS-1$
    private static final String PROP_COMMAND_NAME = "commandName"; //$NON-NLS-1$

    /**
     * The new BSL procedure name from a {@code procedure} (or {@code handler} alias) property on a
     * handler-rebind call, or {@code null} when no such property is present. The same key
     * {@code create_metadata} accepts when binding a handler.
     */
    private static String handlerProcedureValue(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_PROCEDURE.equalsIgnoreCase(name) || PROP_HANDLER.equalsIgnoreCase(name))
            {
                return asString(prop.get(KEY_VALUE));
            }
        }
        return null;
    }

    /**
     * The name of the first property that is NOT the handler-rebind property ({@code procedure} /
     * {@code handler} alias), or {@code null} when the list carries only rebind properties. Used to
     * REJECT a handler-rebind call that mixes in other property changes (which the rebind path would
     * otherwise silently drop). Package-visible for tests.
     */
    static String firstNonHandlerRebindProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (!PROP_PROCEDURE.equalsIgnoreCase(name) && !PROP_HANDLER.equalsIgnoreCase(name))
            {
                return name;
            }
        }
        return null;
    }

    /** Whether any property in the list re-points a button at a form command ({@code command}). */
    private static boolean hasCommandProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_COMMAND.equalsIgnoreCase(name) || PROP_COMMAND_NAME.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves / reorders a form ITEM addressed by {@code ref} (a field / group / decoration / button /
     * table), expressed as the {@code parent} and/or {@code position} move properties. Resolves the
     * MD-form, opens ONE BM write transaction on the re-fetched content form, re-parents / reorders the
     * item via {@link FormElementWriter#moveItem} (which rejects an ambiguous / missing item, an
     * unknown parent - the error advertises the {@code AutoCommandBar} token - a placement the
     * designer forbids and a containment cycle, rolling the tx back), then
     * force-exports the CONTENT form to its {@code Form.form} on disk - the same persistence path the
     * property-modify branch uses. Position semantics match the dedicated move primitive exactly (the
     * integer index is the desired FINAL 0-based position).
     */
    private String moveFormItem(ProjectContext ctx, String normFqn, // NOSONAR form-move orchestration: structural validation + move dispatch; the mutating write stays inline, further extraction deferred
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        // A move addresses a form ITEM only - never an attribute / command (which are not in the items
        // tree and have no position / parent).
        FormElementWriter.Kind kind = FormElementWriter.kindForToken(ref.kindToken);
        if (kind == FormElementWriter.Kind.ATTRIBUTE || kind == FormElementWriter.Kind.COMMAND)
        {
            return ToolResult.error("'parent' / 'position' move a form ITEM (field / group / " //$NON-NLS-1$
                + "decoration / button / table); a form " + ref.kindToken + " is not positioned. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Address the item by its FQN, e.g. 'Type.Object.Form.FormName.Field.Price'.").toJson(); //$NON-NLS-1$
        }

        // A move is structural - it must not be mixed with ordinary property changes in one call.
        String targetParent = null;
        boolean hasParent = false;
        String position = null;
        boolean hasPosition = false;
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isParentProp(name))
            {
                targetParent = asString(prop.get(KEY_VALUE));
                hasParent = true;
            }
            else if (isPositionProp(name))
            {
                position = asString(prop.get(KEY_VALUE));
                hasPosition = true;
            }
            else
            {
                return ToolResult.error("A move ('parent' / 'position') cannot be combined with other " //$NON-NLS-1$
                    + "property changes ('" + name + "') in one call. Move the item first, then modify " //$NON-NLS-1$ //$NON-NLS-2$
                    + "its properties in a separate call.").toJson(); //$NON-NLS-1$
            }
        }
        if (!hasParent && !hasPosition)
        {
            return ToolResult.error("Nothing to move: provide 'parent' (to re-parent) and/or " //$NON-NLS-1$
                + "'position' (to reorder).").toJson(); //$NON-NLS-1$
        }
        // A re-parent with no explicit position appends to the destination (position stays null); a pure
        // reorder keeps the current parent (targetParent stays null).
        final String targetParentFinal;
        if (!hasParent)
        {
            targetParentFinal = null;
        }
        else
        {
            targetParentFinal = targetParent == null ? "" : targetParent; //$NON-NLS-1$
        }
        final String positionFinal = position;

        final String itemName = ref.name;
        final String[] destination = new String[1];
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a form item as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name'."); //$NON-NLS-1$
            final String mdFormName = fctx.mdForm.getName();
            persisted = FormElementWriter.writeEditableForm(fctx, "MoveFormItem", //$NON-NLS-1$
                (formModel, tx) -> destination[0] = FormElementWriter.moveItem(formModel, itemName,
                    targetParentFinal, positionFinal, mdFormName));
        }
        catch (Exception e)
        {
            return moveFormItemError(e);
        }

        List<String> applied = new ArrayList<>();
        if (hasParent)
        {
            applied.add(PROP_PARENT);
        }
        if (hasPosition)
        {
            applied.add(PROP_POSITION);
        }
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted)
            .put("destination", destination[0]) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Moved form item '" + itemName + "' to " + destination[0]) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Maps a failure from the {@link #moveFormItem} write transaction to its error JSON: a structured
     * {@link FormValidationException} payload when present (the move primitive rejected the item / parent /
     * placement and rolled the tx back), otherwise a generic "Failed to move form item" error built from
     * the unwrapped cause message. Mirrors the catch arm of {@code moveFormItem} verbatim.
     */
    private String moveFormItemError(Exception e)
    {
        String validationJson = FormValidationException.jsonOf(e);
        if (validationJson != null)
        {
            return validationJson;
        }
        Activator.logError("Error moving form item", e); //$NON-NLS-1$
        return ToolResult.error("Failed to move form item: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
    }

    /**
     * REBINDS an existing event handler (addressed by a handler FQN, {@code ...Handler.Event} at form
     * or item level) to a different BSL procedure {@code procName}. Resolves the MD-form, opens ONE BM
     * write transaction on the re-fetched content form, resolves the handler's CONTAINER via
     * {@link FormElementWriter#resolveHandlerContainer} (the form root, the named item, or the form
     * COMMAND for a {@code ...Command.C.Handler.Action} FQN - so a command's Action procedure is
     * rebindable too), re-points the existing handler via {@link
     * FormElementWriter#rebindHandler} (which fails clearly when no handler for the event exists, so the
     * tx rolls back), then force-exports the CONTENT form to its {@code Form.form} on disk - the same
     * persistence path the property-modify branch uses. Does NOT bind a NEW event (that is
     * create_metadata's job); a single {@code procedure} property is the whole change.
     */
    private String rebindFormHandler(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, String procName)
    {
        final String eventName = ref.name;
        final boolean commandOwner = ref.isItemLevel()
            && FormElementWriter.kindForToken(ref.itemKindToken) == FormElementWriter.Kind.COMMAND;
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a handler as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Handler.Event' or " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<ItemKind>.<ItemName>.Handler.Event'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "RebindFormHandler", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // Form-level handlers live on the form root; item-level handlers on the named
                    // item; a COMMAND ref (...Command.C.Handler.Action) on the form command - the
                    // same resolution create_metadata / delete_metadata use.
                    EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
                    if (container == null)
                    {
                        throw new FormValidationException(ToolResult.error((commandOwner
                            ? "Form command not found: " : "Form item not found: ") + ref.itemName //$NON-NLS-1$ //$NON-NLS-2$
                            + ". Use get_metadata_details to inspect the form items.").toJson()); //$NON-NLS-1$
                    }
                    String err = FormElementWriter.rebindHandler(container, eventName, procName);
                    if (err != null)
                    {
                        throw new FormValidationException(ToolResult.error(err).toJson());
                    }
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error rebinding form handler", e); //$NON-NLS-1$
            return ToolResult.error("Failed to rebind form handler: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, java.util.Collections.singletonList(PROP_PROCEDURE))
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Rebound the handler for event '" + eventName + "' to procedure '" //$NON-NLS-1$ //$NON-NLS-2$
                + procName + "'") //$NON-NLS-1$
            .toJson();
    }

    /**
     * RE-POINTS an existing button (a Button form item) at a different (existing) form command. A
     * button's {@code commandName} references a FormCommand (a form-model object, not an mdclass
     * object), so it is not introspector-assignable and is rebound here. Resolves the MD-form, opens ONE
     * BM write transaction on the re-fetched content form, resolves the button and re-points it via
     * {@link FormElementWriter#rebindButtonCommand} (which validates the command exists, rolling the tx
     * back otherwise), then force-exports the CONTENT form to its {@code Form.form} on disk. A
     * {@code command} change is structural-by-reference and must not be mixed with other property
     * changes in one call.
     */
    private String rebindButtonCommand(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        String commandName = null;
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_COMMAND.equalsIgnoreCase(name) || PROP_COMMAND_NAME.equalsIgnoreCase(name))
            {
                commandName = asString(prop.get(KEY_VALUE));
            }
            else
            {
                return ToolResult.error("Re-pointing a button's command ('command') cannot be combined " //$NON-NLS-1$
                    + "with other property changes ('" + name + "') in one call. Rebind the command " //$NON-NLS-1$ //$NON-NLS-2$
                    + "first, then modify the button's properties in a separate call.").toJson(); //$NON-NLS-1$
            }
        }
        if (commandName == null || commandName.isEmpty())
        {
            return ToolResult.error("Provide the form command to point the button at in the 'command' " //$NON-NLS-1$
                + "property (e.g. {name:'command', value:'Refresh'}).").toJson(); //$NON-NLS-1$
        }

        final String buttonName = ref.name;
        final String commandNameFinal = commandName;
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a button as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Button.Name' or 'CommonForm.FormName.Button.Name'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "RebindButtonCommand", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // Strict resolution: an AMBIGUOUS button name (several items by that name anywhere
                    // in the form-item tree) is rejected with a clear error instead of silently
                    // re-pointing the first match (findUniqueFormItem throws; the tx rolls back).
                    EObject button = FormElementWriter.findUniqueFormItem(formModel, buttonName);
                    if (button == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form button not found: " //$NON-NLS-1$
                            + buttonName + ". Use get_metadata_details to inspect the form items.") //$NON-NLS-1$
                            .toJson());
                    }
                    String err =
                        FormElementWriter.rebindButtonCommand(formModel, button, commandNameFinal);
                    if (err != null)
                    {
                        throw new FormValidationException(ToolResult.error(err).toJson());
                    }
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error rebinding button command", e); //$NON-NLS-1$
            return ToolResult.error("Failed to rebind button command: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, java.util.Collections.singletonList(PROP_COMMAND))
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Re-pointed button '" + buttonName + "' at command '" //$NON-NLS-1$ //$NON-NLS-2$
                + commandNameFinal + "'") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Refuses the structural form property a client must not set as a value: {@code id} (the
     * form-wide-unique item id is allocated automatically). The {@code name} (rename) property is
     * already refused by {@link #prepare}. Returns a JSON error to reject, or {@code null} to allow.
     */
    private static String guardFormProperty(JsonObject prop)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if ("id".equalsIgnoreCase(name)) //$NON-NLS-1$
        {
            return ToolResult.error("The form item 'id' is allocated automatically and must stay " //$NON-NLS-1$
                + "form-wide unique - it cannot be set.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Maps the friendly {@code type} alias to a form attribute's real {@code valueType} feature so a
     * form attribute's data type is set with the same {@code {name:'type', value:{types:[...]}}} shape
     * mdclass attributes use. Returns the original prop unchanged when no alias applies.
     */
    private static JsonObject normalizeFormProperty(EObject member, JsonObject prop)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if ("type".equalsIgnoreCase(name) //$NON-NLS-1$
            && member.eClass().getEStructuralFeature("type") == null //$NON-NLS-1$
            && member.eClass().getEStructuralFeature(PROP_VALUE_TYPE) != null)
        {
            JsonObject copy = prop.deepCopy();
            copy.addProperty("name", PROP_VALUE_TYPE); //$NON-NLS-1$
            return copy;
        }
        return prop;
    }

    /**
     * Validates one property against the introspected schema and, on success, appends a
     * {@link PreparedChange}. Returns a JSON error string on failure, or {@code null} on success.
     * Accepts any {@link EObject} so it serves both mdclass nodes and form members (the introspector
     * and the prepared change are EClass-driven, not mdclass-specific).
     *
     * <p>{@code extInfo} is the element's nested {@code <extInfo>} EObject (a form element's layout /
     * kind-specific sub-object, e.g. a UsualGroup's {@code UsualGroupExtInfo}) when the property may
     * live there, or {@code null} on the mdclass path (an mdclass object has no extInfo, so the
     * extInfo traversal is a no-op and this behaves exactly as before). A property found on the
     * extInfo carries {@code info.onExtInfo == true}; the {@link PreparedChange} is built against the
     * extInfo's feature, and the caller routes the {@code eSet} to the extInfo holder.</p>
     */
    private String prepare(Configuration config, Version version, EObject target, EObject extInfo,
        JsonObject prop, List<PreparedChange> out, MdNameNormalizer.Report normReport)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("Each entry in 'properties' needs a non-empty 'name'.").toJson(); //$NON-NLS-1$
        }
        if ("name".equalsIgnoreCase(name)) //$NON-NLS-1$
        {
            return ToolResult.error("Renaming via the 'name' property is not allowed here: use " //$NON-NLS-1$
                + "rename_metadata_object, which cascades the rename across BSL code, forms and " //$NON-NLS-1$
                + "metadata. modify_metadata only sets non-identity properties.").toJson(); //$NON-NLS-1$
        }
        String value = asString(prop.get(KEY_VALUE));

        // findFeature classifies ONLY the matched feature and skips the current-value rendering
        // (eGet + proxy + type rendering) that the full introspect() performs for EVERY assignable
        // feature - prepare() never reads currentValue, and this runs on the UI thread per property.
        // A direct feature of `target` wins; only when it has none does a matching feature on the
        // element's `extInfo` win (info.onExtInfo). On the mdclass path extInfo is null - a no-op.
        PropertyInfo info = MetadataPropertyIntrospector.findFeature(target, extInfo, name);
        if (info == null)
        {
            // The "available properties" hint covers the extInfo layout props too (the now-extended
            // assignable set): its EClass comes from the live extInfo instance, or - when the slot is
            // empty - is derived reflectively; null on the mdclass path (member-only, unchanged).
            EClass extInfoEClass = extInfo != null ? extInfo.eClass()
                : FormElementWriter.resolveExtInfoEClass(target);
            return ToolResult.error("Property '" + name + "' is not assignable on " //$NON-NLS-1$ //$NON-NLS-2$
                + target.eClass().getName() + ". Assignable properties: " //$NON-NLS-1$
                + String.join(", ", MetadataPropertyIntrospector.assignableNames(target, extInfoEClass)) //$NON-NLS-1$
                + ". Use get_metadata_details with assignable:true for kinds + allowed values.").toJson(); //$NON-NLS-1$
        }

        switch (info.valueKind)
        {
            case LOCALIZED_STRING:
                return prepareLocalized(config, name, value, prop, info, out, normReport);
            case ENUM:
            {
                EEnumLiteral literal = MetadataPropertyIntrospector.resolveEnumLiteral(info.feature, value);
                if (literal == null)
                {
                    return ToolResult.error("'" + value + "' is not a valid value for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Allowed: " + String.join(", ", info.allowedValues) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                out.add(PreparedChange.scalar(info.feature, literal.getInstance()));
                return null;
            }
            case BOOLEAN:
            {
                Boolean b = parseBoolean(value);
                if (b == null)
                {
                    return ToolResult.error("'" + value + "' is not a valid boolean for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Use true or false.").toJson(); //$NON-NLS-1$
                }
                out.add(PreparedChange.scalar(info.feature, b));
                return null;
            }
            case INTEGER:
            {
                Integer i = parseInteger(value);
                if (i == null)
                {
                    return ToolResult.error("'" + value + "' is not a valid integer for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "'.").toJson(); //$NON-NLS-1$
                }
                out.add(PreparedChange.scalar(info.feature, i));
                return null;
            }
            case TYPE_DESCRIPTION:
                return prepareTypeDescription(config, version, name, prop, info, out);
            case REFERENCE:
                return prepareReference(config, name, value, info, out);
            case MANY_REFERENCE:
                return prepareManyReference(config, name, prop, info, out);
            case STYLE_VALUE:
                return prepareStyleValue(name, prop, target, info, out);
            case STRING:
            default:
                if (value == null || value.isEmpty())
                {
                    return requireValueError(name);
                }
                out.add(PreparedChange.scalar(info.feature,
                    normalizeStringPropertyValue(name, value, normReport)));
                return null;
        }
    }

    /**
     * Validates a {@code LOCALIZED_STRING} property (resolving its synonym language code) and, on
     * success, appends the prepared localized change to {@code out}. Returns a JSON error on failure,
     * or {@code null} on success. Read-only: it only builds and queues the change (no model mutation).
     */
    private String prepareLocalized(Configuration config, String name, String value, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out, MdNameNormalizer.Report normReport)
    {
        if (value == null || value.isEmpty())
        {
            return requireValueError(name);
        }
        String code;
        try
        {
            code = MetadataLanguageUtils.resolveSynonymLanguage(config, value,
                asString(prop.get("language")), "'" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        out.add(PreparedChange.localized(info.feature, code, normReport.apply(name, value)));
        return null;
    }

    /**
     * Validates a {@code TYPE_DESCRIPTION} property (building the type description for the resolved
     * platform version) and, on success, appends the prepared scalar change to {@code out}. Returns a
     * JSON error on failure, or {@code null} on success. Read-only: it only builds and queues the
     * change (no model mutation).
     */
    private String prepareTypeDescription(Configuration config, Version version, String name,
        JsonObject prop, PropertyInfo info, List<PreparedChange> out)
    {
        if (version == null)
        {
            return ToolResult.error("Cannot resolve the platform version needed to build a " //$NON-NLS-1$
                + "type for '" + name + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MetadataTypeBuilder.Result tr =
            MetadataTypeBuilder.build(prop.get(KEY_VALUE), config, version);
        if (tr.error != null)
        {
            return ToolResult.error("Invalid 'type' for '" + name + "': " + tr.error).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.add(PreparedChange.typeDescription(info.feature, tr.typeDescription));
        return null;
    }

    /**
     * Validates a single-valued {@code REFERENCE} property (resolving and type-checking its FQN target)
     * and, on success, appends the prepared reference change to {@code out}. Returns a JSON error on
     * failure, or {@code null} on success. Read-only: it only builds and queues the change (no model
     * mutation).
     */
    private String prepareReference(Configuration config, String name, String value, PropertyInfo info,
        List<PreparedChange> out)
    {
        if (value == null || value.isEmpty())
        {
            return requireValueError(name);
        }
        MdObject targetMd = resolveReferenceTarget(config, value);
        String vErr = validateReferenceTarget(name, info.feature, targetMd, value);
        if (vErr != null)
        {
            return vErr;
        }
        out.add(PreparedChange.reference(info.feature, ((IBmObject)targetMd).bmGetId()));
        return null;
    }

    /**
     * Validates a {@code MANY_REFERENCE} property (the value must be a JSON array of non-empty FQNs,
     * each resolving and type-checking) and, on success, appends the prepared list-reference change to
     * {@code out}. Returns a JSON error on failure, or {@code null} on success. Read-only: it only
     * builds and queues the change (no model mutation).
     */
    private String prepareManyReference(Configuration config, String name, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out)
    {
        JsonElement raw = prop.get(KEY_VALUE);
        if (raw == null || !raw.isJsonArray())
        {
            return ToolResult.error("'" + name + "' is a list reference: provide 'value' as an " //$NON-NLS-1$ //$NON-NLS-2$
                + "array of FQNs, e.g. [\"Catalog.Products\", \"Document.Order\"].").toJson(); //$NON-NLS-1$
        }
        List<Long> ids = new ArrayList<>();
        for (JsonElement el : raw.getAsJsonArray())
        {
            String fqn = (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
            if (fqn == null || fqn.isEmpty())
            {
                return ToolResult.error("Each entry of the '" + name + "' list must be a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "non-empty FQN string.").toJson(); //$NON-NLS-1$
            }
            MdObject t = resolveReferenceTarget(config, fqn);
            String vErr = validateReferenceTarget(name, info.feature, t, fqn);
            if (vErr != null)
            {
                return vErr;
            }
            ids.add(((IBmObject)t).bmGetId());
        }
        out.add(PreparedChange.manyReference(info.feature, ids));
        return null;
    }

    /**
     * Validates a {@code STYLE_VALUE} property (building the StyleItem Color / Font value) and, on
     * success, appends the prepared style-value change to {@code out} (which also keeps the sibling
     * {@code type} feature consistent with the value). Returns a JSON error on failure, or {@code null}
     * on success. Read-only: it only builds and queues the change (no model mutation).
     */
    private String prepareStyleValue(String name, JsonObject prop, EObject target, PropertyInfo info,
        List<PreparedChange> out)
    {
        StyleValueBuilder.Result sv = StyleValueBuilder.build(prop.get(KEY_VALUE));
        if (sv.error != null)
        {
            return ToolResult.error("Invalid StyleItem '" + name + "': " + sv.error).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The style item's `type` (Color / Font) is kept consistent with the value it holds, so
        // the change sets both the `value` and the sibling `type` feature in one shot.
        EStructuralFeature typeFeature = target.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        out.add(PreparedChange.styleValue(info.feature, typeFeature, sv.value, sv.type));
        return null;
    }

    /**
     * Applies the yo-to-ye normalization to a free STRING property value with a deliberately
     * NARROW scope: only the {@code comment} property is normalized (it is presentation text
     * checked by the same EDT validator, 1C standard #std474, as names and synonyms). Every
     * other free STRING feature can be identifier-like — e.g. {@code XDTOPackage.namespace}
     * is a URI — where a silent yo-to-ye rewrite would corrupt the value, so the caller's
     * text is kept verbatim. LOCALIZED_STRING values are normalized separately (see the
     * LOCALIZED_STRING branch of {@code prepare}).
     *
     * @param name the property name as supplied by the caller
     * @param value the non-empty property value
     * @param normReport the normalization report (honors the {@code normalizeYo} toggle)
     * @return the value to assign — normalized for {@code comment}, verbatim otherwise
     */
    static String normalizeStringPropertyValue(String name, String value,
        MdNameNormalizer.Report normReport)
    {
        return "comment".equalsIgnoreCase(name) ? normReport.apply(name, value) : value; //$NON-NLS-1$
    }

    /** Resolves a reference-target FQN to its metadata object (a top object), or {@code null}. */
    private static MdObject resolveReferenceTarget(Configuration config, String fqn)
    {
        String norm = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode n = MetadataNodeResolver.resolveExisting(config, norm);
        return n != null ? n.object : null;
    }

    /**
     * Validates a reference target: it must resolve, be a re-fetchable top object, and have a type
     * assignable to the reference feature's target type. Returns a JSON error or {@code null} on OK.
     */
    private static String validateReferenceTarget(String prop, EStructuralFeature feature,
        MdObject target, String fqn)
    {
        if (target == null)
        {
            return ToolResult.error("Reference target '" + fqn + "' for '" + prop + "' was not found. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "Use a valid FQN (e.g. 'Catalog.Products'); check with get_metadata_objects.").toJson(); //$NON-NLS-1$
        }
        if (!(target instanceof IBmObject) || !((IBmObject)target).bmIsTop())
        {
            return ToolResult.error("Reference target '" + fqn + "' for '" + prop + "' must be a " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "top-level object; references to members are not supported.").toJson(); //$NON-NLS-1$
        }
        EClass targetType = ((EReference)feature).getEReferenceType();
        if (targetType != null && !targetType.isSuperTypeOf(target.eClass()))
        {
            return ToolResult.error("'" + fqn + MSG_IS_A + target.eClass().getName() + " but '" + prop //$NON-NLS-1$ //$NON-NLS-2$
                + "' requires a " + targetType.getName() + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Error for a missing / empty {@code value}: this tool never clears a property on an omitted
     * value (a clear must be explicit), matching the former set_metadata_property's "empty = not
     * provided" guard.
     */
    private static String requireValueError(String name)
    {
        return ToolResult.error("Property '" + name + "' needs a non-empty 'value'. modify_metadata " //$NON-NLS-1$ //$NON-NLS-2$
            + "does not clear a property on an empty value.").toJson(); //$NON-NLS-1$
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A {@link PreparedChange} paired with WHERE it must be applied: {@code onExtInfo == false} targets
     * the form member itself (a direct feature); {@code onExtInfo == true} targets the member's nested
     * {@code <extInfo>} holder (a layout / kind-specific property - a UsualGroup's grouping / united /
     * ... live under {@code <extInfo>}). Threading the receiver per property lets a mixed direct +
     * extInfo batch apply each change to the correct EObject inside the one form write transaction.
     */
    private static final class HolderChange
    {
        private final boolean onExtInfo;
        private final PreparedChange change;

        HolderChange(boolean onExtInfo, PreparedChange change)
        {
            this.onExtInfo = onExtInfo;
            this.change = change;
        }
    }

    /**
     * The resolved receiver for a form-member property: {@code onExtInfo} tells the caller whether the
     * write goes to the member's nested {@code <extInfo>} holder (vs the member itself), and
     * {@code classifyExtInfo} is the extInfo instance the property was classified against (a live
     * instance, a throwaway probe for an as-yet-uncreated extInfo, or {@code null} for a pure-direct
     * member) - passed to {@link #prepare} so the value is coerced to the correct feature. Carries no
     * behaviour. Package-visible so {@link #resolveFormHolder} is unit-testable.
     */
    static final class FormHolder
    {
        final boolean onExtInfo;
        final EObject classifyExtInfo;

        FormHolder(boolean onExtInfo, EObject classifyExtInfo)
        {
            this.onExtInfo = onExtInfo;
            this.classifyExtInfo = classifyExtInfo;
        }
    }

    /**
     * The STYLE_VALUE-only binding of a {@link PreparedChange}: the sibling {@code type} feature
     * (Color / Font) and the {@link StyleElementType} to set on it alongside the value, so the style
     * item's type stays consistent with the value it holds. A parameter-object that keeps the
     * {@link PreparedChange} constructor below the 7-parameter bar; {@code null} for every non-style
     * change. Carries no behaviour.
     */
    private static final class StyleBinding
    {
        private final EStructuralFeature typeFeature;
        private final StyleElementType type;

        StyleBinding(EStructuralFeature typeFeature, StyleElementType type)
        {
            this.typeFeature = typeFeature;
            this.type = type;
        }
    }

    /** A validated, coerced change ready to apply to the re-fetched target inside the write tx. */
    private static final class PreparedChange
    {
        private enum Kind { SCALAR, LOCALIZED, REFERENCE, MANY_REFERENCE, STYLE_VALUE }

        private final EStructuralFeature feature;
        private final Kind kind;
        private final Object scalarValue;
        private final String localizedLanguage;
        private final String localizedValue;
        /** For a REFERENCE: the target's bmId. For a MANY_REFERENCE: the targets' bmIds in order. */
        private final List<Long> referenceBmIds;
        /** For a STYLE_VALUE: the sibling `type` feature + StyleElementType; {@code null} otherwise. */
        private final StyleBinding styleBinding;
        /**
         * {@code true} for a {@code TYPE_DESCRIPTION} change (the object's / attribute's {@code type} /
         * form-attribute {@code valueType}). This is the destructive case the consent gate prompts on:
         * retyping data can drop stored values on a database update. Every benign change is {@code false}.
         */
        private final boolean typeChange;

        private PreparedChange(EStructuralFeature feature, Kind kind, Object scalarValue, // NOSONAR cohesive discriminated-union payload, one field per Kind (already reduced by StyleBinding); a further param-object would fragment it
            String language, String localizedValue, List<Long> referenceBmIds,
            StyleBinding styleBinding, boolean typeChange)
        {
            this.feature = feature;
            this.kind = kind;
            this.scalarValue = scalarValue;
            this.localizedLanguage = language;
            this.localizedValue = localizedValue;
            this.referenceBmIds = referenceBmIds;
            this.styleBinding = styleBinding;
            this.typeChange = typeChange;
        }

        static PreparedChange scalar(EStructuralFeature feature, Object value)
        {
            return new PreparedChange(feature, Kind.SCALAR, value, null, null, null, null, false);
        }

        /**
         * A {@code TYPE_DESCRIPTION} change: a scalar set of a freshly-built (detached) type description,
         * flagged {@link #typeChange} so the caller can route it through the destructive-consent gate
         * (retyping data is the destructive case a plain property edit is not).
         */
        static PreparedChange typeDescription(EStructuralFeature feature, Object typeDescription)
        {
            return new PreparedChange(feature, Kind.SCALAR, typeDescription, null, null, null, null, true);
        }

        static PreparedChange localized(EStructuralFeature feature, String language, String value)
        {
            return new PreparedChange(feature, Kind.LOCALIZED, null, language, value, null, null, false);
        }

        static PreparedChange reference(EStructuralFeature feature, long targetBmId)
        {
            return new PreparedChange(feature, Kind.REFERENCE, null, null, null,
                java.util.Collections.singletonList(targetBmId), null, false);
        }

        static PreparedChange manyReference(EStructuralFeature feature, List<Long> targetBmIds)
        {
            return new PreparedChange(feature, Kind.MANY_REFERENCE, null, null, null, targetBmIds,
                null, false);
        }

        /**
         * A StyleItem value change: the freshly-built mcore {@link Value} ({@code styleValue}) is a
         * detached containment object, so it is set directly on the re-fetched style item inside the
         * tx (like the TYPE_DESCRIPTION scalar). The sibling {@code typeFeature} (Color / Font) is set
         * to {@code type} in the same change so the style item's type stays consistent with its value.
         */
        static PreparedChange styleValue(EStructuralFeature valueFeature, EStructuralFeature typeFeature,
            Value styleValue, StyleElementType type)
        {
            return new PreparedChange(valueFeature, Kind.STYLE_VALUE, styleValue, null, null, null,
                new StyleBinding(typeFeature, type), false);
        }

        String featureName()
        {
            return feature.getName();
        }

        /** Whether this change retypes data (a {@code TYPE_DESCRIPTION} / form {@code valueType} set). */
        boolean isTypeChange()
        {
            return typeChange;
        }

        @SuppressWarnings("unchecked")
        void applyTo(EObject target, IBmTransaction tx)
        {
            switch (kind)
            {
                case LOCALIZED:
                {
                    Object map = target.eGet(feature);
                    if (!(map instanceof EMap))
                    {
                        throw new IllegalStateException("Localized feature '" + feature.getName() //$NON-NLS-1$
                            + "' is not a map"); //$NON-NLS-1$
                    }
                    ((EMap<String, String>)map).put(localizedLanguage, localizedValue);
                    return;
                }
                case REFERENCE:
                {
                    // BM normalizes the target to its in-tx counterpart by bmId on set.
                    target.eSet(feature, requireInTx(tx, referenceBmIds.get(0)));
                    return;
                }
                case MANY_REFERENCE:
                {
                    // Replace the whole list (a plain, non-containment cross-reference list, so add()
                    // only links the target - it does not reparent it).
                    EList<EObject> list = (EList<EObject>)target.eGet(feature);
                    list.clear();
                    for (Long id : referenceBmIds)
                    {
                        list.add(requireInTx(tx, id));
                    }
                    return;
                }
                case STYLE_VALUE:
                {
                    // Keep the style item's `type` consistent with the value it now holds (Color / Font),
                    // then set the freshly-built (detached) mcore Value as its containment `value`.
                    if (styleBinding != null && styleBinding.typeFeature != null
                        && styleBinding.type != null)
                    {
                        target.eSet(styleBinding.typeFeature, styleBinding.type);
                    }
                    target.eSet(feature, scalarValue);
                    return;
                }
                case SCALAR:
                default:
                    target.eSet(feature, scalarValue);
                    return;
            }
        }

        /** Re-fetches a reference target inside the tx, failing clearly if it has gone (rolls back). */
        private static EObject requireInTx(IBmTransaction tx, long bmId)
        {
            EObject t = (EObject)tx.getObjectById(bmId);
            if (t == null)
            {
                throw new IllegalStateException("Reference target is no longer in the transaction"); //$NON-NLS-1$
            }
            return t;
        }
    }

    private static String asString(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static Boolean parseBoolean(String value)
    {
        if (value == null)
        {
            return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
        }
        String v = value.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
    }

    private static Integer parseInteger(String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        try
        {
            double d = Double.parseDouble(value.trim());
            if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            {
                return null;
            }
            return Integer.valueOf((int)d);
        }
        catch (NumberFormatException e)
        {
            return null;
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

    private static String topFqn(String normFqn)
    {
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        return parts.length >= 2 ? parts[0] + "." + parts[1] : normFqn; //$NON-NLS-1$
    }
}
