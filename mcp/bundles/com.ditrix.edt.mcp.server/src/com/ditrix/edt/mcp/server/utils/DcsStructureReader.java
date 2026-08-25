/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Enumerator;

import com._1c.g5.v8.dt.dcs.model.common.DataCompositionDataSetFieldRole;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValue;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValueValue;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetFieldFolder;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSource;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrder;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFieldGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.FilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.OrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.SelectedItem;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.EnumValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeValue;
import com._1c.g5.v8.dt.mcore.Value;

/**
 * Shared READER that renders a 1C Data Composition Schema (\u0421\u041a\u0414 / a {@code .dcs} resource) - the model
 * behind a Report's / CommonTemplate's / object-owned Template's {@link DataCompositionSchema} content -
 * to a full Markdown document: data sources, data sets (with the FULL query text in a fenced block and
 * their fields), calculated fields, total fields, parameters, and the DEFAULT settings variant's
 * structure (selection / filter / order), as far as the typed model allows. An empty section (an empty
 * list, or no default settings) is skipped entirely rather than rendered with a placeholder - issue #267.
 *
 * <p>Like {@link DcsWriter} (the DCS WRITER), this reader uses the typed DCS API directly for schema,
 * settings, core and common model objects, plus the typed {@code mcore} value hierarchy. Default
 * selection, filter and order settings are traversed through their generated settings interfaces.</p>
 *
 * <p>Pure aside from reading the supplied {@link DataCompositionSchema}, which the caller must still hold
 * inside its BM transaction when {@link #render} runs (the schema is a transient
 * {@code @ExternalProperty} whose containing resource is only valid inside that boundary).</p>
 */
public final class DcsStructureReader
{
    /** Shared Markdown table column header: the DCS {@code dataPath}. */
    private static final String COLUMN_DATA_PATH = "Data path"; //$NON-NLS-1$

    /** Shared Markdown table column header: a localized presentation title. */
    private static final String COLUMN_TITLE = "Title"; //$NON-NLS-1$

    /** Suffix appended to a disabled ({@code use == false}) selection/filter/order item. */
    private static final String SUFFIX_NOT_USED = " [not used]"; //$NON-NLS-1$

    private DcsStructureReader()
    {
        // utility class
    }

    /**
     * Renders the FULL schema structure to a Markdown document (data sources / data sets / calculated
     * fields / total fields / parameters / the default settings variant). Every section is skipped when
     * its underlying collection is empty, so a mostly-empty schema renders a short document rather than a
     * wall of empty headings.
     *
     * @param fqn the (normalized) template FQN, for the heading
     * @param schema the resolved schema content (must still be inside the caller's read/rollback
     *            transaction); {@code null} renders a minimal note
     * @param language the resolved title/presentation language CODE (may be {@code null})
     * @return the Markdown document
     */
    public static String render(String fqn, DataCompositionSchema schema, String language)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Data Composition Schema: ").append(fqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (schema == null)
        {
            sb.append("_(no schema content)_\n"); //$NON-NLS-1$
            return sb.toString();
        }
        renderDataSources(sb, schema);
        renderDataSets(sb, schema, language);
        renderCalculatedFields(sb, schema, language);
        renderTotalFields(sb, schema);
        renderParameters(sb, schema, language);
        renderDefaultSettings(sb, schema, language);
        return sb.toString();
    }

    // ==================== Data sources ====================

    private static void renderDataSources(StringBuilder sb, DataCompositionSchema schema)
    {
        EList<DataCompositionSchemaDataSource> sources = schema.getDataSources();
        if (sources.isEmpty())
        {
            return;
        }
        sb.append("## Data sources\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Name", "Type", "Connection string")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (DataCompositionSchemaDataSource source : sources)
        {
            sb.append(MarkdownUtils.tableRow(source.getName(), source.getDataSourceType(),
                source.getConnectionString()));
        }
        sb.append('\n');
    }

    // ==================== Data sets ====================

    private static void renderDataSets(StringBuilder sb, DataCompositionSchema schema, String language)
    {
        EList<DataSet> dataSets = schema.getDataSets();
        if (dataSets.isEmpty())
        {
            return;
        }
        sb.append("## Data sets\n\n"); //$NON-NLS-1$
        for (DataSet dataSet : dataSets)
        {
            renderDataSet(sb, dataSet, language);
        }
    }

    private static void renderDataSet(StringBuilder sb, DataSet dataSet, String language)
    {
        sb.append("### ").append(nameOrUnnamed(dataSet.getName())) //$NON-NLS-1$
            .append(" (").append(dataSetKind(dataSet)).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (dataSet instanceof DataCompositionSchemaDataSetQuery)
        {
            renderQueryDataSet(sb, (DataCompositionSchemaDataSetQuery)dataSet);
        }
        else if (dataSet instanceof DataCompositionSchemaDataSetObject)
        {
            renderObjectDataSet(sb, (DataCompositionSchemaDataSetObject)dataSet);
        }
        else if (dataSet instanceof DataCompositionSchemaDataSetUnion)
        {
            renderUnionDataSet(sb, (DataCompositionSchemaDataSetUnion)dataSet);
        }
        renderDataSetFields(sb, dataSet.getFields(), language);
    }

    private static void renderQueryDataSet(StringBuilder sb, DataCompositionSchemaDataSetQuery query)
    {
        if (nonEmpty(query.getDataSource()))
        {
            sb.append("**Data source:** ").append(query.getDataSource()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("**Auto-fill fields:** ").append(query.isAutoFillAvailableFields()) //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$
        String queryText = query.getQuery();
        if (nonEmpty(queryText))
        {
            // The FULL query text goes in a fenced block (issue #267), never a table cell: it is
            // long, multi-line, bilingual free-form 1C query-language text that a table would mangle.
            sb.append("```sql\n").append(queryText).append("\n```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void renderObjectDataSet(StringBuilder sb, DataCompositionSchemaDataSetObject objectSet)
    {
        sb.append("**Object:** ").append(emptyIfNull(objectSet.getObjectName())).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (nonEmpty(objectSet.getDataSource()))
        {
            sb.append("**Data source:** ").append(objectSet.getDataSource()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void renderUnionDataSet(StringBuilder sb, DataCompositionSchemaDataSetUnion union)
    {
        List<String> names = new ArrayList<>();
        for (DataSet nested : union.getItems())
        {
            names.add(nameOrUnnamed(nested.getName()));
        }
        if (!names.isEmpty())
        {
            sb.append("**Union of:** ").append(String.join(", ", names)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static void renderDataSetFields(StringBuilder sb, EList<DataSetField> fields, String language)
    {
        if (fields.isEmpty())
        {
            return;
        }
        sb.append("**Fields:**\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader(COLUMN_DATA_PATH, "Field", COLUMN_TITLE, "Role")); //$NON-NLS-1$ //$NON-NLS-2$
        for (DataSetField field : fields)
        {
            if (field instanceof DataCompositionSchemaDataSetField)
            {
                DataCompositionSchemaDataSetField f = (DataCompositionSchemaDataSetField)field;
                sb.append(MarkdownUtils.tableRow(f.getDataPath(), f.getField(),
                    presentationText(f.getTitle(), language), roleSummary(f.getRole())));
            }
            else if (field instanceof DataCompositionSchemaDataSetFieldFolder)
            {
                DataCompositionSchemaDataSetFieldFolder folder = (DataCompositionSchemaDataSetFieldFolder)field;
                sb.append(MarkdownUtils.tableRow(folder.getDataPath(), "", //$NON-NLS-1$
                    presentationText(folder.getTitle(), language), "(folder)")); //$NON-NLS-1$
            }
            else
            {
                sb.append(MarkdownUtils.tableRow("", "", "", field.eClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        sb.append('\n');
    }

    private static String dataSetKind(DataSet dataSet)
    {
        if (dataSet instanceof DataCompositionSchemaDataSetQuery)
        {
            return "query"; //$NON-NLS-1$
        }
        if (dataSet instanceof DataCompositionSchemaDataSetObject)
        {
            return "object"; //$NON-NLS-1$
        }
        if (dataSet instanceof DataCompositionSchemaDataSetUnion)
        {
            return "union"; //$NON-NLS-1$
        }
        return dataSet.eClass().getName();
    }

    // ==================== Calculated fields / Total fields ====================

    private static void renderCalculatedFields(StringBuilder sb, DataCompositionSchema schema, String language)
    {
        EList<DataCompositionSchemaCalculatedField> fields = schema.getCalculatedFields();
        if (fields.isEmpty())
        {
            return;
        }
        sb.append("## Calculated fields\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader(COLUMN_DATA_PATH, COLUMN_TITLE, "Expression")); //$NON-NLS-1$
        for (DataCompositionSchemaCalculatedField field : fields)
        {
            sb.append(MarkdownUtils.tableRow(field.getDataPath(), presentationText(field.getTitle(), language),
                field.getExpression()));
        }
        sb.append('\n');
    }

    private static void renderTotalFields(StringBuilder sb, DataCompositionSchema schema)
    {
        EList<DataCompositionSchemaTotalField> fields = schema.getTotalFields();
        if (fields.isEmpty())
        {
            return;
        }
        sb.append("## Total fields\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader(COLUMN_DATA_PATH, "Expression", "Groups")); //$NON-NLS-1$ //$NON-NLS-2$
        for (DataCompositionSchemaTotalField field : fields)
        {
            sb.append(MarkdownUtils.tableRow(field.getDataPath(), field.getExpression(),
                String.join(", ", field.getGroups()))); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    // ==================== Parameters ====================

    private static void renderParameters(StringBuilder sb, DataCompositionSchema schema, String language)
    {
        EList<DataCompositionSchemaParameter> parameters = schema.getParameters();
        if (parameters.isEmpty())
        {
            return;
        }
        sb.append("## Parameters\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Name", COLUMN_TITLE, "Value type", "Value", "Use")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (DataCompositionSchemaParameter parameter : parameters)
        {
            sb.append(MarkdownUtils.tableRow(parameter.getName(), presentationText(parameter.getTitle(), language),
                describeType(parameter.getValueType()), joinValues(parameter.getValues()),
                enumLiteral(parameter.getUse())));
        }
        sb.append('\n');
    }

    // ==================== Default settings variant (typed settings API) ====================

    private static void renderDefaultSettings(StringBuilder sb, DataCompositionSchema schema, String language)
    {
        DataCompositionSettings settings = schema.getDefaultSettings();
        if (settings == null)
        {
            return;
        }
        String selection = renderSelection(settings.getSelection(), language);
        String filter = renderFilter(settings.getFilter());
        String order = renderOrder(settings.getOrder());
        if (selection.isEmpty() && filter.isEmpty() && order.isEmpty())
        {
            return;
        }
        sb.append("## Default settings\n\n"); //$NON-NLS-1$
        sb.append(selection).append(filter).append(order);
    }

    /** Package-visible (not private) so the typed settings projection is directly unit-testable. */
    static String renderSelection(DataCompositionSelectedFields selection, String language)
    {
        if (selection == null || selection.getItems().isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("### Selection\n\n"); //$NON-NLS-1$
        for (SelectedItem item : selection.getItems())
        {
            appendSelectedItem(sb, item, 0, language);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendSelectedItem(StringBuilder sb, SelectedItem item, int depth, String language)
    {
        indent(sb, depth);
        if (item instanceof DataCompositionSelectedField)
        {
            DataCompositionSelectedField field = (DataCompositionSelectedField)item;
            sb.append("- ").append(escapeOutline(describeValue(field.getField()))); //$NON-NLS-1$
            String title = presentationText(field.getTitle(), language);
            if (!title.isEmpty())
            {
                sb.append(" (title: ").append(escapeOutline(title)).append(')'); //$NON-NLS-1$
            }
            if (!field.isUse())
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
        }
        else if (item instanceof DataCompositionSelectedFieldGroup)
        {
            DataCompositionSelectedFieldGroup group = (DataCompositionSelectedFieldGroup)item;
            sb.append("- ").append(escapeOutline(describeValue(group.getField()))).append(" (group)"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!group.isUse())
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
            for (SelectedItem child : group.getItems())
            {
                appendSelectedItem(sb, child, depth + 1, language);
            }
        }
        else if (item instanceof DataCompositionAutoSelectedField)
        {
            sb.append("- _(auto fields)_\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("- ").append(item.eClass().getName()).append('\n'); //$NON-NLS-1$
        }
    }

    /** Package-visible (not private) so the typed settings projection is directly unit-testable. */
    static String renderFilter(DataCompositionFilter filter)
    {
        if (filter == null || filter.getItems().isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("### Filter\n\n"); //$NON-NLS-1$
        for (FilterItem item : filter.getItems())
        {
            appendFilterItem(sb, item, 0);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendFilterItem(StringBuilder sb, FilterItem item, int depth)
    {
        indent(sb, depth);
        if (item instanceof DataCompositionFilterItem)
        {
            DataCompositionFilterItem condition = (DataCompositionFilterItem)item;
            sb.append("- ").append(escapeOutline(describeValue(condition.getLeft()))); //$NON-NLS-1$
            String comparison = settingsEnumLiteral(condition.getComparisonType());
            if (!comparison.isEmpty())
            {
                sb.append(' ').append(comparison);
            }
            String right = joinValues(condition.getRight());
            if (!right.isEmpty())
            {
                sb.append(' ').append(escapeOutline(right));
            }
            if (!condition.isUse())
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
        }
        else if (item instanceof DataCompositionFilterItemGroup)
        {
            DataCompositionFilterItemGroup group = (DataCompositionFilterItemGroup)item;
            String groupType = settingsEnumLiteral(group.getGroupType());
            sb.append("- ").append(groupType.isEmpty() ? "group" : groupType + " group"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!group.isUse())
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
            for (FilterItem child : group.getItems())
            {
                appendFilterItem(sb, child, depth + 1);
            }
        }
        else
        {
            sb.append("- ").append(item.eClass().getName()).append('\n'); //$NON-NLS-1$
        }
    }

    /** Package-visible (not private) so the typed settings projection is directly unit-testable. */
    static String renderOrder(DataCompositionOrder order)
    {
        if (order == null || order.getItems().isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("### Order\n\n"); //$NON-NLS-1$
        for (OrderItem item : order.getItems())
        {
            appendOrderItem(sb, item);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendOrderItem(StringBuilder sb, OrderItem item)
    {
        if (item instanceof DataCompositionOrderItem)
        {
            DataCompositionOrderItem orderItem = (DataCompositionOrderItem)item;
            sb.append("- ").append(escapeOutline(describeValue(orderItem.getField()))); //$NON-NLS-1$
            String direction = settingsEnumLiteral(orderItem.getOrderType());
            if (!direction.isEmpty())
            {
                sb.append(' ').append(direction);
            }
            if (!orderItem.isUse())
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
        }
        else if (item instanceof DataCompositionAutoOrderItem)
        {
            sb.append("- _(auto order)_\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("- ").append(item.eClass().getName()).append('\n'); //$NON-NLS-1$
        }
    }

    // ==================== Shared value / type / presentation helpers ====================

    /**
     * Describes an mcore {@link Value} (a filter/order/selection operand) as a short, readable string.
     * The common DCS cases are resolved by their typed API: a {@link DataCompositionField} (a bound field
     * path - the ordinary case for the left side of a comparison, an order field, or a selected field) by
     * its path; the primitive value wrappers by their literal; a {@link DesignTimeValueValue} (a
     * design-time parameter/expression reference) by its raw text. Anything else degrades to its EClass
     * simple name rather than throwing (mirrors {@code FormStructureReader}'s reflective degrade
     * philosophy, applied here to a typed union of ~50 possible {@code Value} subtypes).
     *
     * @return the described value, or {@code ""} when {@code value} is {@code null}
     */
    private static String describeValue(Value value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof DataCompositionField)
        {
            return emptyIfNull(((DataCompositionField)value).getValue());
        }
        String simple = describeSimpleValue(value);
        if (simple != null)
        {
            return simple;
        }
        String typed = describeTypedValue(value);
        if (typed != null)
        {
            return typed;
        }
        return value.eClass().getName();
    }

    /**
     * Describes a primitive {@code mcore} value wrapper (String / Number / Boolean / Date) - the leaf
     * case of {@link #describeValue}, split out to keep that method's cognitive complexity low.
     *
     * @return the described value, or {@code null} when {@code value} is none of the primitive wrappers
     */
    private static String describeSimpleValue(Value value)
    {
        if (value instanceof StringValue)
        {
            return "\"" + emptyIfNull(((StringValue)value).getValue()) + "\""; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (value instanceof NumberValue)
        {
            Object number = ((NumberValue)value).getValue();
            return number != null ? number.toString() : ""; //$NON-NLS-1$
        }
        if (value instanceof BooleanValue)
        {
            return Boolean.toString(((BooleanValue)value).isValue());
        }
        if (value instanceof DateValue)
        {
            Object date = ((DateValue)value).getValue();
            return date != null ? date.toString() : ""; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Describes a typed-reference {@code mcore} value (Enum / Type / Reference / design-time value) -
     * the other leaf case of {@link #describeValue}, split out to keep that method's cognitive
     * complexity low.
     *
     * @return the described value, or {@code null} when {@code value} is none of these typed-reference
     *         kinds
     */
    private static String describeTypedValue(Value value)
    {
        if (value instanceof EnumValue)
        {
            return describeEnumLiteral(((EnumValue)value).getValue());
        }
        if (value instanceof TypeValue)
        {
            TypeItem typeItem = ((TypeValue)value).getValue();
            return typeItem != null ? typeItemName(typeItem) : ""; //$NON-NLS-1$
        }
        if (value instanceof ReferenceValue)
        {
            Object ref = ((ReferenceValue)value).getValue();
            return ref != null ? ref.toString() : ""; //$NON-NLS-1$
        }
        if (value instanceof DesignTimeValueValue)
        {
            DesignTimeValue designTimeValue = ((DesignTimeValueValue)value).getValue();
            return designTimeValue != null ? emptyIfNull(designTimeValue.getValue()) : ""; //$NON-NLS-1$
        }
        return null;
    }

    /** An {@link EnumValue}'s literal: the {@link Enumerator} name, or its raw {@code toString()}. */
    private static String describeEnumLiteral(Object literal)
    {
        if (literal instanceof Enumerator)
        {
            return ((Enumerator)literal).getName();
        }
        return literal != null ? literal.toString() : ""; //$NON-NLS-1$
    }

    private static String joinValues(List<Value> values)
    {
        if (values == null || values.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        List<String> parts = new ArrayList<>();
        for (Value value : values)
        {
            parts.add(describeValue(value));
        }
        return String.join(", ", parts); //$NON-NLS-1$
    }

    /**
     * Reads the presentation text for the given language CODE: a localized {@link LocalString} (keyed by
     * language code, never by language name - CLAUDE.md don't #2) takes priority when present and
     * non-empty for the requested language, else the language-neutral {@link Presentation#getValue()}.
     *
     * @return the presentation text, or {@code ""} when {@code presentation} is {@code null} or carries
     *         neither a localized nor a neutral value
     */
    private static String presentationText(Presentation presentation, String language)
    {
        if (presentation == null)
        {
            return ""; //$NON-NLS-1$
        }
        LocalString local = presentation.getLocalValue();
        if (local != null)
        {
            String text = MetadataLanguageUtils.getSynonymForLanguage(local.getContent().map(), language);
            if (!text.isEmpty())
            {
                return text;
            }
        }
        String value = presentation.getValue();
        return value != null ? value : ""; //$NON-NLS-1$
    }

    /**
     * Describes an mcore {@link TypeDescription} as a comma-joined list of its contained type names (a
     * {@link Type}'s {@code getName()}, falling back to the type item's EClass simple name).
     *
     * @return the described type, or {@code ""} when {@code type} is {@code null} or declares no types
     */
    static String describeType(TypeDescription type)
    {
        if (type == null)
        {
            return ""; //$NON-NLS-1$
        }
        EList<TypeItem> types = type.getTypes();
        if (types.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        List<String> names = new ArrayList<>();
        for (TypeItem typeItem : types)
        {
            names.add(typeItemName(typeItem));
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    private static String typeItemName(TypeItem typeItem)
    {
        if (typeItem instanceof Type)
        {
            String name = ((Type)typeItem).getName();
            if (nonEmpty(name))
            {
                return name;
            }
        }
        return typeItem.eClass().getName();
    }

    /** Summarizes a field's role as a comma-joined list of its SET boolean flags plus an optional period type. */
    private static String roleSummary(DataCompositionDataSetFieldRole role)
    {
        if (role == null)
        {
            return ""; //$NON-NLS-1$
        }
        List<String> flags = new ArrayList<>();
        if (role.isDimension())
        {
            flags.add("dimension"); //$NON-NLS-1$
        }
        if (role.isMain())
        {
            flags.add("main"); //$NON-NLS-1$
        }
        if (role.isRequired())
        {
            flags.add("required"); //$NON-NLS-1$
        }
        if (role.isIgnoreNullValues())
        {
            flags.add("ignoreNullValues"); //$NON-NLS-1$
        }
        if (role.isDimensionAttribute())
        {
            flags.add("dimensionAttribute"); //$NON-NLS-1$
        }
        if (role.isAccount())
        {
            flags.add("account"); //$NON-NLS-1$
        }
        if (role.isBalance())
        {
            flags.add("balance"); //$NON-NLS-1$
        }
        if (role.getPeriodType() != null)
        {
            flags.add("periodType=" + enumLiteral(role.getPeriodType())); //$NON-NLS-1$
        }
        return String.join(", ", flags); //$NON-NLS-1$
    }

    /** Reads an EMF enum literal via the common {@link Enumerator} interface, never {@code null}. */
    private static String enumLiteral(Enumerator value)
    {
        return value != null ? value.getName() : ""; //$NON-NLS-1$
    }

    /** Settings outlines use the generated enum constant token ({@code GREATER}, {@code ASC}). */
    private static String settingsEnumLiteral(Enumerator value)
    {
        return value instanceof Enum<?> ? ((Enum<?>)value).name() : enumLiteral(value);
    }

    private static String nameOrUnnamed(String name)
    {
        return nonEmpty(name) ? name : "(unnamed)"; //$NON-NLS-1$
    }

    private static String emptyIfNull(String value)
    {
        return nonEmpty(value) ? value : ""; //$NON-NLS-1$
    }

    private static boolean nonEmpty(String value)
    {
        return value != null && !value.isEmpty();
    }

    private static void indent(StringBuilder sb, int depth)
    {
        for (int i = 0; i < depth; i++)
        {
            sb.append("  "); //$NON-NLS-1$
        }
    }

    /** Strips line breaks from a value embedded in a bullet-outline line, so it cannot break the outline. */
    private static String escapeOutline(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        return text.replace("\r", "").replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
