/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaFieldUseRestriction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChart;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChartGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFieldGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldExpression;
import com._1c.g5.v8.dt.dcs.model.settings.GroupItem;
import com._1c.g5.v8.dt.dcs.model.settings.SelectedItem;
import com._1c.g5.v8.dt.dcs.model.settings.StructureItem;
import com._1c.g5.v8.dt.dcs.model.settings.UserField;
import com._1c.g5.v8.dt.dcs.util.DcsTerms;

/**
 * Reference guard for DCS charts. A chart draws its point (category) and series axes from
 * group fields and its values from its selection, whose items must be resources. The platform
 * stores a chart whose references resolve to nothing and then renders an empty area, so every
 * chart a write adds or changes is checked here against the end state of that write.
 *
 * <p>Resolution mirrors EDT's available-fields rules on the schema model: data-set and calculated
 * fields group unless their use restriction forbids it, a resource is a {@code totalFields}
 * entry, and paths compare case-insensitively. Two things are not provable from the model and
 * are accepted: the attribute part of a dotted path ({@code Field.Attribute}), which depends on
 * the field's runtime type, and a field an auto-fill query data set derives from its query text
 * without listing it.</p>
 */
public final class DcsChartReferences
{
    /** Role of a point (category) group field. */
    public static final String ROLE_POINTS = "points"; //$NON-NLS-1$
    /** Role of a series group field. */
    public static final String ROLE_SERIES = "series"; //$NON-NLS-1$
    /** Role of a measure, an item of the chart's selection. */
    public static final String ROLE_MEASURE = "selection"; //$NON-NLS-1$

    private static final int MAX_CHOICES = 20;
    private static final int MAX_PROBLEMS = 10;

    private static final Pattern AGGREGATE = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(Sum|Count|Max|Min|Avg|\u0421\u0443\u043c\u043c\u0430" //$NON-NLS-1$
            + "|\u041a\u043e\u043b\u0438\u0447\u0435\u0441\u0442\u0432\u043e" //$NON-NLS-1$
            + "|\u041c\u0430\u043a\u0441\u0438\u043c\u0443\u043c|\u041c\u0438\u043d\u0438\u043c\u0443\u043c" //$NON-NLS-1$
            + "|\u0421\u0440\u0435\u0434\u043d\u0435\u0435)\\s*\\(", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String[][] PERCENT_TERMS = {
        DcsTerms.kDCSSystemFieldsGroupPercent, DcsTerms.kDCSSystemFieldsOverallPercent,
        DcsTerms.kDCSSystemFieldsHierarchicalPercent, DcsTerms.kDCSSystemFieldsRowOrSeriesPercent,
        DcsTerms.kDCSSystemFieldsColumnOrPointPercent, DcsTerms.kDCSSystemFieldsRowOrSeriesGroupPercent,
        DcsTerms.kDCSSystemFieldsColumnOrPointGroupPercent,
        DcsTerms.kDCSSystemFieldsPercentInRowOrSeriesHierarchy,
        DcsTerms.kDCSSystemFieldsPercentInColumnOrPointHierarchy};

    private DcsChartReferences()
    {
        // Utility class
    }

    /** One chart reference; {@code field} is {@code null} for an automatic measure. */
    public static final class Reference
    {
        public final String role;
        public final String field;
        public final String address;

        Reference(String role, String field, String address)
        {
            this.role = role;
            this.field = field;
            this.address = address;
        }
    }

    /** Every point and series group field and every measure of a chart, with its address. */
    public static List<Reference> references(DataCompositionChart chart, String chartAddress)
    {
        List<Reference> result = new ArrayList<>();
        collectGroups(chart.getPoints(), ROLE_POINTS, chartAddress + "/" + ROLE_POINTS, result); //$NON-NLS-1$
        collectGroups(chart.getSeries(), ROLE_SERIES, chartAddress + "/" + ROLE_SERIES, result); //$NON-NLS-1$
        DataCompositionSelectedFields selection = chart.getSelection();
        if (selection != null)
        {
            collectMeasures(selection.getItems(), chartAddress + "/selection/items", result); //$NON-NLS-1$
        }
        return result;
    }

    /** Whether {@code planned} holds a chart that {@code current} does not hold unchanged. */
    public static boolean changesCharts(DataCompositionSettings current, DataCompositionSettings planned)
    {
        return !changed(charts(current), charts(planned)).isEmpty();
    }

    /**
     * Refusal for the first chart in {@code planned} that is new or changed relative to
     * {@code original} and refers to data {@code schema} does not have, or {@code null}. A bad
     * reference the same chart already had before the write is left alone, so an unrelated edit
     * never fails over it.
     *
     * @param schema the schema in the state this write leaves it in
     * @param original the settings before the write, possibly {@code null}
     * @param planned the settings this write commits, possibly {@code null}
     * @param settingsAddress canonical address of the settings object
     * @return an actionable refusal, or {@code null}
     */
    public static String error(DataCompositionSchema schema, DataCompositionSettings original,
        DataCompositionSettings planned, String settingsAddress)
    {
        if (schema == null || planned == null)
        {
            return null;
        }
        Map<String, DataCompositionChart> before = charts(original);
        Resolver resolver = null;
        for (Map.Entry<String, DataCompositionChart> entry : changed(before, charts(planned)).entrySet())
        {
            DataCompositionChart chart = entry.getValue();
            if (resolver == null)
            {
                resolver = new Resolver(schema, planned);
            }
            String chartAddress = settingsAddress + "/" + entry.getKey(); //$NON-NLS-1$
            List<Problem> problems = resolver.problems(chart, chartAddress);
            DataCompositionChart counterpart = before.get(entry.getKey());
            if (counterpart != null && !problems.isEmpty())
            {
                Set<String> existing = new LinkedHashSet<>();
                for (Problem problem : resolver.problems(counterpart, chartAddress))
                {
                    existing.add(problem.key());
                }
                problems.removeIf(problem -> existing.contains(problem.key()));
            }
            if (!problems.isEmpty())
            {
                return resolver.render(chartAddress, problems);
            }
        }
        return null;
    }

    /** Charts of a settings tree, keyed by their address relative to the settings. */
    static Map<String, DataCompositionChart> charts(DataCompositionSettings settings)
    {
        Map<String, DataCompositionChart> result = new LinkedHashMap<>();
        if (settings != null)
        {
            collectCharts(settings.getItems(), "items", result); //$NON-NLS-1$
        }
        return result;
    }

    private static void collectCharts(List<StructureItem> items, String where,
        Map<String, DataCompositionChart> result)
    {
        for (int i = 0; i < items.size(); i++)
        {
            StructureItem item = items.get(i);
            String address = where + "/" + i; //$NON-NLS-1$
            if (item instanceof DataCompositionChart)
            {
                result.put(address, (DataCompositionChart)item);
            }
            else if (item instanceof DataCompositionGroup)
            {
                collectCharts(((DataCompositionGroup)item).getItems(), address + "/items", result); //$NON-NLS-1$
            }
        }
    }

    /**
     * The planned charts no unchanged original accounts for. Each original accounts for one equal
     * chart, at its own address first and then anywhere, so a moved chart is not a change and a
     * copy of one is. Accounted originals leave {@code before}, which keeps only the changed or
     * removed ones.
     */
    private static Map<String, DataCompositionChart> changed(Map<String, DataCompositionChart> before,
        Map<String, DataCompositionChart> planned)
    {
        Map<String, DataCompositionChart> result = new LinkedHashMap<>(planned);
        result.entrySet().removeIf(entry -> EcoreUtil.equals(before.get(entry.getKey()), entry.getValue())
            && before.remove(entry.getKey()) != null);
        result.entrySet().removeIf(entry -> removeEqual(before, entry.getValue()));
        return result;
    }

    private static boolean removeEqual(Map<String, DataCompositionChart> charts, DataCompositionChart chart)
    {
        for (Iterator<DataCompositionChart> it = charts.values().iterator(); it.hasNext();)
        {
            if (EcoreUtil.equals(it.next(), chart))
            {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private static void collectGroups(List<DataCompositionChartGroup> groups, String role,
        String where, List<Reference> result)
    {
        for (int i = 0; i < groups.size(); i++)
        {
            DataCompositionChartGroup group = groups.get(i);
            String address = where + "/" + i; //$NON-NLS-1$
            if (group.getGroupFields() != null)
            {
                List<GroupItem> fields = group.getGroupFields().getItems();
                for (int j = 0; j < fields.size(); j++)
                {
                    if (fields.get(j) instanceof DataCompositionGroupField)
                    {
                        result.add(new Reference(role,
                            path(((DataCompositionGroupField)fields.get(j)).getField()),
                            address + "/groupFields/items/" + j)); //$NON-NLS-1$
                    }
                }
            }
            collectGroups(group.getItems(), role, address + "/items", result); //$NON-NLS-1$
        }
    }

    private static void collectMeasures(List<SelectedItem> items, String where, List<Reference> result)
    {
        for (int i = 0; i < items.size(); i++)
        {
            SelectedItem item = items.get(i);
            String address = where + "/" + i; //$NON-NLS-1$
            if (item instanceof DataCompositionAutoSelectedField)
            {
                result.add(new Reference(ROLE_MEASURE, null, address));
            }
            else if (item instanceof DataCompositionSelectedField)
            {
                result.add(new Reference(ROLE_MEASURE,
                    path(((DataCompositionSelectedField)item).getField()), address));
            }
            else if (item instanceof DataCompositionSelectedFieldGroup)
            {
                collectMeasures(((DataCompositionSelectedFieldGroup)item).getItems(),
                    address + "/items", result); //$NON-NLS-1$
            }
        }
    }

    private static String path(DataCompositionField field)
    {
        return field == null || field.getValue() == null ? "" : field.getValue(); //$NON-NLS-1$
    }

    private static String lower(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }

    /** User-field paths compare by the part after the folder term, which is spelled in either language. */
    private static String userFieldKey(String path)
    {
        String low = lower(path);
        for (String term : DcsTerms.kDCSSUserFieldsTerm)
        {
            String prefix = lower(term) + "."; //$NON-NLS-1$
            if (low.startsWith(prefix))
            {
                return low.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean isPercentTerm(String value)
    {
        for (String[] term : PERCENT_TERMS)
        {
            for (String spelling : term)
            {
                if (spelling.equalsIgnoreCase(value)) return true;
            }
        }
        return false;
    }

    /** One unresolved reference and why. */
    private static final class Problem
    {
        final Reference reference;
        final String reason;

        Problem(Reference reference, String reason)
        {
            this.reference = reference;
            this.reason = reason;
        }

        String key()
        {
            return reference.role + '\n' + (reference.field == null ? "" : lower(reference.field)); //$NON-NLS-1$
        }
    }

    /** A schema field that settings can group by. */
    private static final class FieldInfo
    {
        final String dataPath;
        boolean groupable;
        boolean childrenGroupable;

        FieldInfo(String dataPath)
        {
            this.dataPath = dataPath;
        }
    }

    /** The fields, resources and user fields of the write's end state. */
    private static final class Resolver
    {
        private final Map<String, FieldInfo> fields = new LinkedHashMap<>();
        private final Map<String, String> resources = new LinkedHashMap<>();
        private final Map<String, String> userFields = new LinkedHashMap<>();
        private final Map<String, Boolean> userResources = new LinkedHashMap<>();
        private final List<String> autoFillQueries = new ArrayList<>();

        Resolver(DataCompositionSchema schema, DataCompositionSettings settings)
        {
            for (DataSet dataSet : schema.getDataSets())
            {
                addDataSet(dataSet);
            }
            for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields())
            {
                String dataPath = field.getDataPath();
                if (dataPath == null || dataPath.isEmpty()) continue;
                boolean groupable = !restrictsGroup(field.getUseRestriction());
                FieldInfo info = fields.computeIfAbsent(lower(dataPath), key -> new FieldInfo(dataPath));
                info.groupable |= groupable;
                info.childrenGroupable |= groupable;
            }
            for (DataCompositionSchemaTotalField total : schema.getTotalFields())
            {
                String dataPath = total.getDataPath();
                if (dataPath != null && !dataPath.isEmpty())
                {
                    resources.putIfAbsent(lower(dataPath), dataPath);
                }
            }
            if (settings.getUserFields() != null)
            {
                for (UserField field : settings.getUserFields().getItems())
                {
                    String dataPath = field.getDataPath();
                    if (dataPath == null || dataPath.isEmpty()) continue;
                    String key = userFieldKey(dataPath);
                    key = key == null ? lower(dataPath) : key;
                    userFields.putIfAbsent(key, dataPath);
                    userResources.merge(key, Boolean.valueOf(isResource(field)), Boolean::logicalOr);
                }
            }
        }

        private void addDataSet(DataSet dataSet)
        {
            if (dataSet instanceof DataCompositionSchemaDataSetQuery
                && ((DataCompositionSchemaDataSetQuery)dataSet).isAutoFillAvailableFields())
            {
                String query = ((DataCompositionSchemaDataSetQuery)dataSet).getQuery();
                if (query != null) autoFillQueries.add(query);
            }
            for (DataSetField field : dataSet.getFields())
            {
                if (!(field instanceof DataCompositionSchemaDataSetField)) continue;
                DataCompositionSchemaDataSetField dataSetField = (DataCompositionSchemaDataSetField)field;
                String dataPath = dataSetField.getDataPath();
                if (dataPath == null || dataPath.isEmpty()) continue;
                boolean groupable = !restrictsGroup(dataSetField.getUseRestriction());
                FieldInfo info = fields.computeIfAbsent(lower(dataPath), key -> new FieldInfo(dataPath));
                info.groupable |= groupable;
                info.childrenGroupable |= groupable
                    && !restrictsGroup(dataSetField.getAttributeUseRestriction());
            }
            if (dataSet instanceof DataCompositionSchemaDataSetUnion)
            {
                for (DataSet item : ((DataCompositionSchemaDataSetUnion)dataSet).getItems())
                {
                    addDataSet(item);
                }
            }
        }

        private static boolean restrictsGroup(DataCompositionSchemaFieldUseRestriction restriction)
        {
            return restriction != null && restriction.isGroup();
        }

        private static boolean isResource(UserField field)
        {
            if (!(field instanceof DataCompositionUserFieldExpression)) return false;
            DataCompositionUserFieldExpression expression = (DataCompositionUserFieldExpression)field;
            String total = expression.getTotalExpression();
            String detail = expression.getDetailExpression();
            return total != null && !total.trim().isEmpty()
                || detail != null && AGGREGATE.matcher(detail).find();
        }

        private boolean hasResources()
        {
            return !resources.isEmpty() || userResources.containsValue(Boolean.TRUE);
        }

        List<Problem> problems(DataCompositionChart chart, String chartAddress)
        {
            List<Problem> result = new ArrayList<>();
            boolean measured = false;
            for (Reference reference : references(chart, chartAddress))
            {
                String reason;
                if (ROLE_MEASURE.equals(reference.role))
                {
                    measured = true;
                    reason = reference.field == null ? autoMeasureReason() : measureReason(reference.field);
                }
                else
                {
                    reason = groupReason(reference.field);
                }
                if (reason != null)
                {
                    result.add(new Problem(reference, reason));
                }
            }
            if (!measured)
            {
                result.add(new Problem(new Reference(ROLE_MEASURE, null, chartAddress + "/selection"), //$NON-NLS-1$
                    "the chart has no measure, so it has nothing to draw")); //$NON-NLS-1$
            }
            return result;
        }

        private String autoMeasureReason()
        {
            return hasResources() ? null
                : "the automatic measure expands to the schema's resources, and it has none"; //$NON-NLS-1$
        }

        private String measureReason(String path)
        {
            if (path.isEmpty()) return "the measure names no field"; //$NON-NLS-1$
            String key = lower(path);
            if (resources.containsKey(key)) return null;
            String userKey = userFieldKey(path);
            if (userKey != null && userFields.containsKey(userKey))
            {
                return Boolean.TRUE.equals(userResources.get(userKey)) ? null
                    : "user field '" + userFields.get(userKey) + "' has no total expression, " //$NON-NLS-1$ //$NON-NLS-2$
                        + "so it is not a resource"; //$NON-NLS-1$
            }
            int dot = path.lastIndexOf('.');
            if (dot > 0 && isPercentTerm(path.substring(dot + 1))
                && resources.containsKey(lower(path.substring(0, dot))))
            {
                return null;
            }
            if (fields.containsKey(key))
            {
                return "'" + fields.get(key).dataPath + "' is a field but not a resource"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return "no resource has this data path"; //$NON-NLS-1$
        }

        private String groupReason(String path)
        {
            if (path.isEmpty()) return "the group field names no field"; //$NON-NLS-1$
            String key = lower(path);
            FieldInfo info = fields.get(key);
            if (info != null)
            {
                return info.groupable ? null
                    : "'" + info.dataPath + "' is not available for grouping (its use restriction " //$NON-NLS-1$ //$NON-NLS-2$
                        + "forbids it)"; //$NON-NLS-1$
            }
            String userKey = userFieldKey(path);
            if (userKey != null && userFields.containsKey(userKey)) return null;
            if (resources.containsKey(key))
            {
                return "'" + resources.get(key) + "' is a resource; a resource cannot be a point " //$NON-NLS-1$ //$NON-NLS-2$
                    + "or series"; //$NON-NLS-1$
            }
            for (int dot = path.lastIndexOf('.'); dot > 0; dot = path.lastIndexOf('.', dot - 1))
            {
                String head = lower(path.substring(0, dot));
                if (resources.containsKey(head))
                {
                    return "'" + resources.get(head) + "' is a resource, and a resource's attributes " //$NON-NLS-1$ //$NON-NLS-2$
                        + "cannot be grouped"; //$NON-NLS-1$
                }
                FieldInfo parent = fields.get(head);
                if (parent != null)
                {
                    return parent.childrenGroupable ? null
                        : "the attributes of '" + parent.dataPath + "' are not available for " //$NON-NLS-1$ //$NON-NLS-2$
                            + "grouping (its attribute use restriction forbids it)"; //$NON-NLS-1$
                }
            }
            int dot = path.indexOf('.');
            return mentionedByAutoFillQuery(dot < 0 ? path : path.substring(0, dot)) ? null
                : "no schema field, calculated field or user field has this data path"; //$NON-NLS-1$
        }

        private boolean mentionedByAutoFillQuery(String name)
        {
            if (autoFillQueries.isEmpty() || name.isEmpty()) return false;
            Pattern word = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name) //$NON-NLS-1$
                + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); //$NON-NLS-1$
            for (String query : autoFillQueries)
            {
                if (word.matcher(query).find()) return true;
            }
            return false;
        }

        String render(String chartAddress, List<Problem> problems)
        {
            StringBuilder message = new StringBuilder("Chart at '").append(chartAddress) //$NON-NLS-1$
                .append("' refers to data this schema does not have, so it would render empty. "); //$NON-NLS-1$
            boolean groupProblem = false;
            boolean measureProblem = false;
            for (int i = 0; i < problems.size() && i < MAX_PROBLEMS; i++)
            {
                Problem problem = problems.get(i);
                String role = ROLE_MEASURE.equals(problem.reference.role) ? "measure" //$NON-NLS-1$
                    : ROLE_POINTS.equals(problem.reference.role) ? "point" : "series"; //$NON-NLS-1$ //$NON-NLS-2$
                message.append(i == 0 ? "" : " ").append(role); //$NON-NLS-1$ //$NON-NLS-2$
                if (problem.reference.field != null)
                {
                    message.append(" '").append(problem.reference.field).append('\''); //$NON-NLS-1$
                }
                message.append(" at '").append(problem.reference.address).append("': ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(problem.reason).append('.');
                groupProblem |= !ROLE_MEASURE.equals(problem.reference.role);
                measureProblem |= ROLE_MEASURE.equals(problem.reference.role);
            }
            if (problems.size() > MAX_PROBLEMS)
            {
                message.append(" (").append(problems.size() - MAX_PROBLEMS) //$NON-NLS-1$
                    .append(" more)"); //$NON-NLS-1$
            }
            if (measureProblem)
            {
                List<String> choices = new ArrayList<>(resources.values());
                for (Map.Entry<String, String> entry : userFields.entrySet())
                {
                    if (Boolean.TRUE.equals(userResources.get(entry.getKey()))) choices.add(entry.getValue());
                }
                message.append(" A measure must be a resource: ").append(choices(choices)) //$NON-NLS-1$
                    .append(", a resource's percent field such as '<resource>.") //$NON-NLS-1$
                    .append(DcsTerms.kDCSSystemFieldsOverallPercent[0])
                    .append("', or kind='auto'. Declare a missing resource in totalFields first."); //$NON-NLS-1$
            }
            if (groupProblem)
            {
                List<String> choices = new ArrayList<>();
                for (FieldInfo info : fields.values())
                {
                    if (info.groupable) choices.add(info.dataPath);
                }
                choices.addAll(userFields.values());
                message.append(" A point or series groups by a schema field, calculated field or ") //$NON-NLS-1$
                    .append("user field, or an attribute of one ('<field>.<attribute>'): ") //$NON-NLS-1$
                    .append(choices(choices)).append('.');
            }
            return message.append(" Nothing was written.").toString(); //$NON-NLS-1$
        }

        private static String choices(List<String> values)
        {
            if (values.isEmpty()) return "(none in this schema)"; //$NON-NLS-1$
            List<String> shown = values.size() > MAX_CHOICES ? values.subList(0, MAX_CHOICES) : values;
            String result = String.join(", ", shown); //$NON-NLS-1$
            return values.size() > MAX_CHOICES
                ? result + " and " + (values.size() - MAX_CHOICES) + " more (dcs action='get' lists them)" //$NON-NLS-1$ //$NON-NLS-2$
                : result;
        }
    }
}
