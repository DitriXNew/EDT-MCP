/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.areaTemplate.AreaTemplateItem;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.AreaTemplateParameter;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplate;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateField;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateFieldAppearance;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableCell;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableCellAppearance;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableRow;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionDetailsAreaParameter;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionDetailsAreaParameterFieldExpression;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionExpressionAreaParameter;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.TemplateItem;
import com._1c.g5.v8.dt.dcs.model.common.DataCompositionAreaTemplateType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.details.DataCompositionDetailsProcessingAction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaGroupTemplate;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameter;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.util.DcsTerms;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * JSON body of a schema's area template and of a grouping binding, in both directions (#662).
 *
 * <p>{@link #apply} and {@link #applyBinding} build the model the way EDT's template designer does
 * ({@code DataCompositionAreaTemplateBuilder}): a static text is a core {@link LocalString}, a
 * parameter cell is a {@link DataCompositionParameter}, and cell or field appearance items are core
 * {@link DataCompositionParameterValue}s named by the platform appearance catalogue - plus the three
 * template-only keys the designer adds outside that catalogue ({@code HorizontalMerge},
 * {@code VerticalMerge}, {@code Details}, spelled by {@link DcsTerms}).
 *
 * <p>{@link #restate} is the inverse, and {@link #restatementRefusal} is the reproducibility guard:
 * a stored node is restated, rebuilt by the writer in a dry run, and compared with
 * {@link DcsModelComparison}. A stored value the writer cannot rebuild exactly is therefore never
 * silently replaced.
 */
public final class DcsTemplateBody
{
    static final String KEY_NAME = "name"; //$NON-NLS-1$
    static final String KEY_ROWS = "rows"; //$NON-NLS-1$
    static final String KEY_CELLS = "cells"; //$NON-NLS-1$
    static final String KEY_PARAMETERS = "parameters"; //$NON-NLS-1$
    static final String KEY_ROW = "row"; //$NON-NLS-1$
    static final String KEY_COL = "col"; //$NON-NLS-1$
    static final String KEY_GROUP_NAME = "groupName"; //$NON-NLS-1$
    static final String KEY_GROUP_FIELDS = "groupFields"; //$NON-NLS-1$
    static final String KEY_TEMPLATE_TYPE = "templateType"; //$NON-NLS-1$
    static final String KEY_TEMPLATE = "template"; //$NON-NLS-1$

    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$
    private static final String KEY_TEXT = "text"; //$NON-NLS-1$
    private static final String KEY_PARAMETER = "parameter"; //$NON-NLS-1$
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$
    private static final String KEY_VALUES = "values"; //$NON-NLS-1$
    private static final String KEY_USE = "use"; //$NON-NLS-1$
    private static final String KEY_KIND = "kind"; //$NON-NLS-1$
    private static final String KEY_APPEARANCE = "appearance"; //$NON-NLS-1$
    private static final String KEY_EXPRESSION = "expression"; //$NON-NLS-1$
    private static final String KEY_FIELD_EXPRESSION = "fieldExpression"; //$NON-NLS-1$
    private static final String KEY_FIELD = "field"; //$NON-NLS-1$
    private static final String KEY_MAIN_ACTION = "mainAction"; //$NON-NLS-1$
    private static final String KEY_UNSUPPORTED = "unsupported"; //$NON-NLS-1$
    private static final String KIND_EXPRESSION = "expression"; //$NON-NLS-1$
    private static final String KIND_DETAILS = "details"; //$NON-NLS-1$
    private static final String KIND_PARAMETER = "parameter"; //$NON-NLS-1$
    private static final String KIND_BOOLEAN = "boolean"; //$NON-NLS-1$

    private static final com._1c.g5.v8.dt.dcs.model.areaTemplate.DcsFactory AREA =
        com._1c.g5.v8.dt.dcs.model.areaTemplate.DcsFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.core.DcsFactory CORE =
        com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.schema.DcsFactory SCHEMA =
        com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE;

    private DcsTemplateBody()
    {
        // utility class
    }

    /**
     * The appearance keys EDT's template designer writes outside the platform appearance catalogue:
     * cell merges and the details parameter. Their names come from {@link DcsTerms}
     * ({@code String[]{en, ru}}), so no Cyrillic literal enters this class.
     */
    enum TemplateKey
    {
        HORIZONTAL_MERGE(DcsTerms.kDCSAppearanceHorizontalMergeTerm, true),
        VERTICAL_MERGE(DcsTerms.kDCSAppearanceVerticalMergeTerm, true),
        DETAILS(DcsTerms.kDCSAppearanceDetailTerm, false);

        private final String[] terms;
        private final boolean merge;

        TemplateKey(String[] terms, boolean merge)
        {
            this.terms = terms;
            this.merge = merge;
        }

        /** The name the configuration's default language writes (the catalogue's own rule). */
        String spelled(DcsPresentationParser.LanguageContext languages)
        {
            String code = languages == null ? "en" : languages.configurationCode(); //$NON-NLS-1$
            return terms["ru".equalsIgnoreCase(code) && terms.length > 1 ? 1 : 0]; //$NON-NLS-1$
        }

        String english()
        {
            return terms[0];
        }

        static TemplateKey of(String name)
        {
            if (name == null) return null;
            for (TemplateKey key : values())
            {
                for (String term : key.terms)
                {
                    if (term != null && term.equalsIgnoreCase(name)) return key;
                }
            }
            return null;
        }
    }

    /** One mutation's build context: platform version, languages, appearance catalogue, colors. */
    public static final class Context
    {
        final Version version;
        final DcsPresentationParser.LanguageContext languages;
        final StyleValueBuilder.NamedColorResolver namedColors;
        /** The mutation's catalogues, read only when an appearance key needs them; null for an explicit catalogue. */
        private final DcsWriterCatalogues catalogues;
        private final DcsAvailableParameterCollection explicitAppearance;

        private Context(Version version, DcsPresentationParser.LanguageContext languages,
            DcsWriterCatalogues catalogues, DcsAvailableParameterCollection explicitAppearance,
            StyleValueBuilder.NamedColorResolver namedColors)
        {
            this.version = version == null ? Version.LATEST : version;
            this.languages = languages;
            this.catalogues = catalogues;
            this.explicitAppearance = explicitAppearance;
            this.namedColors = namedColors;
        }

        /**
         * The writer's context: the mutation's catalogues (the appearance catalogue is loaded only
         * when a body has an appearance key, once per mutation) with the call's own language
         * context, so used languages are recorded.
         */
        public static Context of(DcsWriterCatalogues catalogues,
            DcsPresentationParser.LanguageContext languages)
        {
            return new Context(catalogues.version(), languages, catalogues, null, catalogues.namedColors());
        }

        /** The platform appearance catalogue, or {@code null} when it could not be loaded. */
        DcsAvailableParameterCollection appearance()
        {
            return catalogues == null ? explicitAppearance : catalogues.appearanceCatalogue().parameters();
        }

        /** Why the appearance catalogue could not be loaded, or {@code null}. */
        String appearanceFailure()
        {
            return catalogues == null ? null : catalogues.appearanceCatalogue().failure();
        }

        /** The guard's context: the same catalogues, with a dry-run copy of their languages. */
        static Context forGuard(DcsWriterCatalogues catalogues)
        {
            DcsPresentationParser.LanguageContext languages = catalogues.languages();
            return of(catalogues, languages == null ? null : languages.dryRun());
        }

        /** A context over an explicit appearance catalogue (headless tests). */
        static Context of(Version version, DcsPresentationParser.LanguageContext languages,
            DcsAvailableParameterCollection appearance, StyleValueBuilder.NamedColorResolver namedColors)
        {
            return new Context(version, languages, null, appearance, namedColors);
        }

        Context dryRun()
        {
            return new Context(version, languages == null ? null : languages.dryRun(), catalogues,
                explicitAppearance, namedColors);
        }
    }

    /** A stored node restated as a body, with the members that have no body form. */
    static final class Restatement
    {
        final JsonObject body;
        final List<String> unsupported;

        Restatement(JsonObject body, List<String> unsupported)
        {
            this.body = body;
            this.unsupported = unsupported;
        }
    }

    // ----- build --------------------------------------------------------------------------------

    /**
     * Applies a template body to {@code target}, a detached template description (a new one, or a
     * copy of the stored one).
     *
     * @param target the description to change
     * @param spec the body: {@code name}, {@code rows} | {@code cells}, {@code parameters}
     * @param context the build context
     * @param path where an error says the body came from
     * @param authoritative whether omitted {@code rows}/{@code parameters} are reset (replace)
     * @param references collects the template-parameter names the written cells refer to
     * @return {@code null} on success, otherwise the error; {@code target} is then unusable
     */
    static String apply(DataCompositionSchemaTemplateDescription target, JsonObject spec,
        Context context, String path, boolean authoritative, Set<String> references)
    {
        String members = JsonUtils.unknownMemberError(spec, path, KEY_NAME, KEY_ROWS, KEY_CELLS,
            KEY_PARAMETERS);
        if (members != null) return members;
        if (spec.has(KEY_ROWS) && spec.has(KEY_CELLS))
        {
            return "'" + path + "' carries both rows and cells. Send rows to rewrite the whole " //$NON-NLS-1$ //$NON-NLS-2$
                + "template body, or cells to patch single cells of the stored one, not both."; //$NON-NLS-1$
        }
        if (authoritative && spec.has(KEY_CELLS))
        {
            return "action='replace' rebuilds the whole template, so '" + path //$NON-NLS-1$
                + ".cells' (a patch of single stored cells) is refused. Send rows, or patch the cells " //$NON-NLS-1$
                + "with action='update'."; //$NON-NLS-1$
        }
        if (spec.has(KEY_NAME))
        {
            String name = string(spec, KEY_NAME);
            if (name == null)
            {
                return "'" + path + ".name' must be a string."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            target.setName(name);
        }
        if (spec.has(KEY_ROWS))
        {
            JsonArray rows = array(spec, KEY_ROWS);
            if (rows == null) return "'" + path + ".rows' must be an array of row objects."; //$NON-NLS-1$ //$NON-NLS-2$
            DataCompositionAreaTemplate area = AREA.createDataCompositionAreaTemplate();
            for (int i = 0; i < rows.size(); i++)
            {
                String rowPath = path + ".rows[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                if (!rows.get(i).isJsonObject()) return "'" + rowPath + "' must be a row object."; //$NON-NLS-1$ //$NON-NLS-2$
                DataCompositionAreaTemplateTableRow row = AREA.createDataCompositionAreaTemplateTableRow();
                String error = buildRow(row, rows.get(i).getAsJsonObject(), context, rowPath, references);
                if (error != null) return error;
                area.getItems().add(row);
            }
            target.setTemplate(area);
        }
        else if (authoritative)
        {
            target.setTemplate(null);
        }
        if (spec.has(KEY_CELLS))
        {
            String error = patchCells(target, spec.get(KEY_CELLS), context, path + ".cells", references); //$NON-NLS-1$
            if (error != null) return error;
        }
        if (spec.has(KEY_PARAMETERS))
        {
            JsonArray parameters = array(spec, KEY_PARAMETERS);
            if (parameters == null)
            {
                return "'" + path + ".parameters' must be an array of template parameters."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            List<AreaTemplateParameter> built = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < parameters.size(); i++)
            {
                String parameterPath = path + ".parameters[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                if (!parameters.get(i).isJsonObject())
                {
                    return "'" + parameterPath + "' must be a template parameter object."; //$NON-NLS-1$ //$NON-NLS-2$
                }
                ParameterResult parameter = buildParameter(parameters.get(i).getAsJsonObject(), parameterPath);
                if (parameter.error != null) return parameter.error;
                if (!names.add(parameter.value.getName().toLowerCase(Locale.ROOT)))
                {
                    return "Template parameter '" + parameter.value.getName() + "' at '" + parameterPath //$NON-NLS-1$ //$NON-NLS-2$
                        + "' repeats an earlier name (names are compared case-insensitively). Give each " //$NON-NLS-1$
                        + "parameter its own name."; //$NON-NLS-1$
                }
                built.add(parameter.value);
            }
            target.getParameters().clear();
            target.getParameters().addAll(built);
        }
        else if (authoritative)
        {
            target.getParameters().clear();
        }
        return null;
    }

    /**
     * One table row. A row has no {@code tableID} member: EDT's Template.dcs serializer does not
     * write it, so a written one would be lost on export.
     */
    private static String buildRow(DataCompositionAreaTemplateTableRow row, JsonObject spec,
        Context context, String path, Set<String> references)
    {
        String members = JsonUtils.unknownMemberError(spec, path, KEY_CELLS);
        if (members != null) return members;
        JsonArray cells = array(spec, KEY_CELLS);
        if (cells == null) return "'" + path + ".cells' must be an array of cell objects."; //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < cells.size(); i++)
        {
            String cellPath = path + ".cells[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            if (!cells.get(i).isJsonObject()) return "'" + cellPath + "' must be a cell object."; //$NON-NLS-1$ //$NON-NLS-2$
            DataCompositionAreaTemplateTableCell cell = AREA.createDataCompositionAreaTemplateTableCell();
            String error = buildCell(cell, cells.get(i).getAsJsonObject(), context, cellPath, false,
                references);
            if (error != null) return error;
            row.getCells().add(cell);
        }
        return null;
    }

    /**
     * Writes one cell. A fresh cell is built entirely from {@code spec}; a patched cell keeps what
     * the spec does not name: its content when no {@code items}/{@code text}/{@code parameter} is
     * sent, and every appearance key the spec's {@code appearance} does not name.
     */
    private static String buildCell(DataCompositionAreaTemplateTableCell cell, JsonObject spec,
        Context context, String path, boolean patch, Set<String> references)
    {
        String members = JsonUtils.unknownMemberError(spec, path, KEY_ITEMS, KEY_TEXT, KEY_PARAMETER,
            KEY_APPEARANCE);
        if (members != null) return members;
        int contents = (spec.has(KEY_ITEMS) ? 1 : 0) + (spec.has(KEY_TEXT) ? 1 : 0)
            + (spec.has(KEY_PARAMETER) ? 1 : 0);
        if (contents > 1)
        {
            return "'" + path + "' carries more than one of items, text and parameter. Send one: " //$NON-NLS-1$ //$NON-NLS-2$
                + "text or parameter for a single field, items for several."; //$NON-NLS-1$
        }
        if (contents == 1)
        {
            List<TemplateItem> fields = new ArrayList<>();
            if (spec.has(KEY_ITEMS))
            {
                JsonArray items = array(spec, KEY_ITEMS);
                if (items == null) return "'" + path + ".items' must be an array of field objects."; //$NON-NLS-1$ //$NON-NLS-2$
                for (int i = 0; i < items.size(); i++)
                {
                    String fieldPath = path + ".items[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                    if (!items.get(i).isJsonObject()) return "'" + fieldPath + "' must be a field object."; //$NON-NLS-1$ //$NON-NLS-2$
                    FieldResult field = buildField(items.get(i).getAsJsonObject(), context, fieldPath,
                        references);
                    if (field.error != null) return field.error;
                    fields.add(field.value);
                }
            }
            else
            {
                JsonObject shorthand = new JsonObject();
                String member = spec.has(KEY_TEXT) ? KEY_TEXT : KEY_PARAMETER;
                shorthand.add(member, spec.get(member));
                FieldResult field = buildField(shorthand, context, path, references);
                if (field.error != null) return field.error;
                fields.add(field.value);
            }
            cell.getItem().clear();
            cell.getItem().addAll(fields);
        }
        if (spec.has(KEY_APPEARANCE))
        {
            List<DataCompositionParameterValue> current = patch && cell.getAppearance() != null
                ? cell.getAppearance().getItems() : Collections.emptyList();
            ItemsResult appearance = appearance(spec.get(KEY_APPEARANCE), current, context,
                path + ".appearance", references); //$NON-NLS-1$
            if (appearance.error != null) return appearance.error;
            DataCompositionAreaTemplateTableCellAppearance holder =
                AREA.createDataCompositionAreaTemplateTableCellAppearance();
            holder.getItems().addAll(appearance.items);
            cell.setAppearance(holder);
        }
        return null;
    }

    /**
     * One field. Its value is a text or a template parameter only: EDT's Template.dcs serializer
     * writes no other value kind into a template field (a number, a boolean, a date or a plain
     * string is dropped on export), so offering one would report a write the file never keeps.
     */
    private static FieldResult buildField(JsonObject spec, Context context, String path,
        Set<String> references)
    {
        String members = JsonUtils.unknownMemberError(spec, path, KEY_TEXT, KEY_PARAMETER,
            KEY_APPEARANCE);
        if (members != null) return FieldResult.failure(members);
        if (spec.has(KEY_TEXT) == spec.has(KEY_PARAMETER))
        {
            return FieldResult.failure("'" + path + "' needs exactly one of text and parameter: " //$NON-NLS-1$ //$NON-NLS-2$
                + "text for a static text, parameter for a template parameter whose expression gives " //$NON-NLS-1$
                + "the value."); //$NON-NLS-1$
        }
        DataCompositionAreaTemplateField field = AREA.createDataCompositionAreaTemplateField();
        if (spec.has(KEY_TEXT))
        {
            DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(spec.get(KEY_TEXT),
                context.languages, path + ".text"); //$NON-NLS-1$
            if (!parsed.isSuccess()) return FieldResult.failure(parsed.error());
            Presentation presentation = DcsPresentationParser.build(parsed.plan());
            if (presentation == null || presentation.getLocalValue() == null)
            {
                return FieldResult.failure("'" + path + ".text' is empty. Pass a non-empty string " //$NON-NLS-1$ //$NON-NLS-2$
                    + "or {languageCode:text}, or leave the cell without a field."); //$NON-NLS-1$
            }
            field.setValue(presentation.getLocalValue());
        }
        else
        {
            String name = string(spec, KEY_PARAMETER);
            if (name == null || name.isEmpty())
            {
                return FieldResult.failure("'" + path + ".parameter' must be a non-empty template " //$NON-NLS-1$ //$NON-NLS-2$
                    + "parameter name."); //$NON-NLS-1$
            }
            DataCompositionParameter parameter = CORE.createDataCompositionParameter();
            parameter.setValue(name);
            field.setValue(parameter);
            references.add(name);
        }
        if (spec.has(KEY_APPEARANCE))
        {
            ItemsResult appearance = appearance(spec.get(KEY_APPEARANCE), Collections.emptyList(),
                context, path + ".appearance", references); //$NON-NLS-1$
            if (appearance.error != null) return FieldResult.failure(appearance.error);
            DataCompositionAreaTemplateFieldAppearance holder =
                AREA.createDataCompositionAreaTemplateFieldAppearance();
            holder.getItems().addAll(appearance.items);
            field.setAppearance(holder);
        }
        return FieldResult.success(field);
    }

    private static ParameterResult buildParameter(JsonObject spec, String path)
    {
        String kind = spec.has(KEY_KIND) ? string(spec, KEY_KIND) : KIND_EXPRESSION;
        if (!KIND_EXPRESSION.equals(kind) && !KIND_DETAILS.equals(kind))
        {
            return ParameterResult.failure("'" + path + ".kind' is '" + spec.get(KEY_KIND) //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Use 'expression' (the default) or 'details'."); //$NON-NLS-1$
        }
        String members = KIND_EXPRESSION.equals(kind)
            ? JsonUtils.unknownMemberError(spec, path, KEY_NAME, KEY_KIND, KEY_EXPRESSION)
            : JsonUtils.unknownMemberError(spec, path, KEY_NAME, KEY_KIND, KEY_FIELD_EXPRESSION,
                KEY_MAIN_ACTION);
        if (members != null) return ParameterResult.failure(members);
        String name = string(spec, KEY_NAME);
        if (name == null || name.isEmpty())
        {
            return ParameterResult.failure("'" + path + ".name' must be a non-empty template parameter name."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (KIND_EXPRESSION.equals(kind))
        {
            DataCompositionExpressionAreaParameter parameter =
                AREA.createDataCompositionExpressionAreaParameter();
            parameter.setName(name);
            if (spec.has(KEY_EXPRESSION))
            {
                String expression = string(spec, KEY_EXPRESSION);
                if (expression == null) return ParameterResult.failure("'" + path + ".expression' must be a string."); //$NON-NLS-1$ //$NON-NLS-2$
                parameter.setExpression(expression);
            }
            return ParameterResult.success(parameter);
        }
        DataCompositionDetailsAreaParameter parameter = AREA.createDataCompositionDetailsAreaParameter();
        parameter.setName(name);
        if (spec.has(KEY_FIELD_EXPRESSION))
        {
            JsonArray expressions = array(spec, KEY_FIELD_EXPRESSION);
            if (expressions == null)
            {
                return ParameterResult.failure("'" + path + ".fieldExpression' must be an array of " //$NON-NLS-1$ //$NON-NLS-2$
                    + "{field, expression} objects."); //$NON-NLS-1$
            }
            for (int i = 0; i < expressions.size(); i++)
            {
                String itemPath = path + ".fieldExpression[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                if (!expressions.get(i).isJsonObject()) return ParameterResult.failure("'" + itemPath //$NON-NLS-1$
                    + "' must be a {field, expression} object."); //$NON-NLS-1$
                JsonObject item = expressions.get(i).getAsJsonObject();
                String itemMembers = JsonUtils.unknownMemberError(item, itemPath, KEY_FIELD, KEY_EXPRESSION);
                if (itemMembers != null) return ParameterResult.failure(itemMembers);
                DataCompositionDetailsAreaParameterFieldExpression built =
                    AREA.createDataCompositionDetailsAreaParameterFieldExpression();
                for (String member : List.of(KEY_FIELD, KEY_EXPRESSION))
                {
                    if (!item.has(member)) continue;
                    String text = string(item, member);
                    if (text == null) return ParameterResult.failure("'" + itemPath + "." + member //$NON-NLS-1$ //$NON-NLS-2$
                        + "' must be a string."); //$NON-NLS-1$
                    if (KEY_FIELD.equals(member)) built.setField(text);
                    else built.setExpression(text);
                }
                parameter.getFieldExpression().add(built);
            }
        }
        if (spec.has(KEY_MAIN_ACTION))
        {
            DataCompositionDetailsProcessingAction action = literal(string(spec, KEY_MAIN_ACTION),
                DataCompositionDetailsProcessingAction.VALUES);
            if (action == null)
            {
                return ParameterResult.failure("'" + path + ".mainAction' is " + spec.get(KEY_MAIN_ACTION) //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Use one of the platform literals: " //$NON-NLS-1$
                    + literals(DataCompositionDetailsProcessingAction.VALUES) + "."); //$NON-NLS-1$
            }
            parameter.setMainAction(action);
        }
        return ParameterResult.success(parameter);
    }

    /**
     * Patches single stored cells by position. Content members replace that cell's fields;
     * {@code appearance} merges key by key into the cell's stored appearance. Nothing else in the
     * cell, its row or the template changes.
     */
    private static String patchCells(DataCompositionSchemaTemplateDescription target, JsonElement raw,
        Context context, String path, Set<String> references)
    {
        if (raw == null || !raw.isJsonArray()) return "'" + path + "' must be an array of {row, col, ...} patches."; //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionAreaTemplate area = target.getTemplate();
        List<DataCompositionAreaTemplateTableRow> rows = new ArrayList<>();
        if (area != null)
        {
            for (AreaTemplateItem item : area.getItems())
            {
                if (item instanceof DataCompositionAreaTemplateTableRow)
                    rows.add((DataCompositionAreaTemplateTableRow)item);
                else rows.add(null);
            }
        }
        if (rows.isEmpty())
        {
            return "'" + path + "' patches cells, but template '" + target.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "' has no rows. Send rows to give it a body first."; //$NON-NLS-1$
        }
        JsonArray patches = raw.getAsJsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < patches.size(); i++)
        {
            String patchPath = path + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            if (!patches.get(i).isJsonObject()) return "'" + patchPath + "' must be a {row, col, ...} object."; //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject patch = patches.get(i).getAsJsonObject().deepCopy();
            Integer row = index(patch, KEY_ROW);
            Integer col = index(patch, KEY_COL);
            if (row == null || col == null)
            {
                return "'" + patchPath + "' needs 'row' and 'col', zero-based integers of an existing cell."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (row.intValue() >= rows.size() || rows.get(row.intValue()) == null
                || col.intValue() >= rows.get(row.intValue()).getCells().size())
            {
                return "'" + patchPath + "' addresses cell (row " + row + ", col " + col //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "), outside the stored grid of template '" + target.getName() + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + gridSize(rows) + ". Re-run get and patch an existing cell."; //$NON-NLS-1$
            }
            if (!seen.add(row + ":" + col)) //$NON-NLS-1$
            {
                return "'" + patchPath + "' patches cell (row " + row + ", col " + col //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ") a second time. Merge both patches into one."; //$NON-NLS-1$
            }
            patch.remove(KEY_ROW);
            patch.remove(KEY_COL);
            String error = buildCell(rows.get(row.intValue()).getCells().get(col.intValue()), patch,
                context, patchPath, true, references);
            if (error != null) return error;
        }
        return null;
    }

    private static String gridSize(List<DataCompositionAreaTemplateTableRow> rows)
    {
        List<String> widths = new ArrayList<>();
        for (DataCompositionAreaTemplateTableRow row : rows)
        {
            widths.add(row == null ? "0" : Integer.toString(row.getCells().size())); //$NON-NLS-1$
        }
        return rows.size() + " row(s) with " + String.join(", ", widths) + " cell(s)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ----- appearance ----------------------------------------------------------------------------

    /**
     * Builds an appearance item list in body order. Each catalogue key goes through the shared typed
     * builder against the items built so far (so the dotted-key holder and merge rules are the
     * builder's own); a template-only key is built here. In patch mode {@code current} is the stored
     * list and the result merges into it.
     */
    private static ItemsResult appearance(JsonElement raw, List<DataCompositionParameterValue> current,
        Context context, String path, Set<String> references)
    {
        if (raw == null || !raw.isJsonObject())
        {
            return ItemsResult.failure("'" + path + "' must be an appearance object: a keyed map " //$NON-NLS-1$ //$NON-NLS-2$
                + "{HorizontalAlign:'Right', ...} or {items:[...]}."); //$NON-NLS-1$
        }
        JsonObject body = raw.getAsJsonObject();
        List<DataCompositionParameterValue> items = new ArrayList<>(EcoreUtil.copyAll(current));
        List<JsonObject> entries = new ArrayList<>();
        List<String> entryPaths = new ArrayList<>();
        if (body.has(KEY_ITEMS))
        {
            String members = JsonUtils.unknownMemberError(body, path, KEY_ITEMS);
            if (members != null) return ItemsResult.failure(members);
            JsonArray array = array(body, KEY_ITEMS);
            if (array == null) return ItemsResult.failure("'" + path + ".items' must be an array."); //$NON-NLS-1$ //$NON-NLS-2$
            for (int i = 0; i < array.size(); i++)
            {
                String itemPath = path + ".items[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                if (!array.get(i).isJsonObject()) return ItemsResult.failure("'" + itemPath + "' must be an object."); //$NON-NLS-1$ //$NON-NLS-2$
                JsonObject item = array.get(i).getAsJsonObject();
                String members2 = JsonUtils.unknownMemberError(item, itemPath, KEY_PARAMETER, KEY_USE,
                    KEY_VALUE, KEY_VALUES, "nestedParameterValues"); //$NON-NLS-1$
                if (members2 != null) return ItemsResult.failure(members2);
                JsonObject wrapped = new JsonObject();
                JsonArray single = new JsonArray();
                single.add(item);
                wrapped.add(KEY_ITEMS, single);
                entries.add(wrapped);
                entryPaths.add(itemPath);
            }
        }
        else
        {
            for (Map.Entry<String, JsonElement> member : body.entrySet())
            {
                JsonObject single = new JsonObject();
                single.add(member.getKey(), member.getValue());
                entries.add(single);
                entryPaths.add(path + "." + member.getKey()); //$NON-NLS-1$
            }
        }
        Set<Object> written = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String entryPath = entryPaths.get(i);
            String name = entryName(entry);
            TemplateKey key = TemplateKey.of(name);
            if (key != null)
            {
                if (!written.add(key)) return ItemsResult.failure(duplicateKey(name, path));
                ItemResult built = templateItem(key, entry, context, entryPath);
                if (built.error != null) return ItemsResult.failure(built.error);
                if (key == TemplateKey.DETAILS)
                {
                    references.add(((DataCompositionParameter)built.value.getValues().get(0)).getValue());
                }
                putTemplateItem(items, key, built.value);
                continue;
            }
            DcsAvailableParameterCollection available = context.appearance();
            if (available == null)
            {
                String failure = context.appearanceFailure();
                return ItemsResult.failure("Could not load the typed appearance model for platform " //$NON-NLS-1$
                    + context.version + " at '" + entryPath + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + (failure == null ? "catalogue unavailable" : failure) //$NON-NLS-1$
                    + ". Verify the project platform version and retry."); //$NON-NLS-1$
            }
            DcsAvailableParameter declared = name == null ? null
                : DcsSettingsWriter.findAppearanceParameter(available, name);
            if (declared != null && !written.add(declared)) return ItemsResult.failure(duplicateKey(name, path));
            DcsSettingsWriter.AppearanceItemsResult built = DcsSettingsWriter.buildAppearanceItems(entry,
                items, context.languages, available, context.version, context.namedColors, path);
            if (built.error != null) return ItemsResult.failure(built.error);
            items = built.items;
        }
        return ItemsResult.success(items);
    }

    private static String duplicateKey(String name, String path)
    {
        return "Appearance key '" + name + "' appears twice in '" + path //$NON-NLS-1$ //$NON-NLS-2$
            + "'. An appearance body writes each key once; merge the two entries."; //$NON-NLS-1$
    }

    /** The parameter name an appearance entry addresses: its keyed name, or its item's parameter. */
    private static String entryName(JsonObject entry)
    {
        if (!entry.has(KEY_ITEMS))
        {
            return entry.keySet().iterator().next();
        }
        JsonObject item = entry.getAsJsonArray(KEY_ITEMS).get(0).getAsJsonObject();
        JsonElement parameter = item.get(KEY_PARAMETER);
        if (parameter == null) return null;
        if (parameter.isJsonPrimitive() && parameter.getAsJsonPrimitive().isString())
        {
            return parameter.getAsString();
        }
        if (parameter.isJsonObject() && parameter.getAsJsonObject().has(KEY_VALUE)
            && parameter.getAsJsonObject().get(KEY_VALUE).isJsonPrimitive())
        {
            return parameter.getAsJsonObject().get(KEY_VALUE).getAsString();
        }
        return null;
    }

    /** Builds a HorizontalMerge/VerticalMerge (boolean) or Details (template parameter) item. */
    private static ItemResult templateItem(TemplateKey key, JsonObject entry, Context context, String path)
    {
        boolean use = true;
        JsonElement raw;
        if (entry.has(KEY_ITEMS))
        {
            JsonObject item = entry.getAsJsonArray(KEY_ITEMS).get(0).getAsJsonObject();
            String members = JsonUtils.unknownMemberError(item, path, KEY_PARAMETER, KEY_USE, KEY_VALUE,
                KEY_VALUES);
            if (members != null) return ItemResult.failure(members);
            if (item.has(KEY_USE))
            {
                if (!isBoolean(item.get(KEY_USE))) return ItemResult.failure("'" + path + ".use' must be a boolean."); //$NON-NLS-1$ //$NON-NLS-2$
                use = item.get(KEY_USE).getAsBoolean();
            }
            if (item.has(KEY_VALUES))
            {
                JsonElement values = item.get(KEY_VALUES);
                if (item.has(KEY_VALUE) || !values.isJsonArray() || values.getAsJsonArray().size() != 1)
                {
                    return ItemResult.failure("Appearance key '" + key.english() + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                        + "' needs exactly one value. Use value or a one-element values array."); //$NON-NLS-1$
                }
                raw = values.getAsJsonArray().get(0);
            }
            else
            {
                raw = item.get(KEY_VALUE);
            }
        }
        else
        {
            raw = entry.get(entry.keySet().iterator().next());
        }
        Value value;
        if (raw != null && raw.isJsonObject())
        {
            DcsSettingsWriter.ValueResult parsed = DcsSettingsWriter.untypedValue(raw, path);
            if (parsed.error != null) return ItemResult.failure(parsed.error);
            value = parsed.value;
        }
        else if (key.merge && isBoolean(raw))
        {
            BooleanValue flag = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createBooleanValue();
            flag.setValue(raw.getAsBoolean());
            value = flag;
        }
        else if (!key.merge && raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString())
        {
            DataCompositionParameter parameter = CORE.createDataCompositionParameter();
            parameter.setValue(raw.getAsString());
            value = parameter;
        }
        else
        {
            value = null;
        }
        if (key.merge && !(value instanceof BooleanValue))
        {
            return ItemResult.failure("Appearance key '" + key.english() + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' takes a boolean: true merges the cell with its neighbour."); //$NON-NLS-1$
        }
        if (!key.merge && (!(value instanceof DataCompositionParameter)
            || ((DataCompositionParameter)value).getValue() == null
            || ((DataCompositionParameter)value).getValue().isEmpty()))
        {
            return ItemResult.failure("Appearance key '" + key.english() + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' takes the name of a template parameter (a string), the one that carries the " //$NON-NLS-1$
                + "cell's details."); //$NON-NLS-1$
        }
        DataCompositionParameterValue item = CORE.createDataCompositionParameterValue();
        DataCompositionParameter name = CORE.createDataCompositionParameter();
        name.setValue(key.spelled(context.languages));
        item.setParameter(name);
        item.setUse(use);
        item.getValues().add(value);
        return ItemResult.success(item);
    }

    private static void putTemplateItem(List<DataCompositionParameterValue> items, TemplateKey key,
        DataCompositionParameterValue item)
    {
        for (int i = 0; i < items.size(); i++)
        {
            DataCompositionParameter parameter = items.get(i).getParameter();
            if (parameter != null && TemplateKey.of(parameter.getValue()) == key)
            {
                items.set(i, item);
                return;
            }
        }
        items.add(item);
    }

    // ----- bindings ------------------------------------------------------------------------------

    /**
     * Applies a binding body to {@code target}, a detached binding (new or a copy of the stored one).
     * A non-empty {@code groupName} clears {@code groupFields} and vice versa, as EDT's dialog does.
     *
     * @param authoritative whether the binding is rebuilt from the body alone (create or replace)
     * @return {@code null} on success, otherwise the error
     */
    static String applyBinding(DataCompositionSchemaGroupTemplate target, JsonObject spec, String path,
        boolean authoritative)
    {
        String members = JsonUtils.unknownMemberError(spec, path, KEY_GROUP_NAME, KEY_GROUP_FIELDS,
            KEY_TEMPLATE_TYPE, KEY_TEMPLATE);
        if (members != null) return members;
        String groupName = null;
        if (spec.has(KEY_GROUP_NAME))
        {
            groupName = string(spec, KEY_GROUP_NAME);
            if (groupName == null || groupName.isEmpty())
            {
                return "'" + path + ".groupName' must be a non-empty grouping name."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        List<String> groupFields = null;
        if (spec.has(KEY_GROUP_FIELDS))
        {
            JsonArray fields = array(spec, KEY_GROUP_FIELDS);
            if (fields == null || fields.size() == 0)
            {
                return "'" + path + ".groupFields' must be a non-empty array of field data paths."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            groupFields = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++)
            {
                JsonElement field = fields.get(i);
                if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()
                    || field.getAsString().isEmpty())
                {
                    return "'" + path + ".groupFields[" + i + "]' must be a non-empty data path string."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                groupFields.add(field.getAsString());
            }
        }
        if (groupName != null && groupFields != null)
        {
            return "'" + path + "' carries both groupName and groupFields. A binding names its " //$NON-NLS-1$ //$NON-NLS-2$
                + "grouping either by name or by its fields, as EDT's dialog does; send one."; //$NON-NLS-1$
        }
        DataCompositionAreaTemplateType type = null;
        if (spec.has(KEY_TEMPLATE_TYPE))
        {
            type = literal(string(spec, KEY_TEMPLATE_TYPE), DataCompositionAreaTemplateType.VALUES);
            if (type == null)
            {
                return "'" + path + ".templateType' is " + spec.get(KEY_TEMPLATE_TYPE) //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Use one of the platform literals: " //$NON-NLS-1$
                    + literals(DataCompositionAreaTemplateType.VALUES) + "."; //$NON-NLS-1$
            }
        }
        String template = null;
        if (spec.has(KEY_TEMPLATE))
        {
            template = string(spec, KEY_TEMPLATE);
            if (template == null || template.isEmpty())
            {
                return "'" + path + ".template' must be the non-empty name of a template of this schema."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (authoritative)
        {
            target.setGroupName(null);
            target.getGroupFields().clear();
            target.setTemplateType(DataCompositionAreaTemplateType.HEADER);
            target.setTemplate(null);
        }
        if (groupName != null)
        {
            target.setGroupName(groupName);
            target.getGroupFields().clear();
        }
        if (groupFields != null)
        {
            target.setGroupName(null);
            target.getGroupFields().clear();
            for (String value : groupFields)
            {
                DataCompositionField field = CORE.createDataCompositionField();
                field.setValue(value);
                target.getGroupFields().add(field);
            }
        }
        if (type != null) target.setTemplateType(type);
        if (template != null) target.setTemplate(template);
        boolean named = target.getGroupName() != null && !target.getGroupName().isEmpty();
        if (named == !target.getGroupFields().isEmpty() || target.getTemplate() == null
            || target.getTemplate().isEmpty())
        {
            return "The binding at '" + path + "' needs exactly one of groupName and groupFields, " //$NON-NLS-1$ //$NON-NLS-2$
                + "and template (the name of a template of this schema)."; //$NON-NLS-1$
        }
        return null;
    }

    // ----- restatement ---------------------------------------------------------------------------

    /**
     * The body that restates a stored template description or binding, in canonical form: cells as
     * {@code items}, appearance as {@code {items:[...]}}, texts as language maps. A member with no
     * body form is shown as {@code {"unsupported":"<class>: <reason>"}} and listed.
     */
    static Restatement restate(EObject node)
    {
        List<String> unsupported = new ArrayList<>();
        JsonObject body = new JsonObject();
        if (node instanceof DataCompositionSchemaGroupTemplate)
        {
            restateBinding((DataCompositionSchemaGroupTemplate)node, body, unsupported);
        }
        else if (node instanceof DataCompositionSchemaTemplateDescription)
        {
            restateTemplate((DataCompositionSchemaTemplateDescription)node, body, unsupported);
        }
        else
        {
            unsupported.add(node == null ? "no node" : node.eClass().getName() //$NON-NLS-1$
                + ": not a template or a grouping binding"); //$NON-NLS-1$
        }
        return new Restatement(body, unsupported);
    }

    /** The restated body rendered as indented JSON, for a read page. */
    static String json(JsonObject body)
    {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(body);
    }

    private static void restateTemplate(DataCompositionSchemaTemplateDescription template, JsonObject body,
        List<String> unsupported)
    {
        if (template.getName() != null) body.addProperty(KEY_NAME, template.getName());
        DataCompositionAreaTemplate area = template.getTemplate();
        if (area != null)
        {
            JsonArray rows = new JsonArray();
            for (AreaTemplateItem item : area.getItems())
            {
                if (!(item instanceof DataCompositionAreaTemplateTableRow))
                {
                    rows.add(placeholder(item, "an area item other than a table row has no body form", //$NON-NLS-1$
                        unsupported));
                    continue;
                }
                DataCompositionAreaTemplateTableRow row = (DataCompositionAreaTemplateTableRow)item;
                if (row.getTableID() != null)
                {
                    rows.add(placeholder(row, "tableID '" + row.getTableID() //$NON-NLS-1$
                        + "' is a runtime value Template.dcs does not store", unsupported)); //$NON-NLS-1$
                    continue;
                }
                JsonObject rowBody = new JsonObject();
                JsonArray cells = new JsonArray();
                for (DataCompositionAreaTemplateTableCell cell : row.getCells())
                {
                    cells.add(restateCell(cell, unsupported));
                }
                rowBody.add(KEY_CELLS, cells);
                rows.add(rowBody);
            }
            body.add(KEY_ROWS, rows);
        }
        if (!template.getParameters().isEmpty())
        {
            JsonArray parameters = new JsonArray();
            for (AreaTemplateParameter parameter : template.getParameters())
            {
                parameters.add(restateParameter(parameter, unsupported));
            }
            body.add(KEY_PARAMETERS, parameters);
        }
    }

    private static JsonElement restateCell(DataCompositionAreaTemplateTableCell cell, List<String> unsupported)
    {
        if (cell.getAppIndex() != 0)
        {
            return placeholder(cell, "appearance index " + cell.getAppIndex() //$NON-NLS-1$
                + " is a runtime value with no body form", unsupported); //$NON-NLS-1$
        }
        JsonObject body = new JsonObject();
        if (!cell.getItem().isEmpty())
        {
            JsonArray items = new JsonArray();
            for (TemplateItem item : cell.getItem())
            {
                items.add(item instanceof DataCompositionAreaTemplateField
                    ? restateField((DataCompositionAreaTemplateField)item, unsupported)
                    : placeholder(item, "a cell item other than a field has no body form", unsupported)); //$NON-NLS-1$
            }
            body.add(KEY_ITEMS, items);
        }
        if (cell.getAppearance() != null)
        {
            body.add(KEY_APPEARANCE, restateAppearance(cell.getAppearance(), unsupported));
        }
        return body;
    }

    private static JsonElement restateField(DataCompositionAreaTemplateField field, List<String> unsupported)
    {
        JsonObject body = new JsonObject();
        Value value = field.getValue();
        if (value instanceof LocalString)
        {
            LocalString text = (LocalString)value;
            if (text.isFormattedString())
            {
                return placeholder(field, "a formatted text has no body form", unsupported); //$NON-NLS-1$
            }
            JsonObject localized = new JsonObject();
            for (Map.Entry<String, String> entry : text.getContent())
            {
                if (entry.getValue() == null)
                {
                    return placeholder(field, "language '" + entry.getKey() + "' of its text holds no value", //$NON-NLS-1$ //$NON-NLS-2$
                        unsupported);
                }
                localized.addProperty(entry.getKey(), entry.getValue());
            }
            if (localized.size() == 0)
            {
                return placeholder(field, "an empty text has no body form", unsupported); //$NON-NLS-1$
            }
            body.add(KEY_TEXT, localized);
        }
        else if (value instanceof DataCompositionParameter)
        {
            String name = ((DataCompositionParameter)value).getValue();
            if (name == null || name.isEmpty())
            {
                return placeholder(field, "its parameter has no name", unsupported); //$NON-NLS-1$
            }
            body.addProperty(KEY_PARAMETER, name);
        }
        else
        {
            return placeholder(field, (value == null ? "the field has no value" //$NON-NLS-1$
                : "its value is a " + value.eClass().getName()) //$NON-NLS-1$
                + "; a template field holds a text or a template parameter", unsupported); //$NON-NLS-1$
        }
        if (field.getAppearance() != null)
        {
            body.add(KEY_APPEARANCE, restateAppearance(field.getAppearance(), unsupported));
        }
        return body;
    }

    private static JsonObject restateAppearance(ParameterValues appearance, List<String> unsupported)
    {
        JsonArray items = new JsonArray();
        for (DataCompositionParameterValue item : appearance.getItems())
        {
            items.add(restateAppearanceItem(item, unsupported));
        }
        JsonObject body = new JsonObject();
        body.add(KEY_ITEMS, items);
        return body;
    }

    private static JsonElement restateAppearanceItem(DataCompositionParameterValue item,
        List<String> unsupported)
    {
        String name = item.getParameter() == null ? null : item.getParameter().getValue();
        if (item instanceof SettingsParameterValue)
        {
            return placeholder(item, "appearance item '" + name //$NON-NLS-1$
                + "' is a settings parameter value; EDT writes core parameter values into a template", //$NON-NLS-1$
                unsupported);
        }
        TemplateKey key = TemplateKey.of(name);
        if (key != null)
        {
            if (item.getValues().size() != 1 || !item.getNestedParameterValues().isEmpty())
            {
                return placeholder(item, "appearance item '" + name + "' does not hold exactly one value", //$NON-NLS-1$ //$NON-NLS-2$
                    unsupported);
            }
            JsonElement value = DcsSettingsWriter.valueSpec(item.getValues().get(0));
            if (value == null)
            {
                return placeholder(item, "appearance item '" + name + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + DcsSettingsWriter.untypedValueRefusal(item.getValues().get(0)), unsupported);
            }
            JsonObject body = new JsonObject();
            JsonObject parameter = new JsonObject();
            parameter.addProperty(KEY_KIND, KIND_PARAMETER);
            parameter.addProperty(KEY_VALUE, name);
            body.add(KEY_PARAMETER, parameter);
            body.addProperty(KEY_USE, item.isUse());
            JsonArray values = new JsonArray();
            values.add(value);
            body.add(KEY_VALUES, values);
            return body;
        }
        JsonObject body = DcsSettingsWriter.appearanceItemBody(item);
        return body != null ? body
            : placeholder(item, "appearance item '" + name + "' has a value with no body form", unsupported); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonElement restateParameter(AreaTemplateParameter parameter, List<String> unsupported)
    {
        JsonObject body = new JsonObject();
        if (parameter.getName() != null) body.addProperty(KEY_NAME, parameter.getName());
        if (parameter instanceof DataCompositionExpressionAreaParameter)
        {
            String expression = ((DataCompositionExpressionAreaParameter)parameter).getExpression();
            if (expression != null) body.addProperty(KEY_EXPRESSION, expression);
            return body;
        }
        if (!(parameter instanceof DataCompositionDetailsAreaParameter))
        {
            return placeholder(parameter, "this template parameter kind has no body form", unsupported); //$NON-NLS-1$
        }
        DataCompositionDetailsAreaParameter details = (DataCompositionDetailsAreaParameter)parameter;
        if (details.getGroupID() != null)
        {
            return placeholder(parameter, "groupID is a runtime value with no body form", unsupported); //$NON-NLS-1$
        }
        body.addProperty(KEY_KIND, KIND_DETAILS);
        if (!details.getFieldExpression().isEmpty())
        {
            JsonArray expressions = new JsonArray();
            for (DataCompositionDetailsAreaParameterFieldExpression item : details.getFieldExpression())
            {
                JsonObject expression = new JsonObject();
                if (item.getField() != null) expression.addProperty(KEY_FIELD, item.getField());
                if (item.getExpression() != null) expression.addProperty(KEY_EXPRESSION, item.getExpression());
                expressions.add(expression);
            }
            body.add(KEY_FIELD_EXPRESSION, expressions);
        }
        if (details.getMainAction() != null
            && details.getMainAction() != DataCompositionDetailsProcessingAction.NONE)
        {
            body.addProperty(KEY_MAIN_ACTION, details.getMainAction().getLiteral());
        }
        return body;
    }

    private static void restateBinding(DataCompositionSchemaGroupTemplate binding, JsonObject body,
        List<String> unsupported)
    {
        String className = binding.eClass().getName();
        boolean named = binding.getGroupName() != null && !binding.getGroupName().isEmpty();
        if (named && !binding.getGroupFields().isEmpty())
        {
            unsupported.add(className + ": it names a grouping both by name and by fields"); //$NON-NLS-1$
        }
        else if (!named && binding.getGroupFields().isEmpty())
        {
            unsupported.add(className + ": it names no grouping"); //$NON-NLS-1$
        }
        if (named)
        {
            body.addProperty(KEY_GROUP_NAME, binding.getGroupName());
        }
        if (!binding.getGroupFields().isEmpty())
        {
            JsonArray fields = new JsonArray();
            for (DataCompositionField field : binding.getGroupFields())
            {
                String value = field == null ? null : field.getValue();
                if (value == null || value.isEmpty())
                {
                    unsupported.add(className + ": a group field has no data path"); //$NON-NLS-1$
                    continue;
                }
                fields.add(value);
            }
            body.add(KEY_GROUP_FIELDS, fields);
        }
        if (binding.getTemplateType() != null)
        {
            body.addProperty(KEY_TEMPLATE_TYPE, binding.getTemplateType().getLiteral());
        }
        if (binding.getTemplate() == null || binding.getTemplate().isEmpty())
        {
            unsupported.add(className + ": it names no template"); //$NON-NLS-1$
        }
        else
        {
            body.addProperty(KEY_TEMPLATE, binding.getTemplate());
        }
    }

    private static JsonObject placeholder(EObject object, String reason, List<String> unsupported)
    {
        String text = (object == null ? "null" : object.eClass().getName()) + ": " + reason; //$NON-NLS-1$ //$NON-NLS-2$
        unsupported.add(text);
        JsonObject body = new JsonObject();
        body.addProperty(KEY_UNSUPPORTED, text);
        return body;
    }

    // ----- the guard -----------------------------------------------------------------------------

    /**
     * The reproducibility guard for a stored template description or binding: restate it, rebuild it
     * with the writer in a dry run, and compare. A node the writer would not rebuild exactly cannot
     * be replaced by a typed body without dropping a stored value.
     *
     * @param node a template description or a grouping binding
     * @param catalogues the mutation's catalogue snapshot
     * @return {@code null} when restatable, otherwise the reason
     */
    public static String restatementRefusal(EObject node, DcsWriterCatalogues catalogues)
    {
        return restatementRefusal(node, Context.forGuard(catalogues));
    }

    static String restatementRefusal(EObject node, Context context)
    {
        Restatement restated = restate(node);
        if (!restated.unsupported.isEmpty()) return restated.unsupported.get(0);
        EObject rebuilt;
        String error;
        if (node instanceof DataCompositionSchemaGroupTemplate)
        {
            DataCompositionSchemaGroupTemplate binding = SCHEMA.createDataCompositionSchemaGroupTemplate();
            error = applyBinding(binding, restated.body, "stored binding", true); //$NON-NLS-1$
            rebuilt = binding;
        }
        else
        {
            DataCompositionSchemaTemplateDescription template =
                SCHEMA.createDataCompositionSchemaTemplateDescription();
            error = apply(template, restated.body, context.dryRun(), "stored template", true, //$NON-NLS-1$
                new LinkedHashSet<>());
            rebuilt = template;
        }
        if (error != null) return "the writer rejects its own restatement: " + error; //$NON-NLS-1$
        String difference = DcsModelComparison.firstDifference(node, rebuilt);
        return difference == null ? null : "the writer would rebuild it differently (" + difference + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The guard for the stored parts an {@code update}/{@code upsert} discards: the area body when
     * the body carries {@code rows}, the parameter list when it carries {@code parameters}.
     */
    static String partRefusal(DataCompositionSchemaTemplateDescription stored, Context context,
        boolean rows, boolean parameters)
    {
        if (!rows && !parameters) return null;
        DataCompositionSchemaTemplateDescription probe = EcoreUtil.copy(stored);
        if (!rows) probe.setTemplate(null);
        if (!parameters) probe.getParameters().clear();
        return restatementRefusal(probe, context);
    }

    // ----- small helpers -------------------------------------------------------------------------

    private static <T extends Enumerator> T literal(String raw, List<T> values)
    {
        if (raw == null) return null;
        for (T value : values)
        {
            if (value.getLiteral().equalsIgnoreCase(raw) || value.getName().equalsIgnoreCase(raw)) return value;
        }
        return null;
    }

    private static String literals(List<? extends Enumerator> values)
    {
        List<String> result = new ArrayList<>();
        for (Enumerator value : values) result.add(value.getLiteral());
        return String.join(", ", result); //$NON-NLS-1$
    }

    private static String string(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static JsonArray array(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static boolean isBoolean(JsonElement value)
    {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static Integer index(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        try
        {
            java.math.BigDecimal number = primitive.getAsBigDecimal();
            if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0
                || number.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return null;
            return Integer.valueOf(number.intValueExact());
        }
        catch (ArithmeticException | NumberFormatException e)
        {
            return null;
        }
    }

    private static final class FieldResult
    {
        final DataCompositionAreaTemplateField value;
        final String error;

        private FieldResult(DataCompositionAreaTemplateField value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static FieldResult success(DataCompositionAreaTemplateField value)
        {
            return new FieldResult(value, null);
        }

        static FieldResult failure(String error)
        {
            return new FieldResult(null, error);
        }
    }

    private static final class ParameterResult
    {
        final AreaTemplateParameter value;
        final String error;

        private ParameterResult(AreaTemplateParameter value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static ParameterResult success(AreaTemplateParameter value)
        {
            return new ParameterResult(value, null);
        }

        static ParameterResult failure(String error)
        {
            return new ParameterResult(null, error);
        }
    }

    private static final class ItemResult
    {
        final DataCompositionParameterValue value;
        final String error;

        private ItemResult(DataCompositionParameterValue value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static ItemResult success(DataCompositionParameterValue value)
        {
            return new ItemResult(value, null);
        }

        static ItemResult failure(String error)
        {
            return new ItemResult(null, error);
        }
    }

    private static final class ItemsResult
    {
        final List<DataCompositionParameterValue> items;
        final String error;

        private ItemsResult(List<DataCompositionParameterValue> items, String error)
        {
            this.items = items;
            this.error = error;
        }

        static ItemsResult success(List<DataCompositionParameterValue> items)
        {
            return new ItemsResult(items, null);
        }

        static ItemsResult failure(String error)
        {
            return new ItemsResult(null, error);
        }
    }
}
