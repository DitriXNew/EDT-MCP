/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValueValue;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetFieldFolder;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTable;
import com._1c.g5.v8.dt.dcs.model.settings.StructureItem;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.NullValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Value;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/**
 * Pure Markdown projection and pointer-resolution layer shared by report/template schemas and form
 * dynamic lists/conditional appearance. In particular, schema and dynamic-list roots reach
 * {@link #renderSettingsOutline} for their settings;
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
    private static final int ERROR_KEY_LIMIT = 20;

    private static final FeatureAlias[] FEATURE_ALIASES = {
        new FeatureAlias(DataCompositionSchema.class, FEATURE_VARIANTS, MODEL_FEATURE_VARIANTS)
    };

    // Aggregate rows are item-paged and cannot split one cell across item offsets. Keep a very long
    // presentation bounded and point at its exact node, whose character pager carries the full value.
    private static final int MAX_TABLE_CELL_CHARS = 4096;
    private static final int HASH_HEADER_CHARS = 34;
    private static final int DEFAULT_CHARACTER_LIMIT = DcsXmlCodec.DEFAULT_CHUNK_CHARS;

    // The protocol caps the decoded content text, not its JSON-RPC escaping. Reserve the fixed
    // DcsTool hash header and the largest bounded Markdown signal that can be appended afterwards,
    // so every candidate measured here is also guaranteed to fit what the client receives.
    private static final int MAX_PAGE_CHARS = OutputSizeGuard.MAX_CONTENT_CHARS
        - HASH_HEADER_CHARS - McpProtocolHandler.MAX_MARKDOWN_USER_SIGNAL_AUGMENTATION_CHARS;

    private static final PageBoundaries ITEM_BOUNDARIES = new PageBoundaries()
    {
        @Override
        public int atOrBefore(int start, int candidate)
        {
            return candidate;
        }

        @Override
        public int next(int start)
        {
            return start + 1;
        }
    };

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
     * @param limit caller page size, or {@code null} when omitted
     * @param offset non-negative page offset
     * @return Markdown or an actionable failure
     */
    public static Result render(String rootFqn, TargetKind kind, EObject root,
        DcsAddress requestedAddress, String type, String language, Integer limit, int offset)
    {
        return render(rootFqn, kind, root, requestedAddress, type, language, limit, offset,
            MAX_PAGE_CHARS);
    }

    static Result render(String rootFqn, TargetKind kind, EObject root,
        DcsAddress requestedAddress, String type, String language, Integer limit, int offset,
        int maxPageChars)
    {
        String canonicalRoot = DcsAddress.render(rootFqn, Collections.<String> emptyList());
        if (requestedAddress == null)
        {
            return Result.failure("DCS address is missing. Pass an existing DCS root FQN."); //$NON-NLS-1$
        }
        if (!requestedAddress.hasPointer())
        {
            if (kind == TargetKind.FORM)
            {
                if (!"conditionalAppearance".equals(type)) //$NON-NLS-1$
                {
                    return typeMismatch(type, "conditionalAppearance", canonicalRoot); //$NON-NLS-1$
                }
                return Result.success(renderFormConditionalAppearance(canonicalRoot, root,
                    language, characterPageLimit(limit, maxPageChars), offset, maxPageChars));
            }
            if (TYPE_SCHEMA.equals(type))
            {
                if (kind == TargetKind.DYNAMIC_LIST)
                {
                    return typeMismatch(type, TYPE_DYNAMIC_LIST, canonicalRoot);
                }
                return Result.success(renderSchemaSummary(canonicalRoot,
                    root instanceof DataCompositionSchema ? (DataCompositionSchema)root : null,
                    language, itemPageLimit(limit), offset, maxPageChars));
            }
            if (TYPE_DYNAMIC_LIST.equals(type))
            {
                if (kind != TargetKind.DYNAMIC_LIST)
                {
                    return typeMismatch(type, TYPE_SCHEMA, canonicalRoot);
                }
                return Result.success(renderDynamicListSummary(canonicalRoot, root, language,
                    itemPageLimit(limit), offset, maxPageChars));
            }
            if ("userSettings".equals(type)) //$NON-NLS-1$
            {
                return Result.success(renderSettingsPage(canonicalRoot, kind, root, type, language,
                    characterPageLimit(limit, maxPageChars), offset, maxPageChars));
            }
            return renderRootCollection(canonicalRoot, kind, root, type, language,
                itemPageLimit(limit), offset, maxPageChars);
        }

        if (root == null)
        {
            return Result.failure("Pointer '" + requestedAddress + "' cannot be resolved because DCS root '" //$NON-NLS-1$ //$NON-NLS-2$
                + rootFqn + "' has no " + (kind == TargetKind.FORM //$NON-NLS-1$
                    ? "conditional appearance" : "schema content") //$NON-NLS-1$ //$NON-NLS-2$
                + ". Create it first, then re-run dcs action='get'."); //$NON-NLS-1$
        }
        NodeResolution resolution = resolvePointer(rootFqn, root, requestedAddress.segments());
        if (!resolution.isSuccess())
        {
            return Result.failure(resolution.error);
        }
        NodeRef node = resolution.node;
        if (isChart(node.value))
        {
            return Result.success(renderTextPage(node.address, type, renderFullNode(node, language),
                characterPageLimit(limit, maxPageChars), offset, false, maxPageChars));
        }
        String actualType = typeOf(node);
        if (actualType == null)
        {
            return Result.failure("DCS collection '" + node.address //$NON-NLS-1$
                + "' is not addressable by any public type. Read its parent '" //$NON-NLS-1$
                + parentAddress(node.address) + "' instead."); //$NON-NLS-1$
        }
        if (!type.equals(actualType))
        {
            if (node.value instanceof DataCompositionSettings && isStructureKind(type))
            {
                return settingsStructureTypeMismatch(type, node.address);
            }
            if (isStructureCollection(node) && isStructureKind(type))
            {
                return structureCollectionTypeMismatch(type, node.address);
            }
            return typeMismatch(type, actualType, node.address);
        }
        if (node.value instanceof List<?>)
        {
            return Result.success(renderCollectionPage(node.address, type, node.items, language,
                itemPageLimit(limit), offset, maxPageChars));
        }
        if ((TYPE_DYNAMIC_LIST.equals(type) && FEATURE_QUERY_TEXT.equals(node.collection))
            || ("dataSet".equals(type) && FEATURE_QUERY.equals(node.collection))) //$NON-NLS-1$
        {
            return Result.success(renderScalarPage(node.address, type,
                node.value == null ? "" : node.value.toString(), //$NON-NLS-1$
                characterPageLimit(limit, maxPageChars), offset, maxPageChars));
        }
        return Result.success(renderTextPage(node.address, type, renderFullNode(node, language),
            characterPageLimit(limit, maxPageChars), offset, false, maxPageChars));
    }

    private static int itemPageLimit(Integer requested)
    {
        int raw = requested == null ? Pagination.DEFAULT_LIMIT : requested.intValue();
        return Pagination.clampLimit(raw, Pagination.MAX_LIMIT);
    }

    private static int characterPageLimit(Integer requested, int maxPageChars)
    {
        int raw = requested == null ? DEFAULT_CHARACTER_LIMIT : requested.intValue();
        return Math.min(Math.max(1, raw), maxPageChars);
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

    /** Returns canonical addresses of nodes that refer to an identity being removed or renamed. */
    public static List<String> referenceAddresses(EObject root, String rootFqn, String kind,
        String identity)
    {
        return referenceAddressesAt(root,
            DcsAddress.render(rootFqn, Collections.<String>emptyList()), kind, identity);
    }

    /** Resolves the same canonical pointer used by reads for the read-only options catalogue. */
    static OptionsNode resolveOptionsNode(String rootFqn, EObject root, DcsAddress address)
    {
        if (address == null || !address.hasPointer())
        {
            return OptionsNode.success(root, null, null);
        }
        if (root == null)
        {
            return OptionsNode.failure("Pointer '" + address //$NON-NLS-1$
                + "' cannot be resolved because the DCS root has no content."); //$NON-NLS-1$
        }
        NodeResolution resolution = resolvePointer(rootFqn, root, address.segments());
        return resolution.isSuccess()
            ? OptionsNode.success(resolution.node.value, resolution.node.owner,
                typeOf(resolution.node))
            : OptionsNode.failure(resolution.error);
    }

    private static String renderFormConditionalAppearance(String address, EObject appearance,
        String language, int limit, int offset, int maxPageChars)
    {
        String full;
        if (appearance == null)
        {
            full = "**Address:** `" + address //$NON-NLS-1$
                + "`\n\n_(conditional appearance is not present)_\n"; //$NON-NLS-1$
        }
        else
        {
            StringBuilder result = new StringBuilder();
            appendObjectOutline(result, appearance, address, 0, language);
            full = result.toString();
        }
        return renderTextPage(address, "conditionalAppearance", full, limit, offset, false, //$NON-NLS-1$
            maxPageChars);
    }

    static List<String> referenceAddressesAt(EObject root, String rootAddress, String kind,
        String identity)
    {
        if (root == null || identity == null || identity.isEmpty())
        {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<>();
        collectReferences(root, rootAddress, kind, identity, result);
        return new ArrayList<>(result);
    }

    /** Lists schema/settings subtypes that the writer cannot reproduce, with canonical addresses. */
    public static List<String> unmodellableNodes(EObject root, String rootFqn)
    {
        if (root == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        collectUnmodellable(root, DcsAddress.render(rootFqn, Collections.<String>emptyList()),
            "", false, result); //$NON-NLS-1$
        return result;
    }

    private static void collectUnmodellable(EObject object, String address, String collection,
        boolean inAdditionalProperties, List<String> result)
    {
        boolean additionalProperties = inAdditionalProperties
            || "additionalProperties".equals(collection); //$NON-NLS-1$
        boolean unsupported = object instanceof DataSet
            && !(object instanceof DataCompositionSchemaDataSetQuery)
            && !(object instanceof DataCompositionSchemaDataSetObject)
            && !(object instanceof DataCompositionSchemaDataSetUnion)
            || object instanceof DataSetField
                && !(object instanceof DataCompositionSchemaDataSetField)
                && !(object instanceof DataCompositionSchemaDataSetFieldFolder)
            || object instanceof StructureItem && !(object instanceof DataCompositionGroup)
                && !(object instanceof DataCompositionTable)
            || "nestedSchemas".equals(collection) || "templates".equals(collection) //$NON-NLS-1$ //$NON-NLS-2$
            || "fieldTemplates".equals(collection) || "groupTemplates".equals(collection) //$NON-NLS-1$ //$NON-NLS-2$
            || "groupHeaderTemplates".equals(collection) //$NON-NLS-1$
            || "totalFieldsTemplates".equals(collection); //$NON-NLS-1$
        if (additionalProperties && object instanceof Value
            && !isAuthorableAdditionalPropertyValue((Value)object))
        {
            unsupported = true;
        }
        if (object instanceof DataCompositionParameterValue
            && !((DataCompositionParameterValue)object).getNestedParameterValues().isEmpty())
        {
            unsupported = true;
        }
        if (unsupported)
        {
            result.add(object.eClass().getName() + " at " + address); //$NON-NLS-1$
            return;
        }
        for (EReference reference : object.eClass().getEAllContainments())
        {
            Object value = object.eGet(reference);
            String feature = canonicalFeature(object, reference.getName());
            String featureAddress = child(address, feature);
            if (reference.isMany() && value instanceof List<?>)
            {
                List<?> children = (List<?>)value;
                for (int i = 0; i < children.size(); i++)
                {
                    Object contained = children.get(i);
                    if (contained instanceof EObject)
                    {
                        EObject childObject = (EObject)contained;
                        String childAddress = object instanceof DataSet
                            && "fields".equals(feature) && childObject instanceof DataSetField //$NON-NLS-1$
                                ? fieldAddress((DataSet)object, (DataSetField)childObject,
                                    featureAddress)
                                : child(featureAddress,
                                    selector(feature, object, childObject, i));
                        collectUnmodellable(childObject, childAddress, feature,
                            additionalProperties, result);
                    }
                }
            }
            else if (value instanceof EObject)
            {
                collectUnmodellable((EObject)value, featureAddress, feature,
                    additionalProperties, result);
            }
        }
    }

    private static boolean isAuthorableAdditionalPropertyValue(Value value)
    {
        return value instanceof DataCompositionField || value instanceof DataCompositionParameter
            || value instanceof DesignTimeValueValue || value instanceof StringValue
            || value instanceof NumberValue || value instanceof BooleanValue
            || value instanceof DateValue || value instanceof NullValue;
    }

    private static void collectReferences(EObject object, String address, String kind,
        String identity, Set<String> result)
    {
        if (("field".equals(kind) || "fieldFolder".equals(kind) //$NON-NLS-1$ //$NON-NLS-2$
            || "calculatedField".equals(kind) || "totalField".equals(kind) //$NON-NLS-1$ //$NON-NLS-2$
            || "userField".equals(kind)) //$NON-NLS-1$
            && object instanceof DataCompositionField
            && sameIdentity(identity, ((DataCompositionField)object).getValue()))
        {
            result.add(parentAddress(address));
        }
        if ("parameter".equals(kind) && object instanceof DataCompositionParameter //$NON-NLS-1$
            && sameIdentity(identity, ((DataCompositionParameter)object).getValue()))
        {
            result.add(parentAddress(address));
        }
        if ("parameter".equals(kind) && object instanceof DataCompositionSchemaDataSetLink //$NON-NLS-1$
            && sameIdentity(identity, ((DataCompositionSchemaDataSetLink)object).getParameter()))
        {
            result.add(address);
        }
        if ("dataSet".equals(kind) && object instanceof DataCompositionSchemaDataSetLink) //$NON-NLS-1$
        {
            DataCompositionSchemaDataSetLink link = (DataCompositionSchemaDataSetLink)object;
            if (sameIdentity(identity, link.getSourceDataSet())
                || sameIdentity(identity, link.getDestinationDataSet()))
            {
                result.add(address);
            }
        }
        if (object instanceof DataCompositionSchemaDataSetField)
        {
            DataCompositionSchemaDataSetField field =
                (DataCompositionSchemaDataSetField)object;
            if (("dataSet".equals(kind) //$NON-NLS-1$
                && sameIdentity(identity, field.getInHierarchyDataSet()))
                || ("parameter".equals(kind) //$NON-NLS-1$
                    && sameIdentity(identity, field.getInHierarchyDataSetParameter())))
            {
                result.add(address);
            }
        }
        if ("dataSource".equals(kind)) //$NON-NLS-1$
        {
            String dataSource = object instanceof DataCompositionSchemaDataSetQuery
                ? ((DataCompositionSchemaDataSetQuery)object).getDataSource()
                : object instanceof DataCompositionSchemaDataSetObject
                    ? ((DataCompositionSchemaDataSetObject)object).getDataSource() : null;
            if (sameIdentity(identity, dataSource))
            {
                result.add(address);
            }
        }
        // Expressions name fields by data path and parameters as '&Name'. Query text names only
        // parameters in this identity surface; query source/alias tokens are not DCS field nodes.
        if ("field".equals(kind) || "fieldFolder".equals(kind) //$NON-NLS-1$ //$NON-NLS-2$
            || "calculatedField".equals(kind) //$NON-NLS-1$
            || "totalField".equals(kind) || "userField".equals(kind) //$NON-NLS-1$ //$NON-NLS-2$
            || "parameter".equals(kind)) //$NON-NLS-1$
        {
            collectTextReferences(object, address, kind, identity, result);
        }
        for (EReference reference : object.eClass().getEAllContainments())
        {
            Object value = object.eGet(reference);
            String feature = canonicalFeature(object, reference.getName());
            String featureAddress = child(address, feature);
            if (reference.isMany() && value instanceof List<?>)
            {
                List<?> children = (List<?>)value;
                for (int i = 0; i < children.size(); i++)
                {
                    Object contained = children.get(i);
                    if (contained instanceof EObject)
                    {
                        EObject childObject = (EObject)contained;
                        String childAddress = object instanceof DataSet
                            && "fields".equals(feature) && childObject instanceof DataSetField //$NON-NLS-1$
                                ? fieldAddress((DataSet)object, (DataSetField)childObject,
                                    featureAddress)
                                : child(featureAddress,
                                    selector(feature, object, childObject, i));
                        collectReferences(childObject, childAddress, kind, identity, result);
                    }
                }
            }
            else if (value instanceof EObject)
            {
                collectReferences((EObject)value, featureAddress, kind, identity, result);
            }
        }
    }

    private static Result renderRootCollection(String rootFqn, TargetKind kind, EObject root,
        String type, String language, int limit, int offset, int maxPageChars)
    {
        CollectionRef collection = rootCollection(rootFqn, kind, root, type);
        if (collection.error != null)
        {
            return Result.failure(collection.error);
        }
        return Result.success(renderCollectionPage(collection.address, type, collection.items,
            language, limit, offset, maxPageChars));
    }

    private static String renderSchemaSummary(String rootFqn, DataCompositionSchema schema,
        String language, int limit, int offset, int maxPageChars)
    {
        StringBuilder result = summaryHeader("Data Composition Schema", rootFqn); //$NON-NLS-1$
        result.append("## Counts\n\n"); //$NON-NLS-1$
        result.append(MarkdownUtils.tableHeader("Section", "Count", "Address")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Data sources", size(schema, "dataSources"), child(rootFqn, "dataSources")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Data sets", size(schema, "dataSets"), child(rootFqn, "dataSets")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Data set links", size(schema, "dataSetLinks"), //$NON-NLS-1$ //$NON-NLS-2$
            child(rootFqn, "dataSetLinks")); //$NON-NLS-1$
        appendCount(result, "Calculated fields", size(schema, "calculatedFields"), //$NON-NLS-1$ //$NON-NLS-2$
            child(rootFqn, "calculatedFields")); //$NON-NLS-1$
        appendCount(result, "Total fields", size(schema, "totalFields"), child(rootFqn, "totalFields")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Parameters", size(schema, "parameters"), child(rootFqn, "parameters")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionSettings settings = schema == null ? null : schema.getDefaultSettings();
        appendCount(result, "Default settings", settings == null ? 0 : 1, child(rootFqn, "defaultSettings")); //$NON-NLS-1$ //$NON-NLS-2$
        appendSettingsCounts(result, settings, child(rootFqn, "defaultSettings")); //$NON-NLS-1$
        appendCount(result, "Variants", size(schema, MODEL_FEATURE_VARIANTS), child(rootFqn, FEATURE_VARIANTS)); //$NON-NLS-1$
        result.append('\n');

        List<SummarySection> sections = new ArrayList<>();
        if (schema != null)
        {
            addNameSection(sections, "Data sources", directItems(rootFqn, schema, "dataSources"), language); //$NON-NLS-1$ //$NON-NLS-2$
            addNameSection(sections, "Data sets", directItems(rootFqn, schema, "dataSets"), language); //$NON-NLS-1$ //$NON-NLS-2$
            addDataSetLinksSection(sections, rootFqn, schema);
            addNameSection(sections, "Calculated fields", //$NON-NLS-1$
                directItems(rootFqn, schema, "calculatedFields"), language); //$NON-NLS-1$
            addNameSection(sections, "Total fields", directItems(rootFqn, schema, "totalFields"), language); //$NON-NLS-1$ //$NON-NLS-2$
            addNameSection(sections, "Parameters", directItems(rootFqn, schema, "parameters"), language); //$NON-NLS-1$ //$NON-NLS-2$
            addNameSection(sections, "Variants", directItems(rootFqn, schema, MODEL_FEATURE_VARIANTS), language); //$NON-NLS-1$
        }
        return renderSummaryPage(result.toString(), sections,
            "_Query text and recursive settings are omitted from this summary. Drill down with an address._\n", //$NON-NLS-1$
            limit, offset, maxPageChars);
    }

    private static String renderDynamicListSummary(String rootFqn, EObject root, String language,
        int limit, int offset, int maxPageChars)
    {
        StringBuilder result = summaryHeader("Dynamic List", rootFqn); //$NON-NLS-1$
        List<SummarySection> sections = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        if (root != null)
        {
            for (EAttribute attribute : root.eClass().getEAllAttributes())
            {
                Object value = root.eGet(attribute);
                if (FEATURE_QUERY_TEXT.equals(attribute.getName()))
                {
                    String query = value == null ? "" : value.toString(); //$NON-NLS-1$
                    value = query.length() + " characters (omitted from summary; read at `" //$NON-NLS-1$
                        + child(rootFqn, FEATURE_QUERY_TEXT) + "`)"; //$NON-NLS-1$
                }
                String address = child(rootFqn, attribute.getName());
                properties.add(MarkdownUtils.tableRow(attribute.getName(),
                    boundedTableCell(displayValue(value, language), address)));
            }
            EStructuralFeature mainTable = root.eClass().getEStructuralFeature("mainTable"); //$NON-NLS-1$
            if (mainTable != null)
            {
                properties.add(MarkdownUtils.tableRow("mainTable", //$NON-NLS-1$
                    boundedTableCell(displayValue(root.eGet(mainTable), language),
                        child(rootFqn, "mainTable")))); //$NON-NLS-1$
            }
        }
        if (!properties.isEmpty())
        {
            sections.add(new SummarySection("Properties", //$NON-NLS-1$
                MarkdownUtils.tableHeader("Property", "Value"), properties)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        result.append("## Counts\n\n"); //$NON-NLS-1$
        result.append(MarkdownUtils.tableHeader("Section", "Count", "Address")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Fields", size(root, "fields"), child(rootFqn, "fields")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        appendCount(result, "Calculated fields", size(root, "calculatedFields"), //$NON-NLS-1$ //$NON-NLS-2$
            child(rootFqn, "calculatedFields")); //$NON-NLS-1$
        appendCount(result, "Parameters", size(root, "parameters"), child(rootFqn, "parameters")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionSettings settings = asSettings(featureValue(root, "listSettings")); //$NON-NLS-1$
        appendCount(result, "List settings", settings == null ? 0 : 1, child(rootFqn, "listSettings")); //$NON-NLS-1$ //$NON-NLS-2$
        appendSettingsCounts(result, settings, child(rootFqn, "listSettings")); //$NON-NLS-1$
        result.append('\n');
        addNameSection(sections, "Fields", directItems(rootFqn, root, "fields"), language); //$NON-NLS-1$ //$NON-NLS-2$
        addNameSection(sections, "Calculated fields", //$NON-NLS-1$
            directItems(rootFqn, root, "calculatedFields"), language); //$NON-NLS-1$
        addNameSection(sections, "Parameters", directItems(rootFqn, root, "parameters"), language); //$NON-NLS-1$ //$NON-NLS-2$
        return renderSummaryPage(result.toString(), sections,
            "_Query text and recursive list settings are omitted from this summary. Drill down with an address._\n", //$NON-NLS-1$
            limit, offset, maxPageChars);
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

    private static void addNameSection(List<SummarySection> sections, String title,
        List<NodeRef> items, String language)
    {
        if (items.isEmpty())
        {
            return;
        }
        List<String> rows = new ArrayList<>();
        for (NodeRef item : items)
        {
            rows.add(MarkdownUtils.tableRow(
                boundedTableCell(itemName(item.value, language), item.address),
                itemKind(item.value), item.address));
        }
        sections.add(new SummarySection(title,
            MarkdownUtils.tableHeader("Name", "Kind", "Address"), rows)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void addDataSetLinksSection(List<SummarySection> sections, String rootFqn,
        DataCompositionSchema schema)
    {
        if (schema.getDataSetLinks().isEmpty())
        {
            return;
        }
        List<String> rows = new ArrayList<>();
        String collectionAddress = child(rootFqn, "dataSetLinks"); //$NON-NLS-1$
        for (int i = 0; i < schema.getDataSetLinks().size(); i++)
        {
            DataCompositionSchemaDataSetLink link = schema.getDataSetLinks().get(i);
            String address = child(collectionAddress, Integer.toString(i));
            rows.add(MarkdownUtils.tableRow(address, boundedTableCell(
                endpoint(link.getSourceDataSet()) + " → " + endpoint(link.getDestinationDataSet()), //$NON-NLS-1$
                address)));
        }
        sections.add(new SummarySection("Data set links", //$NON-NLS-1$
            MarkdownUtils.tableHeader("Address", "Link"), rows)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String renderSummaryPage(String fixed, List<SummarySection> sections,
        String footer, int limit, int offset, int maxPageChars)
    {
        List<SummaryRow> rows = new ArrayList<>();
        for (SummarySection section : sections)
        {
            for (String row : section.rows)
            {
                rows.add(new SummaryRow(section, row));
            }
        }
        int total = rows.size();
        int from = Math.min(offset, total);
        int requestedEnd = Math.min(from + limit, total);
        return fitBoundedPage(from, requestedEnd, total, maxPageChars, ITEM_BOUNDARIES,
            (end, stoppedBy) -> renderSummaryCandidate(fixed, rows, footer, from, end,
                stoppedBy));
    }

    private static String renderSummaryCandidate(String fixed, List<SummaryRow> rows,
        String footer, int from, int to, PageStop stoppedBy)
    {
        StringBuilder result = new StringBuilder(fixed)
            .append("## Aggregate tables page\n\n") //$NON-NLS-1$
            .append("**Aggregate items:** ").append(rows.size()).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Page items:** ").append(to - from).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Offset:** ").append(from).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Next offset:** ") //$NON-NLS-1$
            .append(to < rows.size() ? Integer.toString(to) : "none").append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Stopped by:** ").append(stoppedBy.label).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        SummarySection current = null;
        for (int i = from; i < to; i++)
        {
            SummaryRow row = rows.get(i);
            if (row.section != current)
            {
                current = row.section;
                result.append("## ").append(current.title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                if (i > 0 && rows.get(i - 1).section == current)
                {
                    result.append("_(continued from an earlier page)_\n\n"); //$NON-NLS-1$
                }
                result.append(current.header);
            }
            result.append(row.markdown);
            if (i + 1 >= to || rows.get(i + 1).section != current)
            {
                result.append('\n');
                if (i + 1 >= to && i + 1 < rows.size()
                    && rows.get(i + 1).section == current)
                {
                    result.append("_(section continues at offset ").append(i + 1) //$NON-NLS-1$
                        .append(")_\n\n"); //$NON-NLS-1$
                }
            }
        }
        if (from == to)
        {
            result.append("_(no aggregate rows on this page)_\n\n"); //$NON-NLS-1$
        }
        return result.append(footer).toString();
    }

    private static String endpoint(String dataSetName)
    {
        return dataSetName == null || dataSetName.isEmpty() ? "(unset)" : dataSetName; //$NON-NLS-1$
    }

    private static CollectionRef rootCollection(String rootFqn, TargetKind kind, EObject root,
        String type)
    {
        if (kind == TargetKind.FORM)
        {
            return unsupportedCollection(rootFqn, type, kind);
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
            case "fieldFolder": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? unsupportedCollection(rootFqn, type, kind)
                    : schemaFieldFolders(rootFqn, root);
            case "parameter": //$NON-NLS-1$
                return directCollection(rootFqn, root, "parameters"); //$NON-NLS-1$
            case "calculatedField": //$NON-NLS-1$
                return directCollection(rootFqn, root, "calculatedFields"); //$NON-NLS-1$
            case "totalField": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? unsupportedCollection(rootFqn, type, kind)
                    : directCollection(rootFqn, root, "totalFields"); //$NON-NLS-1$
            case "variant": //$NON-NLS-1$
                return kind == TargetKind.DYNAMIC_LIST ? unsupportedCollection(rootFqn, type, kind)
                    : directCollection(rootFqn, root, DataCompositionSchema.class,
                        MODEL_FEATURE_VARIANTS);
            default:
                return settingsCollection(rootFqn, kind, root, type);
        }
    }

    private static CollectionRef schemaFields(String rootFqn, EObject root)
    {
        return schemaFields(rootFqn, root, false);
    }

    private static CollectionRef schemaFieldFolders(String rootFqn, EObject root)
    {
        return schemaFields(rootFqn, root, true);
    }

    private static CollectionRef schemaFields(String rootFqn, EObject root, boolean foldersOnly)
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
                String dataSetAddress = child(child(rootFqn, "dataSets"), dataSetSelector); //$NON-NLS-1$
                collectDataSetFields((EObject)dataSet, dataSetAddress, result, foldersOnly);
            }
        }
        return CollectionRef.success(child(rootFqn, "dataSets"), result); //$NON-NLS-1$
    }

    private static void collectDataSetFields(EObject dataSet, String dataSetAddress,
        List<NodeRef> result, boolean foldersOnly)
    {
        if (dataSet instanceof DataSet)
        {
            collectFieldLevel((DataSet)dataSet, null, child(dataSetAddress, "fields"), //$NON-NLS-1$
                result, foldersOnly);
        }
        if (!(dataSet instanceof DataCompositionSchemaDataSetUnion))
        {
            return;
        }
        Object value = featureValue(dataSet, FEATURE_ITEMS);
        if (!(value instanceof List<?>))
        {
            return;
        }
        List<?> items = (List<?>)value;
        String itemsAddress = child(dataSetAddress, FEATURE_ITEMS);
        for (int i = 0; i < items.size(); i++)
        {
            Object item = items.get(i);
            if (item instanceof EObject)
            {
                EObject member = (EObject)item;
                collectDataSetFields(member,
                    child(itemsAddress, selector(FEATURE_ITEMS, dataSet, member, i)), result,
                    foldersOnly);
            }
        }
    }

    private static void collectFieldLevel(DataSet dataSet,
        DataCompositionSchemaDataSetFieldFolder parent, String collectionAddress,
        List<NodeRef> result, boolean foldersOnly)
    {
        List<DataSetField> fields = DcsFieldFolders.children(dataSet, parent);
        EObject owner = parent == null ? dataSet : parent;
        for (int i = 0; i < fields.size(); i++)
        {
            DataSetField field = fields.get(i);
            String address = child(collectionAddress, selector("fields", owner, field, i)); //$NON-NLS-1$
            if (!foldersOnly || field instanceof DataCompositionSchemaDataSetFieldFolder)
            {
                result.add(new NodeRef(field, address, "fields", owner, //$NON-NLS-1$
                    Collections.<NodeRef>emptyList()));
            }
            if (field instanceof DataCompositionSchemaDataSetFieldFolder)
            {
                collectFieldLevel(dataSet, (DataCompositionSchemaDataSetFieldFolder)field,
                    child(address, "fields"), result, foldersOnly); //$NON-NLS-1$
            }
        }
    }

    private static void collectTextReferences(EObject object, String address, String kind,
        String identity, Set<String> result)
    {
        for (EAttribute attribute : object.eClass().getEAllAttributes())
        {
            String name = attribute.getName();
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT); //$NON-NLS-1$
            boolean expression = normalized.endsWith("expression") //$NON-NLS-1$
                || "linkcondition".equals(normalized); //$NON-NLS-1$
            boolean query = "parameter".equals(kind) //$NON-NLS-1$
                && ("query".equals(normalized) || "querytext".equals(normalized)); //$NON-NLS-1$ //$NON-NLS-2$
            if (!expression && !query)
            {
                continue;
            }
            Object value = object.eGet(attribute);
            boolean parameter = "parameter".equals(kind); //$NON-NLS-1$
            if (value instanceof String
                && textReferences((String)value, identity, parameter))
            {
                result.add(address);
            }
        }
    }

    /**
     * Compares two DCS identities the way 1C does - case-insensitively - because a retained
     * reference spelled `revenue` really does point at the field `Revenue`.
     *
     * <p>This is DELIBERATELY stricter than the resolvers: natural-key lookup, duplicate counting,
     * rename-collision detection and pointer resolution all still compare exactly. A guard may
     * safely see MORE references than a resolver would select, because its only effect is to
     * refuse; loosening the resolvers instead would change which node an address selects, redefine
     * what counts as a duplicate, and could reject schemas that already exist. Guards conservative,
     * resolvers exact - do not "align" these by making the resolvers case-insensitive.
     *
     * @param first one identity, may be {@code null}
     * @param second the other identity, may be {@code null}
     * @return {@code true} when both are present and equal ignoring case
     */
    private static boolean sameIdentity(String first, String second)
    {
        return first != null && second != null
            && first.toLowerCase(Locale.ROOT).equals(second.toLowerCase(Locale.ROOT));
    }

    private static boolean textReferences(String text, String identity,
        boolean requireParameterPrefix)
    {
        String normalizedIdentity = identity.toLowerCase(Locale.ROOT);
        int current = 0;
        while (current < text.length())
        {
            if (text.charAt(current) == '"')
            {
                current = afterStringLiteral(text, current);
                continue;
            }
            if (!isExpressionTokenCharacter(text.charAt(current)))
            {
                current++;
                continue;
            }
            int start = current;
            while (current < text.length()
                && isExpressionTokenCharacter(text.charAt(current)))
            {
                current++;
            }
            boolean parameterToken = start > 0 && text.charAt(start - 1) == '&';
            if ((!requireParameterPrefix || parameterToken)
                && (current >= text.length() || text.charAt(current) != '('))
            {
                String token = text.substring(start, current).toLowerCase(Locale.ROOT);
                int separator = token.indexOf('.');
                if (token.equals(normalizedIdentity) || separator > 0
                    && token.substring(0, separator).equals(normalizedIdentity))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static int afterStringLiteral(String expression, int openingQuote)
    {
        int current = openingQuote + 1;
        while (current < expression.length())
        {
            if (expression.charAt(current) != '"')
            {
                current++;
            }
            else if (current + 1 < expression.length() && expression.charAt(current + 1) == '"')
            {
                current += 2;
            }
            else
            {
                return current + 1;
            }
        }
        return current;
    }

    private static boolean isExpressionTokenCharacter(char value)
    {
        return Character.isLetterOrDigit(value) || value == '_' || value == '.';
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
        if ("additionalProperties".equals(feature)) //$NON-NLS-1$
        {
            EObject value = asEObject(featureValue(settings, feature));
            List<NodeRef> one = value == null ? Collections.<NodeRef> emptyList()
                : Collections.singletonList(new NodeRef(value, child(settingsAddress, feature),
                feature, settings, Collections.<NodeRef> emptyList()));
            return CollectionRef.success(child(settingsAddress, feature), one);
        }
        if (settings == null)
        {
            return CollectionRef.success(child(child(settingsAddress, feature), FEATURE_ITEMS),
                Collections.<NodeRef> emptyList());
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
        String canonical = canonicalFeature(owner, featureName);
        String address = child(rootFqn, canonical);
        return CollectionRef.success(address, directItemsAt(rootFqn, owner, featureName));
    }

    private static CollectionRef directCollection(String rootFqn, EObject owner,
        Class<? extends EObject> resolvedOwnerType, String featureName)
    {
        String canonical = canonicalFeature(owner, resolvedOwnerType, featureName);
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
        Object value = fieldChildren(owner, featureName);
        if (value == null) value = featureValue(owner, featureName);
        if (!(value instanceof List<?>))
        {
            return Collections.emptyList();
        }
        List<NodeRef> result = new ArrayList<>();
        List<?> list = (List<?>)value;
        String canonical = canonicalFeature(owner, featureName);
        String collectionAddress = child(ownerAddress, canonical);
        for (int i = 0; i < list.size(); i++)
        {
            Object item = list.get(i);
            if (item instanceof EObject)
            {
                String selector = selector(canonical, owner, (EObject)item, i);
                result.add(new NodeRef(item, child(collectionAddress, selector), canonical,
                    owner, Collections.<NodeRef> emptyList()));
            }
        }
        return result;
    }

    private static CollectionRef unsupportedCollection(String rootFqn, String type, TargetKind kind)
    {
        String rootType = kind == TargetKind.DYNAMIC_LIST ? TYPE_DYNAMIC_LIST
            : kind == TargetKind.FORM ? "form conditional-appearance" : TYPE_SCHEMA; //$NON-NLS-1$
        return CollectionRef.failure("Type '" + type + "' is not a collection on " + rootType //$NON-NLS-1$ //$NON-NLS-2$
            + " root '" + rootFqn + "'. Use a compatible type or pass an fqn pointer to a specific node; " //$NON-NLS-1$ //$NON-NLS-2$
            + "call get_tool_guide('dcs') for the address/type map."); //$NON-NLS-1$
    }

    private static String renderCollectionPage(String address, String type, List<NodeRef> all,
        String language, int limit, int offset, int maxPageChars)
    {
        int total = all.size();
        int from = Math.min(offset, total);
        int requestedEnd = Math.min(from + limit, total);
        return fitBoundedPage(from, requestedEnd, total, maxPageChars, ITEM_BOUNDARIES,
            (end, stoppedBy) -> renderCollectionCandidate(address, type, all, language,
                from, end, stoppedBy));
    }

    private static String renderCollectionCandidate(String address, String type,
        List<NodeRef> all, String language, int from, int to, PageStop stoppedBy)
    {
        int total = all.size();
        List<NodeRef> page = all.subList(from, to);
        StringBuilder result = new StringBuilder("# DCS collection: ").append(type).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Address:** `").append(address).append("`\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Items:** ").append(total)
            .append(Pagination.truncationNotice(page.size(), total)).append("\n\n") //$NON-NLS-1$
            .append("**Page items:** ").append(page.size()).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Offset:** ").append(from).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Next offset:** ").append(to < total ? Integer.toString(to) : "none").append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .append("**Stopped by:** ").append(stoppedBy.label).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (page.isEmpty())
        {
            result.append("_(no items on this page)_\n"); //$NON-NLS-1$
            return result.toString();
        }
        result.append(MarkdownUtils.tableHeader("Name", "Kind", "Address", "Note")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (NodeRef item : page)
        {
            String note = isChart(item.value)
                ? "Read-only existing chart; chart authoring is unsupported." //$NON-NLS-1$
                : isNestedDataSet(item.value)
                    ? "Read-only existing nested data set; nested-data-set authoring is unsupported." //$NON-NLS-1$
                    : ""; //$NON-NLS-1$
            if (item.value instanceof EObject
                && "SettingsVariant".equals(((EObject)item.value).eClass().getName())) //$NON-NLS-1$
            {
                String presentation = presentationFeature((EObject)item.value, "presentation", //$NON-NLS-1$
                    language);
                if (!presentation.isEmpty())
                {
                    note = "Presentation: " + presentation; //$NON-NLS-1$
                }
            }
            result.append(MarkdownUtils.tableRow(
                boundedTableCell(itemName(item.value, language), item.address),
                itemKind(item.value), item.address, boundedTableCell(note, item.address)));
        }
        return result.toString();
    }

    private static String renderScalarPage(String address, String type, String value, int limit,
        int offset, int maxPageChars)
    {
        return renderTextPage(address, type, value, limit, offset, true, maxPageChars);
    }

    private static String renderSettingsPage(String rootFqn, TargetKind kind, EObject root,
        String type, String language, int limit, int offset, int maxPageChars)
    {
        String feature = kind == TargetKind.DYNAMIC_LIST ? "listSettings" : "defaultSettings"; //$NON-NLS-1$ //$NON-NLS-2$
        String address = child(rootFqn, feature);
        DataCompositionSettings settings = asSettings(featureValue(root, feature));
        return renderTextPage(address, type, renderSettingsOutline(address, settings, language),
            limit, offset, false, maxPageChars);
    }

    private static String renderTextPage(String address, String type, String value, int limit,
        int offset, boolean fenced, int maxPageChars)
    {
        int total = value.length();
        int from = DcsXmlCodec.safeStart(value, Math.min(offset, total));
        long requestedEnd = (long)from + Math.max(1, limit);
        int boundedEnd = DcsXmlCodec.safeEndAtOrBefore(value, from,
            (int)Math.min(total, requestedEnd));
        if (boundedEnd == from && from < total)
        {
            boundedEnd = DcsXmlCodec.nextBoundary(value, from);
        }
        PageBoundaries boundaries = new PageBoundaries()
        {
            @Override
            public int atOrBefore(int start, int candidate)
            {
                return DcsXmlCodec.safeEndAtOrBefore(value, start, candidate);
            }

            @Override
            public int next(int start)
            {
                return DcsXmlCodec.nextBoundary(value, start);
            }
        };
        return fitBoundedPage(from, boundedEnd, total, maxPageChars, boundaries,
            (end, stoppedBy) -> renderTextCandidate(address, type, value, from, end,
                fenced, stoppedBy));
    }

    private static String renderTextCandidate(String address, String type, String value,
        int from, int to, boolean fenced, PageStop stoppedBy)
    {
        int total = value.length();
        String page = value.substring(from, to);
        StringBuilder result = new StringBuilder("# DCS value: ").append(type).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Address:** `").append(address).append("`\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Characters:** ").append(total)
            .append(Pagination.truncationNotice(page.length(), total)).append("\n\n") //$NON-NLS-1$
            .append("**Page characters:** ").append(page.length()).append("\n\n") //$NON-NLS-1$
            .append("**Offset:** ").append(from).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Next offset:** ").append(to < total ? Integer.toString(to) : "none") //$NON-NLS-1$ //$NON-NLS-2$
            .append("\n\n**Stopped by:** ").append(stoppedBy.label) //$NON-NLS-1$
            .append("\n\n## Value\n\n"); //$NON-NLS-1$
        if (fenced)
        {
            appendFenced(result, page);
        }
        else
        {
            result.append(page);
            if (!page.endsWith("\n")) //$NON-NLS-1$
            {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private static String fitBoundedPage(int from, int requestedEnd, int total,
        int maxPageChars, PageBoundaries boundaries, PageCandidate renderer)
    {
        PageStop requestedStop = requestedEnd >= total ? PageStop.COMPLETE : PageStop.LIMIT;
        String requested = renderer.render(requestedEnd, requestedStop);
        if (requested.length() <= maxPageChars)
        {
            return requested;
        }
        if (from >= total)
        {
            throw new IllegalStateException("The fixed DCS page envelope exceeds the output budget"); //$NON-NLS-1$
        }

        int minimumEnd = boundaries.next(from);
        String minimum = renderer.render(minimumEnd, PageStop.BUDGET);
        if (minimum.length() > maxPageChars)
        {
            throw new IllegalStateException(
                "One DCS page item cannot fit the serialized-character output budget"); //$NON-NLS-1$
        }

        int bestEnd = minimumEnd;
        String best = minimum;
        int low = minimumEnd + 1;
        int high = requestedEnd - 1;
        while (low <= high)
        {
            int midpoint = low + (high - low) / 2;
            int candidateEnd = boundaries.atOrBefore(from, midpoint);
            if (candidateEnd < minimumEnd)
            {
                low = midpoint + 1;
                continue;
            }
            String candidate = renderer.render(candidateEnd, PageStop.BUDGET);
            if (candidate.length() <= maxPageChars)
            {
                bestEnd = candidateEnd;
                best = candidate;
                low = midpoint + 1;
            }
            else
            {
                high = midpoint - 1;
            }
        }
        String fitted = renderer.render(bestEnd, PageStop.BUDGET);
        if (fitted.length() > maxPageChars)
        {
            throw new IllegalStateException("Measured DCS page exceeds the output budget"); //$NON-NLS-1$
        }
        return fitted.equals(best) ? best : fitted;
    }

    private static NodeResolution resolvePointer(String rootFqn, EObject root, List<String> segments)
    {
        Object current = root;
        EObject owner = null;
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
            List<DataSetField> virtualFields = fieldChildren(object, segment);
            if (virtualFields != null)
            {
                owner = object;
                currentAddress = child(currentAddress, "fields"); //$NON-NLS-1$
                collectionName = "fields"; //$NON-NLS-1$
                if (i + 1 >= segments.size())
                {
                    return NodeResolution.success(new NodeRef(virtualFields, currentAddress,
                        collectionName, object, nodeRefs(currentAddress, object, collectionName,
                            virtualFields)));
                }
                String fieldSelector = segments.get(++i);
                int selected = find(virtualFields, collectionName, object, fieldSelector);
                if (selected < 0)
                {
                    return failedSegment(fieldSelector, currentAddress,
                        selectors(virtualFields, collectionName, object));
                }
                current = virtualFields.get(selected);
                currentAddress = child(currentAddress, selector(collectionName, object,
                    (EObject)current, selected));
                continue;
            }
            String modelName = modelFeature(object, segment);
            EStructuralFeature feature = object.eClass().getEStructuralFeature(modelName);
            if (feature == null)
            {
                return failedSegment(segment, currentAddress, navigationKeys(object));
            }
            Object value = object.eGet(feature);
            if (object instanceof DataSet && "fields".equals(segment)) //$NON-NLS-1$
            {
                value = DcsFieldFolders.children((DataSet)object, null);
            }
            owner = object;
            currentAddress = child(currentAddress, canonicalFeature(object, feature.getName()));
            collectionName = canonicalFeature(object, feature.getName());
            if (!feature.isMany())
            {
                if (value == null)
                {
                    if (feature instanceof EAttribute && i + 1 >= segments.size())
                    {
                        current = null;
                        continue;
                    }
                    return unsetFeature(segment, parentAddress(currentAddress), currentAddress);
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
                    object, nodeRefs(currentAddress, object, collectionName, list)));
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
            owner, Collections.<NodeRef> emptyList()));
    }

    private static NodeResolution failedSegment(String segment, String address, List<String> existing)
    {
        String available = boundedExisting(existing);
        return NodeResolution.failure("Pointer segment '" + segment + "' could not be resolved at '" //$NON-NLS-1$ //$NON-NLS-2$
            + address + "'. Existing keys/indices at that level: " + available //$NON-NLS-1$
            + ". Copy one of those into the address, or get its parent collection first."); //$NON-NLS-1$
    }

    private static NodeResolution unsetFeature(String segment, String ownerAddress,
        String featureAddress)
    {
        return NodeResolution.failure("Pointer segment '" + segment + "' names a feature on '" //$NON-NLS-1$ //$NON-NLS-2$
            + ownerAddress + "', but that feature is not set. Create it with action='upsert' at '" //$NON-NLS-1$
            + featureAddress + "', then retry the read."); //$NON-NLS-1$
    }

    private static String boundedExisting(List<String> existing)
    {
        if (existing.isEmpty())
        {
            return "none"; //$NON-NLS-1$
        }
        int shown = Math.min(ERROR_KEY_LIMIT, existing.size());
        String available = String.join(", ", existing.subList(0, shown)); //$NON-NLS-1$
        int remaining = existing.size() - shown;
        return remaining == 0 ? available : available + ", ... (" + remaining + " more)"; //$NON-NLS-1$ //$NON-NLS-2$
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
        if (object instanceof DataCompositionSchemaDataSetFieldFolder)
        {
            result.add("fields"); //$NON-NLS-1$
        }
        for (EReference reference : object.eClass().getEAllReferences())
        {
            if (reference.isContainment())
            {
                result.add(canonicalFeature(object, reference.getName()));
            }
        }
        return result;
    }

    private static List<DataSetField> fieldChildren(EObject owner, String featureName)
    {
        if (!"fields".equals(featureName)) return null; //$NON-NLS-1$
        if (owner instanceof DataSet)
        {
            return DcsFieldFolders.children((DataSet)owner, null);
        }
        if (owner instanceof DataCompositionSchemaDataSetFieldFolder
            && owner.eContainer() instanceof DataSet)
        {
            return DcsFieldFolders.children((DataSet)owner.eContainer(),
                (DataCompositionSchemaDataSetFieldFolder)owner);
        }
        return null;
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
                    collection, owner, Collections.<NodeRef> emptyList()));
            }
        }
        return result;
    }

    private static String renderFullNode(NodeRef node, String language)
    {
        if (!(node.value instanceof EObject))
        {
            return "# DCS value\n\n" //$NON-NLS-1$
                + MarkdownUtils.escapeMarkdown(displayValue(node.value, language)) + '\n';
        }
        EObject object = (EObject)node.value;
        if (isChart(object))
        {
            return "# Existing DCS chart\n\n" //$NON-NLS-1$
                + "This chart is visible read-only; chart authoring is unsupported.\n"; //$NON-NLS-1$
        }
        if (isNestedDataSet(object))
        {
            return "# Existing DCS nested data set\n\n" //$NON-NLS-1$
                + "This nested data set is visible read-only; nested-data-set authoring is unsupported.\n"; //$NON-NLS-1$
        }
        StringBuilder result = new StringBuilder("# DCS node: ").append(itemKind(object)) //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$

        if (object instanceof DataCompositionSettings)
        {
            result.append(renderSettingsOutline(node.address, (DataCompositionSettings)object, language));
            return result.toString();
        }
        appendScalarTable(result, object, language, FEATURE_QUERY, FEATURE_QUERY_TEXT);
        appendExplicitEmptyStrings(result, object);
        appendQuerySummary(result, object, node.address);
        if (object instanceof DataSet)
        {
            appendFieldsTable(result, (DataSet)object, node.address, language);
        }
        else if (object instanceof DataCompositionSchemaDataSetFieldFolder
            && object.eContainer() instanceof DataSet)
        {
            appendContainedOutline(result, object, node.address, language);
            appendFolderFieldsTable(result, (DataSet)object.eContainer(),
                (DataCompositionSchemaDataSetFieldFolder)object, node.address, language);
        }
        else if ("SettingsVariant".equals(object.eClass().getName())) //$NON-NLS-1$
        {
            EObject presentation = asEObject(featureValue(object, "presentation")); //$NON-NLS-1$
            if (presentation != null)
            {
                result.append("## Presentation\n\n"); //$NON-NLS-1$
                String resolved = presentationFeature(object, "presentation", language); //$NON-NLS-1$
                if (!resolved.isEmpty())
                {
                    result.append("**Resolved value:** ") //$NON-NLS-1$
                        .append(MarkdownUtils.escapeMarkdown(resolved)).append("\n\n"); //$NON-NLS-1$
                }
                appendObjectOutline(result, presentation, child(node.address, "presentation"), 0, //$NON-NLS-1$
                    language);
                result.append('\n');
            }
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
            if (!skip.contains(attribute.getName()) && !isExplicitEmptyString(object, attribute))
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

    private static void appendQuerySummary(StringBuilder result, EObject object, String address)
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
        String type = FEATURE_QUERY.equals(featureName) ? "dataSet" : TYPE_DYNAMIC_LIST; //$NON-NLS-1$
        result.append("## Query text\n\n") //$NON-NLS-1$
            .append("**Characters:** ").append(query.length()).append("\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Address:** `").append(child(address, featureName)).append("`\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("_Query text is omitted from this node page; read the address above with ") //$NON-NLS-1$
            .append("type='").append(type).append("' and character limit/offset._\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
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
            result.append(MarkdownUtils.tableRow(dataPath, stringFeature(field, "field"), //$NON-NLS-1$
                presentationFeature(field, "title", language), itemKind(field), //$NON-NLS-1$
                fieldAddress(dataSet, field, collection)));
        }
        result.append('\n');
    }

    /**
     * The writer deliberately accepts empty strings for these required authoring members. EMF/XML
     * may report an empty default as unset after re-load, but omitting it from the typed page would
     * make the value impossible to copy back into a valid write body. Render it literally as an
     * empty value; non-empty query text retains the normal omission/paging policy.
     */
    private static void appendExplicitEmptyStrings(StringBuilder result, EObject object)
    {
        String featureName = explicitEmptyStringFeature(object);
        if (featureName == null)
        {
            return;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || !"".equals(object.eGet(feature))) //$NON-NLS-1$
        {
            return;
        }
        result.append("## Explicit empty strings\n\n- ").append(featureName) //$NON-NLS-1$
            .append(": \n\n"); //$NON-NLS-1$
    }

    private static boolean isExplicitEmptyString(EObject object, EAttribute attribute)
    {
        String featureName = explicitEmptyStringFeature(object);
        return featureName != null && featureName.equals(attribute.getName())
            && "".equals(object.eGet(attribute)); //$NON-NLS-1$
    }

    private static String explicitEmptyStringFeature(EObject object)
    {
        if (object instanceof DataCompositionSchemaCalculatedField)
        {
            return "expression"; //$NON-NLS-1$
        }
        if (object instanceof DataCompositionSchemaDataSetObject)
        {
            return "objectName"; //$NON-NLS-1$
        }
        if (object instanceof DataCompositionSchemaDataSetQuery)
        {
            return FEATURE_QUERY;
        }
        return null;
    }

    private static void appendFolderFieldsTable(StringBuilder result, DataSet dataSet,
        DataCompositionSchemaDataSetFieldFolder folder, String address, String language)
    {
        List<DataSetField> fields = DcsFieldFolders.children(dataSet, folder);
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
            result.append(MarkdownUtils.tableRow(stringFeature(field, "dataPath"), //$NON-NLS-1$
                stringFeature(field, "field"), presentationFeature(field, "title", language), //$NON-NLS-1$ //$NON-NLS-2$
                itemKind(field), child(collection, selector("fields", folder, field, i)))); //$NON-NLS-1$
        }
        result.append('\n');
    }

    private static String fieldAddress(DataSet dataSet, DataSetField field,
        String rootCollectionAddress)
    {
        String address = rootCollectionAddress;
        DataCompositionSchemaDataSetFieldFolder parent = null;
        for (DataCompositionSchemaDataSetFieldFolder ancestor :
            DcsFieldFolders.ancestors(dataSet, field))
        {
            List<DataSetField> siblings = DcsFieldFolders.children(dataSet, parent);
            EObject owner = parent == null ? dataSet : parent;
            address = child(address, selector("fields", owner, ancestor, //$NON-NLS-1$
                siblings.indexOf(ancestor)));
            address = child(address, "fields"); //$NON-NLS-1$
            parent = ancestor;
        }
        List<DataSetField> siblings = DcsFieldFolders.children(dataSet, parent);
        EObject owner = parent == null ? dataSet : parent;
        return child(address, selector("fields", owner, field, siblings.indexOf(field))); //$NON-NLS-1$
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
        if (isNestedDataSet(object))
        {
            result.append("- DataCompositionSchemaNestedDataSet — `").append(address) //$NON-NLS-1$
                .append("` — read-only; nested-data-set authoring is unsupported.\n"); //$NON-NLS-1$
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
            String feature = canonicalFeature(object, reference.getName());
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
            result.add(new NodeRef(object, address, FEATURE_ITEMS, null,
                Collections.<NodeRef> emptyList()));
        }
        for (EReference reference : object.eClass().getEAllContainments())
        {
            Object value = object.eGet(reference);
            String feature = canonicalFeature(object, reference.getName());
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
            String type = collectionType(node.collection);
            return type == null && node.owner != null ? typeOf(node.owner) : type;
        }
        if (!(node.value instanceof EObject))
        {
            String ownerType = node.owner == null ? null : typeOf(node.owner);
            if (TYPE_DYNAMIC_LIST.equals(ownerType))
            {
                return TYPE_DYNAMIC_LIST;
            }
            if ("dataSet".equals(ownerType) && FEATURE_QUERY.equals(node.collection)) //$NON-NLS-1$
            {
                return "dataSet"; //$NON-NLS-1$
            }
            return "userSettings"; //$NON-NLS-1$
        }
        String type = typeOf((EObject)node.value, node.owner);
        return type == null ? collectionType(node.collection) : type;
    }

    static String typeOf(EObject object, EObject owner)
    {
        if (object == null) return null;
        String type = typeOf(object);
        if (type != null || owner == null
            || !"SettingsParameterValue".equals(object.eClass().getName())) //$NON-NLS-1$
        {
            return type;
        }
        String ownerType = typeOf(owner);
        return "dataParameter".equals(ownerType) || "outputParameter".equals(ownerType) //$NON-NLS-1$ //$NON-NLS-2$
            ? ownerType : null;
    }

    static String typeOf(EObject object)
    {
        if (object instanceof DataCompositionSchema)
        {
            return TYPE_SCHEMA;
        }
        if (object instanceof DataCompositionSchemaDataSetLink)
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
        if (object instanceof DataCompositionSchemaDataSetFieldFolder)
        {
            return "fieldFolder"; //$NON-NLS-1$
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
        if (name.contains("Appearance")) //$NON-NLS-1$
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
        return null;
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
            case "dataSetLinks": //$NON-NLS-1$
                return TYPE_SCHEMA;
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
            case "additionalProperties": //$NON-NLS-1$
                return "userSettings"; //$NON-NLS-1$
            default:
                return null;
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
        boolean unionItems = FEATURE_ITEMS.equals(collection)
            && owner instanceof DataCompositionSchemaDataSetUnion;
        if (NATURAL_NAME_COLLECTIONS.contains(collection) || unionItems)
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

    private static boolean isStructureCollection(NodeRef node)
    {
        return node.value instanceof List<?> && FEATURE_ITEMS.equals(node.collection)
            && node.owner instanceof DataCompositionSettings;
    }

    private static boolean isStructureKind(String type)
    {
        return "grouping".equals(type) || "table".equals(type); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Result structureCollectionTypeMismatch(String requested, String address)
    {
        return Result.failure("Type '" + requested + "' does not match structure collection '" //$NON-NLS-1$ //$NON-NLS-2$
            + address + "' for a read. It is polymorphic (groupings and tables), so read this " //$NON-NLS-1$
            + "same address with type='userSettings'; read one structure item by its own type at '" //$NON-NLS-1$
            + address + "/<index>'. For a write, type='" + requested //$NON-NLS-1$
            + "' is accepted at this same address because the write type describes the body, " //$NON-NLS-1$
            + "not the collection target."); //$NON-NLS-1$
    }

    private static Result settingsStructureTypeMismatch(String requested, String address)
    {
        String collection = address + "/items"; //$NON-NLS-1$
        return Result.failure("Type '" + requested + "' does not match settings target '" //$NON-NLS-1$ //$NON-NLS-2$
            + address + "' for a read (its type is 'userSettings'). Its structure collection is '" //$NON-NLS-1$
            + collection + "' and reads with type='userSettings'; read one structure item by its " //$NON-NLS-1$
            + "own type at '" + collection + "/<index>'. For a write, type='" + requested //$NON-NLS-1$ //$NON-NLS-2$
            + "' is accepted at this same settings address because the write type describes the " //$NON-NLS-1$
            + "body, not the settings target."); //$NON-NLS-1$
    }

    private static String boundedTableCell(String value, String address)
    {
        if (value == null || value.length() <= MAX_TABLE_CELL_CHARS)
        {
            return value;
        }
        String suffix = "… (" + value.length() + " characters; read `" + address + "`)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int end = DcsXmlCodec.safeEndAtOrBefore(value, 0,
            Math.max(0, MAX_TABLE_CELL_CHARS - suffix.length()));
        return value.substring(0, end) + suffix;
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
        if (presentation instanceof Presentation)
        {
            Presentation typed = (Presentation)presentation;
            LocalString localized = typed.getLocalValue();
            if (localized != null)
            {
                String value = MetadataLanguageUtils.getSynonymForLanguage(
                    localized.getContent().map(), language);
                if (!value.isEmpty())
                {
                    return value;
                }
            }
            return typed.getValue() == null ? "" : typed.getValue(); //$NON-NLS-1$
        }
        String neutral = stringFeature(presentation, "value"); //$NON-NLS-1$
        EObject local = asEObject(featureValue(presentation, "localValue")); //$NON-NLS-1$
        Object content = featureValue(local, "content"); //$NON-NLS-1$
        Object map = content instanceof Map<?, ?> ? content
            : content instanceof EObject ? featureValue((EObject)content, "map") : null; //$NON-NLS-1$
        if (map instanceof Map<?, ?>)
        {
            String localized = localizedText((Map<?, ?>)map, language);
            if (!localized.isEmpty())
            {
                return localized;
            }
        }
        return neutral;
    }

    private static String localizedText(Map<?, ?> content, String language)
    {
        Map<String, String> localized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : content.entrySet())
        {
            if (entry.getKey() != null && entry.getValue() != null)
            {
                localized.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return MetadataLanguageUtils.getSynonymForLanguage(localized, language);
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
        EStructuralFeature feature = object.eClass().getEStructuralFeature(modelFeature(object, name));
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
        return value instanceof EObject && DcsUnsupportedAuthoring.CHART_CLASS
            .equals(((EObject)value).eClass().getName());
    }

    private static boolean isNestedDataSet(Object value)
    {
        return value instanceof EObject && DcsUnsupportedAuthoring.NESTED_DATA_SET_CLASS
            .equals(((EObject)value).eClass().getName());
    }

    private static String canonicalFeature(EObject owner, String modelName)
    {
        return canonicalFeature(owner, null, modelName);
    }

    private static String canonicalFeature(EObject owner,
        Class<? extends EObject> resolvedOwnerType, String modelName)
    {
        for (FeatureAlias alias : FEATURE_ALIASES)
        {
            if (alias.matches(owner, resolvedOwnerType) && alias.modelName.equals(modelName))
            {
                return alias.canonicalName;
            }
        }
        return modelName;
    }

    private static String modelFeature(EObject owner, String canonicalName)
    {
        for (FeatureAlias alias : FEATURE_ALIASES)
        {
            if (alias.ownerType.isInstance(owner) && alias.canonicalName.equals(canonicalName))
            {
                return alias.modelName;
            }
        }
        return canonicalName;
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

    @FunctionalInterface
    private interface PageCandidate
    {
        String render(int end, PageStop stoppedBy);
    }

    private interface PageBoundaries
    {
        int atOrBefore(int start, int candidate);

        int next(int start);
    }

    private enum PageStop
    {
        COMPLETE("end of content"), //$NON-NLS-1$
        LIMIT("requested limit"), //$NON-NLS-1$
        BUDGET("serialized character budget"); //$NON-NLS-1$

        final String label;

        PageStop(String label)
        {
            this.label = label;
        }
    }

    private static final class FeatureAlias
    {
        final Class<? extends EObject> ownerType;
        final String canonicalName;
        final String modelName;

        FeatureAlias(Class<? extends EObject> ownerType, String canonicalName, String modelName)
        {
            this.ownerType = ownerType;
            this.canonicalName = canonicalName;
            this.modelName = modelName;
        }

        boolean matches(EObject owner, Class<? extends EObject> resolvedOwnerType)
        {
            return owner != null ? ownerType.isInstance(owner)
                : resolvedOwnerType != null && ownerType.isAssignableFrom(resolvedOwnerType);
        }
    }

    private static final class SummarySection
    {
        final String title;
        final String header;
        final List<String> rows;

        SummarySection(String title, String header, List<String> rows)
        {
            this.title = title;
            this.header = header;
            this.rows = rows;
        }
    }

    private static final class SummaryRow
    {
        final SummarySection section;
        final String markdown;

        SummaryRow(SummarySection section, String markdown)
        {
            this.section = section;
            this.markdown = markdown;
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
        final EObject owner;
        final List<NodeRef> items;

        NodeRef(Object value, String address, String collection, EObject owner, List<NodeRef> items)
        {
            this.value = value;
            this.address = address;
            this.collection = collection;
            this.owner = owner;
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

        static CollectionRef failure(String error)
        {
            return new CollectionRef(null, Collections.<NodeRef> emptyList(), error);
        }
    }

    static final class OptionsNode
    {
        final Object value;
        final EObject owner;
        final String actualType;
        final String error;

        private OptionsNode(Object value, EObject owner, String actualType, String error)
        {
            this.value = value;
            this.owner = owner;
            this.actualType = actualType;
            this.error = error;
        }

        static OptionsNode success(Object value, EObject owner, String actualType)
        {
            return new OptionsNode(value, owner, actualType, null);
        }

        static OptionsNode failure(String error)
        {
            return new OptionsNode(null, null, null, error);
        }

        boolean isSuccess()
        {
            return error == null;
        }
    }
}
