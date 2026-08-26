/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.common.DataCompositionDataSetFieldRole;
import com._1c.g5.v8.dt.dcs.model.common.DataCompositionOrderExpression;
import com._1c.g5.v8.dt.dcs.model.common.DataCompositionPeriodType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterUse;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionSortDirection;
import com._1c.g5.v8.dt.dcs.model.core.InputParameters;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSource;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaFieldUseRestriction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.AvailableValue;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Authors the CONTENT of a 1C Data Composition Schema (\u0421\u041a\u0414 / a {@code .dcs}
 * resource) - the {@link DataCompositionSchema} model behind a Report's Data Composition Schema
 * {@code BasicTemplate} - from the structured schema body a client passes to the {@code dcs} tool.
 * The writer is a pure, typed EMF transformation: it takes an already-resolved
 * {@link DataCompositionSchema} (reached by the caller inside a BM boundary from
 * {@code BasicTemplate.getTemplate()}) and applies a parsed spec of data sources / query data sets (with
 * their fields) / schema parameters using the TYPED DCS API (the schema / core / common
 * {@code DcsFactory} singletons) - never a reflective {@code eSet}, never opening a transaction or
 * force-exporting (the caller owns both, exactly like {@link SpreadsheetTemplateWriter}).
 *
 * <p>The schema body shape:</p>
 *
 * <pre>
 * {
 *   "dataSources": [ {"name":"DataSource1", "type":"Local"} ],           // optional
 *   "dataSets": [
 *     { "name":"DataSet1", "type":"query", "query":"SELECT ...",
 *       "dataSource":"DataSource1", "autoFillFields":true,
 *       "fields": [ {"dataPath":"Goods", "field":"Goods", "title":"Goods",
 *                    "role":{"dimension":true}} ] }
 *   ],
 *   "parameters": [ {"name":"Period", "valueType":{types:[{kind:'Date'}]},
 *                    "title":"Period", "use":"Auto"} ],
 *   "calculatedFields": [ {"dataPath":"Margin", "expression":"Revenue - Cost", "title":"Margin"} ]
 * }
 * </pre>
 *
 * <p>Model shape (all typed, no DOM):</p>
 * <ul>
 * <li>{@link DataCompositionSchema#getDataSources()} holds {@link DataCompositionSchemaDataSource}s
 * (a {@code name} + a {@code dataSourceType}, {@code "Local"} for the current infobase). Every query data
 * set references a data source by name, so the writer ENSURES a data source exists for each referenced
 * name (auto-creating a default {@code "Local"} source when the payload declares none).</li>
 * <li>{@link DataCompositionSchema#getDataSets()} holds {@link DataSet}s; v1 authors a
 * {@link DataCompositionSchemaDataSetQuery} ({@code name} + {@code query} text + {@code dataSource} +
 * {@code autoFillAvailableFields}). {@code autoFillAvailableFields} defaults to {@code true} when no
 * explicit {@code fields} are given (EDT derives the fields from the query) and to {@code false} when the
 * caller lists explicit fields, unless {@code autoFillFields} overrides it.</li>
 * <li>A data set's {@link DataSet#getFields()} holds {@link DataCompositionSchemaDataSetField}s
 * ({@code dataPath} = the available-field path exposed to settings, {@code field} = the source query
 * column - defaulted to {@code dataPath} - an optional {@code title} {@link Presentation}, and an optional
 * structured {@code role}).</li>
 * <li>{@link DataCompositionSchema#getParameters()} holds {@link DataCompositionSchemaParameter}s
 * ({@code name} + an optional mcore {@link TypeDescription} value type + an optional {@code title}
 * {@link Presentation} + an optional {@link DataCompositionParameterUse use}).</li>
 * <li>{@link DataCompositionSchema#getCalculatedFields()} holds {@link DataCompositionSchemaCalculatedField}s
 * ({@code dataPath} = the available-field path exposed to settings, {@code expression} = the 1C
 * expression computed from the OTHER available fields (e.g. two other dataset fields), and an optional
 * {@code title} {@link Presentation}). Found-or-updated by {@code dataPath}, exactly like a data set
 * field: a repeated {@code calculatedFields} entry for the same {@code dataPath} UPDATES its
 * {@code expression} / {@code title} in place rather than adding a duplicate.</li>
 * </ul>
 *
 * <p>A {@code title} is a core {@link Presentation}: a plain JSON string sets its language-neutral
 * {@link Presentation#setValue(String) value}; a JSON object {@code {"ru":"...","en":"..."}} populates a
 * localized {@link LocalString} keyed by language code.</p>
 *
 * <p>A parameter's value type needs the platform type provider (primitive proxies) and the configuration
 * (reference targets), which are NOT headless - so the writer stays pure by delegating type building to a
 * {@link TypeResolver} the caller supplies (wrapping {@link MetadataTypeBuilder#build} with its
 * {@code config} / {@code version}). All parameter value types are resolved up front, before any model
 * mutation, so a bad type spec mutates nothing.</p>
 *
 * <p>The whole spec is PARSED + VALIDATED up front ({@link #parse(JsonObject)}) - required names, the data
 * set {@code type}, every enum ({@code use}, a role's {@code periodType}) resolved by literal name
 * (case-insensitive, with an actionable error naming the valid tokens) - so a malformed spec fails before
 * ANY model mutation. Parsing is pure (no DCS factory, no model) and separately unit-testable; only
 * {@link #apply} touches the model. A rejected spec leaves the schema untouched and reports a ready
 * {@link ToolResult#error} JSON string (which the calling tool returns verbatim, rolling its write
 * transaction back).</p>
 */
public final class DcsWriter
{
    // ---- top-level spec keys ------------------------------------------------------------------

    private static final String KEY_DATA_SOURCES = "dataSources"; //$NON-NLS-1$
    private static final String KEY_DATA_SETS = "dataSets"; //$NON-NLS-1$
    private static final String KEY_PARAMETERS = "parameters"; //$NON-NLS-1$
    private static final String KEY_CALCULATED_FIELDS = "calculatedFields"; //$NON-NLS-1$
    private static final String KEY_TOTAL_FIELDS = "totalFields"; //$NON-NLS-1$
    private static final String KEY_DATA_SET_LINKS = "dataSetLinks"; //$NON-NLS-1$

    // ---- per-entry keys -----------------------------------------------------------------------

    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_QUERY = "query"; //$NON-NLS-1$
    private static final String KEY_DATA_SOURCE = "dataSource"; //$NON-NLS-1$
    private static final String KEY_AUTO_FILL = "autoFillFields"; //$NON-NLS-1$
    private static final String KEY_FIELDS = "fields"; //$NON-NLS-1$
    private static final String KEY_DATA_PATH = "dataPath"; //$NON-NLS-1$
    private static final String KEY_FIELD = "field"; //$NON-NLS-1$
    private static final String KEY_EXPRESSION = "expression"; //$NON-NLS-1$
    private static final String KEY_TITLE = "title"; //$NON-NLS-1$
    private static final String KEY_ROLE = "role"; //$NON-NLS-1$
    private static final String KEY_VALUE_TYPE = "valueType"; //$NON-NLS-1$
    private static final String KEY_USE = "use"; //$NON-NLS-1$
    private static final String KEY_CONNECTION_STRING = "connectionString"; //$NON-NLS-1$
    private static final String KEY_USE_RESTRICTION = "useRestriction"; //$NON-NLS-1$
    private static final String KEY_GROUPS = "groups"; //$NON-NLS-1$
    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$
    private static final String KEY_OBJECT_NAME = "objectName"; //$NON-NLS-1$
    private static final String KEY_SOURCE_DATA_SET = "sourceDataSet"; //$NON-NLS-1$
    private static final String KEY_DESTINATION_DATA_SET = "destinationDataSet"; //$NON-NLS-1$
    private static final String KEY_SOURCE_EXPRESSION = "sourceExpression"; //$NON-NLS-1$
    private static final String KEY_DESTINATION_EXPRESSION = "destinationExpression"; //$NON-NLS-1$
    private static final String KEY_PARAMETER = "parameter"; //$NON-NLS-1$
    private static final String KEY_PARAMETER_LIST_ALLOWED = "parameterListAllowed"; //$NON-NLS-1$
    private static final String KEY_LINK_CONDITION = "linkCondition"; //$NON-NLS-1$
    private static final String KEY_START_EXPRESSION = "startExpression"; //$NON-NLS-1$
    private static final String KEY_REQUIRED = "required"; //$NON-NLS-1$
    private static final String KEY_APPEARANCE = "appearance"; //$NON-NLS-1$
    private static final String KEY_ATTRIBUTE_USE_RESTRICTION = "attributeUseRestriction"; //$NON-NLS-1$
    private static final String KEY_PRESENTATION_EXPRESSION = "presentationExpression"; //$NON-NLS-1$
    private static final String KEY_ORDER_EXPRESSIONS = "orderExpressions"; //$NON-NLS-1$
    private static final String KEY_ORDER_EXPRESSION = "orderExpression"; //$NON-NLS-1$
    private static final String KEY_IN_HIERARCHY_DATA_SET = "inHierarchyDataSet"; //$NON-NLS-1$
    private static final String KEY_IN_HIERARCHY_DATA_SET_PARAMETER = "inHierarchyDataSetParameter"; //$NON-NLS-1$
    private static final String KEY_AVAILABLE_VALUES = "availableValues"; //$NON-NLS-1$
    private static final String KEY_INPUT_PARAMETERS = "inputParameters"; //$NON-NLS-1$
    private static final String KEY_VALUES = "values"; //$NON-NLS-1$
    private static final String KEY_PRESENTATION = "presentation"; //$NON-NLS-1$
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$
    private static final String KEY_USE_RESTRICTION_FLAG = "useRestriction"; //$NON-NLS-1$
    private static final String KEY_VALUE_LIST_ALLOWED = "valueListAllowed"; //$NON-NLS-1$
    private static final String KEY_AVAILABLE_AS_FIELD = "availableAsField"; //$NON-NLS-1$
    private static final String KEY_DENY_INCOMPLETE_VALUES = "denyIncompleteValues"; //$NON-NLS-1$
    private static final String KEY_FUNCTIONAL_OPTIONS_PARAMETER = "functionalOptionsParameter"; //$NON-NLS-1$
    private static final String KEY_LINK_CONDITION_EXPRESSION = "linkConditionExpression"; //$NON-NLS-1$
    private static final String KEY_ORDER_TYPE = "orderType"; //$NON-NLS-1$
    private static final String KEY_AUTO_ORDER = "autoOrder"; //$NON-NLS-1$

    // ---- validation error-message stems (java:S1192) --------------------------------------------

    private static final String ERR_DATA_SET = "A data set ("; //$NON-NLS-1$
    private static final String ERR_PARAMETER = "A parameter ("; //$NON-NLS-1$
    private static final String ERR_FIELD_ROLE = "A field role ("; //$NON-NLS-1$
    private static final String ERR_CALCULATED_FIELD = "A calculated field ("; //$NON-NLS-1$
    private static final String ERR_TOTAL_FIELD = "A total field ("; //$NON-NLS-1$
    private static final String ERR_NEEDS_NAME = ") needs a non-empty 'name'."; //$NON-NLS-1$

    // ---- role keys ----------------------------------------------------------------------------

    private static final String ROLE_DIMENSION = "dimension"; //$NON-NLS-1$
    private static final String ROLE_MAIN = "main"; //$NON-NLS-1$
    private static final String ROLE_REQUIRED = "required"; //$NON-NLS-1$
    private static final String ROLE_IGNORE_NULL = "ignoreNullValues"; //$NON-NLS-1$
    private static final String ROLE_DIMENSION_ATTRIBUTE = "dimensionAttribute"; //$NON-NLS-1$
    private static final String ROLE_ACCOUNT = "account"; //$NON-NLS-1$
    private static final String ROLE_BALANCE = "balance"; //$NON-NLS-1$
    private static final String ROLE_PERIOD_TYPE = "periodType"; //$NON-NLS-1$
    private static final String ROLE_PERIOD_NUMBER = "periodNumber"; //$NON-NLS-1$

    private static final String RESTRICTION_FIELD = "field"; //$NON-NLS-1$
    private static final String RESTRICTION_CONDITION = "condition"; //$NON-NLS-1$
    private static final String RESTRICTION_GROUP = "group"; //$NON-NLS-1$
    private static final String RESTRICTION_ORDER = "order"; //$NON-NLS-1$

    /** The only v1-supported data set type token. */
    private static final String TYPE_QUERY = "query"; //$NON-NLS-1$
    private static final String TYPE_OBJECT = "object"; //$NON-NLS-1$
    private static final String TYPE_UNION = "union"; //$NON-NLS-1$

    /**
     * The default data source type: the current infobase. The platform-canonical token is {@code "Local"}
     * (capital L) - EDT's own DCS designer ({@code DcsUiUtil} / {@code DcsNewWizardRelatedModelsFactory})
     * calls {@code setDataSourceType("Local")} and the serializer writes it verbatim (no case
     * normalization), so a query data set only binds to the current infobase when the token matches exactly.
     */
    private static final String LOCAL_SOURCE_TYPE = "Local"; //$NON-NLS-1$

    /** The auto-created default data source name when the payload declares none but a query needs one. */
    private static final String DEFAULT_DATA_SOURCE_NAME = "DataSource1"; //$NON-NLS-1$

    private DcsWriter()
    {
        // Utility class
    }

    // ---- type-building seam -------------------------------------------------------------------

    /**
     * Builds a parameter's mcore {@link TypeDescription} from a {@code valueType} JSON spec. Supplied by
     * the caller (inside its BM boundary, with the resolved {@code Configuration} / platform {@code Version})
     * so the pure writer never touches the platform type provider directly - typically
     * {@code spec -> MetadataTypeBuilder.build(spec, config, version)} adapted to a {@link TypeResolution}.
     */
    @FunctionalInterface
    public interface TypeResolver
    {
        /**
         * Resolves a {@code valueType} spec into an mcore {@link TypeDescription}, or an actionable error.
         *
         * @param valueTypeSpec the {@code valueType} JSON (an object like {@code {types:[{kind:'String'}]}})
         * @return the resolution - exactly one of {@link TypeResolution#typeDescription} /
         *         {@link TypeResolution#error} is non-null
         */
        TypeResolution resolve(JsonElement valueTypeSpec);
    }

    /**
     * Model-aware exceptions to lifecycle requirements enforced by the otherwise shape-only parser.
     * Paths use data-set natural keys, including every containing union, so one legacy object data set
     * cannot relax validation for an unrelated new entry in the same payload.
     */
    static final class DataSetValidationContext
    {
        private final Set<List<String>> missingObjectNameAllowed = new HashSet<>();

        void allowMissingObjectName(List<String> dataSetPath)
        {
            missingObjectNameAllowed.add(new ArrayList<>(dataSetPath));
        }

        boolean allowsMissingObjectName(List<String> dataSetPath)
        {
            return missingObjectNameAllowed.contains(dataSetPath);
        }
    }

    /** The outcome of a {@link TypeResolver}: a built type or a ready error message. */
    public static final class TypeResolution
    {
        /** The built value type, or {@code null} on error. */
        public final TypeDescription typeDescription;
        /** The error message, or {@code null} on success. */
        public final String error;

        private TypeResolution(TypeDescription typeDescription, String error)
        {
            this.typeDescription = typeDescription;
            this.error = error;
        }

        /**
         * A successful resolution.
         *
         * @param typeDescription the built type (may be {@code null} if the resolver chose to skip)
         * @return the resolution
         */
        public static TypeResolution of(TypeDescription typeDescription)
        {
            return new TypeResolution(typeDescription, null);
        }

        /**
         * A failed resolution.
         *
         * @param error the actionable error message (must not be {@code null})
         * @return the resolution
         */
        public static TypeResolution failed(String error)
        {
            return new TypeResolution(null, error);
        }
    }

    // ---- result -------------------------------------------------------------------------------

    /**
     * The outcome of applying a {@code dcs} spec: either an actionable {@link ToolResult#error} JSON string
     * in {@link #error} (a validation / type-resolution failure) or the counts of what was applied. Exactly
     * one of {@code error} / the counts is meaningful; check {@link #hasError()} first.
     */
    public static final class Result
    {
        /** Non-null when the spec was rejected (nothing meaningfully authored): a ready ToolResult.error. */
        public final String error;
        /** Number of data sources applied (declared + any auto-created default). */
        public final int dataSources;
        /** Number of data sets applied. */
        public final int dataSets;
        /** Number of data set fields applied (across all data sets). */
        public final int fields;
        /** Number of schema parameters applied. */
        public final int parameters;
        /** Number of calculated fields applied (created or updated in place). */
        public final int calculatedFields;
        /** Number of total fields applied (created or updated in place). */
        public final int totalFields;

        private Result(String error, int dataSources, int dataSets, int fields, int parameters,
            int calculatedFields, int totalFields)
        {
            this.error = error;
            this.dataSources = dataSources;
            this.dataSets = dataSets;
            this.fields = fields;
            this.parameters = parameters;
            this.calculatedFields = calculatedFields;
            this.totalFields = totalFields;
        }

        static Result failed(String error)
        {
            return new Result(error, 0, 0, 0, 0, 0, 0);
        }

        static Result ok(int dataSources, int dataSets, int fields, int parameters, int calculatedFields,
            int totalFields)
        {
            return new Result(null, dataSources, dataSets, fields, parameters, calculatedFields, totalFields);
        }

        public boolean hasError()
        {
            return error != null;
        }
    }

    // ---- entry point --------------------------------------------------------------------------

    /**
     * Applies a {@code dcs} spec WITHOUT a parameter type resolver - the convenience overload for callers
     * that author only data sources / data sets / fields / parameters that carry NO {@code valueType}. A
     * parameter that DOES declare a {@code valueType} needs the platform type provider + configuration, so
     * it is rejected with an actionable error; use {@link #apply(DataCompositionSchema, JsonObject,
     * TypeResolver)} (passing a {@link MetadataTypeBuilder}-backed resolver) to author typed parameters.
     *
     * @param schema the report's Data Composition Schema content (must not be {@code null})
     * @param spec the {@code dcs} payload (see the class javadoc for the shape)
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    public static Result apply(DataCompositionSchema schema, JsonObject spec)
    {
        return apply(schema, spec, null);
    }

    /**
     * Applies a {@code dcs} spec to the given {@link DataCompositionSchema}. Validates the whole spec up
     * front (so a malformed entry mutates nothing), resolves every parameter value type through
     * {@code typeResolver} before any mutation, then find-or-creates the data sources / query data sets
     * (with their fields) / parameters with the typed DCS API. Does NOT open a transaction and does NOT
     * force-export - the caller ({@code DcsTool}) reaches the schema inside its own BM write
     * boundary and drains it to the {@code .dcs} after this returns.
     *
     * @param schema the report's Data Composition Schema content (must not be {@code null})
     * @param spec the schema body (see the class javadoc for the shape)
     * @param typeResolver builds a parameter's value type from its {@code valueType} spec; may be
     *            {@code null} only when no parameter carries a {@code valueType}
     * @return a {@link Result} - check {@link Result#hasError()} first; {@link Result#error} is a ready
     *         {@link ToolResult#error} JSON string the caller returns verbatim
     */
    public static Result apply(DataCompositionSchema schema, JsonObject spec, TypeResolver typeResolver)
    {
        return apply(schema, spec, typeResolver, null);
    }

    /**
     * Shared parameter-type resolver used by the {@code dcs} tool. It deliberately routes through
     * the one metadata type grammar in {@link MetadataTypeBuilder}.
     */
    public static TypeResolver typeResolver(Configuration configuration, Version version)
    {
        return valueTypeSpec -> {
            if (version == null)
            {
                return TypeResolution.failed(
                    "Cannot resolve the platform version needed to build the parameter type."); //$NON-NLS-1$
            }
            MetadataTypeBuilder.Result result = MetadataTypeBuilder.build(valueTypeSpec, configuration,
                version, false, MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);
            return result.error != null ? TypeResolution.failed(result.error)
                : TypeResolution.of(result.typeDescription);
        };
    }

    /**
     * Applies a DCS schema body with shared configured-language validation for every nested
     * presentation. The language context also records the canonical codes used by the body.
     */
    public static Result apply(DataCompositionSchema schema, JsonObject spec, TypeResolver typeResolver,
        DcsPresentationParser.LanguageContext languages)
    {
        return apply(schema, spec, typeResolver, languages, Version.LATEST, null);
    }

    /** Applies schema members that also need the project's appearance catalogue and named colors. */
    public static Result apply(DataCompositionSchema schema, JsonObject spec, TypeResolver typeResolver,
        DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors)
    {
        return apply(schema, spec, typeResolver, languages, version, namedColors, null);
    }

    static Result apply(DataCompositionSchema schema, JsonObject spec, TypeResolver typeResolver,
        DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors, DataSetValidationContext dataSetValidation)
    {
        if (schema == null)
        {
            return Result.failed(ToolResult.error(
                "The report has no Data Composition Schema content to write to.").toJson()); //$NON-NLS-1$
        }
        ParseResult parsed = parse(spec, languages, dataSetValidation);
        if (parsed.error != null)
        {
            return Result.failed(ToolResult.error(parsed.error).toJson());
        }
        Plan plan = parsed.plan;

        String modelError = validateNaturalKeys(schema, plan);
        if (modelError != null)
        {
            return Result.failed(ToolResult.error(modelError).toJson());
        }

        String memberError = resolveMembers(schema, plan, typeResolver, languages, version,
            namedColors);
        if (memberError != null)
            return Result.failed(ToolResult.error(memberError).toJson());

        int sources = applyDataSets(schema, plan);
        int fields = applyFields(schema, plan);
        applyDataSetLinks(schema, plan);
        int calculatedFields = applyCalculatedFields(schema, plan);
        int totalFields = applyTotalFields(schema, plan);
        for (int i = 0; i < plan.parameters.size(); i++)
        {
            applyParameter(schema, plan.parameters.get(i));
        }

        return Result.ok(sources, plan.dataSets.size(), fields, plan.parameters.size(), calculatedFields,
            totalFields);
    }

    /** Resolves every new member on detached values before any schema mutation. */
    private static String resolveMembers(DataCompositionSchema schema, Plan plan,
        TypeResolver typeResolver, DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors)
    {
        String error = resolveDataSetMembers(schema.getDataSets(), plan.dataSets, typeResolver,
            languages, version, namedColors, KEY_DATA_SETS);
        if (error != null) return error;
        for (int i = 0; i < plan.calculatedFields.size(); i++)
        {
            CalculatedFieldPlan item = plan.calculatedFields.get(i);
            DataCompositionSchemaCalculatedField current = findCalculatedField(schema, item.dataPath);
            item.resolved = new ResolvedMembers();
            error = resolveCommonMembers(item.members, item.resolved,
                current == null ? null : current.getValueType(),
                current == null ? null : current.getAppearance(), typeResolver, languages, version,
                namedColors, KEY_CALCULATED_FIELDS + "[" + i + "]", true); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
            UseRestrictionResult restriction = parseUseRestrictionMember(item.members,
                KEY_USE_RESTRICTION, KEY_CALCULATED_FIELDS + "[" + i + "]", //$NON-NLS-1$ //$NON-NLS-2$
                current == null ? null : current.getUseRestriction());
            if (restriction.error != null) return restriction.error;
            item.resolved.attributeUseRestriction = restriction.plan == null
                ? null : buildUseRestriction(restriction.plan);
            error = resolveOrderExpressions(item.members, KEY_ORDER_EXPRESSION,
                item.resolved, KEY_CALCULATED_FIELDS + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
        }
        for (int i = 0; i < plan.parameters.size(); i++)
        {
            ParameterPlan item = plan.parameters.get(i);
            DataCompositionSchemaParameter current = findParameter(schema, item.name);
            item.resolved = new ResolvedMembers();
            error = resolveCommonMembers(item.members, item.resolved,
                current == null ? null : current.getValueType(), null, typeResolver, languages,
                version, namedColors, KEY_PARAMETERS + "[" + i + "]", false); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
            TypeDescription effectiveType = item.members.has(KEY_VALUE_TYPE)
                ? item.resolved.valueType : current == null ? null : current.getValueType();
            if (item.members.has(KEY_VALUES))
            {
                ValuesResult values = values(item.members.get(KEY_VALUES), effectiveType,
                    languages, KEY_PARAMETERS + "[" + i + "]." + KEY_VALUES); //$NON-NLS-1$ //$NON-NLS-2$
                if (values.error != null) return values.error;
                item.resolved.values = values.values;
            }
            error = resolveParameterScalars(item.members, item.resolved,
                KEY_PARAMETERS + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
        }
        return null;
    }

    private static String resolveDataSetMembers(List<DataSet> existing, List<DataSetPlan> plans,
        TypeResolver typeResolver, DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors, String path)
    {
        for (int i = 0; i < plans.size(); i++)
        {
            DataSetPlan setPlan = plans.get(i);
            DataSet currentSet = findDataSet(existing, setPlan.name);
            for (int j = 0; j < setPlan.fields.size(); j++)
            {
                FieldPlan item = setPlan.fields.get(j);
                DataCompositionSchemaDataSetField current = currentSet == null ? null
                    : findField(currentSet, item.dataPath);
                item.resolved = new ResolvedMembers();
                String itemPath = path + "[" + i + "]." + KEY_FIELDS + "[" + j + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                String error = resolveCommonMembers(item.members, item.resolved,
                    current == null ? null : current.getValueType(),
                    current == null ? null : current.getAppearance(), typeResolver, languages,
                    version, namedColors, itemPath, false);
                if (error != null) return error;
                UseRestrictionResult restriction = parseUseRestrictionMember(item.members,
                    KEY_ATTRIBUTE_USE_RESTRICTION, itemPath,
                    current == null ? null : current.getAttributeUseRestriction());
                if (restriction.error != null) return restriction.error;
                item.resolved.attributeUseRestriction = restriction.plan == null
                    ? null : buildUseRestriction(restriction.plan);
                error = resolveOrderExpressions(item.members, KEY_ORDER_EXPRESSIONS,
                    item.resolved, itemPath);
                if (error != null) return error;
                StringResult hierarchy = optionalString(item.members, KEY_IN_HIERARCHY_DATA_SET,
                    itemPath);
                if (hierarchy.error != null) return hierarchy.error;
                item.resolved.inHierarchyDataSet = hierarchy.value;
                hierarchy = optionalString(item.members, KEY_IN_HIERARCHY_DATA_SET_PARAMETER,
                    itemPath);
                if (hierarchy.error != null) return hierarchy.error;
                item.resolved.inHierarchyDataSetParameter = hierarchy.value;
            }
            if (currentSet instanceof DataCompositionSchemaDataSetUnion)
            {
                String error = resolveDataSetMembers(
                    ((DataCompositionSchemaDataSetUnion)currentSet).getItems(), setPlan.items,
                    typeResolver, languages, version, namedColors,
                    path + "[" + i + "]." + KEY_ITEMS); //$NON-NLS-1$ //$NON-NLS-2$
                if (error != null) return error;
            }
            else if (!setPlan.items.isEmpty())
            {
                String error = resolveDataSetMembers(Collections.<DataSet>emptyList(), setPlan.items,
                    typeResolver, languages, version, namedColors,
                    path + "[" + i + "]." + KEY_ITEMS); //$NON-NLS-1$ //$NON-NLS-2$
                if (error != null) return error;
            }
        }
        return null;
    }

    private static String resolveCommonMembers(JsonObject members, ResolvedMembers resolved,
        TypeDescription currentType, DataCompositionAppearance currentAppearance,
        TypeResolver typeResolver, DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors, String path, boolean calculated)
    {
        if (members.has(KEY_VALUE_TYPE) && !members.get(KEY_VALUE_TYPE).isJsonNull())
        {
            JsonElement spec = members.get(KEY_VALUE_TYPE);
            if (!spec.isJsonObject()) return "Member '" + KEY_VALUE_TYPE + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' must be an object like {types:[{kind:'String'}]}."; //$NON-NLS-1$
            if (typeResolver == null) return "Member '" + KEY_VALUE_TYPE + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' needs the project type resolver; retry through the dcs tool."; //$NON-NLS-1$
            TypeResolution type = typeResolver.resolve(spec);
            if (type.error != null) return "Member '" + KEY_VALUE_TYPE + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' is invalid: " + type.error; //$NON-NLS-1$
            resolved.valueType = type.typeDescription;
        }
        if (members.has(KEY_APPEARANCE))
        {
            JsonElement appearance = members.get(KEY_APPEARANCE);
            if (appearance == null || appearance.isJsonNull())
            {
                resolved.appearance = null;
            }
            else if (!appearance.isJsonObject())
            {
                return "Member '" + KEY_APPEARANCE + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                    + "' must be an object of typed appearance keys, or null to clear it."; //$NON-NLS-1$
            }
            else
            {
                DcsSettingsWriter.AppearanceResult built = DcsSettingsWriter.buildAppearance(
                    appearance.getAsJsonObject(), currentAppearance, languages, version,
                    namedColors, path + "." + KEY_APPEARANCE); //$NON-NLS-1$
                if (built.error != null) return built.error;
                resolved.appearance = built.value;
            }
        }
        StringResult presentation = optionalString(members, KEY_PRESENTATION_EXPRESSION, path);
        if (presentation.error != null) return presentation.error;
        resolved.presentationExpression = presentation.value;
        TypeDescription effectiveType = members.has(KEY_VALUE_TYPE) ? resolved.valueType : currentType;
        if (members.has(KEY_AVAILABLE_VALUES))
        {
            AvailableValuesResult available = availableValues(members.get(KEY_AVAILABLE_VALUES),
                effectiveType, languages, path + "." + KEY_AVAILABLE_VALUES); //$NON-NLS-1$
            if (available.error != null) return available.error;
            resolved.availableValues = available.values;
        }
        if (members.has(KEY_INPUT_PARAMETERS))
        {
            InputParametersResult inputs = inputParameters(members.get(KEY_INPUT_PARAMETERS),
                languages, path + "." + KEY_INPUT_PARAMETERS); //$NON-NLS-1$
            if (inputs.error != null) return inputs.error;
            resolved.inputParameters = inputs.value;
        }
        return null;
    }

    private static String resolveParameterScalars(JsonObject members, ResolvedMembers resolved,
        String path)
    {
        StringResult expression = optionalString(members, KEY_EXPRESSION, path);
        if (expression.error != null) return expression.error;
        resolved.expression = expression.value;
        StringResult functional = optionalString(members, KEY_FUNCTIONAL_OPTIONS_PARAMETER, path);
        if (functional.error != null) return functional.error;
        resolved.functionalOptionsParameter = functional.value;
        BooleanResult flag = optionalBoolean(members, KEY_USE_RESTRICTION_FLAG, path);
        if (flag.error != null) return flag.error;
        resolved.useRestriction = flag.value;
        flag = optionalBoolean(members, KEY_VALUE_LIST_ALLOWED, path);
        if (flag.error != null) return flag.error;
        resolved.valueListAllowed = flag.value;
        flag = optionalBoolean(members, KEY_AVAILABLE_AS_FIELD, path);
        if (flag.error != null) return flag.error;
        resolved.availableAsField = flag.value;
        flag = optionalBoolean(members, KEY_DENY_INCOMPLETE_VALUES, path);
        if (flag.error != null) return flag.error;
        resolved.denyIncompleteValues = flag.value;
        return null;
    }

    private static UseRestrictionResult parseUseRestrictionMember(JsonObject members, String key,
        String path, DataCompositionSchemaFieldUseRestriction current)
    {
        if (!members.has(key) || members.get(key).isJsonNull())
            return UseRestrictionResult.ok(null);
        JsonObject wrapper = new JsonObject();
        JsonElement supplied = members.get(key);
        if (supplied.isJsonObject() && current != null)
        {
            JsonObject merged = supplied.getAsJsonObject().deepCopy();
            if (!merged.has(RESTRICTION_FIELD))
                merged.addProperty(RESTRICTION_FIELD, current.isField());
            if (!merged.has(RESTRICTION_CONDITION))
                merged.addProperty(RESTRICTION_CONDITION, current.isCondition());
            if (!merged.has(RESTRICTION_GROUP))
                merged.addProperty(RESTRICTION_GROUP, current.isGroup());
            if (!merged.has(RESTRICTION_ORDER))
                merged.addProperty(RESTRICTION_ORDER, current.isOrder());
            supplied = merged;
        }
        wrapper.add(KEY_USE_RESTRICTION, supplied);
        return parseUseRestriction(wrapper, path + "." + key); //$NON-NLS-1$
    }

    private static String resolveOrderExpressions(JsonObject members, String key,
        ResolvedMembers resolved, String path)
    {
        if (!members.has(key)) return null;
        JsonElement raw = members.get(key);
        if (raw == null || !raw.isJsonArray())
            return "Member '" + key + "' at '" + path + "' must be an array of " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "{expression, orderType, autoOrder} objects."; //$NON-NLS-1$
        List<DataCompositionOrderExpression> values = new ArrayList<>();
        int index = 0;
        for (JsonElement element : raw.getAsJsonArray())
        {
            String itemPath = path + "." + key + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (element == null || !element.isJsonObject())
                return "Order expression at '" + itemPath + "' must be an object."; //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject item = element.getAsJsonObject();
            String unknown = unknownMembers(item, itemPath, KEY_EXPRESSION, KEY_ORDER_TYPE,
                KEY_AUTO_ORDER);
            if (unknown != null) return unknown;
            StringResult expression = optionalString(item, KEY_EXPRESSION, itemPath);
            if (expression.error != null) return expression.error;
            if (!item.has(KEY_EXPRESSION) || expression.value == null || expression.value.isEmpty())
                return "Order expression at '" + itemPath //$NON-NLS-1$
                    + "' needs a non-empty 'expression'."; //$NON-NLS-1$
            DataCompositionSortDirection direction = null;
            if (item.has(KEY_ORDER_TYPE))
            {
                if (!isStringMember(item, KEY_ORDER_TYPE))
                    return "Order expression member 'orderType' at '" + itemPath //$NON-NLS-1$
                        + "' must be a string naming one of " //$NON-NLS-1$
                        + enumTokens(DataCompositionSortDirection.values()) + "."; //$NON-NLS-1$
                direction = resolveEnum(DataCompositionSortDirection.values(),
                    stringMember(item, KEY_ORDER_TYPE));
                if (direction == null) return "Order expression member 'orderType' at '" //$NON-NLS-1$
                    + itemPath + "' must be one of " //$NON-NLS-1$
                    + enumTokens(DataCompositionSortDirection.values()) + "."; //$NON-NLS-1$
            }
            BooleanResult auto = optionalBoolean(item, KEY_AUTO_ORDER, itemPath);
            if (auto.error != null) return auto.error;
            DataCompositionOrderExpression value =
                com._1c.g5.v8.dt.dcs.model.common.DcsFactory.eINSTANCE
                    .createDataCompositionOrderExpression();
            value.setExpression(expression.value);
            if (direction != null) value.setOrderType(direction);
            if (auto.value != null) value.setAutoOrder(auto.value.booleanValue());
            values.add(value);
            index++;
        }
        resolved.orderExpressions = values;
        return null;
    }

    private static AvailableValuesResult availableValues(JsonElement raw, TypeDescription declared,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (raw == null || !raw.isJsonArray())
            return AvailableValuesResult.failure("Member at '" + path //$NON-NLS-1$
                + "' must be an array of {value, presentation} objects."); //$NON-NLS-1$
        List<AvailableValue> result = new ArrayList<>();
        int index = 0;
        for (JsonElement element : raw.getAsJsonArray())
        {
            String itemPath = path + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            if (element == null || !element.isJsonObject())
                return AvailableValuesResult.failure("Available value at '" + itemPath //$NON-NLS-1$
                    + "' must be an object with 'value' and optional 'presentation'."); //$NON-NLS-1$
            JsonObject item = element.getAsJsonObject();
            String unknown = unknownMembers(item, itemPath, KEY_VALUE, KEY_PRESENTATION);
            if (unknown != null) return AvailableValuesResult.failure(unknown);
            if (!item.has(KEY_VALUE)) return AvailableValuesResult.failure("Available value at '" //$NON-NLS-1$
                + itemPath + "' needs a 'value' ValueSpec."); //$NON-NLS-1$
            ValueBuildResult built = declaredValue(item.get(KEY_VALUE), declared, languages,
                itemPath + "." + KEY_VALUE); //$NON-NLS-1$
            if (built.error != null) return AvailableValuesResult.failure(built.error);
            AvailableValue value = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createAvailableValue();
            value.setValue(built.value);
            if (item.has(KEY_PRESENTATION))
            {
                DcsPresentationParser.ParseResult presentation = DcsPresentationParser.parse(
                    item.get(KEY_PRESENTATION), languages, itemPath + "." + KEY_PRESENTATION); //$NON-NLS-1$
                if (!presentation.isSuccess())
                    return AvailableValuesResult.failure(presentation.error());
                value.setPresentation(DcsPresentationParser.build(presentation.plan()));
            }
            result.add(value);
            index++;
        }
        return AvailableValuesResult.success(result);
    }

    private static ValuesResult values(JsonElement raw, TypeDescription declared,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (raw == null || !raw.isJsonArray())
            return ValuesResult.failure("Member at '" + path + "' must be an array of ValueSpec objects."); //$NON-NLS-1$ //$NON-NLS-2$
        List<Value> result = new ArrayList<>();
        int index = 0;
        for (JsonElement element : raw.getAsJsonArray())
        {
            ValueBuildResult built = declaredValue(element, declared, languages,
                path + "[" + index + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (built.error != null) return ValuesResult.failure(built.error);
            result.add(built.value);
            index++;
        }
        return ValuesResult.success(result);
    }

    private static ValueBuildResult declaredValue(JsonElement raw, TypeDescription declared,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (declared == null)
        {
            DcsSettingsWriter.ValueResult value = DcsSettingsWriter.buildValue(raw, path);
            return value.error == null ? ValueBuildResult.success(value.value)
                : ValueBuildResult.failure(value.error);
        }
        Value expected = expectedValue(declared, raw);
        if (expected == null)
            return ValueBuildResult.failure("Value at '" + path //$NON-NLS-1$
                + "' cannot be mapped to the declared valueType. Supported declared default/available " //$NON-NLS-1$
                + "types are String, Number, Boolean, Date, and Null/Undefined; change the ValueSpec " //$NON-NLS-1$
                + "or remove this member."); //$NON-NLS-1$
        DcsSettingsWriter.ValueResult value = DcsSettingsWriter.buildTypedParameterValue(raw,
            expected, languages, path);
        return value.error == null ? ValueBuildResult.success(value.value)
            : ValueBuildResult.failure(value.error);
    }

    private static Value expectedValue(TypeDescription declared, JsonElement raw)
    {
        String kind = raw != null && raw.isJsonObject()
            ? stringMember(raw.getAsJsonObject(), "kind") : null; //$NON-NLS-1$
        if (kind == null) return null;
        String canonical = kind.toLowerCase(java.util.Locale.ROOT);
        boolean allowed = declaredTypeNames(declared).contains(canonical);
        if (!allowed)
        {
            allowed = "string".equals(canonical) && declared.getStringQualifiers() != null //$NON-NLS-1$
                || "number".equals(canonical) && declared.getNumberQualifiers() != null //$NON-NLS-1$
                || "date".equals(canonical) && declared.getDateQualifiers() != null; //$NON-NLS-1$
        }
        if (!allowed) return null;
        switch (canonical)
        {
            case "string": return McoreFactory.eINSTANCE.createStringValue(); //$NON-NLS-1$
            case "number": return McoreFactory.eINSTANCE.createNumberValue(); //$NON-NLS-1$
            case "boolean": return McoreFactory.eINSTANCE.createBooleanValue(); //$NON-NLS-1$
            case "date": return McoreFactory.eINSTANCE.createDateValue(); //$NON-NLS-1$
            case "null": return McoreFactory.eINSTANCE.createNullValue(); //$NON-NLS-1$
            default: return null;
        }
    }

    private static Set<String> declaredTypeNames(TypeDescription declared)
    {
        Set<String> result = new HashSet<>();
        for (TypeItem type : declared.getTypes())
        {
            String name = type.getName();
            String normalized = name == null && type.eIsProxy()
                ? EcoreUtil.getURI(type).toString().toLowerCase(java.util.Locale.ROOT)
                : name == null ? "" : name.toLowerCase(java.util.Locale.ROOT); //$NON-NLS-1$
            result.add(normalized);
            if (normalized.contains("string")) result.add("string"); //$NON-NLS-1$ //$NON-NLS-2$
            if (normalized.contains("number") || normalized.contains("decimal")) result.add("number"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (normalized.contains("boolean")) result.add("boolean"); //$NON-NLS-1$ //$NON-NLS-2$
            if (normalized.contains("date")) result.add("date"); //$NON-NLS-1$ //$NON-NLS-2$
            if (normalized.contains("null") || normalized.contains("undefined")) result.add("null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return result;
    }

    private static InputParametersResult inputParameters(JsonElement raw,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (raw == null || raw.isJsonNull()) return InputParametersResult.success(null);
        if (!raw.isJsonObject()) return InputParametersResult.failure("Member at '" + path //$NON-NLS-1$
            + "' must be {items:[...]}, or null to clear it."); //$NON-NLS-1$
        JsonObject body = raw.getAsJsonObject();
        String unknown = unknownMembers(body, path, KEY_ITEMS);
        if (unknown != null) return InputParametersResult.failure(unknown);
        if (!body.has(KEY_ITEMS) || !body.get(KEY_ITEMS).isJsonArray())
            return InputParametersResult.failure("Input parameters at '" + path //$NON-NLS-1$
                + "' need an 'items' array."); //$NON-NLS-1$
        InputParameters result = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createInputParameters();
        int index = 0;
        for (JsonElement element : body.getAsJsonArray(KEY_ITEMS))
        {
            String itemPath = path + "." + KEY_ITEMS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            if (element == null || !element.isJsonObject())
                return InputParametersResult.failure("Input parameter at '" + itemPath //$NON-NLS-1$
                    + "' must be an object."); //$NON-NLS-1$
            JsonObject item = element.getAsJsonObject();
            unknown = unknownMembers(item, itemPath, KEY_PARAMETER, KEY_VALUES, KEY_USE);
            if (unknown != null) return InputParametersResult.failure(unknown);
            if (!item.has(KEY_PARAMETER))
                return InputParametersResult.failure("Input parameter at '" + itemPath //$NON-NLS-1$
                    + "' needs a parameter ValueSpec."); //$NON-NLS-1$
            DcsSettingsWriter.ValueResult parameter = DcsSettingsWriter.buildValue(
                item.get(KEY_PARAMETER), itemPath + "." + KEY_PARAMETER); //$NON-NLS-1$
            if (parameter.error != null) return InputParametersResult.failure(parameter.error);
            if (!(parameter.value instanceof DataCompositionParameter))
                return InputParametersResult.failure("Input parameter at '" + itemPath //$NON-NLS-1$
                    + ".parameter' must use kind='parameter'."); //$NON-NLS-1$
            ValuesResult values = item.has(KEY_VALUES)
                ? values(item.get(KEY_VALUES), null, languages, itemPath + "." + KEY_VALUES) //$NON-NLS-1$
                : ValuesResult.success(Collections.<Value>emptyList());
            if (values.error != null) return InputParametersResult.failure(values.error);
            BooleanResult use = optionalBoolean(item, KEY_USE, itemPath);
            if (use.error != null) return InputParametersResult.failure(use.error);
            DataCompositionParameterValue value =
                com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                    .createDataCompositionParameterValue();
            value.setParameter((DataCompositionParameter)parameter.value);
            value.getValues().addAll(values.values);
            if (use.value != null) value.setUse(use.value.booleanValue());
            result.getItems().add(value);
            index++;
        }
        return InputParametersResult.success(result);
    }

    private static StringResult optionalString(JsonObject body, String member, String path)
    {
        if (!body.has(member) || body.get(member).isJsonNull()) return StringResult.success(null);
        JsonElement raw = body.get(member);
        return raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()
            ? StringResult.success(raw.getAsString())
            : StringResult.failure("Member '" + member + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' must be a string or null."); //$NON-NLS-1$
    }

    private static BooleanResult optionalBoolean(JsonObject body, String member, String path)
    {
        if (!body.has(member)) return BooleanResult.success(null);
        JsonElement raw = body.get(member);
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()
            ? BooleanResult.success(Boolean.valueOf(raw.getAsBoolean()))
            : BooleanResult.failure("Member '" + member + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' must be true or false."); //$NON-NLS-1$
    }

    private static DataSet findDataSet(List<DataSet> dataSets, String name)
    {
        for (DataSet item : dataSets) if (name.equals(item.getName())) return item;
        return null;
    }

    private static DataCompositionSchemaDataSetField findField(DataSet dataSet, String dataPath)
    {
        for (DataSetField item : dataSet.getFields())
            if (item instanceof DataCompositionSchemaDataSetField
                && dataPath.equals(((DataCompositionSchemaDataSetField)item).getDataPath()))
                return (DataCompositionSchemaDataSetField)item;
        return null;
    }

    private static DataCompositionSchemaCalculatedField findCalculatedField(
        DataCompositionSchema schema, String dataPath)
    {
        for (DataCompositionSchemaCalculatedField item : schema.getCalculatedFields())
            if (dataPath.equals(item.getDataPath())) return item;
        return null;
    }

    private static DataCompositionSchemaParameter findParameter(DataCompositionSchema schema,
        String name)
    {
        for (DataCompositionSchemaParameter item : schema.getParameters())
            if (name.equals(item.getName())) return item;
        return null;
    }

    /**
     * Plans the field/calculated-field/parameter collections shared by a schema and a dynamic list.
     * The existing dynamic-list items are copied into a detached scratch schema and authored through
     * this class's normal parser, natural-key collision checks, type resolver, and typed item appliers.
     * The caller commits the returned detached lists only after every other dynamic-list member has
     * validated, so there is still one implementation of these item bodies and no partial mutation.
     *
     * @param fields current dynamic-list fields
     * @param calculatedFields current dynamic-list calculated fields
     * @param parameters current dynamic-list parameters
     * @param action requested {@code upsert} or {@code update} semantics
     * @param body object containing any of {@code fields}, {@code calculatedFields}, {@code parameters}
     * @param typeResolver shared metadata value-type resolver
     * @param languages configured presentation-language context
     * @return detached item lists or the ready error JSON produced by the shared writer
     */
    public static DynamicItemsResult planDynamicListItems(List<DataSetField> fields,
        List<DataCompositionSchemaCalculatedField> calculatedFields,
        List<DataCompositionSchemaParameter> parameters, String action, JsonObject body,
        TypeResolver typeResolver, DcsPresentationParser.LanguageContext languages)
    {
        return planDynamicListItems(fields, calculatedFields, parameters, action, body,
            typeResolver, languages, Version.LATEST, null);
    }

    public static DynamicItemsResult planDynamicListItems(List<DataSetField> fields,
        List<DataCompositionSchemaCalculatedField> calculatedFields,
        List<DataCompositionSchemaParameter> parameters, String action, JsonObject body,
        TypeResolver typeResolver, DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors)
    {
        JsonObject normalized = body.deepCopy();
        String updateError = dynamicUpdateKeysError(fields, calculatedFields, parameters, action,
            normalized);
        if (updateError != null)
        {
            return DynamicItemsResult.failure(ToolResult.error(updateError).toJson());
        }
        mergeDynamicItemDefaults(normalized, fields, calculatedFields);
        DataCompositionSchema scratch = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery dataSet = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        dataSet.setName("__McpDynamicList"); //$NON-NLS-1$
        dataSet.setQuery("DYNAMIC LIST"); //$NON-NLS-1$
        if (fields != null)
        {
            for (DataSetField field : fields)
            {
                dataSet.getFields().add(EcoreUtil.copy(field));
            }
        }
        scratch.getDataSets().add(dataSet);
        if (calculatedFields != null)
        {
            for (DataCompositionSchemaCalculatedField field : calculatedFields)
            {
                scratch.getCalculatedFields().add(EcoreUtil.copy(field));
            }
        }
        if (parameters != null)
        {
            for (DataCompositionSchemaParameter parameter : parameters)
            {
                scratch.getParameters().add(EcoreUtil.copy(parameter));
            }
        }

        JsonObject adapted = new JsonObject();
        if (nonEmptyOrMalformed(normalized, KEY_FIELDS))
        {
            JsonObject set = new JsonObject();
            set.addProperty(KEY_NAME, "__McpDynamicList"); //$NON-NLS-1$
            set.addProperty(KEY_TYPE, TYPE_QUERY);
            set.addProperty(KEY_QUERY, "DYNAMIC LIST"); //$NON-NLS-1$
            set.addProperty(KEY_AUTO_FILL, false);
            set.add(KEY_FIELDS, normalized.get(KEY_FIELDS).deepCopy());
            JsonArray sets = new JsonArray();
            sets.add(set);
            adapted.add(KEY_DATA_SETS, sets);
        }
        if (nonEmptyOrMalformed(normalized, KEY_CALCULATED_FIELDS))
        {
            adapted.add(KEY_CALCULATED_FIELDS, normalized.get(KEY_CALCULATED_FIELDS).deepCopy());
        }
        if (nonEmptyOrMalformed(normalized, KEY_PARAMETERS))
        {
            adapted.add(KEY_PARAMETERS, normalized.get(KEY_PARAMETERS).deepCopy());
        }

        Result applied = adapted.size() == 0 ? Result.ok(0, 0, 0, 0, 0, 0)
            : apply(scratch, adapted, typeResolver, languages, version, namedColors);
        if (applied.hasError())
        {
            return DynamicItemsResult.failure(applied.error);
        }
        DataCompositionSchemaDataSetQuery plannedSet = (DataCompositionSchemaDataSetQuery)
            scratch.getDataSets().get(0);
        List<DataSetField> plannedFields = new ArrayList<>();
        for (DataSetField field : plannedSet.getFields())
        {
            plannedFields.add(EcoreUtil.copy(field));
        }
        List<DataCompositionSchemaCalculatedField> plannedCalculated = new ArrayList<>();
        for (DataCompositionSchemaCalculatedField field : scratch.getCalculatedFields())
        {
            plannedCalculated.add(EcoreUtil.copy(field));
        }
        List<DataCompositionSchemaParameter> plannedParameters = new ArrayList<>();
        for (DataCompositionSchemaParameter parameter : scratch.getParameters())
        {
            plannedParameters.add(EcoreUtil.copy(parameter));
        }
        return DynamicItemsResult.success(plannedFields, plannedCalculated, plannedParameters,
            body.has(KEY_FIELDS) && body.get(KEY_FIELDS).isJsonArray()
                && !body.getAsJsonArray(KEY_FIELDS).isEmpty(),
            body.has(KEY_CALCULATED_FIELDS) && body.get(KEY_CALCULATED_FIELDS).isJsonArray()
                && !body.getAsJsonArray(KEY_CALCULATED_FIELDS).isEmpty(),
            body.has(KEY_PARAMETERS) && body.get(KEY_PARAMETERS).isJsonArray()
                && !body.getAsJsonArray(KEY_PARAMETERS).isEmpty(), applied);
    }

    private static boolean nonEmptyOrMalformed(JsonObject body, String member)
    {
        return body.has(member) && (!body.get(member).isJsonArray()
            || !body.getAsJsonArray(member).isEmpty());
    }

    private static String dynamicUpdateKeysError(List<DataSetField> fields,
        List<DataCompositionSchemaCalculatedField> calculatedFields,
        List<DataCompositionSchemaParameter> parameters, String action, JsonObject body)
    {
        if (!"update".equals(action)) //$NON-NLS-1$
        {
            return null;
        }
        String error = missingDynamicKey(body, KEY_FIELDS, KEY_DATA_PATH,
            dynamicFieldKeys(fields));
        if (error != null)
        {
            return error;
        }
        error = missingDynamicKey(body, KEY_CALCULATED_FIELDS, KEY_DATA_PATH,
            dynamicCalculatedFieldKeys(calculatedFields));
        return error != null ? error
            : missingDynamicKey(body, KEY_PARAMETERS, KEY_NAME, dynamicParameterKeys(parameters));
    }

    private static String missingDynamicKey(JsonObject body, String collection, String keyMember,
        Set<String> existing)
    {
        if (!body.has(collection) || !body.get(collection).isJsonArray())
        {
            return null;
        }
        JsonArray array = body.getAsJsonArray(collection);
        for (int i = 0; i < array.size(); i++)
        {
            JsonElement element = array.get(i);
            if (!element.isJsonObject())
            {
                continue;
            }
            String key = stringMember(element.getAsJsonObject(), keyMember);
            if (key != null && !existing.contains(key))
            {
                return "action='update' cannot create " + collection + " entry '" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + "'. Existing keys: " + (existing.isEmpty() ? "(none)" //$NON-NLS-1$ //$NON-NLS-2$
                        : String.join(", ", existing)) //$NON-NLS-1$
                    + ". Copy an existing address from dcs action='get', or use action='upsert'."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static Set<String> dynamicFieldKeys(List<DataSetField> fields)
    {
        Set<String> result = new LinkedHashSet<>();
        if (fields != null)
        {
            for (DataSetField field : fields)
            {
                org.eclipse.emf.ecore.EStructuralFeature feature =
                    field.eClass().getEStructuralFeature(KEY_DATA_PATH);
                Object value = feature == null ? null : field.eGet(feature);
                if (value instanceof String) result.add((String)value);
            }
        }
        return result;
    }

    private static Set<String> dynamicCalculatedFieldKeys(
        List<DataCompositionSchemaCalculatedField> fields)
    {
        Set<String> result = new LinkedHashSet<>();
        if (fields != null)
        {
            for (DataCompositionSchemaCalculatedField field : fields)
            {
                result.add(field.getDataPath());
            }
        }
        return result;
    }

    private static Set<String> dynamicParameterKeys(List<DataCompositionSchemaParameter> parameters)
    {
        Set<String> result = new LinkedHashSet<>();
        if (parameters != null)
        {
            for (DataCompositionSchemaParameter parameter : parameters)
            {
                result.add(parameter.getName());
            }
        }
        return result;
    }

    private static void mergeDynamicItemDefaults(JsonObject body, List<DataSetField> fields,
        List<DataCompositionSchemaCalculatedField> calculatedFields)
    {
        if (body.has(KEY_FIELDS) && body.get(KEY_FIELDS).isJsonArray() && fields != null)
        {
            for (JsonElement element : body.getAsJsonArray(KEY_FIELDS))
            {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String key = stringMember(entry, KEY_DATA_PATH);
                for (DataSetField current : fields)
                {
                    org.eclipse.emf.ecore.EStructuralFeature path =
                        current.eClass().getEStructuralFeature(KEY_DATA_PATH);
                    org.eclipse.emf.ecore.EStructuralFeature source =
                        current.eClass().getEStructuralFeature(KEY_FIELD);
                    Object currentPath = path == null ? null : current.eGet(path);
                    Object currentSource = source == null ? null : current.eGet(source);
                    if (key != null && key.equals(currentPath) && !entry.has(KEY_FIELD)
                        && currentSource instanceof String && !((String)currentSource).isEmpty())
                    {
                        entry.addProperty(KEY_FIELD, (String)currentSource);
                    }
                }
            }
        }
        if (body.has(KEY_CALCULATED_FIELDS) && body.get(KEY_CALCULATED_FIELDS).isJsonArray()
            && calculatedFields != null)
        {
            for (JsonElement element : body.getAsJsonArray(KEY_CALCULATED_FIELDS))
            {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String key = stringMember(entry, KEY_DATA_PATH);
                for (DataCompositionSchemaCalculatedField current : calculatedFields)
                {
                    if (key != null && key.equals(current.getDataPath())
                        && !entry.has(KEY_EXPRESSION) && current.getExpression() != null)
                    {
                        entry.addProperty(KEY_EXPRESSION, current.getExpression());
                    }
                }
            }
        }
    }

    /** Detached dynamic-list item plan produced by the schema item implementation. */
    public static final class DynamicItemsResult
    {
        private final List<DataSetField> fields;
        private final List<DataCompositionSchemaCalculatedField> calculatedFields;
        private final List<DataCompositionSchemaParameter> parameters;
        private final boolean fieldsTouched;
        private final boolean calculatedFieldsTouched;
        private final boolean parametersTouched;
        private final Result applied;
        private final String errorJson;

        private DynamicItemsResult(List<DataSetField> fields,
            List<DataCompositionSchemaCalculatedField> calculatedFields,
            List<DataCompositionSchemaParameter> parameters, boolean fieldsTouched,
            boolean calculatedFieldsTouched, boolean parametersTouched, Result applied,
            String errorJson)
        {
            this.fields = fields;
            this.calculatedFields = calculatedFields;
            this.parameters = parameters;
            this.fieldsTouched = fieldsTouched;
            this.calculatedFieldsTouched = calculatedFieldsTouched;
            this.parametersTouched = parametersTouched;
            this.applied = applied;
            this.errorJson = errorJson;
        }

        private static DynamicItemsResult success(List<DataSetField> fields,
            List<DataCompositionSchemaCalculatedField> calculatedFields,
            List<DataCompositionSchemaParameter> parameters, boolean fieldsTouched,
            boolean calculatedFieldsTouched, boolean parametersTouched, Result applied)
        {
            return new DynamicItemsResult(fields, calculatedFields, parameters, fieldsTouched,
                calculatedFieldsTouched, parametersTouched, applied, null);
        }

        private static DynamicItemsResult failure(String errorJson)
        {
            return new DynamicItemsResult(null, null, null, false, false, false, null, errorJson);
        }

        public boolean isSuccess() { return errorJson == null; }
        public List<DataSetField> fields() { return fields; }
        public List<DataCompositionSchemaCalculatedField> calculatedFields() { return calculatedFields; }
        public List<DataCompositionSchemaParameter> parameters() { return parameters; }
        public boolean fieldsTouched() { return fieldsTouched; }
        public boolean calculatedFieldsTouched() { return calculatedFieldsTouched; }
        public boolean parametersTouched() { return parametersTouched; }
        public Result applied() { return applied; }
        public String errorJson() { return errorJson; }
    }

    // ---- model mutation (typed DCS API) -------------------------------------------------------

    /**
     * Ensures every declared data source exists, then find-or-creates each query data set and wires its
     * query text / data source / auto-fill flag. Returns the number of data sources present after ensuring
     * (declared + any auto-created default).
     */
    private static int applyDataSets(DataCompositionSchema schema, Plan plan)
    {
        for (DataSourcePlan source : plan.dataSources)
        {
            DataCompositionSchemaDataSource applied = ensureDataSource(schema, source.name, source.type);
            applied.setDataSourceType(source.type);
            if (source.connectionString != null)
            {
                applied.setConnectionString(source.connectionString);
            }
        }
        String defaultSourceName = plan.dataSources.isEmpty() ? null : plan.dataSources.get(0).name;

        for (DataSetPlan setPlan : plan.dataSets)
        {
            DataSet dataSet = getOrCreateDataSet(schema.getDataSets(), setPlan);
            if (dataSet instanceof DataCompositionSchemaDataSetQuery)
            {
                DataCompositionSchemaDataSetQuery query = (DataCompositionSchemaDataSetQuery)dataSet;
                if (setPlan.query != null) query.setQuery(setPlan.query);
                String sourceName = ensureSourceName(schema, setPlan.dataSource, defaultSourceName);
                if (defaultSourceName == null) defaultSourceName = sourceName;
                query.setDataSource(sourceName);
                query.setAutoFillAvailableFields(setPlan.autoFill != null
                    ? setPlan.autoFill.booleanValue() : setPlan.fields.isEmpty());
            }
            else if (dataSet instanceof DataCompositionSchemaDataSetObject)
            {
                DataCompositionSchemaDataSetObject object = (DataCompositionSchemaDataSetObject)dataSet;
                String sourceName = ensureSourceName(schema, setPlan.dataSource, defaultSourceName);
                if (defaultSourceName == null) defaultSourceName = sourceName;
                object.setDataSource(sourceName);
                object.setObjectName(setPlan.objectName);
            }
            if (dataSet instanceof DataCompositionSchemaDataSetUnion)
            {
                defaultSourceName = applyNestedDataSets(schema,
                    ((DataCompositionSchemaDataSetUnion)dataSet).getItems(), setPlan.items,
                    defaultSourceName);
            }
        }
        return schema.getDataSources().size();
    }

    /** Applies each data set's explicit fields, returning the total number of fields authored. */
    private static int applyFields(DataCompositionSchema schema, Plan plan)
    {
        int count = 0;
        for (DataSetPlan setPlan : plan.dataSets)
        {
            DataSet dataSet = getOrCreateDataSet(schema.getDataSets(), setPlan);
            for (FieldPlan fieldPlan : setPlan.fields)
            {
                applyField(dataSet, fieldPlan);
                count++;
            }
            count += applyNestedFields(schema, setPlan, dataSet);
        }
        return count;
    }

    /**
     * Writes one data set field: find-or-creates the {@link DataCompositionSchemaDataSetField} by data
     * path, sets its {@code dataPath} / {@code field} (the source query column, defaulted to the data
     * path), an optional {@code title} {@link Presentation}, and an optional structured role.
     */
    private static void applyField(DataSet dataSet, FieldPlan plan)
    {
        DataCompositionSchemaDataSetField field = getOrCreateField(dataSet, plan.dataPath);
        field.setDataPath(plan.dataPath);
        field.setField(plan.field != null ? plan.field : plan.dataPath);
        if (plan.title != null)
        {
            field.setTitle(buildPresentation(plan.title));
        }
        if (plan.role != null)
        {
            field.setRole(buildRole(plan.role));
        }
        if (plan.useRestriction != null)
        {
            field.setUseRestriction(buildUseRestriction(plan.useRestriction));
        }
        applyResolvedFieldMembers(field, plan.resolved, plan.members);
    }

    /**
     * Find-or-updates each planned calculated field by {@code dataPath}, writing its {@code expression}
     * and optional {@code title}. Returns the number of calculated fields applied (created or updated).
     */
    private static int applyCalculatedFields(DataCompositionSchema schema, Plan plan)
    {
        for (CalculatedFieldPlan calcPlan : plan.calculatedFields)
        {
            applyCalculatedField(schema, calcPlan);
        }
        return plan.calculatedFields.size();
    }

    /** Find-or-updates every total field by its {@code dataPath}. */
    private static int applyTotalFields(DataCompositionSchema schema, Plan plan)
    {
        for (TotalFieldPlan totalPlan : plan.totalFields)
        {
            DataCompositionSchemaTotalField field = getOrCreateTotalField(schema, totalPlan.dataPath);
            field.setDataPath(totalPlan.dataPath);
            if (totalPlan.expression != null)
            {
                field.setExpression(totalPlan.expression);
            }
            if (totalPlan.groups != null)
            {
                field.getGroups().clear();
                field.getGroups().addAll(totalPlan.groups);
            }
        }
        return plan.totalFields.size();
    }

    /**
     * Writes one calculated field: find-or-creates the {@link DataCompositionSchemaCalculatedField} by
     * data path (an existing one with the same {@code dataPath} is UPDATED in place, never duplicated -
     * the same find-or-update discipline as a query data set / a data set field), then sets its
     * {@code expression} and an optional {@code title} {@link Presentation}.
     */
    private static void applyCalculatedField(DataCompositionSchema schema, CalculatedFieldPlan plan)
    {
        DataCompositionSchemaCalculatedField field = getOrCreateCalculatedField(schema, plan.dataPath);
        field.setDataPath(plan.dataPath);
        field.setExpression(plan.expression);
        if (plan.title != null)
        {
            field.setTitle(buildPresentation(plan.title));
        }
        applyResolvedCalculatedMembers(field, plan.resolved, plan.members);
    }

    /**
     * Writes one schema parameter: find-or-creates the {@link DataCompositionSchemaParameter} by name and
     * sets its (already-resolved) value type, an optional {@code title} {@link Presentation}, and an
     * optional {@link DataCompositionParameterUse use}.
     */
    private static void applyParameter(DataCompositionSchema schema, ParameterPlan plan)
    {
        DataCompositionSchemaParameter parameter = getOrCreateParameter(schema, plan.name);
        ResolvedMembers resolved = plan.resolved;
        if (plan.members.has(KEY_VALUE_TYPE))
        {
            parameter.setValueType(resolved.valueType);
        }
        if (plan.title != null)
        {
            parameter.setTitle(buildPresentation(plan.title));
        }
        if (plan.use != null)
        {
            parameter.setUse(plan.use);
        }
        if (plan.members.has(KEY_VALUES)) replace(parameter.getValues(), resolved.values);
        if (plan.members.has(KEY_AVAILABLE_VALUES))
            replace(parameter.getAvailableValues(), resolved.availableValues);
        if (plan.members.has(KEY_EXPRESSION)) parameter.setExpression(resolved.expression);
        if (resolved.useRestriction != null)
            parameter.setUseRestriction(resolved.useRestriction.booleanValue());
        if (resolved.valueListAllowed != null)
            parameter.setValueListAllowed(resolved.valueListAllowed.booleanValue());
        if (resolved.availableAsField != null)
            parameter.setAvailableAsField(resolved.availableAsField.booleanValue());
        if (resolved.denyIncompleteValues != null)
            parameter.setDenyIncompleteValues(resolved.denyIncompleteValues.booleanValue());
        if (plan.members.has(KEY_FUNCTIONAL_OPTIONS_PARAMETER))
            parameter.setFunctionalOptionsParameter(resolved.functionalOptionsParameter);
        if (plan.members.has(KEY_INPUT_PARAMETERS))
            parameter.setInputParameters(resolved.inputParameters);
    }

    private static void applyResolvedFieldMembers(DataCompositionSchemaDataSetField field,
        ResolvedMembers resolved, JsonObject members)
    {
        if (resolved == null) return;
        if (members.has(KEY_VALUE_TYPE))
            field.setValueType(resolved.valueType);
        if (members.has(KEY_APPEARANCE)) field.setAppearance(resolved.appearance);
        if (members.has(KEY_ATTRIBUTE_USE_RESTRICTION))
            field.setAttributeUseRestriction(resolved.attributeUseRestriction);
        if (members.has(KEY_PRESENTATION_EXPRESSION))
            field.setPresentationExpression(resolved.presentationExpression);
        if (members.has(KEY_ORDER_EXPRESSIONS))
            replace(field.getOrderExpressions(), resolved.orderExpressions);
        if (members.has(KEY_IN_HIERARCHY_DATA_SET))
            field.setInHierarchyDataSet(resolved.inHierarchyDataSet);
        if (members.has(KEY_IN_HIERARCHY_DATA_SET_PARAMETER))
            field.setInHierarchyDataSetParameter(resolved.inHierarchyDataSetParameter);
        if (members.has(KEY_AVAILABLE_VALUES))
            replace(field.getAvailableValues(), resolved.availableValues);
        if (members.has(KEY_INPUT_PARAMETERS)) field.setInputParameters(resolved.inputParameters);
    }

    private static void applyResolvedCalculatedMembers(DataCompositionSchemaCalculatedField field,
        ResolvedMembers resolved, JsonObject members)
    {
        if (resolved == null) return;
        if (members.has(KEY_VALUE_TYPE))
            field.setValueType(resolved.valueType);
        if (members.has(KEY_APPEARANCE)) field.setAppearance(resolved.appearance);
        if (members.has(KEY_USE_RESTRICTION))
            field.setUseRestriction(resolved.attributeUseRestriction);
        if (members.has(KEY_PRESENTATION_EXPRESSION))
            field.setPresentationExpression(resolved.presentationExpression);
        if (members.has(KEY_ORDER_EXPRESSION))
            replace(field.getOrderExpression(), resolved.orderExpressions);
        if (members.has(KEY_AVAILABLE_VALUES))
            replace(field.getAvailableValues(), resolved.availableValues);
        if (members.has(KEY_INPUT_PARAMETERS)) field.setInputParameters(resolved.inputParameters);
    }

    private static <T> void replace(List<T> target, List<T> values)
    {
        target.clear();
        if (values != null) target.addAll(values);
    }

    /** Builds a {@link DataCompositionDataSetFieldRole} from a validated role plan (only set flags). */
    private static DataCompositionDataSetFieldRole buildRole(RolePlan plan)
    {
        DataCompositionDataSetFieldRole role =
            com._1c.g5.v8.dt.dcs.model.common.DcsFactory.eINSTANCE.createDataCompositionDataSetFieldRole();
        if (plan.dimension != null)
        {
            role.setDimension(plan.dimension.booleanValue());
        }
        if (plan.main != null)
        {
            role.setMain(plan.main.booleanValue());
        }
        if (plan.required != null)
        {
            role.setRequired(plan.required.booleanValue());
        }
        if (plan.ignoreNullValues != null)
        {
            role.setIgnoreNullValues(plan.ignoreNullValues.booleanValue());
        }
        if (plan.dimensionAttribute != null)
        {
            role.setDimensionAttribute(plan.dimensionAttribute.booleanValue());
        }
        if (plan.account != null)
        {
            role.setAccount(plan.account.booleanValue());
        }
        if (plan.balance != null)
        {
            role.setBalance(plan.balance.booleanValue());
        }
        if (plan.periodType != null)
        {
            role.setPeriodType(plan.periodType);
        }
        if (plan.periodNumber != null)
        {
            role.setPeriodNumber(plan.periodNumber.intValue());
        }
        return role;
    }

    /** Builds one field-use restriction after its complete shape has been validated. */
    private static DataCompositionSchemaFieldUseRestriction buildUseRestriction(UseRestrictionPlan plan)
    {
        DataCompositionSchemaFieldUseRestriction restriction =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaFieldUseRestriction();
        if (plan.field != null)
        {
            restriction.setField(plan.field.booleanValue());
        }
        if (plan.condition != null)
        {
            restriction.setCondition(plan.condition.booleanValue());
        }
        if (plan.group != null)
        {
            restriction.setGroup(plan.group.booleanValue());
        }
        if (plan.order != null)
        {
            restriction.setOrder(plan.order.booleanValue());
        }
        return restriction;
    }

    /**
     * Builds a core {@link Presentation} from a title plan. Both a plain string and a localized map
     * populate a {@link LocalString} keyed by language code; a missing plan means no presentation.
     */
    private static com._1c.g5.v8.dt.dcs.model.core.Presentation buildPresentation(
        DcsPresentationParser.Plan plan)
    {
        return DcsPresentationParser.build(plan);
    }

    // ---- find-or-create -----------------------------------------------------------------------

    /** Find-or-creates a data source by name, setting its type on create. */
    private static DataCompositionSchemaDataSource ensureDataSource(DataCompositionSchema schema, String name,
        String type)
    {
        for (DataCompositionSchemaDataSource existing : schema.getDataSources())
        {
            if (name.equals(existing.getName()))
            {
                return existing;
            }
        }
        DataCompositionSchemaDataSource source =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSource();
        source.setName(name);
        source.setDataSourceType(type != null ? type : LOCAL_SOURCE_TYPE);
        schema.getDataSources().add(source);
        return source;
    }

    /**
     * Find-or-creates a QUERY data set by name. A pre-existing query data set with the same name is
     * reused; a fresh one is appended otherwise. Model validation rejects a same-named object/union
     * data set before this method is called, because appending a second subtype under the same natural
     * key would produce a schema the 1C serializer refuses.
     */
    private static DataSet getOrCreateDataSet(List<DataSet> dataSets, DataSetPlan plan)
    {
        for (DataSet existing : dataSets)
        {
            if (plan.name.equals(existing.getName()))
            {
                return existing;
            }
        }
        DataSet dataSet;
        if (TYPE_OBJECT.equals(plan.type))
            dataSet = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetObject();
        else if (TYPE_UNION.equals(plan.type))
            dataSet = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetUnion();
        else
            dataSet = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetQuery();
        dataSet.setName(plan.name);
        dataSets.add(dataSet);
        return dataSet;
    }

    /** Find-or-creates a data set field by data path. */
    private static DataCompositionSchemaDataSetField getOrCreateField(DataSet dataSet,
        String dataPath)
    {
        for (DataSetField existing : dataSet.getFields())
        {
            if (existing instanceof DataCompositionSchemaDataSetField
                && dataPath.equals(((DataCompositionSchemaDataSetField)existing).getDataPath()))
            {
                return (DataCompositionSchemaDataSetField)existing;
            }
        }
        DataCompositionSchemaDataSetField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetField();
        dataSet.getFields().add(field);
        return field;
    }

    private static String ensureSourceName(DataCompositionSchema schema, String requested,
        String defaultSourceName)
    {
        String result = requested != null ? requested
            : defaultSourceName != null ? defaultSourceName : DEFAULT_DATA_SOURCE_NAME;
        ensureDataSource(schema, result, LOCAL_SOURCE_TYPE);
        return result;
    }

    private static String applyNestedDataSets(DataCompositionSchema schema, List<DataSet> target,
        List<DataSetPlan> plans, String defaultSourceName)
    {
        for (DataSetPlan plan : plans)
        {
            DataSet dataSet = getOrCreateDataSet(target, plan);
            if (dataSet instanceof DataCompositionSchemaDataSetQuery)
            {
                DataCompositionSchemaDataSetQuery query = (DataCompositionSchemaDataSetQuery)dataSet;
                query.setQuery(plan.query);
                String sourceName = ensureSourceName(schema, plan.dataSource, defaultSourceName);
                if (defaultSourceName == null) defaultSourceName = sourceName;
                query.setDataSource(sourceName);
                query.setAutoFillAvailableFields(plan.autoFill != null ? plan.autoFill.booleanValue()
                    : plan.fields.isEmpty());
            }
            else if (dataSet instanceof DataCompositionSchemaDataSetObject)
            {
                DataCompositionSchemaDataSetObject object = (DataCompositionSchemaDataSetObject)dataSet;
                object.setObjectName(plan.objectName);
                String sourceName = ensureSourceName(schema, plan.dataSource, defaultSourceName);
                if (defaultSourceName == null) defaultSourceName = sourceName;
                object.setDataSource(sourceName);
            }
            else
            {
                defaultSourceName = applyNestedDataSets(schema,
                    ((DataCompositionSchemaDataSetUnion)dataSet).getItems(), plan.items,
                    defaultSourceName);
            }
        }
        return defaultSourceName;
    }

    private static int applyNestedFields(DataCompositionSchema schema, DataSetPlan plan, DataSet dataSet)
    {
        if (!(dataSet instanceof DataCompositionSchemaDataSetUnion)) return 0;
        int count = 0;
        List<DataSet> targets = ((DataCompositionSchemaDataSetUnion)dataSet).getItems();
        for (DataSetPlan child : plan.items)
        {
            DataSet target = getOrCreateDataSet(targets, child);
            for (FieldPlan field : child.fields)
            {
                applyField(target, field);
                count++;
            }
            count += applyNestedFields(schema, child, target);
        }
        return count;
    }

    private static void applyDataSetLinks(DataCompositionSchema schema, Plan plan)
    {
        for (DataSetLinkPlan linkPlan : plan.dataSetLinks)
        {
            DataCompositionSchemaDataSetLink link =
                com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                    .createDataCompositionSchemaDataSetLink();
            link.setSourceDataSet(linkPlan.sourceDataSet);
            link.setDestinationDataSet(linkPlan.destinationDataSet);
            link.setSourceExpression(linkPlan.sourceExpression);
            link.setDestinationExpression(linkPlan.destinationExpression);
            if (linkPlan.parameter != null) link.setParameter(linkPlan.parameter);
            if (linkPlan.parameterListAllowed != null)
                link.setParameterListAllowed(linkPlan.parameterListAllowed.booleanValue());
            if (linkPlan.linkCondition != null) link.setLinkConditionExpression(linkPlan.linkCondition);
            if (linkPlan.startExpression != null) link.setStartExpression(linkPlan.startExpression);
            if (linkPlan.required != null) link.setRequired(linkPlan.required.booleanValue());
            schema.getDataSetLinks().add(link);
        }
    }

    /** Find-or-creates a total field by data path. */
    private static DataCompositionSchemaTotalField getOrCreateTotalField(DataCompositionSchema schema,
        String dataPath)
    {
        for (DataCompositionSchemaTotalField existing : schema.getTotalFields())
        {
            if (dataPath.equals(existing.getDataPath()))
            {
                return existing;
            }
        }
        DataCompositionSchemaTotalField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaTotalField();
        schema.getTotalFields().add(field);
        return field;
    }

    /** Find-or-creates a calculated field by data path (mirrors {@link #getOrCreateField}). */
    private static DataCompositionSchemaCalculatedField getOrCreateCalculatedField(DataCompositionSchema schema,
        String dataPath)
    {
        for (DataCompositionSchemaCalculatedField existing : schema.getCalculatedFields())
        {
            if (dataPath.equals(existing.getDataPath()))
            {
                return existing;
            }
        }
        DataCompositionSchemaCalculatedField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaCalculatedField();
        schema.getCalculatedFields().add(field);
        return field;
    }

    /** Find-or-creates a schema parameter by name. */
    private static DataCompositionSchemaParameter getOrCreateParameter(DataCompositionSchema schema,
        String name)
    {
        for (DataCompositionSchemaParameter existing : schema.getParameters())
        {
            if (name.equals(existing.getName()))
            {
                return existing;
            }
        }
        DataCompositionSchemaParameter parameter =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
        parameter.setName(name);
        schema.getParameters().add(parameter);
        return parameter;
    }

    // ---- model-aware validation (read-only) --------------------------------------------------

    /** Validates every natural key and subtype collision before the first schema mutation. */
    private static String validateNaturalKeys(DataCompositionSchema schema, Plan plan)
    {
        String duplicate = duplicatePlanKey(plan.dataSources, source -> source.name, "data source"); //$NON-NLS-1$
        if (duplicate == null)
        {
            duplicate = duplicatePlanKey(plan.dataSets, dataSet -> dataSet.name, "data set"); //$NON-NLS-1$
        }
        if (duplicate == null)
        {
            duplicate = duplicatePlanKey(plan.parameters, parameter -> parameter.name, "parameter"); //$NON-NLS-1$
        }
        if (duplicate == null)
        {
            duplicate = duplicatePlanKey(plan.calculatedFields, field -> field.dataPath,
                "calculated field"); //$NON-NLS-1$
        }
        if (duplicate == null)
        {
            duplicate = duplicatePlanKey(plan.totalFields, field -> field.dataPath, "total field"); //$NON-NLS-1$
        }
        if (duplicate != null)
        {
            return duplicate;
        }

        String dataSetError = validateDataSetNaturalKeys(schema.getDataSets(), plan.dataSets, false,
            "data set"); //$NON-NLS-1$
        if (dataSetError != null)
        {
            return dataSetError;
        }
        return existingDuplicateKeys(schema);
    }

    private static String validateDataSetNaturalKeys(List<DataSet> existingDataSets,
        List<DataSetPlan> plans, boolean checkPlanDuplicates, String kind)
    {
        if (checkPlanDuplicates)
        {
            String duplicate = duplicatePlanKey(plans, dataSet -> dataSet.name, kind);
            if (duplicate != null) return duplicate;
        }
        for (DataSetPlan dataSet : plans)
        {
            String fieldDuplicate = duplicatePlanKey(dataSet.fields, field -> field.dataPath,
                "field in data set '" + dataSet.name + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            if (fieldDuplicate != null) return fieldDuplicate;
            DataSet matching = null;
            for (DataSet existing : existingDataSets)
            {
                if (!dataSet.name.equals(existing.getName()))
                {
                    continue;
                }
                if (matching != null)
                {
                    return "Data set natural key '" + dataSet.name //$NON-NLS-1$
                        + "' already occurs more than once. Rename the duplicate in the DCS designer " //$NON-NLS-1$
                        + "before authoring it."; //$NON-NLS-1$
                }
                matching = existing;
            }
            if (matching != null && !dataSetMatches(matching, dataSet.type))
            {
                return "Data set '" + dataSet.name + "' already exists as kind '" //$NON-NLS-1$ //$NON-NLS-2$
                    + dataSetKind(matching) + "', so it cannot also be authored as kind '" //$NON-NLS-1$
                    + dataSet.type + "'. Rename the clashing data set or replace its exact node."; //$NON-NLS-1$
            }
            if (matching != null)
            {
                String fieldError = validateFieldSubtypes(matching, dataSet);
                if (fieldError != null)
                {
                    return fieldError;
                }
            }
            List<DataSet> existingItems = matching instanceof DataCompositionSchemaDataSetUnion
                ? ((DataCompositionSchemaDataSetUnion)matching).getItems()
                : Collections.emptyList();
            String nestedError = validateDataSetNaturalKeys(existingItems, dataSet.items, true,
                "data set in union '" + dataSet.name + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            if (nestedError != null) return nestedError;
        }
        return null;
    }

    private static String validateFieldSubtypes(DataSet existing,
        DataSetPlan plan)
    {
        for (FieldPlan field : plan.fields)
        {
            DataSetField matching = null;
            for (DataSetField candidate : existing.getFields())
            {
                String candidatePath = dataPath(candidate);
                if (!field.dataPath.equals(candidatePath))
                {
                    continue;
                }
                if (matching != null)
                {
                    return "Field natural key '" + field.dataPath + "' in data set '" + plan.name //$NON-NLS-1$ //$NON-NLS-2$
                        + "' already occurs more than once. Rename the duplicate in the DCS designer."; //$NON-NLS-1$
                }
                matching = candidate;
            }
            if (matching != null && !(matching instanceof DataCompositionSchemaDataSetField))
            {
                return "Field '" + field.dataPath + "' in data set '" + plan.name //$NON-NLS-1$ //$NON-NLS-2$
                    + "' already exists as subtype '" + matching.eClass().getName() //$NON-NLS-1$
                    + "', so it cannot also be authored as a regular field. Rename the clashing " //$NON-NLS-1$
                    + "field, or use action='replace' when subtype replacement is available."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static String existingDuplicateKeys(DataCompositionSchema schema)
    {
        String error = duplicateExisting(schema.getDataSources(), source -> source.getName(),
            "data source"); //$NON-NLS-1$
        if (error == null)
        {
            error = duplicateExisting(schema.getParameters(), parameter -> parameter.getName(),
                "parameter"); //$NON-NLS-1$
        }
        if (error == null)
        {
            error = duplicateExisting(schema.getCalculatedFields(), field -> field.getDataPath(),
                "calculated field"); //$NON-NLS-1$
        }
        if (error == null)
        {
            error = duplicateExisting(schema.getTotalFields(), field -> field.getDataPath(),
                "total field"); //$NON-NLS-1$
        }
        return error;
    }

    private static <T> String duplicatePlanKey(List<T> values,
        java.util.function.Function<T, String> keyFunction, String kind)
    {
        Set<String> seen = new HashSet<>();
        for (T value : values)
        {
            String key = keyFunction.apply(value);
            if (!seen.add(key))
            {
                return "The body names " + kind + " natural key '" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + "' more than once. Keep exactly one entry for that key."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static <T> String duplicateExisting(List<T> values,
        java.util.function.Function<T, String> keyFunction, String kind)
    {
        Set<String> seen = new HashSet<>();
        for (T value : values)
        {
            String key = keyFunction.apply(value);
            if (key != null && !seen.add(key))
            {
                return "Existing " + kind + " natural key '" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + "' occurs more than once. Rename the duplicate in the DCS designer before " //$NON-NLS-1$
                    + "authoring it."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static String dataPath(DataSetField field)
    {
        org.eclipse.emf.ecore.EStructuralFeature feature =
            field.eClass().getEStructuralFeature(KEY_DATA_PATH);
        Object value = feature == null ? null : field.eGet(feature);
        return value instanceof String ? (String)value : null;
    }

    private static String dataSetKind(DataSet dataSet)
    {
        if (dataSet instanceof DataCompositionSchemaDataSetQuery) return TYPE_QUERY;
        if (dataSet instanceof DataCompositionSchemaDataSetObject) return TYPE_OBJECT;
        if (dataSet instanceof DataCompositionSchemaDataSetUnion) return TYPE_UNION;
        String className = dataSet.eClass().getName();
        if (className.endsWith("DataSetObject")) //$NON-NLS-1$
        {
            return "object"; //$NON-NLS-1$
        }
        if (className.endsWith("DataSetUnion")) //$NON-NLS-1$
        {
            return "union"; //$NON-NLS-1$
        }
        return className;
    }

    private static boolean dataSetMatches(DataSet dataSet, String type)
    {
        return TYPE_QUERY.equals(type) && dataSet instanceof DataCompositionSchemaDataSetQuery
            || TYPE_OBJECT.equals(type) && dataSet instanceof DataCompositionSchemaDataSetObject
            || TYPE_UNION.equals(type) && dataSet instanceof DataCompositionSchemaDataSetUnion;
    }

    // ---- parsing / validation (pure, no model) ------------------------------------------------

    /**
     * Parses + validates a {@code dcs} spec into a {@link Plan} of resolved entries, or a ready error
     * message. Pure: touches no DCS factory and no model, so it is independently unit-testable. Every enum
     * ({@code use}, a role's {@code periodType}) is resolved to its literal here (a bad token fails the
     * parse); required names and the data set {@code type} are enforced here too. A parameter's
     * {@code valueType} is only SHAPE-checked here (it must be a JSON object) - it is built later, at
     * apply time, through the caller's {@link TypeResolver}.
     *
     * @param spec the {@code dcs} payload
     * @return a {@link ParseResult} - its {@link ParseResult#error} is non-null on invalid input
     */
    static ParseResult parse(JsonObject spec)
    {
        return parse(spec, null);
    }

    static ParseResult parse(JsonObject spec, DcsPresentationParser.LanguageContext languages)
    {
        return parse(spec, languages, null);
    }

    private static ParseResult parse(JsonObject spec,
        DcsPresentationParser.LanguageContext languages, DataSetValidationContext dataSetValidation)
    {
        if (spec == null)
        {
            return ParseResult.failed("A 'dcs' payload is required, e.g. {dataSets:[{name:'DataSet1'," //$NON-NLS-1$
                + "type:'query',query:'SELECT ...'}]}."); //$NON-NLS-1$
        }
        String unknown = unknownMembers(spec, "body", KEY_DATA_SOURCES, KEY_DATA_SETS, //$NON-NLS-1$
            KEY_PARAMETERS, KEY_CALCULATED_FIELDS, KEY_TOTAL_FIELDS, KEY_DATA_SET_LINKS);
        if (unknown != null)
        {
            return ParseResult.failed(unknown);
        }
        Plan plan = new Plan();

        String error = parseDataSources(spec, plan);
        if (error == null)
        {
            error = parseDataSets(spec, plan, languages, dataSetValidation);
        }
        if (error == null)
        {
            error = parseDataSetLinks(spec, plan);
        }
        if (error == null)
        {
            error = parseParameters(spec, plan, languages);
        }
        if (error == null)
        {
            error = parseCalculatedFields(spec, plan, languages);
        }
        if (error == null)
        {
            error = parseTotalFields(spec, plan);
        }
        if (error != null)
        {
            return ParseResult.failed(error);
        }
        if (spec.entrySet().isEmpty() && plan.dataSets.isEmpty() && plan.parameters.isEmpty()
            && plan.dataSources.isEmpty()
            && plan.calculatedFields.isEmpty() && plan.totalFields.isEmpty()
            && plan.dataSetLinks.isEmpty())
        {
            return ParseResult.failed("The 'dcs' payload is empty: provide at least one of 'dataSets', " //$NON-NLS-1$
                + "'parameters', 'dataSources', 'dataSetLinks', 'calculatedFields' or 'totalFields', e.g. " //$NON-NLS-1$
                + "{dataSets:[{name:'DataSet1'," //$NON-NLS-1$
                + "type:'query',query:'SELECT ...'}]}."); //$NON-NLS-1$
        }
        // Locale validation runs LAST, after every member has been shape-checked. Reporting an
        // undeclared language inside a member the payload is not even allowed to carry would name the
        // wrong problem: the member itself is the error.
        String presentationError = DcsPresentationParser.validateRecursively(spec, languages);
        if (presentationError != null)
        {
            return ParseResult.failed(presentationError);
        }
        return ParseResult.ok(plan);
    }

    private static String parseDataSources(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_DATA_SOURCES);
        if (entries == null)
        {
            return notAnObjectArray(KEY_DATA_SOURCES);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_DATA_SOURCES + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            String unknown = unknownMembers(entry, where, KEY_NAME, KEY_TYPE, KEY_CONNECTION_STRING);
            if (unknown != null)
            {
                return unknown;
            }
            String typeError = stringMembersError(entry, "A data source", where, //$NON-NLS-1$
                KEY_NAME, KEY_TYPE, KEY_CONNECTION_STRING);
            if (typeError != null) return typeError;
            String name = nonEmptyString(entry, KEY_NAME);
            if (name == null)
            {
                return "A data source (" + where + ERR_NEEDS_NAME; //$NON-NLS-1$
            }
            String type = nonEmptyString(entry, KEY_TYPE);
            String connectionString = stringMember(entry, KEY_CONNECTION_STRING);
            plan.dataSources.add(new DataSourcePlan(name, type != null ? type : LOCAL_SOURCE_TYPE,
                connectionString));
        }
        return null;
    }

    private static String parseDataSets(JsonObject spec, Plan plan,
        DcsPresentationParser.LanguageContext languages, DataSetValidationContext dataSetValidation)
    {
        List<JsonObject> entries = objectArray(spec, KEY_DATA_SETS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_DATA_SETS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            String error = parseDataSet(entries.get(i), i, plan, languages, dataSetValidation);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String parseDataSet(JsonObject entry, int index, Plan plan,
        DcsPresentationParser.LanguageContext languages, DataSetValidationContext dataSetValidation)
    {
        String where = KEY_DATA_SETS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        DataSetParseResult result = parseDataSet(entry, where, languages, dataSetValidation,
            Collections.<String>emptyList());
        if (result.error == null)
        {
            plan.dataSets.add(result.plan);
        }
        return result.error;
    }

    private static DataSetParseResult parseDataSet(JsonObject entry, String where,
        DcsPresentationParser.LanguageContext languages, DataSetValidationContext dataSetValidation,
        List<String> parentPath)
    {
        String unknown = unknownMembers(entry, where, KEY_NAME, KEY_TYPE, KEY_QUERY,
            KEY_DATA_SOURCE, KEY_AUTO_FILL, KEY_FIELDS, KEY_OBJECT_NAME, KEY_ITEMS);
        if (unknown != null)
        {
            return DataSetParseResult.failed(unknown);
        }
        String stringError = stringMembersError(entry, "A data set", where, KEY_NAME, //$NON-NLS-1$
            KEY_TYPE, KEY_QUERY, KEY_DATA_SOURCE, KEY_OBJECT_NAME);
        if (stringError != null) return DataSetParseResult.failed(stringError);
        String name = nonEmptyString(entry, KEY_NAME);
        if (name == null)
        {
            return DataSetParseResult.failed(ERR_DATA_SET + where + ERR_NEEDS_NAME);
        }
        List<String> dataSetPath = new ArrayList<>(parentPath);
        dataSetPath.add(name);
        String type = nonEmptyString(entry, KEY_TYPE);
        if (type == null)
        {
            type = TYPE_QUERY;
        }
        if (!TYPE_QUERY.equalsIgnoreCase(type) && !TYPE_OBJECT.equalsIgnoreCase(type)
            && !TYPE_UNION.equalsIgnoreCase(type))
        {
            return DataSetParseResult.failed(ERR_DATA_SET + where + ") has unsupported type '" //$NON-NLS-1$
                + type + "'. Use query, object, or union."); //$NON-NLS-1$
        }
        String query = stringMember(entry, KEY_QUERY);
        String objectName = stringMember(entry, KEY_OBJECT_NAME);
        if (TYPE_QUERY.equalsIgnoreCase(type) && !entry.has(KEY_QUERY))
            return DataSetParseResult.failed("A query data set (" + where //$NON-NLS-1$
                + ") needs a 'query' member. Pass an empty string only when intentionally resetting it."); //$NON-NLS-1$
        if (TYPE_QUERY.equalsIgnoreCase(type) && !isStringMember(entry, KEY_QUERY))
            return DataSetParseResult.failed("A query data set (" + where //$NON-NLS-1$
                + ") member 'query' must be a string. Pass an empty string only when intentionally " //$NON-NLS-1$
                + "resetting it."); //$NON-NLS-1$
        if (TYPE_OBJECT.equalsIgnoreCase(type) && !entry.has(KEY_OBJECT_NAME)
            && (dataSetValidation == null
                || !dataSetValidation.allowsMissingObjectName(dataSetPath)))
            return DataSetParseResult.failed("An object data set (" + where //$NON-NLS-1$
                + ") needs an 'objectName' member. Pass an empty string only when intentionally resetting it."); //$NON-NLS-1$
        if (TYPE_OBJECT.equalsIgnoreCase(type) && entry.has(KEY_OBJECT_NAME)
            && !isStringMember(entry, KEY_OBJECT_NAME))
            return DataSetParseResult.failed("An object data set (" + where //$NON-NLS-1$
                + ") member 'objectName' must be a string. Pass an empty string only when intentionally " //$NON-NLS-1$
                + "resetting it."); //$NON-NLS-1$
        if (TYPE_UNION.equalsIgnoreCase(type) && entry.has(KEY_QUERY))
            return DataSetParseResult.failed("Union data set '" + name + "' at " + where //$NON-NLS-1$ //$NON-NLS-2$
                + " cannot declare 'query'. Remove 'query'; put each query in a nested data set " //$NON-NLS-1$
                + "under 'items'."); //$NON-NLS-1$
        String dataSource = nonEmptyString(entry, KEY_DATA_SOURCE);
        Boolean autoFill = boolMember(entry, KEY_AUTO_FILL);
        if (entry.has(KEY_AUTO_FILL) && autoFill == null)
        {
            return DataSetParseResult.failed(ERR_DATA_SET + where + ") '" + KEY_AUTO_FILL //$NON-NLS-1$
                + "' must be true or false."); //$NON-NLS-1$
        }

        List<JsonObject> fieldEntries = objectArray(entry, KEY_FIELDS);
        if (fieldEntries == null)
        {
            return DataSetParseResult.failed(ERR_DATA_SET + where + ") '" + KEY_FIELDS //$NON-NLS-1$
                + "' must be an array of objects."); //$NON-NLS-1$
        }
        List<FieldPlan> fields = new ArrayList<>();
        for (int i = 0; i < fieldEntries.size(); i++)
        {
            FieldParseResult field = parseField(fieldEntries.get(i),
                where + "." + KEY_FIELDS + "[" + i + "]", languages); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (field.error != null)
            {
                return DataSetParseResult.failed(field.error);
            }
            fields.add(field.plan);
        }
        List<JsonObject> itemEntries = objectArray(entry, KEY_ITEMS);
        if (itemEntries == null)
        {
            return DataSetParseResult.failed(ERR_DATA_SET + where + ") 'items' must be an array of objects."); //$NON-NLS-1$
        }
        if (!TYPE_UNION.equalsIgnoreCase(type) && !itemEntries.isEmpty())
        {
            return DataSetParseResult.failed("Data set '" + name + "' at " + where //$NON-NLS-1$ //$NON-NLS-2$
                + " can use 'items' only with type='union'. Remove items or change its type."); //$NON-NLS-1$
        }
        List<DataSetPlan> items = new ArrayList<>();
        for (int i = 0; i < itemEntries.size(); i++)
        {
            DataSetParseResult child = parseDataSet(itemEntries.get(i),
                where + ".items[" + i + "]", languages, dataSetValidation, dataSetPath); //$NON-NLS-1$ //$NON-NLS-2$
            if (child.error != null) return child;
            items.add(child.plan);
        }
        return DataSetParseResult.ok(new DataSetPlan(name, type.toLowerCase(), query, dataSource,
            objectName, autoFill, fields, items));
    }

    private static String parseDataSetLinks(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_DATA_SET_LINKS);
        if (entries == null) return "'dataSetLinks' must be an array of objects."; //$NON-NLS-1$
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_DATA_SET_LINKS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            String unknown = unknownMembers(entry, where, KEY_SOURCE_DATA_SET,
                KEY_DESTINATION_DATA_SET, KEY_SOURCE_EXPRESSION, KEY_DESTINATION_EXPRESSION, KEY_PARAMETER,
                KEY_PARAMETER_LIST_ALLOWED, KEY_LINK_CONDITION_EXPRESSION, KEY_LINK_CONDITION,
                KEY_START_EXPRESSION, KEY_REQUIRED);
            if (unknown != null) return unknown;
            String stringError = stringMembersError(entry, "A data-set link", where, //$NON-NLS-1$
                KEY_SOURCE_DATA_SET, KEY_DESTINATION_DATA_SET, KEY_SOURCE_EXPRESSION,
                KEY_DESTINATION_EXPRESSION);
            if (stringError != null) return stringError;
            String source = nonEmptyString(entry, KEY_SOURCE_DATA_SET);
            String destination = nonEmptyString(entry, KEY_DESTINATION_DATA_SET);
            String sourceExpression = nonEmptyString(entry, KEY_SOURCE_EXPRESSION);
            String destinationExpression = nonEmptyString(entry, KEY_DESTINATION_EXPRESSION);
            if (source == null || destination == null || sourceExpression == null
                || destinationExpression == null)
            {
                return "Data-set link '" + where + "' needs non-empty sourceDataSet, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "destinationDataSet, sourceExpression, and destinationExpression. Add the " //$NON-NLS-1$
                    + "missing member and retry."; //$NON-NLS-1$
            }
            Boolean parameterListAllowed = boolMember(entry, KEY_PARAMETER_LIST_ALLOWED);
            if (entry.has(KEY_PARAMETER_LIST_ALLOWED) && parameterListAllowed == null)
            {
                return "Data-set link '" + where + "' member '" + KEY_PARAMETER_LIST_ALLOWED //$NON-NLS-1$ //$NON-NLS-2$
                    + "' must be true or false."; //$NON-NLS-1$
            }
            Boolean required = boolMember(entry, KEY_REQUIRED);
            if (entry.has(KEY_REQUIRED) && required == null)
            {
                return "Data-set link '" + where + "' member '" + KEY_REQUIRED //$NON-NLS-1$ //$NON-NLS-2$
                    + "' must be true or false."; //$NON-NLS-1$
            }
            for (String member : new String[] {KEY_PARAMETER, KEY_LINK_CONDITION_EXPRESSION,
                KEY_LINK_CONDITION, KEY_START_EXPRESSION})
            {
                if (entry.has(member) && !entry.get(member).isJsonNull()
                    && !isStringMember(entry, member))
                {
                    return "Data-set link '" + where + "' member '" + member //$NON-NLS-1$ //$NON-NLS-2$
                        + "' must be a string or null."; //$NON-NLS-1$
                }
            }
            if (entry.has(KEY_LINK_CONDITION_EXPRESSION) && entry.has(KEY_LINK_CONDITION)
                && !java.util.Objects.equals(stringMember(entry, KEY_LINK_CONDITION_EXPRESSION),
                    stringMember(entry, KEY_LINK_CONDITION)))
            {
                return "Data-set link '" + where + "' supplies both canonical '" //$NON-NLS-1$ //$NON-NLS-2$
                    + KEY_LINK_CONDITION_EXPRESSION + "' and legacy '" + KEY_LINK_CONDITION //$NON-NLS-1$
                    + "' with different values. Keep only the canonical member."; //$NON-NLS-1$
            }
            String linkCondition = stringMember(entry, KEY_LINK_CONDITION_EXPRESSION);
            if (linkCondition == null) linkCondition = stringMember(entry, KEY_LINK_CONDITION);
            plan.dataSetLinks.add(new DataSetLinkPlan(source, destination, sourceExpression,
                destinationExpression, stringMember(entry, KEY_PARAMETER),
                parameterListAllowed, linkCondition,
                stringMember(entry, KEY_START_EXPRESSION), required));
        }
        return null;
    }

    private static FieldParseResult parseField(JsonObject entry, String where,
        DcsPresentationParser.LanguageContext languages)
    {
        String unknown = unknownMembers(entry, where, KEY_DATA_PATH, KEY_FIELD, KEY_NAME,
            KEY_TITLE, KEY_ROLE, KEY_USE_RESTRICTION, KEY_VALUE_TYPE, KEY_APPEARANCE,
            KEY_ATTRIBUTE_USE_RESTRICTION, KEY_PRESENTATION_EXPRESSION, KEY_ORDER_EXPRESSIONS,
            KEY_IN_HIERARCHY_DATA_SET, KEY_IN_HIERARCHY_DATA_SET_PARAMETER,
            KEY_AVAILABLE_VALUES, KEY_INPUT_PARAMETERS);
        if (unknown != null)
        {
            return FieldParseResult.failed(unknown);
        }
        String stringError = stringMembersError(entry, "A field", where, KEY_DATA_PATH, //$NON-NLS-1$
            KEY_FIELD, KEY_NAME);
        if (stringError != null) return FieldParseResult.failed(stringError);
        String dataPath = nonEmptyString(entry, KEY_DATA_PATH);
        if (dataPath == null)
        {
            return FieldParseResult.failed("A field (" + where + ") needs a non-empty 'dataPath'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // 'field' is the source query column; fall back to the payload's 'name' alias, else the dataPath.
        String field = nonEmptyString(entry, KEY_FIELD);
        if (field == null)
        {
            field = nonEmptyString(entry, KEY_NAME);
        }
        TitleResult title = parseTitle(entry, where, languages);
        if (title.error != null)
        {
            return FieldParseResult.failed(title.error);
        }
        RoleResult role = parseRole(entry, where);
        if (role.error != null)
        {
            return FieldParseResult.failed(role.error);
        }
        UseRestrictionResult restriction = parseUseRestriction(entry, where);
        if (restriction.error != null)
        {
            return FieldParseResult.failed(restriction.error);
        }
        return FieldParseResult.ok(new FieldPlan(dataPath, field, title.plan, role.plan,
            restriction.plan, entry.deepCopy()));
    }

    private static String parseParameters(JsonObject spec, Plan plan,
        DcsPresentationParser.LanguageContext languages)
    {
        List<JsonObject> entries = objectArray(spec, KEY_PARAMETERS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_PARAMETERS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            String error = parseParameter(entries.get(i), i, plan, languages);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    /**
     * Parses + validates one {@code parameters[index]} entry (name, optional {@code valueType} object,
     * optional title, optional {@code use} enum) into a {@link ParameterPlan}, or a ready error.
     */
    private static String parseParameter(JsonObject entry, int index, Plan plan,
        DcsPresentationParser.LanguageContext languages)
    {
        String where = KEY_PARAMETERS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        String unknown = unknownMembers(entry, where, KEY_NAME, KEY_VALUE_TYPE, KEY_TITLE, KEY_USE,
            KEY_VALUES, KEY_AVAILABLE_VALUES, KEY_EXPRESSION, KEY_USE_RESTRICTION_FLAG,
            KEY_VALUE_LIST_ALLOWED, KEY_AVAILABLE_AS_FIELD, KEY_DENY_INCOMPLETE_VALUES,
            KEY_FUNCTIONAL_OPTIONS_PARAMETER, KEY_INPUT_PARAMETERS);
        if (unknown != null)
        {
            return unknown;
        }
        String stringError = stringMembersError(entry, "A parameter", where, KEY_NAME, KEY_USE); //$NON-NLS-1$
        if (stringError != null) return stringError;
        String name = nonEmptyString(entry, KEY_NAME);
        if (name == null)
        {
            return ERR_PARAMETER + where + ERR_NEEDS_NAME;
        }
        JsonElement valueTypeSpec = null;
        if (entry.has(KEY_VALUE_TYPE) && !entry.get(KEY_VALUE_TYPE).isJsonNull())
        {
            valueTypeSpec = entry.get(KEY_VALUE_TYPE);
            if (!valueTypeSpec.isJsonObject())
            {
                return ERR_PARAMETER + where + ") 'valueType' must be an object like " //$NON-NLS-1$
                    + "{types:[{kind:'String'}]}."; //$NON-NLS-1$
            }
        }
        TitleResult title = parseTitle(entry, where, languages);
        if (title.error != null)
        {
            return title.error;
        }
        DataCompositionParameterUse use = null;
        if (entry.has(KEY_USE) && !entry.get(KEY_USE).isJsonNull())
        {
            use = resolveEnum(DataCompositionParameterUse.values(), stringMember(entry, KEY_USE));
            if (use == null)
            {
                return ERR_PARAMETER + where + ") 'use' must be one of " //$NON-NLS-1$
                    + enumTokens(DataCompositionParameterUse.values()) + "; got '" //$NON-NLS-1$
                    + stringMember(entry, KEY_USE) + "'."; //$NON-NLS-1$
            }
        }
        plan.parameters.add(new ParameterPlan(name, valueTypeSpec, title.plan, use, entry.deepCopy()));
        return null;
    }

    private static String parseCalculatedFields(JsonObject spec, Plan plan,
        DcsPresentationParser.LanguageContext languages)
    {
        List<JsonObject> entries = objectArray(spec, KEY_CALCULATED_FIELDS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_CALCULATED_FIELDS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            String error = parseCalculatedField(entries.get(i), i, plan, languages);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    /**
     * Parses + validates one {@code calculatedFields[index]} entry (a non-empty {@code dataPath}, an
     * explicitly supplied string {@code expression}, and an optional {@code title}) into a
     * {@link CalculatedFieldPlan}, or a ready error naming the exact offending entry. An empty
     * expression is meaningful for a run-time-filled calculated field and must be preserved.
     */
    private static String parseCalculatedField(JsonObject entry, int index, Plan plan,
        DcsPresentationParser.LanguageContext languages)
    {
        String where = KEY_CALCULATED_FIELDS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        String unknown = unknownMembers(entry, where, KEY_DATA_PATH, KEY_EXPRESSION, KEY_TITLE,
            KEY_VALUE_TYPE, KEY_APPEARANCE, KEY_USE_RESTRICTION,
            KEY_PRESENTATION_EXPRESSION, KEY_ORDER_EXPRESSION, KEY_AVAILABLE_VALUES,
            KEY_INPUT_PARAMETERS);
        if (unknown != null)
        {
            return unknown;
        }
        String stringError = stringMembersError(entry, "A calculated field", where, //$NON-NLS-1$
            KEY_DATA_PATH, KEY_EXPRESSION);
        if (stringError != null) return stringError;
        String dataPath = nonEmptyString(entry, KEY_DATA_PATH);
        if (dataPath == null)
        {
            return ERR_CALCULATED_FIELD + where + ") needs a non-empty '" + KEY_DATA_PATH + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!entry.has(KEY_EXPRESSION))
        {
            return ERR_CALCULATED_FIELD + where + ") needs an '" + KEY_EXPRESSION //$NON-NLS-1$
                + "' member. Pass an empty string only when intentionally resetting it."; //$NON-NLS-1$
        }
        if (!isStringMember(entry, KEY_EXPRESSION))
        {
            return ERR_CALCULATED_FIELD + where + ") member '" + KEY_EXPRESSION //$NON-NLS-1$
                + "' must be a string. Pass an empty string only when intentionally resetting it."; //$NON-NLS-1$
        }
        String expression = stringMember(entry, KEY_EXPRESSION);
        TitleResult title = parseTitle(entry, where, languages);
        if (title.error != null)
        {
            return title.error;
        }
        plan.calculatedFields.add(new CalculatedFieldPlan(dataPath, expression, title.plan,
            entry.deepCopy()));
        return null;
    }

    private static String parseTotalFields(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_TOTAL_FIELDS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_TOTAL_FIELDS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_TOTAL_FIELDS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            String unknown = unknownMembers(entry, where, KEY_DATA_PATH, KEY_EXPRESSION, KEY_GROUPS);
            if (unknown != null)
            {
                return unknown;
            }
            String stringError = stringMembersError(entry, "A total field", where, //$NON-NLS-1$
                KEY_DATA_PATH, KEY_EXPRESSION);
            if (stringError != null) return stringError;
            String dataPath = nonEmptyString(entry, KEY_DATA_PATH);
            if (dataPath == null)
            {
                return ERR_TOTAL_FIELD + where + ") needs a non-empty '" + KEY_DATA_PATH + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            String expression = nonEmptyString(entry, KEY_EXPRESSION);
            if (expression == null)
            {
                return ERR_TOTAL_FIELD + where + ") needs a non-empty '" + KEY_EXPRESSION + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            List<String> groups = stringArray(entry, KEY_GROUPS);
            if (entry.has(KEY_GROUPS) && groups == null)
            {
                return ERR_TOTAL_FIELD + where + ") 'groups' must be an array of strings."; //$NON-NLS-1$
            }
            plan.totalFields.add(new TotalFieldPlan(dataPath, expression, groups));
        }
        return null;
    }

    /**
     * Parses an optional {@code title}: a plain string (a language-neutral value) or an object
     * {@code {code:text}} (a localized string keyed by language code). Absent -> a {@code null} plan.
     */
    private static TitleResult parseTitle(JsonObject entry, String where,
        DcsPresentationParser.LanguageContext languages)
    {
        if (!entry.has(KEY_TITLE) || entry.get(KEY_TITLE).isJsonNull())
        {
            return TitleResult.ok(null);
        }
        DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(entry.get(KEY_TITLE),
            languages, where + "." + KEY_TITLE); //$NON-NLS-1$
        return parsed.isSuccess() ? TitleResult.ok(parsed.plan()) : TitleResult.failed(parsed.error());
    }

    /**
     * Parses an optional structured field {@code role}: an object of boolean flags ({@code dimension} /
     * {@code main} / {@code required} / {@code ignoreNullValues} / {@code dimensionAttribute} /
     * {@code account} / {@code balance}) plus an optional {@code periodType} enum
     * (Main / Additional / Specify) and a {@code periodNumber}. Absent -> a {@code null} plan; present but
     * with no recognized key -> an actionable error.
     */
    private static RoleResult parseRole(JsonObject entry, String where)
    {
        if (!entry.has(KEY_ROLE) || entry.get(KEY_ROLE).isJsonNull())
        {
            return RoleResult.ok(null);
        }
        JsonElement element = entry.get(KEY_ROLE);
        if (!element.isJsonObject())
        {
            return RoleResult.failed(ERR_FIELD_ROLE + where + ") must be an object of flags, e.g. " //$NON-NLS-1$
                + "{dimension:true}."); //$NON-NLS-1$
        }
        JsonObject roleObj = element.getAsJsonObject();
        String unknown = unknownMembers(roleObj, where + "." + KEY_ROLE, ROLE_DIMENSION, ROLE_MAIN, //$NON-NLS-1$
            ROLE_REQUIRED, ROLE_IGNORE_NULL, ROLE_DIMENSION_ATTRIBUTE, ROLE_ACCOUNT, ROLE_BALANCE,
            ROLE_PERIOD_TYPE, ROLE_PERIOD_NUMBER);
        if (unknown != null)
        {
            return RoleResult.failed(unknown);
        }
        RolePlan role = new RolePlan();
        role.dimension = boolMember(roleObj, ROLE_DIMENSION);
        role.main = boolMember(roleObj, ROLE_MAIN);
        role.required = boolMember(roleObj, ROLE_REQUIRED);
        role.ignoreNullValues = boolMember(roleObj, ROLE_IGNORE_NULL);
        role.dimensionAttribute = boolMember(roleObj, ROLE_DIMENSION_ATTRIBUTE);
        role.account = boolMember(roleObj, ROLE_ACCOUNT);
        role.balance = boolMember(roleObj, ROLE_BALANCE);
        role.periodNumber = intMember(roleObj, ROLE_PERIOD_NUMBER);
        for (String booleanKey : new String[] {ROLE_DIMENSION, ROLE_MAIN, ROLE_REQUIRED,
            ROLE_IGNORE_NULL, ROLE_DIMENSION_ATTRIBUTE, ROLE_ACCOUNT, ROLE_BALANCE})
        {
            if (roleObj.has(booleanKey) && boolMember(roleObj, booleanKey) == null)
            {
                return RoleResult.failed(ERR_FIELD_ROLE + where + ") '" + booleanKey //$NON-NLS-1$
                    + "' must be true or false."); //$NON-NLS-1$
            }
        }
        if (roleObj.has(ROLE_PERIOD_NUMBER) && role.periodNumber == null)
        {
            return RoleResult.failed(ERR_FIELD_ROLE + where + ") 'periodNumber' must be an integer."); //$NON-NLS-1$
        }
        if (roleObj.has(ROLE_PERIOD_TYPE) && !roleObj.get(ROLE_PERIOD_TYPE).isJsonNull())
        {
            if (!isStringMember(roleObj, ROLE_PERIOD_TYPE))
                return RoleResult.failed(ERR_FIELD_ROLE + where //$NON-NLS-1$
                    + ") 'periodType' must be a string naming one of " //$NON-NLS-1$
                    + enumTokens(DataCompositionPeriodType.values()) + "."); //$NON-NLS-1$
            role.periodType = resolveEnum(DataCompositionPeriodType.values(),
                stringMember(roleObj, ROLE_PERIOD_TYPE));
            if (role.periodType == null)
            {
                return RoleResult.failed(ERR_FIELD_ROLE + where + ") 'periodType' must be one of " //$NON-NLS-1$
                    + enumTokens(DataCompositionPeriodType.values()) + "; got '" //$NON-NLS-1$
                    + stringMember(roleObj, ROLE_PERIOD_TYPE) + "'."); //$NON-NLS-1$
            }
        }
        if (role.isEmpty())
        {
            return RoleResult.failed(ERR_FIELD_ROLE + where + ") needs at least one of 'dimension', " //$NON-NLS-1$
                + "'main', 'required', 'ignoreNullValues', 'dimensionAttribute', 'account', 'balance', " //$NON-NLS-1$
                + "'periodType' or 'periodNumber'."); //$NON-NLS-1$
        }
        return RoleResult.ok(role);
    }

    /** Parses a field's optional use-restriction flags. */
    private static UseRestrictionResult parseUseRestriction(JsonObject entry, String where)
    {
        if (!entry.has(KEY_USE_RESTRICTION) || entry.get(KEY_USE_RESTRICTION).isJsonNull())
        {
            return UseRestrictionResult.ok(null);
        }
        JsonElement element = entry.get(KEY_USE_RESTRICTION);
        if (!element.isJsonObject())
        {
            return UseRestrictionResult.failed("Field useRestriction (" + where //$NON-NLS-1$
                + ") must be an object with boolean field/condition/group/order members."); //$NON-NLS-1$
        }
        JsonObject object = element.getAsJsonObject();
        String unknown = unknownMembers(object, where + "." + KEY_USE_RESTRICTION, //$NON-NLS-1$
            RESTRICTION_FIELD, RESTRICTION_CONDITION, RESTRICTION_GROUP, RESTRICTION_ORDER);
        if (unknown != null)
        {
            return UseRestrictionResult.failed(unknown);
        }
        UseRestrictionPlan plan = new UseRestrictionPlan();
        plan.field = boolMember(object, RESTRICTION_FIELD);
        plan.condition = boolMember(object, RESTRICTION_CONDITION);
        plan.group = boolMember(object, RESTRICTION_GROUP);
        plan.order = boolMember(object, RESTRICTION_ORDER);
        for (String key : object.keySet())
        {
            if (boolMember(object, key) == null)
            {
                return UseRestrictionResult.failed("Field useRestriction (" + where + ") member '" //$NON-NLS-1$ //$NON-NLS-2$
                    + key + "' must be true or false."); //$NON-NLS-1$
            }
        }
        if (plan.isEmpty())
        {
            return UseRestrictionResult.failed("Field useRestriction (" + where //$NON-NLS-1$
                + ") needs at least one of: field, condition, group, order."); //$NON-NLS-1$
        }
        return UseRestrictionResult.ok(plan);
    }

    // ---- enum resolution ----------------------------------------------------------------------

    /**
     * Resolves an EMF enum literal by name, case-insensitively, matching the Java constant name, the EMF
     * literal, or the EMF name. Returns {@code null} for a blank or unknown token (the caller builds the
     * actionable error).
     */
    private static <E extends Enum<E> & Enumerator> E resolveEnum(E[] values, String token)
    {
        if (token == null)
        {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        for (E value : values)
        {
            if (trimmed.equalsIgnoreCase(value.name()) || trimmed.equalsIgnoreCase(value.getLiteral())
                || trimmed.equalsIgnoreCase(value.getName()))
            {
                return value;
            }
        }
        return null;
    }

    private static <E extends Enum<E>> String enumTokens(E[] values)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(values[i].name());
        }
        return sb.toString();
    }

    // ---- JSON member helpers ------------------------------------------------------------------

    /**
     * Reads a key as an array of JSON objects: {@code null} when the key is present but is not an array of
     * objects (a shape error); an empty list when the key is absent.
     */
    private static List<JsonObject> objectArray(JsonObject spec, String key)
    {
        if (!spec.has(key) || spec.get(key).isJsonNull())
        {
            return new ArrayList<>();
        }
        JsonElement element = spec.get(key);
        if (!element.isJsonArray())
        {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : array)
        {
            if (item == null || !item.isJsonObject())
            {
                return null;
            }
            result.add(item.getAsJsonObject());
        }
        return result;
    }

    private static List<String> stringArray(JsonObject object, String key)
    {
        if (!object.has(key) || object.get(key).isJsonNull())
        {
            return null;
        }
        JsonElement element = object.get(key);
        if (!element.isJsonArray())
        {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray())
        {
            if (item == null || !item.isJsonPrimitive()
                || !item.getAsJsonPrimitive().isString())
            {
                return null;
            }
            result.add(item.getAsString());
        }
        return result;
    }

    private static String unknownMembers(JsonObject object, String where, String... accepted)
    {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(accepted));
        for (String key : object.keySet())
        {
            if (!allowed.contains(key))
            {
                return "Unknown member '" + key + "' in " + where + ". Accepted members: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + String.join(", ", allowed) + ". Remove '" + key + "' or use one of them."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return null;
    }

    private static String notAnObjectArray(String key)
    {
        return "'" + key + "' must be an array of objects."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String stringMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement element = obj.get(name);
        return element != null && element.isJsonPrimitive()
            && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
    }

    static String stringMembersError(JsonObject object, String subject, String where,
        String... members)
    {
        for (String member : members)
        {
            if (object.has(member) && !isStringMember(object, member))
            {
                return subject + " (" + where + ") member '" + member //$NON-NLS-1$ //$NON-NLS-2$
                    + "' must be a string."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static boolean isStringMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return false;
        }
        JsonElement element = obj.get(name);
        return element != null && element.isJsonPrimitive()
            && element.getAsJsonPrimitive().isString();
    }

    private static String nonEmptyString(JsonObject obj, String name)
    {
        String value = stringMember(obj, name);
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static Integer intMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement element = obj.get(name);
        if (element == null || !element.isJsonPrimitive())
        {
            return null;
        }
        try
        {
            double d = element.getAsDouble();
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

    private static Boolean boolMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null; // NOSONAR intentional tri-state Boolean; null (absent) is distinct from false
        }
        JsonElement element = obj.get(name);
        if (element == null || !element.isJsonPrimitive())
        {
            return null; // NOSONAR intentional tri-state Boolean; null (absent) is distinct from false
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean())
        {
            return Boolean.valueOf(primitive.getAsBoolean());
        }
        String s = primitive.getAsString().trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.TRUE;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR intentional tri-state Boolean; an unrecognized token is treated as absent
    }

    // ---- parsed spec (pure data) --------------------------------------------------------------

    /** A parsed {@link Plan} OR a ready error message from up-front validation. */
    static final class ParseResult
    {
        final Plan plan;
        final String error;

        private ParseResult(Plan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static ParseResult ok(Plan plan)
        {
            return new ParseResult(plan, null);
        }

        static ParseResult failed(String error)
        {
            return new ParseResult(null, error);
        }
    }

    /** The validated, resolved spec ready to apply to a {@link DataCompositionSchema}. */
    static final class Plan
    {
        final List<DataSourcePlan> dataSources = new ArrayList<>();
        final List<DataSetPlan> dataSets = new ArrayList<>();
        final List<DataSetLinkPlan> dataSetLinks = new ArrayList<>();
        final List<ParameterPlan> parameters = new ArrayList<>();
        final List<CalculatedFieldPlan> calculatedFields = new ArrayList<>();
        final List<TotalFieldPlan> totalFields = new ArrayList<>();
    }

    /** A validated data source (a name + a data source type). */
    static final class DataSourcePlan
    {
        final String name;
        final String type;
        final String connectionString;

        DataSourcePlan(String name, String type, String connectionString)
        {
            this.name = name;
            this.type = type;
            this.connectionString = connectionString;
        }
    }

    /** A validated query data set (a name + query text + optional data source + fields). */
    static final class DataSetPlan
    {
        final String name;
        final String type;
        final String query;
        final String dataSource;
        final String objectName;
        final Boolean autoFill;
        final List<FieldPlan> fields;
        final List<DataSetPlan> items;

        DataSetPlan(String name, String type, String query, String dataSource, String objectName,
            Boolean autoFill, List<FieldPlan> fields, List<DataSetPlan> items)
        {
            this.name = name;
            this.type = type;
            this.query = query;
            this.dataSource = dataSource;
            this.objectName = objectName;
            this.autoFill = autoFill;
            this.fields = fields;
            this.items = items;
        }
    }

    static final class DataSetLinkPlan
    {
        final String sourceDataSet;
        final String destinationDataSet;
        final String sourceExpression;
        final String destinationExpression;
        final String parameter;
        final Boolean parameterListAllowed;
        final String linkCondition;
        final String startExpression;
        final Boolean required;

        DataSetLinkPlan(String sourceDataSet, String destinationDataSet, String sourceExpression,
            String destinationExpression, String parameter, Boolean parameterListAllowed,
            String linkCondition, String startExpression, Boolean required)
        {
            this.sourceDataSet = sourceDataSet;
            this.destinationDataSet = destinationDataSet;
            this.sourceExpression = sourceExpression;
            this.destinationExpression = destinationExpression;
            this.parameter = parameter;
            this.parameterListAllowed = parameterListAllowed;
            this.linkCondition = linkCondition;
            this.startExpression = startExpression;
            this.required = required;
        }
    }

    private static final class DataSetParseResult
    {
        final DataSetPlan plan; final String error;
        private DataSetParseResult(DataSetPlan plan, String error)
        { this.plan = plan; this.error = error; }
        static DataSetParseResult ok(DataSetPlan plan) { return new DataSetParseResult(plan, null); }
        static DataSetParseResult failed(String error) { return new DataSetParseResult(null, error); }
    }

    /** A validated data set field (a data path + optional source field / title / role). */
    static final class FieldPlan
    {
        final String dataPath;
        final String field;
        final DcsPresentationParser.Plan title;
        final RolePlan role;
        final UseRestrictionPlan useRestriction;
        final JsonObject members;
        ResolvedMembers resolved;

        FieldPlan(String dataPath, String field, DcsPresentationParser.Plan title, RolePlan role,
            UseRestrictionPlan useRestriction, JsonObject members)
        {
            this.dataPath = dataPath;
            this.field = field;
            this.title = title;
            this.role = role;
            this.useRestriction = useRestriction;
            this.members = members;
        }
    }

    /** A validated schema parameter (a name + a raw value-type spec + optional title / use). */
    static final class ParameterPlan
    {
        final String name;
        final JsonElement valueTypeSpec;
        final DcsPresentationParser.Plan title;
        final DataCompositionParameterUse use;
        final JsonObject members;
        ResolvedMembers resolved;

        ParameterPlan(String name, JsonElement valueTypeSpec, DcsPresentationParser.Plan title,
            DataCompositionParameterUse use, JsonObject members)
        {
            this.name = name;
            this.valueTypeSpec = valueTypeSpec;
            this.title = title;
            this.use = use;
            this.members = members;
        }
    }

    /** A validated calculated field (a data path + expression + optional title). */
    static final class CalculatedFieldPlan
    {
        final String dataPath;
        final String expression;
        final DcsPresentationParser.Plan title;
        final JsonObject members;
        ResolvedMembers resolved;

        CalculatedFieldPlan(String dataPath, String expression, DcsPresentationParser.Plan title)
        {
            this(dataPath, expression, title, new JsonObject());
        }

        CalculatedFieldPlan(String dataPath, String expression, DcsPresentationParser.Plan title,
            JsonObject members)
        {
            this.dataPath = dataPath;
            this.expression = expression;
            this.title = title;
            this.members = members;
        }
    }

    /** Detached, fully validated member values built before the first schema mutation. */
    static final class ResolvedMembers
    {
        TypeDescription valueType;
        DataCompositionAppearance appearance;
        DataCompositionSchemaFieldUseRestriction attributeUseRestriction;
        String presentationExpression;
        List<DataCompositionOrderExpression> orderExpressions;
        String inHierarchyDataSet;
        String inHierarchyDataSetParameter;
        List<AvailableValue> availableValues;
        InputParameters inputParameters;
        List<Value> values;
        String expression;
        Boolean useRestriction;
        Boolean valueListAllowed;
        Boolean availableAsField;
        Boolean denyIncompleteValues;
        String functionalOptionsParameter;
    }

    private static final class StringResult
    {
        final String value; final String error;
        private StringResult(String value, String error) { this.value = value; this.error = error; }
        static StringResult success(String value) { return new StringResult(value, null); }
        static StringResult failure(String error) { return new StringResult(null, error); }
    }

    private static final class BooleanResult
    {
        final Boolean value; final String error;
        private BooleanResult(Boolean value, String error) { this.value = value; this.error = error; }
        static BooleanResult success(Boolean value) { return new BooleanResult(value, null); }
        static BooleanResult failure(String error) { return new BooleanResult(null, error); }
    }

    private static final class ValueBuildResult
    {
        final Value value; final String error;
        private ValueBuildResult(Value value, String error) { this.value = value; this.error = error; }
        static ValueBuildResult success(Value value) { return new ValueBuildResult(value, null); }
        static ValueBuildResult failure(String error) { return new ValueBuildResult(null, error); }
    }

    private static final class ValuesResult
    {
        final List<Value> values; final String error;
        private ValuesResult(List<Value> values, String error) { this.values = values; this.error = error; }
        static ValuesResult success(List<Value> values) { return new ValuesResult(values, null); }
        static ValuesResult failure(String error) { return new ValuesResult(null, error); }
    }

    private static final class AvailableValuesResult
    {
        final List<AvailableValue> values; final String error;
        private AvailableValuesResult(List<AvailableValue> values, String error)
        { this.values = values; this.error = error; }
        static AvailableValuesResult success(List<AvailableValue> values)
        { return new AvailableValuesResult(values, null); }
        static AvailableValuesResult failure(String error)
        { return new AvailableValuesResult(null, error); }
    }

    private static final class InputParametersResult
    {
        final InputParameters value; final String error;
        private InputParametersResult(InputParameters value, String error)
        { this.value = value; this.error = error; }
        static InputParametersResult success(InputParameters value)
        { return new InputParametersResult(value, null); }
        static InputParametersResult failure(String error)
        { return new InputParametersResult(null, error); }
    }

    /** A validated total field. */
    static final class TotalFieldPlan
    {
        final String dataPath;
        final String expression;
        final List<String> groups;

        TotalFieldPlan(String dataPath, String expression, List<String> groups)
        {
            this.dataPath = dataPath;
            this.expression = expression;
            this.groups = groups;
        }
    }

    /** A validated field role: each flag is a tri-state {@link Boolean} (null = leave the model default). */
    static final class RolePlan
    {
        Boolean dimension;
        Boolean main;
        Boolean required;
        Boolean ignoreNullValues;
        Boolean dimensionAttribute;
        Boolean account;
        Boolean balance;
        DataCompositionPeriodType periodType;
        Integer periodNumber;

        boolean isEmpty()
        {
            return dimension == null && main == null && required == null && ignoreNullValues == null
                && dimensionAttribute == null && account == null && balance == null && periodType == null
                && periodNumber == null;
        }
    }

    /** A validated field use restriction. */
    static final class UseRestrictionPlan
    {
        Boolean field;
        Boolean condition;
        Boolean group;
        Boolean order;

        boolean isEmpty()
        {
            return field == null && condition == null && group == null && order == null;
        }
    }

    /** The outcome of parsing a field (a resolved {@link FieldPlan} or a ready error). */
    private static final class FieldParseResult
    {
        final FieldPlan plan;
        final String error;

        private FieldParseResult(FieldPlan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static FieldParseResult ok(FieldPlan plan)
        {
            return new FieldParseResult(plan, null);
        }

        static FieldParseResult failed(String error)
        {
            return new FieldParseResult(null, error);
        }
    }

    /** The outcome of parsing a title (a shared presentation plan, possibly {@code null}, or an error). */
    private static final class TitleResult
    {
        final DcsPresentationParser.Plan plan;
        final String error;

        private TitleResult(DcsPresentationParser.Plan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static TitleResult ok(DcsPresentationParser.Plan plan)
        {
            return new TitleResult(plan, null);
        }

        static TitleResult failed(String error)
        {
            return new TitleResult(null, error);
        }
    }

    /** The outcome of parsing a role (a resolved {@link RolePlan}, possibly {@code null}, or an error). */
    private static final class RoleResult
    {
        final RolePlan plan;
        final String error;

        private RoleResult(RolePlan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static RoleResult ok(RolePlan plan)
        {
            return new RoleResult(plan, null);
        }

        static RoleResult failed(String error)
        {
            return new RoleResult(null, error);
        }
    }

    /** The outcome of parsing a field use restriction. */
    private static final class UseRestrictionResult
    {
        final UseRestrictionPlan plan;
        final String error;

        private UseRestrictionResult(UseRestrictionPlan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static UseRestrictionResult ok(UseRestrictionPlan plan)
        {
            return new UseRestrictionResult(plan, null);
        }

        static UseRestrictionResult failed(String error)
        {
            return new UseRestrictionResult(null, error);
        }
    }
}
