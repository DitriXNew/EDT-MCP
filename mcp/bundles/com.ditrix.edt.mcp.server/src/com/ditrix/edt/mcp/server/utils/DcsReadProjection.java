/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/**
 * Pure Markdown projection and pointer-resolution layer shared by report/template schemas and form
 * dynamic lists. In particular, both roots reach {@link #renderSettingsOutline} for their settings;
 * the tool layer only resolves a transaction-local root and adds the hash header.
 */
public final class DcsReadProjection
{
    private static final String TYPE_SCHEMA = "schema"; //$NON-NLS-1$
    private static final String TYPE_DYNAMIC_LIST = "dynamicList"; //$NON-NLS-1$
    private static final String FEATURE_VARIANTS = "variants"; //$NON-NLS-1$
    private static final String MODEL_FEATURE_VARIANTS = "settingsVariants"; //$NON-NLS-1$
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    private static final String FEATURE_QUERY = "query"; //$NON-NLS-1$
    private static final String FEATURE_QUERY_TEXT = "queryText"; //$NON-NLS-1$
    private static final String CHART_CLASS = "DataCompositionChart"; //$NON-NLS-1$

    private static final Set<String> NATURAL_NAME_COLLECTIONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("dataSources", "dataSets", "parameters", FEATURE_VARIANTS))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> DATA_PATH_COLLECTIONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("fields", "calculatedFields", "totalFields"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private DcsReadProjection()
    {
        // utility class
    }

    /**
     * Projects a root summary, a paginated collection, or one fully resolved pointer node.
     *
     * @param rootFqn normalized DCS root FQN
     * @param kind resolved root kind
     * @param root transaction-local root object; a schema may be {@code null} when content is absent
     * @param requestedAddress parsed caller address
     * @param type requested contract type
     * @param language resolved language code
     * @param limit already clamped page size
     * @param offset non-negative page offset
     * @return Markdown or an actionable failure
     */
    public static Result render(String rootFqn, TargetKind kind, EObject root,
        DcsAddress requestedAddress, String type, String language, int limit, int offset)
    {
        String canonicalRoot = DcsAddress.render(rootFqn, Collections.<String> emptyList());
        if (requestedAddress == null)
        {
            return Result.failure("DCS address is missing. Pass an existing DCS root FQN."); //$NON-NLS-1$
        }
        if (!requestedAddress.hasPointer())
        {
            if (TYPE_SCHEMA.equals(type))
            {
                if (kind == TargetKind.DYNAMIC_LIST)
                {
                    return typeMismatch(type, TYPE_DYNAMIC_LIST, canonicalRoot);
                }
                return Result.success(renderSchemaSummary(canonicalRoot,
                    root instanceof DataCompositionSchema ? (DataCompositionSchema)root : null, language));
            }
            if (TYPE_DYNAMIC_LIST.equals(type))
            {
                if (kind != TargetKind.DYNAMIC_LIST)
                {
                    return typeMismatch(type, TYPE_SCHEMA, canonicalRoot);
                }
                return Result.success(renderDynamicListSummary(canonicalRoot, root, language));
            }
            return renderRootCollection(canonicalRoot, kind, root, type, language, limit, offset);
        }

        if (root == null)
        {
            return Result.failure("Pointer '" + requestedAddress + "' cannot be resolved because DCS root '" //$NON-NLS-1$ //$NON-NLS-2$
                + rootFqn + "' has no schema content. Create the schema first, then re-run dcs action='get'."); //$NON-NLS-1$
        }
        NodeResolution resolution = resolvePointer(rootFqn, root, requestedAddress.segments());
        if (!resolution.isSuccess())
        {
            return Result.failure(resolution.error);
        }
        NodeRef node = resolution.node;
        String actualType = typeOf(node);
        if (!type.equals(actualType))
        {
            return typeMismatch(type, actualType, node.address);
        }
        if (node.value instanceof List<?>)
        {
            return Result.success(renderCollectionPage(node.address, type, node.items, language,
                limit, offset));
        }
        return Result.success(renderFullNode(node, language));
    }

    /**
     * Renders the complete typed settings containment subtree. Report default/variant settings and
     * dynamic-list {@code listSettings} both call this exact method.
     *
     * @param address canonical address of the settings object
     * @param settings settings object, possibly {@code null}
     * @param language presentation language code
     * @return nested Markdown outline with an address on every model node
     */
    public static String renderSettingsOutline(String address, DataCompositionSettings settings,
        String language)
    {
        if (settings == null)
        {
            return "**Address:** `" + address + "`\n\n_(settings are not present)_\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        StringBuilder result = new StringBuilder();
        appendObjectOutline(result, settings, address, 0, language);
        return result.toString();
    }

    private static Result renderRootCollection(String rootFqn, TargetKind kind, EObject root,
        String type, String language, int limit, int offset)
    {
        CollectionRef collection = rootCollection(rootFqn, kind, root, type);
        if (collection.error != null)
        {
            return Result.failure(collection.error);
        }
        return Result.success(renderCollectionPage(collection.address, type, collection.items,
            language, limit, offset));
    }

    private static String renderSchemaSummary(String rootFqn, DataCompositionSchema schema,
        String language)
    {
        StringBuilder result = summaryHeader("Data Composition Schema", rootFqn); //$NON-NLS-1$
        result.append("## Counts\n\n"); //$NON-NLS-1$
        result.append(MarkdownUtils.tableHeader("Section", "Count", "Address")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Data sources", size(schema, "dataSources"), child(rootFqn, "dataSources")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Data sets", size(schema, "dataSets"), child(rootFqn, "dataSets")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Calculated fields", size(schema, "calculatedFields"), //$NON-NLS-1$ //$NON-NLS-2$
            child(rootFqn, "calculatedFields")); //$NON-NLS-1$
        appendCount(result, "Total fields", size(schema, "totalFields"), child(rootFqn, "totalFields")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Parameters", size(schema, "parameters"), child(rootFqn, "parameters")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionSettings settings = schema == null ? null : schema.getDefaultSettings();
        appendCount(result, "Default settings", settings == null ? 0 : 1, child(rootFqn, "defaultSettings")); //$NON-NLS-1$ //$NON-NLS-2$
        appendSettingsCounts(result, settings, child(rootFqn, "defaultSettings")); //$NON-NLS-1$
        appendCount(result, "Variants", size(schema, MODEL_FEATURE_VARIANTS), child(rootFqn, FEATURE_VARIANTS)); //$NON-NLS-1$
        result.append('\n');

        if (schema != null)
        {
            appendNameTable(result, "Data sources", directItems(rootFqn, schema, "dataSources"), language); //$NON-NLS-1$ //$NON-NLS-2$
            appendNameTable(result, "Data sets", directItems(rootFqn, schema, "dataSets"), language); //$NON-NLS-1$ //$NON-NLS-2$
            appendNameTable(result, "Calculated fields", //$NON-NLS-1$
                directItems(rootFqn, schema, "calculatedFields"), language); //$NON-NLS-1$
            appendNameTable(result, "Total fields", directItems(rootFqn, schema, "totalFields"), language); //$NON-NLS-1$ //$NON-NLS-2$
            appendNameTable(result, "Parameters", directItems(rootFqn, schema, "parameters"), language); //$NON-NLS-1$ //$NON-NLS-2$
            appendNameTable(result, "Variants", directItems(rootFqn, schema, MODEL_FEATURE_VARIANTS), language); //$NON-NLS-1$
        }
        result.append("_Query text and recursive settings are omitted from this summary. Drill down with an address._\n"); //$NON-NLS-1$
        return result.toString();
    }

    private static String renderDynamicListSummary(String rootFqn, EObject root, String language)
    {
        StringBuilder result = summaryHeader("Dynamic List", rootFqn); //$NON-NLS-1$
        result.append("## Properties\n\n"); //$NON-NLS-1$
        result.append(MarkdownUtils.tableHeader("Property", "Value")); //$NON-NLS-1$ //$NON-NLS-2$
        if (root != null)
        {
            for (EAttribute attribute : root.eClass().getEAllAttributes())
            {
                Object value = root.eGet(attribute);
                if (FEATURE_QUERY_TEXT.equals(attribute.getName()))
                {
                    String query = value == null ? "" : value.toString(); //$NON-NLS-1$
                    value = query.length() + " characters (omitted from summary)"; //$NON-NLS-1$
                }
                result.append(MarkdownUtils.tableRow(attribute.getName(), displayValue(value, language)));
            }
            EStructuralFeature mainTable = root.eClass().getEStructuralFeature("mainTable"); //$NON-NLS-1$
            if (mainTable != null)
            {
                result.append(MarkdownUtils.tableRow("mainTable", displayValue(root.eGet(mainTable), language))); //$NON-NLS-1$
            }
        }
        result.append("\n## Counts\n\n"); //$NON-NLS-1$
        result.append(MarkdownUtils.tableHeader("Section", "Count", "Address")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Fields", size(root, "fields"), child(rootFqn, "fields")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Calculated fields", size(root, "calculatedFields"), //$NON-NLS-1$ //$NON-NLS-2$
            child(rootFqn, "calculatedFields")); //$NON-NLS-1$
        appendCount(result, "Parameters", size(root, "parameters"), child(rootFqn, "parameters")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionSettings settings = asSettings(featureValue(root, "listSettings")); //$NON-NLS-1$
        appendCount(result, "List settings", settings == null ? 0 : 1, child(rootFqn, "listSettings")); //$NON-NLS-1$ //$NON-NLS-2$
        appendSettingsCounts(result, settings, child(rootFqn, "listSettings")); //$NON-NLS-1$
        result.append('\n');
        appendNameTable(result, "Fields", directItems(rootFqn, root, "fields"), language); //$NON-NLS-1$ //$NON-NLS-2$
        appendNameTable(result, "Calculated fields", directItems(rootFqn, root, "calculatedFields"), language); //$NON-NLS-1$ //$NON-NLS-2$
        appendNameTable(result, "Parameters", directItems(rootFqn, root, "parameters"), language); //$NON-NLS-1$ //$NON-NLS-2$
        result.append("_Query text and recursive list settings are omitted from this summary. Drill down with an address._\n"); //$NON-NLS-1$
        return result.toString();
    }

    private static StringBuilder summaryHeader(String label, String rootFqn)
    {
        return new StringBuilder("# ").append(label).append(": ").append(rootFqn).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Address:** `").append(rootFqn).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendSettingsCounts(StringBuilder result, DataCompositionSettings settings,
        String settingsAddress)
    {
        if (settings == null)
        {
            return;
        }
        appendCount(result, "Structure items", size(settings, FEATURE_ITEMS), //$NON-NLS-1$
            child(settingsAddress, FEATURE_ITEMS));
        appendHolderCount(result, settings, settingsAddress, "Selection", "selection"); //$NON-NLS-1$ //$NON-NLS-2$
        appendHolderCount(result, settings, settingsAddress, "Filter", "filter"); //$NON-NLS-1$ //$NON-NLS-2$
        appendHolderCount(result, settings, settingsAddress, "Data parameters", "dataParameters"); //$NON-NLS-1$ //$NON-NLS-2$
        appendHolderCount(result, settings, settingsAddress, "Order", "order"); //$NON-NLS-1$ //$NON-NLS-2$
        appendHolderCount(result, settings, settingsAddress, "Conditional appearance", //$NON-NLS-1$
            "conditionalAppearance"); //$NON-NLS-1$
        appendHolderCount(result, settings, settingsAddress, "Output parameters", "outputParameters"); //$NON-NLS-1$ //$NON-NLS-2$
        appendHolderCount(result, settings, settingsAddress, "User fields", "userFields"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendHolderCount(StringBuilder result, EObject settings,
        String settingsAddress, String label, String featureName)
    {
        EObject holder = asEObject(featureValue(settings, featureName));
        int count = holder == null ? 0 : size(holder, FEATURE_ITEMS);
        String holderAddress = child(settingsAddress, featureName);
        appendCount(result, label, count, child(holderAddress, FEATURE_ITEMS));
    }

    private static void appendCount(StringBuilder result, String label, int count, String address)
    {
        result.append(MarkdownUtils.tableRow(label, Integer.toString(count), address));
    }

    private static void appendNameTable(StringBuilder result, String title, List<NodeRef> items,
        String language)
    {
        if (items.isEmpty())
        {
            return;
        }
        result.append("## ").append(title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        result.append(MarkdownUtils.tableHeader("Name", "Kind", "Address")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (NodeRef item : items)
        {
            result.append(MarkdownUtils.tableRow(itemName(item.value, language), itemKind(item.value),
                item.address));
        }
        result.append('\n');
    }

    private static CollectionRef rootCollection(String rootFqn, TargetKind kind, EObject root,
        String type)
    {
        if (root == null)
        {
            return CollectionRef.empty(rootFqn, type);
        }
        switch (type)
        {
            case "dataSource": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? unsupportedCollection(rootFqn, type, kind)
                    : directCollection(rootFqn, root, "dataSources"); //$NON-NLS-1$
            case "dataSet": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? unsupportedCollection(rootFqn, type, kind)
                    : directCollection(rootFqn, root, "dataSets"); //$NON-NLS-1$
            case "field": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? directCollection(rootFqn, root, "fields") //$NON-NLS-1$
                    : schemaFields(rootFqn, root);
            case "parameter": //$NON-NLS-1$
                return directCollection(rootFqn, root, "parameters"); //$NON-NLS-1$
            case "calculatedField": //$NON-NLS-1$
                return directCollection(rootFqn, root, "calculatedFields"); //$NON-NLS-1$
            case "totalField": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? unsupportedCollection(rootFqn, type, kind)
                    : directCollection(rootFqn, root, "totalFields"); //$NON-NLS-1$
            case "variant": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? unsupportedCollection(rootFqn, type, kind)
                    : directCollection(rootFqn, root, MODEL_FEATURE_VARIANTS);
            default:
                return settingsCollection(rootFqn, kind, root, type);
        }
    }

    private static CollectionRef schemaFields(String rootFqn, EObject root)
    {
        List<NodeRef> result = new ArrayList<>();
        Object value = featureValue(root, "dataSets"); //$NON-NLS-1$
        if (value instanceof List<?>)
        {
            List<?> dataSets = (List<?>)value;
            for (int i = 0; i < dataSets.size(); i++)
            {
                Object dataSet = dataSets.get(i);
                if (!(dataSet instanceof EObject))
                {
                    continue;
                }
                String dataSetSelector = selector("dataSets", null, (EObject)dataSet, i); //$NON-NLS-1$
                String fieldsAddress = child(child(child(rootFqn, "dataSets"), dataSetSelector), "fields"); //$NON-NLS-1$ //$NON-NLS-2$
                result.addAll(directItemsAt(fieldsAddress, (EObject)dataSet, "fields")); //$NON-NLS-1$
            }
        }
        return CollectionRef.success(child(rootFqn, "dataSets"), result); //$NON-NLS-1$
    }

    private static CollectionRef settingsCollection(String rootFqn, TargetKind kind, EObject root,
        String type)
    {
        String settingsFeature = kind == TargetKind.DYNAMIC_LIST ? "listSettings" : "defaultSettings"; //$NON-NLS-1$ //$NON-NLS-2$
        EObject settings = asEObject(featureValue(root, settingsFeature));
        String settingsAddress = child(rootFqn, settingsFeature);
        if ("grouping".equals(type) || "table".equals(type)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String className = "grouping".equals(type) ? "DataCompositionGroup" : "DataCompositionTable"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List<NodeRef> matches = new ArrayList<>();
            collectByClass(settings, settingsAddress, className, matches);
            return CollectionRef.success(child(settingsAddress, FEATURE_ITEMS), matches);
        }
        String feature = settingsFeatureForType(type);
        if (feature == null)
        {
            return unsupportedCollection(rootFqn, type, kind);
        }
        if (settings == null)
        {
            return CollectionRef.success(child(child(settingsAddress, feature), FEATURE_ITEMS),
                Collections.<NodeRef> emptyList());
        }
        if ("additionalProperties".equals(feature)) //$NON-NLS-1$
        {
            EObject value = asEObject(featureValue(settings, feature));
            List<NodeRef> one = value == null ? Collections.<NodeRef> emptyList()
                : Collections.singletonList(new NodeRef(value, child(settingsAddress, feature),
                    feature, Collections.<NodeRef> emptyList()));
            return CollectionRef.success(child(settingsAddress, feature), one);
        }
        EObject holder = asEObject(featureValue(settings, feature));
        String holderAddress = child(settingsAddress, feature);
        return CollectionRef.success(child(holderAddress, FEATURE_ITEMS),
            directItemsAt(holderAddress, holder, FEATURE_ITEMS));
    }

    private static String settingsFeatureForType(String type)
    {
        switch (type)
        {
            case "selection": //$NON-NLS-1$
                return "selection"; //$NON-NLS-1$
            case "filter": //$NON-NLS-1$
                return "filter"; //$NON-NLS-1$
            case "dataParameter": //$NON-NLS-1$
                return "dataParameters"; //$NON-NLS-1$
            case "order": //$NON-NLS-1$
                return "order"; //$NON-NLS-1$
            case "conditionalAppearance": //$NON-NLS-1$
                return "conditionalAppearance"; //$NON-NLS-1$
            case "userField": //$NON-NLS-1$
                return "userFields"; //$NON-NLS-1$
            case "outputParameter": //$NON-NLS-1$
                return "outputParameters"; //$NON-NLS-1$
            case "userSettings": //$NON-NLS-1$
                return "additionalProperties"; //$NON-NLS-1$
            default:
                return null;
        }
    }

    private static CollectionRef directCollection(String rootFqn, EObject owner, String featureName)
    {
        String canonical = canonicalFeature(featureName);
        String address = child(rootFqn, canonical);
        return CollectionRef.success(address, directItemsAt(rootFqn, owner, featureName));
    }

    private static List<NodeRef> directItems(String rootFqn, EObject owner, String featureName)
    {
        return directItemsAt(rootFqn, owner, featureName);
    }

    private static List<NodeRef> directItemsAt(String ownerAddress, EObject owner, String featureName)
    {
        if (owner == null)
        {
            return Collections.emptyList();
        }
        Object value = featureValue(owner, featureName);
        if (!(value instanceof List<?>))
        {
            return Collections.emptyList();
        }
        List<NodeRef> result = new ArrayList<>();
        List<?> list = (List<?>)value;
        String canonical = canonicalFeature(featureName);
        String collectionAddress = child(ownerAddress, canonical);
        for (int i = 0; i < list.size(); i++)
        {
            Object item = list.get(i);
            if (item instanceof EObject)
            {
                String selector = selector(canonical, owner, (EObject)item, i);
                result.add(new NodeRef(item, child(collectionAddress, selector), canonical,
                    Collections.<NodeRef> emptyList()));
            }
        }
        return result;
    }

    private static CollectionRef unsupportedCollection(String rootFqn, String type, TargetKind kind)
    {
        String rootType = kind == TargetKind.DYNAMIC_LIST ? TYPE_DYNAMIC_LIST : TYPE_SCHEMA;
        return CollectionRef.failure("Type '" + type + "' is not a collection on " + rootType //$NON-NLS-1$ //$NON-NLS-2$
            + " root '" + rootFqn + "'. Use a compatible type or pass an fqn pointer to a specific node; " //$NON-NLS-1$ //$NON-NLS-2$
            + "call get_tool_guide('dcs') for the address/type map."); //$NON-NLS-1$
    }

    private static String renderCollectionPage(String address, String type, List<NodeRef> all,
        String language, int limit, int offset)
    {
        int total = all.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<NodeRef> page = all.subList(from, to);
        StringBuilder result = new StringBuilder("# DCS collection: ").append(type).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Address:** `").append(address).append("`\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Items:** ").append(total)
            .append(Pagination.truncationNotice(page.size(), total)).append("\n\n") //$NON-NLS-1$
            .append("**Offset:** ").append(offset).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Next offset:** ").append(to < total ? Integer.toString(to) : "none").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (page.isEmpty())
        {
            result.append("_(no items on this page)_\n"); //$NON-NLS-1$
            return result.toString();
        }
        result.append(MarkdownUtils.tableHeader("Name", "Kind", "Address", "Note")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (NodeRef item : page)
        {
            String note = isChart(item.value)
                ? "Read-only existing chart; chart authoring is unsupported." : ""; //$NON-NLS-1$ //$NON-NLS-2$
            result.append(MarkdownUtils.tableRow(itemName(item.value, language), itemKind(item.value),
                item.address, note));
        }
        return result.toString();
    }

    private static NodeResolution resolvePointer(String rootFqn, EObject root, List<String> segments)
    {
        Object current = root;
        String currentAddress = rootFqn;
        String collectionName = null;
        for (int i = 0; i < segments.size(); i++)
        {
            String segment = segments.get(i);
            if (!(current instanceof EObject))
            {
                return failedSegment(segment, currentAddress, Collections.<String> emptyList());
            }
            EObject object = (EObject)current;
            String modelName = modelFeature(segment);
            EStructuralFeature feature = object.eClass().getEStructuralFeature(modelName);
            if (feature == null)
            {
                return failedSegment(segment, currentAddress, navigationKeys(object));
            }
            Object value = object.eGet(feature);
            currentAddress = child(currentAddress, canonicalFeature(feature.getName()));
            collectionName = canonicalFeature(feature.getName());
            if (!feature.isMany())
            {
                if (value == null)
                {
                    return failedSegment(segment, parentAddress(currentAddress), navigationKeys(object));
                }
                current = value;
                continue;
            }
            if (!(value instanceof List<?>))
            {
                return failedSegment(segment, parentAddress(currentAddress), Collections.<String> emptyList());
            }
            List<?> list = (List<?>)value;
            if (i + 1 >= segments.size())
            {
                return NodeResolution.success(new NodeRef(list, currentAddress, collectionName,
                    nodeRefs(currentAddress, object, collectionName, list)));
            }
            String selector = segments.get(++i);
            int selected = find(list, collectionName, object, selector);
            if (selected < 0)
            {
                return failedSegment(selector, currentAddress, selectors(list, collectionName, object));
            }
            current = list.get(selected);
            currentAddress = child(currentAddress, selector(collectionName, object,
                (EObject)current, selected));
        }
        return NodeResolution.success(new NodeRef(current, currentAddress, collectionName,
            Collections.<NodeRef> emptyList()));
    }

    private static NodeResolution failedSegment(String segment, String address, List<String> existing)
    {
        String available = existing.isEmpty() ? "none" : String.join(", ", existing); //$NON-NLS-1$ //$NON-NLS-2$
        return NodeResolution.failure("Pointer segment '" + segment + "' could not be resolved at '" //$NON-NLS-1$ //$NON-NLS-2$
            + address + "'. Existing keys/indices at that level: " + available //$NON-NLS-1$
            + ". Copy one of those into the address, or get its parent collection first."); //$NON-NLS-1$
    }

    private static int find(List<?> list, String collection, EObject owner, String requested)
    {
        for (int i = 0; i < list.size(); i++)
        {
            Object value = list.get(i);
            if (value instanceof EObject
                && requested.equals(selector(collection, owner, (EObject)value, i)))
            {
                return i;
            }
        }
        return -1;
    }

    private static List<String> selectors(List<?> list, String collection, EObject owner)
    {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
        {
            Object item = list.get(i);
            if (item instanceof EObject)
            {
                result.add(selector(collection, owner, (EObject)item, i));
            }
        }
        return result;
    }

    private static List<String> navigationKeys(EObject object)
    {
        List<String> result = new ArrayList<>();
        for (EReference reference : object.eClass().getEAllReferences())
        {
            if (reference.isContainment())
            {
                result.add(canonicalFeature(reference.getName()));
            }
        }
        return result;
    }

    private static List<NodeRef> nodeRefs(String collectionAddress, EObject owner,
        String collection, List<?> list)
    {
        List<NodeRef> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
        {
            Object item = list.get(i);
            if (item instanceof EObject)
            {
                result.add(new NodeRef(item,
                    child(collectionAddress, selector(collection, owner, (EObject)item, i)),
                    collection, Collections.<NodeRef> emptyList()));
            }
        }
        return result;
    }

    private static String renderFullNode(NodeRef node, String language)
    {
        if (!(node.value instanceof EObject))
        {
            return "# DCS value\n\n**Address:** `" + node.address + "`\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + MarkdownUtils.escapeMarkdown(displayValue(node.value, language)) + '\n';
        }
        EObject object = (EObject)node.value;
        if (isChart(object))
        {
            return "# Existing DCS chart\n\n**Address:** `" + node.address //$NON-NLS-1$
                + "`\n\nThis chart is visible read-only; chart authoring is unsupported.\n"; //$NON-NLS-1$
        }
        StringBuilder result = new StringBuilder("# DCS node: ").append(itemKind(object)).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Address:** `").append(node.address).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (object instanceof DataCompositionSettings)
        {
            result.append(renderSettingsOutline(node.address, (DataCompositionSettings)object, language));
            return result.toString();
        }
        appendScalarTable(result, object, language, FEATURE_QUERY, FEATURE_QUERY_TEXT);
        appendQuery(result, object);
        if (object instanceof DataSet)
        {
            appendFieldsTable(result, (DataSet)object, node.address, language);
        }
        else if ("SettingsVariant".equals(object.eClass().getName())) //$NON-NLS-1$
        {
            DataCompositionSettings settings = asSettings(featureValue(object, "settings")); //$NON-NLS-1$
            if (settings != null)
            {
                result.append("## Settings\n\n") //$NON-NLS-1$
                    .append(renderSettingsOutline(child(node.address, "settings"), settings, language)); //$NON-NLS-1$
            }
        }
        else
        {
            appendContainedOutline(result, object, node.address, language);
        }
        return result.toString();
    }

    private static void appendScalarTable(StringBuilder result, EObject object, String language,
        String... excluded)
    {
        Set<String> skip = new LinkedHashSet<>(Arrays.asList(excluded));
        List<String[]> rows = new ArrayList<>();
        for (EAttribute attribute : object.eClass().getEAllAttributes())
        {
            if (!skip.contains(attribute.getName()))
            {
                rows.add(new String[] {attribute.getName(), displayValue(object.eGet(attribute), language)});
            }
        }
        for (EReference reference : object.eClass().getEAllReferences())
        {
            if (!reference.isContainment() && !skip.contains(reference.getName()))
            {
                rows.add(new String[] {reference.getName(), displayValue(object.eGet(reference), language)});
            }
        }
        if (rows.isEmpty())
        {
            return;
        }
        result.append("## Properties\n\n").append(MarkdownUtils.tableHeader("Property", "Value")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] row : rows)
        {
            result.append(MarkdownUtils.tableRow(row));
        }
        result.append('\n');
    }

    private static void appendQuery(StringBuilder result, EObject object)
    {
        String featureName = object instanceof DataCompositionSchemaDataSetQuery
            ? FEATURE_QUERY : FEATURE_QUERY_TEXT;
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null)
        {
            return;
        }
        Object raw = object.eGet(feature);
        String query = raw == null ? "" : raw.toString(); //$NON-NLS-1$
        result.append("## Query text\n\n"); //$NON-NLS-1$
        appendFenced(result, query);
    }

    private static void appendFenced(StringBuilder result, String text)
    {
        String fence = "```"; //$NON-NLS-1$
        while (text.contains(fence))
        {
            fence += '`';
        }
        result.append(fence).append("sql\n").append(text); //$NON-NLS-1$
        if (!text.endsWith("\n")) //$NON-NLS-1$
        {
            result.append('\n');
        }
        result.append(fence).append("\n\n"); //$NON-NLS-1$
    }

    private static void appendFieldsTable(StringBuilder result, DataSet dataSet, String address,
        String language)
    {
        EList<DataSetField> fields = dataSet.getFields();
        result.append("## Fields\n\n"); //$NON-NLS-1$
        if (fields.isEmpty())
        {
            result.append("_(none)_\n"); //$NON-NLS-1$
            return;
        }
        result.append(MarkdownUtils.tableHeader("Data path", "Field", "Title", "Kind", "Address")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String collection = child(address, "fields"); //$NON-NLS-1$
        for (int i = 0; i < fields.size(); i++)
        {
            DataSetField field = fields.get(i);
            String dataPath = stringFeature(field, "dataPath"); //$NON-NLS-1$
            String selector = dataPath.isEmpty() ? Integer.toString(i) : dataPath;
            result.append(MarkdownUtils.tableRow(dataPath, stringFeature(field, "field"), //$NON-NLS-1$
                presentationFeature(field, "title", language), itemKind(field), //$NON-NLS-1$
                child(collection, selector)));
        }
        result.append('\n');
    }

    private static void appendContainedOutline(StringBuilder result, EObject object, String address,
        String language)
    {
        boolean hasContainment = false;
        for (EReference reference : object.eClass().getEAllContainments())
        {
            if (object.eIsSet(reference))
            {
                hasContainment = true;
                break;
            }
        }
        if (!hasContainment)
        {
            return;
        }
        result.append("## Contained structure\n\n"); //$NON-NLS-1$
        appendChildren(result, object, address, 0, language);
    }

    private static void appendObjectOutline(StringBuilder result, EObject object, String address,
        int depth, String language)
    {
        indent(result, depth);
        if (isChart(object))
        {
            result.append("- DataCompositionChart — `").append(address) //$NON-NLS-1$
                .append("` — read-only; chart authoring is unsupported.\n"); //$NON-NLS-1$
            return;
        }
        result.append("- ").append(object.eClass().getName()).append(" — `").append(address) //$NON-NLS-1$ //$NON-NLS-2$
            .append("`\n"); //$NON-NLS-1$
        for (EAttribute attribute : object.eClass().getEAllAttributes())
        {
            if (!object.eIsSet(attribute))
            {
                continue;
            }
            indent(result, depth + 1);
            result.append("- ").append(attribute.getName()).append(": ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(MarkdownUtils.escapeMarkdown(displayValue(object.eGet(attribute), language)))
                .append('\n');
        }
        appendChildren(result, object, address, depth + 1, language);
    }

    private static void appendChildren(StringBuilder result, EObject object, String address,
        int depth, String language)
    {
        for (EReference reference : object.eClass().getEAllContainments())
        {
            if (!object.eIsSet(reference))
            {
                continue;
            }
            Object value = object.eGet(reference);
            String feature = canonicalFeature(reference.getName());
            String featureAddress = child(address, feature);
            if (reference.isMany() && value instanceof List<?>)
            {
                List<?> children = (List<?>)value;
                indent(result, depth);
                result.append("- ").append(feature).append(" (").append(children.size()) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(") — `").append(featureAddress).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (int i = 0; i < children.size(); i++)
                {
                    Object child = children.get(i);
                    if (child instanceof EObject)
                    {
                        String itemAddress = child(featureAddress,
                            selector(feature, object, (EObject)child, i));
                        appendObjectOutline(result, (EObject)child, itemAddress, depth + 1, language);
                    }
                }
            }
            else if (value instanceof EObject)
            {
                appendObjectOutline(result, (EObject)value, featureAddress, depth, language);
            }
        }
    }

    private static void collectByClass(EObject object, String address, String className,
        List<NodeRef> result)
    {
        if (object == null)
        {
            return;
        }
        if (className.equals(object.eClass().getName()))
        {
            result.add(new NodeRef(object, address, FEATURE_ITEMS, Collections.<NodeRef> emptyList()));
        }
        for (EReference reference : object.eClass().getEAllContainments())
        {
            Object value = object.eGet(reference);
            String feature = canonicalFeature(reference.getName());
            String featureAddress = child(address, feature);
            if (reference.isMany() && value instanceof List<?>)
            {
                List<?> children = (List<?>)value;
                for (int i = 0; i < children.size(); i++)
                {
                    Object child = children.get(i);
                    if (child instanceof EObject)
                    {
                        collectByClass((EObject)child,
                            child(featureAddress, selector(feature, object, (EObject)child, i)),
                            className, result);
                    }
                }
            }
            else if (value instanceof EObject)
            {
                collectByClass((EObject)value, featureAddress, className, result);
            }
        }
    }

    private static String typeOf(NodeRef node)
    {
        if (node.value instanceof List<?>)
        {
            return collectionType(node.collection);
        }
        if (!(node.value instanceof EObject))
        {
            return "userSettings"; //$NON-NLS-1$
        }
        EObject object = (EObject)node.value;
        if (object instanceof DataCompositionSchema)
        {
            return TYPE_SCHEMA;
        }
        String name = object.eClass().getName();
        if ("DynamicListExtInfo".equals(name)) //$NON-NLS-1$
        {
            return TYPE_DYNAMIC_LIST;
        }
        if (object instanceof DataSet)
        {
            return "dataSet"; //$NON-NLS-1$
        }
        if (object instanceof DataSetField)
        {
            return "field"; //$NON-NLS-1$
        }
        if (object instanceof DataCompositionSettings)
        {
            return "userSettings"; //$NON-NLS-1$
        }
        if (name.contains("DataSource")) //$NON-NLS-1$
        {
            return "dataSource"; //$NON-NLS-1$
        }
        if (name.contains("CalculatedField")) //$NON-NLS-1$
        {
            return "calculatedField"; //$NON-NLS-1$
        }
        if (name.contains("TotalField")) //$NON-NLS-1$
        {
            return "totalField"; //$NON-NLS-1$
        }
        if ("SettingsVariant".equals(name)) //$NON-NLS-1$
        {
            return "variant"; //$NON-NLS-1$
        }
        if (name.contains("SchemaParameter")) //$NON-NLS-1$
        {
            return "parameter"; //$NON-NLS-1$
        }
        if (name.contains("ConditionalAppearance")) //$NON-NLS-1$
        {
            return "conditionalAppearance"; //$NON-NLS-1$
        }
        if (name.contains("Selected")) //$NON-NLS-1$
        {
            return "selection"; //$NON-NLS-1$
        }
        if (name.contains("Filter")) //$NON-NLS-1$
        {
            return "filter"; //$NON-NLS-1$
        }
        if (name.contains("Order")) //$NON-NLS-1$
        {
            return "order"; //$NON-NLS-1$
        }
        if (name.contains("DataParameter")) //$NON-NLS-1$
        {
            return "dataParameter"; //$NON-NLS-1$
        }
        if (name.contains("OutputParameter")) //$NON-NLS-1$
        {
            return "outputParameter"; //$NON-NLS-1$
        }
        if (name.contains("UserField")) //$NON-NLS-1$
        {
            return "userField"; //$NON-NLS-1$
        }
        if (name.contains("DataCompositionGroup")) //$NON-NLS-1$
        {
            return "grouping"; //$NON-NLS-1$
        }
        if (name.contains("DataCompositionTable")) //$NON-NLS-1$
        {
            return "table"; //$NON-NLS-1$
        }
        return collectionType(node.collection);
    }

    private static String collectionType(String collection)
    {
        if (collection == null)
        {
            return "userSettings"; //$NON-NLS-1$
        }
        switch (collection)
        {
            case "dataSources": //$NON-NLS-1$
                return "dataSource"; //$NON-NLS-1$
            case "dataSets": //$NON-NLS-1$
                return "dataSet"; //$NON-NLS-1$
            case "fields": //$NON-NLS-1$
                return "field"; //$NON-NLS-1$
            case "parameters": //$NON-NLS-1$
                return "parameter"; //$NON-NLS-1$
            case "calculatedFields": //$NON-NLS-1$
                return "calculatedField"; //$NON-NLS-1$
            case "totalFields": //$NON-NLS-1$
                return "totalField"; //$NON-NLS-1$
            case FEATURE_VARIANTS:
                return "variant"; //$NON-NLS-1$
            default:
                return collection;
        }
    }

    private static Result typeMismatch(String requested, String actual, String address)
    {
        return Result.failure("Type '" + requested + "' does not match target '" + address //$NON-NLS-1$ //$NON-NLS-2$
            + "' (its type is '" + actual + "'). Pass type='" + actual //$NON-NLS-1$ //$NON-NLS-2$
            + "', or change fqn to the collection/node for type='" + requested + "'."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String selector(String collection, EObject owner, EObject item, int index)
    {
        if (NATURAL_NAME_COLLECTIONS.contains(collection))
        {
            String name = stringFeature(item, "name"); //$NON-NLS-1$
            if (!name.isEmpty())
            {
                return name;
            }
        }
        if (DATA_PATH_COLLECTIONS.contains(collection))
        {
            String dataPath = stringFeature(item, "dataPath"); //$NON-NLS-1$
            if (!dataPath.isEmpty())
            {
                return dataPath;
            }
        }
        return Integer.toString(index);
    }

    private static String itemName(Object value, String language)
    {
        if (!(value instanceof EObject))
        {
            return displayValue(value, language);
        }
        EObject object = (EObject)value;
        String name = stringFeature(object, "name"); //$NON-NLS-1$
        if (!name.isEmpty())
        {
            return name;
        }
        name = stringFeature(object, "dataPath"); //$NON-NLS-1$
        if (!name.isEmpty())
        {
            return name;
        }
        name = presentationFeature(object, "presentation", language); //$NON-NLS-1$
        return name.isEmpty() ? "(unnamed)" : name; //$NON-NLS-1$
    }

    private static String itemKind(Object value)
    {
        return value instanceof EObject ? ((EObject)value).eClass().getName()
            : value == null ? "null" : value.getClass().getSimpleName(); //$NON-NLS-1$
    }

    private static String presentationFeature(EObject owner, String featureName, String language)
    {
        EObject presentation = asEObject(featureValue(owner, featureName));
        if (presentation == null)
        {
            return ""; //$NON-NLS-1$
        }
        String neutral = stringFeature(presentation, "value"); //$NON-NLS-1$
        EObject local = asEObject(featureValue(presentation, "localValue")); //$NON-NLS-1$
        EObject content = asEObject(featureValue(local, "content")); //$NON-NLS-1$
        Object map = content == null ? null : featureValue(content, "map"); //$NON-NLS-1$
        if (map instanceof Map<?, ?> && language != null)
        {
            Object localized = ((Map<?, ?>)map).get(language);
            if (localized != null && !localized.toString().isEmpty())
            {
                return localized.toString();
            }
        }
        return neutral;
    }

    private static String displayValue(Object value, String language)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof EObject)
        {
            EObject object = (EObject)value;
            String name = stringFeature(object, "name"); //$NON-NLS-1$
            if (!name.isEmpty())
            {
                return name;
            }
            String presentation = presentationFeature(object, "presentation", language); //$NON-NLS-1$
            return presentation.isEmpty() ? object.eClass().getName() : presentation;
        }
        if (value instanceof List<?>)
        {
            List<String> parts = new ArrayList<>();
            for (Object item : (List<?>)value)
            {
                parts.add(displayValue(item, language));
            }
            return String.join(", ", parts); //$NON-NLS-1$
        }
        return value.toString();
    }

    private static String stringFeature(EObject object, String name)
    {
        Object value = featureValue(object, name);
        return value == null ? "" : value.toString(); //$NON-NLS-1$
    }

    private static Object featureValue(EObject object, String name)
    {
        if (object == null)
        {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(modelFeature(name));
        return feature == null ? null : object.eGet(feature);
    }

    private static int size(EObject object, String name)
    {
        Object value = featureValue(object, name);
        return value instanceof List<?> ? ((List<?>)value).size() : 0;
    }

    private static EObject asEObject(Object value)
    {
        return value instanceof EObject ? (EObject)value : null;
    }

    private static DataCompositionSettings asSettings(Object value)
    {
        return value instanceof DataCompositionSettings ? (DataCompositionSettings)value : null;
    }

    private static boolean isChart(Object value)
    {
        return value instanceof EObject && CHART_CLASS.equals(((EObject)value).eClass().getName());
    }

    private static String canonicalFeature(String modelName)
    {
        return MODEL_FEATURE_VARIANTS.equals(modelName) ? FEATURE_VARIANTS : modelName;
    }

    private static String modelFeature(String canonicalName)
    {
        return FEATURE_VARIANTS.equals(canonicalName) ? MODEL_FEATURE_VARIANTS : canonicalName;
    }

    private static String child(String address, String decodedSegment)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(address);
        if (!parsed.isSuccess())
        {
            return address;
        }
        List<String> segments = new ArrayList<>(parsed.address().segments());
        segments.add(decodedSegment);
        return DcsAddress.render(parsed.address().rootFqn(), segments);
    }

    private static String parentAddress(String address)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(address);
        if (!parsed.isSuccess() || parsed.address().segments().isEmpty())
        {
            return address;
        }
        List<String> segments = new ArrayList<>(parsed.address().segments());
        segments.remove(segments.size() - 1);
        return DcsAddress.render(parsed.address().rootFqn(), segments);
    }

    private static void indent(StringBuilder result, int depth)
    {
        for (int i = 0; i < depth; i++)
        {
            result.append("  "); //$NON-NLS-1$
        }
    }

    /** Projection outcome. */
    public static final class Result
    {
        private final String markdown;
        private final String error;

        private Result(String markdown, String error)
        {
            this.markdown = markdown;
            this.error = error;
        }

        static Result success(String markdown)
        {
            return new Result(markdown, null);
        }

        static Result failure(String error)
        {
            return new Result(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public String markdown()
        {
            return markdown;
        }

        public String error()
        {
            return error;
        }
    }

    private static final class NodeRef
    {
        final Object value;
        final String address;
        final String collection;
        final List<NodeRef> items;

        NodeRef(Object value, String address, String collection, List<NodeRef> items)
        {
            this.value = value;
            this.address = address;
            this.collection = collection;
            this.items = items;
        }
    }

    private static final class NodeResolution
    {
        final NodeRef node;
        final String error;

        private NodeResolution(NodeRef node, String error)
        {
            this.node = node;
            this.error = error;
        }

        static NodeResolution success(NodeRef node)
        {
            return new NodeResolution(node, null);
        }

        static NodeResolution failure(String error)
        {
            return new NodeResolution(null, error);
        }

        boolean isSuccess()
        {
            return node != null;
        }
    }

    private static final class CollectionRef
    {
        final String address;
        final List<NodeRef> items;
        final String error;

        private CollectionRef(String address, List<NodeRef> items, String error)
        {
            this.address = address;
            this.items = items;
            this.error = error;
        }

        static CollectionRef success(String address, List<NodeRef> items)
        {
            return new CollectionRef(address, items, null);
        }

        static CollectionRef empty(String rootFqn, String type)
        {
            return success(child(rootFqn, collectionName(type)), Collections.<NodeRef> emptyList());
        }

        static CollectionRef failure(String error)
        {
            return new CollectionRef(null, Collections.<NodeRef> emptyList(), error);
        }

        private static String collectionName(String type)
        {
            switch (type)
            {
                case "dataSource": //$NON-NLS-1$
                    return "dataSources"; //$NON-NLS-1$
                case "dataSet": //$NON-NLS-1$
                    return "dataSets"; //$NON-NLS-1$
                case "field": //$NON-NLS-1$
                    return "fields"; //$NON-NLS-1$
                case "parameter": //$NON-NLS-1$
                    return "parameters"; //$NON-NLS-1$
                case "calculatedField": //$NON-NLS-1$
                    return "calculatedFields"; //$NON-NLS-1$
                case "totalField": //$NON-NLS-1$
                    return "totalFields"; //$NON-NLS-1$
                case "variant": //$NON-NLS-1$
                    return FEATURE_VARIANTS;
                default:
                    return type;
            }
        }
    }
}
