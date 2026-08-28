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
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionGroupType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionPeriodAdditionType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionSortDirection;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionComparisonType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceUse;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceFields;
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
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettingsItemState;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettingsItemViewMode;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTable;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTableGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTableGroupOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTableOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldCase;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldExpression;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldsCaseVariants;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldsVariant;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.FilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.GroupItem;
import com._1c.g5.v8.dt.dcs.model.settings.OrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.SelectedItem;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.dcs.model.settings.StructureItem;
import com._1c.g5.v8.dt.dcs.model.settings.UserField;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameter;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.parameters.DcsParameterValuesBase;
import com._1c.g5.v8.dt.dcs.parameters.appearance.DcsAppearanceParameters;
import com._1c.g5.v8.dt.dcs.parameters.appearance.DynamicListAppearanceParameters;
import com._1c.g5.v8.dt.dcs.parameters.appearance.FormAppearanceParameters;
import com._1c.g5.v8.dt.dcs.parameters.output.DcsChartGroupOutputParameters;
import com._1c.g5.v8.dt.dcs.parameters.output.DcsChartOutputParameters;
import com._1c.g5.v8.dt.dcs.parameters.output.DcsGroupOutputParameters;
import com._1c.g5.v8.dt.dcs.parameters.output.DcsOutputParameters;
import com._1c.g5.v8.dt.dcs.parameters.output.DcsTableGroupOutputParameters;
import com._1c.g5.v8.dt.dcs.parameters.output.DcsTableOutputParameters;
import com._1c.g5.v8.dt.dcs.path.DcsPathException;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.EnumValue;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NullValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Structure;
import com._1c.g5.v8.dt.mcore.StructureProperty;
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
    /**
     * One planner invocation's project lookup. Planning is synchronous and every public entry is
     * synchronized; a ThreadLocal keeps nested schema/dynamic-list planner calls supplied without
     * turning the already-wide recursive settings API into another parallel parameter chain.
     */
    private static final ThreadLocal<StyleValueBuilder.NamedColorResolver> NAMED_COLOR_RESOLVER =
        new ThreadLocal<>();
    private static final ThreadLocal<AppearanceCatalogue> APPEARANCE_CATALOGUE = new ThreadLocal<>();
    private static final String ACTION_UPSERT = "upsert"; //$NON-NLS-1$
    private static final String ACTION_UPDATE = "update"; //$NON-NLS-1$
    private static final String ACTION_REPLACE = "replace"; //$NON-NLS-1$
    private static final String ACTION_REMOVE = "remove"; //$NON-NLS-1$

    private static final String TYPE_SCHEMA = "schema"; //$NON-NLS-1$
    private static final String TYPE_DYNAMIC_LIST = "dynamicList"; //$NON-NLS-1$
    private static final String TYPE_VARIANT = "variant"; //$NON-NLS-1$
    private static final String TYPE_GROUPING = "grouping"; //$NON-NLS-1$
    private static final String TYPE_SELECTION = "selection"; //$NON-NLS-1$
    private static final String TYPE_FILTER = "filter"; //$NON-NLS-1$
    private static final String TYPE_DATA_PARAMETER = "dataParameter"; //$NON-NLS-1$
    private static final String TYPE_ORDER = "order"; //$NON-NLS-1$
    private static final String TYPE_CONDITIONAL_APPEARANCE = "conditionalAppearance"; //$NON-NLS-1$
    private static final String TYPE_TABLE = "table"; //$NON-NLS-1$
    private static final String TYPE_USER_FIELD = "userField"; //$NON-NLS-1$
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
    private static final String KEY_ID = "id"; //$NON-NLS-1$
    private static final String KEY_GROUP_STATE = "groupState"; //$NON-NLS-1$
    private static final String KEY_ADDITIONAL_PROPERTIES = "additionalProperties"; //$NON-NLS-1$

    private DcsSettingsWriter()
    {
        // Utility class
    }

    /** Whether {@code type} is one of the supported settings types. */
    public static boolean supports(String type)
    {
        return TYPE_VARIANT.equals(type) || TYPE_GROUPING.equals(type) || TYPE_SELECTION.equals(type)
            || TYPE_FILTER.equals(type) || TYPE_DATA_PARAMETER.equals(type) || TYPE_ORDER.equals(type)
            || TYPE_CONDITIONAL_APPEARANCE.equals(type) || TYPE_TABLE.equals(type)
            || TYPE_USER_FIELD.equals(type) || TYPE_OUTPUT_PARAMETER.equals(type)
            || TYPE_USER_SETTINGS.equals(type);
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
        commitModel(target, planned);
    }

    /** Copies a validated form-appearance plan while preserving its external BM top-object identity. */
    public static void commitConditionalAppearance(DataCompositionConditionalAppearance target,
        DataCompositionConditionalAppearance planned)
    {
        commitModel(target, planned);
    }

    @SuppressWarnings("unchecked")
    private static void commitModel(EObject target, EObject planned)
    {
        if (target == null || planned == null || target == planned)
        {
            return;
        }
        EObject copy = EcoreUtil.copy(planned);
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
            if (!copy.eIsSet(sourceFeature))
            {
                // BM remembers an explicit eSet(default) even where detached EMF reports the
                // feature as unset. Apart from creating a noisy storage-level hash difference,
                // that is not an authoritative copy of the plan's set/unset state. eUnset is
                // valid for every structural feature, not only features declared unsettable.
                target.eUnset(targetFeature);
            }
            else if (targetFeature.isMany())
            {
                EList<Object> targetValues = (EList<Object>)target.eGet(targetFeature);
                targetValues.clear();
                targetValues.addAll(new ArrayList<>((Collection<Object>)value));
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
        return planSchema(schema, action, type, address, body, languages, Version.LATEST);
    }

    /** Version-aware entry used by the live tool for typed appearance parameters. */
    public static synchronized SchemaResult planSchema(DataCompositionSchema schema, String action, String type,
        DcsAddress address, JsonObject body, DcsPresentationParser.LanguageContext languages,
        Version version)
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
            if (ACTION_REPLACE.equals(action))
            {
                defaultSettings = null;
                variants.clear();
                defaultTouched = true;
                variantsTouched = true;
            }
            if (body.has("defaultSettings")) //$NON-NLS-1$
            {
                JsonObject settingsBody = object(body, "defaultSettings", "schema settings body"); //$NON-NLS-1$ //$NON-NLS-2$
                if (settingsBody == null)
                {
                    return SchemaResult.failure(objectError);
                }
                SettingsResult planned = planSettings(defaultSettings, Collections.emptyList(), action,
                    TYPE_USER_SETTINGS, settingsBody, languages, version);
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
                String duplicate = variantBodyKeyError(array);
                if (duplicate != null)
                {
                    return SchemaResult.failure(duplicate);
                }
                for (int i = 0; i < array.size(); i++)
                {
                    JsonObject variantBody = arrayObject(array, i, "variants"); //$NON-NLS-1$
                    if (variantBody == null)
                    {
                        return SchemaResult.failure(arrayObjectError);
                    }
                    String error = applyVariant(variants, null, action, variantBody, languages, version,
                        "body.variants[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (error != null)
                    {
                        return SchemaResult.failure(error);
                    }
                }
                variantsTouched = ACTION_REPLACE.equals(action) || !array.isEmpty();
            }
            String referenceError = ACTION_REPLACE.equals(action)
                ? omittedSchemaSettingsReferenceError(schema, defaultSettings, variants,
                    address.rootFqn()) : null;
            return referenceError == null
                ? SchemaResult.success(new SchemaPlan(defaultSettings, variants, defaultTouched,
                    variantsTouched))
                : SchemaResult.failure(referenceError);
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
            if (pointerName != null)
            {
                List<Integer> naturalMatches = findVariants(variants, pointerName);
                if (naturalMatches.size() > 1)
                {
                    return SchemaResult.failure(ambiguousVariant(action, pointerName,
                        address.toString(), naturalMatches.size()));
                }
                List<Integer> matches = findVariantSelectors(variants, pointerName);
                if (matches.size() > 1)
                {
                    return SchemaResult.failure(ambiguousVariantSelector(action, pointerName,
                        address.toString(), matches.size()));
                }
            }
            if (ACTION_REMOVE.equals(action))
            {
                if (pointerName == null)
                {
                    return SchemaResult.failure("action='remove' needs an exact '#/variants/<name>' " //$NON-NLS-1$
                        + "address copied from get."); //$NON-NLS-1$
                }
                List<Integer> matches = findVariantSelectors(variants, pointerName);
                int index = matches.isEmpty() ? -1 : matches.get(0).intValue();
                if (index < 0)
                {
                    return SchemaResult.failure("Variant '" + pointerName //$NON-NLS-1$
                        + "' was not found. Existing variants: " + variantNames(variants) + "."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                variants.remove(index);
                return SchemaResult.success(new SchemaPlan(defaultSettings, variants, false, true));
            }
            String error = applyVariant(variants, pointerName, action, body, languages, version, "body"); //$NON-NLS-1$
            if (error == null && ACTION_REPLACE.equals(action))
            {
                error = omittedSchemaSettingsReferenceError(schema, defaultSettings, variants,
                    address.rootFqn());
            }
            return error == null
                ? SchemaResult.success(new SchemaPlan(defaultSettings, variants, false, true))
                : SchemaResult.failure(error);
        }

        if (ACTION_REMOVE.equals(action) && address.segments().size() == 1
            && "defaultSettings".equals(address.segments().get(0))) //$NON-NLS-1$
        {
            if (schema.getDefaultSettings() == null)
                return SchemaResult.failure("defaultSettings is not present. Re-run get."); //$NON-NLS-1$
            String typeError = resolvedSettingsTypeError(schema.getDefaultSettings(),
                address.segments(), action, type);
            if (typeError != null) return SchemaResult.failure(typeError);
            return SchemaResult.success(new SchemaPlan(null, variants, true, false));
        }
        SettingsLocation location = locateSchemaSettings(schema, variants, address, type, body, action);
        if (location.error != null)
        {
            return SchemaResult.failure(location.error);
        }
        SettingsResult planned = planSettings(location.settings, location.relative, action, type, body,
            languages, version, address, schemaSettingsRootAddress(address, location.relative));
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

    /** Live-project entry that additionally resolves style/palette named colors. */
    public static synchronized SchemaResult planSchema(DataCompositionSchema schema, String action, String type,
        DcsAddress address, JsonObject body, DcsPresentationParser.LanguageContext languages,
        Version version, StyleValueBuilder.NamedColorResolver namedColors)
    {
        return withNamedColors(namedColors,
            () -> planSchema(schema, action, type, address, body, languages, version));
    }

    /** Builds a detached dynamic-list settings mutation through the same {@link #planSettings} path. */
    public static synchronized SettingsResult planDynamicList(DataCompositionSettings current, String action,
        String type, DcsAddress address, JsonObject body,
        DcsPresentationParser.LanguageContext languages)
    {
        return planDynamicList(current, action, type, address, body, languages, Version.LATEST);
    }

    /**
     * Builds a detached mutation of a form's own conditional appearance. Form addresses are rooted
     * directly at the holder, so their pointer segments are prefixed with the wrapper feature used
     * by the shared settings machinery. Appearance keys deliberately come from EDT's
     * {@link FormAppearanceParameters}, while every holder/item mutation retains the same semantics
     * as schema and dynamic-list conditional appearance.
     */
    public static synchronized SettingsResult planFormConditionalAppearance(
        DataCompositionConditionalAppearance current, String action, String type,
        DcsAddress address, JsonObject body, DcsPresentationParser.LanguageContext languages,
        Version version, StyleValueBuilder.NamedColorResolver namedColors)
    {
        if (!TYPE_CONDITIONAL_APPEARANCE.equals(type))
        {
            return SettingsResult.failure("A form DCS root supports only " //$NON-NLS-1$
                + "type='conditionalAppearance'; got type='" + type + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String common = validateCommon(action, type, address, body, languages);
        if (common != null) return SettingsResult.failure(common);

        DataCompositionSettings wrapper = DcsFactory.eINSTANCE.createDataCompositionSettings();
        wrapper.setConditionalAppearance(copy(current));
        List<String> relative = new ArrayList<>();
        relative.add("conditionalAppearance"); //$NON-NLS-1$
        relative.addAll(address.segments());
        return withNamedColors(namedColors, () -> withAppearanceCatalogue(AppearanceCatalogue.FORM, () ->
            planSettings(wrapper, relative, action, type, body, languages, version,
                address, address.rootFqn())));
    }

    /** Version-aware dynamic-list settings entry used by the live tool. */
    public static synchronized SettingsResult planDynamicList(DataCompositionSettings current, String action,
        String type, DcsAddress address, JsonObject body,
        DcsPresentationParser.LanguageContext languages, Version version)
    {
        if (APPEARANCE_CATALOGUE.get() != AppearanceCatalogue.DYNAMIC_LIST)
        {
            return withAppearanceCatalogue(AppearanceCatalogue.DYNAMIC_LIST,
                () -> planDynamicList(current, action, type, address, body, languages, version));
        }
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
                    TYPE_USER_SETTINGS, settingsBody, languages, version, address,
                    dynamicListSettingsRootAddress(address)));
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
        return withTouched(planSettings(current, segments, action, type, body, languages, version,
            address, dynamicListSettingsRootAddress(address)));
    }

    /** Live-project dynamic-list entry that additionally resolves style/palette named colors. */
    public static synchronized SettingsResult planDynamicList(DataCompositionSettings current, String action,
        String type, DcsAddress address, JsonObject body,
        DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors)
    {
        return withNamedColors(namedColors,
            () -> planDynamicList(current, action, type, address, body, languages, version));
    }

    /**
     * Shared owner-independent settings planner. This is deliberately public for the equivalence unit
     * test: both report and dynamic-list entry points must produce this same settings tree.
     */
    public static synchronized SettingsResult planSettings(DataCompositionSettings current, List<String> relative,
        String action, String type, JsonObject body, DcsPresentationParser.LanguageContext languages)
    {
        return planSettings(current, relative, action, type, body, languages, Version.LATEST);
    }

    /** Owner-independent settings planner with the target platform version. */
    public static synchronized SettingsResult planSettings(DataCompositionSettings current, List<String> relative,
        String action, String type, JsonObject body, DcsPresentationParser.LanguageContext languages,
        Version version)
    {
        return planSettings(current, relative, action, type, body, languages, version, null, null);
    }

    private static SettingsResult planSettings(DataCompositionSettings current, List<String> relative,
        String action, String type, JsonObject body, DcsPresentationParser.LanguageContext languages,
        Version version, DcsAddress targetAddress, String settingsRootAddress)
    {
        List<String> path = relative == null ? Collections.emptyList() : relative;
        if (ACTION_REMOVE.equals(action))
        {
            if (current == null)
            {
                return SettingsResult.failure("action='remove' cannot find settings at the requested " //$NON-NLS-1$
                    + "address. Re-run dcs action='get' and copy an existing node address."); //$NON-NLS-1$
            }
            if (path.isEmpty())
            {
                return SettingsResult.failure("action='remove' refuses a settings root. Address exactly " //$NON-NLS-1$
                    + "one child node returned by dcs action='get'."); //$NON-NLS-1$
            }
            String typeError = resolvedMutationTypeError(current, path, action, type,
                targetAddress);
            if (typeError != null) return SettingsResult.failure(typeError);
            DataCompositionSettings working = copy(current);
            String error = removeSettingsPath(working, path, type);
            if (error != null)
            {
                return SettingsResult.failure(error);
            }
            String referenceError = omittedUserFieldReferenceError(current, working,
                settingsRootAddress);
            return referenceError == null ? SettingsResult.success(working, true)
                : SettingsResult.failure(referenceError);
        }
        // Resolve the default path BEFORE deciding whether to start from a blank settings object.
        // A concrete type addressed at the bare root (action='replace', type='selection') arrives
        // with an empty segment list and only then gains 'selection' from defaultPath - so reading
        // path.isEmpty() first treated it as an authoritative replacement of the WHOLE settings and
        // silently dropped filter, order, conditional appearance and data parameters. Only a type
        // whose default path is itself empty addresses the settings root; that one still resets,
        // which is what replacing a root means.
        if (path.isEmpty())
        {
            path = defaultPath(type);
        }
        String typeError = resolvedMutationTypeError(current, path, action, type, targetAddress);
        if (typeError != null)
        {
            return SettingsResult.failure(typeError);
        }
        DataCompositionSettings working = ACTION_REPLACE.equals(action) && path.isEmpty()
            ? DcsFactory.eINSTANCE.createDataCompositionSettings() : copy(current);
        if (working == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return SettingsResult.failure("action='update' cannot find settings at the requested " //$NON-NLS-1$
                    + "address. Use action='upsert' to create them first."); //$NON-NLS-1$
            }
            working = DcsFactory.eINSTANCE.createDataCompositionSettings();
        }
        String error = path.isEmpty() ? applySettingsBody(working, body, action, languages, version, "body") //$NON-NLS-1$
            : applySettingsPath(working, path, body, action, type, languages, version);
        if (error != null)
        {
            return SettingsResult.failure(error);
        }
        String referenceError = omittedUserFieldReferenceError(current, working,
            settingsRootAddress);
        return referenceError == null ? SettingsResult.success(working, true)
            : SettingsResult.failure(referenceError);
    }

    /**
     * Refuses every settings mutation whose resulting tree omits an existing user-field identity
     * while retaining a node that still refers to it. Comparing the planned tree, rather than a
     * verb-specific body shape, covers exact renames, holder removal/replacement, and authoritative
     * replacement of a whole settings object with the same rule.
     */
    private static String omittedUserFieldReferenceError(DataCompositionSettings existing,
        DataCompositionSettings retained, String settingsRootAddress)
    {
        if (existing == null || retained == null || settingsRootAddress == null
            || existing.getUserFields() == null)
        {
            return null;
        }
        Set<String> retainedIdentities = new LinkedHashSet<>();
        if (retained.getUserFields() != null)
        {
            for (UserField field : retained.getUserFields().getItems())
            {
                String identity = field.getDataPath();
                if (identity != null && !identity.isEmpty())
                {
                    retainedIdentities.add(identity);
                }
            }
        }

        List<UserField> existingFields = existing.getUserFields().getItems();
        for (int i = 0; i < existingFields.size(); i++)
        {
            String identity = existingFields.get(i).getDataPath();
            if (identity == null || identity.isEmpty() || retainedIdentities.contains(identity))
            {
                continue;
            }
            DcsAddress target = userFieldAddress(settingsRootAddress, i);
            String error = DcsMutationGuard.referenceError(retained, settingsRootAddress, target,
                TYPE_USER_FIELD, identity);
            if (error != null) return error;
        }
        return null;
    }

    private static String omittedSchemaSettingsReferenceError(DataCompositionSchema existing,
        DataCompositionSettings retainedDefault, List<SettingsVariant> retainedVariants,
        String rootFqn)
    {
        String defaultAddress = DcsAddress.render(rootFqn,
            Collections.singletonList("defaultSettings")); //$NON-NLS-1$
        String error = omittedUserFieldReferenceError(existing.getDefaultSettings(),
            retainedDefault, defaultAddress);
        if (error != null) return error;

        for (SettingsVariant existingVariant : existing.getSettingsVariants())
        {
            String name = existingVariant.getName();
            if (name == null || name.isEmpty()) continue;
            List<Integer> matches = findVariants(retainedVariants, name);
            if (matches.size() != 1) continue;
            List<String> segments = new ArrayList<>(Arrays.asList("variants", name, "settings")); //$NON-NLS-1$ //$NON-NLS-2$
            String settingsAddress = DcsAddress.render(rootFqn, segments);
            error = omittedUserFieldReferenceError(existingVariant.getSettings(),
                retainedVariants.get(matches.get(0).intValue()).getSettings(), settingsAddress);
            if (error != null) return error;
        }
        return null;
    }

    private static DcsAddress userFieldAddress(String settingsRootAddress, int index)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(settingsRootAddress);
        List<String> segments = new ArrayList<>(parsed.address().segments());
        segments.add("userFields"); //$NON-NLS-1$
        segments.add(KEY_ITEMS);
        segments.add(Integer.toString(index));
        return DcsAddress.parse(DcsAddress.render(parsed.address().rootFqn(), segments)).address();
    }

    private static String schemaSettingsRootAddress(DcsAddress address, List<String> relative)
    {
        List<String> segments = address.segments();
        int prefixSize = segments.size() - relative.size();
        if (prefixSize <= 0)
        {
            return DcsAddress.render(address.rootFqn(),
                Collections.singletonList("defaultSettings")); //$NON-NLS-1$
        }
        return DcsAddress.render(address.rootFqn(), segments.subList(0, prefixSize));
    }

    private static String dynamicListSettingsRootAddress(DcsAddress address)
    {
        return DcsAddress.render(address.rootFqn(), Collections.singletonList("listSettings")); //$NON-NLS-1$
    }

    private static String validateCommon(String action, String type, DcsAddress address, JsonObject body,
        DcsPresentationParser.LanguageContext languages)
    {
        if (!ACTION_UPSERT.equals(action) && !ACTION_UPDATE.equals(action)
            && !ACTION_REPLACE.equals(action) && !ACTION_REMOVE.equals(action))
        {
            return "Settings authoring does not support action='" + action //$NON-NLS-1$
                + "'. Use upsert, update, replace, or remove."; //$NON-NLS-1$
        }
        if (address == null || body == null && !ACTION_REMOVE.equals(action))
        {
            return "A parsed DCS address and one body object are required for settings authoring."; //$NON-NLS-1$
        }
        if (!TYPE_SCHEMA.equals(type) && !TYPE_DYNAMIC_LIST.equals(type) && !supports(type))
        {
            return "Type '" + type + "' is not a settings type. Use variant, grouping, selection, " //$NON-NLS-1$ //$NON-NLS-2$
                + "filter, dataParameter, order, outputParameter, or userSettings."; //$NON-NLS-1$
        }
        String presentation = body == null ? null
            : DcsPresentationParser.validateRecursively(body, languages);
        return presentation;
    }

    private static SettingsLocation locateSchemaSettings(DataCompositionSchema schema,
        List<SettingsVariant> variants, DcsAddress address, String type, JsonObject body, String action)
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
            String name = segments.get(1);
            List<Integer> naturalMatches = findVariants(variants, name);
            if (naturalMatches.size() > 1)
            {
                return SettingsLocation.failure(ambiguousVariant(action, name, address.toString(),
                    naturalMatches.size()));
            }
            List<Integer> matches = findVariantSelectors(variants, name);
            if (matches.size() > 1)
            {
                return SettingsLocation.failure(ambiguousVariantSelector(action, name, address.toString(),
                    matches.size()));
            }
            int index = matches.isEmpty() ? -1 : matches.get(0).intValue();
            if (index < 0)
            {
                return SettingsLocation.failure("Settings variant '" + name //$NON-NLS-1$
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

    private static String variantBodyKeyError(JsonArray variants)
    {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < variants.size(); i++)
        {
            JsonObject body = arrayObject(variants, i, "variants"); //$NON-NLS-1$
            if (body == null) return arrayObjectError;
            String name = optionalString(body, KEY_NAME, "body.variants[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (stringError != null) return stringError;
            if (name != null && !name.isEmpty() && !names.add(name))
            {
                return "The body names variant natural key '" + name //$NON-NLS-1$
                    + "' more than once. Keep exactly one entry for that key."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static String applyVariant(List<SettingsVariant> variants, String pointerName, String action,
        JsonObject body, DcsPresentationParser.LanguageContext languages, Version version, String path)
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
        List<Integer> matches = findVariants(variants, name);
        if (matches.size() > 1)
        {
            return ambiguousVariant(action, name, path, matches.size());
        }
        int index = matches.isEmpty() ? -1 : matches.get(0).intValue();
        if (ACTION_UPDATE.equals(action) && index < 0)
        {
            return "action='update' could not find variant '" + name + "'. Existing variants: " //$NON-NLS-1$ //$NON-NLS-2$
                + variantNames(variants) + ". Use action='upsert' to create it."; //$NON-NLS-1$
        }
        boolean buildsFresh = index < 0 || ACTION_REPLACE.equals(action);
        SettingsVariant variant = buildsFresh
            ? DcsFactory.eINSTANCE.createSettingsVariant()
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
        // Checking the RESOLVED presentation, not merely that the member was supplied: the parser
        // reports success with a null plan for JSON null, "" and {} alike, so a presence-only guard
        // still lets all three through and stores a presentation-less variant - the shape that makes
        // the platform's DataCompositionNameVariantDefaultCheck throw and stop validating the schema.
        // A variant that ALREADY had none and whose body does not mention it is left alone, so this
        // never refuses over a member the caller never touched.
        if (variant.getPresentation() == null && (buildsFresh || body.has(KEY_PRESENTATION)))
        {
            return "Variant '" + name + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                + "' needs a non-empty 'presentation'. Pass a string or a {languageCode: text} map; " //$NON-NLS-1$
                + "null, an empty string and {} all leave the variant without one."; //$NON-NLS-1$
        }
        if (body.has("settings")) //$NON-NLS-1$
        {
            JsonObject settingsBody = object(body, "settings", path); //$NON-NLS-1$
            if (settingsBody == null)
            {
                return objectError;
            }
            SettingsResult settings = planSettings(variant.getSettings(), Collections.emptyList(),
                action, TYPE_USER_SETTINGS, settingsBody, languages, version);
            if (!settings.isSuccess())
            {
                return settings.error();
            }
            variant.setSettings(settings.settings());
        }
        else if (variant.getSettings() == null && buildsFresh)
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
        JsonObject body, String action, String type, DcsPresentationParser.LanguageContext languages,
        Version version)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        switch (head)
        {
            case KEY_ITEMS:
                return applyStructurePath(settings.getItems(), tail, body, action, languages,
                    version, "settings.items"); //$NON-NLS-1$
            case "selection": //$NON-NLS-1$
                return applySelectionPath(settings, tail, body, action, languages);
            case "filter": //$NON-NLS-1$
                return applyFilterPath(settings, tail, body, action, languages);
            case "dataParameters": //$NON-NLS-1$
                return applyParameterPath(settings, tail, body, action, languages, version, true);
            case "order": //$NON-NLS-1$
                return applyOrderPath(settings, tail, body, action, languages);
            case "conditionalAppearance": //$NON-NLS-1$
                return applyConditionalAppearancePath(settings, tail, body, action, languages, version);
            case "userFields": //$NON-NLS-1$
                return applyUserFieldsPath(settings, tail, body, action, languages, version);
            case "outputParameters": //$NON-NLS-1$
                return applyParameterPath(settings, tail, body, action, languages, version, false);
            case KEY_ADDITIONAL_PROPERTIES:
                return applyAdditionalPropertiesPath(settings, tail, body, action,
                    "settings.additionalProperties"); //$NON-NLS-1$
            default:
                return "Settings path segment '" + head + "' is not authorable for type='" //$NON-NLS-1$ //$NON-NLS-2$
                    + type + "'. Use items, selection, filter, dataParameters, order, or " //$NON-NLS-1$
                    + "conditionalAppearance, outputParameters, userFields, or additionalProperties, " //$NON-NLS-1$
                    + "copying the address " //$NON-NLS-1$
                    + "from dcs action='get'."; //$NON-NLS-1$
        }
    }

    private static String applySettingsBody(DataCompositionSettings settings, JsonObject body,
        String action, DcsPresentationParser.LanguageContext languages, Version version, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS, "selection", "filter", //$NON-NLS-1$ //$NON-NLS-2$
            "dataParameters", "order", "conditionalAppearance", "outputParameters", "userFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            KEY_ADDITIONAL_PROPERTIES,
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
            if (ACTION_REPLACE.equals(action))
            {
                settings.getItems().clear();
            }
            String error = appendGroupings(settings.getItems(), items, action, languages,
                version, path + ".items"); //$NON-NLS-1$
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
            String missing = missingHolderUpdate(action, settings.getSelection(),
                path + ".selection"); //$NON-NLS-1$
            if (missing != null) return missing;
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
            String missing = missingHolderUpdate(action, settings.getFilter(), path + ".filter"); //$NON-NLS-1$
            if (missing != null) return missing;
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
            String missing = missingHolderUpdate(action, settings.getOrder(), path + ".order"); //$NON-NLS-1$
            if (missing != null) return missing;
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
            String missing = missingHolderUpdate(action, settings.getConditionalAppearance(),
                path + ".conditionalAppearance"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionConditionalAppearance holder = copy(settings.getConditionalAppearance());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            }
            String error = applyConditionalAppearance(holder, value, action, languages, version,
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
            String missing = missingHolderUpdate(action, settings.getDataParameters(),
                path + ".dataParameters"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionDataParameterValues holder = copy(settings.getDataParameters());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionDataParameterValues();
            }
            String error = applyParameters(holder, value, action, languages, version, null,
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
            String missing = missingHolderUpdate(action, settings.getOutputParameters(),
                path + ".outputParameters"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionOutputParameterValues holder = copy(settings.getOutputParameters());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues();
            }
            String error = applyParameters(holder, value, action, languages, version,
                OutputParameterCatalogue.SETTINGS, path + ".outputParameters"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setOutputParameters(holder);
        }
        if (body.has("userFields")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "userFields", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            String missing = missingHolderUpdate(action, settings.getUserFields(),
                path + ".userFields"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionUserFields holder = ACTION_REPLACE.equals(action) ? null
                : copy(settings.getUserFields());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionUserFields();
            }
            String error = applyUserFields(holder, value, action, languages, version,
                path + ".userFields"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            settings.setUserFields(holder);
        }
        if (body.has(KEY_ADDITIONAL_PROPERTIES))
        {
            JsonObject properties = object(body, KEY_ADDITIONAL_PROPERTIES, path);
            if (properties == null)
            {
                return objectError;
            }
            String error = applyAdditionalProperties(settings, properties, action,
                path + "." + KEY_ADDITIONAL_PROPERTIES); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String applyAdditionalPropertiesPath(DataCompositionSettings settings,
        List<String> path, JsonObject body, String action, String where)
    {
        if (!path.isEmpty())
        {
            return "Additional-properties address '" + where //$NON-NLS-1$
                + "' authors the holder as one name-to-ValueSpec object. Remove the trailing " //$NON-NLS-1$
                + "segments and retry."; //$NON-NLS-1$
        }
        return applyAdditionalProperties(settings, body, action, where);
    }

    /** Authors the genuinely settable {@link DataCompositionSettings#getAdditionalProperties()} map. */
    private static String applyAdditionalProperties(DataCompositionSettings settings, JsonObject body,
        String action, String path)
    {
        Structure existing = settings.getAdditionalProperties();
        if (ACTION_UPDATE.equals(action) && existing == null)
        {
            return "action='update' cannot find additionalProperties at '" + path //$NON-NLS-1$
                + "'. Use action='upsert' to create them."; //$NON-NLS-1$
        }
        Structure structure = ACTION_REPLACE.equals(action) ? null : copy(existing);
        if (structure == null)
        {
            structure = McoreFactory.eINSTANCE.createStructure();
        }
        for (Map.Entry<String, JsonElement> entry : body.entrySet())
        {
            String name = entry.getKey();
            if (name == null || name.isEmpty())
            {
                return "Additional-property names at '" + path + "' must be non-empty strings."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            ValueResult parsed = value(entry.getValue(), path + "." + name); //$NON-NLS-1$
            if (parsed.error != null)
            {
                return parsed.error;
            }
            StructureProperty property = findStructureProperty(structure, name);
            if (property == null)
            {
                property = McoreFactory.eINSTANCE.createStructureProperty();
                property.setName(name);
                structure.getProperty().add(property);
            }
            property.setValue(parsed.value);
        }
        settings.setAdditionalProperties(structure);
        return null;
    }

    private static StructureProperty findStructureProperty(Structure structure, String name)
    {
        for (StructureProperty property : structure.getProperty())
        {
            if (name.equals(property.getName()))
            {
                return property;
            }
        }
        return null;
    }

    private static String missingHolderUpdate(String action, EObject holder, String path)
    {
        if (!ACTION_UPDATE.equals(action) || holder != null) return null;
        return "action='update' cannot find the existing holder at '" + path //$NON-NLS-1$
            + "'. Use action='upsert' to create it."; //$NON-NLS-1$
    }

    // ---- structure groups -------------------------------------------------------------------

    private static String applyStructurePath(List<StructureItem> items, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version,
        String where)
    {
        if (path.isEmpty())
        {
            // The address ends AT this collection, so an authoritative replace replaces the
            // collection - it does not append to it. Missing this made replace with type='grouping'
            // at a bare root add a second copy of every grouping instead of swapping them.
            if (ACTION_REPLACE.equals(action))
            {
                items.clear();
            }
            if (body.has(KEY_ITEMS))
            {
                String members = checkMembers(body, where, KEY_ITEMS);
                if (members != null)
                {
                    return members;
                }
                JsonArray array = array(body, KEY_ITEMS, where);
                return array == null ? arrayError
                    : appendGroupings(items, array, action, languages, version, where);
            }
            return appendGrouping(items, body, action, languages, version, where);
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
        if (ACTION_REPLACE.equals(action) && path.size() == 1)
        {
            if (!(selected instanceof DataCompositionGroup)
                && !(selected instanceof DataCompositionTable))
            {
                String refusal = DcsUnsupportedAuthoring.refusal(selected,
                    where + "/" + selector); //$NON-NLS-1$
                if (refusal != null) return refusal;
                return "Structure item '" + selector + "' is " + selected.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + ", not a supported grouping or table. Replace a grouping or table address " //$NON-NLS-1$
                    + "returned by get."; //$NON-NLS-1$
            }
            String kind = optionalString(body, KEY_KIND, where + "/" + selector); //$NON-NLS-1$
            if (stringError != null)
            {
                return stringError;
            }
            if (kind == null)
            {
                kind = selected instanceof DataCompositionTable ? "table" : "grouping"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (DcsUnsupportedAuthoring.isChartKind(kind))
            {
                return DcsUnsupportedAuthoring.refusal(DcsUnsupportedAuthoring.CHART_CLASS,
                    where + "/" + selector); //$NON-NLS-1$
            }
            if ("table".equalsIgnoreCase(kind)) //$NON-NLS-1$
            {
                DataCompositionTable table = DcsFactory.eINSTANCE.createDataCompositionTable();
                items.set(index, table);
                return applyTable(table, body, action, languages, version,
                    where + "/" + selector, items); //$NON-NLS-1$
            }
            if ("grouping".equalsIgnoreCase(kind)) //$NON-NLS-1$
            {
                DataCompositionGroup group = DcsFactory.eINSTANCE.createDataCompositionGroup();
                items.set(index, group);
                return applyGrouping(group, body, action, languages, version,
                    where + "/" + selector, items); //$NON-NLS-1$
            }
            return "Structure item kind '" + kind + "' at '" + where + "/" + selector //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "' is invalid. Use kind='grouping' or kind='table'."; //$NON-NLS-1$
        }
        if (selected instanceof DataCompositionTable)
        {
            DataCompositionTable table = (DataCompositionTable)selected;
            if (path.size() == 1)
            {
                return applyTable(table, body, action, languages, version,
                    where + "/" + selector, items); //$NON-NLS-1$
            }
            return applyTableChildPath(table, path.subList(1, path.size()), body, action, languages,
                version, where + "/" + selector); //$NON-NLS-1$
        }
        if (!(selected instanceof DataCompositionGroup))
        {
            String refusal = DcsUnsupportedAuthoring.refusal(selected,
                where + "/" + selector); //$NON-NLS-1$
            if (refusal != null) return refusal;
            return "Structure item '" + selector + "' is " + selected.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ", not DataCompositionGroup. Group authoring cannot replace tables, charts, or " //$NON-NLS-1$
                + "nested settings; address a group returned by get."; //$NON-NLS-1$
        }
        DataCompositionGroup group = (DataCompositionGroup)selected;
        if (path.size() == 1)
        {
            return applyGrouping(group, body, action, languages, version, where + "/" + selector, items); //$NON-NLS-1$
        }
        return applyGroupChildPath(group, path.subList(1, path.size()), body, action, languages, version,
            where + "/" + selector); //$NON-NLS-1$
    }

    private static String applyGroupChildPath(DataCompositionGroup group, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version,
        String where)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        switch (head)
        {
            case KEY_ITEMS:
                return applyStructurePath(group.getItems(), tail, body, action, languages, version,
                    where + "/items"); //$NON-NLS-1$
            case "selection": //$NON-NLS-1$
            case "filter": //$NON-NLS-1$
            case "order": //$NON-NLS-1$
            case "groupFields": //$NON-NLS-1$
                return applyGroupingHolderPath(new GroupSettingsAccess(group), head, tail, body,
                    action, languages, where);
            case "conditionalAppearance": //$NON-NLS-1$
                DataCompositionConditionalAppearance existing = group.getConditionalAppearance();
                if (ACTION_UPDATE.equals(action) && existing == null)
                {
                    return "action='update' cannot find conditionalAppearance at '" + where //$NON-NLS-1$
                        + "/conditionalAppearance'. Use action='upsert' to create it."; //$NON-NLS-1$
                }
                DataCompositionConditionalAppearance holder = ACTION_REPLACE.equals(action)
                    && tail.isEmpty() ? null : copy(existing);
                if (holder == null)
                {
                    holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
                }
                String error = applyConditionalAppearancePath(holder, tail, body, action,
                    languages, version, where + "/conditionalAppearance"); //$NON-NLS-1$
                if (error == null) group.setConditionalAppearance(holder);
                return error;
            case "outputParameters": //$NON-NLS-1$
                return "Group output-parameter node updates are not addressable separately. Update " //$NON-NLS-1$
                    + "the group body and pass outputParameters there."; //$NON-NLS-1$
            default:
                return "Grouping path segment '" + head + "' at '" + where //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is not authorable. Use items, groupFields, selection, filter, order, " //$NON-NLS-1$
                    + "conditionalAppearance, or outputParameters."; //$NON-NLS-1$
        }
    }

    private static String applyGroupingHolderPath(GroupingSettingsAccess owner, String member,
        List<String> path, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String where)
    {
        switch (member)
        {
            case "selection": //$NON-NLS-1$
                return applySelectionPath(owner, path, body, action, languages);
            case "filter": //$NON-NLS-1$
                return applyFilterPath(owner, path, body, action, languages);
            case "order": //$NON-NLS-1$
                return applyOrderPath(owner, path, body, action, languages);
            case "groupFields": //$NON-NLS-1$
                return applyGroupFieldsPath(owner, path, body, action, languages, where);
            default:
                return "Grouping path segment '" + member + "' at '" + where //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is not authorable. Use groupFields, selection, filter, or order."; //$NON-NLS-1$
        }
    }

    private static boolean isGroupingHolder(String member)
    {
        return "groupFields".equals(member) || "selection".equals(member) //$NON-NLS-1$ //$NON-NLS-2$
            || "filter".equals(member) || "order".equals(member); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String appendGroupings(List<StructureItem> items, JsonArray array, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String where)
    {
        for (int i = 0; i < array.size(); i++)
        {
            JsonObject body = arrayObject(array, i, where);
            if (body == null)
            {
                return arrayObjectError;
            }
            String error = appendGrouping(items, body, action, languages, version,
                where + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String appendGrouping(List<StructureItem> items, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String where)
    {
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact structure-item index at '" + where //$NON-NLS-1$
                + "'. Copy the grouping address from get; use upsert to append a new grouping."; //$NON-NLS-1$
        }
        String kind = optionalString(body, KEY_KIND, where);
        if (stringError != null)
        {
            return stringError;
        }
        if ("table".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            DataCompositionTable table = DcsFactory.eINSTANCE.createDataCompositionTable();
            items.add(table);
            return applyTable(table, body, action, languages, version, where, items);
        }
        if (DcsUnsupportedAuthoring.isChartKind(kind))
        {
            return DcsUnsupportedAuthoring.refusal(DcsUnsupportedAuthoring.CHART_CLASS, where);
        }
        if (kind != null && !"grouping".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return "Structure item kind '" + kind + "' at '" + where //$NON-NLS-1$ //$NON-NLS-2$
                + "' is invalid. Use kind='grouping' or kind='table'."; //$NON-NLS-1$
        }
        DataCompositionGroup group = DcsFactory.eINSTANCE.createDataCompositionGroup();
        items.add(group);
        return applyGrouping(group, body, action, languages, version, where, items);
    }

    private static String applyGrouping(DataCompositionGroup group, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String path,
        List<StructureItem> siblings)
    {
        String kindError = kindMustBe(body, path, "grouping"); //$NON-NLS-1$
        if (kindError != null)
        {
            return kindError;
        }
        String members = checkMembers(body, path, KEY_KIND, KEY_NAME, KEY_USE, "groupFields", //$NON-NLS-1$
            "selection", "filter", "order", "outputParameters", KEY_ITEMS, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            KEY_VIEW_MODE, KEY_USER_SETTING_ID, KEY_USER_SETTING_PRESENTATION,
            "itemsViewMode", "itemsUserSettingID", "itemsUserSettingPresentation", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            KEY_ID, KEY_GROUP_STATE);
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
        if (body.has(KEY_ID))
        {
            String id = optionalString(body, KEY_ID, path);
            if (stringError != null) return stringError;
            group.setId(id);
        }
        if (body.has(KEY_GROUP_STATE))
        {
            EnumResult<DataCompositionSettingsItemState> state = enumValue(body, KEY_GROUP_STATE,
                path, DataCompositionSettingsItemState.values());
            if (state.error != null) return state.error;
            group.setGroupState(state.value);
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
            String missing = missingHolderUpdate(action, group.getGroupFields(),
                path + ".groupFields"); //$NON-NLS-1$
            if (missing != null) return missing;
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
            String missing = missingHolderUpdate(action, group.getSelection(),
                path + ".selection"); //$NON-NLS-1$
            if (missing != null) return missing;
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
            String missing = missingHolderUpdate(action, group.getFilter(), path + ".filter"); //$NON-NLS-1$
            if (missing != null) return missing;
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
            String missing = missingHolderUpdate(action, group.getOrder(), path + ".order"); //$NON-NLS-1$
            if (missing != null) return missing;
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
            String missing = missingHolderUpdate(action, group.getOutputParameters(),
                path + ".outputParameters"); //$NON-NLS-1$
            if (missing != null) return missing;
            com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupOutputParameterValues holder =
                copy(group.getOutputParameters());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionGroupOutputParameterValues();
            }
            String error = applyParameters(holder, value, action, languages, version,
                OutputParameterCatalogue.GROUP, path + ".outputParameters"); //$NON-NLS-1$
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
            if (ACTION_REPLACE.equals(action))
            {
                group.getItems().clear();
            }
            return appendGroupings(group.getItems(), items, action, languages, version,
                path + ".items"); //$NON-NLS-1$
        }
        return null;
    }

    // ---- tables -----------------------------------------------------------------------------

    private static String applyTable(DataCompositionTable table, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String path,
        List<StructureItem> siblings)
    {
        String kindError = kindMustBe(body, path, "table"); //$NON-NLS-1$
        if (kindError != null)
        {
            return kindError;
        }
        String members = checkMembers(body, path, KEY_KIND, KEY_NAME, KEY_USE, "rows", "columns", //$NON-NLS-1$ //$NON-NLS-2$
            "selection", "conditionalAppearance", "outputParameters", KEY_VIEW_MODE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            KEY_USER_SETTING_ID, KEY_USER_SETTING_PRESENTATION, "rowsViewMode", //$NON-NLS-1$
            "rowsUserSettingID", "rowsUserSettingPresentation", "columnsViewMode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "columnsUserSettingID", "columnsUserSettingPresentation", KEY_ID); //$NON-NLS-1$ //$NON-NLS-2$
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
                if (sibling != table && sibling instanceof DataCompositionTable
                    && name.equals(((DataCompositionTable)sibling).getName()))
                {
                    return "Table name '" + name + "' collides with a sibling at '" + path //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Choose a unique name or update the existing table address."; //$NON-NLS-1$
                }
            }
            table.setName(name);
        }
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null)
            {
                return booleanError;
            }
            table.setUse(use.booleanValue());
        }
        if (body.has(KEY_ID))
        {
            String id = optionalString(body, KEY_ID, path);
            if (stringError != null) return stringError;
            table.setId(id);
        }
        String scaffold = applyTableScaffold(table, body, languages, path);
        if (scaffold != null)
        {
            return scaffold;
        }
        String error = applyTableGroupsMember(table.getRows(), body, "rows", action, languages, //$NON-NLS-1$
            version, path);
        if (error == null)
        {
            error = applyTableGroupsMember(table.getColumns(), body, "columns", action, languages, //$NON-NLS-1$
                version, path);
        }
        if (error != null)
        {
            return error;
        }
        if (body.has("selection")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "selection", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            String missing = missingHolderUpdate(action, table.getSelection(),
                path + ".selection"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionSelectedFields holder = ACTION_REPLACE.equals(action) ? null
                : copy(table.getSelection());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionSelectedFields();
            }
            error = applySelection(holder, value, action, languages, path + ".selection"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            table.setSelection(holder);
        }
        if (body.has("conditionalAppearance")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "conditionalAppearance", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            String missing = missingHolderUpdate(action, table.getConditionalAppearance(),
                path + ".conditionalAppearance"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionConditionalAppearance holder = ACTION_REPLACE.equals(action) ? null
                : copy(table.getConditionalAppearance());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            }
            error = applyConditionalAppearance(holder, value, action, languages, version,
                path + ".conditionalAppearance"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            table.setConditionalAppearance(holder);
        }
        if (body.has("outputParameters")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "outputParameters", path); //$NON-NLS-1$
            if (value == null)
            {
                return objectError;
            }
            String missing = missingHolderUpdate(action, table.getOutputParameters(),
                path + ".outputParameters"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionTableOutputParameterValues holder = ACTION_REPLACE.equals(action) ? null
                : copy(table.getOutputParameters());
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionTableOutputParameterValues();
            }
            error = applyParameters(holder, value, action, languages, version,
                OutputParameterCatalogue.TABLE, path + ".outputParameters"); //$NON-NLS-1$
            if (error != null)
            {
                return error;
            }
            table.setOutputParameters(holder);
        }
        return null;
    }

    private static String applyTableChildPath(DataCompositionTable table, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version,
        String where)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        String relativeAddress = where.startsWith("settings.") //$NON-NLS-1$
            ? where.substring("settings.".length()) : where; //$NON-NLS-1$
        if ("rows".equals(head) || "columns".equals(head)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return applyTableGroupsPath("rows".equals(head) ? table.getRows() : table.getColumns(), //$NON-NLS-1$
                tail, body, action, languages, version, where + "/" + head); //$NON-NLS-1$
        }
        if ("selection".equals(head)) //$NON-NLS-1$
        {
            // A holder is not a collection: it carries viewMode, userSettingID and a presentation
            // of its own alongside its items. The address ends AT it, so replace starts from
            // nothing rather than from a copy - otherwise clearing the items still left the
            // holder's own scalars set. Same idiom the settings-level paths already use.
            DataCompositionSelectedFields existing = table.getSelection();
            if (ACTION_UPDATE.equals(action) && existing == null)
            {
                return "action='update' cannot find selection at relative address '" //$NON-NLS-1$
                    + relativeAddress
                    + "/selection'. Use action='upsert' to create it."; //$NON-NLS-1$
            }
            DataCompositionSelectedFields holder = ACTION_REPLACE.equals(action) && tail.isEmpty() ? null
                : copy(existing);
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionSelectedFields();
            String error = applySelectionPath(holder, tail, body, action, languages,
                where + "/selection"); //$NON-NLS-1$
            if (error == null) table.setSelection(holder);
            return error;
        }
        if ("conditionalAppearance".equals(head)) //$NON-NLS-1$
        {
            DataCompositionConditionalAppearance existing = table.getConditionalAppearance();
            if (ACTION_UPDATE.equals(action) && existing == null)
            {
                return "action='update' cannot find conditionalAppearance at relative address '" //$NON-NLS-1$
                    + relativeAddress
                    + "/conditionalAppearance'. Use action='upsert' to create it."; //$NON-NLS-1$
            }
            DataCompositionConditionalAppearance holder = ACTION_REPLACE.equals(action) && tail.isEmpty() ? null
                : copy(existing);
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            String error = applyConditionalAppearancePath(holder, tail, body, action, languages, version,
                where + "/conditionalAppearance"); //$NON-NLS-1$
            if (error == null) table.setConditionalAppearance(holder);
            return error;
        }
        if ("outputParameters".equals(head)) //$NON-NLS-1$
        {
            DataCompositionTableOutputParameterValues existing = table.getOutputParameters();
            if (ACTION_UPDATE.equals(action) && existing == null)
            {
                return "action='update' cannot find outputParameters at relative address '" //$NON-NLS-1$
                    + relativeAddress
                    + "/outputParameters'. Use action='upsert' to create them."; //$NON-NLS-1$
            }
            DataCompositionTableOutputParameterValues holder = ACTION_REPLACE.equals(action)
                && tail.isEmpty() ? null : copy(existing);
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionTableOutputParameterValues();
            }
            String error = applyParameterValuesPath(holder, tail, body, action, languages, version,
                OutputParameterCatalogue.TABLE, where + "/outputParameters"); //$NON-NLS-1$
            if (error == null) table.setOutputParameters(holder);
            return error;
        }
        return "Table path segment '" + head + "' at '" + where //$NON-NLS-1$ //$NON-NLS-2$
            + "' is not authorable. Use rows, columns, selection, conditionalAppearance, or " //$NON-NLS-1$
            + "outputParameters."; //$NON-NLS-1$
    }

    private static String applyTableGroupsMember(List<DataCompositionTableGroup> groups, JsonObject body,
        String member, String action, DcsPresentationParser.LanguageContext languages, Version version,
        String path)
    {
        if (!body.has(member))
        {
            return null;
        }
        JsonArray array = array(body, member, path);
        if (array == null)
        {
            return arrayError;
        }
        if (ACTION_REPLACE.equals(action))
        {
            groups.clear();
        }
        return appendTableGroups(groups, array, action, languages, version, path + "." + member); //$NON-NLS-1$
    }

    private static String applyTableGroupsPath(List<DataCompositionTableGroup> groups, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version,
        String where)
    {
        if (path.isEmpty())
        {
            String members = checkMembers(body, where, KEY_ITEMS);
            if (members != null) return members;
            JsonArray array = array(body, KEY_ITEMS, where);
            if (array == null) return arrayError;
            if (ACTION_REPLACE.equals(action)) groups.clear();
            return appendTableGroups(groups, array, action, languages, version, where);
        }
        int selected = index(path.get(0), groups.size(), where);
        if (indexError != null) return indexError;
        DataCompositionTableGroup group = groups.get(selected);
        if (path.size() == 1)
        {
            if (ACTION_REPLACE.equals(action))
            {
                group = DcsFactory.eINSTANCE.createDataCompositionTableGroup();
                groups.set(selected, group);
            }
            return applyTableGroup(group, body, action, languages, version,
                where + "/" + path.get(0)); //$NON-NLS-1$
        }
        String member = path.get(1);
        if (isGroupingHolder(member))
        {
            return applyGroupingHolderPath(new TableGroupSettingsAccess(group), member,
                path.subList(2, path.size()), body, action, languages,
                where + "/" + path.get(0)); //$NON-NLS-1$
        }
        if ("conditionalAppearance".equals(member)) //$NON-NLS-1$
        {
            List<String> tail = path.subList(2, path.size());
            DataCompositionConditionalAppearance existing = group.getConditionalAppearance();
            if (ACTION_UPDATE.equals(action) && existing == null)
            {
                return "action='update' cannot find conditionalAppearance at '" + where + "/" //$NON-NLS-1$ //$NON-NLS-2$
                    + path.get(0) + "/conditionalAppearance'. Use action='upsert' to create it."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            DataCompositionConditionalAppearance holder = ACTION_REPLACE.equals(action)
                && tail.isEmpty() ? null : copy(existing);
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            }
            String error = applyConditionalAppearancePath(holder, tail, body, action, languages,
                version, where + "/" + path.get(0) + "/conditionalAppearance"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error == null) group.setConditionalAppearance(holder);
            return error;
        }
        if ("outputParameters".equals(member)) //$NON-NLS-1$
        {
            List<String> tail = path.subList(2, path.size());
            DataCompositionTableGroupOutputParameterValues existing = group.getOutputParameters();
            if (ACTION_UPDATE.equals(action) && existing == null)
            {
                return "action='update' cannot find outputParameters at '" + where + "/" //$NON-NLS-1$ //$NON-NLS-2$
                    + path.get(0) + "/outputParameters'. Use action='upsert' to create them."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            DataCompositionTableGroupOutputParameterValues holder = ACTION_REPLACE.equals(action)
                && tail.isEmpty() ? null : copy(existing);
            if (holder == null)
            {
                holder = DcsFactory.eINSTANCE.createDataCompositionTableGroupOutputParameterValues();
            }
            String error = applyParameterValuesPath(holder, tail, body, action, languages, version,
                OutputParameterCatalogue.TABLE_GROUP,
                where + "/" + path.get(0) + "/outputParameters"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error == null) group.setOutputParameters(holder);
            return error;
        }
        if (!KEY_ITEMS.equals(member))
        {
            return "Table-axis path '" + where + "/" + String.join("/", path) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "' is invalid. Use items, groupFields, selection, filter, order, " //$NON-NLS-1$
                + "conditionalAppearance, or outputParameters."; //$NON-NLS-1$
        }
        return applyTableGroupsPath(group.getItems(), path.subList(2, path.size()), body, action,
            languages, version, where + "/" + path.get(0) + "/items"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String appendTableGroups(List<DataCompositionTableGroup> groups, JsonArray array,
        String action, DcsPresentationParser.LanguageContext languages, Version version, String where)
    {
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact table-axis group index at '" + where //$NON-NLS-1$
                + "'. Use upsert to append a group."; //$NON-NLS-1$
        }
        for (int i = 0; i < array.size(); i++)
        {
            JsonObject item = arrayObject(array, i, where);
            if (item == null) return arrayObjectError;
            DataCompositionTableGroup group = DcsFactory.eINSTANCE.createDataCompositionTableGroup();
            String error = applyTableGroup(group, item, action, languages, version,
                where + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
            groups.add(group);
        }
        return null;
    }

    private static String applyTableGroup(DataCompositionTableGroup group, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String path)
    {
        String members = checkMembers(body, path, KEY_NAME, KEY_USE, "groupFields", "filter", //$NON-NLS-1$ //$NON-NLS-2$
            "order", "selection", "conditionalAppearance", "outputParameters", KEY_ITEMS, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            KEY_VIEW_MODE, KEY_USER_SETTING_ID, KEY_USER_SETTING_PRESENTATION, "itemsViewMode", //$NON-NLS-1$
            "itemsUserSettingID", "itemsUserSettingPresentation"); //$NON-NLS-1$ //$NON-NLS-2$
        if (members != null) return members;
        if (body.has(KEY_NAME))
        {
            String name = requiredString(body, KEY_NAME, path);
            if (stringError != null) return stringError;
            group.setName(name);
        }
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null) return booleanError;
            group.setUse(use.booleanValue());
        }
        String scaffold = applyTableGroupScaffold(group, body, languages, path);
        if (scaffold != null) return scaffold;
        if (body.has("groupFields")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "groupFields", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, group.getGroupFields(),
                path + ".groupFields"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionGroupFields holder = ACTION_REPLACE.equals(action) ? null : copy(group.getGroupFields());
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionGroupFields();
            String error = applyGroupFields(holder, value, action, path + ".groupFields"); //$NON-NLS-1$
            if (error != null) return error;
            group.setGroupFields(holder);
        }
        if (body.has("filter")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "filter", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, group.getFilter(), path + ".filter"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionFilter holder = ACTION_REPLACE.equals(action) ? null : copy(group.getFilter());
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionFilter();
            String error = applyFilter(holder, value, action, languages, path + ".filter"); //$NON-NLS-1$
            if (error != null) return error;
            group.setFilter(holder);
        }
        if (body.has("order")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "order", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, group.getOrder(), path + ".order"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionOrder holder = ACTION_REPLACE.equals(action) ? null : copy(group.getOrder());
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionOrder();
            String error = applyOrder(holder, value, action, languages, path + ".order"); //$NON-NLS-1$
            if (error != null) return error;
            group.setOrder(holder);
        }
        if (body.has("selection")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "selection", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, group.getSelection(),
                path + ".selection"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionSelectedFields holder = ACTION_REPLACE.equals(action) ? null : copy(group.getSelection());
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionSelectedFields();
            String error = applySelection(holder, value, action, languages, path + ".selection"); //$NON-NLS-1$
            if (error != null) return error;
            group.setSelection(holder);
        }
        if (body.has("conditionalAppearance")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "conditionalAppearance", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, group.getConditionalAppearance(),
                path + ".conditionalAppearance"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionConditionalAppearance holder = ACTION_REPLACE.equals(action) ? null
                : copy(group.getConditionalAppearance());
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            String error = applyConditionalAppearance(holder, value, action, languages, version,
                path + ".conditionalAppearance"); //$NON-NLS-1$
            if (error != null) return error;
            group.setConditionalAppearance(holder);
        }
        if (body.has("outputParameters")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "outputParameters", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, group.getOutputParameters(),
                path + ".outputParameters"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionTableGroupOutputParameterValues holder = ACTION_REPLACE.equals(action) ? null
                : copy(group.getOutputParameters());
            if (holder == null) holder = DcsFactory.eINSTANCE.createDataCompositionTableGroupOutputParameterValues();
            String error = applyParameters(holder, value, action, languages, version,
                OutputParameterCatalogue.TABLE_GROUP, path + ".outputParameters"); //$NON-NLS-1$
            if (error != null) return error;
            group.setOutputParameters(holder);
        }
        if (body.has(KEY_ITEMS))
        {
            JsonArray array = array(body, KEY_ITEMS, path);
            if (array == null) return arrayError;
            if (ACTION_REPLACE.equals(action)) group.getItems().clear();
            return appendTableGroups(group.getItems(), array, action, languages, version, path + ".items"); //$NON-NLS-1$
        }
        return null;
    }

    private static String applyGroupFieldsPath(GroupingSettingsAccess owner, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String where)
    {
        // A holder addressed directly starts empty on replace, like every other holder: copying it
        // meant the old group fields stayed and the replacement was appended behind them.
        DataCompositionGroupFields fields = ACTION_REPLACE.equals(action) && path.isEmpty() ? null
            : copy(owner.groupFields());
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
            DataCompositionGroupField field = ACTION_REPLACE.equals(action)
                ? DcsFactory.eINSTANCE.createDataCompositionGroupField()
                : (DataCompositionGroupField)item;
            if (field != item)
            {
                fields.getItems().set(index, field);
            }
            error = applyGroupField(field, body,
                where + "/groupFields/items/" + path.get(1)); //$NON-NLS-1$
        }
        else
        {
            return "Group-fields address at '" + where //$NON-NLS-1$
                + "' must end at groupFields or groupFields/items/<index>. Copy it from get."; //$NON-NLS-1$
        }
        if (error == null)
        {
            owner.groupFields(fields);
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
            String error = appendGroupField(fields.getItems(), item, action,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
        }
        return null;
    }

    private static String appendGroupField(List<GroupItem> items, JsonObject body, String action,
        String path)
    {
        String updateError = updateAppendError(action, "group-field item", path); //$NON-NLS-1$
        if (updateError != null) return updateError;
        DataCompositionGroupField field = DcsFactory.eINSTANCE.createDataCompositionGroupField();
        String error = applyGroupField(field, body, path);
        if (error == null) items.add(field);
        return error;
    }

    private static String updateAppendError(String action, String itemType, String path)
    {
        if (!ACTION_UPDATE.equals(action)) return null;
        return "action='update' needs an exact " + itemType + " index at '" + path //$NON-NLS-1$ //$NON-NLS-2$
            + "'. Copy the item address from get; use upsert to append a new item."; //$NON-NLS-1$
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
        DataCompositionSelectedFields holder = ACTION_REPLACE.equals(action) && path.isEmpty()
            ? null : copy(owner.selection());
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
            // The address ends AT the collection, so replace replaces it rather than appending to
            // it - otherwise every existing item survived and the replacement queued up behind it.
            if (ACTION_REPLACE.equals(action))
            {
                items.clear();
            }
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
            String at = where + "/items/" + path.get(1); //$NON-NLS-1$
            if (ACTION_REPLACE.equals(action))
            {
                // Authoritative replacement resets omitted values and clears omitted collections,
                // so the item is REBUILT from the body instead of being patched over the existing
                // one. Patching let a title, a use flag or a nested group's items survive a
                // replace that never mentioned them - an update wearing a replace label.
                SelectedItem fresh = newSelectedItem(body, at);
                if (fresh == null)
                {
                    return selectedKindError;
                }
                String rebuilt = applySelectedItem(fresh, body, action, languages, at);
                if (rebuilt != null)
                {
                    return rebuilt;
                }
                items.set(selected, fresh);
                return null;
            }
            return applySelectedItem(item, body, action, languages, at);
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
        if (ACTION_REPLACE.equals(action)) selection.getItems().clear();
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
        String updateError = updateAppendError(action, "selection item", path); //$NON-NLS-1$
        if (updateError != null) return updateError;
        SelectedItem item = newSelectedItem(body, path);
        if (item == null)
        {
            return selectedKindError;
        }
        String error = applySelectedItem(item, body, action, languages, path);
        if (error == null)
        {
            items.add(item);
        }
        return error;
    }

    /**
     * A fresh selection item of the kind the body names, or {@code null} with
     * {@link #selectedKindError} set. Shared by append and by an authoritative replace so both
     * build the same shapes from the same kind vocabulary.
     */
    private static SelectedItem newSelectedItem(JsonObject body, String path)
    {
        selectedKindError = null;
        String kind = optionalString(body, KEY_KIND, path);
        if (stringError != null)
        {
            selectedKindError = stringError;
            return null;
        }
        if ("group".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionSelectedFieldGroup();
        }
        if ("auto".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionAutoSelectedField();
        }
        if (kind == null || "field".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionSelectedField();
        }
        selectedKindError = "Selection kind '" + kind //$NON-NLS-1$
            + "' is invalid. Use one of: field, group, auto."; //$NON-NLS-1$
        return null;
    }

    private static String applySelectedItem(SelectedItem item, JsonObject body, String action,
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
                    String error = appendSelected(selectedGroup.getItems(), child, action,
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
        DataCompositionFilter holder = ACTION_REPLACE.equals(action) && path.isEmpty()
            ? null : copy(owner.filter());
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
            if (ACTION_REPLACE.equals(action))
            {
                items.clear();
            }
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
            String at = where + "/items/" + path.get(1); //$NON-NLS-1$
            if (ACTION_REPLACE.equals(action))
            {
                // Rebuilt, not patched: otherwise comparisonType, right, use and a group's nested
                // items survive a replace that never mentioned them.
                String kind = optionalString(body, KEY_KIND, at);
                if (stringError != null)
                {
                    return stringError;
                }
                FilterItem fresh = newFilterItem(kind, at);
                if (fresh == null)
                {
                    return filterKindError;
                }
                String rebuilt = applyFilterItem(fresh, body, action, languages, at);
                if (rebuilt != null)
                {
                    return rebuilt;
                }
                items.set(selected, fresh);
                return null;
            }
            return applyFilterItem(item, body, action, languages, at);
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
        if (ACTION_REPLACE.equals(action)) filter.getItems().clear();
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
        String updateError = updateAppendError(action, "filter item", path); //$NON-NLS-1$
        if (updateError != null) return updateError;
        String kind = optionalString(body, KEY_KIND, path);
        if (stringError != null)
        {
            return stringError;
        }
        FilterItem item = newFilterItem(kind, path);
        if (item == null)
        {
            return filterKindError;
        }
        String error = applyFilterItem(item, body, action, languages, path);
        if (error == null)
        {
            items.add(item);
        }
        return error;
    }

    /**
     * A fresh filter item of the named kind, or {@code null} with {@link #filterKindError} set.
     * Shared by append and by an authoritative replace so both build the same shapes.
     */
    private static FilterItem newFilterItem(String kind, String path)
    {
        filterKindError = null;
        if ("group".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionFilterItemGroup();
        }
        if (kind == null || "item".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionFilterItem();
        }
        filterKindError = "Filter kind '" + kind + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
            + "' is invalid. Use one of: item, group."; //$NON-NLS-1$
        return null;
    }

    private static String applyFilterItem(FilterItem item, JsonObject body, String action,
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
                    String error = appendFilter(filterGroup.getItems(), child, action,
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
        DataCompositionOrder holder = ACTION_REPLACE.equals(action) && path.isEmpty()
            ? null : copy(owner.order());
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
            if (ACTION_REPLACE.equals(action))
            {
                holder.getItems().clear();
            }
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
        String at = where + "/items/" + path.get(1); //$NON-NLS-1$
        if (ACTION_REPLACE.equals(action))
        {
            // Rebuilt, not patched: field, orderType, use and viewMode must not survive a replace
            // that never named them, and replacing an auto item with a normal one must actually
            // change the item's CLASS rather than patch the old instance.
            String kind = optionalString(body, KEY_KIND, at);
            if (stringError != null)
            {
                return stringError;
            }
            OrderItem fresh = newOrderItem(kind, at);
            if (fresh == null)
            {
                return orderKindError;
            }
            String rebuilt = applyOrderItem(fresh, body, at);
            if (rebuilt != null)
            {
                return rebuilt;
            }
            holder.getItems().set(selected, fresh);
            return null;
        }
        return applyOrderItem(holder.getItems().get(selected), body, at);
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
        if (ACTION_REPLACE.equals(action)) order.getItems().clear();
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
        OrderItem item = newOrderItem(kind, path);
        if (item == null)
        {
            return orderKindError;
        }
        String error = applyOrderItem(item, body, path);
        if (error == null)
        {
            items.add(item);
        }
        return error;
    }

    /**
     * A fresh order item of the named kind, or {@code null} with {@link #orderKindError} set.
     * Shared by append and by an authoritative replace so both build the same shapes.
     */
    private static OrderItem newOrderItem(String kind, String path)
    {
        orderKindError = null;
        if ("auto".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionAutoOrderItem();
        }
        if (kind == null || "item".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionOrderItem();
        }
        orderKindError = "Order kind '" + kind + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
            + "' is invalid. Use one of: item, auto."; //$NON-NLS-1$
        return null;
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
        DcsPresentationParser.LanguageContext languages, Version version)
    {
        DataCompositionConditionalAppearance holder = ACTION_REPLACE.equals(action) && path.isEmpty()
            ? null : copy(settings.getConditionalAppearance());
        if (holder == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find conditionalAppearance. Use action='upsert' " //$NON-NLS-1$
                    + "to create it."; //$NON-NLS-1$
            }
            holder = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        }
        String error = applyConditionalAppearancePath(holder, path, body, action, languages, version,
            "conditionalAppearance"); //$NON-NLS-1$
        if (error == null)
        {
            settings.setConditionalAppearance(holder);
        }
        return error;
    }

    private static String applyConditionalAppearancePath(DataCompositionConditionalAppearance holder,
        List<String> path, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String where)
    {
        if (path.isEmpty())
        {
            return applyConditionalAppearance(holder, body, action, languages, version, where);
        }
        if (path.size() >= 2 && KEY_ITEMS.equals(path.get(0)))
        {
            int selected = index(path.get(1), holder.getItems().size(), where + "/items"); //$NON-NLS-1$
            if (indexError != null)
            {
                return indexError;
            }
            DataCompositionConditionalAppearanceItem item = holder.getItems().get(selected);
            if (path.size() == 2)
            {
                if (ACTION_REPLACE.equals(action))
                {
                    item = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearanceItem();
                    holder.getItems().set(selected, item);
                }
                return applyConditionalAppearanceItem(item, body, action,
                    languages, version, where + "/items/" + path.get(1)); //$NON-NLS-1$
            }
            else if ("selection".equals(path.get(2))) //$NON-NLS-1$
            {
                // A holder-addressed replace is authoritative: starting from the retained holder
                // let omitted items survive, unlike every sibling holder replacement.
                DataCompositionAppearanceFields fields = ACTION_REPLACE.equals(action)
                    && path.size() == 3 ? null : copy(item.getSelection());
                if (fields == null)
                {
                    if (ACTION_UPDATE.equals(action))
                    {
                        return "action='update' cannot find conditional-appearance selection at '" //$NON-NLS-1$
                            + where + "/items/" + path.get(1) //$NON-NLS-1$
                            + "/selection'. Use action='upsert' to create it."; //$NON-NLS-1$
                    }
                    fields = DcsFactory.eINSTANCE.createDataCompositionAppearanceFields();
                }
                String error = applyAppearanceFieldsPath(fields, path.subList(3, path.size()), body,
                    action, where + "/items/" + path.get(1) + "/selection"); //$NON-NLS-1$ //$NON-NLS-2$
                if (error == null) item.setSelection(fields);
                return error;
            }
            else if ("filter".equals(path.get(2))) //$NON-NLS-1$
            {
                DataCompositionFilter filter = ACTION_REPLACE.equals(action) && path.size() == 3
                    ? null : copy(item.getFilter());
                if (filter == null)
                {
                    if (ACTION_UPDATE.equals(action))
                    {
                        return "action='update' cannot find conditional-appearance filter at '" //$NON-NLS-1$
                            + where + "/items/" + path.get(1) //$NON-NLS-1$
                            + "/filter'. Use action='upsert' to create it."; //$NON-NLS-1$
                    }
                    filter = DcsFactory.eINSTANCE.createDataCompositionFilter();
                }
                String error = applyFilterPath(filter, path.subList(3, path.size()), body, action,
                    languages, where + "/items/" + path.get(1) + "/filter"); //$NON-NLS-1$ //$NON-NLS-2$
                if (error == null) item.setFilter(filter);
                return error;
            }
            else
            {
                return "Conditional-appearance item address must continue through selection/items/" //$NON-NLS-1$
                    + "<index> or filter/items/<index>. Copy the canonical address from get."; //$NON-NLS-1$
            }
        }
        return "Conditional-appearance address must end at " + where + ", " + where //$NON-NLS-1$ //$NON-NLS-2$
            + "/items/<index>, or an exact selection/items/<index> or filter/items/<index> " //$NON-NLS-1$
            + "below a rule. Copy the canonical address from get."; //$NON-NLS-1$
    }

    private static String applyConditionalAppearance(DataCompositionConditionalAppearance holder,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version,
        String path)
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
            if (ACTION_REPLACE.equals(action))
            {
                holder.getItems().clear();
            }
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' needs an exact conditional-appearance item address at '" //$NON-NLS-1$
                    + path + "'. Use upsert to append a rule."; //$NON-NLS-1$
            }
            for (int i = 0; i < items.size(); i++)
            {
                JsonObject itemBody = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
                if (itemBody == null)
                {
                    return arrayObjectError;
                }
                DataCompositionConditionalAppearanceItem item =
                    DcsFactory.eINSTANCE.createDataCompositionConditionalAppearanceItem();
                String error = applyConditionalAppearanceItem(item, itemBody, action, languages,
                    version, path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                if (error != null)
                {
                    return error;
                }
                holder.getItems().add(item);
            }
        }
        return applyHolderScaffold(holder, body, languages, path);
    }

    private static String applyConditionalAppearanceItem(DataCompositionConditionalAppearanceItem item,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version,
        String path)
    {
        String members = checkMembers(body, path, KEY_USE, "selection", "filter", "appearance", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            KEY_PRESENTATION, KEY_VIEW_MODE, KEY_USER_SETTING_ID, KEY_USER_SETTING_PRESENTATION,
            "useInGroup", "useInHierarchicalGroup", "useInOverall", "useInFieldsHeader", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "useInHeader", "useInParameters", "useInFilter", "useInResourceFieldsHeader", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "useInOverallHeader", "useInOverallResourceFieldsHeader"); //$NON-NLS-1$ //$NON-NLS-2$
        if (members != null) return members;
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null) return booleanError;
            item.setUse(use.booleanValue());
        }
        if (body.has("selection")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "selection", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, item.getSelection(),
                path + ".selection"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionAppearanceFields fields = ACTION_REPLACE.equals(action) ? null
                : copy(item.getSelection());
            if (fields == null) fields = DcsFactory.eINSTANCE.createDataCompositionAppearanceFields();
            String error = applyAppearanceFields(fields, value, action, path + ".selection"); //$NON-NLS-1$
            if (error != null) return error;
            item.setSelection(fields);
        }
        if (body.has("filter")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "filter", path); //$NON-NLS-1$
            if (value == null) return objectError;
            String missing = missingHolderUpdate(action, item.getFilter(), path + ".filter"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionFilter filter = ACTION_REPLACE.equals(action) ? null : copy(item.getFilter());
            if (filter == null) filter = DcsFactory.eINSTANCE.createDataCompositionFilter();
            String error = applyFilter(filter, value, action, languages, path + ".filter"); //$NON-NLS-1$
            if (error != null) return error;
            item.setFilter(filter);
        }
        if (body.has("appearance")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "appearance", path); //$NON-NLS-1$
            if (value == null) return objectError;
            DataCompositionAppearance current = ACTION_REPLACE.equals(action)
                ? null : item.getAppearance();
            AppearanceResult appearance = appearance(value, current, languages, version,
                path + ".appearance"); //$NON-NLS-1$
            if (appearance.error != null) return appearance.error;
            item.setAppearance(appearance.value);
        }
        if (body.has(KEY_PRESENTATION))
        {
            PresentationResult value = presentation(body.get(KEY_PRESENTATION), languages,
                path + ".presentation"); //$NON-NLS-1$
            if (value.error != null) return value.error;
            item.setPresentation(value.value);
        }
        String scaffold = applyConditionalAppearanceItemScaffold(item, body, languages, path);
        if (scaffold != null) return scaffold;
        String[] flags = {"useInGroup", "useInHierarchicalGroup", "useInOverall", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "useInFieldsHeader", "useInHeader", "useInParameters", "useInFilter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "useInResourceFieldsHeader", "useInOverallHeader", //$NON-NLS-1$ //$NON-NLS-2$
            "useInOverallResourceFieldsHeader"}; //$NON-NLS-1$
        for (String flag : flags)
        {
            if (!body.has(flag)) continue;
            EnumResult<DataCompositionConditionalAppearanceUse> value = enumValue(body, flag, path,
                DataCompositionConditionalAppearanceUse.values());
            if (value.error != null) return value.error;
            setAppearanceUse(item, flag, value.value);
        }
        return null;
    }

    private static String applyAppearanceFields(DataCompositionAppearanceFields fields, JsonObject body,
        String action, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS);
        if (members != null) return members;
        if (!body.has(KEY_ITEMS)) return null;
        JsonArray array = array(body, KEY_ITEMS, path);
        if (array == null) return arrayError;
        if (ACTION_REPLACE.equals(action)) fields.getItems().clear();
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact appearance-field address. Use upsert to append " //$NON-NLS-1$
                + "a selection field or replace the selection holder."; //$NON-NLS-1$
        }
        for (int i = 0; i < array.size(); i++)
        {
            JsonObject itemBody = arrayObject(array, i, path + ".items"); //$NON-NLS-1$
            if (itemBody == null) return arrayObjectError;
            DataCompositionAppearanceField field = DcsFactory.eINSTANCE.createDataCompositionAppearanceField();
            String error = applyAppearanceField(field, itemBody,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
            fields.getItems().add(field);
        }
        return null;
    }

    private static String applyAppearanceFieldsPath(DataCompositionAppearanceFields fields,
        List<String> path, JsonObject body, String action, String holderPath)
    {
        if (path.isEmpty()) return applyAppearanceFields(fields, body, action, holderPath);
        if (path.size() != 2 || !KEY_ITEMS.equals(path.get(0)))
        {
            return "Appearance-field address must end at '" + holderPath //$NON-NLS-1$
                + "' or '" + holderPath + "/items/<index>'. Copy the canonical address from get."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        int selected = index(path.get(1), fields.getItems().size(), holderPath + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        DataCompositionAppearanceField field = fields.getItems().get(selected);
        if (ACTION_REPLACE.equals(action))
        {
            field = DcsFactory.eINSTANCE.createDataCompositionAppearanceField();
            fields.getItems().set(selected, field);
        }
        return applyAppearanceField(field, body, holderPath + "/items/" + path.get(1)); //$NON-NLS-1$
    }

    private static String applyAppearanceField(DataCompositionAppearanceField field, JsonObject body,
        String path)
    {
        String members = checkMembers(body, path, KEY_USE, KEY_FIELD);
        if (members != null) return members;
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null) return booleanError;
            field.setUse(use.booleanValue());
        }
        if (body.has(KEY_FIELD))
        {
            FieldResult value = fieldValue(body.get(KEY_FIELD), path + ".field"); //$NON-NLS-1$
            if (value.error != null) return value.error;
            field.setField(value.value);
        }
        return null;
    }

    private static AppearanceResult appearance(JsonObject body, DataCompositionAppearance current,
        DcsPresentationParser.LanguageContext languages, Version version, String path)
    {
        String language = languages == null ? "en" : languages.resolvedCode(); //$NON-NLS-1$
        try
        {
            AppearanceCatalogue kind = APPEARANCE_CATALOGUE.get();
            DcsAvailableParameterCollection available = appearanceParameters(
                kind == null ? AppearanceCatalogue.SCHEMA : kind, version, language);
            return appearance(body, current, languages, available, version, path);
        }
        catch (DcsPathException | RuntimeException e)
        {
            return AppearanceResult.failure("Could not load the typed appearance model for platform " //$NON-NLS-1$
                + version + " at '" + path + "': " + e.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Verify the project platform version and retry."); //$NON-NLS-1$
        }
    }

    /** Shared schema/settings seam for the platform-typed, merge-on-update appearance builder. */
    static AppearanceResult buildAppearance(JsonObject body, DataCompositionAppearance current,
        DcsPresentationParser.LanguageContext languages, Version version,
        StyleValueBuilder.NamedColorResolver namedColors, String path)
    {
        return withNamedColors(namedColors,
            () -> appearance(body, current, languages, version, path));
    }

    /** Headless unit seam: production supplies the same bilingual collection from EDT's catalogue. */
    static AppearanceResult buildAppearanceForTest(JsonObject body, DataCompositionAppearance current,
        DcsPresentationParser.LanguageContext languages, DcsAvailableParameterCollection available)
    {
        return appearance(body, current, languages, available, Version.LATEST, "appearance"); //$NON-NLS-1$
    }

    private static AppearanceResult appearance(JsonObject body, DataCompositionAppearance current,
        DcsPresentationParser.LanguageContext languages, DcsAvailableParameterCollection available,
        Version version, String path)
    {
        // Update and exact-target upsert are PATCH operations. Start from a detached copy so an
        // omitted appearance key survives and a later conversion error cannot mutate the input.
        // Replace deliberately passes no current value and therefore retains its clearing contract.
        DataCompositionAppearance result = copy(current);
        if (result == null)
        {
            result = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                .createDataCompositionAppearance();
        }
        for (String key : body.keySet())
        {
            DcsAvailableParameter parameter = available.findItem(key);
            if (parameter == null)
            {
                return AppearanceResult.failure("Unknown appearance key '" + key + "' at '" //$NON-NLS-1$ //$NON-NLS-2$
                    + path + "'. Use one of the typed keys for platform " + version + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + parameterKeys(available) + ". Remove '" + key + "' or correct its spelling."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            AppearanceParameterSpec spec = appearanceParameterSpec(body.get(key),
                path + "." + key); //$NON-NLS-1$
            if (spec.error != null) return AppearanceResult.failure(spec.error);
            ValueResult mapped = typedParameterValue(spec.value, parameter.defValue(), languages,
                path + "." + key); //$NON-NLS-1$
            if (mapped.error != null) return AppearanceResult.failure(mapped.error);
            DataCompositionParameterValue item =
                com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                    .createDataCompositionParameterValue();
            item.setUse(spec.use);
            DataCompositionParameter name =
                com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionParameter();
            name.setValue(parameterName(parameter, languages));
            item.setParameter(name);
            item.getValues().add(mapped.value);
            putAppearanceParameter(result, item, parameter, available);
        }
        return AppearanceResult.success(result);
    }

    /** Extracts the optional per-parameter use flag without passing it to the typed value builder. */
    private static AppearanceParameterSpec appearanceParameterSpec(JsonElement raw, String path)
    {
        if (raw == null || !raw.isJsonObject() || !raw.getAsJsonObject().has(KEY_USE))
        {
            return AppearanceParameterSpec.success(raw, true);
        }
        JsonObject supplied = raw.getAsJsonObject();
        Boolean use = bool(supplied, KEY_USE, path);
        if (use == null) return AppearanceParameterSpec.failure(booleanError);
        JsonObject value = supplied.deepCopy();
        value.remove(KEY_USE);
        if (value.size() == 0)
        {
            return AppearanceParameterSpec.failure("Appearance parameter at '" + path //$NON-NLS-1$
                + "' needs its typed value beside 'use', or in a 'value' member."); //$NON-NLS-1$
        }
        JsonElement typed = value.size() == 1 && value.has("value") //$NON-NLS-1$
            ? value.get("value") : value; //$NON-NLS-1$
        return AppearanceParameterSpec.success(typed, use.booleanValue());
    }

    /** Replaces one named appearance parameter in place, or appends it when the patch adds a new key. */
    private static void putAppearanceParameter(DataCompositionAppearance appearance,
        DataCompositionParameterValue replacement, DcsAvailableParameter declared,
        DcsAvailableParameterCollection available)
    {
        for (int i = 0; i < appearance.getItems().size(); i++)
        {
            DataCompositionParameterValue existing = appearance.getItems().get(i);
            String existingName = existing.getParameter() == null
                ? null : existing.getParameter().getValue();
            if (existingName != null && available.findItem(existingName) == declared)
            {
                appearance.getItems().set(i, replacement);
                return;
            }
        }
        appearance.getItems().add(replacement);
    }

    /**
     * EDT's DCS catalogues publish English at alias 0 and specifically Russian at alias 1. The
     * serialized name follows the configuration language, not a per-call presentation language.
     */
    static String parameterName(DcsAvailableParameter parameter,
        DcsPresentationParser.LanguageContext languages)
    {
        String code = languages == null ? "en" : languages.configurationCode(); //$NON-NLS-1$
        String localized = parameter.key("ru".equalsIgnoreCase(code) ? 1 : 0); //$NON-NLS-1$
        return localized == null || localized.isEmpty() ? parameter.key(0) : localized;
    }

    /** Maps a JSON value to the platform-declared parameter type for every typed parameter path. */
    private static ValueResult typedParameterValue(JsonElement raw, Value expected,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (expected instanceof LocalString)
        {
            if (raw != null && raw.isJsonObject() && raw.getAsJsonObject().has(KEY_KIND)
                && raw.getAsJsonObject().has("value")) //$NON-NLS-1$
            {
                ValueResult supplied = value(raw, path);
                if (supplied.error != null) return supplied;
                return typedValueMismatch(expected, supplied.value, path);
            }
            JsonElement localized = raw;
            if (raw != null && raw.isJsonPrimitive()
                && raw.getAsJsonPrimitive().isString())
            {
                JsonObject map = new JsonObject();
                map.addProperty(languages == null ? "en" : languages.writeLanguageCode(), //$NON-NLS-1$
                    raw.getAsString());
                localized = map;
            }
            DcsPresentationParser.ParseResult parsed =
                DcsPresentationParser.parse(localized, languages, path);
            if (!parsed.isSuccess()) return ValueResult.failure(parsed.error());
            if (parsed.plan() == null)
            {
                return ValueResult.failure("Localized parameter value at '" + path //$NON-NLS-1$
                    + "' must be a string or a non-empty {languageCode:text} object."); //$NON-NLS-1$
            }
            LocalString value = DcsPresentationParser.build(parsed.plan()).getLocalValue();
            return value == null ? ValueResult.failure("Localized parameter value at '" + path //$NON-NLS-1$
                + "' could not be represented as LocalString. Pass a string or " //$NON-NLS-1$
                + "{languageCode:text} object.") : ValueResult.success(value); //$NON-NLS-1$
        }
        if (expected instanceof ColorValue || expected instanceof FontValue)
        {
            StyleValueBuilder.Result built = StyleValueBuilder.build(raw, NAMED_COLOR_RESOLVER.get());
            if (built.error != null)
            {
                return ValueResult.failure("Typed parameter value at '" + path + "' is invalid: " //$NON-NLS-1$ //$NON-NLS-2$
                    + built.error);
            }
            if (expected instanceof ColorValue != built.value instanceof ColorValue)
            {
                return ValueResult.failure("Parameter at '" + path + "' expects " //$NON-NLS-1$ //$NON-NLS-2$
                    + (expected instanceof ColorValue ? "a color" : "a font") //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Pass the matching {color:...} or {font:...} object."); //$NON-NLS-1$
            }
            return ValueResult.success(built.value);
        }
        if (expected instanceof EnumValue)
        {
            String literal = enumParameterLiteral(raw);
            if (literal == null)
            {
                return ValueResult.failure("Enum parameter value at '" + path //$NON-NLS-1$
                    + "' must be either a bare platform literal string or a ValueSpec " //$NON-NLS-1$
                    + "{\"kind\":\"string\",\"value\":\"<literal>\"}. Allowed literals: " //$NON-NLS-1$
                    + enumeratorValues(((EnumValue)expected).getValue()) + "."); //$NON-NLS-1$
            }
            Enumerator selected = findEnumerator(((EnumValue)expected).getValue(), literal);
            if (selected == null)
            {
                return ValueResult.failure("Enum parameter value '" + literal + "' at '" //$NON-NLS-1$ //$NON-NLS-2$
                    + path + "' is invalid. Use one of: " //$NON-NLS-1$
                    + enumeratorValues(((EnumValue)expected).getValue()) + "."); //$NON-NLS-1$
            }
            EnumValue value = McoreFactory.eINSTANCE.createEnumValue();
            value.setValue(selected);
            return ValueResult.success(value);
        }
        ValueResult value = value(raw, path);
        if (value.error != null) return value;
        if (!hasExpectedType(expected, value.value))
        {
            return typedValueMismatch(expected, value.value, path);
        }
        return value;
    }

    /** Shared schema/settings seam for a ValueSpec constrained by a declared platform value type. */
    static ValueResult buildTypedParameterValue(JsonElement raw, Value expected,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        return typedParameterValue(raw, expected, languages, path);
    }

    /** Shared schema/settings seam for an unconstrained ValueSpec. */
    static ValueResult buildValue(JsonElement raw, String path)
    {
        return value(raw, path);
    }

    /** The two accepted enum wire forms: a bare string, or the ordinary string ValueSpec. */
    private static String enumParameterLiteral(JsonElement raw)
    {
        if (raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString())
        {
            return raw.getAsString();
        }
        if (raw == null || !raw.isJsonObject())
        {
            return null;
        }
        JsonObject spec = raw.getAsJsonObject();
        if (spec.size() != 2 || !spec.has(KEY_KIND) || !spec.has("value")) //$NON-NLS-1$
        {
            return null;
        }
        JsonElement kind = spec.get(KEY_KIND);
        JsonElement value = spec.get("value"); //$NON-NLS-1$
        return kind != null && kind.isJsonPrimitive() && kind.getAsJsonPrimitive().isString()
            && "string".equalsIgnoreCase(kind.getAsString()) //$NON-NLS-1$
            && value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static <T> T withNamedColors(StyleValueBuilder.NamedColorResolver resolver,
        Supplier<T> operation)
    {
        StyleValueBuilder.NamedColorResolver previous = NAMED_COLOR_RESOLVER.get();
        if (resolver == null)
        {
            NAMED_COLOR_RESOLVER.remove();
        }
        else
        {
            NAMED_COLOR_RESOLVER.set(resolver);
        }
        try
        {
            return operation.get();
        }
        finally
        {
            if (previous == null)
            {
                NAMED_COLOR_RESOLVER.remove();
            }
            else
            {
                NAMED_COLOR_RESOLVER.set(previous);
            }
        }
    }

    private static <T> T withAppearanceCatalogue(AppearanceCatalogue catalogue,
        Supplier<T> operation)
    {
        AppearanceCatalogue previous = APPEARANCE_CATALOGUE.get();
        APPEARANCE_CATALOGUE.set(catalogue);
        try
        {
            return operation.get();
        }
        finally
        {
            if (previous == null) APPEARANCE_CATALOGUE.remove();
            else APPEARANCE_CATALOGUE.set(previous);
        }
    }

    enum AppearanceCatalogue
    {
        SCHEMA,
        FORM,
        DYNAMIC_LIST
    }

    static DcsAvailableParameterCollection appearanceParameters(AppearanceCatalogue catalogue,
        Version version, String language) throws DcsPathException
    {
        DcsAppearanceParameters parameters;
        switch (catalogue)
        {
            case FORM:
                parameters = new FormAppearanceParameters(platformVersion(version), language);
                break;
            case DYNAMIC_LIST:
                parameters = new DynamicListAppearanceParameters(platformVersion(version), language);
                break;
            default:
                parameters = new DcsAppearanceParameters(platformVersion(version), language);
                break;
        }
        return parameters.getAvailableParameters().getParameters();
    }

    private static boolean hasExpectedType(Value expected, Value supplied)
    {
        return expected == null || expected.getClass().isInstance(supplied)
            || expected.eClass().isSuperTypeOf(supplied.eClass());
    }

    private static ValueResult typedValueMismatch(Value expected, Value supplied, String path)
    {
        return ValueResult.failure("Parameter at '" + path + "' expects " //$NON-NLS-1$ //$NON-NLS-2$
            + expected.eClass().getName() + ", but got " + supplied.eClass().getName() //$NON-NLS-1$
            + ". Pass the matching typed value."); //$NON-NLS-1$
    }

    private static AvailableParametersResult availableOutputParameters(
        OutputParameterCatalogue catalogue, Version version,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (catalogue == null) return AvailableParametersResult.success(null);
        Version platform = platformVersion(version);
        String language = languages == null ? "en" : languages.resolvedCode(); //$NON-NLS-1$
        try
        {
            return AvailableParametersResult.success(outputParameters(catalogue, platform, language));
        }
        catch (DcsPathException | RuntimeException e)
        {
            return AvailableParametersResult.failure("Could not load the typed output parameter " //$NON-NLS-1$
                + "model for platform " + platform + " at '" + path + "': " + e.getMessage() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". Verify the project platform version and retry."); //$NON-NLS-1$
        }
    }

    private static Version platformVersion(Version version)
    {
        return version == null ? Version.LATEST : version;
    }

    enum OutputParameterCatalogue
    {
        SETTINGS,
        GROUP,
        TABLE,
        TABLE_GROUP,
        CHART,
        CHART_GROUP;

        DcsParameterValuesBase create(Version version, String language) throws DcsPathException
        {
            switch (this)
            {
                case SETTINGS: return new DcsOutputParameters(version, language);
                case GROUP: return new DcsGroupOutputParameters(version, language);
                case TABLE: return new DcsTableOutputParameters(version, language);
                case TABLE_GROUP: return new DcsTableGroupOutputParameters(version, language);
                case CHART: return new DcsChartOutputParameters(version, language);
                default: return new DcsChartGroupOutputParameters(version, language);
            }
        }
    }

    static DcsAvailableParameterCollection outputParameters(OutputParameterCatalogue catalogue,
        Version version, String language) throws DcsPathException
    {
        return catalogue.create(platformVersion(version), language)
            .getAvailableParameters().getParameters();
    }

    private static String applyParameterPath(DataCompositionSettings settings, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages,
        Version version, boolean dataParameters)
    {
        ParameterValues holder = ACTION_REPLACE.equals(action) && path.isEmpty() ? null
            : dataParameters ? copy(settings.getDataParameters()) : copy(settings.getOutputParameters());
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
        String error = applyParameterValuesPath(holder, path, body, action, languages, version,
            dataParameters ? null : OutputParameterCatalogue.SETTINGS, where);
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

    private static String applyParameterValuesPath(ParameterValues holder, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version,
        OutputParameterCatalogue catalogue, String where)
    {
        if (path.isEmpty())
        {
            return applyParameters(holder, body, action, languages, version, catalogue, where);
        }
        if (path.size() == 2 && KEY_ITEMS.equals(path.get(0)))
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
            String at = where + "/items/" + path.get(1); //$NON-NLS-1$
            boolean needsTypedDefinition = body.has("parameter") || body.has("value"); //$NON-NLS-1$ //$NON-NLS-2$
            AvailableParametersResult typed = needsTypedDefinition
                ? availableOutputParameters(catalogue, version, languages, at)
                : AvailableParametersResult.success(null);
            if (typed.error != null) return typed.error;
            if (ACTION_REPLACE.equals(action))
            {
                // Rebuilt, not patched: value, use, viewMode and the user-setting scaffolding must
                // not survive a replace that never named them. There is only one shape here, so
                // the rebuild is the factory call itself.
                SettingsParameterValue fresh = DcsFactory.eINSTANCE.createSettingsParameterValue();
                String error = applyParameterItem(fresh, body, languages, typed.parameters,
                    platformVersion(version), at);
                if (error == null)
                {
                    holder.getItems().set(selected, fresh);
                }
                return error;
            }
            return applyParameterItem((SettingsParameterValue)item, body, languages,
                typed.parameters, platformVersion(version), at);
        }
        return "Parameter-settings address at '" + where //$NON-NLS-1$
            + "' must end at the holder or items/<index>. Copy it from get."; //$NON-NLS-1$
    }

    private static String applyParameters(ParameterValues holder, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version,
        OutputParameterCatalogue catalogue, String path)
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
        AvailableParametersResult typed = items.isEmpty()
            ? AvailableParametersResult.success(null)
            : availableOutputParameters(catalogue, version, languages, path);
        if (typed.error != null) return typed.error;
        if (ACTION_REPLACE.equals(action)) holder.getItems().clear();
        for (int i = 0; i < items.size(); i++)
        {
            JsonObject bodyItem = arrayObject(items, i, path + ".items"); //$NON-NLS-1$
            if (bodyItem == null)
            {
                return arrayObjectError;
            }
            SettingsParameterValue item = DcsFactory.eINSTANCE.createSettingsParameterValue();
            String error = applyParameterItem(item, bodyItem, languages, typed.parameters,
                platformVersion(version), path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
            holder.getItems().add(item);
        }
        return null;
    }

    // ---- user fields ------------------------------------------------------------------------

    private static String applyUserFieldsPath(DataCompositionSettings settings, List<String> path,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, Version version)
    {
        DataCompositionUserFields holder = ACTION_REPLACE.equals(action) && path.isEmpty()
            ? null : copy(settings.getUserFields());
        if (holder == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find userFields. Use action='upsert' to create them."; //$NON-NLS-1$
            }
            holder = DcsFactory.eINSTANCE.createDataCompositionUserFields();
        }
        String error;
        if (path.isEmpty())
        {
            error = applyUserFields(holder, body, action, languages, version, "userFields"); //$NON-NLS-1$
        }
        else if (path.size() >= 2 && KEY_ITEMS.equals(path.get(0)))
        {
            int selected = index(path.get(1), holder.getItems().size(), "userFields/items"); //$NON-NLS-1$
            if (indexError != null) return indexError;
            String at = "userFields/items/" + path.get(1); //$NON-NLS-1$
            UserField field = holder.getItems().get(selected);
            if (path.size() == 2 && ACTION_REPLACE.equals(action))
            {
                // Authoritative replacement, so the field is REBUILT from the body: applying over
                // the existing one let an omitted title, use flag, detailExpression or
                // totalExpression survive a replace that never mentioned them, which is an update
                // wearing a replace label. Same reasoning as the indexed selection item.
                UserField fresh = newUserField(body, at);
                if (fresh == null)
                {
                    return userFieldKindError;
                }
                // A rebuilt field starts empty, so an omitted dataPath would silently produce a
                // user field with no data path at all rather than keeping the old one. Replace is
                // authoritative, which makes the identity the caller's responsibility to restate.
                if (!body.has("dataPath")) //$NON-NLS-1$
                {
                    return "action='replace' at '" + at + "' must restate 'dataPath': a replacement " //$NON-NLS-1$ //$NON-NLS-2$
                        + "is built from the body alone, so an omitted identity would clear it. " //$NON-NLS-1$
                        + "Use action='update' to change properties without restating it."; //$NON-NLS-1$
                }
                error = applyUserField(fresh, body, action, languages, version, at);
                if (error == null)
                {
                    holder.getItems().set(selected, fresh);
                }
            }
            else if (path.size() == 2)
            {
                error = applyUserField(field, body, action, languages, version, at);
            }
            else if (!(field instanceof DataCompositionUserFieldCase)
                || !"variants".equals(path.get(2))) //$NON-NLS-1$
            {
                return "User-field child address at '" + at //$NON-NLS-1$
                    + "' is authorable only for a case field through variants/items/<index>. " //$NON-NLS-1$
                    + "Copy the canonical case-variant address from get."; //$NON-NLS-1$
            }
            else
            {
                DataCompositionUserFieldCase caseField = (DataCompositionUserFieldCase)field;
                List<String> tail = path.subList(3, path.size());
                DataCompositionUserFieldsCaseVariants variants = ACTION_REPLACE.equals(action)
                    && tail.isEmpty() ? null : copy(caseField.getVariants());
                if (variants == null)
                {
                    if (ACTION_UPDATE.equals(action))
                    {
                        return "action='update' cannot find variants at '" + at //$NON-NLS-1$
                            + "/variants'. Use action='upsert' to create them."; //$NON-NLS-1$
                    }
                    variants = DcsFactory.eINSTANCE.createDataCompositionUserFieldsCaseVariants();
                }
                error = applyUserFieldVariantsPath(variants, tail, body, action, languages,
                    at + "/variants"); //$NON-NLS-1$
                if (error == null) caseField.setVariants(variants);
            }
        }
        else
        {
            return "User-field address must end at userFields, userFields/items/<index>, or an " //$NON-NLS-1$
                + "exact variants/items/<index> descendant of a case field. Copy the canonical " //$NON-NLS-1$
                + "address from get."; //$NON-NLS-1$
        }
        if (error == null) settings.setUserFields(holder);
        return error;
    }

    private static String applyUserFields(DataCompositionUserFields holder, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS);
        if (members != null) return members;
        if (!body.has(KEY_ITEMS)) return null;
        JsonArray array = array(body, KEY_ITEMS, path);
        if (array == null) return arrayError;
        if (ACTION_REPLACE.equals(action)) holder.getItems().clear();
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact user-field index at '" + path //$NON-NLS-1$
                + "'. Use upsert to append a user field."; //$NON-NLS-1$
        }
        for (int i = 0; i < array.size(); i++)
        {
            JsonObject itemBody = arrayObject(array, i, path + ".items"); //$NON-NLS-1$
            if (itemBody == null) return arrayObjectError;
            String kind = requiredString(itemBody, KEY_KIND, path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (stringError != null) return stringError;
            UserField item;
            if ("expression".equalsIgnoreCase(kind)) //$NON-NLS-1$
            {
                item = DcsFactory.eINSTANCE.createDataCompositionUserFieldExpression();
            }
            else if ("case".equalsIgnoreCase(kind)) //$NON-NLS-1$
            {
                item = DcsFactory.eINSTANCE.createDataCompositionUserFieldCase();
            }
            else
            {
                return "User-field kind '" + kind + "' at '" + path + ".items[" + i //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "]' is invalid. Use kind='expression' or kind='case'."; //$NON-NLS-1$
            }
            String error = applyUserField(item, itemBody, action, languages, version,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
            holder.getItems().add(item);
        }
        return null;
    }

    /**
     * A fresh user field of the kind the body names, or {@code null} with {@link #userFieldKindError}
     * set. Shared by append and by an authoritative replace so both build the same shapes from the
     * same kind vocabulary.
     */
    private static UserField newUserField(JsonObject body, String path)
    {
        userFieldKindError = null;
        String kind = requiredString(body, KEY_KIND, path);
        if (stringError != null)
        {
            userFieldKindError = stringError;
            return null;
        }
        if ("expression".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionUserFieldExpression();
        }
        if ("case".equalsIgnoreCase(kind)) //$NON-NLS-1$
        {
            return DcsFactory.eINSTANCE.createDataCompositionUserFieldCase();
        }
        userFieldKindError = "User-field kind '" + kind + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
            + "' is invalid. Use kind='expression' or kind='case'."; //$NON-NLS-1$
        return null;
    }

    private static String applyUserField(UserField field, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, Version version, String path)
    {
        String expectedKind = field instanceof DataCompositionUserFieldExpression ? "expression" : "case"; //$NON-NLS-1$ //$NON-NLS-2$
        String kindError = kindMustBe(body, path, expectedKind);
        if (kindError != null) return kindError;
        String members;
        if (field instanceof DataCompositionUserFieldExpression)
        {
            members = checkMembers(body, path, KEY_KIND, KEY_USE, "dataPath", KEY_TITLE, //$NON-NLS-1$
                "detailExpression", "detailExpressionPresentation", "totalExpression", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "totalExpressionPresentation"); //$NON-NLS-1$
        }
        else
        {
            members = checkMembers(body, path, KEY_KIND, KEY_USE, "dataPath", KEY_TITLE, "variants"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (members != null) return members;
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null) return booleanError;
            field.setUse(use.booleanValue());
        }
        if (body.has("dataPath")) //$NON-NLS-1$
        {
            String value = requiredString(body, "dataPath", path); //$NON-NLS-1$
            if (stringError != null) return stringError;
            field.setDataPath(value);
        }
        if (body.has(KEY_TITLE))
        {
            PresentationResult value = presentation(body.get(KEY_TITLE), languages, path + ".title"); //$NON-NLS-1$
            if (value.error != null) return value.error;
            field.setTitle(value.value);
        }
        if (field instanceof DataCompositionUserFieldExpression)
        {
            DataCompositionUserFieldExpression expression = (DataCompositionUserFieldExpression)field;
            String[] membersToSet = {"detailExpression", "detailExpressionPresentation", //$NON-NLS-1$ //$NON-NLS-2$
                "totalExpression", "totalExpressionPresentation"}; //$NON-NLS-1$ //$NON-NLS-2$
            for (String member : membersToSet)
            {
                if (!body.has(member)) continue;
                String value = optionalString(body, member, path);
                if (stringError != null) return stringError;
                if ("detailExpression".equals(member)) expression.setDetailExpression(value); //$NON-NLS-1$
                else if ("detailExpressionPresentation".equals(member)) //$NON-NLS-1$
                    expression.setDetailExpressionPresentation(value);
                else if ("totalExpression".equals(member)) expression.setTotalExpression(value); //$NON-NLS-1$
                else expression.setTotalExpressionPresentation(value);
            }
        }
        else if (body.has("variants")) //$NON-NLS-1$
        {
            JsonObject value = object(body, "variants", path); //$NON-NLS-1$
            if (value == null) return objectError;
            DataCompositionUserFieldCase caseField = (DataCompositionUserFieldCase)field;
            String missing = missingHolderUpdate(action, caseField.getVariants(),
                path + ".variants"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionUserFieldsCaseVariants variants = ACTION_REPLACE.equals(action) ? null
                : copy(caseField.getVariants());
            if (variants == null) variants = DcsFactory.eINSTANCE.createDataCompositionUserFieldsCaseVariants();
            String error = applyUserFieldVariants(variants, value, action, languages,
                path + ".variants"); //$NON-NLS-1$
            if (error != null) return error;
            caseField.setVariants(variants);
        }
        return null;
    }

    private static String applyUserFieldVariants(DataCompositionUserFieldsCaseVariants holder,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_ITEMS);
        if (members != null) return members;
        if (!body.has(KEY_ITEMS)) return null;
        JsonArray array = array(body, KEY_ITEMS, path);
        if (array == null) return arrayError;
        if (ACTION_REPLACE.equals(action)) holder.getItems().clear();
        if (ACTION_UPDATE.equals(action))
        {
            return "action='update' needs an exact case-variant address ending in " //$NON-NLS-1$
                + "variants/items/<index>. Copy it from get; use upsert to append one."; //$NON-NLS-1$
        }
        for (int i = 0; i < array.size(); i++)
        {
            JsonObject itemBody = arrayObject(array, i, path + ".items"); //$NON-NLS-1$
            if (itemBody == null) return arrayObjectError;
            DataCompositionUserFieldsVariant variant =
                DcsFactory.eINSTANCE.createDataCompositionUserFieldsVariant();
            String error = applyUserFieldVariant(variant, itemBody, action, languages,
                path + ".items[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null) return error;
            holder.getItems().add(variant);
        }
        return null;
    }

    private static String applyUserFieldVariantsPath(DataCompositionUserFieldsCaseVariants holder,
        List<String> path, JsonObject body, String action,
        DcsPresentationParser.LanguageContext languages, String where)
    {
        if (path.isEmpty())
        {
            return applyUserFieldVariants(holder, body, action, languages, where);
        }
        if (path.size() < 2 || !KEY_ITEMS.equals(path.get(0)))
        {
            return "Case-variant address at '" + where //$NON-NLS-1$
                + "' must continue with items/<index>. Copy the canonical address from get."; //$NON-NLS-1$
        }
        int selected = index(path.get(1), holder.getItems().size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        DataCompositionUserFieldsVariant variant = holder.getItems().get(selected);
        String at = where + "/items/" + path.get(1); //$NON-NLS-1$
        if (path.size() == 2)
        {
            if (ACTION_REPLACE.equals(action))
            {
                variant = DcsFactory.eINSTANCE.createDataCompositionUserFieldsVariant();
                holder.getItems().set(selected, variant);
            }
            return applyUserFieldVariant(variant, body, action, languages, at);
        }
        if (!"filter".equals(path.get(2))) //$NON-NLS-1$
        {
            return "Case-variant child address at '" + at //$NON-NLS-1$
                + "' is authorable only through filter/items/<index>. Copy it from get."; //$NON-NLS-1$
        }
        List<String> tail = path.subList(3, path.size());
        DataCompositionFilter filter = ACTION_REPLACE.equals(action) && tail.isEmpty()
            ? null : copy(variant.getFilter());
        if (filter == null)
        {
            if (ACTION_UPDATE.equals(action))
            {
                return "action='update' cannot find filter at '" + at //$NON-NLS-1$
                    + "/filter'. Use action='upsert' to create it."; //$NON-NLS-1$
            }
            filter = DcsFactory.eINSTANCE.createDataCompositionFilter();
        }
        String error = applyFilterPath(filter, tail, body, action, languages, at + "/filter"); //$NON-NLS-1$
        if (error == null) variant.setFilter(filter);
        return error;
    }

    private static String applyUserFieldVariant(DataCompositionUserFieldsVariant variant,
        JsonObject body, String action, DcsPresentationParser.LanguageContext languages, String path)
    {
        String members = checkMembers(body, path, KEY_USE, "filter", "value", //$NON-NLS-1$ //$NON-NLS-2$
            "presentationValue"); //$NON-NLS-1$
        if (members != null) return members;
        if (body.has(KEY_USE))
        {
            Boolean use = bool(body, KEY_USE, path);
            if (use == null) return booleanError;
            variant.setUse(use.booleanValue());
        }
        if (body.has("filter")) //$NON-NLS-1$
        {
            JsonObject filterBody = object(body, "filter", path); //$NON-NLS-1$
            if (filterBody == null) return objectError;
            String missing = missingHolderUpdate(action, variant.getFilter(), path + ".filter"); //$NON-NLS-1$
            if (missing != null) return missing;
            DataCompositionFilter filter = ACTION_REPLACE.equals(action) ? null
                : copy(variant.getFilter());
            if (filter == null) filter = DcsFactory.eINSTANCE.createDataCompositionFilter();
            String error = applyFilter(filter, filterBody, action, languages, path + ".filter"); //$NON-NLS-1$
            if (error != null) return error;
            variant.setFilter(filter);
        }
        if (body.has("value")) //$NON-NLS-1$
        {
            ValueResult parsed = value(body.get("value"), path + ".value"); //$NON-NLS-1$ //$NON-NLS-2$
            if (parsed.error != null) return parsed.error;
            variant.setValue(parsed.value);
        }
        if (body.has("presentationValue")) //$NON-NLS-1$
        {
            PresentationResult parsed = presentation(body.get("presentationValue"), languages, //$NON-NLS-1$
                path + ".presentationValue"); //$NON-NLS-1$
            if (parsed.error != null) return parsed.error;
            variant.setPresentationValue(parsed.value);
        }
        return null;
    }

    private static String applyParameterItem(SettingsParameterValue item, JsonObject body,
        DcsPresentationParser.LanguageContext languages,
        DcsAvailableParameterCollection available, Version version, String path)
    {
        String members = checkMembers(body, path, "parameter", "value", KEY_USE, //$NON-NLS-1$ //$NON-NLS-2$
            KEY_VIEW_MODE, KEY_USER_SETTING_ID, KEY_USER_SETTING_PRESENTATION);
        if (members != null)
        {
            return members;
        }
        DcsAvailableParameter declared = null;
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
            if (available != null)
            {
                String name = ((DataCompositionParameter)parameter.value).getValue();
                declared = available.findItem(name);
                if (declared == null)
                {
                    return unknownOutputParameter(name, available, version,
                        path + ".parameter"); //$NON-NLS-1$
                }
                ((DataCompositionParameter)parameter.value).setValue(
                    parameterName(declared, languages));
            }
            item.setParameter((DataCompositionParameter)parameter.value);
        }
        else if (available != null && body.has("value")) //$NON-NLS-1$
        {
            String name = item.getParameter() == null ? null : item.getParameter().getValue();
            if (name == null || name.isEmpty())
            {
                return "Output parameter value at '" + path //$NON-NLS-1$
                    + ".value' needs a declared 'parameter'. Pass a typed parameter name."; //$NON-NLS-1$
            }
            declared = available.findItem(name);
            if (declared == null)
            {
                return unknownOutputParameter(name, available, version,
                    path + ".parameter"); //$NON-NLS-1$
            }
            item.getParameter().setValue(parameterName(declared, languages));
        }
        if (body.has("value")) //$NON-NLS-1$
        {
            ValueResult mapped = available == null
                ? value(body.get("value"), path + ".value") //$NON-NLS-1$ //$NON-NLS-2$
                : typedParameterValue(body.get("value"), declared.defValue(), languages, //$NON-NLS-1$
                    path + ".value"); //$NON-NLS-1$
            if (mapped.error != null)
            {
                return mapped.error;
            }
            item.getValues().clear();
            item.getValues().add(mapped.value);
        }
        else if (declared != null && !item.getValues().isEmpty()
            && !hasExpectedType(declared.defValue(), item.getValues().get(0)))
        {
            return typedValueMismatch(declared.defValue(), item.getValues().get(0),
                path + ".value").error; //$NON-NLS-1$
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

    /** Headless unit seam: production obtains this same collection from the holder catalogue. */
    static String applyOutputParameterItemForTest(SettingsParameterValue item, JsonObject body,
        DcsPresentationParser.LanguageContext languages,
        DcsAvailableParameterCollection available)
    {
        return applyParameterItem(item, body, languages, available, Version.LATEST,
            "outputParameters.items[0]"); //$NON-NLS-1$
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

    private static String applyTableScaffold(DataCompositionTable table, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        String error = applySettingsItemScaffold(table, body, languages, path);
        if (error != null) return error;
        String[] prefixes = {"rows", "columns"}; //$NON-NLS-1$ //$NON-NLS-2$
        for (String prefix : prefixes)
        {
            String viewMember = prefix + "ViewMode"; //$NON-NLS-1$
            if (body.has(viewMember))
            {
                EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, viewMember,
                    path, DataCompositionSettingsItemViewMode.values());
                if (value.error != null) return value.error;
                if ("rows".equals(prefix)) table.setRowsViewMode(value.value); //$NON-NLS-1$
                else table.setColumnsViewMode(value.value);
            }
            String idMember = prefix + "UserSettingID"; //$NON-NLS-1$
            if (body.has(idMember))
            {
                String value = optionalString(body, idMember, path);
                if (stringError != null) return stringError;
                if ("rows".equals(prefix)) table.setRowsUserSettingID(value); //$NON-NLS-1$
                else table.setColumnsUserSettingID(value);
            }
            String presentationMember = prefix + "UserSettingPresentation"; //$NON-NLS-1$
            if (body.has(presentationMember))
            {
                PresentationResult value = presentation(body.get(presentationMember), languages,
                    path + "." + presentationMember); //$NON-NLS-1$
                if (value.error != null) return value.error;
                if ("rows".equals(prefix)) table.setRowsUserSettingPresentation(value.value); //$NON-NLS-1$
                else table.setColumnsUserSettingPresentation(value.value);
            }
        }
        return null;
    }

    private static String applyTableGroupScaffold(DataCompositionTableGroup group, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        String error = applySettingsItemScaffold(group, body, languages, path);
        if (error != null) return error;
        if (body.has("itemsViewMode")) //$NON-NLS-1$
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, "itemsViewMode", //$NON-NLS-1$
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null) return value.error;
            group.setItemsViewMode(value.value);
        }
        if (body.has("itemsUserSettingID")) //$NON-NLS-1$
        {
            String value = optionalString(body, "itemsUserSettingID", path); //$NON-NLS-1$
            if (stringError != null) return stringError;
            group.setItemsUserSettingID(value);
        }
        if (body.has("itemsUserSettingPresentation")) //$NON-NLS-1$
        {
            PresentationResult value = presentation(body.get("itemsUserSettingPresentation"), //$NON-NLS-1$
                languages, path + ".itemsUserSettingPresentation"); //$NON-NLS-1$
            if (value.error != null) return value.error;
            group.setItemsUserSettingPresentation(value.value);
        }
        return null;
    }

    private static String applySettingsItemScaffold(Object target, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        DataCompositionSettingsItemViewMode view = null;
        if (body.has(KEY_VIEW_MODE))
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, KEY_VIEW_MODE,
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null) return value.error;
            view = value.value;
        }
        String id = null;
        if (body.has(KEY_USER_SETTING_ID))
        {
            id = optionalString(body, KEY_USER_SETTING_ID, path);
            if (stringError != null) return stringError;
        }
        Presentation settingPresentation = null;
        if (body.has(KEY_USER_SETTING_PRESENTATION))
        {
            PresentationResult value = presentation(body.get(KEY_USER_SETTING_PRESENTATION),
                languages, path + ".userSettingPresentation"); //$NON-NLS-1$
            if (value.error != null) return value.error;
            settingPresentation = value.value;
        }
        if (target instanceof DataCompositionTable)
        {
            DataCompositionTable value = (DataCompositionTable)target;
            if (view != null) value.setViewMode(view);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(settingPresentation);
        }
        else if (target instanceof DataCompositionTableGroup)
        {
            DataCompositionTableGroup value = (DataCompositionTableGroup)target;
            if (view != null) value.setViewMode(view);
            if (body.has(KEY_USER_SETTING_ID)) value.setUserSettingID(id);
            if (body.has(KEY_USER_SETTING_PRESENTATION)) value.setUserSettingPresentation(settingPresentation);
        }
        return null;
    }

    private static String applyConditionalAppearanceItemScaffold(
        DataCompositionConditionalAppearanceItem item, JsonObject body,
        DcsPresentationParser.LanguageContext languages, String path)
    {
        if (body.has(KEY_VIEW_MODE))
        {
            EnumResult<DataCompositionSettingsItemViewMode> value = enumValue(body, KEY_VIEW_MODE,
                path, DataCompositionSettingsItemViewMode.values());
            if (value.error != null) return value.error;
            item.setViewMode(value.value);
        }
        if (body.has(KEY_USER_SETTING_ID))
        {
            String value = optionalString(body, KEY_USER_SETTING_ID, path);
            if (stringError != null) return stringError;
            item.setUserSettingID(value);
        }
        if (body.has(KEY_USER_SETTING_PRESENTATION))
        {
            PresentationResult value = presentation(body.get(KEY_USER_SETTING_PRESENTATION),
                languages, path + ".userSettingPresentation"); //$NON-NLS-1$
            if (value.error != null) return value.error;
            item.setUserSettingPresentation(value.value);
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

    private static void setAppearanceUse(DataCompositionConditionalAppearanceItem item, String member,
        DataCompositionConditionalAppearanceUse value)
    {
        switch (member)
        {
            case "useInGroup": item.setUseInGroup(value); break; //$NON-NLS-1$
            case "useInHierarchicalGroup": item.setUseInHierarchicalGroup(value); break; //$NON-NLS-1$
            case "useInOverall": item.setUseInOverall(value); break; //$NON-NLS-1$
            case "useInFieldsHeader": item.setUseInFieldsHeader(value); break; //$NON-NLS-1$
            case "useInHeader": item.setUseInHeader(value); break; //$NON-NLS-1$
            case "useInParameters": item.setUseInParameters(value); break; //$NON-NLS-1$
            case "useInFilter": item.setUseInFilter(value); break; //$NON-NLS-1$
            case "useInResourceFieldsHeader": item.setUseInResourceFieldsHeader(value); break; //$NON-NLS-1$
            case "useInOverallHeader": item.setUseInOverallHeader(value); break; //$NON-NLS-1$
            default: item.setUseInOverallResourceFieldsHeader(value); break;
        }
    }

    private static String unknownOutputParameter(String name,
        DcsAvailableParameterCollection parameters, Version version, String path)
    {
        return "Unknown output parameter '" + name + "' at '" + path //$NON-NLS-1$ //$NON-NLS-2$
            + "'. Use one of the typed keys for platform " + version + ": " //$NON-NLS-1$ //$NON-NLS-2$
            + parameterKeys(parameters) + ". Remove '" + name + "' or correct its spelling."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String parameterKeys(DcsAvailableParameterCollection parameters)
    {
        final int limit = 24;
        List<String> keys = new ArrayList<>();
        int shown = Math.min(parameters.itemsCount(), limit);
        for (int i = 0; i < shown; i++)
        {
            keys.add(parameters.getItemAt(i).key(0));
        }
        String result = String.join(", ", keys); //$NON-NLS-1$
        int remaining = parameters.itemsCount() - shown;
        return remaining == 0 ? result : result + ", ... (" + remaining + " more)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Enumerator findEnumerator(Enumerator sample, String raw)
    {
        if (sample == null) return null;
        Object[] values = sample.getClass().getEnumConstants();
        if (values == null) return null;
        for (Object candidate : values)
        {
            Enumerator value = (Enumerator)candidate;
            if (value.getLiteral().equalsIgnoreCase(raw) || value.getName().equalsIgnoreCase(raw)
                || candidate.toString().equalsIgnoreCase(raw))
            {
                return value;
            }
        }
        return null;
    }

    private static String enumeratorValues(Enumerator sample)
    {
        List<String> result = enumeratorLiterals(sample);
        return result.isEmpty() ? "(none)" : String.join(", ", result); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static List<String> enumeratorLiterals(Enumerator sample)
    {
        if (sample == null) return Collections.emptyList();
        Object[] values = sample.getClass().getEnumConstants();
        if (values == null) return Collections.singletonList(sample.getLiteral());
        List<String> result = new ArrayList<>();
        for (Object candidate : values)
        {
            result.add(((Enumerator)candidate).getLiteral());
        }
        return Collections.unmodifiableList(result);
    }

    // ---- JSON helpers -----------------------------------------------------------------------

    private static String objectError;
    private static String arrayError;
    private static String arrayObjectError;
    private static String selectedKindError;
    private static String userFieldKindError;
    private static String filterKindError;
    private static String orderKindError;
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
        return parsed.isSuccess() ? PresentationResult.success(DcsPresentationParser.build(parsed.plan()))
            : PresentationResult.failure(parsed.error());
    }

    /**
     * The settings segments a concrete type resolves to when the caller addressed a bare root, so
     * a caller-facing guard can scope itself to the node the planner will actually rewrite instead
     * of to the whole document. Empty for a type that addresses the settings root itself.
     *
     * @param type the settings type token
     * @return the default segments, possibly empty
     */
    public static List<String> defaultSettingsPath(String type)
    {
        return defaultPath(type);
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
            case TYPE_CONDITIONAL_APPEARANCE:
                return Collections.singletonList("conditionalAppearance"); //$NON-NLS-1$
            case TYPE_TABLE:
                return Collections.singletonList(KEY_ITEMS);
            case TYPE_USER_FIELD:
                return Collections.singletonList("userFields"); //$NON-NLS-1$
            case TYPE_OUTPUT_PARAMETER:
                return Collections.singletonList("outputParameters"); //$NON-NLS-1$
            default:
                return Collections.emptyList();
        }
    }

    private static String removeSettingsPath(DataCompositionSettings settings, List<String> path,
        String type)
    {
        EObject target = resolveSettingsNode(settings, path);
        if (target == null)
        {
            return "action='remove' could not resolve target '" + String.join("/", path) //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Re-run dcs action='get' and copy an existing node address."; //$NON-NLS-1$
        }
        boolean additionalProperties = !path.isEmpty()
            && KEY_ADDITIONAL_PROPERTIES.equals(path.get(0)) && TYPE_USER_SETTINGS.equals(type);
        String typeError = additionalProperties ? null
            : resolvedSettingsTypeError(target, path, ACTION_REMOVE, type);
        if (typeError != null) return typeError;
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        if (KEY_ITEMS.equals(head))
        {
            return removeStructurePath(settings.getItems(), tail, "settings/items"); //$NON-NLS-1$
        }
        if (tail.isEmpty())
        {
            switch (head)
            {
                case "selection": settings.setSelection(null); return null; //$NON-NLS-1$
                case "filter": settings.setFilter(null); return null; //$NON-NLS-1$
                case "dataParameters": settings.setDataParameters(null); return null; //$NON-NLS-1$
                case "order": settings.setOrder(null); return null; //$NON-NLS-1$
                case "conditionalAppearance": settings.setConditionalAppearance(null); return null; //$NON-NLS-1$
                case "outputParameters": settings.setOutputParameters(null); return null; //$NON-NLS-1$
                case "userFields": settings.setUserFields(null); return null; //$NON-NLS-1$
                case KEY_ADDITIONAL_PROPERTIES: settings.setAdditionalProperties(null); return null;
                default: break;
            }
        }
        if ("selection".equals(head)) //$NON-NLS-1$
        {
            return removeSelectionPath(settings.getSelection(), tail, "selection"); //$NON-NLS-1$
        }
        if ("order".equals(head)) //$NON-NLS-1$
        {
            return removeIndexed(settings.getOrder() == null ? null : settings.getOrder().getItems(),
                tail, "order"); //$NON-NLS-1$
        }
        if ("conditionalAppearance".equals(head)) //$NON-NLS-1$
        {
            return removeConditionalAppearancePath(settings.getConditionalAppearance(), tail,
                "conditionalAppearance"); //$NON-NLS-1$
        }
        if ("dataParameters".equals(head)) //$NON-NLS-1$
        {
            return removeIndexed(settings.getDataParameters() == null ? null
                : settings.getDataParameters().getItems(), tail, "dataParameters"); //$NON-NLS-1$
        }
        if ("outputParameters".equals(head)) //$NON-NLS-1$
        {
            return removeIndexed(settings.getOutputParameters() == null ? null
                : settings.getOutputParameters().getItems(), tail, "outputParameters"); //$NON-NLS-1$
        }
        if ("userFields".equals(head)) //$NON-NLS-1$
        {
            return removeUserFieldsPath(settings.getUserFields(), tail, "userFields"); //$NON-NLS-1$
        }
        if ("filter".equals(head)) //$NON-NLS-1$
        {
            return removeFilterPath(settings.getFilter(), tail, "filter"); //$NON-NLS-1$
        }
        return "action='remove' cannot address settings path '" + String.join("/", path) //$NON-NLS-1$ //$NON-NLS-2$
            + "' for type='" + type + "'. Copy an exact node address from dcs action='get'."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static EObject resolveSettingsNode(EObject root, List<String> path)
    {
        EObject current = root;
        for (int i = 0; i < path.size(); i++)
        {
            EStructuralFeature feature = current.eClass().getEStructuralFeature(path.get(i));
            if (feature == null)
            {
                return null;
            }
            Object value = current.eGet(feature);
            if (feature.isMany())
            {
                if (!(value instanceof List<?>) || ++i >= path.size()
                    || !DcsAddress.isZeroBasedIndex(path.get(i)))
                {
                    return null;
                }
                List<?> values = (List<?>)value;
                int selected = Integer.parseInt(path.get(i));
                if (selected >= values.size())
                {
                    return null;
                }
                value = values.get(selected);
            }
            if (!(value instanceof EObject))
            {
                return null;
            }
            current = (EObject)value;
        }
        return current;
    }

    private static String resolvedMutationTypeError(DataCompositionSettings settings,
        List<String> path, String action, String type, DcsAddress targetAddress)
    {
        if (settings == null) return null;
        if (!path.isEmpty() && KEY_ADDITIONAL_PROPERTIES.equals(path.get(0))
            && TYPE_USER_SETTINGS.equals(type))
        {
            return null;
        }
        EObject target = resolveSettingsNode(settings, path);
        if (target == null)
        {
            // An upsert may be addressing a node that does not exist yet. Leave unresolved paths
            // to the action-specific dispatch, which creates for upsert and refuses for update.
            return null;
        }
        return resolvedSettingsTypeError(target, path, action, type,
            targetAddress == null ? null : targetAddress.toString());
    }

    private static String resolvedSettingsTypeError(EObject target, List<String> path,
        String action, String type)
    {
        return resolvedSettingsTypeError(target, path, action, type, null);
    }

    private static String resolvedSettingsTypeError(EObject target, List<String> path,
        String action, String type, String targetAddress)
    {
        String renderedPath = path.isEmpty() ? "settings root" : String.join("/", path); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = DcsUnsupportedAuthoring.refusal(target,
            targetAddress == null ? renderedPath : targetAddress);
        if (refusal != null) return refusal;
        String actualType = DcsReadProjection.typeOf(target, target.eContainer());
        if (actualType == null)
        {
            return "action='" + action + "' resolved target '" + renderedPath + "' as " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + target.eClass().getName() + ", which has no public DCS type. " //$NON-NLS-1$
                + (ACTION_REMOVE.equals(action) ? "Remove" : "Address") //$NON-NLS-1$ //$NON-NLS-2$
                + " a parent node " //$NON-NLS-1$
                + "rendered by dcs action='get' instead."; //$NON-NLS-1$
        }
        if (!type.equals(actualType))
        {
            // Structure slots are polymorphic, so exact replace may swap grouping and table kinds.
            if (ACTION_REPLACE.equals(action) && target instanceof StructureItem
                && (TYPE_GROUPING.equals(type) || TYPE_TABLE.equals(type))
                && (TYPE_GROUPING.equals(actualType) || TYPE_TABLE.equals(actualType)))
            {
                return null;
            }
            return "action='" + action + "' declared type='" + type + "', but resolved target '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + renderedPath + "' has type='" + actualType + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                + (ACTION_REMOVE.equals(action) ? "Removing is not reversible, so pass" : "Pass") //$NON-NLS-1$ //$NON-NLS-2$
                + " type='" + actualType //$NON-NLS-1$
                + "' or copy another exact address from dcs action='get'."; //$NON-NLS-1$
        }
        return null;
    }

    private static String removeStructurePath(List<StructureItem> items, List<String> path, String where)
    {
        if (path.isEmpty())
        {
            return "action='remove' needs one structure-item index after '" + where //$NON-NLS-1$
                + "'. Copy the exact node address from get."; //$NON-NLS-1$
        }
        int selected = index(path.get(0), items.size(), where);
        if (indexError != null) return indexError;
        if (path.size() == 1)
        {
            items.remove(selected);
            return null;
        }
        StructureItem item = items.get(selected);
        List<String> tail = path.subList(1, path.size());
        if (item instanceof DataCompositionGroup)
        {
            DataCompositionGroup group = (DataCompositionGroup)item;
            if (KEY_ITEMS.equals(tail.get(0)))
                return removeStructurePath(group.getItems(), tail.subList(1, tail.size()),
                    where + "/" + path.get(0) + "/items"); //$NON-NLS-1$ //$NON-NLS-2$
            return removeGroupChild(group, tail, where + "/" + path.get(0)); //$NON-NLS-1$
        }
        if (item instanceof DataCompositionTable)
        {
            return removeTableChild((DataCompositionTable)item, tail,
                where + "/" + path.get(0)); //$NON-NLS-1$
        }
        return "Structure subtype '" + item.eClass().getName() + "' at '" + where + "/" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + path.get(0) + "' has no authorable child at the requested address."; //$NON-NLS-1$
    }

    private static String removeGroupChild(DataCompositionGroup group, List<String> path, String where)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        if (isGroupingHolder(head))
        {
            return removeGroupingHolder(new GroupSettingsAccess(group), path, where);
        }
        if (tail.isEmpty())
        {
            switch (head)
            {
                case "conditionalAppearance": group.setConditionalAppearance(null); return null; //$NON-NLS-1$
                case "outputParameters": group.setOutputParameters(null); return null; //$NON-NLS-1$
                default: break;
            }
        }
        if ("conditionalAppearance".equals(head)) //$NON-NLS-1$
            return removeConditionalAppearancePath(group.getConditionalAppearance(), tail,
                where + "/conditionalAppearance"); //$NON-NLS-1$
        if ("outputParameters".equals(head)) //$NON-NLS-1$
            return removeIndexed(group.getOutputParameters() == null ? null
                : group.getOutputParameters().getItems(), tail, where + "/outputParameters"); //$NON-NLS-1$
        return "Grouping child address '" + where + "/" + String.join("/", path) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' does not select exactly one authorable node."; //$NON-NLS-1$
    }

    private static String removeTableChild(DataCompositionTable table, List<String> path, String where)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        if ("rows".equals(head) || "columns".equals(head)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return removeTableGroupPath("rows".equals(head) ? table.getRows() : table.getColumns(), //$NON-NLS-1$
                tail, where + "/" + head); //$NON-NLS-1$
        }
        if (tail.isEmpty())
        {
            if ("selection".equals(head)) { table.setSelection(null); return null; } //$NON-NLS-1$
            if ("conditionalAppearance".equals(head)) { table.setConditionalAppearance(null); return null; } //$NON-NLS-1$
            if ("outputParameters".equals(head)) { table.setOutputParameters(null); return null; } //$NON-NLS-1$
        }
        if ("selection".equals(head)) //$NON-NLS-1$
            return removeSelectionPath(table.getSelection(), tail, where + "/selection"); //$NON-NLS-1$
        if ("conditionalAppearance".equals(head)) //$NON-NLS-1$
            return removeConditionalAppearancePath(table.getConditionalAppearance(), tail,
                where + "/conditionalAppearance"); //$NON-NLS-1$
        if ("outputParameters".equals(head)) //$NON-NLS-1$
            return removeIndexed(table.getOutputParameters() == null ? null
                : table.getOutputParameters().getItems(), tail, where + "/outputParameters"); //$NON-NLS-1$
        return "Table child address '" + where + "/" + String.join("/", path) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' does not select exactly one authorable node."; //$NON-NLS-1$
    }

    private static String removeTableGroupPath(List<DataCompositionTableGroup> groups, List<String> path,
        String where)
    {
        if (path.isEmpty()) return "action='remove' needs one table-axis group index after '" //$NON-NLS-1$
            + where + "'."; //$NON-NLS-1$
        int selected = index(path.get(0), groups.size(), where);
        if (indexError != null) return indexError;
        if (path.size() == 1)
        {
            groups.remove(selected);
            return null;
        }
        DataCompositionTableGroup group = groups.get(selected);
        List<String> tail = path.subList(1, path.size());
        if (KEY_ITEMS.equals(tail.get(0)))
            return removeTableGroupPath(group.getItems(), tail.subList(1, tail.size()),
                where + "/" + path.get(0) + "/items"); //$NON-NLS-1$ //$NON-NLS-2$
        if (isGroupingHolder(tail.get(0)))
            return removeGroupingHolder(new TableGroupSettingsAccess(group), tail,
                where + "/" + path.get(0)); //$NON-NLS-1$
        if ("conditionalAppearance".equals(tail.get(0))) //$NON-NLS-1$
        {
            if (tail.size() == 1)
            {
                group.setConditionalAppearance(null);
                return null;
            }
            return removeConditionalAppearancePath(group.getConditionalAppearance(),
                tail.subList(1, tail.size()), where + "/" + path.get(0) //$NON-NLS-1$
                    + "/conditionalAppearance"); //$NON-NLS-1$
        }
        if ("outputParameters".equals(tail.get(0))) //$NON-NLS-1$
        {
            if (tail.size() == 1)
            {
                group.setOutputParameters(null);
                return null;
            }
            return removeIndexed(group.getOutputParameters() == null ? null
                : group.getOutputParameters().getItems(), tail.subList(1, tail.size()),
                where + "/" + path.get(0) + "/outputParameters"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "Table-axis child removal at '" + where //$NON-NLS-1$
            + "' currently supports items, groupFields, selection, filter, order, " //$NON-NLS-1$
            + "conditionalAppearance, or outputParameters. Remove the group or update its body."; //$NON-NLS-1$
    }

    private static String removeGroupingHolder(GroupingSettingsAccess owner, List<String> path,
        String where)
    {
        String head = path.get(0);
        List<String> tail = path.subList(1, path.size());
        if (tail.isEmpty())
        {
            switch (head)
            {
                case "groupFields": owner.groupFields(null); return null; //$NON-NLS-1$
                case "selection": owner.selection(null); return null; //$NON-NLS-1$
                case "filter": owner.filter(null); return null; //$NON-NLS-1$
                case "order": owner.order(null); return null; //$NON-NLS-1$
                default: break;
            }
        }
        if ("groupFields".equals(head)) //$NON-NLS-1$
            return removeIndexed(owner.groupFields() == null ? null : owner.groupFields().getItems(),
                tail, where + "/groupFields"); //$NON-NLS-1$
        if ("selection".equals(head)) //$NON-NLS-1$
            return removeSelectionPath(owner.selection(), tail, where + "/selection"); //$NON-NLS-1$
        if ("filter".equals(head)) //$NON-NLS-1$
            return removeFilterPath(owner.filter(), tail, where + "/filter"); //$NON-NLS-1$
        if ("order".equals(head)) //$NON-NLS-1$
            return removeIndexed(owner.order() == null ? null : owner.order().getItems(),
                tail, where + "/order"); //$NON-NLS-1$
        return "Grouping child address '" + where + "/" + String.join("/", path) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' does not select exactly one authorable node."; //$NON-NLS-1$
    }

    private static String removeUserFieldsPath(DataCompositionUserFields holder, List<String> path,
        String where)
    {
        if (holder == null) return "No userFields exist at '" + where + "'. Re-run get."; //$NON-NLS-1$ //$NON-NLS-2$
        if (path.size() < 2 || !KEY_ITEMS.equals(path.get(0)))
        {
            return "User-field removal at '" + where //$NON-NLS-1$
                + "' needs items/<index>. Copy the exact address from get."; //$NON-NLS-1$
        }
        int selected = index(path.get(1), holder.getItems().size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        if (path.size() == 2)
        {
            holder.getItems().remove(selected);
            return null;
        }
        UserField field = holder.getItems().get(selected);
        String at = where + "/items/" + path.get(1); //$NON-NLS-1$
        if (!(field instanceof DataCompositionUserFieldCase)
            || !"variants".equals(path.get(2))) //$NON-NLS-1$
        {
            return "User-field child address at '" + at //$NON-NLS-1$
                + "' does not select an exact removable case variant."; //$NON-NLS-1$
        }
        DataCompositionUserFieldCase caseField = (DataCompositionUserFieldCase)field;
        if (path.size() == 3)
        {
            caseField.setVariants(null);
            return null;
        }
        DataCompositionUserFieldsCaseVariants variants = caseField.getVariants();
        String variantsWhere = at + "/variants"; //$NON-NLS-1$
        if (variants == null)
        {
            return "No case variants exist at '" + variantsWhere + "'. Re-run get."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<String> tail = path.subList(3, path.size());
        if (tail.size() < 2 || !KEY_ITEMS.equals(tail.get(0)))
        {
            return "Case-variant removal at '" + variantsWhere //$NON-NLS-1$
                + "' needs items/<index>. Copy the exact address from get."; //$NON-NLS-1$
        }
        int variantIndex = index(tail.get(1), variants.getItems().size(), variantsWhere + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        if (tail.size() == 2)
        {
            variants.getItems().remove(variantIndex);
            return null;
        }
        DataCompositionUserFieldsVariant variant = variants.getItems().get(variantIndex);
        String variantWhere = variantsWhere + "/items/" + tail.get(1); //$NON-NLS-1$
        if (!"filter".equals(tail.get(2))) //$NON-NLS-1$
        {
            return "Case-variant child address at '" + variantWhere //$NON-NLS-1$
                + "' does not select an exact removable filter node."; //$NON-NLS-1$
        }
        if (tail.size() == 3)
        {
            variant.setFilter(null);
            return null;
        }
        return removeFilterPath(variant.getFilter(), tail.subList(3, tail.size()),
            variantWhere + "/filter"); //$NON-NLS-1$
    }

    private static String removeFilterPath(DataCompositionFilter filter, List<String> path, String where)
    {
        if (filter == null) return "No filter exists at '" + where + "'. Re-run get."; //$NON-NLS-1$ //$NON-NLS-2$
        return removeFilterItems(filter.getItems(), path, where);
    }

    private static String removeSelectionPath(DataCompositionSelectedFields selection,
        List<String> path, String where)
    {
        if (selection == null) return "No selection exists at '" + where + "'. Re-run get."; //$NON-NLS-1$ //$NON-NLS-2$
        return removeSelectedItems(selection.getItems(), path, where);
    }

    /** Mirrors {@link #applySelectedItemsPath}: selection groups may contain selection groups. */
    private static String removeSelectedItems(List<SelectedItem> items, List<String> path,
        String where)
    {
        if (path.size() < 2 || !KEY_ITEMS.equals(path.get(0)))
            return "Selection removal at '" + where //$NON-NLS-1$
                + "' needs items/<index>. Copy it from get."; //$NON-NLS-1$
        int selected = index(path.get(1), items.size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        if (path.size() == 2)
        {
            items.remove(selected);
            return null;
        }
        SelectedItem item = items.get(selected);
        if (item instanceof DataCompositionSelectedFieldGroup && path.size() > 2
            && KEY_ITEMS.equals(path.get(2)))
        {
            return removeSelectedItems(((DataCompositionSelectedFieldGroup)item).getItems(),
                path.subList(2, path.size()), where + "/items/" + path.get(1)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "Selection address at '" + where //$NON-NLS-1$
            + "' does not select exactly one removable node."; //$NON-NLS-1$
    }

    private static String removeConditionalAppearancePath(
        DataCompositionConditionalAppearance holder, List<String> path, String where)
    {
        if (holder == null)
            return "No conditional appearance exists at '" + where + "'. Re-run get."; //$NON-NLS-1$ //$NON-NLS-2$
        if (path.size() < 2 || !KEY_ITEMS.equals(path.get(0)))
            return "Conditional-appearance removal at '" + where //$NON-NLS-1$
                + "' needs items/<index>. Copy it from get."; //$NON-NLS-1$
        int selected = index(path.get(1), holder.getItems().size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        if (path.size() == 2)
        {
            holder.getItems().remove(selected);
            return null;
        }
        DataCompositionConditionalAppearanceItem item = holder.getItems().get(selected);
        String child = path.get(2);
        String childWhere = where + "/items/" + path.get(1) + "/" + child; //$NON-NLS-1$ //$NON-NLS-2$
        if (path.size() == 3)
        {
            if ("selection".equals(child)) { item.setSelection(null); return null; } //$NON-NLS-1$
            if ("filter".equals(child)) { item.setFilter(null); return null; } //$NON-NLS-1$
            if ("appearance".equals(child)) { item.setAppearance(null); return null; } //$NON-NLS-1$
        }
        List<String> tail = path.subList(3, path.size());
        if ("selection".equals(child)) //$NON-NLS-1$
            return removeIndexed(item.getSelection() == null ? null : item.getSelection().getItems(),
                tail, childWhere);
        if ("filter".equals(child)) //$NON-NLS-1$
            return removeFilterPath(item.getFilter(), tail, childWhere);
        return "Conditional-appearance child address at '" + childWhere //$NON-NLS-1$
            + "' does not select exactly one removable node."; //$NON-NLS-1$
    }

    private static String removeFilterItems(List<FilterItem> items, List<String> path, String where)
    {
        if (path.size() < 2 || !KEY_ITEMS.equals(path.get(0)))
            return "Filter removal at '" + where + "' needs items/<index>. Copy it from get."; //$NON-NLS-1$ //$NON-NLS-2$
        int selected = index(path.get(1), items.size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        if (path.size() == 2)
        {
            items.remove(selected);
            return null;
        }
        FilterItem item = items.get(selected);
        if (item instanceof DataCompositionFilterItemGroup && path.size() > 2
            && KEY_ITEMS.equals(path.get(2)))
        {
            return removeFilterItems(((DataCompositionFilterItemGroup)item).getItems(),
                path.subList(2, path.size()), where + "/items/" + path.get(1)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "Filter address at '" + where + "' does not select exactly one removable node."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String removeIndexed(List<?> items, List<String> path, String where)
    {
        if (items == null) return "No collection exists at '" + where + "'. Re-run get."; //$NON-NLS-1$ //$NON-NLS-2$
        if (path.size() != 2 || !KEY_ITEMS.equals(path.get(0)))
            return "Removal at '" + where + "' needs items/<index>. Copy the exact address from get."; //$NON-NLS-1$ //$NON-NLS-2$
        int selected = index(path.get(1), items.size(), where + "/items"); //$NON-NLS-1$
        if (indexError != null) return indexError;
        items.remove(selected);
        return null;
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

    private static List<Integer> findVariants(List<SettingsVariant> variants, String name)
    {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++)
        {
            if (name.equals(variants.get(i).getName()))
            {
                result.add(Integer.valueOf(i));
            }
        }
        return result;
    }

    private static List<Integer> findVariantSelectors(List<SettingsVariant> variants, String selector)
    {
        Set<Integer> result = new LinkedHashSet<>(findVariants(variants, selector));
        if (DcsAddress.isZeroBasedIndex(selector))
        {
            int index = Integer.parseInt(selector);
            if (index < variants.size()) result.add(Integer.valueOf(index));
        }
        return new ArrayList<>(result);
    }

    private static String ambiguousVariant(String action, String name, String address, int count)
    {
        return "Cannot " + action + " variant '" + name + "' at '" + address //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' because natural key '" + name + "' matches " + count //$NON-NLS-1$ //$NON-NLS-2$
            + " existing nodes. The address is ambiguous; disambiguate the duplicates in the DCS " //$NON-NLS-1$
            + "designer first, re-run get, and retry."; //$NON-NLS-1$
    }

    private static String ambiguousVariantSelector(String action, String selector, String address,
        int count)
    {
        return "Cannot " + action + " variant '" + selector + "' at '" + address //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' because selector '" + selector + "' identifies " + count //$NON-NLS-1$ //$NON-NLS-2$
            + " existing nodes. The address is ambiguous; disambiguate the conflicting natural " //$NON-NLS-1$
            + "key and index fallback in the DCS designer first, re-run get, and retry."; //$NON-NLS-1$
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

    private interface GroupingSettingsAccess extends SettingsAccess
    {
        DataCompositionGroupFields groupFields();
        void groupFields(DataCompositionGroupFields value);
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

    private static final class GroupSettingsAccess implements GroupingSettingsAccess
    {
        private final DataCompositionGroup group;
        GroupSettingsAccess(DataCompositionGroup group) { this.group = group; }
        @Override public DataCompositionSelectedFields selection() { return group.getSelection(); }
        @Override public void selection(DataCompositionSelectedFields value) { group.setSelection(value); }
        @Override public DataCompositionFilter filter() { return group.getFilter(); }
        @Override public void filter(DataCompositionFilter value) { group.setFilter(value); }
        @Override public DataCompositionOrder order() { return group.getOrder(); }
        @Override public void order(DataCompositionOrder value) { group.setOrder(value); }
        @Override public DataCompositionGroupFields groupFields() { return group.getGroupFields(); }
        @Override public void groupFields(DataCompositionGroupFields value) { group.setGroupFields(value); }
    }

    private static final class TableGroupSettingsAccess implements GroupingSettingsAccess
    {
        private final DataCompositionTableGroup group;
        TableGroupSettingsAccess(DataCompositionTableGroup group) { this.group = group; }
        @Override public DataCompositionSelectedFields selection() { return group.getSelection(); }
        @Override public void selection(DataCompositionSelectedFields value) { group.setSelection(value); }
        @Override public DataCompositionFilter filter() { return group.getFilter(); }
        @Override public void filter(DataCompositionFilter value) { group.setFilter(value); }
        @Override public DataCompositionOrder order() { return group.getOrder(); }
        @Override public void order(DataCompositionOrder value) { group.setOrder(value); }
        @Override public DataCompositionGroupFields groupFields() { return group.getGroupFields(); }
        @Override public void groupFields(DataCompositionGroupFields value) { group.setGroupFields(value); }
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
                if (defaultSettings == null)
                {
                    schema.setDefaultSettings(null);
                }
                else if (schema.getDefaultSettings() == null)
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

    static final class ValueResult
    {
        final Value value; final String error;
        private ValueResult(Value value, String error) { this.value = value; this.error = error; }
        static ValueResult success(Value value) { return new ValueResult(value, null); }
        static ValueResult failure(String error) { return new ValueResult(null, error); }
    }

    private static final class AvailableParametersResult
    {
        final DcsAvailableParameterCollection parameters; final String error;
        private AvailableParametersResult(DcsAvailableParameterCollection parameters, String error)
        { this.parameters = parameters; this.error = error; }
        static AvailableParametersResult success(DcsAvailableParameterCollection parameters)
        { return new AvailableParametersResult(parameters, null); }
        static AvailableParametersResult failure(String error)
        { return new AvailableParametersResult(null, error); }
    }

    private static final class AppearanceParameterSpec
    {
        final JsonElement value; final boolean use; final String error;
        private AppearanceParameterSpec(JsonElement value, boolean use, String error)
        { this.value = value; this.use = use; this.error = error; }
        static AppearanceParameterSpec success(JsonElement value, boolean use)
        { return new AppearanceParameterSpec(value, use, null); }
        static AppearanceParameterSpec failure(String error)
        { return new AppearanceParameterSpec(null, false, error); }
    }

    private static final class FieldResult
    {
        final DataCompositionField value; final String error;
        private FieldResult(DataCompositionField value, String error) { this.value = value; this.error = error; }
        static FieldResult success(DataCompositionField value) { return new FieldResult(value, null); }
        static FieldResult failure(String error) { return new FieldResult(null, error); }
    }

    static final class AppearanceResult
    {
        final DataCompositionAppearance value; final String error;
        private AppearanceResult(DataCompositionAppearance value, String error)
        { this.value = value; this.error = error; }
        static AppearanceResult success(DataCompositionAppearance value)
        { return new AppearanceResult(value, null); }
        static AppearanceResult failure(String error)
        { return new AppearanceResult(null, error); }
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
