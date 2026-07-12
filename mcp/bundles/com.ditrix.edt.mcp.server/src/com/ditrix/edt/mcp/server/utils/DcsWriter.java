/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.Enumerator;

import com._1c.g5.v8.dt.dcs.model.common.DataCompositionDataSetFieldRole;
import com._1c.g5.v8.dt.dcs.model.common.DataCompositionPeriodType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterUse;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSource;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Authors the CONTENT of a 1C Data Composition Schema (СКД / a {@code .dcs}
 * resource) - the {@link DataCompositionSchema} model behind a Report's Data Composition Schema
 * {@code BasicTemplate} - from the structured {@code dcs} JSON a client passes to {@code modify_metadata}.
 * The writer is a pure, typed EMF transformation: it takes an already-resolved
 * {@link DataCompositionSchema} (reached by the caller inside a BM boundary from
 * {@code BasicTemplate.getTemplate()}) and applies a parsed spec of data sources / query data sets (with
 * their fields) / schema parameters using the TYPED DCS API (the schema / core / common
 * {@code DcsFactory} singletons) - never a reflective {@code eSet}, never opening a transaction or
 * force-exporting (the caller owns both, exactly like {@link SpreadsheetTemplateWriter}).
 *
 * <p>The v1 {@code dcs} payload shape:</p>
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
 *                    "title":"Period", "use":"Auto"} ]
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

    // ---- per-entry keys -----------------------------------------------------------------------

    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_QUERY = "query"; //$NON-NLS-1$
    private static final String KEY_DATA_SOURCE = "dataSource"; //$NON-NLS-1$
    private static final String KEY_AUTO_FILL = "autoFillFields"; //$NON-NLS-1$
    private static final String KEY_FIELDS = "fields"; //$NON-NLS-1$
    private static final String KEY_DATA_PATH = "dataPath"; //$NON-NLS-1$
    private static final String KEY_FIELD = "field"; //$NON-NLS-1$
    private static final String KEY_TITLE = "title"; //$NON-NLS-1$
    private static final String KEY_ROLE = "role"; //$NON-NLS-1$
    private static final String KEY_VALUE_TYPE = "valueType"; //$NON-NLS-1$
    private static final String KEY_USE = "use"; //$NON-NLS-1$

    // ---- validation error-message stems (java:S1192) --------------------------------------------

    private static final String ERR_DATA_SET = "A data set ("; //$NON-NLS-1$
    private static final String ERR_PARAMETER = "A parameter ("; //$NON-NLS-1$
    private static final String ERR_FIELD_ROLE = "A field role ("; //$NON-NLS-1$
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

    /** The only v1-supported data set type token. */
    private static final String TYPE_QUERY = "query"; //$NON-NLS-1$

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

        private Result(String error, int dataSources, int dataSets, int fields, int parameters)
        {
            this.error = error;
            this.dataSources = dataSources;
            this.dataSets = dataSets;
            this.fields = fields;
            this.parameters = parameters;
        }

        static Result failed(String error)
        {
            return new Result(error, 0, 0, 0, 0);
        }

        static Result ok(int dataSources, int dataSets, int fields, int parameters)
        {
            return new Result(null, dataSources, dataSets, fields, parameters);
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
     * force-export - the caller ({@code ModifyMetadataTool}) reaches the schema inside its own BM write
     * boundary and drains it to the {@code .dcs} after this returns.
     *
     * @param schema the report's Data Composition Schema content (must not be {@code null})
     * @param spec the {@code dcs} payload (see the class javadoc for the shape)
     * @param typeResolver builds a parameter's value type from its {@code valueType} spec; may be
     *            {@code null} only when no parameter carries a {@code valueType}
     * @return a {@link Result} - check {@link Result#hasError()} first; {@link Result#error} is a ready
     *         {@link ToolResult#error} JSON string the caller returns verbatim
     */
    public static Result apply(DataCompositionSchema schema, JsonObject spec, TypeResolver typeResolver)
    {
        if (schema == null)
        {
            return Result.failed(ToolResult.error(
                "The report has no Data Composition Schema content to write to.").toJson()); //$NON-NLS-1$
        }
        ParseResult parsed = parse(spec);
        if (parsed.error != null)
        {
            return Result.failed(ToolResult.error(parsed.error).toJson());
        }
        Plan plan = parsed.plan;

        // Resolve every parameter value type BEFORE any mutation, so a bad type spec mutates nothing.
        TypeDescription[] paramTypes = new TypeDescription[plan.parameters.size()];
        for (int i = 0; i < plan.parameters.size(); i++)
        {
            ParameterPlan param = plan.parameters.get(i);
            if (param.valueTypeSpec == null)
            {
                continue;
            }
            if (typeResolver == null)
            {
                return Result.failed(ToolResult.error("Parameter '" + param.name //$NON-NLS-1$
                    + "' declares a 'valueType' but no type resolver is available in this context.") //$NON-NLS-1$
                    .toJson());
            }
            TypeResolution resolution = typeResolver.resolve(param.valueTypeSpec);
            if (resolution.error != null)
            {
                return Result.failed(ToolResult.error("Parameter '" + param.name + "' valueType: " //$NON-NLS-1$ //$NON-NLS-2$
                    + resolution.error).toJson());
            }
            paramTypes[i] = resolution.typeDescription;
        }

        int sources = applyDataSets(schema, plan);
        int fields = applyFields(schema, plan);
        for (int i = 0; i < plan.parameters.size(); i++)
        {
            applyParameter(schema, plan.parameters.get(i), paramTypes[i]);
        }

        return Result.ok(sources, plan.dataSets.size(), fields, plan.parameters.size());
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
            ensureDataSource(schema, source.name, source.type);
        }
        String defaultSourceName = plan.dataSources.isEmpty() ? null : plan.dataSources.get(0).name;

        for (DataSetPlan setPlan : plan.dataSets)
        {
            DataCompositionSchemaDataSetQuery dataSet = getOrCreateQueryDataSet(schema, setPlan.name);
            if (setPlan.query != null)
            {
                dataSet.setQuery(setPlan.query);
            }
            String sourceName = setPlan.dataSource;
            if (sourceName == null)
            {
                if (defaultSourceName == null)
                {
                    defaultSourceName = DEFAULT_DATA_SOURCE_NAME;
                }
                sourceName = defaultSourceName;
            }
            ensureDataSource(schema, sourceName, LOCAL_SOURCE_TYPE);
            dataSet.setDataSource(sourceName);
            dataSet.setAutoFillAvailableFields(
                setPlan.autoFill != null ? setPlan.autoFill.booleanValue() : setPlan.fields.isEmpty());
        }
        return schema.getDataSources().size();
    }

    /** Applies each data set's explicit fields, returning the total number of fields authored. */
    private static int applyFields(DataCompositionSchema schema, Plan plan)
    {
        int count = 0;
        for (DataSetPlan setPlan : plan.dataSets)
        {
            if (setPlan.fields.isEmpty())
            {
                continue;
            }
            DataCompositionSchemaDataSetQuery dataSet = getOrCreateQueryDataSet(schema, setPlan.name);
            for (FieldPlan fieldPlan : setPlan.fields)
            {
                applyField(dataSet, fieldPlan);
                count++;
            }
        }
        return count;
    }

    /**
     * Writes one data set field: find-or-creates the {@link DataCompositionSchemaDataSetField} by data
     * path, sets its {@code dataPath} / {@code field} (the source query column, defaulted to the data
     * path), an optional {@code title} {@link Presentation}, and an optional structured role.
     */
    private static void applyField(DataCompositionSchemaDataSetQuery dataSet, FieldPlan plan)
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
    }

    /**
     * Writes one schema parameter: find-or-creates the {@link DataCompositionSchemaParameter} by name and
     * sets its (already-resolved) value type, an optional {@code title} {@link Presentation}, and an
     * optional {@link DataCompositionParameterUse use}.
     */
    private static void applyParameter(DataCompositionSchema schema, ParameterPlan plan,
        TypeDescription valueType)
    {
        DataCompositionSchemaParameter parameter = getOrCreateParameter(schema, plan.name);
        if (valueType != null)
        {
            parameter.setValueType(valueType);
        }
        if (plan.title != null)
        {
            parameter.setTitle(buildPresentation(plan.title));
        }
        if (plan.use != null)
        {
            parameter.setUse(plan.use);
        }
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

    /**
     * Builds a core {@link Presentation} from a title plan: a plain string sets the language-neutral
     * {@link Presentation#setValue(String) value}; a localized map populates a {@link LocalString} keyed by
     * language code.
     */
    private static Presentation buildPresentation(TitlePlan plan)
    {
        Presentation presentation =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createPresentation();
        if (plan.value != null)
        {
            presentation.setValue(plan.value);
        }
        else if (plan.localized != null)
        {
            LocalString localString =
                com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString();
            for (Map.Entry<String, String> entry : plan.localized.entrySet())
            {
                localString.getContent().put(entry.getKey(), entry.getValue());
            }
            presentation.setLocalValue(localString);
        }
        return presentation;
    }

    // ---- find-or-create -----------------------------------------------------------------------

    /** Find-or-creates a data source by name, setting its type on create. */
    private static void ensureDataSource(DataCompositionSchema schema, String name, String type)
    {
        for (DataCompositionSchemaDataSource existing : schema.getDataSources())
        {
            if (name.equals(existing.getName()))
            {
                return;
            }
        }
        DataCompositionSchemaDataSource source =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSource();
        source.setName(name);
        source.setDataSourceType(type != null ? type : LOCAL_SOURCE_TYPE);
        schema.getDataSources().add(source);
    }

    /**
     * Find-or-creates a QUERY data set by name. A pre-existing query data set with the same name is
     * reused; a fresh one is appended otherwise. The parse layer already rejected a name clash with a
     * non-query data set, so a same-named existing non-query set (a designer-authored object/union set)
     * is left alone and a fresh query set is added rather than corrupting it.
     */
    private static DataCompositionSchemaDataSetQuery getOrCreateQueryDataSet(DataCompositionSchema schema,
        String name)
    {
        for (DataSet existing : schema.getDataSets())
        {
            if (existing instanceof DataCompositionSchemaDataSetQuery && name.equals(existing.getName()))
            {
                return (DataCompositionSchemaDataSetQuery)existing;
            }
        }
        DataCompositionSchemaDataSetQuery dataSet = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        dataSet.setName(name);
        schema.getDataSets().add(dataSet);
        return dataSet;
    }

    /** Find-or-creates a data set field by data path. */
    private static DataCompositionSchemaDataSetField getOrCreateField(DataCompositionSchemaDataSetQuery dataSet,
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
        if (spec == null)
        {
            return ParseResult.failed("A 'dcs' payload is required, e.g. {dataSets:[{name:'DataSet1'," //$NON-NLS-1$
                + "type:'query',query:'SELECT ...'}]}."); //$NON-NLS-1$
        }
        Plan plan = new Plan();

        String error = parseDataSources(spec, plan);
        if (error == null)
        {
            error = parseDataSets(spec, plan);
        }
        if (error == null)
        {
            error = parseParameters(spec, plan);
        }
        if (error != null)
        {
            return ParseResult.failed(error);
        }
        if (plan.dataSets.isEmpty() && plan.parameters.isEmpty() && plan.dataSources.isEmpty())
        {
            return ParseResult.failed("The 'dcs' payload is empty: provide at least one of 'dataSets', " //$NON-NLS-1$
                + "'parameters' or 'dataSources', e.g. {dataSets:[{name:'DataSet1',type:'query'," //$NON-NLS-1$
                + "query:'SELECT ...'}]}."); //$NON-NLS-1$
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
            String name = nonEmptyString(entry, KEY_NAME);
            if (name == null)
            {
                return "A data source (" + where + ERR_NEEDS_NAME; //$NON-NLS-1$
            }
            String type = nonEmptyString(entry, KEY_TYPE);
            plan.dataSources.add(new DataSourcePlan(name, type != null ? type : LOCAL_SOURCE_TYPE));
        }
        return null;
    }

    private static String parseDataSets(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_DATA_SETS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_DATA_SETS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            String error = parseDataSet(entries.get(i), i, plan);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String parseDataSet(JsonObject entry, int index, Plan plan)
    {
        String where = KEY_DATA_SETS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        String name = nonEmptyString(entry, KEY_NAME);
        if (name == null)
        {
            return ERR_DATA_SET + where + ERR_NEEDS_NAME;
        }
        String type = nonEmptyString(entry, KEY_TYPE);
        if (type != null && !TYPE_QUERY.equalsIgnoreCase(type))
        {
            return ERR_DATA_SET + where + ") 'type' must be 'query' in v1; got '" + type //$NON-NLS-1$
                + "'. Object / union data sets are deferred to v2."; //$NON-NLS-1$
        }
        String query = nonEmptyString(entry, KEY_QUERY);
        if (query == null)
        {
            return "A query data set (" + where + ") needs a non-empty 'query'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String dataSource = nonEmptyString(entry, KEY_DATA_SOURCE);
        Boolean autoFill = boolMember(entry, KEY_AUTO_FILL);

        List<JsonObject> fieldEntries = objectArray(entry, KEY_FIELDS);
        if (fieldEntries == null)
        {
            return ERR_DATA_SET + where + ") '" + KEY_FIELDS + "' must be an array of objects."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<FieldPlan> fields = new ArrayList<>();
        for (int i = 0; i < fieldEntries.size(); i++)
        {
            FieldParseResult field = parseField(fieldEntries.get(i), where + "." + KEY_FIELDS + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (field.error != null)
            {
                return field.error;
            }
            fields.add(field.plan);
        }
        plan.dataSets.add(new DataSetPlan(name, query, dataSource, autoFill, fields));
        return null;
    }

    private static FieldParseResult parseField(JsonObject entry, String where)
    {
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
        TitleResult title = parseTitle(entry, where);
        if (title.error != null)
        {
            return FieldParseResult.failed(title.error);
        }
        RoleResult role = parseRole(entry, where);
        if (role.error != null)
        {
            return FieldParseResult.failed(role.error);
        }
        return FieldParseResult.ok(new FieldPlan(dataPath, field, title.plan, role.plan));
    }

    private static String parseParameters(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_PARAMETERS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_PARAMETERS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_PARAMETERS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
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
            TitleResult title = parseTitle(entry, where);
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
            plan.parameters.add(new ParameterPlan(name, valueTypeSpec, title.plan, use));
        }
        return null;
    }

    /**
     * Parses an optional {@code title}: a plain string (a language-neutral value) or an object
     * {@code {code:text}} (a localized string keyed by language code). Absent -> a {@code null} plan.
     */
    private static TitleResult parseTitle(JsonObject entry, String where)
    {
        if (!entry.has(KEY_TITLE) || entry.get(KEY_TITLE).isJsonNull())
        {
            return TitleResult.ok(null);
        }
        JsonElement element = entry.get(KEY_TITLE);
        if (element.isJsonPrimitive())
        {
            String value = element.getAsString();
            if (value.isEmpty())
            {
                return TitleResult.ok(null);
            }
            return TitleResult.ok(TitlePlan.of(value));
        }
        if (element.isJsonObject())
        {
            Map<String, String> localized = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
            {
                JsonElement text = member.getValue();
                if (text == null || !text.isJsonPrimitive())
                {
                    return TitleResult.failed("A title (" + where + ") localized value for '" //$NON-NLS-1$ //$NON-NLS-2$
                        + member.getKey() + "' must be a string."); //$NON-NLS-1$
                }
                localized.put(member.getKey(), text.getAsString());
            }
            if (localized.isEmpty())
            {
                return TitleResult.ok(null);
            }
            return TitleResult.ok(TitlePlan.ofLocalized(localized));
        }
        return TitleResult.failed("A title (" + where + ") must be a string or a {code:text} object."); //$NON-NLS-1$ //$NON-NLS-2$
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
        RolePlan role = new RolePlan();
        role.dimension = boolMember(roleObj, ROLE_DIMENSION);
        role.main = boolMember(roleObj, ROLE_MAIN);
        role.required = boolMember(roleObj, ROLE_REQUIRED);
        role.ignoreNullValues = boolMember(roleObj, ROLE_IGNORE_NULL);
        role.dimensionAttribute = boolMember(roleObj, ROLE_DIMENSION_ATTRIBUTE);
        role.account = boolMember(roleObj, ROLE_ACCOUNT);
        role.balance = boolMember(roleObj, ROLE_BALANCE);
        role.periodNumber = intMember(roleObj, ROLE_PERIOD_NUMBER);
        if (roleObj.has(ROLE_PERIOD_TYPE) && !roleObj.get(ROLE_PERIOD_TYPE).isJsonNull())
        {
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

    private static String notAnObjectArray(String key)
    {
        return "'" + key + "' must be an array of objects."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String stringMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement element = obj.get(name);
        return (element != null && element.isJsonPrimitive()) ? element.getAsString() : null;
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
        final List<ParameterPlan> parameters = new ArrayList<>();
    }

    /** A validated data source (a name + a data source type). */
    static final class DataSourcePlan
    {
        final String name;
        final String type;

        DataSourcePlan(String name, String type)
        {
            this.name = name;
            this.type = type;
        }
    }

    /** A validated query data set (a name + query text + optional data source + fields). */
    static final class DataSetPlan
    {
        final String name;
        final String query;
        final String dataSource;
        final Boolean autoFill;
        final List<FieldPlan> fields;

        DataSetPlan(String name, String query, String dataSource, Boolean autoFill, List<FieldPlan> fields)
        {
            this.name = name;
            this.query = query;
            this.dataSource = dataSource;
            this.autoFill = autoFill;
            this.fields = fields;
        }
    }

    /** A validated data set field (a data path + optional source field / title / role). */
    static final class FieldPlan
    {
        final String dataPath;
        final String field;
        final TitlePlan title;
        final RolePlan role;

        FieldPlan(String dataPath, String field, TitlePlan title, RolePlan role)
        {
            this.dataPath = dataPath;
            this.field = field;
            this.title = title;
            this.role = role;
        }
    }

    /** A validated schema parameter (a name + a raw value-type spec + optional title / use). */
    static final class ParameterPlan
    {
        final String name;
        final JsonElement valueTypeSpec;
        final TitlePlan title;
        final DataCompositionParameterUse use;

        ParameterPlan(String name, JsonElement valueTypeSpec, TitlePlan title,
            DataCompositionParameterUse use)
        {
            this.name = name;
            this.valueTypeSpec = valueTypeSpec;
            this.title = title;
            this.use = use;
        }
    }

    /** A validated title: exactly one of {@link #value} (neutral) / {@link #localized} (by code) is set. */
    static final class TitlePlan
    {
        final String value;
        final Map<String, String> localized;

        private TitlePlan(String value, Map<String, String> localized)
        {
            this.value = value;
            this.localized = localized;
        }

        static TitlePlan of(String value)
        {
            return new TitlePlan(value, null);
        }

        static TitlePlan ofLocalized(Map<String, String> localized)
        {
            return new TitlePlan(null, localized);
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

    /** The outcome of parsing a title (a resolved {@link TitlePlan}, possibly {@code null}, or an error). */
    private static final class TitleResult
    {
        final TitlePlan plan;
        final String error;

        private TitleResult(TitlePlan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static TitleResult ok(TitlePlan plan)
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
}
