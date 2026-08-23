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
import java.util.Locale;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSource;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Adapts node-addressed {@code dcs} mutations to the shared bulk {@link DcsWriter}. The adapter is
 * read-only until {@link DcsWriter#apply}: it resolves natural keys, enforces update/upsert semantics,
 * and fills only the legacy writer's required members from the current model. Thus the complete
 * request is validated before the first schema mutation.
 */
public final class DcsSchemaWriter
{
    private static final String ACTION_UPSERT = "upsert"; //$NON-NLS-1$
    private static final String ACTION_UPDATE = "update"; //$NON-NLS-1$
    private static final String ACTION_REPLACE = "replace"; //$NON-NLS-1$
    private static final String ACTION_REMOVE = "remove"; //$NON-NLS-1$

    private static final String TYPE_SCHEMA = "schema"; //$NON-NLS-1$
    private static final String TYPE_DATA_SOURCE = "dataSource"; //$NON-NLS-1$
    private static final String TYPE_DATA_SET = "dataSet"; //$NON-NLS-1$
    private static final String TYPE_FIELD = "field"; //$NON-NLS-1$
    private static final String TYPE_PARAMETER = "parameter"; //$NON-NLS-1$
    private static final String TYPE_CALCULATED_FIELD = "calculatedField"; //$NON-NLS-1$
    private static final String TYPE_TOTAL_FIELD = "totalField"; //$NON-NLS-1$

    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_DATA_PATH = "dataPath"; //$NON-NLS-1$
    private static final String KEY_QUERY = "query"; //$NON-NLS-1$
    private static final String KEY_DATA_SOURCE = "dataSource"; //$NON-NLS-1$
    private static final String KEY_AUTO_FILL = "autoFillFields"; //$NON-NLS-1$
    private static final String KEY_FIELDS = "fields"; //$NON-NLS-1$
    private static final String KEY_FIELD = "field"; //$NON-NLS-1$
    private static final String KEY_EXPRESSION = "expression"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_OBJECT_NAME = "objectName"; //$NON-NLS-1$
    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$

    private DcsSchemaWriter()
    {
        // Utility class
    }

    /** Pure request preparation, including recursive presentation validation. */
    public static PrepareResult prepare(String action, String type, DcsAddress address, JsonObject body,
        DcsPresentationParser.LanguageContext languages)
    {
        if (!ACTION_UPSERT.equals(action) && !ACTION_UPDATE.equals(action)
            && !ACTION_REPLACE.equals(action) && !ACTION_REMOVE.equals(action))
        {
            return PrepareResult.failure("Schema authoring supports action='upsert' or 'update'; got '" //$NON-NLS-1$ //$NON-NLS-2$
                + action + "'. Use upsert, update, replace, or remove."); //$NON-NLS-1$
        }
        if (address == null || body == null && !ACTION_REMOVE.equals(action))
        {
            return PrepareResult.failure("A parsed DCS address and one body object are required. " //$NON-NLS-1$
                + "Pass the target fqn and a body matching type='" + type + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!schemaType(type))
        {
            return PrepareResult.failure("Type '" + type + "' is not authorable in the schema layer. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use one of: schema, dataSource, dataSet, field, parameter, calculatedField, " //$NON-NLS-1$
                + "totalField. Use the shared settings writer or dynamic-list writer for their " //$NON-NLS-1$
                + "respective target roots."); //$NON-NLS-1$
        }
        if (ACTION_UPDATE.equals(action) && !isExactNode(type, address.segments()))
        {
            return PrepareResult.failure("action='update' requires one existing " + type + " node; '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + address + "' is a root or collection target. Copy an exact '#/...' node address " //$NON-NLS-1$
                + "from dcs action='get', or use action='upsert' with its natural key."); //$NON-NLS-1$
        }
        return PrepareResult.success(new Request(action, type, address,
            body == null ? null : body.deepCopy(), languages));
    }

    /**
     * Performs all model-aware validation and then delegates the only mutation to {@link DcsWriter}.
     * The caller must invoke this inside its single BM write transaction.
     */
    public static Result apply(DataCompositionSchema schema, Request request, DcsWriter.TypeResolver resolver)
    {
        if (schema == null)
        {
            return Result.failure("The DCS schema content is unavailable. Re-open the template and retry."); //$NON-NLS-1$
        }
        if (ACTION_REPLACE.equals(request.action))
        {
            String refusal = DcsMutationGuard.replaceError(schema, request.address);
            if (refusal != null) return Result.failure(refusal);
        }
        String referenceError = identityReferenceError(schema, request);
        if (referenceError != null) return Result.failure(referenceError);

        DataCompositionSchema working = EcoreUtil.copy(schema);
        String renameError = renameForUpdate(working, request);
        if (renameError != null) return Result.failure(renameError);
        if (ACTION_REMOVE.equals(request.action))
        {
            String error = remove(working, request);
            if (error != null) return Result.failure(error);
            error = assembledReferenceError(working, request.address.rootFqn());
            if (error != null) return Result.failure(error);
            commitSchemaLayer(schema, working);
            return Result.success(null);
        }
        if (ACTION_REPLACE.equals(request.action))
        {
            String error = clearReplaceTarget(working, request);
            if (error != null) return Result.failure(error);
            if (TYPE_SCHEMA.equals(request.type) && request.body.entrySet().isEmpty())
            {
                error = assembledReferenceError(working, request.address.rootFqn());
                if (error != null) return Result.failure(error);
                commitSchemaLayer(schema, working);
                return Result.success(null);
            }
        }
        PayloadResult payload = payload(working, request);
        if (payload.error != null)
        {
            return Result.failure(payload.error);
        }
        DcsWriter.Result applied = DcsWriter.apply(working, payload.payload, resolver, request.languages);
        if (applied.hasError()) return Result.failureJson(applied.error);
        String assembledError = assembledReferenceError(working, request.address.rootFqn());
        if (assembledError != null) return Result.failure(assembledError);
        commitSchemaLayer(schema, working);
        return Result.success(applied);
    }

    private static String identityReferenceError(DataCompositionSchema schema, Request request)
    {
        if (!ACTION_REMOVE.equals(request.action) && !ACTION_UPDATE.equals(request.action)) return null;
        List<String> segments = request.address.segments();
        String identity = null;
        if (TYPE_FIELD.equals(request.type) && isFieldPath(segments, true))
            identity = segments.get(segments.size() - 1);
        else if ((TYPE_DATA_SOURCE.equals(request.type) || TYPE_DATA_SET.equals(request.type)
            || TYPE_PARAMETER.equals(request.type)
            || TYPE_CALCULATED_FIELD.equals(request.type) || TYPE_TOTAL_FIELD.equals(request.type))
            && segments.size() == 2) identity = segments.get(1);
        if (identity == null) return null;
        if (ACTION_UPDATE.equals(request.action))
        {
            String member = keyMember(request.type);
            String replacement = string(request.body, member);
            if (replacement == null || identity.equals(replacement)) return null;
        }
        return DcsMutationGuard.referenceError(schema, request.address, request.type, identity);
    }

    /**
     * Applies an identity-changing {@code action='update'} to the working copy BEFORE the body is
     * planned, so the writer's natural-key lookup finds the same node instead of creating a second
     * one. Only the exact identity address of a renameable type qualifies; every other update is a
     * no-op here. The reference guard has already refused a rename of anything still referred to,
     * so no cascade is needed.
     */
    private static String renameForUpdate(DataCompositionSchema schema, Request request)
    {
        if (!ACTION_UPDATE.equals(request.action)) return null;
        List<String> path = request.address.segments();
        String member = keyMember(request.type);
        boolean field = TYPE_FIELD.equals(request.type);
        if (field)
        {
            if (!isFieldPath(path, true)) return null;
        }
        else
        {
            String own = collection(request.type);
            if (own == null || path.size() != 2 || !own.equals(path.get(0))) return null;
        }
        String oldKey = field ? path.get(path.size() - 1) : path.get(1);
        String newKey = string(request.body, member);
        if (newKey == null || oldKey.equals(newKey)) return null;
        if (newKey.isEmpty())
        {
            return "Body for type='" + request.type + "' needs a non-empty '" + member //$NON-NLS-1$ //$NON-NLS-2$
                + "' natural key. Add it and retry."; //$NON-NLS-1$
        }
        FieldTarget fieldTarget = field ? resolveFieldTarget(schema, path) : null;
        if (fieldTarget != null && fieldTarget.error != null) return fieldTarget.error;
        String targetCollection = field ? "fields" : collection(request.type); //$NON-NLS-1$
        List<String> existing = field ? fieldKeys(fieldTarget.dataSet)
            : keys(schema, targetCollection, null);
        if (!existing.contains(oldKey))
        {
            return missing(request, oldKey, existing);
        }
        EObject target;
        if (field)
        {
            List<DataSetField> matches = dataSetFields(fieldTarget.dataSet, oldKey);
            if (matches.size() != 1)
            {
                return ambiguousIdentity(request, "rename", oldKey, matches.size()); //$NON-NLS-1$
            }
            DataSetField addressed = matches.get(0);
            if (!(addressed instanceof DataCompositionSchemaDataSetField))
            {
                return unsupportedField(request, oldKey, addressed);
            }
            target = addressed;
        }
        else
        {
            List<EObject> matches = identityMatches(schema, request.type, oldKey);
            if (matches.size() != 1)
            {
                return ambiguousIdentity(request, "rename", oldKey, matches.size()); //$NON-NLS-1$
            }
            target = matches.get(0);
        }
        if (TYPE_DATA_SET.equals(request.type) && !existing.contains(newKey))
        {
            String collisionAddress = dataSetAddress(schema.getDataSets(), request.address.rootFqn(),
                Arrays.asList("dataSets"), newKey); //$NON-NLS-1$
            if (collisionAddress != null)
            {
                return "Cannot rename dataSet '" + oldKey + "' to '" + newKey //$NON-NLS-1$ //$NON-NLS-2$
                    + "' at '" + request.address + "' because data set '" + newKey //$NON-NLS-1$ //$NON-NLS-2$
                    + "' already exists at '" + collisionAddress //$NON-NLS-1$
                    + "'. Choose an unused 'name' and retry."; //$NON-NLS-1$
            }
        }
        if (existing.contains(newKey))
        {
            return "Cannot rename " + request.type + " '" + oldKey + "' to '" + newKey //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "' at '" + request.address + "' because sibling '" + newKey //$NON-NLS-1$ //$NON-NLS-2$
                + "' already exists. Choose an unused '" + member + "' and retry."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (TYPE_DATA_SOURCE.equals(request.type))
        {
            ((DataCompositionSchemaDataSource)target).setName(newKey);
        }
        else if (TYPE_DATA_SET.equals(request.type))
        {
            ((DataSet)target).setName(newKey);
        }
        else if (TYPE_PARAMETER.equals(request.type))
        {
            ((DataCompositionSchemaParameter)target).setName(newKey);
        }
        else if (TYPE_CALCULATED_FIELD.equals(request.type))
        {
            ((DataCompositionSchemaCalculatedField)target).setDataPath(newKey);
        }
        else if (TYPE_TOTAL_FIELD.equals(request.type))
        {
            ((DataCompositionSchemaTotalField)target).setDataPath(newKey);
        }
        else if (TYPE_FIELD.equals(request.type))
        {
            ((DataCompositionSchemaDataSetField)target).setDataPath(newKey);
        }
        request.renamedTo = newKey;
        return null;
    }

    private static String keyMember(String type)
    {
        return TYPE_DATA_SOURCE.equals(type) || TYPE_DATA_SET.equals(type)
            || TYPE_PARAMETER.equals(type) ? KEY_NAME : KEY_DATA_PATH;
    }

    private static String ambiguousIdentity(Request request, String operation, String key,
        int count)
    {
        return "Cannot " + operation + " " + request.type + " '" + key + "' at '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + request.address
            + "' because natural key '" + key + "' matches " + count //$NON-NLS-1$ //$NON-NLS-2$
            + " existing nodes. The address is ambiguous; disambiguate the duplicates in the DCS " //$NON-NLS-1$
            + "designer first, re-run get, and retry."; //$NON-NLS-1$
    }

    private static String unsupportedField(Request request, String key, DataSetField field)
    {
        return "Field '" + key + "' at '" + request.address //$NON-NLS-1$ //$NON-NLS-2$
            + "' has unsupported subtype '" + field.eClass().getName() //$NON-NLS-1$
            + "'. Field folders are not authorable; edit or remove the folder in the DCS " //$NON-NLS-1$
            + "designer, re-run get, and retry."; //$NON-NLS-1$
    }

    private static String dataSetAddress(List<DataSet> dataSets, String rootFqn,
        List<String> prefix, String key)
    {
        for (int i = 0; i < dataSets.size(); i++)
        {
            DataSet dataSet = dataSets.get(i);
            List<String> address = new ArrayList<>(prefix);
            String name = dataSet.getName();
            address.add(name == null || name.isEmpty() ? Integer.toString(i) : name);
            if (key.equals(dataSet.getName()))
            {
                return DcsAddress.render(rootFqn, address);
            }
            if (dataSet instanceof DataCompositionSchemaDataSetUnion)
            {
                address.add(KEY_ITEMS);
                String nested = dataSetAddress(
                    ((DataCompositionSchemaDataSetUnion)dataSet).getItems(), rootFqn, address, key);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static List<EObject> identityMatches(DataCompositionSchema schema, String type,
        String key)
    {
        List<EObject> result = new ArrayList<>();
        if (TYPE_DATA_SOURCE.equals(type))
        {
            for (DataCompositionSchemaDataSource item : schema.getDataSources())
            {
                if (key.equals(item.getName())) result.add(item);
            }
        }
        else if (TYPE_DATA_SET.equals(type))
        {
            for (DataSet item : schema.getDataSets())
            {
                if (key.equals(item.getName())) result.add(item);
            }
        }
        else if (TYPE_PARAMETER.equals(type))
        {
            for (DataCompositionSchemaParameter item : schema.getParameters())
            {
                if (key.equals(item.getName())) result.add(item);
            }
        }
        else if (TYPE_CALCULATED_FIELD.equals(type))
        {
            for (DataCompositionSchemaCalculatedField item : schema.getCalculatedFields())
            {
                if (key.equals(item.getDataPath())) result.add(item);
            }
        }
        else if (TYPE_TOTAL_FIELD.equals(type))
        {
            for (DataCompositionSchemaTotalField item : schema.getTotalFields())
            {
                if (key.equals(item.getDataPath())) result.add(item);
            }
        }
        return result;
    }

    /** Returns an actionable error when a complete assembled or imported schema has dangling references. */
    public static String validateAssembledReferences(DataCompositionSchema schema, String rootFqn)
    {
        return assembledReferenceError(schema, rootFqn);
    }

    private static String assembledReferenceError(DataCompositionSchema schema, String rootFqn)
    {
        Set<String> dataSetNames = new LinkedHashSet<>();
        List<DataSet> dataSets = new ArrayList<>();
        collectDataSets(schema.getDataSets(), dataSetNames, dataSets);
        Set<String> parameterNames = new LinkedHashSet<>();
        for (DataCompositionSchemaParameter parameter : schema.getParameters())
        {
            if (parameter.getName() != null && !parameter.getName().isEmpty())
            {
                parameterNames.add(parameter.getName());
            }
        }
        for (int i = 0; i < schema.getDataSetLinks().size(); i++)
        {
            DataCompositionSchemaDataSetLink link = schema.getDataSetLinks().get(i);
            String address = DcsAddress.render(rootFqn,
                Arrays.asList("dataSetLinks", Integer.toString(i))); //$NON-NLS-1$
            String error = dataSetLinkReferenceError(link.getSourceDataSet(), "sourceDataSet", //$NON-NLS-1$
                address, dataSetNames);
            if (error != null) return error;
            error = dataSetLinkReferenceError(link.getDestinationDataSet(), "destinationDataSet", //$NON-NLS-1$
                address, dataSetNames);
            if (error != null) return error;
            error = dataSetLinkParameterReferenceError(link.getParameter(), address, parameterNames);
            if (error != null) return error;
        }

        Set<String> dataSourceNames = new LinkedHashSet<>();
        for (DataCompositionSchemaDataSource dataSource : schema.getDataSources())
        {
            if (dataSource.getName() != null && !dataSource.getName().isEmpty())
            {
                dataSourceNames.add(dataSource.getName());
            }
        }
        for (DataSet dataSet : dataSets)
        {
            String dataSource = dataSet instanceof DataCompositionSchemaDataSetQuery
                ? ((DataCompositionSchemaDataSetQuery)dataSet).getDataSource()
                : dataSet instanceof DataCompositionSchemaDataSetObject
                    ? ((DataCompositionSchemaDataSetObject)dataSet).getDataSource() : null;
            if (dataSource == null || dataSource.isEmpty() || dataSourceNames.contains(dataSource))
            {
                continue;
            }
            List<String> addresses = DcsReadProjection.referenceAddresses(schema, rootFqn,
                TYPE_DATA_SOURCE, dataSource);
            String address = addresses.isEmpty() ? rootFqn : String.join(", ", addresses); //$NON-NLS-1$
            return "Data set at '" + address //$NON-NLS-1$
                + "' has dangling dataSource '" + dataSource //$NON-NLS-1$
                + "' after assembling the schema. Add or keep a data source named '" + dataSource //$NON-NLS-1$
                + "' in the assembled schema (include it in the replacement body when replacing), " //$NON-NLS-1$
                + "or update/remove the referring nodes first and retry."; //$NON-NLS-1$
        }
        return null;
    }

    private static void collectDataSets(List<DataSet> candidates, Set<String> names,
        List<DataSet> dataSets)
    {
        for (DataSet dataSet : candidates)
        {
            dataSets.add(dataSet);
            if (dataSet.getName() != null && !dataSet.getName().isEmpty())
            {
                names.add(dataSet.getName());
            }
            if (dataSet instanceof DataCompositionSchemaDataSetUnion)
            {
                collectDataSets(((DataCompositionSchemaDataSetUnion)dataSet).getItems(), names,
                    dataSets);
            }
        }
    }

    private static String dataSetLinkReferenceError(String identity, String member, String address,
        Set<String> dataSetNames)
    {
        if (identity == null || identity.isEmpty() || dataSetNames.contains(identity))
        {
            return null;
        }
        return "Data-set link at '" + address + "' has dangling " + member + " '" //$NON-NLS-1$ //$NON-NLS-2$
            + identity
            + "' after assembling the schema. Add or keep a data set named '" + identity //$NON-NLS-1$
            + "' in the assembled schema (include it in the replacement body when replacing), " //$NON-NLS-1$
            + "or update/remove the referring nodes first and retry."; //$NON-NLS-1$
    }

    private static String dataSetLinkParameterReferenceError(String identity, String address,
        Set<String> parameterNames)
    {
        if (identity == null || identity.isEmpty() || parameterNames.contains(identity))
        {
            return null;
        }
        return "Data-set link at '" + address + "' has dangling parameter '" + identity //$NON-NLS-1$ //$NON-NLS-2$
            + "' after assembling the schema. Add or keep a parameter named '" + identity //$NON-NLS-1$
            + "' in the assembled schema (include it in the replacement body when replacing), " //$NON-NLS-1$
            + "or update/remove the referring nodes first and retry."; //$NON-NLS-1$
    }

    private static String clearReplaceTarget(DataCompositionSchema schema, Request request)
    {
        List<String> path = request.address.segments();
        if (TYPE_SCHEMA.equals(request.type))
        {
            if (!path.isEmpty())
                return "type='schema' replace targets the bare root. Remove the '#/...' fragment."; //$NON-NLS-1$
            schema.getDataSources().clear();
            schema.getDataSets().clear();
            schema.getDataSetLinks().clear();
            schema.getParameters().clear();
            schema.getCalculatedFields().clear();
            schema.getTotalFields().clear();
            return null;
        }
        if (TYPE_FIELD.equals(request.type) && isFieldPath(path, false))
        {
            FieldTarget target = resolveFieldTarget(schema, path);
            if (target.error != null) return target.error;
            target.dataSet.getFields().clear();
            return null;
        }
        // An exact field address has no collection of its own: it falls through to remove(),
        // which deletes the addressed field so the authoritative body recreates it.
        String collection = collection(request.type);
        if (collection == null && !TYPE_FIELD.equals(request.type))
            return "Type '" + request.type + "' has no replaceable schema collection."; //$NON-NLS-1$ //$NON-NLS-2$
        if (collection != null && path.size() == 1 && collection.equals(path.get(0)))
        {
            clearCollection(schema, collection);
            return null;
        }
        if (TYPE_DATA_SET.equals(request.type) && path.size() == 2
            && "dataSets".equals(path.get(0))) //$NON-NLS-1$
        {
            DataSet existing = findDataSet(schema, path.get(1));
            if (existing == null)
            {
                return "Data set '" + path.get(1) + "' was not found. Re-run get."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            String kind = string(request.body, KEY_TYPE);
            if (kind == null)
            {
                kind = dataSetKind(existing);
            }
            kind = kind.toLowerCase(Locale.ROOT);
            DataSet replacement;
            if ("query".equals(kind)) //$NON-NLS-1$
            {
                replacement = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
                if (!request.body.has(KEY_QUERY)) request.body.addProperty(KEY_QUERY, ""); //$NON-NLS-1$
            }
            else if ("object".equals(kind)) //$NON-NLS-1$
            {
                replacement = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetObject();
                if (!request.body.has(KEY_OBJECT_NAME)) request.body.addProperty(KEY_OBJECT_NAME, ""); //$NON-NLS-1$
            }
            else if ("union".equals(kind)) //$NON-NLS-1$
            {
                replacement = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetUnion();
            }
            else
            {
                return "Data set type '" + kind //$NON-NLS-1$
                    + "' is unsupported. Use query, object, or union."; //$NON-NLS-1$
            }
            request.body.addProperty(KEY_TYPE, kind);
            replacement.setName(path.get(1));
            schema.getDataSets().set(schema.getDataSets().indexOf(existing), replacement);
            return null;
        }
        if ((TYPE_CALCULATED_FIELD.equals(request.type) || TYPE_TOTAL_FIELD.equals(request.type))
            && collection != null && path.size() == 2 && collection.equals(path.get(0))
            && !request.body.has(KEY_EXPRESSION))
        {
            return "An authoritative action='replace' of " + request.type + " at '" //$NON-NLS-1$ //$NON-NLS-2$
                + request.address + "' must carry 'expression' because it has no valid empty value. " //$NON-NLS-1$
                + "Re-run dcs action='get', copy the current expression, and resend it."; //$NON-NLS-1$
        }
        String removed = remove(schema, request);
        if (removed != null && ACTION_REPLACE.equals(request.action))
            return removed.replace("action='remove'", "action='replace'"); //$NON-NLS-1$ //$NON-NLS-2$
        return removed;
    }

    private static String remove(DataCompositionSchema schema, Request request)
    {
        List<String> path = request.address.segments();
        if (path.isEmpty())
            return "action='remove' refuses the bare DCS root. Address exactly one '#/...' node."; //$NON-NLS-1$
        if (TYPE_SCHEMA.equals(request.type) && path.size() == 2
            && "dataSetLinks".equals(path.get(0))) //$NON-NLS-1$
        {
            if (!DcsAddress.isZeroBasedIndex(path.get(1))) return "Data-set link selector '" //$NON-NLS-1$
                + path.get(1) + "' must be a zero-based index copied from get."; //$NON-NLS-1$
            int index = Integer.parseInt(path.get(1));
            if (index >= schema.getDataSetLinks().size()) return "Data-set link index '" //$NON-NLS-1$
                + path.get(1) + "' is out of range. Re-run get."; //$NON-NLS-1$
            schema.getDataSetLinks().remove(index);
            return null;
        }
        if (TYPE_FIELD.equals(request.type) && isFieldPath(path, true))
        {
            FieldTarget target = resolveFieldTarget(schema, path);
            if (target.error != null) return target.error;
            String fieldKey = path.get(path.size() - 1);
            List<DataSetField> matches = dataSetFields(target.dataSet, fieldKey);
            if (matches.isEmpty()) return "Field '" + fieldKey + "' was not found in data set '" //$NON-NLS-1$ //$NON-NLS-2$
                + target.dataSet.getName() + "'. Re-run get."; //$NON-NLS-1$
            if (matches.size() != 1)
            {
                return ambiguousIdentity(request, removeOperation(request), fieldKey,
                    matches.size());
            }
            DataSetField field = matches.get(0);
            if (!(field instanceof DataCompositionSchemaDataSetField))
            {
                return unsupportedField(request, fieldKey, field);
            }
            target.dataSet.getFields().remove(field);
            return null;
        }
        String collection = collection(request.type);
        if (collection == null || path.size() != 2 || !collection.equals(path.get(0)))
            return "action='remove' for type='" + request.type //$NON-NLS-1$
                + "' needs one exact canonical node address; got '" + request.address + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        String key = path.get(1);
        List<EObject> matches = identityMatches(schema, request.type, key);
        if (matches.isEmpty())
        {
            return "No " + request.type + " named '" + key //$NON-NLS-1$ //$NON-NLS-2$
                + "' exists at '" + request.address //$NON-NLS-1$
                + "'. Re-run get and copy an existing address."; //$NON-NLS-1$
        }
        if (matches.size() != 1)
        {
            return ambiguousIdentity(request, removeOperation(request), key, matches.size());
        }
        EObject target = matches.get(0);
        if (target instanceof DataCompositionSchemaDataSource)
            schema.getDataSources().remove(target);
        else if (target instanceof DataSet) schema.getDataSets().remove(target);
        else if (target instanceof DataCompositionSchemaParameter)
            schema.getParameters().remove(target);
        else if (target instanceof DataCompositionSchemaCalculatedField)
            schema.getCalculatedFields().remove(target);
        else if (target instanceof DataCompositionSchemaTotalField)
            schema.getTotalFields().remove(target);
        return null;
    }

    private static String removeOperation(Request request)
    {
        return ACTION_REPLACE.equals(request.action) ? "replace" : "remove"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void clearCollection(DataCompositionSchema schema, String collection)
    {
        switch (collection)
        {
            case "dataSources": schema.getDataSources().clear(); break; //$NON-NLS-1$
            case "dataSets": schema.getDataSets().clear(); break; //$NON-NLS-1$
            case "parameters": schema.getParameters().clear(); break; //$NON-NLS-1$
            case "calculatedFields": schema.getCalculatedFields().clear(); break; //$NON-NLS-1$
            case "totalFields": schema.getTotalFields().clear(); break; //$NON-NLS-1$
            default: break;
        }
    }

    private static void commitSchemaLayer(DataCompositionSchema target, DataCompositionSchema source)
    {
        target.getDataSources().clear();
        target.getDataSources().addAll(EcoreUtil.copyAll(source.getDataSources()));
        target.getDataSets().clear();
        target.getDataSets().addAll(EcoreUtil.copyAll(source.getDataSets()));
        target.getDataSetLinks().clear();
        target.getDataSetLinks().addAll(EcoreUtil.copyAll(source.getDataSetLinks()));
        target.getParameters().clear();
        target.getParameters().addAll(EcoreUtil.copyAll(source.getParameters()));
        target.getCalculatedFields().clear();
        target.getCalculatedFields().addAll(EcoreUtil.copyAll(source.getCalculatedFields()));
        target.getTotalFields().clear();
        target.getTotalFields().addAll(EcoreUtil.copyAll(source.getTotalFields()));
    }

    private static PayloadResult payload(DataCompositionSchema schema, Request request)
    {
        switch (request.type)
        {
            case TYPE_SCHEMA:
                if (!request.address.segments().isEmpty())
                {
                    return PayloadResult.failure("type='schema' targets the bare root, not '" //$NON-NLS-1$
                        + request.address + "'. Remove the '#/...' fragment."); //$NON-NLS-1$
                }
                return normalizeSchemaBody(schema, request);
            case TYPE_DATA_SOURCE:
                return namedPayload(schema, request, "dataSources", KEY_NAME); //$NON-NLS-1$
            case TYPE_DATA_SET:
                return dataSetPayload(schema, request);
            case TYPE_FIELD:
                return fieldPayload(schema, request);
            case TYPE_PARAMETER:
                return namedPayload(schema, request, "parameters", KEY_NAME); //$NON-NLS-1$
            case TYPE_CALCULATED_FIELD:
                return expressionPayload(schema, request, "calculatedFields"); //$NON-NLS-1$
            case TYPE_TOTAL_FIELD:
                return expressionPayload(schema, request, "totalFields"); //$NON-NLS-1$
            default:
                return PayloadResult.failure("Type '" + request.type + "' is not authorable here."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static PayloadResult normalizeSchemaBody(DataCompositionSchema schema, Request request)
    {
        JsonObject body = request.body.deepCopy();
        if (body.has("dataSources") && body.get("dataSources").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (JsonObject entry : objects(body.getAsJsonArray("dataSources"))) //$NON-NLS-1$
            {
                mergeDataSourceType(schema, entry, string(entry, KEY_NAME));
            }
        }
        if (body.has("dataSets") && body.get("dataSets").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (JsonObject entry : objects(body.getAsJsonArray("dataSets"))) //$NON-NLS-1$
            {
                String name = string(entry, KEY_NAME);
                DataSet existing = findDataSet(schema, name);
                String error = normalizeDataSet(entry, existing, name);
                if (error != null)
                {
                    return PayloadResult.failure(error);
                }
            }
        }
        PayloadResult calculated = normalizeExpressions(schema, body, "calculatedFields"); //$NON-NLS-1$
        if (calculated.error != null)
        {
            return calculated;
        }
        PayloadResult totals = normalizeExpressions(schema, body, "totalFields"); //$NON-NLS-1$
        return totals.error == null ? PayloadResult.success(body) : totals;
    }

    private static PayloadResult namedPayload(DataCompositionSchema schema, Request request,
        String collection, String keyMember)
    {
        KeyResult keyed = naturalKey(request, collection, keyMember);
        if (keyed.error != null)
        {
            return PayloadResult.failure(keyed.error);
        }
        List<String> existing = keys(schema, collection, null);
        if (ACTION_UPDATE.equals(request.action) && !existing.contains(keyed.key))
        {
            return PayloadResult.failure(missing(request, keyed.key, existing));
        }
        JsonObject entry = request.body.deepCopy();
        entry.addProperty(keyMember, keyed.key);
        if ("dataSources".equals(collection)) //$NON-NLS-1$
        {
            mergeDataSourceType(schema, entry, keyed.key);
        }
        return PayloadResult.success(wrap(collection, entry));
    }

    private static PayloadResult dataSetPayload(DataCompositionSchema schema, Request request)
    {
        KeyResult keyed = naturalKey(request, "dataSets", KEY_NAME); //$NON-NLS-1$
        if (keyed.error != null)
        {
            return PayloadResult.failure(keyed.error);
        }
        DataSet existing = findDataSet(schema, keyed.key);
        if (ACTION_UPDATE.equals(request.action) && existing == null)
        {
            return PayloadResult.failure(missing(request, keyed.key, keys(schema, "dataSets", null))); //$NON-NLS-1$
        }
        JsonObject entry = request.body.deepCopy();
        entry.addProperty(KEY_NAME, keyed.key);
        String error = normalizeDataSet(entry, existing, keyed.key);
        return error == null ? PayloadResult.success(wrap("dataSets", entry)) //$NON-NLS-1$
            : PayloadResult.failure(error);
    }

    private static String normalizeDataSet(JsonObject entry, DataSet existing, String name)
    {
        if (name == null)
        {
            return null; // DcsWriter reports the malformed natural key with its exact body location.
        }
        if (existing instanceof DataCompositionSchemaDataSetObject)
        {
            entry.addProperty(KEY_TYPE, "object"); //$NON-NLS-1$
            DataCompositionSchemaDataSetObject object = (DataCompositionSchemaDataSetObject)existing;
            if (!entry.has(KEY_OBJECT_NAME)) entry.addProperty(KEY_OBJECT_NAME, object.getObjectName());
            if (!entry.has(KEY_DATA_SOURCE) && object.getDataSource() != null)
                entry.addProperty(KEY_DATA_SOURCE, object.getDataSource());
            mergeDataSetFields(entry, existing);
            return null;
        }
        if (existing instanceof DataCompositionSchemaDataSetUnion)
        {
            return normalizeUnionDataSet(entry, (DataCompositionSchemaDataSetUnion)existing, name);
        }
        if (existing != null && !(existing instanceof DataCompositionSchemaDataSetQuery))
        {
            return null;
        }
        String declaredType = string(entry, KEY_TYPE);
        if (existing == null && "object".equalsIgnoreCase(declaredType)) //$NON-NLS-1$
        {
            entry.addProperty(KEY_TYPE, "object"); //$NON-NLS-1$
            return null;
        }
        if (existing == null && "union".equalsIgnoreCase(declaredType)) //$NON-NLS-1$
        {
            return normalizeUnionDataSet(entry, null, name);
        }
        DataCompositionSchemaDataSetQuery query = (DataCompositionSchemaDataSetQuery)existing;
        entry.addProperty(KEY_TYPE, "query"); //$NON-NLS-1$
        if (!entry.has(KEY_QUERY))
        {
            if (query == null || query.getQuery() == null || query.getQuery().isEmpty())
            {
                return "Creating query data set '" + name + "' requires a non-empty 'query'. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Pass the exact 1C query text; existing data sets may omit it on partial update."; //$NON-NLS-1$
            }
            entry.addProperty(KEY_QUERY, query.getQuery());
        }
        if (query != null)
        {
            if (!entry.has(KEY_DATA_SOURCE) && query.getDataSource() != null)
            {
                entry.addProperty(KEY_DATA_SOURCE, query.getDataSource());
            }
            if (!entry.has(KEY_AUTO_FILL))
            {
                entry.addProperty(KEY_AUTO_FILL, query.isAutoFillAvailableFields());
            }
            mergeDataSetFields(entry, query);
        }
        return null;
    }

    private static String normalizeUnionDataSet(JsonObject entry,
        DataCompositionSchemaDataSetUnion existing, String name)
    {
        if (entry.has(KEY_QUERY))
        {
            return "Union data set '" + name + "' cannot declare 'query'. Remove 'query'; " //$NON-NLS-1$ //$NON-NLS-2$
                + "put each query in a nested data set under 'items'."; //$NON-NLS-1$
        }
        entry.addProperty(KEY_TYPE, "union"); //$NON-NLS-1$
        if (existing != null)
        {
            mergeDataSetFields(entry, existing);
        }
        if (!entry.has(KEY_ITEMS) || !entry.get(KEY_ITEMS).isJsonArray())
        {
            return null;
        }
        for (JsonObject child : objects(entry.getAsJsonArray(KEY_ITEMS)))
        {
            String childName = string(child, KEY_NAME);
            DataSet current = existing == null ? null : findDataSet(existing.getItems(), childName);
            String error = normalizeDataSet(child, current, childName);
            if (error != null) return error;
        }
        return null;
    }

    private static void mergeDataSetFields(JsonObject entry, DataSet dataSet)
    {
        if (!entry.has(KEY_FIELDS) || !entry.get(KEY_FIELDS).isJsonArray()) return;
        for (JsonObject field : objects(entry.getAsJsonArray(KEY_FIELDS)))
        {
            String path = string(field, KEY_DATA_PATH);
            DataCompositionSchemaDataSetField current = findField(dataSet, path);
            if (current != null) mergeFieldDefaults(field, current);
        }
    }

    private static PayloadResult fieldPayload(DataCompositionSchema schema, Request request)
    {
        List<String> segments = request.address.segments();
        if (!isFieldPath(segments, false) && !isFieldPath(segments, true))
        {
            return PayloadResult.failure("type='field' needs " //$NON-NLS-1$
                + "'#/dataSets/<dataSet>(/items/<dataSet>)*/fields' or an exact address with " //$NON-NLS-1$
                + "a trailing '/<dataPath>'; got '" //$NON-NLS-1$
                + request.address + "'. Copy the parent or node address from dcs action='get'."); //$NON-NLS-1$
        }
        FieldTarget target = resolveFieldTarget(schema, segments);
        if (target.error != null)
        {
            return PayloadResult.failure(target.error);
        }
        DataSet set = target.dataSet;
        String pointerKey = target.exact ? segments.get(segments.size() - 1) : null;
        KeyResult keyed = key(request, KEY_DATA_PATH, pointerKey);
        if (keyed.error != null)
        {
            return PayloadResult.failure(keyed.error);
        }
        DataCompositionSchemaDataSetField existing = findField(set, keyed.key);
        if (ACTION_UPDATE.equals(request.action) && existing == null)
        {
            return PayloadResult.failure(missing(request, keyed.key,
                fieldKeys(set)));
        }
        JsonObject field = request.body.deepCopy();
        field.addProperty(KEY_DATA_PATH, keyed.key);
        if (existing != null)
        {
            mergeFieldDefaults(field, existing);
        }
        JsonArray fields = new JsonArray();
        fields.add(field);
        JsonObject dataSet = nestedDataSetPayload(target.dataSets, fields);
        DataSet root = target.dataSets.get(0);
        String normalizeError = normalizeDataSet(dataSet, root, root.getName());
        if (normalizeError != null) return PayloadResult.failure(normalizeError);
        return PayloadResult.success(wrap("dataSets", dataSet)); //$NON-NLS-1$
    }

    private static PayloadResult expressionPayload(DataCompositionSchema schema, Request request,
        String collection)
    {
        KeyResult keyed = naturalKey(request, collection, KEY_DATA_PATH);
        if (keyed.error != null)
        {
            return PayloadResult.failure(keyed.error);
        }
        boolean exists = keys(schema, collection, null).contains(keyed.key);
        String current = expression(schema, collection, keyed.key);
        if (ACTION_UPDATE.equals(request.action) && !exists)
        {
            return PayloadResult.failure(missing(request, keyed.key, keys(schema, collection, null)));
        }
        JsonObject entry = request.body.deepCopy();
        entry.addProperty(KEY_DATA_PATH, keyed.key);
        if (!entry.has(KEY_EXPRESSION))
        {
            if (current == null || current.isEmpty())
            {
                return PayloadResult.failure("Creating " + request.type + " '" + keyed.key //$NON-NLS-1$ //$NON-NLS-2$
                    + "' requires a non-empty 'expression'. Pass the DCS expression, or target an " //$NON-NLS-1$
                    + "existing node for a partial update."); //$NON-NLS-1$
            }
            entry.addProperty(KEY_EXPRESSION, current);
        }
        return PayloadResult.success(wrap(collection, entry));
    }

    private static PayloadResult normalizeExpressions(DataCompositionSchema schema, JsonObject body,
        String collection)
    {
        if (!body.has(collection) || !body.get(collection).isJsonArray())
        {
            return PayloadResult.success(body);
        }
        for (JsonObject entry : objects(body.getAsJsonArray(collection)))
        {
            if (entry.has(KEY_EXPRESSION))
            {
                continue;
            }
            String key = string(entry, KEY_DATA_PATH);
            String current = expression(schema, collection, key);
            if (current == null || current.isEmpty())
            {
                return PayloadResult.failure("Creating " + collection + " entry '" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + "' requires a non-empty 'expression'. Add it to that body entry."); //$NON-NLS-1$
            }
            entry.addProperty(KEY_EXPRESSION, current);
        }
        return PayloadResult.success(body);
    }

    private static KeyResult naturalKey(Request request, String collection, String member)
    {
        List<String> segments = request.address.segments();
        if (segments.isEmpty())
        {
            return key(request, member, null);
        }
        if (segments.size() == 1 && collection.equals(segments.get(0)))
        {
            return key(request, member, null);
        }
        if (segments.size() == 2 && collection.equals(segments.get(0)))
        {
            return key(request, member, segments.get(1));
        }
        return KeyResult.failure("type='" + request.type + "' needs the bare root, '#/" //$NON-NLS-1$ //$NON-NLS-2$
            + collection + "', or '#/" + collection + "/<naturalKey>'; got '" //$NON-NLS-1$ //$NON-NLS-2$
            + request.address + "'. Copy a matching address from dcs action='get'."); //$NON-NLS-1$
    }

    private static KeyResult key(Request request, String member, String pointerKey)
    {
        String bodyKey = string(request.body, member);
        if (pointerKey != null && bodyKey != null && !pointerKey.equals(bodyKey))
        {
            if (ACTION_UPDATE.equals(request.action) && bodyKey.equals(request.renamedTo))
            {
                return KeyResult.success(bodyKey);
            }
            return KeyResult.failure("Body natural key '" + bodyKey + "' does not match address key '" //$NON-NLS-1$ //$NON-NLS-2$
                + pointerKey + "' at '" + request.address + "'. Make '" + member //$NON-NLS-1$ //$NON-NLS-2$
                + "' match the pointer, or omit it from the partial body."); //$NON-NLS-1$
        }
        String effective = pointerKey != null ? pointerKey : bodyKey;
        if (effective == null || effective.isEmpty())
        {
            return KeyResult.failure("Body for type='" + request.type + "' needs a non-empty '" //$NON-NLS-1$ //$NON-NLS-2$
                + member + "' natural key when the fqn does not name one. Add it and retry."); //$NON-NLS-1$
        }
        return KeyResult.success(effective);
    }

    private static String dataSetKind(DataSet dataSet)
    {
        if (dataSet instanceof DataCompositionSchemaDataSetObject) return "object"; //$NON-NLS-1$
        if (dataSet instanceof DataCompositionSchemaDataSetUnion) return "union"; //$NON-NLS-1$
        return "query"; //$NON-NLS-1$
    }

    private static boolean schemaType(String type)
    {
        return TYPE_SCHEMA.equals(type) || TYPE_DATA_SOURCE.equals(type) || TYPE_DATA_SET.equals(type)
            || TYPE_FIELD.equals(type) || TYPE_PARAMETER.equals(type)
            || TYPE_CALCULATED_FIELD.equals(type) || TYPE_TOTAL_FIELD.equals(type);
    }

    private static boolean isExactNode(String type, List<String> segments)
    {
        if (TYPE_FIELD.equals(type))
        {
            return isFieldPath(segments, true);
        }
        String collection = collection(type);
        return collection != null && segments.size() == 2 && collection.equals(segments.get(0));
    }

    private static String collection(String type)
    {
        switch (type)
        {
            case TYPE_DATA_SOURCE:
                return "dataSources"; //$NON-NLS-1$
            case TYPE_DATA_SET:
                return "dataSets"; //$NON-NLS-1$
            case TYPE_PARAMETER:
                return "parameters"; //$NON-NLS-1$
            case TYPE_CALCULATED_FIELD:
                return "calculatedFields"; //$NON-NLS-1$
            case TYPE_TOTAL_FIELD:
                return "totalFields"; //$NON-NLS-1$
            default:
                return null;
        }
    }

    private static JsonObject wrap(String collection, JsonObject entry)
    {
        JsonArray array = new JsonArray();
        array.add(entry);
        JsonObject payload = new JsonObject();
        payload.add(collection, array);
        return payload;
    }

    private static List<JsonObject> objects(JsonArray array)
    {
        if (array == null)
        {
            return Collections.emptyList();
        }
        List<JsonObject> result = new ArrayList<>();
        array.forEach(item -> {
            if (item != null && item.isJsonObject())
            {
                result.add(item.getAsJsonObject());
            }
        });
        return result;
    }

    private static String string(JsonObject object, String member)
    {
        if (object == null || !object.has(member) || !object.get(member).isJsonPrimitive())
        {
            return null;
        }
        return object.get(member).getAsString();
    }

    private static DataSet findDataSet(DataCompositionSchema schema, String name)
    {
        return findDataSet(schema.getDataSets(), name);
    }

    private static DataSet findDataSet(List<DataSet> dataSets, String name)
    {
        for (DataSet dataSet : dataSets)
        {
            if (name != null && name.equals(dataSet.getName()))
            {
                return dataSet;
            }
        }
        return null;
    }

    private static boolean isFieldPath(List<String> path, boolean exact)
    {
        int fieldsIndex = path.size() - (exact ? 2 : 1);
        if (fieldsIndex < 2 || fieldsIndex % 2 != 0
            || !"dataSets".equals(path.get(0)) || !KEY_FIELDS.equals(path.get(fieldsIndex))) //$NON-NLS-1$
        {
            return false;
        }
        for (int i = 2; i < fieldsIndex; i += 2)
        {
            if (!KEY_ITEMS.equals(path.get(i))) return false;
        }
        return true;
    }

    private static FieldTarget resolveFieldTarget(DataCompositionSchema schema, List<String> path)
    {
        boolean exact = isFieldPath(path, true);
        int fieldsIndex = path.size() - (exact ? 2 : 1);
        List<DataSet> level = schema.getDataSets();
        List<DataSet> resolved = new ArrayList<>();
        for (int selectorIndex = 1; selectorIndex < fieldsIndex; selectorIndex += 2)
        {
            String selector = path.get(selectorIndex);
            DataSet dataSet = findDataSetBySelector(level, selector);
            if (dataSet == null)
            {
                return FieldTarget.failure("Data set selector '" + selector //$NON-NLS-1$
                    + "' was not found while resolving field address. Existing data sets at that " //$NON-NLS-1$
                    + "level: " + display(dataSetKeys(level)) //$NON-NLS-1$
                    + ". Re-run dcs action='get' and copy the current field address."); //$NON-NLS-1$
            }
            resolved.add(dataSet);
            if (selectorIndex + 2 < fieldsIndex)
            {
                if (!(dataSet instanceof DataCompositionSchemaDataSetUnion))
                {
                    return FieldTarget.failure("Data set '" + dataSet.getName() + "' in field address " //$NON-NLS-1$ //$NON-NLS-2$
                        + "is kind '" + dataSetKind(dataSet) + "', not union. Only union data sets " //$NON-NLS-1$ //$NON-NLS-2$
                        + "have nested 'items'. Re-run dcs action='get' and copy the current address."); //$NON-NLS-1$
                }
                level = ((DataCompositionSchemaDataSetUnion)dataSet).getItems();
            }
        }
        return FieldTarget.success(resolved, exact);
    }

    private static DataSet findDataSetBySelector(List<DataSet> dataSets, String selector)
    {
        DataSet named = findDataSet(dataSets, selector);
        if (named != null) return named;
        if (!DcsAddress.isZeroBasedIndex(selector)) return null;
        int index = Integer.parseInt(selector);
        return index < dataSets.size() ? dataSets.get(index) : null;
    }

    private static List<String> dataSetKeys(List<DataSet> dataSets)
    {
        List<String> result = new ArrayList<>();
        for (DataSet dataSet : dataSets) result.add(dataSet.getName());
        return result;
    }

    private static List<String> fieldKeys(DataSet dataSet)
    {
        List<String> result = new ArrayList<>();
        for (DataSetField field : dataSet.getFields())
        {
            String value = fieldKey(field);
            if (value != null)
            {
                result.add(value);
            }
        }
        return result;
    }

    private static List<DataSetField> dataSetFields(DataSet dataSet, String path)
    {
        List<DataSetField> result = new ArrayList<>();
        for (DataSetField field : dataSet.getFields())
        {
            if (path.equals(fieldKey(field)))
            {
                result.add(field);
            }
        }
        return result;
    }

    private static String fieldKey(DataSetField field)
    {
        org.eclipse.emf.ecore.EStructuralFeature feature =
            field.eClass().getEStructuralFeature(KEY_DATA_PATH);
        Object value = feature == null ? null : field.eGet(feature);
        return value instanceof String ? (String)value : null;
    }

    private static JsonObject nestedDataSetPayload(List<DataSet> dataSets, JsonArray fields)
    {
        JsonObject root = null;
        JsonObject parent = null;
        for (DataSet dataSet : dataSets)
        {
            JsonObject item = new JsonObject();
            item.addProperty(KEY_NAME, dataSet.getName());
            if (root == null)
            {
                root = item;
            }
            else
            {
                JsonArray items = new JsonArray();
                items.add(item);
                parent.add(KEY_ITEMS, items);
            }
            parent = item;
        }
        parent.add(KEY_FIELDS, fields);
        return root;
    }

    private static DataCompositionSchemaDataSetField findField(DataSet dataSet,
        String path)
    {
        for (DataSetField field : dataSet.getFields())
        {
            if (field instanceof DataCompositionSchemaDataSetField
                && path != null && path.equals(((DataCompositionSchemaDataSetField)field).getDataPath()))
            {
                return (DataCompositionSchemaDataSetField)field;
            }
        }
        return null;
    }

    private static void mergeDataSourceType(DataCompositionSchema schema, JsonObject entry, String name)
    {
        if (entry.has("type") || name == null) //$NON-NLS-1$
        {
            return;
        }
        for (DataCompositionSchemaDataSource source : schema.getDataSources())
        {
            if (name.equals(source.getName()) && source.getDataSourceType() != null)
            {
                entry.addProperty("type", source.getDataSourceType()); //$NON-NLS-1$
                return;
            }
        }
    }

    private static void mergeFieldDefaults(JsonObject body, DataCompositionSchemaDataSetField current)
    {
        if (!body.has(KEY_FIELD))
        {
            body.addProperty(KEY_FIELD, current.getField());
        }
        if (body.has("role") && body.get("role").isJsonObject() && current.getRole() != null) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject role = body.getAsJsonObject("role"); //$NON-NLS-1$
            addMissing(role, "dimension", current.getRole().isDimension()); //$NON-NLS-1$
            addMissing(role, "main", current.getRole().isMain()); //$NON-NLS-1$
            addMissing(role, "required", current.getRole().isRequired()); //$NON-NLS-1$
            addMissing(role, "ignoreNullValues", current.getRole().isIgnoreNullValues()); //$NON-NLS-1$
            addMissing(role, "dimensionAttribute", current.getRole().isDimensionAttribute()); //$NON-NLS-1$
            addMissing(role, "account", current.getRole().isAccount()); //$NON-NLS-1$
            addMissing(role, "balance", current.getRole().isBalance()); //$NON-NLS-1$
            if (!role.has("periodType") && current.getRole().getPeriodType() != null) //$NON-NLS-1$
            {
                role.addProperty("periodType", current.getRole().getPeriodType().getLiteral()); //$NON-NLS-1$
            }
            if (!role.has("periodNumber")) //$NON-NLS-1$
            {
                role.addProperty("periodNumber", current.getRole().getPeriodNumber()); //$NON-NLS-1$
            }
        }
        if (body.has("useRestriction") && body.get("useRestriction").isJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
            && current.getUseRestriction() != null)
        {
            JsonObject restriction = body.getAsJsonObject("useRestriction"); //$NON-NLS-1$
            addMissing(restriction, "field", current.getUseRestriction().isField()); //$NON-NLS-1$
            addMissing(restriction, "condition", current.getUseRestriction().isCondition()); //$NON-NLS-1$
            addMissing(restriction, "group", current.getUseRestriction().isGroup()); //$NON-NLS-1$
            addMissing(restriction, "order", current.getUseRestriction().isOrder()); //$NON-NLS-1$
        }
    }

    private static void addMissing(JsonObject object, String member, boolean value)
    {
        if (!object.has(member))
        {
            object.addProperty(member, value);
        }
    }

    private static String expression(DataCompositionSchema schema, String collection, String key)
    {
        if (key == null)
        {
            return null;
        }
        if ("calculatedFields".equals(collection)) //$NON-NLS-1$
        {
            for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields())
            {
                if (key.equals(field.getDataPath()))
                {
                    return field.getExpression();
                }
            }
        }
        else
        {
            for (DataCompositionSchemaTotalField field : schema.getTotalFields())
            {
                if (key.equals(field.getDataPath()))
                {
                    return field.getExpression();
                }
            }
        }
        return null;
    }

    private static List<String> keys(DataCompositionSchema schema, String collection, String parent)
    {
        List<String> result = new ArrayList<>();
        switch (collection)
        {
            case "dataSources": //$NON-NLS-1$
                for (DataCompositionSchemaDataSource item : schema.getDataSources())
                {
                    result.add(item.getName());
                }
                break;
            case "dataSets": //$NON-NLS-1$
                for (DataSet item : schema.getDataSets())
                {
                    result.add(item.getName());
                }
                break;
            case "parameters": //$NON-NLS-1$
                for (DataCompositionSchemaParameter item : schema.getParameters())
                {
                    result.add(item.getName());
                }
                break;
            case "calculatedFields": //$NON-NLS-1$
                for (DataCompositionSchemaCalculatedField item : schema.getCalculatedFields())
                {
                    result.add(item.getDataPath());
                }
                break;
            case "totalFields": //$NON-NLS-1$
                for (DataCompositionSchemaTotalField item : schema.getTotalFields())
                {
                    result.add(item.getDataPath());
                }
                break;
            case "fields": //$NON-NLS-1$
                DataSet dataSet = findDataSet(schema, parent);
                if (dataSet != null)
                {
                    for (DataSetField item : dataSet.getFields())
                    {
                        org.eclipse.emf.ecore.EStructuralFeature feature =
                            item.eClass().getEStructuralFeature(KEY_DATA_PATH);
                        Object value = feature == null ? null : item.eGet(feature);
                        if (value instanceof String)
                        {
                            result.add((String)value);
                        }
                    }
                }
                break;
            default:
                break;
        }
        return result;
    }

    private static String missing(Request request, String key, List<String> existing)
    {
        return "action='update' could not find " + request.type + " '" + key + "' at '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + request.address + "'. Existing keys at that level: " + display(existing) //$NON-NLS-1$
            + ". Copy one of those addresses from dcs action='get', or use action='upsert' to create '" //$NON-NLS-1$
            + key + "'."; //$NON-NLS-1$
    }

    private static final class FieldTarget
    {
        final List<DataSet> dataSets;
        final DataSet dataSet;
        final boolean exact;
        final String error;

        private FieldTarget(List<DataSet> dataSets, boolean exact, String error)
        {
            this.dataSets = dataSets;
            this.dataSet = dataSets.isEmpty() ? null : dataSets.get(dataSets.size() - 1);
            this.exact = exact;
            this.error = error;
        }

        static FieldTarget success(List<DataSet> dataSets, boolean exact)
        {
            return new FieldTarget(dataSets, exact, null);
        }

        static FieldTarget failure(String error)
        {
            return new FieldTarget(Collections.<DataSet> emptyList(), false, error);
        }
    }

    private static String display(List<String> values)
    {
        return values.isEmpty() ? "(none)" : String.join(", ", values); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Immutable request plan. */
    public static final class Request
    {
        private final String action;
        private final String type;
        private final DcsAddress address;
        private final JsonObject body;
        private final DcsPresentationParser.LanguageContext languages;
        /** Set by renameForUpdate when it actually renamed the addressed node, else null. */
        private String renamedTo;

        private Request(String action, String type, DcsAddress address, JsonObject body,
            DcsPresentationParser.LanguageContext languages)
        {
            this.action = action;
            this.type = type;
            this.address = address;
            this.body = body;
            this.languages = languages;
        }
    }

    /** Pure preparation result. */
    public static final class PrepareResult
    {
        private final Request request;
        private final String error;

        private PrepareResult(Request request, String error)
        {
            this.request = request;
            this.error = error;
        }

        private static PrepareResult success(Request request)
        {
            return new PrepareResult(request, null);
        }

        private static PrepareResult failure(String error)
        {
            return new PrepareResult(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public Request request()
        {
            return request;
        }

        public String error()
        {
            return error;
        }
    }

    /** Mutation result; an error may already be a serialized ToolResult from the shared writer. */
    public static final class Result
    {
        private final DcsWriter.Result applied;
        private final String error;
        private final boolean errorJson;

        private Result(DcsWriter.Result applied, String error, boolean errorJson)
        {
            this.applied = applied;
            this.error = error;
            this.errorJson = errorJson;
        }

        private static Result success(DcsWriter.Result applied)
        {
            return new Result(applied, null, false);
        }

        private static Result failure(String error)
        {
            return new Result(null, error, false);
        }

        private static Result failureJson(String error)
        {
            return new Result(null, error, true);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public DcsWriter.Result applied()
        {
            return applied;
        }

        public String error()
        {
            return error;
        }

        public boolean isErrorJson()
        {
            return errorJson;
        }
    }

    private static final class PayloadResult
    {
        final JsonObject payload;
        final String error;

        private PayloadResult(JsonObject payload, String error)
        {
            this.payload = payload;
            this.error = error;
        }

        static PayloadResult success(JsonObject payload)
        {
            return new PayloadResult(payload, null);
        }

        static PayloadResult failure(String error)
        {
            return new PayloadResult(null, error);
        }
    }

    private static final class KeyResult
    {
        final String key;
        final String error;

        private KeyResult(String key, String error)
        {
            this.key = key;
            this.error = error;
        }

        static KeyResult success(String key)
        {
            return new KeyResult(key, null);
        }

        static KeyResult failure(String error)
        {
            return new KeyResult(null, error);
        }
    }
}
