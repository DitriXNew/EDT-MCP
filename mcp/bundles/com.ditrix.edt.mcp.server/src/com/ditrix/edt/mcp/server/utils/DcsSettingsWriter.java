/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionGroupType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionPeriodAdditionType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionSortDirection;
import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionComparisonType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionDataParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFieldPlacement;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterApplicationType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemsGroupType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrder;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFieldGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettingsItemViewMode;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.FilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.GroupItem;
import com._1c.g5.v8.dt.dcs.model.settings.OrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.SelectedItem;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.dcs.model.settings.StructureItem;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NullValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Value;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The single typed authoring implementation for {@link DataCompositionSettings}. Schema default
 * settings, schema variants, and dynamic-list list settings all enter through {@link #planSettings};
 * the owner-specific methods only locate the settings object and commit the detached plan.
 *
 * <p>Every plan is built against an {@link EcoreUtil#copy} (or a new detached settings object). Thus
 * enum, value, subtype, index and unknown-member validation finishes before the caller performs the
 * first mutation of its transaction-bound model.</p>
 */
public final class DcsSettingsWriter
{
    private static final String ACTION_UPSERT = "upsert"; //$NON-NLS-1$
    private static final String ACTION_UPDATE = "update"; //$NON-NLS-1$

    private static final String TYPE_SCHEMA = "schema"; //$NON-NLS-1$
    private static final String TYPE_DYNAMIC_LIST = "dynamicList"; //$NON-NLS-1$
    private static final String TYPE_VARIANT = "variant"; //$NON-NLS-1$
    private static final String TYPE_GROUPING = "grouping"; //$NON-NLS-1$
    private static final String TYPE_SELECTION = "selection"; //$NON-NLS-1$
    private static final String TYPE_FILTER = "filter"; //$NON-NLS-1$
    private static final String TYPE_DATA_PARAMETER = "dataParameter"; //$NON-NLS-1$
    private static final String TYPE_ORDER = "order"; //$NON-NLS-1$
    private static final String TYPE_OUTPUT_PARAMETER = "outputParameter"; //$NON-NLS-1$
    private static final String TYPE_USER_SETTINGS = "userSettings"; //$NON-NLS-1$

    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$
    private static final String KEY_KIND = "kind"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_USE = "use"; //$NON-NLS-1$
    private static final String KEY_FIELD = "field"; //$NON-NLS-1$
    private static final String KEY_TITLE = "title"; //$NON-NLS-1$
    private static final String KEY_PRESENTATION = "presentation"; //$NON-NLS-1$
    private static final String KEY_VIEW_MODE = "viewMode"; //$NON-NLS-1$
    private static final String KEY_USER_SETTING_ID = "userSettingID"; //$NON-NLS-1$
    private static final String KEY_USER_SETTING_PRESENTATION = "userSettingPresentation"; //$NON-NLS-1$

    private DcsSettingsWriter()
    {
        // Utility class
    }

    /** Whether {@code type} is one of the supported settings types. */
    public static boolean supports(String type)
    {
        return TYPE_VARIANT.equals(type) || TYPE_GROUPING.equals(type) || TYPE_SELECTION.equals(type)
            || TYPE_FILTER.equals(type) || TYPE_DATA_PARAMETER.equals(type) || TYPE_ORDER.equals(type)
            || TYPE_OUTPUT_PARAMETER.equals(type) || TYPE_USER_SETTINGS.equals(type);
    }

    /** Extracts the settings members accepted in a root {@code type=schema} body. */
    public static JsonObject schemaMembers(JsonObject body)
    {
        JsonObject result = new JsonObject();
        copyMember(body, result, "defaultSettings"); //$NON-NLS-1$
        copyMember(body, result, "variants"); //$NON-NLS-1$
        return result;
    }

    /** Extracts the shared settings member accepted in a root {@code type=dynamicList} body. */
    public static JsonObject dynamicListMembers(JsonObject body)
    {
        JsonObject result = new JsonObject();
        copyMember(body, result, "listSettings"); //$NON-NLS-1$
        return result;
    }

    /**
     * Copies a fully validated detached plan into an existing settings object while preserving the
     * target's BM identity. Preserving that identity is essential for dynamic-list settings that
     * already own the external {@code ListSettings.dcss} top-object FQN.
     */
    @SuppressWarnings("unchecked")
    public static void commitSettings(DataCompositionSettings target,
        DataCompositionSettings planned)
    {
        if (target == null || planned == null || target == planned)
        {
            return;
        }
        DataCompositionSettings copy = EcoreUtil.copy(planned);
        for (EStructuralFeature targetFeature : target.eClass().getEAllStructuralFeatures())
        {
            if (!targetFeature.isChangeable() || targetFeature.isDerived())
            {
                continue;
            }
            EStructuralFeature sourceFeature = copy.eClass()
                .getEStructuralFeature(targetFeature.getName());
            if (sourceFeature == null)
            {
                continue;
            }
            Object value = copy.eGet(sourceFeature);
            if (targetFeature.isMany())
            {
                EList<Object> targetValues = (EList<Object>)target.eGet(targetFeature);
                targetValues.clear();
                targetValues.addAll(new ArrayList<>((Collection<Object>)value));
            }
            else if (sourceFeature.isUnsettable() && !copy.eIsSet(sourceFeature))
            {
                target.eUnset(targetFeature);
            }
            else
            {
                target.eSet(targetFeature, value);
            }
        }
    }

    /**
     * Builds a detached schema-settings mutation. Calling {@link SchemaPlan#commit} is the only point
     * that mutates {@code schema}.
     */
    public static synchronized SchemaResult planSchema(DataCompositionSchema schema, String action, String type,
        DcsAddress address, JsonObject body, DcsPresentationParser.LanguageContext languages)
    {
        String common = validateCommon(action, type, address, body, languages);
        if (common != null)
        {
            return SchemaResult.failure(common);
        }
        DataCompositionSettings defaultSettings = copy(schema.getDefaultSettings());
        List<SettingsVariant> variants = copyVariants(schema.getSettingsVariants());
        boolean defaultTouched = false;
        boolean variantsTouched = false;

        if (TYPE_SCHEMA.equals(type))
        {
            if (address.hasPointer())
            {
                return SchemaResult.failure("type='schema' settings target the bare root; got '" //$NON-NLS-1$
                    + address + "'. Remove the '#/...' fragment."); //$NON-NLS-1$
            }
            String members = checkMembers(body, "schema settings body", //$NON-NLS-1$
                "defaultSettings", "variants"); //$NON-NLS-1$ //$NON-NLS-2$
            if (members != null)
            {
                return SchemaResult.failure(members);
            }
            if (body.has("defaultSettings")) //$NON-NLS-1$
            {
                JsonObject settingsBody = object(body, "defaultSettings", "schema settings body"); //$NON-NLS-1$ //$NON-NLS-2$
                if (settingsBody == null)
                {
                    return SchemaResult.failure(objectError);
                }
                SettingsResult planned = planSettings(defaultSettings, Collections.emptyList(), action,
                    TYPE_USER_SETTINGS, settingsBody, languages);
                if (!planned.isSuccess())
                {
                    return SchemaResult.failure(planned.error());
                }
                defaultSettings = planned.settings();
                defaultTouched = true;
            }
            if (body.has("variants")) //$NON-NLS-1$
            {
                JsonArray array = array(body, "variants", "schema settings body"); //$NON-NLS-1$ //$NON-NLS-2$
                if (array == null)
                {
                    return SchemaResult.failure(arrayError);
                }
                for (int i = 0; i < array.size(); i++)
                {
                    JsonObject variantBody = arrayObject(array, i, "variants"); //$NON-NLS-1$
                    if (variantBody == null)
                    {
                        return SchemaResult.failure(arrayObjectError);
                    }
                    String error = applyVariant(variants, null, action, variantBody, languages,
                        "body.variants[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (error != null)
                    {
                        return SchemaResult.failure(error);
                    }
                }
                variantsTouched = !array.isEmpty();
            }
            return SchemaResult.success(new SchemaPlan(defaultSettings, variants, defaultTouched,
                variantsTouched));
        }

        if (TYPE_VARIANT.equals(type))
        {
            List<String> segments = address.segments();
            String pointerName = null;
            if (segments.isEmpty() || segments.size() == 1 && "variants".equals(segments.get(0))) //$NON-NLS-1$
            {
                // natural key comes from body
            }
            else if (segments.size() == 2 && "variants".equals(segments.get(0))) //$NON-NLS-1$
            {
                pointerName = segments.get(1);
            }
            else
            {
                return SchemaResult.failure("type='variant' needs the root, '#/variants', or an " //$NON-NLS-1$
                    + "exact '#/variants/<name>' address; got '" + address //$NON-NLS-1$
                    + "'. Copy a variant address from dcs action='get'."); //$NON-NLS-1$
            }
            String error = applyVariant(variants, pointerName, action, body, languages, "body"); //$NON-NLS-1$
            return error == null
                ? SchemaResult.success(new SchemaPlan(defaultSettings, variants, false, true))
                : SchemaResult.failure(error);
        }

        SettingsLocation location = locateSchemaSettings(schema, variants, address, type, body);
        if (location.error != null)
        {
            return SchemaResult.failure(location.error);
        }
        SettingsResult planned = planSettings(location.settings, location.relative, action, type, body,
            languages);
        if (!planned.isSuccess())
        {
            return SchemaResult.failure(planned.error());
        }
        if (location.variantIndex >= 0)
        {
            variants.get(location.variantIndex).setSettings(planned.settings());
            variantsTouched = true;
        }
        else
        {
            defaultSettings = planned.settings();
            defaultTouched = true;
        }
        return SchemaResult.success(new SchemaPlan(defaultSettings, variants, defaultTouched,
            variantsTouched));
    }

    /** Builds a detached dynamic-list settings mutation through the same {@link #planSettings} path. */
    public static synchronized SettingsResult planDynamicList(DataCompositionSettings current, String action,
        String type, DcsAddress address, JsonObject body,
        DcsPresentationParser.LanguageContext languages)
    {
        String common = validateCommon(action, type, address, body, languages);
        if (common != null)
        {
            return SettingsResult.failure(common);
        }
        if (TYPE_VARIANT.equals(type))
        {
            return SettingsResult.failure("type='variant' is available only on schema roots. " //$NON-NLS-1$
                + "Use userSettings or a concrete settings type below '#/listSettings'."); //$NON-NLS-1$
        }
        if (TYPE_DYNAMIC_LIST.equals(type))
        {
            if (address.hasPointer())
            {
                return SettingsResult.failure("type='dynamicList' targets the bare form-attribute " //$NON-NLS-1$
                    + "root; got '" + address + "'. Remove the '#/...' fragment."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String members = checkMembers(body, "dynamic-list settings body", "listSettings"); //$NON-NLS-1$ //$NON-NLS-2$
            if (members != null)
            {
                return SettingsResult.failure(members);
            }
            if (!body.has("listSettings")) //$NON-NLS-1$
            {
                return SettingsResult.success(copy(current), false);
            }
            JsonObject settingsBody = object(body, "listSettings", "dynamic-list settings body"); //$NON-NLS-1$ //$NON-NLS-2$
            return settingsBody == null ? SettingsResult.failure(objectError)
                : withTouched(planSettings(current, Collections.emptyList(), action,
                    TYPE_USER_SETTINGS, settingsBody, languages));
        }

        List<String> segments = new ArrayList<>(address.segments());
        if (!segments.isEmpty() && "listSettings".equals(segments.get(0))) //$NON-NLS-1$
        {
            segments.remove(0);
        }
        else if (!segments.isEmpty())
        {
            return SettingsResult.failure("Dynamic-list settings address '" + address //$NON-NLS-1$
                + "' must start with '#/listSettings'. Copy the settings address from dcs action='get'."); //$NON-NLS-1$
        }
        return withTouched(planSettings(current, segments, action, type, body, languages));
    }

    /**
     * Shared owner-independent settings planner. This is deliberately public for the equivalence unit
     * test: both report and dynamic-list entry points must produce this same settings tree.
     */
    public static synchronized SettingsResult planSettings(DataCompositionSettings current, List<String> relative,
        String action, String type, JsonObject body, DcsPresentationParser.LanguageContext languages)
    {
        DataCompositionSettings working = copy(current);
        if (working == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return SettingsResult.failure("action='update' cannot find settings at the requested " //$NON-NLS-1$
                    + "address. Use action='upsert' to create them first."); //$NON-NLS-1$
            }
            working = DcsFactory.eINSTANCE.createDataCompositionSettings();
        }
        List<String> path = relative == null ? Collections.emptyList() : relative;
        if (path.isEmpty())
        {
            path = defaultPath(type);
        }
        String error = path.isEmpty() ? applySettingsBody(working, body, action, languages, "body") //$NON-NLS-1$
            : applySettingsPath(working, path, body, action, type, languages);
        return error == null ? SettingsResult.success(working, true) : SettingsResult.failure(error);
    }

    private static String validateCommon(String action, String type, DcsAddress address, JsonObject body,
        DcsPresentationParser.LanguageContext languages)
    {
        if (!ACTION_UPSERT.equals(action) && !ACTION_UPDATE.equals(action))
        {
            return "Settings authoring supports action='upsert' or 'update'; got '" + action //$NON-NLS-1$
                + "'. Use one of those actions."; //$NON-NLS-1$
        }
        if (address == null || body == null)
        {
            return "A parsed DCS address and one body object are required for settings authoring."; //$NON-NLS-1$
        }
        if (!TYPE_SCHEMA.equals(type) && !TYPE_DYNAMIC_LIST.equals(type) && !supports(type))
        {
            return "Type '" + type + "' is not a settings type. Use variant, grouping, selection, " //$NON-NLS-1$ //$NON-NLS-2$
                + "filter, dataParameter, order, outputParameter, or userSettings."; //$NON-NLS-1$
        }
        String presentation = DcsPresentationParser.validateRecursively(body, languages);
        return presentation;
    }

    private static SettingsLocation locateSchemaSettings(DataCompositionSchema schema,
        List<SettingsVariant> variants, DcsAddress address, String type, JsonObject body)
    {
        List<String> segments = new ArrayList<>(address.segments());
        if (segments.isEmpty())
        {
            return SettingsLocation.defaultSettings(copy(schema.getDefaultSettings()), defaultPath(type));
        }
        if ("defaultSettings".equals(segments.get(0))) //$NON-NLS-1$
        {
            segments.remove(0);
            return SettingsLocation.defaultSettings(copy(schema.getDefaultSettings()), segments);
        }
        if (segments.size() >= 3 && "variants".equals(segments.get(0)) //$NON-NLS-1$
            && "settings".equals(segments.get(2))) //$NON-NLS-1$
        {
            int index = findVariant(variants, segments.get(1));
            if (index < 0)
            {
                return SettingsLocation.failure("Settings variant '" + segments.get(1) //$NON-NLS-1$
                    + "' was not found. Existing variants: " + variantNames(variants) //$NON-NLS-1$
                    + ". Copy a variant address from dcs action='get', or upsert the variant first."); //$NON-NLS-1$
            }
            return SettingsLocation.variant(copy(variants.get(index).getSettings()),
                new ArrayList<>(segments.subList(3, segments.size())), index);
        }
        return SettingsLocation.failure("Settings address '" + address //$NON-NLS-1$
            + "' must start with '#/defaultSettings' or '#/variants/<name>/settings'. " //$NON-NLS-1$
            + "Copy an address from dcs action='get'."); //$NON-NLS-1$
    }

    private static String applyVariant(List<SettingsVariant> variants, String pointerName, String action,
        JsonObject body, DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_NAME, KEY_PRESENTATION, "settings"); //$NON-NLS-1$
        if (members != null)
        {
            return members;
        }
        String bodyName = optionalString(body, KEY_NAME, path);
        if (stringError != null)
        {
            return stringError;
        }
        if (pointerName != null && bodyName != null && !pointerName.equals(bodyName))
        {
            return "Variant body name '" + bodyName + "' does not match address name '" //$NON-NLS-1$ //$NON-NLS-2$
                + pointerName + "'. Make 'name' match the pointer, or omit it."; //$NON-NLS-1$
        }
        String name = pointerName != null ? pointerName : bodyName;
        if (name == null || name.isEmpty())
        {
            return "Variant body at '" + path //$NON-NLS-1$
                + "' needs a non-empty 'name'. Add its natural key and retry."; //$NON-NLS-1$
        }
        int index = findVariant(variants, name);
        if (ACTION_UPDATE.equals(action) && index < 0)
        {
            return "action='update' could not find variant '" + name + "'. Existing variants: " //$NON-NLS-1$ //$NON-NLS-2$
                + variantNames(variants) + ". Use action='upsert' to create it."; //$NON-NLS-1$
        }
        SettingsVariant variant = index < 0 ? DcsFactory.eINSTANCE.createSettingsVariant()
            : EcoreUtil.copy(variants.get(index));
        variant.setName(name);
        if (body.has(KEY_PRESENTATION))
        {
            PresentationResult presentation = presentation(body.get(KEY_PRESENTATION), languages,
                path + ".presentation"); //$NON-NLS-1$
            if (presentation.error != null)
            {
                return presentation.error;
            }
            variant.setPresentation(presentation.value);
        }
        if (body.has("settings")) //$NON-NLS-1$
        {
            JsonObject settingsBody = object(body, "settings", path); //$NON-NLS-1$
            if (settingsBody == null)
            {
                return objectError;
            }
            SettingsResult settings = planSettings(variant.getSettings(), Collections.emptyList(),
                action, TYPE_USER_SETTINGS, settingsBody, languages);
            if (!settings.isSuccess())
            {
                return settings.error();
            }
            variant.setSettings(settings.settings());
        }
        else if (variant.getSettings() == null && index < 0)
        {
            variant.setSettings(DcsFactory.eINSTANCE.createDataCompositionSettings());
        }
        if (index < 0)
        {
            variants.add(variant);
        }
        else
        {
            variants.set(index, variant);
        }
        return null;
    }

    private static String applySettingsPath(DataCompositionSettings settings, List<String> path,
        JsonObject body, String action, String type, DcsPresentationParser.LanguageContext languages)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        switch (head)
        {
            case KEY_ITEMS:
                return applyStructurePath(settings.getItems(), tail, body, action, languages,
                    "settings.items"); //$NON-NLS-1$
            case "selection": //$NON-NLS-1$
                return applySelectionPath(settings, tail, body, action, languages);
            case "filter": //$NON-NLS-1$
                return applyFilterPath(settings, tail, body, action, languages);
            case "dataParameters": //$NON-NLS-1$
                return applyParameterPath(settings, tail, body, action, languages, true);
            case "order": //$NON-NLS-1$
                return applyOrderPath(settings, tail, body, action, languages);
            case "conditionalAppearance": //$NON-NLS-1$
                return applyConditionalAppearancePath(settings, tail, body, action, languages);
            case "outputParameters": //$NON-NLS-1$
                return applyParameterPath(settings, tail, body, action, languages, false);
            default:
                return "Settings path segment '" + head + "' is not authorable for type='" //$NON-NLS-1$ //$NON-NLS-2$
                    + type + "'. Use items, selection, filter, dataParameters, order, or " //$NON-NLS-1$
                    + "outputParameters, copying the address from dcs action='get'."; //$NON-NLS-1$
        }
    }

    private static String applySettingsBody(DataCompositionSettings settings, JsonObject body,
        String action, DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS, "selection", "filter", //$NON-NLS-1$ //$NON-NLS-2$
            "dataParameters", "order", "conditionalAppearance", "outputParameters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "itemsViewMode", //$NON-NLS-1$
            "itemsUserSettingID", "itemsUserSettingPresentation"); //$NON-NLS-1$ //$NON-NLS-2$
        if (members != null)
        {
            return members;
        }
        String scaffold = applyItemsScaffold(settings, body, languages, path);
        if (scaffold != null)
        {
            return scaffold;
        }
        if (body.has(KEY_ITEMS))
        {
            JsonArray items = array(body, KEY_ITEMS, path);
            if (items == null)
            {
                return arrayError;
            }
            String error = appendGroupings(settings.getItems(), items, action, languages,
                path + ".items"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
        }
        if (body.has("selection")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "selection", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionSelectedFields holder = copy(settings.getSelection());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionSelectedFields();
            }
            String error = applySelection(holder, value, action, languages, path + ".selection"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setSelection(holder);
        }
        if (body.has("filter")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "filter", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionFilter holder = copy(settings.getFilter());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionFilter();
            }
            String error = applyFilter(holder, value, action, languages, path + ".filter"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setFilter(holder);
        }
        if (body.has("order")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "order", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionOrder holder = copy(settings.getOrder());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionOrder();
            }
            String error = applyOrder(holder, value, action, languages, path + ".order"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setOrder(holder);
        }
        if (body.has("conditionalAppearance")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "conditionalAppearance", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionConditionalAppearance holder = copy(settings.getConditionalAppearance());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            }
            String error = applyConditionalAppearance(holder, value, languages,
                path + ".conditionalAppearance"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setConditionalAppearance(holder);
        }
        if (body.has("dataParameters")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "dataParameters", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionDataParameterValues holder = copy(settings.getDataParameters());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionDataParameterValues();
            }
            String error = applyParameters(holder, value, action, languages,
                path + ".dataParameters"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setDataParameters(holder);
        }
        if (body.has("outputParameters")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "outputParameters", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionOutputParameterValues holder = copy(settings.getOutputParameters());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues();
            }
            String error = applyParameters(holder, value, action, languages,
                path + ".outputParameters"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setOutputParameters(holder);
        }
        return null;
    }

    // ---- structure groups -------------------------------------------------------------------

    private static String applyStructurePath(List<StructureItem> items, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        if (path.isEmpty())
        {
            if (body.has(KEY_ITEMS))
            {
                String members = checkMembers(body, where, KEY_ITEMS);
                if (members != null)
                {
                    return members;
                }
                JsonArray array = array(body, KEY_ITEMS, where);
                return array == null ? arrayError
                    : appendGroupings(items, array, action, languages, where);
            }
            return appendGrouping(items, body, action, languages, where);
        }
        String selector = path.get(0);
        if (!DcsAddress.isZeroBasedIndex(selector))
        {
            return "Structure item selector '" + selector + "' at '" + where //$NON-NLS-1$ //$NON-NLS-2$
                + "' must be a zero-based index. Re-run dcs action='get', copy the indexed " //$NON-NLS-1$
                + "address, and pass its hash as expectedHash."; //$NON-NLS-1$
        }
        int index = findStructure(items, selector);
        if (index < 0)
        {
            return "Structure item index '" + selector + "' was not found at '" + where //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Existing indices: " + structureSelectors(items) //$NON-NLS-1$
                + ". Re-run dcs action='get' and copy the new address."; //$NON-NLS-1$
        }
        StructureItem selected = items.get(index);
        if (!(selected instanceof DataCompositionGroup))
        {
            return "Structure item '" + selector + "' is " + selected.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ", not DataCompositionGroup. Group authoring cannot replace tables, charts, or " //$NON-NLS-1$
                + "nested settings; address a group returned by get."; //$NON-NLS-1$
        }
        DataCompositionGroup group = (DataCompositionGroup)selected;
        if (path.size() == 1)
        {
            return applyGrouping(group, body, action, languages, where + "/" + selector, items); //$NON-NLS-1$
        }
        return applyGroupChildPath(group, path.subList(1, path.size()), body, action, languages,
            where + "/" + selector); //$NON-NLS-1$
    }

    private static String applyGroupChildPath(DataCompositionGroup group, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        switch (head)
        {
            case KEY_ITEMS:
                return applyStructurePath(group.getItems(), tail, body, action, languages,
                    where + "/items"); //$NON-NLS-1$
            case "selection": //$NON-NLS-1$
                return applySelectionPath(new GroupSettingsAccess(group), tail, body, action, languages);
            case "filter": //$NON-NLS-1$
                return applyFilterPath(new GroupSettingsAccess(group), tail, body, action, languages);
            case "order": //$NON-NLS-1$
                return applyOrderPath(new GroupSettingsAccess(group), tail, body, action, languages);
            case "groupFields": //$NON-NLS-1$
                return applyGroupFieldsPath(group, tail, body, action, languages, where);
            case "outputParameters": //$NON-NLS-1$
                return "Group output-parameter node updates are not addressable separately. Update " //$NON-NLS-1$
                    + "the group body and pass outputParameters there."; //$NON-NLS-1$
            default:
                return "Grouping path segment '" + head + "' at '" + where //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is not authorable. Use items, groupFields, selection, filter, or order."; //$NON-NLS-1$
        }
    }

    private static String appendGroupings(List<StructureItem> items, JsonArray array, String action,
        DcsPresentationParser.LanguageContext languages, String where)
    {
        for (int i = 0; i < array.size(); i++)
        {
            JsonObject body = arrayObject(array, i, where);
            if (body == null)
            {
                return arrayObjectError;
            }
            String error = appendGrouping(items, body, action, languages,
                where + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String appendGrouping(List<StructureItem> items, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String where)
    {
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact structure-item index at '" + where //$NON-NLS-1$
                + "'. Copy the grouping address from get; use upsert to append a new grouping."; //$NON-NLS-1$
        }
        DataCompositionGroup group = DcsFactory.eINSTANCE.createDataCompositionGroup();
        items.add(group);
        return applyGrouping(group, body, action, languages, where, items);
    }

    private static String applyGrouping(DataCompositionGroup group, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String path, List<StructureItem> siblings)
    {
        String members = checkMembers(body, path, KEY_NAME, KEY_USE, "groupFields", //$NON-NLS-1$
            "selection", "filter", "order", "outputParameters", KEY_ITEMS, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            KEY_VIEW_MODE, KEY_USER_SETTING_ID, KEY_USER_SETTING_PRESENTATION,
            "itemsViewMode", "itemsUserSettingID", "itemsUserSettingPresentation"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (members != null)
        {
            return members;
        }
        if (body.has(KEY_NAME))
        {
            String name = requiredString(body, KEY_NAME, path);
            if (stringError != null)
            {
                return stringError;
            }
            for (StructureItem sibling : siblings)
            {
                if (sibling != group && sibling instanceof DataCompositionGroup
                    && name.equals(((DataCompositionGroup)sibling).getName()))
                {
                    return "Grouping name '" + name + "' collides with a sibling at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Choose a unique name, or update that sibling's returned address."; //$NON-NLS-1$
                }
            }
            group.setName(name);
        }
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null)
            {
                return booleanError;
            }
            group.setUse(use.booleanValue());
        }
        String scaffold = applyGroupScaffold(group, body, languages, path);
        if (scaffold != null)
        {
            return scaffold;
        }
        if (body.has("groupFields")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "groupFields", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionGroupFields fields = copy(group.getGroupFields());
            if (fields == null)
            {
                fields = DcsFactory.eINSTANCE.createDataCompositionGroupFields();
            }
            String error = applyGroupFields(fields, value, action, path + ".groupFields"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            group.setGroupFields(fields);
        }
        if (body.has("selection")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "selection", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionSelectedFields holder = copy(group.getSelection());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionSelectedFields();
            }
            String error = applySelection(holder, value, action, languages, path + ".selection"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            group.setSelection(holder);
        }
        if (body.has("filter")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "filter", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionFilter holder = copy(group.getFilter());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionFilter();
            }
            String error = applyFilter(holder, value, action, languages, path + ".filter"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            group.setFilter(holder);
        }
        if (body.has("order")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "order", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            DataCompositionOrder holder = copy(group.getOrder());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionOrder();
            }
            String error = applyOrder(holder, value, action, languages, path + ".order"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            group.setOrder(holder);
        }
        if (body.has("outputParameters")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "outputParameters", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupOutputParameterValues holder =
                copy(group.getOutputParameters());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionGroupOutputParameterValues();
            }
            String error = applyParameters(holder, value, action, languages,
                path + ".outputParameters"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            group.setOutputParameters(holder);
        }
        if (body.has(KEY_ITEMS))
        {
            JsonArray items = array(body, KEY_ITEMS, path);
            if (items == null)
            {
                return arrayError;
            }
            return appendGroupings(group.getItems(), items, action, languages, path + ".items"); //$NON-NLS-1$
        }
        return null;
    }

    private static String applyGroupFieldsPath(DataCompositionGroup group, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        DataCompositionGroupFields fields = copy(group.getGroupFields());
        if (fields == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find groupFields at '" + where //$NON-NLS-1$
                    + "'. Use action='upsert' to create them."; //$NON-NLS-1$
            }
            fields = DcsFactory.eINSTANCE.createDataCompositionGroupFields();
        }
        String error;
        if (path.isEmpty())
        {
            error = applyGroupFields(fields, body, action, where + ".groupFields"); //$NON-NLS-1$
        }
        else if (path.size() == 2 && KEY_ITEMS.equals(path.get(0)))
        {
            int index = index(path.get(1), fields.getItems().size(), where + "/groupFields/items"); //$NON-NLS-1$
            if (indexError != null)
            {
                return indexError;
            }
            GroupItem item = fields.getItems().get(index);
            if (!(item instanceof DataCompositionGroupField))
            {
                return "Group field index '" + path.get(1) + "' is " + item.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + ", not DataCompositionGroupField. Choose a field address returned by get."; //$NON-NLS-1$
            }
            error = applyGroupField((DataCompositionGroupField)item, body,
                where + "/groupFields/items/" + path.get(1)); //$NON-NLS-1$
        }
        else
        {
            return "Group-fields address at '" + where //$NON-NLS-1$
                + "' must end at groupFields or groupFields/items/<index>. Copy it from get."; //$NON-NLS-1$
        }
        if (error == null)
        {
            group.setGroupFields(fields);
        }
        return error;
    }

    private static String applyGroupFields(DataCompositionGroupFields fields, JsonObject body,
        String action, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS);
        if (members != null)
        {
            return members;
        }
        if (!body.has(KEY_ITEMS))
        {
            return null;
        }
        JsonArray items = array(body, KEY_ITEMS, path);
        if (items == null)
        {
            return arrayError;
        }
        for (int i = 0; i < items.size(); i++)
        {
            JsonObject item = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
            if (item == null)
            {
                return arrayObjectError;
            }
            DataCompositionGroupField field = DcsFactory.eINSTANCE.createDataCompositionGroupField();
            String error = applyGroupField(field, item, path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
            fields.getItems().add(field);
        }
        return null;
    }

    private static String applyGroupField(DataCompositionGroupField field, JsonObject body, String path)
    {
        String members = checkMembers(body, path, KEY_FIELD, KEY_USE, "groupType", //$NON-NLS-1$
            "periodAdditionType", "periodAdditionBegin", "periodAdditionEnd"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (members != null)
        {
            return members;
        }
        if (body.has(KEY_FIELD))
        {
            FieldResult value = fieldValue(body.get(KEY_FIELD), path + ".field"); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            field.setField(value.value);
        }
        if (body.has(KEY_USE))
        {
            Boolean value = bool(body, KEY_USE, path);
            if (value == null)
            {
                return booleanError;
            }
            field.setUse(value.booleanValue());
        }
        if (body.has("groupType")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionGroupType> value = enumValue(body, "groupType", path, //$NON-NLS-1$
                DataCompositionGroupType.values());
            if (value.error != null)
            {
                return value.error;
            }
            field.setGroupType(value.value);
        }
        if (body.has("periodAdditionType")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionPeriodAdditionType> value = enumValue(body,
                "periodAdditionType", path, DataCompositionPeriodAdditionType.values()); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            field.setPeriodAdditionType(value.value);
        }
        if (body.has("periodAdditionBegin")) //$NON-NLS-1$
        {
            ValueResult value = value(body.get("periodAdditionBegin"), path + ".periodAdditionBegin"); //$NON-NLS-1$ //$NON-NLS-2$
            if (value.error != null)
            {
                return value.error;
            }
            field.setPeriodAdditionBegin(value.value);
        }
        if (body.has("periodAdditionEnd")) //$NON-NLS-1$
        {
            ValueResult value = value(body.get("periodAdditionEnd"), path + ".periodAdditionEnd"); //$NON-NLS-1$ //$NON-NLS-2$
            if (value.error != null)
            {
                return value.error;
            }
            field.setPeriodAdditionEnd(value.value);
        }
        return null;
    }

    // ---- selection --------------------------------------------------------------------------

    private static String applySelectionPath(SettingsAccess owner, List<String> path, JsonObject body,
        String action, DcsPresentationParser.LanguageContext languages)
    {
        DataCompositionSelectedFields holder = copy(owner.selection());
        if (holder == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find selection. Use action='upsert' to create it."; //$NON-NLS-1$
            }
            holder = DcsFactory.eINSTANCE.createDataCompositionSelectedFields();
        }
        String error = applySelectionPath(holder, path, body, action, languages, "selection"); //$NON-NLS-1$
        if (error == null)
        {
            owner.selection(holder);
        }
        return error;
    }

    private static String applySelectionPath(DataCompositionSettings settings, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages)
    {
        return applySelectionPath(new RootSettingsAccess(settings), path, body, action, languages);
    }

    private static String applySelectionPath(DataCompositionSelectedFields holder, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        if (path.isEmpty())
        {
            return applySelection(holder, body, action, languages, where);
        }
        return applySelectedItemsPath(holder.getItems(), path, body, action, languages, where);
    }

    private static String applySelectedItemsPath(List<SelectedItem> items, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        if (!KEY_ITEMS.equals(path.get(0)))
        {
            return "Selection address at '" + where + "' must continue with items/<index>."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (path.size() == 1)
        {
            return appendSelected(items, body, action, languages, where + "/items"); //$NON-NLS-1$
        }
        int selected = index(path.get(1), items.size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null)
        {
            return indexError;
        }
        SelectedItem item = items.get(selected);
        if (path.size() == 2)
        {
            return applySelectedItem(item, body, languages, where + "/items/" + path.get(1)); //$NON-NLS-1$
        }
        if (!(item instanceof DataCompositionSelectedFieldGroup))
        {
            return "Selection item index '" + path.get(1) //$NON-NLS-1$
                + "' is not a group. Copy a nested-group address from get."; //$NON-NLS-1$
        }
        return applySelectedItemsPath(((DataCompositionSelectedFieldGroup)item).getItems(),
            path.subList(2, path.size()), body, action, languages,
            where + "/items/" + path.get(1)); //$NON-NLS-1$
    }

    private static String applySelection(DataCompositionSelectedFields selection, JsonObject body,
        String action, DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS, KEY_VIEW_MODE, KEY_USER_SETTING_ID,
            KEY_USER_SETTING_PRESENTATION);
        if (members != null)
        {
            return members;
        }
        String scaffold = applyHolderScaffold(selection, body, languages, path);
        if (scaffold != null || !body.has(KEY_ITEMS))
        {
            return scaffold;
        }
        JsonArray items = array(body, KEY_ITEMS, path);
        if (items == null)
        {
            return arrayError;
        }
        for (int i = 0; i < items.size(); i++)
        {
            JsonObject item = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
            if (item == null)
            {
                return arrayObjectError;
            }
            String error = appendSelected(selection.getItems(), item, action, languages,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String appendSelected(List<SelectedItem> items, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact selection item index at '" + path //$NON-NLS-1$
                + "'. Copy the item address from get; use upsert to append a new item."; //$NON-NLS-1$
        }
        String kind = optionalString(body, KEY_KIND, path);
        if (stringError != null)
        {
            return stringError;
        }
        SelectedItem item;
        if ("group".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            item = DcsFactory.eINSTANCE.createDataCompositionSelectedFieldGroup();
        }
        else if ("auto".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            item = DcsFactory.eINSTANCE.createDataCompositionAutoSelectedField();
        }
        else if (kind == null || "field".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            item = DcsFactory.eINSTANCE.createDataCompositionSelectedField();
        }
        else
        {
            return "Selection kind '" + kind //$NON-NLS-1$
                + "' is invalid. Use one of: field, group, auto."; //$NON-NLS-1$
        }
        String error = applySelectedItem(item, body, languages, path);
        if (error == null)
        {
            items.add(item);
        }
        return error;
    }

    private static String applySelectedItem(SelectedItem item, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (item instanceof DataCompositionAutoSelectedField)
        {
            String members = checkMembers(body, path, KEY_KIND, KEY_USE);
            if (members != null)
            {
                return members;
            }
            String mismatch = kindMustBe(body, path, "auto"); //$NON-NLS-1$
            if (mismatch != null)
            {
                return mismatch;
            }
            if (body.has(KEY_USE))
            {
                Boolean use = bool(body, KEY_USE, path);
                if (use == null)
                {
                    return booleanError;
                }
                ((DataCompositionAutoSelectedField)item).setUse(use.booleanValue());
            }
            return null;
        }
        boolean group = item instanceof DataCompositionSelectedFieldGroup;
        String members = group
            ? checkMembers(body, path, KEY_KIND, KEY_FIELD, KEY_TITLE, KEY_USE, KEY_ITEMS,
                "placement", KEY_VIEW_MODE) //$NON-NLS-1$
            : checkMembers(body, path, KEY_KIND, KEY_FIELD, KEY_TITLE, KEY_USE, KEY_VIEW_MODE);
        if (members != null)
        {
            return members;
        }
        String mismatch = kindMustBe(body, path, group ? "group" : "field"); //$NON-NLS-1$ //$NON-NLS-2$
        if (mismatch != null)
        {
            return mismatch;
        }
        DataCompositionSelectedField selected = group ? null : (DataCompositionSelectedField)item;
        DataCompositionSelectedFieldGroup selectedGroup = group
            ? (DataCompositionSelectedFieldGroup)item : null;
        if (body.has(KEY_FIELD))
        {
            FieldResult field = fieldValue(body.get(KEY_FIELD), path + ".field"); //$NON-NLS-1$
            if (field.error != null)
            {
                return field.error;
            }
            if (group)
            {
                selectedGroup.setField(field.value);
            }
            else
            {
                selected.setField(field.value);
            }
        }
        if (body.has(KEY_TITLE))
        {
            PresentationResult title = presentation(body.get(KEY_TITLE), languages, path + ".title"); //$NON-NLS-1$
            if (title.error != null)
            {
                return title.error;
            }
            if (group)
            {
                selectedGroup.setTitle(title.value);
            }
            else
            {
                selected.setTitle(title.value);
            }
        }
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null)
            {
                return booleanError;
            }
            if (group)
            {
                selectedGroup.setUse(use.booleanValue());
            }
            else
            {
                selected.setUse(use.booleanValue());
            }
        }
        if (body.has(KEY_VIEW_MODE))
        {
            EnumResult<DataCompositionSettingsItemViewMode> view = enumValue(body, KEY_VIEW_MODE,
                path, DataCompositionSettingsItemViewMode.values());
            if (view.error != null)
            {
                return view.error;
            }
            if (group)
            {
                selectedGroup.setViewMode(view.value);
            }
            else
            {
                selected.setViewMode(view.value);
            }
        }
        if (group)
        {
            if (body.has("placement")) //$NON-NLS-1$
            {
                EnumResult<DataCompositionFieldPlacement> placement = enumValue(body, "placement", //$NON-NLS-1$
                    path, DataCompositionFieldPlacement.values());
                if (placement.error != null)
                {
                    return placement.error;
                }
                selectedGroup.setPlacement(placement.value);
            }
            if (body.has(KEY_ITEMS))
            {
                JsonArray items = array(body, KEY_ITEMS, path);
                if (items == null)
                {
                    return arrayError;
                }
                for (int i = 0; i < items.size(); i++)
                {
                    JsonObject child = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
                    if (child == null)
                    {
                        return arrayObjectError;
                    }
                    String error = appendSelected(selectedGroup.getItems(), child, ACTION_UPSERT,
                        languages, path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (error != null)
                    {
                        return error;
                    }
                }
            }
        }
        return null;
    }

    // ---- filter -----------------------------------------------------------------------------

    private static String applyFilterPath(SettingsAccess owner, List<String> path, JsonObject body,
        String action, DcsPresentationParser.LanguageContext languages)
    {
        DataCompositionFilter holder = copy(owner.filter());
        if (holder == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find filter. Use action='upsert' to create it."; //$NON-NLS-1$
            }
            holder = DcsFactory.eINSTANCE.createDataCompositionFilter();
        }
        String error = applyFilterPath(holder, path, body, action, languages, "filter"); //$NON-NLS-1$
        if (error == null)
        {
            owner.filter(holder);
        }
        return error;
    }

    private static String applyFilterPath(DataCompositionSettings settings, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages)
    {
        return applyFilterPath(new RootSettingsAccess(settings), path, body, action, languages);
    }

    private static String applyFilterPath(DataCompositionFilter holder, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        if (path.isEmpty())
        {
            return applyFilter(holder, body, action, languages, where);
        }
        return applyFilterItemsPath(holder.getItems(), path, body, action, languages, where);
    }

    private static String applyFilterItemsPath(List<FilterItem> items, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        if (!KEY_ITEMS.equals(path.get(0)))
        {
            return "Filter address at '" + where + "' must continue with items/<index>."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (path.size() == 1)
        {
            return appendFilter(items, body, action, languages, where + "/items"); //$NON-NLS-1$
        }
        int selected = index(path.get(1), items.size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null)
        {
            return indexError;
        }
        FilterItem item = items.get(selected);
        if (path.size() == 2)
        {
            return applyFilterItem(item, body, languages, where + "/items/" + path.get(1)); //$NON-NLS-1$
        }
        if (!(item instanceof DataCompositionFilterItemGroup))
        {
            return "Filter item index '" + path.get(1) //$NON-NLS-1$
                + "' is not a group. Copy a nested-group address from get."; //$NON-NLS-1$
        }
        return applyFilterItemsPath(((DataCompositionFilterItemGroup)item).getItems(),
            path.subList(2, path.size()), body, action, languages,
            where + "/items/" + path.get(1)); //$NON-NLS-1$
    }

    private static String applyFilter(DataCompositionFilter filter, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS, KEY_VIEW_MODE, KEY_USER_SETTING_ID,
            KEY_USER_SETTING_PRESENTATION);
        if (members != null)
        {
            return members;
        }
        String scaffold = applyHolderScaffold(filter, body, languages, path);
        if (scaffold != null || !body.has(KEY_ITEMS))
        {
            return scaffold;
        }
        JsonArray items = array(body, KEY_ITEMS, path);
        if (items == null)
        {
            return arrayError;
        }
        for (int i = 0; i < items.size(); i++)
        {
            JsonObject item = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
            if (item == null)
            {
                return arrayObjectError;
            }
            String error = appendFilter(filter.getItems(), item, action, languages,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String appendFilter(List<FilterItem> items, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact filter item index at '" + path //$NON-NLS-1$
                + "'. Copy the item address from get; use upsert to append a new item."; //$NON-NLS-1$
        }
        String kind = optionalString(body, KEY_KIND, path);
        if (stringError != null)
        {
            return stringError;
        }
        FilterItem item;
        if ("group".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            item = DcsFactory.eINSTANCE.createDataCompositionFilterItemGroup();
        }
        else if (kind == null || "item".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            item = DcsFactory.eINSTANCE.createDataCompositionFilterItem();
        }
        else
        {
            return "Filter kind '" + kind + "' is invalid. Use one of: item, group."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String error = applyFilterItem(item, body, languages, path);
        if (error == null)
        {
            items.add(item);
        }
        return error;
    }

    private static String applyFilterItem(FilterItem item, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        boolean group = item instanceof DataCompositionFilterItemGroup;
        String members = group
            ? checkMembers(body, path, KEY_KIND, "groupType", KEY_USE, KEY_ITEMS, //$NON-NLS-1$
                KEY_PRESENTATION, "application", KEY_VIEW_MODE, KEY_USER_SETTING_ID, //$NON-NLS-1$
                KEY_USER_SETTING_PRESENTATION)
            : checkMembers(body, path, KEY_KIND, "left", "comparisonType", "right", KEY_USE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                KEY_PRESENTATION, "application", KEY_VIEW_MODE, KEY_USER_SETTING_ID, //$NON-NLS-1$
                KEY_USER_SETTING_PRESENTATION);
        if (members != null)
        {
            return members;
        }
        String mismatch = kindMustBe(body, path, group ? "group" : "item"); //$NON-NLS-1$ //$NON-NLS-2$
        if (mismatch != null)
        {
            return mismatch;
        }
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null)
            {
                return booleanError;
            }
            if (group)
            {
                ((DataCompositionFilterItemGroup)item).setUse(use.booleanValue());
            }
            else
            {
                ((DataCompositionFilterItem)item).setUse(use.booleanValue());
            }
        }
        String scaffold = applyFilterItemScaffold(item, body, languages, path);
        if (scaffold != null)
        {
            return scaffold;
        }
        if (group)
        {
            DataCompositionFilterItemGroup filterGroup = (DataCompositionFilterItemGroup)item;
            if (body.has("groupType")) //$NON-NLS-1$
            {
                EnumResult<DataCompositionFilterItemsGroupType> value = enumValue(body, "groupType", //$NON-NLS-1$
                    path, DataCompositionFilterItemsGroupType.values());
                if (value.error != null)
                {
                    return value.error;
                }
                filterGroup.setGroupType(value.value);
            }
            if (body.has(KEY_ITEMS))
            {
                JsonArray children = array(body, KEY_ITEMS, path);
                if (children == null)
                {
                    return arrayError;
                }
                for (int i = 0; i < children.size(); i++)
                {
                    JsonObject child = arrayObject(children, i, path + ".items"); //$NON-NLS-1$
                    if (child == null)
                    {
                        return arrayObjectError;
                    }
                    String error = appendFilter(filterGroup.getItems(), child, ACTION_UPSERT,
                        languages, path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (error != null)
                    {
                        return error;
                    }
                }
            }
            return null;
        }
        DataCompositionFilterItem filterItem = (DataCompositionFilterItem)item;
        if (body.has("left")) //$NON-NLS-1$
        {
            ValueResult left = value(body.get("left"), path + ".left"); //$NON-NLS-1$ //$NON-NLS-2$
            if (left.error != null)
            {
                return left.error;
            }
            filterItem.setLeft(left.value);
        }
        if (body.has("comparisonType")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionComparisonType> comparison = enumValue(body,
                "comparisonType", path, DataCompositionComparisonType.values()); //$NON-NLS-1$
            if (comparison.error != null)
            {
                return comparison.error;
            }
            filterItem.setComparisonType(comparison.value);
        }
        if (body.has("right")) //$NON-NLS-1$
        {
            JsonArray right = array(body, "right", path); //$NON-NLS-1$
            if (right == null)
            {
                return arrayError;
            }
            List<Value> values = new ArrayList<>();
            for (int i = 0; i < right.size(); i++)
            {
                ValueResult value = value(right.get(i), path + ".right[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                if (value.error != null)
                {
                    return value.error;
                }
                values.add(value.value);
            }
            filterItem.getRight().clear();
            filterItem.getRight().addAll(values);
        }
        return null;
    }

    // ---- order ------------------------------------------------------------------------------

    private static String applyOrderPath(SettingsAccess owner, List<String> path, JsonObject body,
        String action, DcsPresentationParser.LanguageContext languages)
    {
        DataCompositionOrder holder = copy(owner.order());
        if (holder == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find order. Use action='upsert' to create it."; //$NON-NLS-1$
            }
            holder = DcsFactory.eINSTANCE.createDataCompositionOrder();
        }
        String error = applyOrderPath(holder, path, body, action, languages, "order"); //$NON-NLS-1$
        if (error == null)
        {
            owner.order(holder);
        }
        return error;
    }

    private static String applyOrderPath(DataCompositionSettings settings, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages)
    {
        return applyOrderPath(new RootSettingsAccess(settings), path, body, action, languages);
    }

    private static String applyOrderPath(DataCompositionOrder holder, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        if (path.isEmpty())
        {
            return applyOrder(holder, body, action, languages, where);
        }
        if (!KEY_ITEMS.equals(path.get(0)))
        {
            return "Order address at '" + where + "' must continue with items/<index>."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (path.size() == 1)
        {
            return appendOrder(holder.getItems(), body, action, where + "/items"); //$NON-NLS-1$
        }
        if (path.size() != 2)
        {
            return "Order item address at '" + where + "' has extra segments. Copy it from get."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        int selected = index(path.get(1), holder.getItems().size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null)
        {
            return indexError;
        }
        return applyOrderItem(holder.getItems().get(selected), body, where + "/items/" + path.get(1)); //$NON-NLS-1$
    }

    private static String applyOrder(DataCompositionOrder order, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS, KEY_VIEW_MODE, KEY_USER_SETTING_ID,
            KEY_USER_SETTING_PRESENTATION);
        if (members != null)
        {
            return members;
        }
        String scaffold = applyHolderScaffold(order, body, languages, path);
        if (scaffold != null || !body.has(KEY_ITEMS))
        {
            return scaffold;
        }
        JsonArray items = array(body, KEY_ITEMS, path);
        if (items == null)
        {
            return arrayError;
        }
        for (int i = 0; i < items.size(); i++)
        {
            JsonObject item = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
            if (item == null)
            {
                return arrayObjectError;
            }
            String error = appendOrder(order.getItems(), item, action,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String appendOrder(List<OrderItem> items, JsonObject body, String action, String path)
    {
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact order item index at '" + path //$NON-NLS-1$
                + "'. Copy the item address from get; use upsert to append a new item."; //$NON-NLS-1$
        }
        String kind = optionalString(body, KEY_KIND, path);
        if (stringError != null)
        {
            return stringError;
        }
        OrderItem item;
        if ("auto".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            item = DcsFactory.eINSTANCE.createDataCompositionAutoOrderItem();
        }
        else if (kind == null || "item".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            item = DcsFactory.eINSTANCE.createDataCompositionOrderItem();
        }
        else
        {
            return "Order kind '" + kind + "' is invalid. Use one of: item, auto."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String error = applyOrderItem(item, body, path);
        if (error == null)
        {
            items.add(item);
        }
        return error;
    }

    private static String applyOrderItem(OrderItem item, JsonObject body, String path)
    {
        if (item instanceof DataCompositionAutoOrderItem)
        {
            String members = checkMembers(body, path, KEY_KIND, KEY_USE);
            if (members != null)
            {
                return members;
            }
            String mismatch = kindMustBe(body, path, "auto"); //$NON-NLS-1$
            if (mismatch != null)
            {
                return mismatch;
            }
            if (body.has(KEY_USE))
            {
                Boolean use = bool(body, KEY_USE, path);
                if (use == null)
                {
                    return booleanError;
                }
                ((DataCompositionAutoOrderItem)item).setUse(use.booleanValue());
            }
            return null;
        }
        String members = checkMembers(body, path, KEY_KIND, KEY_FIELD, "orderType", KEY_USE, //$NON-NLS-1$
            KEY_VIEW_MODE);
        if (members != null)
        {
            return members;
        }
        String mismatch = kindMustBe(body, path, "item"); //$NON-NLS-1$
        if (mismatch != null)
        {
            return mismatch;
        }
        DataCompositionOrderItem order = (DataCompositionOrderItem)item;
        if (body.has(KEY_FIELD))
        {
            FieldResult field = fieldValue(body.get(KEY_FIELD), path + ".field"); //$NON-NLS-1$
            if (field.error != null)
            {
                return field.error;
            }
            order.setField(field.value);
        }
        if (body.has("orderType")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionSortDirection> direction = enumValue(body, "orderType", //$NON-NLS-1$
                path, DataCompositionSortDirection.values());
            if (direction.error != null)
            {
                return direction.error;
            }
            order.setOrderType(direction.value);
        }
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null)
            {
                return booleanError;
            }
            order.setUse(use.booleanValue());
        }
        if (body.has(KEY_VIEW_MODE))
        {
            EnumResult<DataCompositionSettingsItemViewMode> view = enumValue(body, KEY_VIEW_MODE,
                path, DataCompositionSettingsItemViewMode.values());
            if (view.error != null)
            {
                return view.error;
            }
            order.setViewMode(view.value);
        }
        return null;
    }

    // ---- data/output parameters --------------------------------------------------------------

    private static String applyConditionalAppearancePath(DataCompositionSettings settings,
        List<String> path, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages)
    {
        if (!path.isEmpty())
        {
            return "Conditional-appearance rules are read-only. Address the holder itself and " //$NON-NLS-1$
                + "set only its empty items/scaffolding body."; //$NON-NLS-1$
        }
        DataCompositionConditionalAppearance holder = copy(settings.getConditionalAppearance());
        if (holder == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find conditionalAppearance. Use action='upsert' " //$NON-NLS-1$
                    + "to create its empty holder scaffolding."; //$NON-NLS-1$
            }
            holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        }
        String error = applyConditionalAppearance(holder, body, languages,
            "conditionalAppearance"); //$NON-NLS-1$
        if (error == null)
        {
            settings.setConditionalAppearance(holder);
        }
        return error;
    }

    private static String applyConditionalAppearance(DataCompositionConditionalAppearance holder,
        JsonObject body, DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS, KEY_VIEW_MODE, KEY_USER_SETTING_ID,
            KEY_USER_SETTING_PRESENTATION);
        if (members != null)
        {
            return members;
        }
        if (body.has(KEY_ITEMS))
        {
            JsonArray items = array(body, KEY_ITEMS, path);
            if (items == null)
            {
                return arrayError;
            }
            if (!items.isEmpty())
            {
                return "Member '" + path + ".items' contains " + items.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + " rule(s), but conditional-appearance rule authoring is not implemented. " //$NON-NLS-1$
                    + "Pass items:[] to materialize only the holder scaffolding, or omit the member " //$NON-NLS-1$
                    + "to preserve existing rules."; //$NON-NLS-1$
            }
        }
        return applyHolderScaffold(holder, body, languages, path);
    }

    private static String applyParameterPath(DataCompositionSettings settings, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages,
        boolean dataParameters)
    {
        ParameterValues holder = dataParameters ? copy(settings.getDataParameters())
            : copy(settings.getOutputParameters());
        if (holder == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find " + (dataParameters ? "dataParameters" //$NON-NLS-1$ //$NON-NLS-2$
                    : "outputParameters") + ". Use action='upsert' to create it."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            holder = dataParameters ? DcsFactory.eINSTANCE.createDataCompositionDataParameterValues()
                : DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues();
        }
        String where = dataParameters ? "dataParameters" : "outputParameters"; //$NON-NLS-1$ //$NON-NLS-2$
        String error;
        if (path.isEmpty())
        {
            error = applyParameters(holder, body, action, languages, where);
        }
        else if (path.size() == 2 && KEY_ITEMS.equals(path.get(0)))
        {
            int selected = index(path.get(1), holder.getItems().size(), where + "/items"); //$NON-NLS-1$
            if (indexError != null)
            {
                return indexError;
            }
            DataCompositionParameterValue item = holder.getItems().get(selected);
            if (!(item instanceof SettingsParameterValue))
            {
                return "Parameter item index '" + path.get(1) + "' is " //$NON-NLS-1$ //$NON-NLS-2$
                    + item.eClass().getName() + ", not SettingsParameterValue. Choose an address from get."; //$NON-NLS-1$
            }
            error = applyParameterItem((SettingsParameterValue)item, body, languages,
                where + "/items/" + path.get(1)); //$NON-NLS-1$
        }
        else
        {
            return "Parameter-settings address at '" + where //$NON-NLS-1$
                + "' must end at the holder or items/<index>. Copy it from get."; //$NON-NLS-1$
        }
        if (error == null)
        {
            if (dataParameters)
            {
                settings.setDataParameters((DataCompositionDataParameterValues)holder);
            }
            else
            {
                settings.setOutputParameters((DataCompositionOutputParameterValues)holder);
            }
        }
        return error;
    }

    private static String applyParameters(ParameterValues holder, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS);
        if (members != null)
        {
            return members;
        }
        if (!body.has(KEY_ITEMS))
        {
            return null;
        }
        JsonArray items = array(body, KEY_ITEMS, path);
        if (items == null)
        {
            return arrayError;
        }
        if (ACTION_UPDATE.equals(action) && !items.isEmpty())
        {
            return "action='update' needs an exact parameter item index at '" + path //$NON-NLS-1$
                + "'. Copy it from get; use upsert to append an item."; //$NON-NLS-1$
        }
        for (int i = 0; i < items.size(); i++)
        {
            JsonObject bodyItem = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
            if (bodyItem == null)
            {
                return arrayObjectError;
            }
            SettingsParameterValue item = DcsFactory.eINSTANCE.createSettingsParameterValue();
            String error = applyParameterItem(item, bodyItem, languages,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
            holder.getItems().add(item);
        }
        return null;
    }

    private static String applyParameterItem(SettingsParameterValue item, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, "parameter", "value", KEY_USE, //$NON-NLS-1$ //$NON-NLS-2$
            KEY_VIEW_MODE, KEY_USER_SETTING_ID, KEY_USER_SETTING_PRESENTATION);
        if (members != null)
        {
            return members;
        }
        if (body.has("parameter")) //$NON-NLS-1$
        {
            ValueResult parameter = value(body.get("parameter"), path + ".parameter"); //$NON-NLS-1$ //$NON-NLS-2$
            if (parameter.error != null)
            {
                return parameter.error;
            }
            if (!(parameter.value instanceof DataCompositionParameter))
            {
                return "Value at '" + path //$NON-NLS-1$
                    + ".parameter' must use kind='parameter'. Change its kind and retry."; //$NON-NLS-1$
            }
            item.setParameter((DataCompositionParameter)parameter.value);
        }
        if (body.has("value")) //$NON-NLS-1$
        {
            ValueResult value = value(body.get("value"), path + ".value"); //$NON-NLS-1$ //$NON-NLS-2$
            if (value.error != null)
            {
                return value.error;
            }
            item.getValues().clear();
            item.getValues().add(value.value);
        }
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null)
            {
                return booleanError;
            }
            item.setUse(use.booleanValue());
        }
        String scaffold = applyParameterScaffold(item, body, languages, path);
        return scaffold;
    }

    // ---- scaffolding ------------------------------------------------------------------------

    private static String applyItemsScaffold(DataCompositionSettings settings, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (body.has("itemsViewMode")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, "itemsViewMode", //$NON-NLS-1$
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null)
            {
                return value.error;
            }
            settings.setItemsViewMode(value.value);
        }
        if (body.has("itemsUserSettingID")) //$NON-NLS-1$
        {
            String value = optionalString(body, "itemsUserSettingID", path); //$NON-NLS-1$
            if (stringError != null)
            {
                return stringError;
            }
            settings.setItemsUserSettingID(value);
        }
        if (body.has("itemsUserSettingPresentation")) //$NON-NLS-1$
        {
            PresentationResult value = presentation(body.get("itemsUserSettingPresentation"), //$NON-NLS-1$
                languages, path + ".itemsUserSettingPresentation"); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            settings.setItemsUserSettingPresentation(value.value);
        }
        return null;
    }

    private static String applyHolderScaffold(Object holder, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        EnumResult<DataCompositionSettingsItemViewMode> view = null;
        if (body.has(KEY_VIEW_MODE))
        {
            view = enumValue(body, KEY_VIEW_MODE, path,
                DataCompositionSettingsItemViewMode.values());
            if (view.error != null)
            {
                return view.error;
            }
        }
        String id = null;
        if (body.has(KEY_USER_SETTING_ID))
        {
            id = optionalString(body, KEY_USER_SETTING_ID, path);
            if (stringError != null)
            {
                return stringError;
            }
        }
        Presentation presentation = null;
        if (body.has(KEY_USER_SETTING_PRESENTATION))
        {
            PresentationResult parsed = presentation(body.get(KEY_USER_SETTING_PRESENTATION),
                languages, path + ".userSettingPresentation"); //$NON-NLS-1$
            if (parsed.error != null)
            {
                return parsed.error;
            }
            presentation = parsed.value;
        }
        if (holder instanceof DataCompositionSelectedFields)
        {
            DataCompositionSelectedFields value = (DataCompositionSelectedFields)holder;
            if (view != null) value.setViewMode(view.value);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(presentation);
        }
        else if (holder instanceof DataCompositionFilter)
        {
            DataCompositionFilter value = (DataCompositionFilter)holder;
            if (view != null) value.setViewMode(view.value);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(presentation);
        }
        else if (holder instanceof DataCompositionOrder)
        {
            DataCompositionOrder value = (DataCompositionOrder)holder;
            if (view != null) value.setViewMode(view.value);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(presentation);
        }
        else if (holder instanceof DataCompositionConditionalAppearance)
        {
            DataCompositionConditionalAppearance value =
                (DataCompositionConditionalAppearance)holder;
            if (view != null) value.setViewMode(view.value);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(presentation);
        }
        return null;
    }

    private static String applyGroupScaffold(DataCompositionGroup group, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        String holder = applyGroupHolderScaffold(group, body, languages, path);
        if (holder != null)
        {
            return holder;
        }
        if (body.has("itemsViewMode")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, "itemsViewMode", //$NON-NLS-1$
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null)
            {
                return value.error;
            }
            group.setItemsViewMode(value.value);
        }
        if (body.has("itemsUserSettingID")) //$NON-NLS-1$
        {
            String value = optionalString(body, "itemsUserSettingID", path); //$NON-NLS-1$
            if (stringError != null)
            {
                return stringError;
            }
            group.setItemsUserSettingID(value);
        }
        if (body.has("itemsUserSettingPresentation")) //$NON-NLS-1$
        {
            PresentationResult value = presentation(body.get("itemsUserSettingPresentation"), //$NON-NLS-1$
                languages, path + ".itemsUserSettingPresentation"); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            group.setItemsUserSettingPresentation(value.value);
        }
        return null;
    }

    private static String applyGroupHolderScaffold(DataCompositionGroup group, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (body.has(KEY_VIEW_MODE))
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, KEY_VIEW_MODE,
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null)
            {
                return value.error;
            }
            group.setViewMode(value.value);
        }
        if (body.has(KEY_USER_SETTING_ID))
        {
            String value = optionalString(body, KEY_USER_SETTING_ID, path);
            if (stringError != null)
            {
                return stringError;
            }
            group.setUserSettingID(value);
        }
        if (body.has(KEY_USER_SETTING_PRESENTATION))
        {
            PresentationResult value = presentation(body.get(KEY_USER_SETTING_PRESENTATION), languages,
                path + ".userSettingPresentation"); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            group.setUserSettingPresentation(value.value);
        }
        return null;
    }

    private static String applyFilterItemScaffold(FilterItem item, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        DataCompositionSettingsItemViewMode view = null;
        if (body.has(KEY_VIEW_MODE))
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, KEY_VIEW_MODE,
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null)
            {
                return value.error;
            }
            view = value.value;
        }
        String id = null;
        if (body.has(KEY_USER_SETTING_ID))
        {
            id = optionalString(body, KEY_USER_SETTING_ID, path);
            if (stringError != null)
            {
                return stringError;
            }
        }
        Presentation userPresentation = null;
        if (body.has(KEY_USER_SETTING_PRESENTATION))
        {
            PresentationResult value = presentation(body.get(KEY_USER_SETTING_PRESENTATION), languages,
                path + ".userSettingPresentation"); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            userPresentation = value.value;
        }
        Presentation itemPresentation = null;
        if (body.has(KEY_PRESENTATION))
        {
            PresentationResult value = presentation(body.get(KEY_PRESENTATION), languages,
                path + ".presentation"); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            itemPresentation = value.value;
        }
        DataCompositionFilterApplicationType application = null;
        if (body.has("application")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionFilterApplicationType> value = enumValue(body, "application", //$NON-NLS-1$
                path, DataCompositionFilterApplicationType.values());
            if (value.error != null)
            {
                return value.error;
            }
            application = value.value;
        }
        if (item instanceof DataCompositionFilterItem)
        {
            DataCompositionFilterItem value = (DataCompositionFilterItem)item;
            if (view != null) value.setViewMode(view);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(userPresentation);
            if (body.has(KEY_PRESENTATION)) value.setPresentation(itemPresentation);
            if (application != null) value.setApplication(application);
        }
        else
        {
            DataCompositionFilterItemGroup value = (DataCompositionFilterItemGroup)item;
            if (view != null) value.setViewMode(view);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(userPresentation);
            if (body.has(KEY_PRESENTATION)) value.setPresentation(itemPresentation);
            if (application != null) value.setApplication(application);
        }
        return null;
    }

    private static String applyParameterScaffold(SettingsParameterValue item, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (body.has(KEY_VIEW_MODE))
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, KEY_VIEW_MODE,
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null)
            {
                return value.error;
            }
            item.setViewMode(value.value);
        }
        if (body.has(KEY_USER_SETTING_ID))
        {
            String value = optionalString(body, KEY_USER_SETTING_ID, path);
            if (stringError != null)
            {
                return stringError;
            }
            item.setUserSettingID(value);
        }
        if (body.has(KEY_USER_SETTING_PRESENTATION))
        {
            PresentationResult value = presentation(body.get(KEY_USER_SETTING_PRESENTATION), languages,
                path + ".userSettingPresentation"); //$NON-NLS-1$
            if (value.error != null)
            {
                return value.error;
            }
            item.setUserSettingPresentation(value.value);
        }
        return null;
    }

    // ---- values / enums ---------------------------------------------------------------------

    private static FieldResult fieldValue(JsonElement element, String path)
    {
        ValueResult result = value(element, path);
        if (result.error != null)
        {
            return FieldResult.failure(result.error);
        }
        if (!(result.value instanceof DataCompositionField))
        {
            return FieldResult.failure("Value at '" + path //$NON-NLS-1$
                + "' must use kind='field'. Change its kind and pass the field path in 'value'."); //$NON-NLS-1$
        }
        return FieldResult.success((DataCompositionField)result.value);
    }

    private static ValueResult value(JsonElement element, String path)
    {
        if (element == null || !element.isJsonObject())
        {
            return ValueResult.failure("ValueSpec at '" + path //$NON-NLS-1$
                + "' must be an object with 'kind' and 'value'."); //$NON-NLS-1$
        }
        JsonObject object = element.getAsJsonObject();
        String members = checkMembers(object, path, KEY_KIND, "value"); //$NON-NLS-1$
        if (members != null)
        {
            return ValueResult.failure(members);
        }
        String kind = requiredString(object, KEY_KIND, path);
        if (stringError != null)
        {
            return ValueResult.failure(stringError);
        }
        JsonElement raw = object.get("value"); //$NON-NLS-1$
        try
        {
            switch (kind)
            {
                case "field": //$NON-NLS-1$
                    DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                        .createDataCompositionField();
                    field.setValue(requiredPrimitiveString(raw, path));
                    return primitiveStringError == null ? ValueResult.success(field)
                        : ValueResult.failure(primitiveStringError);
                case "parameter": //$NON-NLS-1$
                    DataCompositionParameter parameter = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                        .createDataCompositionParameter();
                    parameter.setValue(requiredPrimitiveString(raw, path));
                    return primitiveStringError == null ? ValueResult.success(parameter)
                        : ValueResult.failure(primitiveStringError);
                case "expression": //$NON-NLS-1$
                    String expression = requiredPrimitiveString(raw, path);
                    if (primitiveStringError != null)
                    {
                        return ValueResult.failure(primitiveStringError);
                    }
                    com._1c.g5.v8.dt.dcs.model.core.DesignTimeValue design =
                        com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDesignTimeValue();
                    design.setValue(expression);
                    com._1c.g5.v8.dt.dcs.model.core.DesignTimeValueValue designValue =
                        com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDesignTimeValueValue();
                    designValue.setValue(design);
                    return ValueResult.success(designValue);
                case "string": //$NON-NLS-1$
                    String string = primitiveString(raw, path, true);
                    if (primitiveStringError != null)
                    {
                        return ValueResult.failure(primitiveStringError);
                    }
                    StringValue stringValue = McoreFactory.eINSTANCE.createStringValue();
                    stringValue.setValue(string);
                    return ValueResult.success(stringValue);
                case "number": //$NON-NLS-1$
                    if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber())
                    {
                        return ValueResult.failure("Number ValueSpec at '" + path //$NON-NLS-1$
                            + "' needs a JSON number in 'value'."); //$NON-NLS-1$
                    }
                    NumberValue number = McoreFactory.eINSTANCE.createNumberValue();
                    number.setValue(raw.getAsBigDecimal());
                    return ValueResult.success(number);
                case "boolean": //$NON-NLS-1$
                    if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean())
                    {
                        return ValueResult.failure("Boolean ValueSpec at '" + path //$NON-NLS-1$
                            + "' needs true or false in 'value'."); //$NON-NLS-1$
                    }
                    BooleanValue bool = McoreFactory.eINSTANCE.createBooleanValue();
                    bool.setValue(raw.getAsBoolean());
                    return ValueResult.success(bool);
                case "date": //$NON-NLS-1$
                    String date = requiredPrimitiveString(raw, path);
                    if (primitiveStringError != null)
                    {
                        return ValueResult.failure(primitiveStringError);
                    }
                    DateValue dateValue = McoreFactory.eINSTANCE.createDateValue();
                    dateValue.setValue(com._1c.g5.v8.dt.mcore.util.Date.fromString(date));
                    return ValueResult.success(dateValue);
                case "null": //$NON-NLS-1$
                    if (raw != null && !raw.isJsonNull())
                    {
                        return ValueResult.failure("Null ValueSpec at '" + path //$NON-NLS-1$
                            + "' must omit 'value' or set it to null."); //$NON-NLS-1$
                    }
                    NullValue nullValue = McoreFactory.eINSTANCE.createNullValue();
                    return ValueResult.success(nullValue);
                default:
                    return ValueResult.failure("ValueSpec kind '" + kind + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                        + "' is invalid. Use one of: field, parameter, expression, string, number, " //$NON-NLS-1$
                        + "boolean, date, null."); //$NON-NLS-1$
            }
        }
        catch (IllegalArgumentException e)
        {
            return ValueResult.failure("Date ValueSpec value '" + raw + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' is invalid. Pass the platform date literal accepted by mcore Date.fromString."); //$NON-NLS-1$
        }
    }

    private static <T extends Enum<T> & Enumerator> EnumResult<T> enumValue(JsonObject body,
        String member, String path, T[] values)
    {
        String raw = optionalString(body, member, path);
        if (stringError != null)
        {
            return EnumResult.failure(stringError);
        }
        for (T value : values)
        {
            if (value.getLiteral().equalsIgnoreCase(raw) || value.getName().equalsIgnoreCase(raw)
                || value.name().equalsIgnoreCase(raw))
            {
                return EnumResult.success(value);
            }
        }
        List<String> allowed = new ArrayList<>();
        for (T value : values)
        {
            allowed.add(value.getLiteral());
        }
        return EnumResult.failure("Enum value '" + raw + "' for '" + path + "." + member //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' is invalid. Use one of the platform literals: " + String.join(", ", allowed) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ---- JSON helpers -----------------------------------------------------------------------

    private static String objectError;
    private static String arrayError;
    private static String arrayObjectError;
    private static String stringError;
    private static String booleanError;
    private static String indexError;
    private static String primitiveStringError;

    private static JsonObject object(JsonObject body, String member, String path)
    {
        JsonElement value = body.get(member);
        if (value == null || !value.isJsonObject())
        {
            objectError = "Member '" + path + "." + member + "' must be a JSON object."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        objectError = null;
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject body, String member, String path)
    {
        JsonElement value = body.get(member);
        if (value == null || !value.isJsonArray())
        {
            arrayError = "Member '" + path + "." + member + "' must be a JSON array."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        arrayError = null;
        return value.getAsJsonArray();
    }

    private static JsonObject arrayObject(JsonArray array, int index, String path)
    {
        JsonElement value = array.get(index);
        if (value == null || !value.isJsonObject())
        {
            arrayObjectError = "Entry '" + path + "[" + index + "]' must be a JSON object."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        arrayObjectError = null;
        return value.getAsJsonObject();
    }

    private static String optionalString(JsonObject body, String member, String path)
    {
        if (!body.has(member) || body.get(member).isJsonNull())
        {
            stringError = null;
            return null;
        }
        JsonElement value = body.get(member);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            stringError = "Member '" + path + "." + member + "' must be a string."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        stringError = null;
        return value.getAsString();
    }

    private static String requiredString(JsonObject body, String member, String path)
    {
        String result = optionalString(body, member, path);
        if (stringError == null && (result == null || result.isEmpty()))
        {
            stringError = "Member '" + path + "." + member + "' must be a non-empty string."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return result;
    }

    private static String requiredPrimitiveString(JsonElement value, String path)
    {
        return primitiveString(value, path, false);
    }

    private static String primitiveString(JsonElement value, String path, boolean allowEmpty)
    {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || !allowEmpty && value.getAsString().isEmpty())
        {
            primitiveStringError = "ValueSpec at '" + path //$NON-NLS-1$
                + (allowEmpty ? "' needs a string in 'value'." //$NON-NLS-1$
                    : "' needs a non-empty string in 'value'."); //$NON-NLS-1$
            return null;
        }
        primitiveStringError = null;
        return value.getAsString();
    }

    private static Boolean bool(JsonObject body, String member, String path)
    {
        JsonElement value = body.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
        {
            booleanError = "Member '" + path + "." + member + "' must be true or false."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        booleanError = null;
        return Boolean.valueOf(value.getAsBoolean());
    }

    private static int index(String raw, int size, String path)
    {
        if (!DcsAddress.isZeroBasedIndex(raw))
        {
            indexError = "Index '" + raw + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' is invalid. Pass a zero-based integer copied from dcs action='get'."; //$NON-NLS-1$
            return -1;
        }
        int value = Integer.parseInt(raw);
        if (value >= size)
        {
            indexError = "Index '" + raw + "' at '" + path + "' is out of range; existing indices: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (size == 0 ? "(none)" : "0.." + (size - 1)) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Re-run dcs action='get' and copy the new address."; //$NON-NLS-1$
            return -1;
        }
        indexError = null;
        return value;
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

    private static String kindMustBe(JsonObject body, String path, String expected)
    {
        if (!body.has(KEY_KIND))
        {
            return null;
        }
        String kind = optionalString(body, KEY_KIND, path);
        if (stringError != null)
        {
            return stringError;
        }
        return expected.equalsIgnoreCase(kind) ? null
            : "Item kind '" + kind + "' at '" + path + "' collides with existing subtype '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + expected + "'. Keep kind='" + expected + "', or append a new item with upsert."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static PresentationResult presentation(JsonElement element,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(element, languages, path);
        return parsed.isSuccess() ? PresentationResult.success(parsed.plan() == null ? null
            : DcsPresentationParser.build(parsed.plan())) : PresentationResult.failure(parsed.error());
    }

    private static List<String> defaultPath(String type)
    {
        switch (type)
        {
            case TYPE_GROUPING:
                return Collections.singletonList(KEY_ITEMS);
            case TYPE_SELECTION:
                return Collections.singletonList("selection"); //$NON-NLS-1$
            case TYPE_FILTER:
                return Collections.singletonList("filter"); //$NON-NLS-1$
            case TYPE_DATA_PARAMETER:
                return Collections.singletonList("dataParameters"); //$NON-NLS-1$
            case TYPE_ORDER:
                return Collections.singletonList("order"); //$NON-NLS-1$
            case TYPE_OUTPUT_PARAMETER:
                return Collections.singletonList("outputParameters"); //$NON-NLS-1$
            default:
                return Collections.emptyList();
        }
    }

    private static int findStructure(List<StructureItem> items, String selector)
    {
        if (DcsAddress.isZeroBasedIndex(selector))
        {
            int index = Integer.parseInt(selector);
            return index < items.size() ? index : -1;
        }
        return -1;
    }

    private static String structureSelectors(List<StructureItem> items)
    {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++)
        {
            StructureItem item = items.get(i);
            result.add(Integer.toString(i));
        }
        return result.isEmpty() ? "(none)" : String.join(", ", result); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int findVariant(List<SettingsVariant> variants, String name)
    {
        for (int i = 0; i < variants.size(); i++)
        {
            if (name.equals(variants.get(i).getName()))
            {
                return i;
            }
        }
        return -1;
    }

    private static String variantNames(List<SettingsVariant> variants)
    {
        List<String> names = new ArrayList<>();
        for (SettingsVariant variant : variants)
        {
            names.add(variant.getName());
        }
        return names.isEmpty() ? "(none)" : String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<SettingsVariant> copyVariants(List<SettingsVariant> variants)
    {
        List<SettingsVariant> result = new ArrayList<>();
        for (SettingsVariant variant : variants)
        {
            result.add(EcoreUtil.copy(variant));
        }
        return result;
    }

    private static <T extends org.eclipse.emf.ecore.EObject> T copy(T value)
    {
        return value == null ? null : EcoreUtil.copy(value);
    }

    private static void copyMember(JsonObject source, JsonObject target, String member)
    {
        if (source != null && source.has(member))
        {
            target.add(member, source.get(member).deepCopy());
        }
    }

    private static SettingsResult withTouched(SettingsResult result)
    {
        return result.isSuccess() ? SettingsResult.success(result.settings(), true) : result;
    }

    // ---- owner access -----------------------------------------------------------------------

    private interface SettingsAccess
    {
        DataCompositionSelectedFields selection();
        void selection(DataCompositionSelectedFields value);
        DataCompositionFilter filter();
        void filter(DataCompositionFilter value);
        DataCompositionOrder order();
        void order(DataCompositionOrder value);
    }

    private static final class RootSettingsAccess implements SettingsAccess
    {
        private final DataCompositionSettings settings;
        RootSettingsAccess(DataCompositionSettings settings) { this.settings = settings; }
        @Override public DataCompositionSelectedFields selection() { return settings.getSelection(); }
        @Override public void selection(DataCompositionSelectedFields value) { settings.setSelection(value); }
        @Override public DataCompositionFilter filter() { return settings.getFilter(); }
        @Override public void filter(DataCompositionFilter value) { settings.setFilter(value); }
        @Override public DataCompositionOrder order() { return settings.getOrder(); }
        @Override public void order(DataCompositionOrder value) { settings.setOrder(value); }
    }

    private static final class GroupSettingsAccess implements SettingsAccess
    {
        private final DataCompositionGroup group;
        GroupSettingsAccess(DataCompositionGroup group) { this.group = group; }
        @Override public DataCompositionSelectedFields selection() { return group.getSelection(); }
        @Override public void selection(DataCompositionSelectedFields value) { group.setSelection(value); }
        @Override public DataCompositionFilter filter() { return group.getFilter(); }
        @Override public void filter(DataCompositionFilter value) { group.setFilter(value); }
        @Override public DataCompositionOrder order() { return group.getOrder(); }
        @Override public void order(DataCompositionOrder value) { group.setOrder(value); }
    }

    /** Detached schema settings plan. */
    public static final class SchemaPlan
    {
        private final DataCompositionSettings defaultSettings;
        private final List<SettingsVariant> variants;
        private final boolean defaultTouched;
        private final boolean variantsTouched;

        private SchemaPlan(DataCompositionSettings defaultSettings, List<SettingsVariant> variants,
            boolean defaultTouched, boolean variantsTouched)
        {
            this.defaultSettings = defaultSettings;
            this.variants = variants;
            this.defaultTouched = defaultTouched;
            this.variantsTouched = variantsTouched;
        }

        /** Commits the already-validated detached tree. */
        public void commit(DataCompositionSchema schema)
        {
            if (defaultTouched)
            {
                if (schema.getDefaultSettings() == null)
                {
                    schema.setDefaultSettings(defaultSettings);
                }
                else
                {
                    commitSettings(schema.getDefaultSettings(), defaultSettings);
                }
            }
            if (variantsTouched)
            {
                schema.getSettingsVariants().clear();
                schema.getSettingsVariants().addAll(variants);
            }
        }
    }

    /** Schema planning outcome. */
    public static final class SchemaResult
    {
        private final SchemaPlan plan;
        private final String error;
        private SchemaResult(SchemaPlan plan, String error) { this.plan = plan; this.error = error; }
        private static SchemaResult success(SchemaPlan plan) { return new SchemaResult(plan, null); }
        private static SchemaResult failure(String error) { return new SchemaResult(null, error); }
        public boolean isSuccess() { return error == null; }
        public SchemaPlan plan() { return plan; }
        public String error() { return error; }
    }

    /** Shared settings planning outcome. */
    public static final class SettingsResult
    {
        private final DataCompositionSettings settings;
        private final boolean touched;
        private final String error;
        private SettingsResult(DataCompositionSettings settings, boolean touched, String error)
        {
            this.settings = settings;
            this.touched = touched;
            this.error = error;
        }
        private static SettingsResult success(DataCompositionSettings value, boolean touched)
        {
            return new SettingsResult(value, touched, null);
        }
        private static SettingsResult failure(String error)
        {
            return new SettingsResult(null, false, error);
        }
        public boolean isSuccess() { return error == null; }
        public DataCompositionSettings settings() { return settings; }
        public boolean touched() { return touched; }
        public String error() { return error; }
    }

    private static final class SettingsLocation
    {
        final DataCompositionSettings settings;
        final List<String> relative;
        final int variantIndex;
        final String error;
        private SettingsLocation(DataCompositionSettings settings, List<String> relative,
            int variantIndex, String error)
        {
            this.settings = settings; this.relative = relative; this.variantIndex = variantIndex;
            this.error = error;
        }
        static SettingsLocation defaultSettings(DataCompositionSettings value, List<String> relative)
        { return new SettingsLocation(value, relative, -1, null); }
        static SettingsLocation variant(DataCompositionSettings value, List<String> relative, int index)
        { return new SettingsLocation(value, relative, index, null); }
        static SettingsLocation failure(String error)
        { return new SettingsLocation(null, null, -1, error); }
    }

    private static final class ValueResult
    {
        final Value value; final String error;
        private ValueResult(Value value, String error) { this.value = value; this.error = error; }
        static ValueResult success(Value value) { return new ValueResult(value, null); }
        static ValueResult failure(String error) { return new ValueResult(null, error); }
    }

    private static final class FieldResult
    {
        final DataCompositionField value; final String error;
        private FieldResult(DataCompositionField value, String error) { this.value = value; this.error = error; }
        static FieldResult success(DataCompositionField value) { return new FieldResult(value, null); }
        static FieldResult failure(String error) { return new FieldResult(null, error); }
    }

    private static final class PresentationResult
    {
        final Presentation value; final String error;
        private PresentationResult(Presentation value, String error) { this.value = value; this.error = error; }
        static PresentationResult success(Presentation value) { return new PresentationResult(value, null); }
        static PresentationResult failure(String error) { return new PresentationResult(null, error); }
    }

    private static final class EnumResult<T>
    {
        final T value; final String error;
        private EnumResult(T value, String error) { this.value = value; this.error = error; }
        static <T> EnumResult<T> success(T value) { return new EnumResult<>(value, null); }
        static <T> EnumResult<T> failure(String error) { return new EnumResult<>(null, error); }
    }
}
