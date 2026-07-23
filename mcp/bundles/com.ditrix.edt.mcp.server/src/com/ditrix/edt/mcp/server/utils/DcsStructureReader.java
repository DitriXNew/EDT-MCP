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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

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
 * Shared READER that renders a 1C Data Composition Schema (СКД / a {@code .dcs} resource) - the model
 * behind a Report's / CommonTemplate's / object-owned Template's {@link DataCompositionSchema} content -
 * to a full Markdown document: data sources, data sets (with the FULL query text in a fenced block and
 * their fields), calculated fields, total fields, parameters, and the DEFAULT settings variant's
 * structure (selection / filter / order), as far as the typed model allows. An empty section (an empty
 * list, or no default settings) is skipped entirely rather than rendered with a placeholder - issue #267.
 *
 * <p>Like {@link DcsWriter} (the DCS WRITER), this reader uses the TYPED DCS API directly for the
 * {@code schema} / {@code core} / {@code common} packages and {@code com._1c.g5.v8.dt.mcore} - the
 * {@link DataCompositionSchema} itself, its data sources / data sets / fields / calculated fields / total
 * fields / parameters, {@link Presentation} / {@link LocalString} titles, and every {@code mcore}
 * {@code Value} subtype. The {@code com._1c.g5.v8.dt.dcs.model.settings} package (the DEFAULT SETTINGS
 * variant: {@code DataCompositionSettings} / selection / filter / order and their enums) is a Tycho
 * ACCESS-RESTRICTED (non-API) package on this target platform - a compile-time reference to any of its
 * types fails the build - so that one subtree is read via EMF REFLECTION instead (mirroring
 * {@link FormStructureReader}'s reflective form-model access, for the same class of reason: a forbidden
 * compile-time dependency), starting from the schema's {@code defaultSettings} feature.</p>
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

    // ==================== Default settings variant (reflective - access-restricted "settings" package) ====================
    //
    // com._1c.g5.v8.dt.dcs.model.settings (DataCompositionSettings / selection / filter / order and their
    // enums) is Tycho ACCESS-RESTRICTED on this target platform (proven at build time - a compile-time
    // reference fails), so this whole subtree is read through EMF reflection from the schema's
    // 'defaultSettings' feature onward, exactly like FormStructureReader reads the form-model package. A
    // feature value that resolves to an ACCESSIBLE typed instance (an mcore Value / a core Presentation)
    // is cast back to its typed interface and rendered by the existing typed helpers below.

    private static final String FEATURE_DEFAULT_SETTINGS = "defaultSettings"; //$NON-NLS-1$
    private static final String FEATURE_SELECTION = "selection"; //$NON-NLS-1$
    private static final String FEATURE_FILTER = "filter"; //$NON-NLS-1$
    private static final String FEATURE_ORDER = "order"; //$NON-NLS-1$
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    private static final String FEATURE_FIELD = "field"; //$NON-NLS-1$
    private static final String FEATURE_LEFT = "left"; //$NON-NLS-1$
    private static final String FEATURE_RIGHT = "right"; //$NON-NLS-1$
    private static final String FEATURE_COMPARISON_TYPE = "comparisonType"; //$NON-NLS-1$
    private static final String FEATURE_GROUP_TYPE = "groupType"; //$NON-NLS-1$
    private static final String FEATURE_ORDER_TYPE = "orderType"; //$NON-NLS-1$
    private static final String FEATURE_TITLE = "title"; //$NON-NLS-1$
    private static final String FEATURE_USE = "use"; //$NON-NLS-1$

    private static final String ECLASS_SELECTED_FIELD = "DataCompositionSelectedField"; //$NON-NLS-1$
    private static final String ECLASS_SELECTED_FIELD_GROUP = "DataCompositionSelectedFieldGroup"; //$NON-NLS-1$
    private static final String ECLASS_FILTER_ITEM = "DataCompositionFilterItem"; //$NON-NLS-1$
    private static final String ECLASS_FILTER_ITEM_GROUP = "DataCompositionFilterItemGroup"; //$NON-NLS-1$
    private static final String ECLASS_ORDER_ITEM = "DataCompositionOrderItem"; //$NON-NLS-1$

    private static void renderDefaultSettings(StringBuilder sb, DataCompositionSchema schema, String language)
    {
        EObject settings = getSingleReference(schema, FEATURE_DEFAULT_SETTINGS);
        if (settings == null)
        {
            return;
        }
        String selection = renderSelection(getSingleReference(settings, FEATURE_SELECTION), language);
        String filter = renderFilter(getSingleReference(settings, FEATURE_FILTER));
        String order = renderOrder(getSingleReference(settings, FEATURE_ORDER));
        if (selection.isEmpty() && filter.isEmpty() && order.isEmpty())
        {
            return;
        }
        sb.append("## Default settings\n\n"); //$NON-NLS-1$
        sb.append(selection).append(filter).append(order);
    }

    /** Package-visible (not private) so it is directly unit-testable with a dynamic EObject fixture. */
    static String renderSelection(EObject selection, String language)
    {
        List<EObject> items = getReferenceList(selection, FEATURE_ITEMS);
        if (items.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("### Selection\n\n"); //$NON-NLS-1$
        for (EObject item : items)
        {
            appendSelectedItem(sb, item, 0, language);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendSelectedItem(StringBuilder sb, EObject item, int depth, String language)
    {
        indent(sb, depth);
        String eClassName = item.eClass().getName();
        if (ECLASS_SELECTED_FIELD.equals(eClassName) || ECLASS_SELECTED_FIELD_GROUP.equals(eClassName))
        {
            sb.append("- ").append(escapeOutline(describeValue(valueFeature(item, FEATURE_FIELD)))); //$NON-NLS-1$
            if (ECLASS_SELECTED_FIELD_GROUP.equals(eClassName))
            {
                sb.append(" (group)"); //$NON-NLS-1$
            }
            else
            {
                String title = presentationText(presentationFeature(item, FEATURE_TITLE), language);
                if (!title.isEmpty())
                {
                    sb.append(" (title: ").append(escapeOutline(title)).append(')'); //$NON-NLS-1$
                }
            }
            if (!isUsed(item))
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
            for (EObject child : getReferenceList(item, FEATURE_ITEMS))
            {
                appendSelectedItem(sb, child, depth + 1, language);
            }
        }
        else if (isAutoItem(eClassName))
        {
            sb.append("- _(auto fields)_\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("- ").append(eClassName).append('\n'); //$NON-NLS-1$
        }
    }

    /** Package-visible (not private) so it is directly unit-testable with a dynamic EObject fixture. */
    static String renderFilter(EObject filter)
    {
        List<EObject> items = getReferenceList(filter, FEATURE_ITEMS);
        if (items.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("### Filter\n\n"); //$NON-NLS-1$
        for (EObject item : items)
        {
            appendFilterItem(sb, item, 0);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendFilterItem(StringBuilder sb, EObject item, int depth)
    {
        indent(sb, depth);
        String eClassName = item.eClass().getName();
        if (ECLASS_FILTER_ITEM.equals(eClassName))
        {
            sb.append("- ").append(escapeOutline(describeValue(valueFeature(item, FEATURE_LEFT)))); //$NON-NLS-1$
            String comparison = enumFeature(item, FEATURE_COMPARISON_TYPE);
            if (!comparison.isEmpty())
            {
                sb.append(' ').append(comparison);
            }
            String right = joinValues(valueListFeature(item, FEATURE_RIGHT));
            if (!right.isEmpty())
            {
                sb.append(' ').append(escapeOutline(right));
            }
            if (!isUsed(item))
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
        }
        else if (ECLASS_FILTER_ITEM_GROUP.equals(eClassName))
        {
            String groupType = enumFeature(item, FEATURE_GROUP_TYPE);
            sb.append("- ").append(groupType.isEmpty() ? "group" : groupType + " group"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!isUsed(item))
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
            for (EObject child : getReferenceList(item, FEATURE_ITEMS))
            {
                appendFilterItem(sb, child, depth + 1);
            }
        }
        else
        {
            sb.append("- ").append(eClassName).append('\n'); //$NON-NLS-1$
        }
    }

    /** Package-visible (not private) so it is directly unit-testable with a dynamic EObject fixture. */
    static String renderOrder(EObject order)
    {
        List<EObject> items = getReferenceList(order, FEATURE_ITEMS);
        if (items.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("### Order\n\n"); //$NON-NLS-1$
        for (EObject item : items)
        {
            appendOrderItem(sb, item);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendOrderItem(StringBuilder sb, EObject item)
    {
        String eClassName = item.eClass().getName();
        if (ECLASS_ORDER_ITEM.equals(eClassName))
        {
            sb.append("- ").append(escapeOutline(describeValue(valueFeature(item, FEATURE_FIELD)))); //$NON-NLS-1$
            String direction = enumFeature(item, FEATURE_ORDER_TYPE);
            if (!direction.isEmpty())
            {
                sb.append(' ').append(direction);
            }
            if (!isUsed(item))
            {
                sb.append(SUFFIX_NOT_USED);
            }
            sb.append('\n');
        }
        else if (isAutoItem(eClassName))
        {
            sb.append("- _(auto order)_\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("- ").append(eClassName).append('\n'); //$NON-NLS-1$
        }
    }

    /** {@code true} for the "auto" marker item kinds (auto-filled selected field / auto order item). */
    private static boolean isAutoItem(String eClassName)
    {
        return eClassName.startsWith("DataCompositionAuto"); //$NON-NLS-1$
    }

    // ==================== EMF reflection helpers (the "settings" subtree only) ====================

    /** The value of a single-valued reference feature, or {@code null} when absent/unset/not-a-reference. */
    private static EObject getSingleReference(EObject object, String featureName)
    {
        if (object == null)
        {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || feature.isMany())
        {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof EObject ? (EObject)value : null;
    }

    /** A many-valued reference feature's contained {@link EObject}s, or an empty list when absent. */
    private static List<EObject> getReferenceList(EObject object, String featureName)
    {
        List<EObject> result = new ArrayList<>();
        if (object == null)
        {
            return result;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isMany())
        {
            return result;
        }
        Object value = object.eGet(feature);
        if (value instanceof List<?>)
        {
            for (Object element : (List<?>)value)
            {
                if (element instanceof EObject)
                {
                    result.add((EObject)element);
                }
            }
        }
        return result;
    }

    private static Object getValue(EObject object, String featureName)
    {
        if (object == null)
        {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        return feature != null ? object.eGet(feature) : null;
    }

    /** A reference feature's value cast back to the ACCESSIBLE {@code mcore} {@link Value} type. */
    private static Value valueFeature(EObject object, String featureName)
    {
        Object value = getValue(object, featureName);
        return value instanceof Value ? (Value)value : null;
    }

    /** A many-valued reference feature's elements cast back to the ACCESSIBLE {@code mcore} {@link Value} type. */
    private static List<Value> valueListFeature(EObject object, String featureName)
    {
        List<Value> result = new ArrayList<>();
        for (EObject element : getReferenceList(object, featureName))
        {
            if (element instanceof Value)
            {
                result.add((Value)element);
            }
        }
        return result;
    }

    /** A reference feature's value cast back to the ACCESSIBLE {@code core} {@link Presentation} type. */
    private static Presentation presentationFeature(EObject object, String featureName)
    {
        Object value = getValue(object, featureName);
        return value instanceof Presentation ? (Presentation)value : null;
    }

    /**
     * Reads a restricted-package enum feature via the common (accessible)
     * {@link org.eclipse.emf.common.util.Enumerator} interface, never naming the concrete (restricted)
     * enum type - the same trick {@link #describeValue} uses for an {@code EnumValue}.
     *
     * @return the enum literal, or {@code ""} when the feature is absent/unset/not an enum value
     */
    private static String enumFeature(EObject object, String featureName)
    {
        Object value = getValue(object, featureName);
        return value instanceof Enumerator ? enumLiteral((Enumerator)value) : ""; //$NON-NLS-1$
    }

    /**
     * @return the item's {@code use} flag; an absent feature or a non-Boolean value is treated as used
     *         ({@code true}), so the render only ever calls out a DISABLED item (mirrors
     *         {@code FormStructureReader.visibilityOf}'s defaulting philosophy for the same reason)
     */
    private static boolean isUsed(EObject item)
    {
        Object value = getValue(item, FEATURE_USE);
        return !(value instanceof Boolean) || ((Boolean)value).booleanValue();
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
    private static String describeType(TypeDescription type)
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
