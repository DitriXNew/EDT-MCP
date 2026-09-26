/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettingsItemState;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldExpression;
import com._1c.g5.v8.dt.dcs.model.settings.GroupItem;
import com._1c.g5.v8.dt.dcs.model.settings.SelectedItem;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.dcs.model.settings.StructureItem;
import com._1c.g5.v8.dt.dcs.model.settings.UserField;
import com._1c.g5.v8.dt.dcs.util.DcsTerms;

/**
 * Reference guard for DCS charts. A chart draws its point (category) and series axes from
 * group fields and its values from its selection, whose items must be resources. The platform
 * stores a chart whose references resolve to nothing and then renders an empty area.
 *
 * <p>The guard is a ratchet over the whole schema: {@link #census} counts the broken chart
 * references of every settings tree (default settings and each variant), and {@link #error}
 * refuses a write after which some kind of broken reference (settings tree, role and field) is
 * more frequent than before it. A write that breaks an unchanged chart by changing the schema is
 * refused like one that adds a broken chart, and a problem that was already there in the same
 * tree never blocks an unrelated edit. Switched-off points, series and measures are not judged.</p>
 *
 * <p>Resolution mirrors EDT's available-fields rules on the schema model: data-set and calculated
 * fields group unless their use restriction forbids it, a resource is a {@code totalFields}
 * entry, a user field is a leaf with no attributes or percent fields, and paths compare
 * case-insensitively. What the model cannot prove is accepted: the attribute part of a dotted
 * path under a schema field ({@code Field.Attribute}), whose existence depends on a runtime type;
 * any unlisted field when an auto-fill query data set exists, because EDT derives such a set's
 * fields by parsing its query; and a case user field as a measure.</p>
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

    /** The platform's aggregate functions, each as its English and Russian spelling. */
    private static final Set<String> AGGREGATES = lowerSet(
        "Sum", "\u0421\u0443\u043c\u043c\u0430", //$NON-NLS-1$ //$NON-NLS-2$
        "Count", "\u041a\u043e\u043b\u0438\u0447\u0435\u0441\u0442\u0432\u043e", //$NON-NLS-1$ //$NON-NLS-2$
        "Max", "\u041c\u0430\u043a\u0441\u0438\u043c\u0443\u043c", //$NON-NLS-1$ //$NON-NLS-2$
        "Min", "\u041c\u0438\u043d\u0438\u043c\u0443\u043c", //$NON-NLS-1$ //$NON-NLS-2$
        "Avg", "\u0421\u0440\u0435\u0434\u043d\u0435\u0435", //$NON-NLS-1$ //$NON-NLS-2$
        "Array", "\u041c\u0430\u0441\u0441\u0438\u0432", //$NON-NLS-1$ //$NON-NLS-2$
        "JoinStrings", "\u0421\u043e\u0435\u0434\u0438\u043d\u0438\u0442\u044c\u0421\u0442\u0440\u043e\u043a\u0438", //$NON-NLS-1$ //$NON-NLS-2$
        "Every", "\u041a\u0430\u0436\u0434\u044b\u0439", //$NON-NLS-1$ //$NON-NLS-2$
        "Any", "\u041b\u044e\u0431\u043e\u0439", //$NON-NLS-1$ //$NON-NLS-2$
        "Var_Samp", "\u0414\u0438\u0441\u043f\u0435\u0440\u0441\u0438\u044f\u0412\u044b\u0431\u043e\u0440\u043a\u0438", //$NON-NLS-1$ //$NON-NLS-2$
        "Var_Pop", "\u0414\u0438\u0441\u043f\u0435\u0440\u0441\u0438\u044f\u0413\u0435\u043d\u0435\u0440\u0430\u043b\u044c\u043d\u043e\u0439\u0421\u043e\u0432\u043e\u043a\u0443\u043f\u043d\u043e\u0441\u0442\u0438", //$NON-NLS-1$ //$NON-NLS-2$
        "Covar_Pop", "\u041a\u043e\u0432\u0430\u0440\u0438\u0430\u0446\u0438\u044f\u0413\u0435\u043d\u0435\u0440\u0430\u043b\u044c\u043d\u043e\u0439\u0421\u043e\u0432\u043e\u043a\u0443\u043f\u043d\u043e\u0441\u0442\u0438", //$NON-NLS-1$ //$NON-NLS-2$
        "Covar_Samp", "\u041a\u043e\u0432\u0430\u0440\u0438\u0430\u0446\u0438\u044f\u0412\u044b\u0431\u043e\u0440\u043a\u0438", //$NON-NLS-1$ //$NON-NLS-2$
        "Corr", "\u041a\u043e\u0440\u0440\u0435\u043b\u044f\u0446\u0438\u044f", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_Slope", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044f\u041d\u0430\u043a\u043b\u043e\u043d", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_Intercept", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044f\u041e\u0442\u0440\u0435\u0437\u043e\u043a", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_Count", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044f\u041a\u043e\u043b\u0438\u0447\u0435\u0441\u0442\u0432\u043e", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_R2", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044fR2", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_AvgX", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044f\u0421\u0440\u0435\u0434\u043d\u0435\u0435X", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_AvgY", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044f\u0421\u0440\u0435\u0434\u043d\u0435\u0435Y", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_SXX", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044fSXX", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_SYY", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044fSYY", //$NON-NLS-1$ //$NON-NLS-2$
        "Regr_SXY", "\u0420\u0435\u0433\u0440\u0435\u0441\u0441\u0438\u044fSXY"); //$NON-NLS-1$ //$NON-NLS-2$

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
        /** {@code false} when the item or a folder or group holding it is switched off. */
        public final boolean use;

        Reference(String role, String field, String address)
        {
            this(role, field, address, true);
        }

        Reference(String role, String field, String address, boolean use)
        {
            this.role = role;
            this.field = field;
            this.address = address;
            this.use = use;
        }
    }

    /**
     * The broken chart references of a schema, in document order and counted by kind. A kind is
     * a settings tree plus role and field: within one tree a reference resolves the same way in
     * every chart, so a write may move a break between charts of one tree but never add one.
     */
    public static final class Census
    {
        private final List<Problem> problems = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final Map<String, Integer> counts = new HashMap<>();

        private void add(String tree, Problem problem)
        {
            String key = tree + '\u0000' + problem.key;
            problems.add(problem);
            keys.add(key);
            counts.merge(key, Integer.valueOf(1), Integer::sum);
        }

        private int count(String key)
        {
            Integer count = counts.get(key);
            return count == null ? 0 : count.intValue();
        }

        /** Number of broken references counted. */
        public int size()
        {
            return problems.size();
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
     * The broken chart references of every settings tree of {@code schema}, judged against the
     * schema's own fields and each tree's own user fields.
     *
     * @param schema the schema, possibly {@code null}
     * @param rootFqn the canonical root the reported addresses start with
     * @return the census, empty when the schema has no chart
     */
    public static Census census(DataCompositionSchema schema, String rootFqn)
    {
        Census census = new Census();
        if (schema == null) return census;
        Resolver schemaLevel = null;
        // Parallel lists, not a map: two variants may share a name and both must be counted.
        List<String> addresses = new ArrayList<>();
        // The census identity of each tree: named variants by name (and rank among same-named
        // ones), unnamed ones by index, so neither kind can collide with the other.
        List<String> identities = new ArrayList<>();
        List<DataCompositionSettings> trees = new ArrayList<>();
        addresses.add(DcsAddress.render(rootFqn, Collections.singletonList("defaultSettings"))); //$NON-NLS-1$
        identities.add("d"); //$NON-NLS-1$
        trees.add(schema.getDefaultSettings());
        List<SettingsVariant> variants = schema.getSettingsVariants();
        Map<String, Integer> sameName = new HashMap<>();
        for (int i = 0; i < variants.size(); i++)
        {
            String name = variants.get(i).getName();
            boolean unnamed = name == null || name.isEmpty();
            String selector = unnamed ? Integer.toString(i) : name;
            addresses.add(DcsAddress.render(rootFqn, Arrays.asList("variants", selector, "settings"))); //$NON-NLS-1$ //$NON-NLS-2$
            identities.add(unnamed ? "i" + i //$NON-NLS-1$
                : "n" + name + '\u0000' + sameName.merge(name, Integer.valueOf(1), Integer::sum)); //$NON-NLS-1$
            trees.add(variants.get(i).getSettings());
        }
        for (int t = 0; t < trees.size(); t++)
        {
            String tree = identities.get(t);
            Map<String, DataCompositionChart> charts = drawnCharts(trees.get(t));
            if (charts.isEmpty()) continue;
            if (schemaLevel == null) schemaLevel = new Resolver(schema);
            Resolver resolver = schemaLevel.withUserFields(trees.get(t));
            for (Map.Entry<String, DataCompositionChart> chart : charts.entrySet())
            {
                String chartAddress = addresses.get(t) + "/" + chart.getKey(); //$NON-NLS-1$
                for (Problem problem : resolver.problems(chart.getValue(), chartAddress))
                {
                    census.add(tree, problem);
                }
            }
        }
        return census;
    }

    /**
     * Refusal when {@code after} holds more broken chart references of some kind than
     * {@code before}, or {@code null}. It lists every reference of each kind that grew.
     *
     * @param before the census of the schema before the write
     * @param after the census of the state the write leaves behind
     * @return an actionable refusal, or {@code null}
     */
    public static String error(Census before, Census after)
    {
        List<Problem> grown = new ArrayList<>();
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < after.problems.size(); i++)
        {
            String key = after.keys.get(i);
            if (after.count(key) > before.count(key))
            {
                grown.add(after.problems.get(i));
                kinds.add(key);
            }
        }
        if (grown.isEmpty()) return null;
        int added = 0;
        for (String kind : kinds)
        {
            added += after.count(kind) - before.count(kind);
        }
        return grown.get(0).resolver.render(grown, added);
    }

    /** Charts of a settings tree, keyed by their address relative to the settings. */
    static Map<String, DataCompositionChart> charts(DataCompositionSettings settings)
    {
        Map<String, DataCompositionChart> result = new LinkedHashMap<>();
        if (settings != null)
        {
            collectCharts(settings.getItems(), "items", false, result); //$NON-NLS-1$
        }
        return result;
    }

    private static boolean drawn(DataCompositionGroup group)
    {
        return group.isUse() && group.getGroupState() == DataCompositionSettingsItemState.ENABLED;
    }

    /** Charts of a settings tree that are drawn: neither they nor a structure group above is off. */
    static Map<String, DataCompositionChart> drawnCharts(DataCompositionSettings settings)
    {
        Map<String, DataCompositionChart> result = new LinkedHashMap<>();
        if (settings != null)
        {
            collectCharts(settings.getItems(), "items", true, result); //$NON-NLS-1$
        }
        return result;
    }

    /** Whether a user-field expression aggregates, outside its string literals. */
    static boolean aggregates(String expression)
    {
        if (expression == null) return false;
        int current = 0;
        while (current < expression.length())
        {
            char c = expression.charAt(current);
            if (c == '"')
            {
                current = DcsReadProjection.afterStringLiteral(expression, current);
                continue;
            }
            if (!Character.isLetterOrDigit(c) && c != '_')
            {
                current++;
                continue;
            }
            int start = current;
            while (current < expression.length() && (Character.isLetterOrDigit(expression.charAt(current))
                || expression.charAt(current) == '_' || expression.charAt(current) == '.'))
            {
                current++;
            }
            int next = current;
            while (next < expression.length() && Character.isWhitespace(expression.charAt(next)))
            {
                next++;
            }
            if (next < expression.length() && expression.charAt(next) == '('
                && AGGREGATES.contains(lower(expression.substring(start, current))))
            {
                return true;
            }
        }
        return false;
    }

    private static void collectCharts(List<StructureItem> items, String where, boolean drawnOnly,
        Map<String, DataCompositionChart> result)
    {
        for (int i = 0; i < items.size(); i++)
        {
            StructureItem item = items.get(i);
            String address = where + "/" + i; //$NON-NLS-1$
            if (item instanceof DataCompositionChart)
            {
                if (!drawnOnly || ((DataCompositionChart)item).isUse())
                {
                    result.put(address, (DataCompositionChart)item);
                }
            }
            else if (item instanceof DataCompositionGroup && (!drawnOnly || drawn((DataCompositionGroup)item)))
            {
                collectCharts(((DataCompositionGroup)item).getItems(), address + "/items", drawnOnly, //$NON-NLS-1$
                    result);
            }
        }
    }

    /**
     * The planned charts no unchanged original accounts for. Each original accounts for one equal
     * chart, at its own address first and then anywhere, so a moved chart is not a change and a
     * copy of one is.
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
        collectGroups(groups, role, where, true, result);
    }

    private static void collectGroups(List<DataCompositionChartGroup> groups, String role,
        String where, boolean used, List<Reference> result)
    {
        for (int i = 0; i < groups.size(); i++)
        {
            DataCompositionChartGroup group = groups.get(i);
            String address = where + "/" + i; //$NON-NLS-1$
            boolean groupUsed = used && group.isUse()
                && group.getGroupState() == DataCompositionSettingsItemState.ENABLED;
            if (group.getGroupFields() != null)
            {
                List<GroupItem> fields = group.getGroupFields().getItems();
                for (int j = 0; j < fields.size(); j++)
                {
                    if (fields.get(j) instanceof DataCompositionGroupField)
                    {
                        DataCompositionGroupField field = (DataCompositionGroupField)fields.get(j);
                        result.add(new Reference(role, path(field.getField()),
                            address + "/groupFields/items/" + j, groupUsed && field.isUse())); //$NON-NLS-1$
                    }
                }
            }
            collectGroups(group.getItems(), role, address + "/items", groupUsed, result); //$NON-NLS-1$
        }
    }

    private static void collectMeasures(List<SelectedItem> items, String where, List<Reference> result)
    {
        collectMeasures(items, where, true, result);
    }

    private static void collectMeasures(List<SelectedItem> items, String where, boolean used,
        List<Reference> result)
    {
        for (int i = 0; i < items.size(); i++)
        {
            SelectedItem item = items.get(i);
            String address = where + "/" + i; //$NON-NLS-1$
            if (item instanceof DataCompositionAutoSelectedField)
            {
                result.add(new Reference(ROLE_MEASURE, null, address,
                    used && ((DataCompositionAutoSelectedField)item).isUse()));
            }
            else if (item instanceof DataCompositionSelectedField)
            {
                DataCompositionSelectedField field = (DataCompositionSelectedField)item;
                result.add(new Reference(ROLE_MEASURE, path(field.getField()), address,
                    used && field.isUse()));
            }
            else if (item instanceof DataCompositionSelectedFieldGroup)
            {
                DataCompositionSelectedFieldGroup folder = (DataCompositionSelectedFieldGroup)item;
                collectMeasures(folder.getItems(), address + "/items", used && folder.isUse(), result); //$NON-NLS-1$
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

    private static Set<String> lowerSet(String... values)
    {
        Set<String> result = new HashSet<>();
        for (String value : values)
        {
            result.add(lower(value));
        }
        return result;
    }

    /** A field path as resolution compares it: case-insensitive, either user-field folder spelling. */
    private static String canonical(String path)
    {
        String userKey = userFieldKey(path);
        return userKey != null ? "\u0001" + lower(englishPercentTerm(userKey)) : lower(englishPercentTerm(path));
    }

    /** A percent field keyed by its English term, so a respelling in Russian is the same field. */
    private static String englishPercentTerm(String path)
    {
        int dot = path.lastIndexOf('.');
        if (dot <= 0) return path;
        String suffix = path.substring(dot + 1);
        for (String[] term : PERCENT_TERMS)
        {
            for (String spelling : term)
            {
                if (spelling.equalsIgnoreCase(suffix)) return path.substring(0, dot + 1) + term[0];
            }
        }
        return path;
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

    /** One unresolved reference, why, and the resolver that judged it. */
    private static final class Problem
    {
        final Reference reference;
        final String reason;
        final String chartAddress;
        final Resolver resolver;
        /** Role and lower-case field; the two field-less measure problems get their own marker. */
        final String key;

        Problem(Reference reference, String reason, String chartAddress, Resolver resolver, String marker)
        {
            this.reference = reference;
            this.reason = reason;
            this.chartAddress = chartAddress;
            this.resolver = resolver;
            // A NUL-led marker cannot collide with a field path such as a literal '#none'.
            // A literal schema path keeps its own key; only spellings the resolver aliases share one.
            this.key = reference.role + '\n' + (reference.field == null ? '\u0000' + marker
                : resolver.isSchemaPath(reference.field) ? lower(reference.field) : canonical(reference.field));
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

    /**
     * The fields and resources of a schema, and the user fields of one settings tree. A user
     * field maps to {@code TRUE} when it is a resource, {@code FALSE} when it is not, and
     * {@code null} when the model cannot tell.
     */
    private static final class Resolver
    {
        private final Map<String, FieldInfo> fields;
        private final Map<String, String> resources;
        private final boolean autoFill;
        private final Map<String, String> userFields = new LinkedHashMap<>();
        private final Map<String, Boolean> userResources = new HashMap<>();

        Resolver(DataCompositionSchema schema)
        {
            fields = new LinkedHashMap<>();
            resources = new LinkedHashMap<>();
            boolean anyAutoFill = false;
            for (DataSet dataSet : schema.getDataSets())
            {
                anyAutoFill |= addDataSet(dataSet);
            }
            autoFill = anyAutoFill;
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
        }

        private Resolver(Resolver schemaLevel)
        {
            fields = schemaLevel.fields;
            resources = schemaLevel.resources;
            autoFill = schemaLevel.autoFill;
        }

        /** Whether {@code path} names a schema field or resource literally. */
        boolean isSchemaPath(String path)
        {
            String key = lower(path);
            return fields.containsKey(key) || resources.containsKey(key);
        }

        /** This schema's resolver extended with the user fields of one settings tree. */
        Resolver withUserFields(DataCompositionSettings settings)
        {
            Resolver result = new Resolver(this);
            if (settings == null || settings.getUserFields() == null) return result;
            for (UserField field : settings.getUserFields().getItems())
            {
                String dataPath = field.getDataPath();
                // A switched-off user field is not available to a chart.
                if (!field.isUse() || dataPath == null || dataPath.isEmpty()) continue;
                String key = userFieldKey(dataPath);
                key = key == null ? lower(dataPath) : key;
                result.userFields.putIfAbsent(key, dataPath);
                Boolean resource = isResource(field);
                result.userResources.put(key, result.userResources.containsKey(key)
                    ? either(result.userResources.get(key), resource) : resource);
            }
            return result;
        }

        /** A duplicated user field is a resource if either spelling is; unknown beats "not". */
        private static Boolean either(Boolean first, Boolean second)
        {
            if (Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second)) return Boolean.TRUE;
            return first == null || second == null ? null : Boolean.FALSE;
        }

        /** Whether the data set is or holds an auto-fill query set; lists its fields either way. */
        private boolean addDataSet(DataSet dataSet)
        {
            boolean autoFillSet = dataSet instanceof DataCompositionSchemaDataSetQuery
                && ((DataCompositionSchemaDataSetQuery)dataSet).isAutoFillAvailableFields();
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
                    autoFillSet |= addDataSet(item);
                }
            }
            return autoFillSet;
        }

        private static boolean restrictsGroup(DataCompositionSchemaFieldUseRestriction restriction)
        {
            return restriction != null && restriction.isGroup();
        }

        /** As EDT decides it: a total expression, or an aggregate in the detail expression. */
        private static Boolean isResource(UserField field)
        {
            if (!(field instanceof DataCompositionUserFieldExpression)) return null;
            DataCompositionUserFieldExpression expression = (DataCompositionUserFieldExpression)field;
            String total = expression.getTotalExpression();
            return Boolean.valueOf(total != null && !total.trim().isEmpty()
                || aggregates(expression.getDetailExpression()));
        }

        private boolean hasResources()
        {
            return !resources.isEmpty() || userResources.containsValue(Boolean.TRUE)
                || userResources.containsValue(null);
        }

        List<Problem> problems(DataCompositionChart chart, String chartAddress)
        {
            List<Problem> result = new ArrayList<>();
            boolean measured = false;
            for (Reference reference : references(chart, chartAddress))
            {
                if (!reference.use)
                {
                    // A switched-off item is not drawn: it neither breaks nor measures the chart.
                    continue;
                }
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
                    result.add(new Problem(reference, reason, chartAddress, this, "#auto")); //$NON-NLS-1$
                }
            }
            if (!measured)
            {
                result.add(new Problem(new Reference(ROLE_MEASURE, null, chartAddress + "/selection"), //$NON-NLS-1$
                    "the chart has no measure, so it has nothing to draw", chartAddress, this, "#none")); //$NON-NLS-1$ //$NON-NLS-2$
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
                return !Boolean.FALSE.equals(userResources.get(userKey)) ? null
                    : "user field '" + userFields.get(userKey) + "' has no total expression and " //$NON-NLS-1$ //$NON-NLS-2$
                        + "no aggregate in its expression, so it is not a resource"; //$NON-NLS-1$
            }
            int dot = path.lastIndexOf('.');
            if (dot > 0 && isPercentTerm(path.substring(dot + 1)))
            {
                String base = path.substring(0, dot);
                if (resources.containsKey(lower(base))) return null;
                String baseUserKey = userFieldKey(base);
                if (baseUserKey != null && userFields.containsKey(baseUserKey))
                {
                    return "percent fields belong to totalFields resources; user field '" //$NON-NLS-1$
                        + userFields.get(baseUserKey) + "' has none"; //$NON-NLS-1$
                }
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
            if (userKey != null) return userGroupReason(userKey);
            // An auto-fill set may derive a data-set field under a resource's name, and such a field groups.
            if (resources.containsKey(key) && !autoFill)
            {
                return "'" + resources.get(key) + "' is a resource; a resource cannot be a point " //$NON-NLS-1$ //$NON-NLS-2$
                    + "or series"; //$NON-NLS-1$
            }
            for (int dot = path.lastIndexOf('.'); dot > 0; dot = path.lastIndexOf('.', dot - 1))
            {
                String head = path.substring(0, dot);
                if (resources.containsKey(lower(head)))
                {
                    return "'" + resources.get(lower(head)) + "' is a resource, and a resource's " //$NON-NLS-1$ //$NON-NLS-2$
                        + "attributes cannot be grouped"; //$NON-NLS-1$
                }
                FieldInfo parent = fields.get(lower(head));
                if (parent != null)
                {
                    return parent.childrenGroupable ? null
                        : "the attributes of '" + parent.dataPath + "' are not available for " //$NON-NLS-1$ //$NON-NLS-2$
                            + "grouping (its attribute use restriction forbids it)"; //$NON-NLS-1$
                }
            }
            return autoFill ? null
                : "no schema field, calculated field or user field has this data path"; //$NON-NLS-1$
        }

        /** A user field groups as itself only: EDT lists it as a leaf, with no attributes. */
        private String userGroupReason(String userKey)
        {
            if (userFields.containsKey(userKey)) return null;
            for (int dot = userKey.lastIndexOf('.'); dot > 0; dot = userKey.lastIndexOf('.', dot - 1))
            {
                String head = userKey.substring(0, dot);
                if (userFields.containsKey(head))
                {
                    return "user field '" + userFields.get(head) + "' has no attributes to group by; " //$NON-NLS-1$ //$NON-NLS-2$
                        + "group by the user field itself or by an attribute of a schema field"; //$NON-NLS-1$
                }
            }
            return "no user field of these settings has this data path"; //$NON-NLS-1$
        }

        String render(List<Problem> problems, int added)
        {
            Set<String> charts = new LinkedHashSet<>();
            for (int i = 0; i < problems.size() && i < MAX_PROBLEMS; i++)
            {
                charts.add(problems.get(i).chartAddress);
            }
            StringBuilder message = new StringBuilder(charts.size() == 1 ? "Chart at '" : "Charts at '") //$NON-NLS-1$ //$NON-NLS-2$
                .append(String.join("', '", charts)) //$NON-NLS-1$
                .append(charts.size() == 1 ? "' refers" : "' refer") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" to data this schema does not have, so it would render empty. "); //$NON-NLS-1$
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
            if (added < problems.size())
            {
                message.append(" The write adds ").append(added).append(" of these ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(problems.size()).append("; the others were already there."); //$NON-NLS-1$
            }
            if (measureProblem)
            {
                List<String> choices = new ArrayList<>(resources.values());
                for (Map.Entry<String, String> entry : userFields.entrySet())
                {
                    if (Boolean.TRUE.equals(userResources.get(entry.getKey()))) choices.add(entry.getValue());
                }
                message.append(" A measure must be a resource: ").append(choices(choices)) //$NON-NLS-1$
                    .append(", a totalFields resource's percent field such as '<resource>.") //$NON-NLS-1$
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
                    .append("user field, or by an attribute of a schema or calculated field ") //$NON-NLS-1$
                    .append("('<field>.<attribute>'): ").append(choices(choices)).append('.'); //$NON-NLS-1$
            }
            return message.append(" Fix or remove the reference, or keep the schema data it needs.") //$NON-NLS-1$
                .append(" Nothing was written.").toString(); //$NON-NLS-1$
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
