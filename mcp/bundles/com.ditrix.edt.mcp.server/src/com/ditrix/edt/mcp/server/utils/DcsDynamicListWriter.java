/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.Enumerator;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.DynamicListKeyType;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Detached planner and thin commit adapter for {@link DynamicListExtInfo}. Field, calculated-field,
 * and parameter bodies are delegated to {@link DcsWriter#planDynamicListItems}; list settings are
 * delegated to {@link DcsSettingsWriter#planDynamicList}. Query conversion is committed through
 * {@link FormElementWriter#configureDynamicListQuery}, preserving the established
 * {@code modify_metadata} behavior and safety checks.
 */
public final class DcsDynamicListWriter
{
    private static final String ACTION_UPSERT = "upsert"; //$NON-NLS-1$
    private static final String ACTION_UPDATE = "update"; //$NON-NLS-1$

    private static final String TYPE_DYNAMIC_LIST = "dynamicList"; //$NON-NLS-1$
    private static final String TYPE_FIELD = "field"; //$NON-NLS-1$
    private static final String TYPE_PARAMETER = "parameter"; //$NON-NLS-1$
    private static final String TYPE_CALCULATED_FIELD = "calculatedField"; //$NON-NLS-1$

    private static final String KEY_FIELDS = "fields"; //$NON-NLS-1$
    private static final String KEY_CALCULATED_FIELDS = "calculatedFields"; //$NON-NLS-1$
    private static final String KEY_PARAMETERS = "parameters"; //$NON-NLS-1$
    private static final String KEY_DATA_PATH = "dataPath"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$

    private DcsDynamicListWriter()
    {
        // Utility class
    }

    /** Pure/model-read planning; the returned plan is the only mutating object. */
    public static synchronized Result plan(DynamicListExtInfo current, String action, String type,
        DcsAddress address, JsonObject body, DcsWriter.TypeResolver typeResolver,
        DcsPresentationParser.LanguageContext languages, Version version)
    {
        return plan(current, action, type, address, body, typeResolver, languages, version, null);
    }

    /** Live-project planner variant with named style/palette color resolution. */
    public static synchronized Result plan(DynamicListExtInfo current, String action, String type,
        DcsAddress address, JsonObject body, DcsWriter.TypeResolver typeResolver,
        DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors)
    {
        // replace and remove are implemented by the SHARED settings writer, which a dynamic list's
        // listSettings uses unchanged - so they belong on the settings types addressed below
        // '#/listSettings', exactly as the tool guide advertises. Refusing them wholesale here made
        // the guide a promise the tool did not keep, and nothing caught it.
        //
        // The dynamic-list-specific types stay on upsert/update: the ext-info scalars and the list's
        // own fields / calculated fields / parameters have no authoritative-replacement semantics of
        // their own, and accepting action='replace' there would silently behave like an update.
        boolean settingsType = DcsSettingsWriter.supports(type);
        if (!ACTION_UPSERT.equals(action) && !ACTION_UPDATE.equals(action) && !settingsType)
        {
            return Result.failure("Dynamic-list type '" + type + "' supports action='upsert' or " //$NON-NLS-1$ //$NON-NLS-2$
                + "'update'; got '" + action + "'. action='replace' and action='remove' are " //$NON-NLS-1$ //$NON-NLS-2$
                + "available on the settings types addressed below '#/listSettings'."); //$NON-NLS-1$
        }
        if (current == null && settingsType
            && !ACTION_UPSERT.equals(action) && !ACTION_UPDATE.equals(action))
        {
            return Result.failure("action='" + action + "' needs an existing dynamic list, but this " //$NON-NLS-1$ //$NON-NLS-2$
                + "form attribute is still a plain attribute. Create the list first with " //$NON-NLS-1$
                + "action='upsert' and type='dynamicList'."); //$NON-NLS-1$
        }
        if (current == null && ACTION_UPDATE.equals(action))
        {
            return Result.failure("action='update' cannot find an existing dynamic list at form " //$NON-NLS-1$
                + "attribute '" + address.rootFqn() + "'; that attribute is still plain. " //$NON-NLS-1$ //$NON-NLS-2$
                + "update never creates or converts a node. Use action='upsert' with " //$NON-NLS-1$
                + "type='dynamicList' and a non-empty 'queryText' or 'mainTable' to request the " //$NON-NLS-1$
                + "guarded conversion."); //$NON-NLS-1$
        }
        if (current == null)
        {
            // Detached placeholder for an upsert conversion. The commit still goes through
            // FormElementWriter.configureDynamicListQuery, which creates the real ext-info only after
            // the existing consent/orphan preflight has allowed the destructive retype.
            current = FormFactory.eINSTANCE.createDynamicListExtInfo();
        }
        String presentation = DcsPresentationParser.validateRecursively(body, languages);
        if (presentation != null)
        {
            return Result.failure(presentation);
        }

        JsonObject normalized;
        if (TYPE_DYNAMIC_LIST.equals(type))
        {
            if (address.hasPointer())
            {
                return Result.failure("type='dynamicList' targets the bare form-attribute root; got '" //$NON-NLS-1$
                    + address + "'. Remove the '#/...' fragment."); //$NON-NLS-1$
            }
            String members = checkMembers(body, "dynamic-list body", //$NON-NLS-1$
                "queryText", "customQuery", "mainTable", "dynamicDataRead", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "autoFillAvailableFields", "autoSaveUserSettings", //$NON-NLS-1$ //$NON-NLS-2$
                "getInvisibleFieldPresentations", "keyType", "keyField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                KEY_FIELDS, KEY_CALCULATED_FIELDS, KEY_PARAMETERS, "listSettings"); //$NON-NLS-1$
            if (members != null)
            {
                return Result.failure(members);
            }
            normalized = body.deepCopy();
        }
        else if (TYPE_FIELD.equals(type) || TYPE_PARAMETER.equals(type)
            || TYPE_CALCULATED_FIELD.equals(type))
        {
            NormalizeResult singular = normalizeSingular(current, action, type, address, body);
            if (singular.error != null)
            {
                return Result.failure(singular.error);
            }
            normalized = singular.body;
        }
        else if (DcsSettingsWriter.supports(type))
        {
            // The project's REAL platform version, not Version.LATEST: conditional-appearance
            // parameter keys are validated against the version's parameter list, so defaulting
            // would accept a key the project's platform does not have.
            DcsSettingsWriter.SettingsResult settings = DcsSettingsWriter.planDynamicList(
                current.getListSettings(), action, type, address, body, languages, version,
                namedColors);
            return settings.isSuccess()
                ? Result.success(Plan.settingsOnly(settings.settings(), settings.touched()))
                : Result.failure(settings.error());
        }
        else
        {
            return Result.failure("Type '" + type + "' is not authorable on a dynamic list. Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "dynamicList, field, calculatedField, parameter, or a settings type."); //$NON-NLS-1$
        }

        ScalarResult scalars = parseScalars(normalized);
        if (scalars.error != null)
        {
            return Result.failure(scalars.error);
        }
        JsonObject itemMembers = itemMembers(normalized);
        DcsWriter.DynamicItemsResult items = DcsWriter.planDynamicListItems(current.getFields(),
            current.getCalculatedFields(), current.getParameters(), action, itemMembers,
            typeResolver, languages, version, namedColors);
        if (!items.isSuccess())
        {
            return Result.failureJson(items.errorJson());
        }
        // The project's real version here too: a type='dynamicList' body can carry a whole
        // listSettings block, conditional-appearance parameters included, so the earlier fix on the
        // concrete-settings branch alone left this path validating against Version.LATEST.
        DcsSettingsWriter.SettingsResult settings = DcsSettingsWriter.planDynamicList(
            current.getListSettings(), action, TYPE_DYNAMIC_LIST, address,
            DcsSettingsWriter.dynamicListMembers(normalized), languages, version, namedColors);
        if (!settings.isSuccess())
        {
            return Result.failure(settings.error());
        }
        return Result.success(new Plan(scalars, items, settings.settings(), settings.touched()));
    }

    private static NormalizeResult normalizeSingular(DynamicListExtInfo current, String action,
        String type, DcsAddress address, JsonObject body)
    {
        String collection = TYPE_FIELD.equals(type) ? KEY_FIELDS
            : TYPE_PARAMETER.equals(type) ? KEY_PARAMETERS : KEY_CALCULATED_FIELDS;
        String keyMember = TYPE_PARAMETER.equals(type) ? KEY_NAME : KEY_DATA_PATH;
        List<String> segments = address.segments();
        String pointerKey = null;
        if (segments.isEmpty() || segments.size() == 1 && collection.equals(segments.get(0)))
        {
            // key comes from the body
        }
        else if (segments.size() == 2 && collection.equals(segments.get(0)))
        {
            pointerKey = segments.get(1);
        }
        else
        {
            return NormalizeResult.failure("type='" + type + "' needs the root, '#/" + collection //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "', or an exact '#/" + collection + "/<naturalKey>' address; got '" //$NON-NLS-1$ //$NON-NLS-2$
                + address + "'. Copy a matching address from dcs action='get'."); //$NON-NLS-1$
        }
        String bodyKey = string(body, keyMember);
        if (pointerKey != null && bodyKey != null && !pointerKey.equals(bodyKey))
        {
            return NormalizeResult.failure("Body natural key '" + bodyKey //$NON-NLS-1$
                + "' does not match address key '" + pointerKey + "'. Make '" + keyMember //$NON-NLS-1$ //$NON-NLS-2$
                + "' match the pointer, or omit it."); //$NON-NLS-1$
        }
        String key = pointerKey != null ? pointerKey : bodyKey;
        if (key == null || key.isEmpty())
        {
            return NormalizeResult.failure("Body for type='" + type + "' needs a non-empty '" //$NON-NLS-1$ //$NON-NLS-2$
                + keyMember + "' natural key. Add it and retry."); //$NON-NLS-1$
        }
        List<String> existing = keys(current, type);
        if (ACTION_UPDATE.equals(action) && !existing.contains(key))
        {
            return NormalizeResult.failure("action='update' could not find " + type + " '" + key //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "'. Existing keys: " + (existing.isEmpty() ? "(none)" : String.join(", ", existing)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". Copy an address from get, or use action='upsert' to create it."); //$NON-NLS-1$
        }
        JsonObject entry = body.deepCopy();
        entry.addProperty(keyMember, key);
        JsonArray values = new JsonArray();
        values.add(entry);
        JsonObject normalized = new JsonObject();
        normalized.add(collection, values);
        return NormalizeResult.success(normalized);
    }

    private static ScalarResult parseScalars(JsonObject body)
    {
        ScalarResult result = new ScalarResult();
        if (body.has("queryText")) //$NON-NLS-1$
        {
            result.queryText = stringValue(body, "queryText", "dynamic-list body"); //$NON-NLS-1$ //$NON-NLS-2$
            if (valueError != null)
            {
                return ScalarResult.failure(valueError);
            }
            result.queryTextTouched = true;
        }
        if (body.has("customQuery")) //$NON-NLS-1$
        {
            result.customQuery = bool(body, "customQuery", "dynamic-list body"); //$NON-NLS-1$ //$NON-NLS-2$
            if (valueError != null)
            {
                return ScalarResult.failure(valueError);
            }
        }
        if (body.has("mainTable")) //$NON-NLS-1$
        {
            result.mainTable = requiredString(body, "mainTable", "dynamic-list body"); //$NON-NLS-1$ //$NON-NLS-2$
            if (valueError != null)
            {
                return ScalarResult.failure(valueError);
            }
        }
        result.dynamicDataRead = optionalBool(body, "dynamicDataRead", "dynamic-list body"); //$NON-NLS-1$ //$NON-NLS-2$
        if (valueError != null) return ScalarResult.failure(valueError);
        result.autoFillAvailableFields = optionalBool(body, "autoFillAvailableFields", //$NON-NLS-1$
            "dynamic-list body"); //$NON-NLS-1$
        if (valueError != null) return ScalarResult.failure(valueError);
        result.autoSaveUserSettings = optionalBool(body, "autoSaveUserSettings", //$NON-NLS-1$
            "dynamic-list body"); //$NON-NLS-1$
        if (valueError != null) return ScalarResult.failure(valueError);
        result.getInvisibleFieldPresentations = optionalBool(body,
            "getInvisibleFieldPresentations", "dynamic-list body"); //$NON-NLS-1$ //$NON-NLS-2$
        if (valueError != null) return ScalarResult.failure(valueError);
        if (body.has("keyType")) //$NON-NLS-1$
        {
            String token = requiredString(body, "keyType", "dynamic-list body"); //$NON-NLS-1$ //$NON-NLS-2$
            if (valueError != null) return ScalarResult.failure(valueError);
            result.keyType = resolveEnum(token, DynamicListKeyType.values());
            if (result.keyType == null)
            {
                return ScalarResult.failure(enumError("keyType", token, DynamicListKeyType.values())); //$NON-NLS-1$
            }
        }
        if (body.has("keyField")) //$NON-NLS-1$
        {
            JsonElement raw = body.get("keyField"); //$NON-NLS-1$
            if (!raw.isJsonArray())
            {
                return ScalarResult.failure("Member 'dynamic-list body.keyField' must be an array of " //$NON-NLS-1$
                    + "field-path strings, e.g. ['Ref']."); //$NON-NLS-1$
            }
            result.keyFields = new ArrayList<>();
            for (int i = 0; i < raw.getAsJsonArray().size(); i++)
            {
                JsonElement value = raw.getAsJsonArray().get(i);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().trim().isEmpty())
                {
                    return ScalarResult.failure("keyField entry '" + value + "' at index " + i //$NON-NLS-1$ //$NON-NLS-2$
                        + " is invalid. Pass a non-empty field-path string."); //$NON-NLS-1$
                }
                result.keyFields.add(value.getAsString());
            }
        }
        return result;
    }

    private static JsonObject itemMembers(JsonObject body)
    {
        JsonObject result = new JsonObject();
        copy(body, result, KEY_FIELDS);
        copy(body, result, KEY_CALCULATED_FIELDS);
        copy(body, result, KEY_PARAMETERS);
        return result;
    }

    private static List<String> keys(DynamicListExtInfo current, String type)
    {
        List<String> result = new ArrayList<>();
        if (TYPE_FIELD.equals(type))
        {
            for (DataSetField field : current.getFields())
            {
                Object value = feature(field, KEY_DATA_PATH);
                if (value instanceof String) result.add((String)value);
            }
        }
        else if (TYPE_CALCULATED_FIELD.equals(type))
        {
            for (DataCompositionSchemaCalculatedField field : current.getCalculatedFields())
            {
                result.add(field.getDataPath());
            }
        }
        else
        {
            for (DataCompositionSchemaParameter parameter : current.getParameters())
            {
                result.add(parameter.getName());
            }
        }
        return result;
    }

    private static Object feature(DataSetField field, String name)
    {
        org.eclipse.emf.ecore.EStructuralFeature feature = field.eClass().getEStructuralFeature(name);
        return feature == null ? null : field.eGet(feature);
    }

    private static String string(JsonObject body, String member)
    {
        JsonElement value = body.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static void copy(JsonObject source, JsonObject target, String member)
    {
        if (source.has(member)) target.add(member, source.get(member).deepCopy());
    }

    private static String checkMembers(JsonObject body, String path, String... accepted)
    {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(accepted));
        for (String member : body.keySet())
        {
            if (!allowed.contains(member))
            {
                return "Unknown member '" + member + "' in " + path + ". Accepted members: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + String.join(", ", allowed) + ". Remove '" + member + "' or use one of them."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return null;
    }

    private static String valueError;

    private static String requiredString(JsonObject body, String member, String path)
    {
        JsonElement value = body.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().trim().isEmpty())
        {
            valueError = "Member '" + path + "." + member + "' must be a non-empty string."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        valueError = null;
        return value.getAsString();
    }

    private static String stringValue(JsonObject body, String member, String path)
    {
        JsonElement value = body.get(member);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isString())
        {
            valueError = "Member '" + path + "." + member + "' must be a string."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        valueError = null;
        return value.getAsString();
    }

    private static Boolean bool(JsonObject body, String member, String path)
    {
        JsonElement value = body.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
        {
            valueError = "Member '" + path + "." + member + "' must be true or false."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        valueError = null;
        return Boolean.valueOf(value.getAsBoolean());
    }

    private static Boolean optionalBool(JsonObject body, String member, String path)
    {
        if (!body.has(member))
        {
            valueError = null;
            return null;
        }
        return bool(body, member, path);
    }

    private static <T extends Enum<T> & Enumerator> T resolveEnum(String token, T[] values)
    {
        for (T value : values)
        {
            if (value.getLiteral().equalsIgnoreCase(token) || value.getName().equalsIgnoreCase(token)
                || value.name().equalsIgnoreCase(token))
            {
                return value;
            }
        }
        return null;
    }

    private static <T extends Enum<T> & Enumerator> String enumError(String member, String bad,
        T[] values)
    {
        List<String> allowed = new ArrayList<>();
        for (T value : values) allowed.add(value.getLiteral());
        return "Enum value '" + bad + "' for dynamic-list '" + member //$NON-NLS-1$ //$NON-NLS-2$
            + "' is invalid. Use one of the platform literals: " + String.join(", ", allowed) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Detached mutation; commit only after the tool has validated hash and all request layers. */
    public static final class Plan
    {
        private final ScalarResult scalars;
        private final DcsWriter.DynamicItemsResult items;
        private final DataCompositionSettings settings;
        private final boolean settingsTouched;

        private Plan(ScalarResult scalars, DcsWriter.DynamicItemsResult items,
            DataCompositionSettings settings, boolean settingsTouched)
        {
            this.scalars = scalars;
            this.items = items;
            this.settings = settings;
            this.settingsTouched = settingsTouched;
        }

        private static Plan settingsOnly(DataCompositionSettings settings, boolean touched)
        {
            return new Plan(new ScalarResult(), null, settings, touched);
        }

        /** Applies the validated plan. Query/main-table changes intentionally use the legacy path. */
        public CommitResult commit(org.eclipse.emf.ecore.EObject formModel,
            org.eclipse.emf.ecore.EObject attribute, DynamicListExtInfo extInfo,
            IBmTransaction transaction, Configuration configuration, Version version)
        {
            List<String> applied = new ArrayList<>();
            if (scalars.queryTextTouched || scalars.customQuery != null || scalars.mainTable != null)
            {
                applied.addAll(FormElementWriter.configureDynamicListQuery(formModel, attribute,
                    scalars.queryTextTouched ? scalars.queryText : null, scalars.customQuery,
                    scalars.mainTable, configuration, version));
            }
            org.eclipse.emf.ecore.EStructuralFeature feature =
                attribute.eClass().getEStructuralFeature("extInfo"); //$NON-NLS-1$
            Object attached = feature == null ? null : attribute.eGet(feature);
            if (attached instanceof DynamicListExtInfo)
            {
                extInfo = (DynamicListExtInfo)attached;
            }
            if (extInfo == null)
            {
                throw new FormValidationException(com.ditrix.edt.mcp.server.protocol.ToolResult.error(
                    "Form attribute is not a dynamic list. To convert it, include a non-empty " //$NON-NLS-1$
                        + "'queryText' or 'mainTable' in the dynamicList body, approve the " //$NON-NLS-1$
                        + "destructive retype, then retry the settings/item write.").toJson()); //$NON-NLS-1$
            }
            if (scalars.dynamicDataRead != null)
            {
                extInfo.setDynamicDataRead(scalars.dynamicDataRead.booleanValue());
                applied.add("dynamicDataRead"); //$NON-NLS-1$
            }
            if (scalars.autoFillAvailableFields != null)
            {
                extInfo.setAutoFillAvailableFields(scalars.autoFillAvailableFields.booleanValue());
                applied.add("autoFillAvailableFields"); //$NON-NLS-1$
            }
            if (scalars.autoSaveUserSettings != null)
            {
                extInfo.setAutoSaveUserSettings(scalars.autoSaveUserSettings.booleanValue());
                applied.add("autoSaveUserSettings"); //$NON-NLS-1$
            }
            if (scalars.getInvisibleFieldPresentations != null)
            {
                extInfo.setGetInvisibleFieldPresentations(
                    scalars.getInvisibleFieldPresentations.booleanValue());
                applied.add("getInvisibleFieldPresentations"); //$NON-NLS-1$
            }
            if (scalars.keyType != null)
            {
                extInfo.setKeyType(scalars.keyType);
                applied.add("keyType"); //$NON-NLS-1$
            }
            if (scalars.keyFields != null)
            {
                extInfo.getKeyField().clear();
                extInfo.getKeyField().addAll(scalars.keyFields);
                applied.add("keyField"); //$NON-NLS-1$
            }
            if (items != null && items.fieldsTouched())
            {
                extInfo.getFields().clear();
                extInfo.getFields().addAll(items.fields());
                applied.add(KEY_FIELDS);
            }
            if (items != null && items.calculatedFieldsTouched())
            {
                extInfo.getCalculatedFields().clear();
                extInfo.getCalculatedFields().addAll(items.calculatedFields());
                applied.add(KEY_CALCULATED_FIELDS);
            }
            if (items != null && items.parametersTouched())
            {
                extInfo.getParameters().clear();
                extInfo.getParameters().addAll(items.parameters());
                applied.add(KEY_PARAMETERS);
            }
            String settingsFqn = null;
            if (settingsTouched)
            {
                settingsFqn = commitSettingsCarrier(extInfo, settings, transaction);
                applied.add("listSettings"); //$NON-NLS-1$
            }
            return new CommitResult(applied, settingsFqn,
                DcsModelComparison.snapshot(extInfo));
        }

        public boolean settingsTouched() { return settingsTouched; }
        public DataCompositionSettings settings() { return settings; }
        public String queryText() { return scalars.queryTextTouched ? scalars.queryText : null; }
        public String mainTable() { return scalars.mainTable; }
        public boolean canConvertPlainAttribute()
        {
            return queryText() != null && !queryText().isEmpty()
                || mainTable() != null && !mainTable().isEmpty();
        }
        public DcsWriter.Result appliedCounts()
        {
            return items == null ? null : items.applied();
        }
    }

    /**
     * Materializes the external settings carrier before copying content into it. An unattached
     * {@code @ExternalProperty} object may be replaced by BM attachment, so assigning the populated
     * detached plan first can lose that content on the first write. Refetching after attachment makes
     * the copy target the exact instance the committed ext-info owns.
     *
     * <p>A {@code null} transaction is supported for detached-model tests; production commits always
     * pass their active BM write transaction.</p>
     */
    static String commitSettingsCarrier(DynamicListExtInfo extInfo,
        DataCompositionSettings planned, IBmTransaction transaction)
    {
        SettingsCarrierAttacher attacher = transaction == null ? null : current ->
        {
            DcsDynamicListContent.Result attached =
                DcsDynamicListContent.ensureAttached(transaction, current);
            if (!attached.isSuccess())
            {
                throw new FormValidationException(
                    com.ditrix.edt.mcp.server.protocol.ToolResult.error(attached.error()).toJson());
            }
            return attached.fqn();
        };
        return commitSettingsCarrierWithAttachment(extInfo, planned, attacher);
    }

    /** Package-visible attachment seam: unit tests can reproduce BM replacing the carrier. */
    static String commitSettingsCarrierWithAttachment(DynamicListExtInfo extInfo,
        DataCompositionSettings planned, SettingsCarrierAttacher attacher)
    {
        if (extInfo.getListSettings() == null)
        {
            extInfo.setListSettings(DcsFactory.eINSTANCE.createDataCompositionSettings());
        }
        String settingsFqn = attacher == null ? null : attacher.attach(extInfo);
        DataCompositionSettings attachedSettings = extInfo.getListSettings();
        if (attachedSettings == null)
        {
            throw new FormValidationException(
                com.ditrix.edt.mcp.server.protocol.ToolResult.error(
                    "Dynamic-list listSettings were materialized, but the committed ext-info does " //$NON-NLS-1$
                        + "not expose the attached settings object. The write was rolled back; " //$NON-NLS-1$
                        + "re-open the form and retry.").toJson()); //$NON-NLS-1$
        }
        DcsSettingsWriter.commitSettings(attachedSettings, planned);
        String difference = DcsModelComparison.firstDifference(planned, attachedSettings);
        if (difference != null)
        {
            throw new FormValidationException(
                com.ditrix.edt.mcp.server.protocol.ToolResult.error(
                    "Dynamic-list listSettings do not match the validated plan after attachment. " //$NON-NLS-1$
                        + "First differing model path: " + difference + ". The write was rolled " //$NON-NLS-1$ //$NON-NLS-2$
                        + "back instead of reporting Applied; re-open the form and retry.").toJson()); //$NON-NLS-1$
        }
        return settingsFqn;
    }

    @FunctionalInterface
    interface SettingsCarrierAttacher
    {
        String attach(DynamicListExtInfo extInfo);
    }

    /** Actual transaction-local state produced by {@link Plan#commit}. */
    public static final class CommitResult
    {
        private final List<String> applied;
        private final String settingsFqn;
        private final DynamicListExtInfo modelSnapshot;

        private CommitResult(List<String> applied, String settingsFqn,
            DynamicListExtInfo modelSnapshot)
        {
            this.applied = applied;
            this.settingsFqn = settingsFqn;
            this.modelSnapshot = modelSnapshot;
        }

        public List<String> applied() { return applied; }
        public String settingsFqn() { return settingsFqn; }
        public DynamicListExtInfo modelSnapshot() { return modelSnapshot; }
    }

    /** Planning outcome; shared-writer errors may already be serialized ToolResult JSON. */
    public static final class Result
    {
        private final Plan plan;
        private final String error;
        private final boolean errorJson;
        private Result(Plan plan, String error, boolean errorJson)
        { this.plan = plan; this.error = error; this.errorJson = errorJson; }
        private static Result success(Plan plan) { return new Result(plan, null, false); }
        private static Result failure(String error) { return new Result(null, error, false); }
        private static Result failureJson(String error) { return new Result(null, error, true); }
        public boolean isSuccess() { return error == null; }
        public Plan plan() { return plan; }
        public String error() { return error; }
        public boolean isErrorJson() { return errorJson; }
    }

    private static final class NormalizeResult
    {
        final JsonObject body; final String error;
        private NormalizeResult(JsonObject body, String error) { this.body = body; this.error = error; }
        static NormalizeResult success(JsonObject body) { return new NormalizeResult(body, null); }
        static NormalizeResult failure(String error) { return new NormalizeResult(null, error); }
    }

    private static final class ScalarResult
    {
        String queryText;
        boolean queryTextTouched;
        Boolean customQuery;
        String mainTable;
        Boolean dynamicDataRead;
        Boolean autoFillAvailableFields;
        Boolean autoSaveUserSettings;
        Boolean getInvisibleFieldPresentations;
        DynamicListKeyType keyType;
        List<String> keyFields;
        String error;
        static ScalarResult failure(String error)
        {
            ScalarResult result = new ScalarResult();
            result.error = error;
            return result;
        }
    }
}
