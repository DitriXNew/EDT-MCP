/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.DerivedField;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.SourceType;
import com._1c.g5.v8.dt.metadata.mdclass.BasicDbObject;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.HierarchyType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.FieldListSupport;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * modify_metadata on a field list - {@code inputByString} / {@code dataLockFields} (issue #740).
 *
 * <p>Driven through the SAME preparation the write runs: {@code prepareFormMemberChanges} (the headless
 * entry ModifyMetadataToolTest uses) for one property, and {@code validateAndPrepare} - the mdclass
 * path itself - for the batch guard. Only field ids may cross into the write.</p>
 */
public class ModifyMetadataToolFieldListTest
{
    // ==================== fixtures ====================

    private static Field standard(BasicDbObject owner, String name, String nameRu, SourceType... sources)
    {
        Field field = McoreFactory.eINSTANCE.createField();
        field.setName(name);
        field.setNameRu(nameRu);
        field.setFieldIdPrefix("StandardAttribute"); //$NON-NLS-1$
        field.getSourceTypes().addAll(Arrays.asList(sources));
        owner.getFields().add(field);
        return field;
    }

    /**
     * Catalog.Goods as EDT infers it with codeLength 9 and descriptionLength 25, EDT's defaults for a new
     * catalog: Code and Description offered for both lists; a non-indexed String(10) attribute Weight
     * lockable but not offered for input by string.
     */
    private static Catalog goods()
    {
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        goods.setCodeLength(9);
        goods.setDescriptionLength(25);
        standard(goods, "Description", "\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.INPUT_BY_STRING, SourceType.FIELDS_FOR_LOCKING);
        standard(goods, "Code", "\u041A\u043E\u0434", SourceType.STANDARD_FIELDS, SourceType.INPUT_BY_STRING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);
        CatalogAttribute weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        goods.getAttributes().add(weight);
        DerivedField field = McoreFactory.eINSTANCE.createDerivedField();
        field.setName("Weight"); //$NON-NLS-1$
        field.setNameRu("Weight"); //$NON-NLS-1$
        field.setSource(weight);
        field.setFieldIdPrefix("Attribute"); //$NON-NLS-1$
        field.getSourceTypes().add(SourceType.FIELDS_FOR_LOCKING);
        goods.getFields().add(field);
        return goods;
    }

    private static JsonArray array(String... entries)
    {
        JsonArray array = new JsonArray();
        for (String entry : entries)
        {
            array.add(entry);
        }
        return array;
    }

    private static JsonObject property(String name, JsonElement value)
    {
        JsonObject property = new JsonObject();
        property.addProperty("name", name); //$NON-NLS-1$
        property.add("value", value); //$NON-NLS-1$
        return property;
    }

    private static JsonObject property(String name, String value)
    {
        return property(name, new JsonPrimitive(value));
    }

    /** The per-property preparation, unwrapped so a refusal arrives as itself. */
    private static List<?> prepared(EObject target, JsonObject... properties) throws Throwable
    {
        Method prepare = ModifyMetadataTool.class.getDeclaredMethod("prepareFormMemberChanges", //$NON-NLS-1$
            MetadataScope.class, Version.class, EObject.class, List.class, MdNameNormalizer.Report.class);
        prepare.setAccessible(true);
        try
        {
            return (List<?>)prepare.invoke(new ModifyMetadataTool(), null, null, target,
                Arrays.asList(properties), new MdNameNormalizer.Report(true));
        }
        catch (InvocationTargetException e)
        {
            throw e.getCause();
        }
    }

    /** The mdclass path's own validation of a whole batch: its JSON error, or null. */
    private static String validateAndPrepare(MdObject target, List<Object> changesOut,
        JsonObject... properties) throws Exception
    {
        Method validate = ModifyMetadataTool.class.getDeclaredMethod("validateAndPrepare", IProject.class, //$NON-NLS-1$
            MetadataScope.class, Configuration.class, Version.class, MdObject.class, List.class, List.class,
            MdNameNormalizer.Report.class, boolean.class);
        validate.setAccessible(true);
        return (String)validate.invoke(new ModifyMetadataTool(), null, null, null, null, target,
            Arrays.asList(properties), changesOut, new MdNameNormalizer.Report(true), false);
    }

    private static Object fieldOf(Object owner, String name) throws Exception
    {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** The single PreparedChange of a one-property preparation. */
    private static Object onlyChange(List<?> holderChanges) throws Exception
    {
        assertEquals(1, holderChanges.size());
        return fieldOf(holderChanges.get(0), "change"); //$NON-NLS-1$
    }

    private static String errorText(String errorJson)
    {
        assertNotNull(errorJson);
        JsonObject parsed = JsonParser.parseString(errorJson).getAsJsonObject();
        assertFalse(parsed.get("success").getAsBoolean()); //$NON-NLS-1$
        return parsed.get("error").getAsString(); //$NON-NLS-1$
    }

    private static String refusal(EObject target, JsonObject property) throws Throwable
    {
        try
        {
            prepared(target, property);
        }
        catch (FormValidationException e)
        {
            return errorText(e.json());
        }
        fail("expected a refusal for " + property); //$NON-NLS-1$
        return null;
    }

    private static void assertContains(String text, String expected)
    {
        assertTrue("expected <" + expected + "> in: " + text, text.contains(expected)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== one property ====================

    /**
     * RU: the address in Russian prepares a FIELD_LIST change whose payload is the field id - the one
     * value that survives EDT rebuilding the derived fields between preparation and write.
     */
    @Test
    public void testARussianAddressPreparesAFieldListChangeCarryingFieldIdsOnly() throws Throwable
    {
        // Справочник.Goods.СтандартныйРеквизит.Наименование
        Object change = onlyChange(prepared(goods(), property("inputByString", //$NON-NLS-1$
            array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435")))); //$NON-NLS-1$

        assertEquals("FIELD_LIST", String.valueOf(fieldOf(change, "kind"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Collections.singletonList("StandardAttribute:Description"), //$NON-NLS-1$
            fieldOf(change, "scalarValue")); //$NON-NLS-1$
    }

    @Test
    public void testAnEntryEdtDoesNotOfferIsRefusedBeforeAnythingIsPrepared() throws Throwable
    {
        String error = refusal(goods(), property("inputByString", //$NON-NLS-1$
            array("Catalog.Goods.Attribute.Weight"))); //$NON-NLS-1$

        assertContains(error, "Catalog.Goods.Attribute.Weight"); //$NON-NLS-1$
        assertContains(error, "indexed"); //$NON-NLS-1$
        assertFalse(error, error.contains("is not assignable")); //$NON-NLS-1$
    }

    @Test
    public void testDataLockFieldsArePreparedWithTheirOwnEligibility() throws Throwable
    {
        Object change = onlyChange(prepared(goods(), property("dataLockFields", //$NON-NLS-1$
            array("Catalog.Goods.Attribute.Weight", "Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("FIELD_LIST", String.valueOf(fieldOf(change, "kind"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("Attribute:Weight", "StandardAttribute:Code"), //$NON-NLS-1$ //$NON-NLS-2$
            fieldOf(change, "scalarValue")); //$NON-NLS-1$
    }

    /** C2: an extension cannot change an adopted object's lists, so the request is refused up front. */
    @Test
    public void testAnAdoptedTargetIsRefused() throws Throwable
    {
        Catalog adopted = goods();
        adopted.setObjectBelonging(ObjectBelonging.ADOPTED);

        String error = refusal(adopted, property("inputByString", //$NON-NLS-1$
            array("Catalog.Goods.StandardAttribute.Code"))); //$NON-NLS-1$
        assertContains(error, "adopted"); //$NON-NLS-1$
        assertContains(error, "base configuration"); //$NON-NLS-1$
    }

    // ==================== the batch guard (C1) ====================

    /*
     * A field list is refused only when the SAME call changes whether EDT offers a standard field the list
     * names: the guard judges EDT's rule for that field on the object's properties before the call and on
     * the values the call leaves. Ending the offer gets one refusal (leave the field out, or keep the
     * property), starting it another (set the property first, then the list in a separate call); a call
     * that leaves the offer as it is shares the call with the list. The fixtures are faithful to EDT's
     * MdFieldInferrer: every standard field the kind has is there, with the source types the object's own
     * properties give it.
     */

    // The Russian names EDT's MdFieldInferrer writes into nameRu (DbViewTerms, md 27.1 and 28.0).
    private static final String RU_CODE = "\u041A\u043E\u0434"; //$NON-NLS-1$
    private static final String RU_DESCRIPTION = "\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435"; //$NON-NLS-1$
    private static final String RU_NUMBER = "\u041D\u043E\u043C\u0435\u0440"; //$NON-NLS-1$
    private static final String RU_PARENT = "\u0420\u043E\u0434\u0438\u0442\u0435\u043B\u044C"; //$NON-NLS-1$
    private static final String RU_IS_FOLDER = "\u042D\u0442\u043E\u0413\u0440\u0443\u043F\u043F\u0430"; //$NON-NLS-1$
    private static final String RU_OWNER = "\u0412\u043B\u0430\u0434\u0435\u043B\u0435\u0446"; //$NON-NLS-1$
    private static final String RU_ACTION_PERIOD_IS_BASIC = "\u041F\u0435\u0440\u0438\u043E\u0434\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u044F\u0411\u0430\u0437\u043E\u0432\u044B\u0439"; //$NON-NLS-1$
    private static final String RU_DELETION_MARK = "\u041F\u043E\u043C\u0435\u0442\u043A\u0430\u0423\u0434\u0430\u043B\u0435\u043D\u0438\u044F"; //$NON-NLS-1$

    /** The two hierarchy types, as the wire spells them (MdClass.xcore enum HierarchyType). */
    private static final String FOLDERS_AND_ITEMS = "HierarchyFoldersAndItems"; //$NON-NLS-1$
    private static final String ITEMS = "HierarchyOfItems"; //$NON-NLS-1$

    /** Every standard field a cell asks about: each one a property decides, and DeletionMark, which none does. */
    private static final List<String> ASKED_FIELDS = Arrays.asList("Code", "Description", "Number", "Parent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "IsFolder", "Owner", "ActionPeriodIsBasic", "DeletionMark"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * The eight kinds, each with the properties of its own that decide whether EDT offers one of the asked
     * fields (MdClass.xcore) - in the order a refusal names them. Spelled out here, never read from
     * FieldListSupport.
     */
    private static final Map<String, List<String>> DECIDING = new LinkedHashMap<>();

    /** What each kind's object is called in these tests. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    /** The nameRu of each asked field. */
    private static final Map<String, String> RUSSIAN = new LinkedHashMap<>();

    static
    {
        DECIDING.put("Catalog", Arrays.asList("codeLength", "descriptionLength", "hierarchical", "hierarchyType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "owners")); //$NON-NLS-1$
        DECIDING.put("Document", Arrays.asList("numberLength")); //$NON-NLS-1$ //$NON-NLS-2$
        DECIDING.put("ChartOfCharacteristicTypes", Arrays.asList("codeLength", "descriptionLength", "hierarchical")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        DECIDING.put("ChartOfAccounts", Arrays.asList("codeLength", "descriptionLength")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DECIDING.put("ChartOfCalculationTypes", Arrays.asList("codeLength", "descriptionLength", "actionPeriodUse")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        DECIDING.put("ExchangePlan", Arrays.asList("codeLength", "descriptionLength")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DECIDING.put("BusinessProcess", Arrays.asList("numberLength")); //$NON-NLS-1$ //$NON-NLS-2$
        DECIDING.put("Task", Arrays.asList("descriptionLength", "numberLength")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        NAMES.put("Catalog", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        NAMES.put("Document", "Order"); //$NON-NLS-1$ //$NON-NLS-2$
        NAMES.put("ChartOfCharacteristicTypes", "Kinds"); //$NON-NLS-1$ //$NON-NLS-2$
        NAMES.put("ChartOfAccounts", "Accounts"); //$NON-NLS-1$ //$NON-NLS-2$
        NAMES.put("ChartOfCalculationTypes", "Accruals"); //$NON-NLS-1$ //$NON-NLS-2$
        NAMES.put("ExchangePlan", "Branches"); //$NON-NLS-1$ //$NON-NLS-2$
        NAMES.put("BusinessProcess", "Approval"); //$NON-NLS-1$ //$NON-NLS-2$
        NAMES.put("Task", "Job"); //$NON-NLS-1$ //$NON-NLS-2$
        RUSSIAN.put("Code", RU_CODE); //$NON-NLS-1$
        RUSSIAN.put("Description", RU_DESCRIPTION); //$NON-NLS-1$
        RUSSIAN.put("Number", RU_NUMBER); //$NON-NLS-1$
        RUSSIAN.put("Parent", RU_PARENT); //$NON-NLS-1$
        RUSSIAN.put("IsFolder", RU_IS_FOLDER); //$NON-NLS-1$
        RUSSIAN.put("Owner", RU_OWNER); //$NON-NLS-1$
        RUSSIAN.put("ActionPeriodIsBasic", RU_ACTION_PERIOD_IS_BASIC); //$NON-NLS-1$
        RUSSIAN.put("DeletionMark", RU_DELETION_MARK); //$NON-NLS-1$
    }

    /**
     * The two values a deciding property walks through in the cell test: a length 0 or EDT's default for a
     * new object, a flag off or on, the two hierarchy types, no owner or one.
     */
    private static List<Object> domain(String property)
    {
        switch (property)
        {
            case "codeLength": //$NON-NLS-1$
                return Arrays.<Object> asList(0, 9);
            case "descriptionLength": //$NON-NLS-1$
                return Arrays.<Object> asList(0, 25);
            case "numberLength": //$NON-NLS-1$
                return Arrays.<Object> asList(0, 9);
            case "hierarchyType": //$NON-NLS-1$
                return Arrays.<Object> asList(FOLDERS_AND_ITEMS, ITEMS);
            default: // hierarchical, actionPeriodUse, owners (whether the catalog has an owner)
                return Arrays.<Object> asList(false, true);
        }
    }

    /** EDT's defaults for a new object: the lengths 9 / 25 / 9, flat, of folders and items, no owner, no action period. */
    private static Map<String, Object> defaults(String kind)
    {
        Map<String, Object> state = new LinkedHashMap<>();
        for (String property : DECIDING.get(kind))
        {
            List<Object> values = domain(property);
            state.put(property, property.endsWith("Length") ? values.get(1) : values.get(0)); //$NON-NLS-1$
        }
        return state;
    }

    private static boolean positive(Map<String, Object> state, String property)
    {
        Object value = state.get(property);
        return value instanceof Integer && (Integer)value > 0;
    }

    private static boolean on(Map<String, Object> state, String property)
    {
        return Boolean.TRUE.equals(state.get(property));
    }

    /** Whether EDT creates the standard field on the kind at all - it does whatever the properties say. */
    private static boolean exists(String kind, String field)
    {
        List<String> deciding = DECIDING.get(kind);
        switch (field)
        {
            case "Code": //$NON-NLS-1$
                return deciding.contains("codeLength"); //$NON-NLS-1$
            case "Description": //$NON-NLS-1$
                return deciding.contains("descriptionLength"); //$NON-NLS-1$
            case "Number": //$NON-NLS-1$
                return deciding.contains("numberLength"); //$NON-NLS-1$
            case "Parent": //$NON-NLS-1$
                return deciding.contains("hierarchical") || "ChartOfAccounts".equals(kind); //$NON-NLS-1$ //$NON-NLS-2$
            case "IsFolder": //$NON-NLS-1$
                return deciding.contains("hierarchical"); //$NON-NLS-1$
            case "Owner": //$NON-NLS-1$
                return deciding.contains("owners"); //$NON-NLS-1$
            case "ActionPeriodIsBasic": //$NON-NLS-1$
                return deciding.contains("actionPeriodUse"); //$NON-NLS-1$
            default: // DeletionMark
                return true;
        }
    }

    /**
     * Whether EDT's MdFieldInferrer gives an existing standard field of a {@code kind} object in
     * {@code state} the source type of the list - FIELDS_FOR_LOCKING when {@code dataLock}, else
     * INPUT_BY_STRING (md 27.1 MdFieldInferrer.xtend :298-1150, the same rules in the md 28.0 class). An
     * exchange plan's Code and Description get both whatever the lengths; a chart of accounts' Parent is
     * lockable whatever its properties.
     */
    private static boolean inferred(String kind, boolean dataLock, String field, Map<String, Object> state)
    {
        switch (field)
        {
            case "Code": //$NON-NLS-1$
                return "ExchangePlan".equals(kind) || positive(state, "codeLength"); //$NON-NLS-1$ //$NON-NLS-2$
            case "Description": //$NON-NLS-1$
                return "ExchangePlan".equals(kind) || positive(state, "descriptionLength"); //$NON-NLS-1$ //$NON-NLS-2$
            case "Number": //$NON-NLS-1$
                return positive(state, "numberLength"); //$NON-NLS-1$
            case "Parent": //$NON-NLS-1$
                return dataLock && ("ChartOfAccounts".equals(kind) || on(state, "hierarchical")); //$NON-NLS-1$ //$NON-NLS-2$
            case "IsFolder": //$NON-NLS-1$
                return dataLock && on(state, "hierarchical") && !ITEMS.equals(state.get("hierarchyType")); //$NON-NLS-1$ //$NON-NLS-2$
            case "Owner": //$NON-NLS-1$
                return dataLock && on(state, "owners"); //$NON-NLS-1$
            case "ActionPeriodIsBasic": //$NON-NLS-1$
                return dataLock && on(state, "actionPeriodUse"); //$NON-NLS-1$
            default: // DeletionMark: lockable on every kind, never an input-by-string field
                return dataLock;
        }
    }

    /**
     * Whether EDT accepts the field in the list: the inferrer's offer, narrowed by EDT's validator, which
     * locks an exchange plan's Code / Description only while the length is not 0
     * (FixedFieldDescriptorProvider.createExchangePlanDataLockFields, metadata 19.0 and 19.1).
     */
    private static boolean offered(String kind, boolean dataLock, String field, Map<String, Object> state)
    {
        boolean validatorRejects = byValidator(kind, dataLock, field)
            && !positive(state, "Code".equals(field) ? "codeLength" : "descriptionLength"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return exists(kind, field) && inferred(kind, dataLock, field, state) && !validatorRejects;
    }

    /**
     * Whether only EDT's validator decides the field for the list: an exchange plan's Code and Description
     * for data locking, which EDT's editor offers whatever the length (MdFieldInferrer gives both
     * FIELDS_FOR_LOCKING unconditionally) - so a refusal about them names the validator, and never says EDT
     * would stop offering them.
     */
    private static boolean byValidator(String kind, boolean dataLock, String field)
    {
        return "ExchangePlan".equals(kind) && dataLock && ("Code".equals(field) || "Description".equals(field)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The properties of the kind whose change can change that offer, in the order a refusal names them. */
    private static List<String> deciders(String kind, boolean dataLock, String field)
    {
        List<String> candidates;
        switch (field)
        {
            case "Code": //$NON-NLS-1$
                candidates = "ExchangePlan".equals(kind) && !dataLock ? fields() : fields("codeLength"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "Description": //$NON-NLS-1$
                candidates = "ExchangePlan".equals(kind) && !dataLock ? fields() : fields("descriptionLength"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "Number": //$NON-NLS-1$
                candidates = fields("numberLength"); //$NON-NLS-1$
                break;
            case "Parent": //$NON-NLS-1$
                candidates = dataLock ? fields("hierarchical") : fields(); //$NON-NLS-1$
                break;
            case "IsFolder": //$NON-NLS-1$
                candidates = dataLock ? fields("hierarchical", "hierarchyType") : fields(); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "Owner": //$NON-NLS-1$
                candidates = dataLock ? fields("owners") : fields(); //$NON-NLS-1$
                break;
            case "ActionPeriodIsBasic": //$NON-NLS-1$
                candidates = dataLock ? fields("actionPeriodUse") : fields(); //$NON-NLS-1$
                break;
            default:
                candidates = fields();
        }
        List<String> deciders = new ArrayList<>(candidates);
        deciders.retainAll(DECIDING.get(kind));
        return deciders;
    }

    private static List<String> fields(String... names)
    {
        return Arrays.asList(names);
    }

    /**
     * A {@code kind} object in {@code state} as EDT infers it: every standard field the kind has, each with
     * the source types {@link #inferred} gives it - the properties only add a source type, EDT creates the
     * field either way.
     */
    private static BasicDbObject fixture(String kind, Map<String, Object> state)
    {
        BasicDbObject object = (BasicDbObject)MdClassFactory.eINSTANCE.create(
            (EClass)MdClassPackage.eINSTANCE.getEClassifier(kind));
        object.setName(NAMES.get(kind));
        for (Map.Entry<String, Object> entry : state.entrySet())
        {
            set(object, entry.getKey(), entry.getValue());
        }
        for (String field : ASKED_FIELDS)
        {
            if (exists(kind, field))
            {
                List<SourceType> sources = new ArrayList<>();
                sources.add(SourceType.STANDARD_FIELDS);
                if (inferred(kind, false, field, state))
                {
                    sources.add(SourceType.INPUT_BY_STRING);
                }
                if (inferred(kind, true, field, state))
                {
                    sources.add(SourceType.FIELDS_FOR_LOCKING);
                }
                standard(object, field, RUSSIAN.get(field), sources.toArray(new SourceType[0]));
            }
        }
        return object;
    }

    /** Sets one deciding property on a fixture: owners as "has an owner", hierarchyType by its wire name. */
    private static void set(BasicDbObject object, String property, Object value)
    {
        if ("owners".equals(property)) //$NON-NLS-1$
        {
            if (Boolean.TRUE.equals(value))
            {
                Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
                owner.setName("Owners"); //$NON-NLS-1$
                ((Catalog)object).getOwners().add(owner);
            }
            return;
        }
        Object model = FOLDERS_AND_ITEMS.equals(value) ? HierarchyType.HIERARCHY_FOLDERS_AND_ITEMS
            : ITEMS.equals(value) ? HierarchyType.HIERARCHY_OF_ITEMS : value;
        object.eSet(object.eClass().getEStructuralFeature(property), model);
    }

    /** A faithful {@code kind} object: EDT's defaults for a new one, with {@code overrides} (name, value, ...). */
    private static BasicDbObject target(String kind, Object... overrides)
    {
        Map<String, Object> state = defaults(kind);
        for (int i = 0; i < overrides.length; i += 2)
        {
            state.put((String)overrides[i], overrides[i + 1]);
        }
        return fixture(kind, state);
    }

    /** A deciding property's wire value: owners as an array of owner FQNs, the rest as JSON scalars. */
    private static JsonElement wire(String property, Object value)
    {
        if ("owners".equals(property)) //$NON-NLS-1$
        {
            return Boolean.TRUE.equals(value) ? array("Catalog.Others") : new JsonArray(); //$NON-NLS-1$
        }
        if (value instanceof Integer)
        {
            return new JsonPrimitive((Integer)value);
        }
        return value instanceof Boolean ? new JsonPrimitive((Boolean)value) : new JsonPrimitive(String.valueOf(value));
    }

    private static JsonObject flag(String name, boolean value)
    {
        return property(name, new JsonPrimitive(value));
    }

    private static JsonObject integer(String name, int value)
    {
        return property(name, new JsonPrimitive(value));
    }

    /** The batch guard's own verdict: its refusal as plain text, or {@code null} when it lets the batch pass. */
    private static String batchRefusal(EObject target, JsonObject... properties)
    {
        String json = ModifyMetadataTool.fieldListBatchError(target, Arrays.asList(properties));
        return json == null ? null : errorText(json);
    }

    /** 'a', 'a' and 'b', 'a', 'b' and 'c' - the properties a refusal is about, as it names them. */
    private static String quoted(List<String> names, String conjunction)
    {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.size(); i++)
        {
            if (i > 0)
            {
                text.append(i == names.size() - 1 ? conjunction : ", "); //$NON-NLS-1$
            }
            text.append('\'').append(names.get(i)).append('\'');
        }
        return text.toString();
    }

    /**
     * What the refusal fails to say, or {@code null} when it says all of it: the list, the field it names
     * and the properties the call changes, and - by direction - why and what to do instead. A call that
     * takes the field away is told to leave it out or keep the property, never to make a second call; a
     * call that brings it back gets the separate-call recipe and is never told the field is no longer
     * offered. The reason names whoever decides the field: EDT's validator for a field only the validator
     * decides ({@link #byValidator}), with no claim about EDT's offer - EDT's editor offers such a field
     * whatever the length - and EDT itself for every other field, never its validator.
     */
    private static String mismatch(String refusal, List<String> properties, String list, String address,
        boolean takesAway, boolean byValidator)
    {
        String direction = takesAway ? "takes the field away" : "brings the field back"; //$NON-NLS-1$ //$NON-NLS-2$
        if (refusal == null)
        {
            return "expected the batch guard to refuse '" + list + "' naming " + address + " with " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + quoted(properties, " and ") + " (the call " + direction + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> fragments = new ArrayList<>();
        fragments.add("'" + list + "' cannot be set in the same call as " + quoted(properties, " and ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " while it names " + address); //$NON-NLS-1$
        if (takesAway)
        {
            fragments.add((byValidator ? "EDT's validator accepts that field now, but would reject it" //$NON-NLS-1$
                : "EDT offers that field now, but would no longer offer it") //$NON-NLS-1$
                + " once this call has changed " + quoted(properties, " and ")); //$NON-NLS-1$ //$NON-NLS-2$
            fragments.add("Leave " + address + " out of the list, or do not change " + quoted(properties, " or ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "."); //$NON-NLS-1$
        }
        else
        {
            fragments.add((byValidator ? "whether EDT's validator accepts that field" : "whether EDT offers that field") //$NON-NLS-1$ //$NON-NLS-2$
                + " depends on " + quoted(properties, " and ")); //$NON-NLS-1$ //$NON-NLS-2$
            fragments.add("Set " + quoted(properties, " and ") + " first, then '" + list + "' in a separate call"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fragments.add("or leave " + address + " out of the list."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String fragment : fragments)
        {
            if (!refusal.contains(fragment))
            {
                return "expected <" + fragment + "> in: " + refusal; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        String judge = byValidator ? "a field only EDT's validator decides" : "a field EDT itself decides"; //$NON-NLS-1$ //$NON-NLS-2$
        List<String> foreigns = new ArrayList<>();
        foreigns.add(takesAway ? "separate call" : "no longer offer"); //$NON-NLS-1$ //$NON-NLS-2$
        // EDT's editor offers a field only its validator decides whatever the property says: no claim about
        // EDT's offer may be made of it, and no field EDT itself decides is blamed on the validator.
        foreigns.addAll(byValidator ? Arrays.asList("EDT offers that field", "no longer offer", "EDT does not offer") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            : Arrays.asList("EDT's validator")); //$NON-NLS-1$
        for (String foreign : foreigns)
        {
            if (refusal.contains(foreign))
            {
                return "the refusal must not say <" + foreign + "> when the call " + direction + " (" + judge //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "): " + refusal; //$NON-NLS-1$
            }
        }
        return null;
    }

    /** The take-away refusal: EDT offers the field now, not once the call has changed {@code properties}. */
    private static void assertTakesAway(String refusal, String list, String address, String... properties)
    {
        String mismatch = mismatch(refusal, Arrays.asList(properties), list, address, true, false);
        assertNull(mismatch, mismatch);
    }

    /** The separate-call refusal: EDT offers the field only once the call has changed {@code properties}. */
    private static void assertBringsBack(String refusal, String list, String address, String... properties)
    {
        String mismatch = mismatch(refusal, Arrays.asList(properties), list, address, false, false);
        assertNull(mismatch, mismatch);
    }

    /** The take-away refusal of a field only EDT's validator decides: it accepts the field now, not after. */
    private static void assertValidatorTakesAway(String refusal, String list, String address, String... properties)
    {
        String mismatch = mismatch(refusal, Arrays.asList(properties), list, address, true, true);
        assertNull(mismatch, mismatch);
    }

    /** The separate-call refusal of a field only EDT's validator decides: it accepts the field only after. */
    private static void assertValidatorBringsBack(String refusal, String list, String address, String... properties)
    {
        String mismatch = mismatch(refusal, Arrays.asList(properties), list, address, false, true);
        assertNull(mismatch, mismatch);
    }

    // ---- a property change that leaves the offer as it is shares the call (PR #744, second review) ----

    /** codeLength 9 -> 10 keeps Code offered for input by string: the list naming it is judged right as it stands. */
    @Test
    public void testACodeLengthThatKeepsCodeOfferedSharesTheCallWithAListNamingIt() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(goods(), changes, integer("codeLength", 10), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());
    }

    /** descriptionLength 25 -> 50 keeps Description lockable, the list first. */
    @Test
    public void testADescriptionLengthThatKeepsDescriptionOfferedSharesTheCallWithAListNamingIt() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(goods(), changes,
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Description")), //$NON-NLS-1$ //$NON-NLS-2$
            property("descriptionLength", "50"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());
    }

    /** RU: numberLength 9 -> 11 keeps a document's Number offered. */
    @Test
    public void testANumberLengthThatKeepsNumberOfferedSharesTheCallWithAListNamingIt() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        // Документ.Order.СтандартныйРеквизит.Номер
        assertNull(validateAndPrepare(target("Document"), changes, integer("numberLength", 11), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.Order.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041D\u043E\u043C\u0435\u0440")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());
    }

    /** One owner replaced by another: the catalog still has an owner, so its Owner stays lockable. */
    @Test
    public void testAnotherNonEmptyOwnersListKeepsOwnerLockableInTheSameCall()
    {
        assertNull(batchRefusal(target("Catalog", "owners", true), property("owners", array("Catalog.Others")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Owner")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * EDT offers an exchange plan's Code and Description for input by string whatever their lengths, so a
     * length that drops to 0 leaves a list naming them exactly as EDT judges it now.
     */
    @Test
    public void testAnExchangePlansInputByStringIsNotDecidedByItsLengths() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(target("ExchangePlan"), changes, integer("codeLength", 0), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("ExchangePlan.Branches.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());

        changes.clear();
        assertNull(validateAndPrepare(target("ExchangePlan"), changes, //$NON-NLS-1$
            property("inputByString", array("ExchangePlan.Branches.StandardAttribute.Description")), //$NON-NLS-1$ //$NON-NLS-2$
            integer("descriptionLength", 0))); //$NON-NLS-1$
        assertEquals(2, changes.size());
    }

    // ---- a property change that ends the offer, or starts it, is refused ----

    /**
     * The case from PR #744's first review: hierarchical:false would leave a data-lock list naming the
     * Parent of a catalog that is no longer hierarchical, which EDT's validator marks after the call (error
     * 81). Nothing is prepared, and the way out is to leave Parent out or keep the hierarchy - a second
     * call would not bring Parent back.
     */
    @Test
    public void testTakingTheHierarchyAwayIsRefusedWhileTheListNamesParent() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        String json = validateAndPrepare(target("Catalog", "hierarchical", true), changes, //$NON-NLS-1$ //$NON-NLS-2$
            flag("hierarchical", false), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Parent"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the batch must be refused, not prepared as " + changes, json); //$NON-NLS-1$
        assertTakesAway(errorText(json), "dataLockFields", "Catalog.Goods.StandardAttribute.Parent", //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchical"); //$NON-NLS-1$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The other direction. A flat catalog still HAS its Parent field - EDT creates it whatever hierarchical
     * says - but does not offer it for data locking, so hierarchical:true in the same call would have the
     * list judged against the flat object. The answer is the separate-call recipe, not "EDT does not offer
     * it".
     */
    @Test
    public void testMakingACatalogHierarchicalInTheSameCallGetsTheSeparateCallRecipe() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        String error = errorText(validateAndPrepare(target("Catalog"), changes, flag("hierarchical", true), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertBringsBack(error, "dataLockFields", "Catalog.Goods.StandardAttribute.Parent", "hierarchical"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(error, error.contains("does not offer it")); //$NON-NLS-1$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$
    }

    /**
     * C1: codeLength 9 -> 0 takes Code out of what EDT offers, and EDT's model validator never re-checks a
     * standard field stored in input by string - nothing would mark the stale entry. Refused in either
     * order, and nothing is prepared.
     */
    @Test
    public void testALengthThatTakesAwayTheFieldTheListNamesIsRefused() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertTakesAway(errorText(validateAndPrepare(goods(), changes, integer("codeLength", 0), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Code")))), "inputByString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.Code", "codeLength"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$

        assertTakesAway(errorText(validateAndPrepare(goods(), changes,
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Description")), //$NON-NLS-1$ //$NON-NLS-2$
            property("descriptionLength", "0"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.Description", "descriptionLength"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(changes.isEmpty());
    }

    /** C1, the other direction: a code-less catalog's Code is offered only once codeLength is set. */
    @Test
    public void testALengthThatBringsTheFieldBackGetsTheSeparateCallRecipe()
    {
        assertBringsBack(batchRefusal(target("Catalog", "codeLength", 0), integer("codeLength", 9), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Code"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.Code", "codeLength"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * For data locking an exchange plan's lengths do decide its Code and Description - through EDT's
     * validator alone: EDT's editor offers both whatever the length (PR #744, third gate). So both refusals
     * speak of what the validator accepts, and neither says EDT would stop offering the field.
     */
    @Test
    public void testAnExchangePlansLengthsStillDecideItsDataLockFields()
    {
        assertValidatorTakesAway(batchRefusal(target("ExchangePlan"), integer("codeLength", 0), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("ExchangePlan.Branches.StandardAttribute.Code"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ExchangePlan.Branches.StandardAttribute.Code", "codeLength"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValidatorBringsBack(batchRefusal(target("ExchangePlan", "descriptionLength", 0), integer("descriptionLength", 25), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("dataLockFields", array("ExchangePlan.Branches.StandardAttribute.Description"))), //$NON-NLS-1$ //$NON-NLS-2$
            "dataLockFields", "ExchangePlan.Branches.StandardAttribute.Description", "descriptionLength"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ---- IsFolder: hierarchical and hierarchyType decide it together ----

    /**
     * On a flat catalog hierarchyType alone changes nothing about IsFolder - EDT offers it neither before
     * nor after the call - so the guard lets the list through and resolve's own refusal answers, truthfully:
     * IsFolder needs a hierarchical catalog of HierarchyFoldersAndItems.
     */
    @Test
    public void testAHierarchyTypeChangeOnAFlatCatalogLeavesIsFolderToTheListsOwnRefusal() throws Exception
    {
        assertNull(batchRefusal(target("Catalog"), property("hierarchyType", ITEMS), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder")))); //$NON-NLS-1$ //$NON-NLS-2$

        String error = errorText(validateAndPrepare(target("Catalog", "hierarchyType", ITEMS), new ArrayList<>(), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder")), //$NON-NLS-1$ //$NON-NLS-2$
            property("hierarchyType", FOLDERS_AND_ITEMS))); //$NON-NLS-1$
        assertContains(error, "but EDT does not offer it for 'dataLockFields'"); //$NON-NLS-1$
        assertContains(error, "an IsFolder on a hierarchical catalog of HierarchyFoldersAndItems"); //$NON-NLS-1$
        assertFalse(error, error.contains("cannot be set in the same call as")); //$NON-NLS-1$
    }

    /**
     * A flat catalog of HierarchyOfItems offers IsFolder for data locking only once BOTH change - neither
     * alone would bring it back, so the recipe names both. The other way, a hierarchical catalog of folders
     * and items losing both: the take-away names both, in the order of EDT's rule.
     */
    @Test
    public void testHierarchicalAndHierarchyTypeDecideIsFolderTogether()
    {
        assertBringsBack(batchRefusal(target("Catalog", "hierarchyType", ITEMS), flag("hierarchical", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("hierarchyType", FOLDERS_AND_ITEMS), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.IsFolder", "hierarchical", "hierarchyType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTakesAway(batchRefusal(target("Catalog", "hierarchical", true), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder")), //$NON-NLS-1$ //$NON-NLS-2$
            property("hierarchyType", ITEMS), flag("hierarchical", false)), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.IsFolder", "hierarchical", "hierarchyType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A refusal names the deciding properties the call CHANGES: hierarchical:true on a hierarchical catalog
     * is no change. And a catalog made hierarchical but of items only still offers no IsFolder - nothing
     * to refuse.
     */
    @Test
    public void testARefusalNamesOnlyThePropertiesTheCallChanges()
    {
        assertTakesAway(batchRefusal(target("Catalog", "hierarchical", true), flag("hierarchical", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("hierarchyType", ITEMS), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.IsFolder", "hierarchyType"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(batchRefusal(target("Catalog", "hierarchyType", ITEMS), flag("hierarchical", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("hierarchyType", ITEMS), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- how the batch's values are read ----

    /** Applied in order, a repeated property is decided by its LAST write - the state the guard judges. */
    @Test
    public void testTheLastWriteOfAPropertyDecides()
    {
        JsonArray code = array("Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        assertNull(batchRefusal(goods(), integer("codeLength", 0), integer("codeLength", 10), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", code))); //$NON-NLS-1$
        assertTakesAway(batchRefusal(goods(), integer("codeLength", 10), property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("inputByString", code)), "inputByString", "Catalog.Goods.StandardAttribute.Code", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "codeLength"); //$NON-NLS-1$
    }

    /**
     * A repeated LIST is decided by its last write too (#744 review): applied in order, an earlier value of
     * the same list is overwritten, so only the list the call leaves is judged. [Code], then [Description],
     * then codeLength 0 leaves [Description] - one call; the other way round leaves [Code] - refused.
     */
    @Test
    public void testTheLastWriteOfAListDecides()
    {
        JsonArray code = array("Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        JsonArray description = array("Catalog.Goods.StandardAttribute.Description"); //$NON-NLS-1$
        assertNull(batchRefusal(goods(), property("inputByString", code), property("inputByString", description), //$NON-NLS-1$ //$NON-NLS-2$
            integer("codeLength", 0))); //$NON-NLS-1$
        assertTakesAway(batchRefusal(goods(), property("inputByString", description), property("inputByString", code), //$NON-NLS-1$ //$NON-NLS-2$
            integer("codeLength", 0)), "inputByString", "Catalog.Goods.StandardAttribute.Code", "codeLength"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * A value the property's own preparation cannot parse - a length that is no integer, a flag that is no
     * boolean, an unknown hierarchy type, owners that are no array of FQN strings - is no end state: the
     * guard reads that property as unchanged - nothing is written either way, and the property's own refusal
     * answers the call. Each value below would end (or start) the field's offer if it were read loosely, and
     * none may make the guard throw. A refused LAST write also cancels an earlier valid one, as the main
     * flag's gate reads it. Whether owner FQNs resolve is another matter
     * ({@link #testOwnersAreReadByTheirShapeNotByWhetherTheyResolve}).
     */
    @Test
    public void testAValueThePropertysOwnParserRefusesIsNotGuardedOn() throws Exception
    {
        JsonArray code = array("Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        for (JsonElement bad : new JsonElement[] {new JsonPrimitive("nine"), new JsonPrimitive(0.5), //$NON-NLS-1$
            JsonNull.INSTANCE, new JsonObject(), array("0")}) //$NON-NLS-1$
        {
            assertNull("codeLength " + bad, batchRefusal(goods(), property("codeLength", bad), //$NON-NLS-1$ //$NON-NLS-2$
                property("inputByString", code))); //$NON-NLS-1$
        }
        assertNull(batchRefusal(goods(), integer("codeLength", 0), property("codeLength", "nine"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("inputByString", code))); //$NON-NLS-1$
        assertContains(errorText(validateAndPrepare(goods(), new ArrayList<>(), property("codeLength", "nine"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", code))), "'nine' is not a valid integer for 'codeLength'"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(batchRefusal(target("Catalog", "hierarchical", true), property("hierarchical", "maybe"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(batchRefusal(target("Catalog", "hierarchical", true), property("hierarchyType", "Bogus"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder")))); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray owner = array("Catalog.Goods.StandardAttribute.Owner"); //$NON-NLS-1$
        for (JsonElement bad : new JsonElement[] {new JsonPrimitive("Catalog.Others"), array(""), //$NON-NLS-1$ //$NON-NLS-2$
            JsonNull.INSTANCE, new JsonObject()})
        {
            assertNull("owners " + bad, batchRefusal(target("Catalog"), property("owners", bad), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                property("dataLockFields", owner))); //$NON-NLS-1$
        }
    }

    /**
     * The guard reads owners by their shape, never by whether they resolve: that is the owners preparation's
     * own check, against the project (PR #744, third gate). So on a catalog without owners, owners that do
     * not resolve still count as an owner - the guard answers first, with the separate-call recipe, and the
     * owners refusal comes on the next call. Nothing is written either way.
     */
    @Test
    public void testOwnersAreReadByTheirShapeNotByWhetherTheyResolve() throws Exception
    {
        JsonObject owners = property("owners", array("Catalog.NoSuchCatalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertBringsBack(batchRefusal(target("Catalog"), owners, //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Owner"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.Owner", "owners"); //$NON-NLS-1$ //$NON-NLS-2$

        List<Object> changes = new ArrayList<>();
        assertContains(errorText(validateAndPrepare(target("Catalog"), changes, owners)), //$NON-NLS-1$
            "Reference target 'Catalog.NoSuchCatalog' for 'owners' was not found"); //$NON-NLS-1$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Before EDT has computed the fields, a standard field named in English - in any case - is still
     * recognized, and the refusal names it canonically; a Russian name needs the computed fields' nameRu,
     * so it is left to resolve, which answers "not computed yet".
     */
    @Test
    public void testAFieldNamedInEnglishIsGuardedBeforeEdtHasComputedTheFields()
    {
        Catalog bare = MdClassFactory.eINSTANCE.createCatalog();
        bare.setName("Goods"); //$NON-NLS-1$
        bare.setCodeLength(9);
        assertTakesAway(batchRefusal(bare, integer("codeLength", 0), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.code"))), "inputByString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.Code", "codeLength"); //$NON-NLS-1$ //$NON-NLS-2$
        // Справочник.Goods.СтандартныйРеквизит.Код
        assertNull(batchRefusal(bare, integer("codeLength", 0), //$NON-NLS-1$
            property("inputByString", array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- the rest of the guard's contract ----

    /**
     * The guard asks which field the list NAMES: a list that leaves the decided field out is judged the
     * same whatever that length becomes, so it shares the call - codeLength 0 with [Description] is the
     * one-call way to drop the code and its input.
     */
    @Test
    public void testALengthAndAListThatLeavesItsFieldOutShareOneCall() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(goods(), changes, property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Description")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());

        changes.clear();
        assertNull(validateAndPrepare(goods(), changes, property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", new JsonArray()))); //$NON-NLS-1$
        assertEquals(2, changes.size());

        changes.clear();
        assertNull(validateAndPrepare(goods(), changes,
            property("dataLockFields", array("Catalog.Goods.Attribute.Weight")), //$NON-NLS-1$ //$NON-NLS-2$
            property("descriptionLength", "0"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());
    }

    /** RU: the decided field is recognized by its Russian name too, through the model's own nameRu. */
    @Test
    public void testTheGuardRecognizesTheDecidedFieldByItsRussianName() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        // Справочник.Goods.СтандартныйРеквизит.Код
        String error = errorText(validateAndPrepare(goods(), changes, property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTakesAway(error, "inputByString", "Catalog.Goods.StandardAttribute.Code", "codeLength"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The guide quotes the batch refusals by fragments of their real text, so a reader can match a refusal
     * to the paragraph that explains it: the fragment both directions share, and the one only the
     * take-away says - in EDT's words, and in its validator's for an exchange plan's data-lock Code - must
     * each be in the refusal AND, quoted, in the guide.
     */
    @Test
    public void testTheGuideQuotesTheBatchRefusalsByExactFragments()
    {
        String guide = new ModifyMetadataTool().getGuide();
        JsonObject code = property("inputByString", array("Catalog.Goods.StandardAttribute.Code")); //$NON-NLS-1$ //$NON-NLS-2$
        String takesAway = batchRefusal(goods(), integer("codeLength", 0), code); //$NON-NLS-1$
        String bringsBack = batchRefusal(target("Catalog", "codeLength", 0), integer("codeLength", 9), code); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String validatorTakesAway = batchRefusal(target("ExchangePlan"), integer("codeLength", 0), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("ExchangePlan.Branches.StandardAttribute.Code"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("codeLength 9 -> 0 must be refused", takesAway); //$NON-NLS-1$
        assertNotNull("codeLength 0 -> 9 must be refused", bringsBack); //$NON-NLS-1$
        assertNotNull("an exchange plan's codeLength 9 -> 0 must be refused", validatorTakesAway); //$NON-NLS-1$
        String shared = "cannot be set in the same call as"; //$NON-NLS-1$
        assertContains(takesAway, shared);
        assertContains(bringsBack, shared);
        assertContains(validatorTakesAway, shared);
        assertContains(guide, "(\"" + shared + "\")"); //$NON-NLS-1$ //$NON-NLS-2$
        String ending = "would no longer offer it once this call has changed"; //$NON-NLS-1$
        assertContains(takesAway, ending);
        assertContains(guide, "(\"" + ending + "\")"); //$NON-NLS-1$ //$NON-NLS-2$
        String validatorEnding = "would reject it once this call has changed"; //$NON-NLS-1$
        assertContains(validatorTakesAway, validatorEnding);
        assertContains(guide, "(\"" + validatorEnding + "\")"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An adopted target cannot hold a field list at all: it gets that refusal, not the batch one. */
    @Test
    public void testAnAdoptedTargetGetsTheFieldListsOwnRefusalNotTheBatchOne() throws Exception
    {
        Catalog adopted = goods();
        adopted.setObjectBelonging(ObjectBelonging.ADOPTED);

        String error = errorText(validateAndPrepare(adopted, new ArrayList<>(), property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(error, "is an adopted object"); //$NON-NLS-1$
        assertFalse(error, error.contains("cannot be set in the same call as")); //$NON-NLS-1$
    }

    /**
     * The guard is about the properties that decide a named standard field only: a field list next to any
     * other property is prepared.
     */
    @Test
    public void testAFieldListWithAnUnrelatedPropertyIsPrepared() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(goods(), changes,
            property("inputByString", array("Catalog.Goods.StandardAttribute.Description")), //$NON-NLS-1$ //$NON-NLS-2$
            property("searchStringModeOnInputByString", "AnyPart"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());
    }

    /** A length the TARGET does not have is not the guard's business (it is refused on its own). */
    @Test
    public void testTheGuardIgnoresALengthTheTargetDoesNotHave()
    {
        BasicDbObject order = target("Document"); //$NON-NLS-1$
        JsonArray numberList = array("Document.Order.StandardAttribute.Number"); //$NON-NLS-1$
        // A document has no descriptionLength...
        assertNull(batchRefusal(order, integer("descriptionLength", 0), property("inputByString", numberList))); //$NON-NLS-1$ //$NON-NLS-2$
        // ...but its numberLength decides the Number the list names.
        assertTakesAway(batchRefusal(order, integer("numberLength", 0), property("inputByString", numberList)), //$NON-NLS-1$ //$NON-NLS-2$
            "inputByString", "Document.Order.StandardAttribute.Number", "numberLength"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** RU: Parent named in Russian - the type and kind tokens and the model's own nameRu - in either order. */
    @Test
    public void testTheGuardRecognizesParentByItsRussianName() throws Exception
    {
        // Справочник.Goods.СтандартныйРеквизит.Родитель
        String json = validateAndPrepare(target("Catalog", "hierarchical", true), new ArrayList<>(), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u0420\u043E\u0434\u0438\u0442\u0435\u043B\u044C")), //$NON-NLS-1$ //$NON-NLS-2$
            flag("hierarchical", false)); //$NON-NLS-1$
        assertNotNull("the batch must be refused", json); //$NON-NLS-1$
        assertTakesAway(errorText(json), "dataLockFields", "Catalog.Goods.StandardAttribute.Parent", //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchical"); //$NON-NLS-1$
    }

    /** A catalog's IsFolder is lockable only while the catalog is hierarchical. */
    @Test
    public void testHierarchicalDecidesACatalogsIsFolder()
    {
        assertTakesAway(batchRefusal(target("Catalog", "hierarchical", true), flag("hierarchical", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.IsFolder", "hierarchical"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** RU: ...and only while its hierarchy has folders, so hierarchyType decides it as well. */
    @Test
    public void testHierarchyTypeDecidesACatalogsIsFolder()
    {
        // Catalog.Goods.StandardAttribute.ЭтоГруппа
        assertTakesAway(batchRefusal(target("Catalog", "hierarchical", true), property("hierarchyType", ITEMS), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.\u042D\u0442\u043E\u0413\u0440\u0443\u043F\u043F\u0430"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.IsFolder", "hierarchyType"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** RU: a catalog's Owner is lockable only while the catalog has an owner. */
    @Test
    public void testOwnersDecidesACatalogsOwner()
    {
        // Catalog.Goods.StandardAttribute.Владелец
        assertTakesAway(batchRefusal(target("Catalog", "owners", true), property("owners", new JsonArray()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.\u0412\u043B\u0430\u0434\u0435\u043B\u0435\u0446"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.Owner", "owners"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A chart of characteristic types declares its own hierarchical, with the same rule for Parent. */
    @Test
    public void testHierarchicalDecidesAChartOfCharacteristicTypesParent()
    {
        assertTakesAway(batchRefusal(target("ChartOfCharacteristicTypes", "hierarchical", true), //$NON-NLS-1$ //$NON-NLS-2$
            flag("hierarchical", false), //$NON-NLS-1$
            property("dataLockFields", array("ChartOfCharacteristicTypes.Kinds.StandardAttribute.Parent"))), //$NON-NLS-1$ //$NON-NLS-2$
            "dataLockFields", "ChartOfCharacteristicTypes.Kinds.StandardAttribute.Parent", "hierarchical"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** RU: ...and for IsFolder, here making a flat chart hierarchical. */
    @Test
    public void testHierarchicalDecidesAChartOfCharacteristicTypesIsFolder()
    {
        // ChartOfCharacteristicTypes.Kinds.StandardAttribute.ЭтоГруппа
        assertBringsBack(batchRefusal(target("ChartOfCharacteristicTypes"), flag("hierarchical", true), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("ChartOfCharacteristicTypes.Kinds.StandardAttribute.\u042D\u0442\u043E\u0413\u0440\u0443\u043F\u043F\u0430"))), //$NON-NLS-1$ //$NON-NLS-2$
            "dataLockFields", "ChartOfCharacteristicTypes.Kinds.StandardAttribute.IsFolder", "hierarchical"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * RU: a chart of calculation types' ActionPeriodIsBasic is lockable only while actionPeriodUse - and
     * EDT's validator accepts a stale one with no marker, so nothing would catch it afterwards.
     */
    @Test
    public void testActionPeriodUseDecidesActionPeriodIsBasic()
    {
        // ChartOfCalculationTypes.Accruals.StandardAttribute.ПериодДействияБазовый
        assertTakesAway(batchRefusal(target("ChartOfCalculationTypes", "actionPeriodUse", true), //$NON-NLS-1$ //$NON-NLS-2$
            flag("actionPeriodUse", false), //$NON-NLS-1$
            property("dataLockFields", //$NON-NLS-1$
                array("ChartOfCalculationTypes.Accruals.StandardAttribute.\u041F\u0435\u0440\u0438\u043E\u0434\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u044F\u0411\u0430\u0437\u043E\u0432\u044B\u0439"))), //$NON-NLS-1$
            "dataLockFields", "ChartOfCalculationTypes.Accruals.StandardAttribute.ActionPeriodIsBasic", //$NON-NLS-1$ //$NON-NLS-2$
            "actionPeriodUse"); //$NON-NLS-1$
    }

    /** A deciding property and a list that leaves its field out share one call. */
    @Test
    public void testADecidingPropertyAndAListWithoutItsFieldShareOneCall() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(target("Catalog", "hierarchical", true), changes, flag("hierarchical", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Description", //$NON-NLS-1$ //$NON-NLS-2$
                "Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$
        assertEquals(2, changes.size());

        assertNull(batchRefusal(target("Catalog", "owners", true), property("owners", new JsonArray()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A property decides a field only for the lists EDT lets it decide it for: hierarchical never makes
     * Parent an input-by-string field, so naming Parent there gets EDT's own "does not offer it" - a
     * separate call would not help.
     */
    @Test
    public void testAPropertyDecidesItsFieldOnlyForTheListItDecidesItFor() throws Exception
    {
        assertNull(batchRefusal(target("Catalog", "hierarchical", true), flag("hierarchical", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$

        String error = errorText(validateAndPrepare(target("Catalog", "hierarchical", true), new ArrayList<>(), //$NON-NLS-1$ //$NON-NLS-2$
            flag("hierarchical", false), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(error, "does not offer it for 'inputByString'"); //$NON-NLS-1$
        assertFalse(error, error.contains("cannot be set in the same call as")); //$NON-NLS-1$
    }

    /** A deciding property the target does not have keeps its own refusal: a document has no hierarchy. */
    @Test
    public void testTheGuardIgnoresADecidingPropertyTheTargetDoesNotHave()
    {
        assertNull(batchRefusal(target("Document"), flag("hierarchical", false), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Document.Order.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Every list and every entry of a batch is asked: the entry whose field the call takes away comes
     * second in the second list, after a list and an entry that are left as they were, and its deciding
     * property comes after one (codeLength 9 -> 11) that changes no offer.
     */
    @Test
    public void testTheGuardAsksEveryListAndEveryEntryOfTheBatch() throws Exception
    {
        assertTakesAway(batchRefusal(target("Catalog", "hierarchical", true), integer("codeLength", 11), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            flag("hierarchical", false), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Code")), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Code", //$NON-NLS-1$ //$NON-NLS-2$
                "Catalog.Goods.StandardAttribute.Parent"))), //$NON-NLS-1$
            "dataLockFields", "Catalog.Goods.StandardAttribute.Parent", "hierarchical"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Both lists leave the decided fields out: one call, every property prepared.
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(target("Catalog", "hierarchical", true), changes, flag("hierarchical", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Description")), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, changes.size());
    }

    /**
     * A property is named case-insensitively, as everywhere in modify_metadata: 'Hierarchical' is
     * hierarchical and 'DataLockFields' is dataLockFields, and the refusal names both as the model does.
     */
    @Test
    public void testTheGuardRecognizesAPropertyWhateverItsCase()
    {
        assertTakesAway(batchRefusal(target("Catalog", "hierarchical", true), flag("Hierarchical", false), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            property("DataLockFields", array("Catalog.Goods.StandardAttribute.Parent"))), "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods.StandardAttribute.Parent", "hierarchical"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the batch guard: EDT's availability, cell by cell ====================

    /** Every state the kind's deciding properties take in the cell test: the product of their {@link #domain}. */
    private static List<Map<String, Object>> states(String kind)
    {
        List<Map<String, Object>> states = new ArrayList<>();
        states.add(new LinkedHashMap<>());
        for (String property : DECIDING.get(kind))
        {
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> state : states)
            {
                for (Object value : domain(property))
                {
                    Map<String, Object> grown = new LinkedHashMap<>(state);
                    grown.put(property, value);
                    next.add(grown);
                }
            }
            states = next;
        }
        return states;
    }

    /** The deciding properties whose value differs between the two states, in the kind's order. */
    private static List<String> changed(String kind, Map<String, Object> before, Map<String, Object> after)
    {
        List<String> changed = new ArrayList<>();
        for (String property : DECIDING.get(kind))
        {
            if (!before.get(property).equals(after.get(property)))
            {
                changed.add(property);
            }
        }
        return changed;
    }

    /**
     * The call taking {@code before} to {@code after} with a list naming {@code address}: only the changed
     * properties, the list last - or every deciding property of the kind (the unchanged ones at their
     * current value), the list first.
     */
    private static JsonObject[] batch(String kind, Map<String, Object> before, Map<String, Object> after,
        boolean everyProperty, String list, String address)
    {
        List<JsonObject> batch = new ArrayList<>();
        if (everyProperty)
        {
            batch.add(property(list, array(address)));
        }
        for (String property : everyProperty ? DECIDING.get(kind) : changed(kind, before, after))
        {
            batch.add(property(property, wire(property, after.get(property))));
        }
        if (!everyProperty)
        {
            batch.add(property(list, array(address)));
        }
        return batch.toArray(new JsonObject[0]);
    }

    /**
     * The guard's whole rule, cell by cell, against EDT's availability as this test spells it out (never
     * read from FieldListSupport): on all eight kinds, from every state of the kind's deciding properties
     * to every state that differs in at most two of them, for both lists and every asked field, the guard
     * passes a call that leaves the field's offer as it is, gives the take-away refusal to one that ends
     * it and the separate-call recipe to one that starts it - naming exactly the deciding properties the
     * call changes, and EDT's validator exactly where only the validator decides the field. Each call is
     * sent twice: with only the changed properties and the list last, and with every deciding property of
     * the kind and the list first. Every decided cell must see both refusals.
     */
    @Test
    public void testEveryCellFollowsEdtsAvailabilityBeforeAndAfterTheCall()
    {
        List<String> wrong = new ArrayList<>();
        List<String> refusedBothWays = new ArrayList<>();
        List<String> decided = new ArrayList<>();
        for (String kind : DECIDING.keySet())
        {
            List<Map<String, Object>> states = states(kind);
            for (Map<String, Object> before : states)
            {
                BasicDbObject target = fixture(kind, before);
                for (Map<String, Object> after : states)
                {
                    List<String> changed = changed(kind, before, after);
                    if (changed.size() > 2)
                    {
                        continue;
                    }
                    for (boolean dataLock : new boolean[] {false, true})
                    {
                        String list = dataLock ? "dataLockFields" : "inputByString"; //$NON-NLS-1$ //$NON-NLS-2$
                        for (String field : ASKED_FIELDS)
                        {
                            String cell = kind + " / " + list + " / " + field; //$NON-NLS-1$ //$NON-NLS-2$
                            String address = kind + '.' + NAMES.get(kind) + ".StandardAttribute." + field; //$NON-NLS-1$
                            boolean now = offered(kind, dataLock, field, before);
                            boolean then = offered(kind, dataLock, field, after);
                            List<String> named = new ArrayList<>(deciders(kind, dataLock, field));
                            named.retainAll(changed);
                            if (!deciders(kind, dataLock, field).isEmpty() && !decided.contains(cell))
                            {
                                decided.add(cell);
                            }
                            if (now != then && !refusedBothWays.contains(cell + now))
                            {
                                refusedBothWays.add(cell + now);
                            }
                            for (boolean everyProperty : new boolean[] {false, true})
                            {
                                String refusal = batchRefusal(target,
                                    batch(kind, before, after, everyProperty, list, address));
                                String mismatch = now == then
                                    ? refusal == null ? null : "expected the call to pass, got: " + refusal //$NON-NLS-1$
                                    : mismatch(refusal, named, list, address, now, byValidator(kind, dataLock, field));
                                if (mismatch != null)
                                {
                                    wrong.add(cell + ", " + before + " -> " + after //$NON-NLS-1$ //$NON-NLS-2$
                                        + (everyProperty ? " (every property)" : "") + ": " + mismatch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(wrong.size() + " cell(s) differ from EDT's availability, e.g.:\n" //$NON-NLS-1$
            + String.join("\n", wrong.subList(0, Math.min(25, wrong.size()))), wrong.isEmpty()); //$NON-NLS-1$
        for (String cell : decided)
        {
            assertTrue(cell + " never takes the field away", refusedBothWays.contains(cell + true)); //$NON-NLS-1$
            assertTrue(cell + " never brings the field back", refusedBothWays.contains(cell + false)); //$NON-NLS-1$
        }
        assertEquals("decided cells (kind / list / field)", 32, decided.size()); //$NON-NLS-1$
    }

    /**
     * The other half of the agreement: resolve - which reads the source types EDT derived, narrowed by
     * EDT's validator - offers, in every state of every kind, exactly what the same spelled-out
     * availability says. With the cell test above, the guard's rules and resolve's live reading are pinned
     * to one table on all eight kinds.
     */
    @Test
    public void testResolveOffersWhatTheSameAvailabilitySaysOnAllEightKinds()
    {
        List<String> wrong = new ArrayList<>();
        for (String kind : DECIDING.keySet())
        {
            for (Map<String, Object> state : states(kind))
            {
                BasicDbObject target = fixture(kind, state);
                for (boolean dataLock : new boolean[] {false, true})
                {
                    EReference list = dataLock ? MdClassPackage.Literals.BASIC_DB_OBJECT__DATA_LOCK_FIELDS
                        : MdClassPackage.Literals.BASIC_DB_OBJECT__INPUT_BY_STRING;
                    List<String> expected = new ArrayList<>();
                    for (String field : ASKED_FIELDS)
                    {
                        if (offered(kind, dataLock, field, state))
                        {
                            expected.add(kind + '.' + NAMES.get(kind) + ".StandardAttribute." + field); //$NON-NLS-1$
                        }
                    }
                    List<String> actual = FieldListSupport.eligibleAddresses(target, list);
                    if (!expected.equals(actual))
                    {
                        wrong.add(kind + ' ' + state + " / " + list.getName() + ": expected " + expected //$NON-NLS-1$ //$NON-NLS-2$
                            + ", resolve offers " + actual); //$NON-NLS-1$
                    }
                }
            }
        }
        assertTrue(wrong.size() + " state(s) differ:\n" + String.join("\n", wrong), wrong.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the write ====================

    /**
     * The write half of the kind: a FIELD_LIST change reaches FieldListSupport.apply, which refuses a
     * detached object by name - never a generic eSet of the field-id strings into the reference list.
     */
    @Test
    public void testAFieldListChangeIsAppliedThroughTheFieldListWriter()
    {
        ModifyMetadataTool.PreparedChange change = ModifyMetadataTool.PreparedChange.fieldList(
            MdClassPackage.Literals.BASIC_DB_OBJECT__INPUT_BY_STRING,
            Collections.singletonList("StandardAttribute:Code")); //$NON-NLS-1$
        try
        {
            change.applyTo(goods(), null);
            fail("a detached catalog cannot hold a BM proxy of its own fields"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertContains(e.getMessage(),
                "'inputByString' can only be written to a top object attached to a BM engine"); //$NON-NLS-1$
        }
    }
}
