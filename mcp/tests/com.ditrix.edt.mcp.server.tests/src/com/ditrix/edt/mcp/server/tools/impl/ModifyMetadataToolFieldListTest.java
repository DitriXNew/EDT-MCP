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
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.DerivedField;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.SourceType;
import com._1c.g5.v8.dt.metadata.mdclass.BasicDbObject;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.HierarchyType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
     * Catalog.Goods as EDT infers it: Code and Description offered for both lists; a non-indexed
     * String(10) attribute Weight lockable but not offered for input by string.
     */
    private static Catalog goods()
    {
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
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

    /**
     * C1: which standard fields a list may hold depends on codeLength / descriptionLength /
     * numberLength, the list is judged on the object as it stands, and EDT's model validator never
     * re-checks a stale standard field of input by string - so the two may not share a call, in either
     * order.
     */
    @Test
    public void testAFieldListCannotShareACallWithALengthThatDecidesIt() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        String error = errorText(validateAndPrepare(goods(), changes, property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(error, "codeLength"); //$NON-NLS-1$
        assertContains(error, "inputByString"); //$NON-NLS-1$
        assertContains(error, "separate call"); //$NON-NLS-1$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$

        String reversed = errorText(validateAndPrepare(goods(), changes,
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Description")), //$NON-NLS-1$ //$NON-NLS-2$
            property("descriptionLength", "50"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(reversed, "descriptionLength"); //$NON-NLS-1$
        assertContains(reversed, "dataLockFields"); //$NON-NLS-1$
        assertTrue(changes.isEmpty());
    }

    /**
     * The guard asks which field the list NAMES: a list that leaves the governed field out is judged
     * the same whatever that length becomes, so it shares the call - codeLength 0 with [Description]
     * is the one-call way to drop the code and its input.
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
            property("descriptionLength", "50"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, changes.size());
    }

    /** RU: the governed field is recognized by its Russian name too, through the model's own nameRu. */
    @Test
    public void testTheGuardRecognizesTheGovernedFieldByItsRussianName() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        // Справочник.Goods.СтандартныйРеквизит.Код
        String error = errorText(validateAndPrepare(goods(), changes, property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(error, "while it names Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        assertContains(error, "or leave Catalog.Goods.StandardAttribute.Code out of the list"); //$NON-NLS-1$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The guide quotes the batch refusal by a fragment of its real text, so a reader can match the
     * refusal to the paragraph that explains it: the fragment must be in the refusal AND, quoted, in
     * the guide.
     */
    @Test
    public void testTheGuideQuotesTheBatchRefusalByAnExactFragment() throws Exception
    {
        String fragment = "cannot be set in the same call as"; //$NON-NLS-1$
        String error = errorText(validateAndPrepare(goods(), new ArrayList<>(), property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(error, fragment);
        assertContains(new ModifyMetadataTool().getGuide(), "(\"" + fragment + "\")"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An adopted target cannot hold a field list at all: it gets that refusal, not "a separate call". */
    @Test
    public void testAnAdoptedTargetGetsTheFieldListsOwnRefusalNotTheBatchOne() throws Exception
    {
        Catalog adopted = goods();
        adopted.setObjectBelonging(ObjectBelonging.ADOPTED);

        String error = errorText(validateAndPrepare(adopted, new ArrayList<>(), property("codeLength", "0"), //$NON-NLS-1$ //$NON-NLS-2$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(error, "is an adopted object"); //$NON-NLS-1$
        assertFalse(error, error.contains("separate call")); //$NON-NLS-1$
    }

    /**
     * The guard is about the properties that decide a named standard field only: a field list next to
     * any other property is prepared.
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
        Document order = MdClassFactory.eINSTANCE.createDocument();
        order.setName("Order"); //$NON-NLS-1$
        standard(order, "Number", "\u041D\u043E\u043C\u0435\u0440", SourceType.STANDARD_FIELDS, SourceType.INPUT_BY_STRING); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray number = array("Document.Order.StandardAttribute.Number"); //$NON-NLS-1$
        // A document has no descriptionLength...
        assertNull(ModifyMetadataTool.fieldListBatchError(order, Arrays.asList(
            property("descriptionLength", "50"), property("inputByString", number)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // ...but its numberLength governs the Number the list names.
        assertContains(errorText(ModifyMetadataTool.fieldListBatchError(order, Arrays.asList(
            property("numberLength", "11"), property("inputByString", number)))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "while it names Document.Order.StandardAttribute.Number"); //$NON-NLS-1$
    }

    // ==================== the batch guard: every property that decides a named field ====================

    // The Russian names EDT's MdFieldInferrer writes into nameRu (DbViewTerms, md 27.1 and 28.0).
    private static final String RU_PARENT = "\u0420\u043E\u0434\u0438\u0442\u0435\u043B\u044C"; //$NON-NLS-1$
    private static final String RU_IS_FOLDER = "\u042D\u0442\u043E\u0413\u0440\u0443\u043F\u043F\u0430"; //$NON-NLS-1$
    private static final String RU_OWNER = "\u0412\u043B\u0430\u0434\u0435\u043B\u0435\u0446"; //$NON-NLS-1$
    private static final String RU_ACTION_PERIOD_IS_BASIC = "\u041F\u0435\u0440\u0438\u043E\u0434\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u044F\u0411\u0430\u0437\u043E\u0432\u044B\u0439"; //$NON-NLS-1$

    /**
     * A standard field EDT gives FIELDS_FOR_LOCKING only while {@code lockable}. The field is there
     * either way: EDT's MdFieldInferrer creates every standard field whatever the deciding property
     * says, and the property only adds the field's data-lock source type (md 27.1 MdFieldInferrer.xtend,
     * the same rule in md 28.0).
     */
    private static Field decided(BasicDbObject owner, String name, String nameRu, boolean lockable)
    {
        return lockable
            ? standard(owner, name, nameRu, SourceType.STANDARD_FIELDS, SourceType.FIELDS_FOR_LOCKING)
            : standard(owner, name, nameRu, SourceType.STANDARD_FIELDS);
    }

    /** Code and Description offered for both lists, as with both lengths above 0. */
    private static void codeAndDescription(BasicDbObject owner)
    {
        standard(owner, "Description", "\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.INPUT_BY_STRING, SourceType.FIELDS_FOR_LOCKING);
        standard(owner, "Code", "\u041A\u043E\u0434", SourceType.STANDARD_FIELDS, SourceType.INPUT_BY_STRING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);
    }

    /**
     * Catalog.Goods as EDT infers it: IsFolder lockable while the catalog is hierarchical with folders,
     * Owner while it has an owner, Parent while it is hierarchical.
     */
    private static Catalog catalog(boolean hierarchical, HierarchyType hierarchyType, boolean owned)
    {
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        goods.setHierarchical(hierarchical);
        goods.setHierarchyType(hierarchyType);
        if (owned)
        {
            Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
            owner.setName("Owners"); //$NON-NLS-1$
            goods.getOwners().add(owner);
        }
        decided(goods, "IsFolder", RU_IS_FOLDER, //$NON-NLS-1$
            hierarchical && hierarchyType == HierarchyType.HIERARCHY_FOLDERS_AND_ITEMS);
        decided(goods, "Owner", RU_OWNER, owned); //$NON-NLS-1$
        decided(goods, "Parent", RU_PARENT, hierarchical); //$NON-NLS-1$
        codeAndDescription(goods);
        return goods;
    }

    private static Catalog hierarchicalCatalog()
    {
        return catalog(true, HierarchyType.HIERARCHY_FOLDERS_AND_ITEMS, false);
    }

    private static Catalog flatCatalog()
    {
        return catalog(false, HierarchyType.HIERARCHY_FOLDERS_AND_ITEMS, false);
    }

    /**
     * ChartOfCharacteristicTypes.Kinds: IsFolder and Parent lockable while it is hierarchical (a chart
     * of characteristic types has no hierarchyType).
     */
    private static ChartOfCharacteristicTypes kinds(boolean hierarchical)
    {
        ChartOfCharacteristicTypes kinds = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        kinds.setName("Kinds"); //$NON-NLS-1$
        kinds.setHierarchical(hierarchical);
        codeAndDescription(kinds);
        decided(kinds, "IsFolder", RU_IS_FOLDER, hierarchical); //$NON-NLS-1$
        decided(kinds, "Parent", RU_PARENT, hierarchical); //$NON-NLS-1$
        return kinds;
    }

    /** ChartOfCalculationTypes.Accruals: ActionPeriodIsBasic lockable while actionPeriodUse. */
    private static ChartOfCalculationTypes accruals(boolean actionPeriodUse)
    {
        ChartOfCalculationTypes accruals = MdClassFactory.eINSTANCE.createChartOfCalculationTypes();
        accruals.setName("Accruals"); //$NON-NLS-1$
        accruals.setActionPeriodUse(actionPeriodUse);
        decided(accruals, "ActionPeriodIsBasic", RU_ACTION_PERIOD_IS_BASIC, actionPeriodUse); //$NON-NLS-1$
        codeAndDescription(accruals);
        return accruals;
    }

    private static JsonObject flag(String name, boolean value)
    {
        return property(name, new JsonPrimitive(value));
    }

    /** The batch guard's own verdict: its refusal as plain text, or {@code null} when it lets the batch pass. */
    private static String batchRefusal(EObject target, JsonObject... properties)
    {
        String json = ModifyMetadataTool.fieldListBatchError(target, Arrays.asList(properties));
        return json == null ? null : errorText(json);
    }

    /**
     * What the separate-call refusal fails to say, or {@code null} when it says all of it: the batched
     * property, the list and the field the list names, why the two cannot share a call, and the recipe.
     */
    private static String separateCallMismatch(String refusal, String property, String list, String address)
    {
        if (refusal == null)
        {
            return "expected the batch guard to refuse '" + list + "' batched with '" + property //$NON-NLS-1$ //$NON-NLS-2$
                + "' naming " + address; //$NON-NLS-1$
        }
        for (String fragment : new String[] {
            "'" + list + "' cannot be set in the same call as '" + property + "' while it names " + address, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "whether EDT offers that field depends on '" + property + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "Set '" + property + "' first, then '" + list + "' in a separate call", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "or leave " + address + " out of the list"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (!refusal.contains(fragment))
            {
                return "expected <" + fragment + "> in: " + refusal; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /** The separate-call refusal, naming the batched property, the list and the field the list names. */
    private static void assertSeparateCall(String refusal, String property, String list, String address)
    {
        String mismatch = separateCallMismatch(refusal, property, list, address);
        assertNull(mismatch, mismatch);
    }

    /**
     * The case from PR #744's review: hierarchical:false batched with a data-lock list naming Parent on
     * a hierarchical catalog. Judged on the object before the call, Parent is lockable; after the call
     * the catalog has no hierarchy, and EDT's validator marks the stored entry (error 81).
     */
    @Test
    public void testHierarchicalCannotShareACallWithADataLockListNamingParent() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        String json = validateAndPrepare(hierarchicalCatalog(), changes, flag("hierarchical", false), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Parent"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the batch must be refused, not prepared as " + changes, json); //$NON-NLS-1$
        assertSeparateCall(errorText(json), "hierarchical", "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog.Goods.StandardAttribute.Parent"); //$NON-NLS-1$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The other direction. A flat catalog still HAS its Parent field - EDT creates it whatever
     * hierarchical says - but does not offer it for data locking, so hierarchical:true in the same call
     * would have the list judged against the flat object. The answer is the separate-call recipe, not
     * "EDT does not offer it".
     */
    @Test
    public void testMakingACatalogHierarchicalInTheSameCallGetsTheSeparateCallRecipe() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        String error = errorText(validateAndPrepare(flatCatalog(), changes, flag("hierarchical", true), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertSeparateCall(error, "hierarchical", "dataLockFields", "Catalog.Goods.StandardAttribute.Parent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(error, error.contains("does not offer it")); //$NON-NLS-1$
        assertTrue("nothing may be prepared: " + changes, changes.isEmpty()); //$NON-NLS-1$
    }

    /** RU: Parent named in Russian - the type and kind tokens and the model's own nameRu - in either order. */
    @Test
    public void testTheGuardRecognizesParentByItsRussianName() throws Exception
    {
        // Справочник.Goods.СтандартныйРеквизит.Родитель
        String json = validateAndPrepare(hierarchicalCatalog(), new ArrayList<>(),
            property("dataLockFields", array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u0420\u043E\u0434\u0438\u0442\u0435\u043B\u044C")), //$NON-NLS-1$ //$NON-NLS-2$
            flag("hierarchical", false)); //$NON-NLS-1$
        assertNotNull("the batch must be refused", json); //$NON-NLS-1$
        assertSeparateCall(errorText(json), "hierarchical", "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog.Goods.StandardAttribute.Parent"); //$NON-NLS-1$
    }

    /** A catalog's IsFolder is lockable only while the catalog is hierarchical. */
    @Test
    public void testHierarchicalDecidesACatalogsIsFolder()
    {
        assertSeparateCall(batchRefusal(hierarchicalCatalog(), flag("hierarchical", false), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.IsFolder"))), //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchical", "dataLockFields", "Catalog.Goods.StandardAttribute.IsFolder"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** RU: ...and only while its hierarchy has folders, so hierarchyType decides it as well. */
    @Test
    public void testHierarchyTypeDecidesACatalogsIsFolder()
    {
        // Catalog.Goods.StandardAttribute.ЭтоГруппа
        assertSeparateCall(batchRefusal(hierarchicalCatalog(), property("hierarchyType", "HierarchyOfItems"), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.\u042D\u0442\u043E\u0413\u0440\u0443\u043F\u043F\u0430"))), //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchyType", "dataLockFields", "Catalog.Goods.StandardAttribute.IsFolder"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** RU: a catalog's Owner is lockable only while the catalog has an owner. */
    @Test
    public void testOwnersDecidesACatalogsOwner()
    {
        Catalog owned = catalog(false, HierarchyType.HIERARCHY_FOLDERS_AND_ITEMS, true);
        // Catalog.Goods.StandardAttribute.Владелец
        assertSeparateCall(batchRefusal(owned, property("owners", new JsonArray()), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.\u0412\u043B\u0430\u0434\u0435\u043B\u0435\u0446"))), //$NON-NLS-1$ //$NON-NLS-2$
            "owners", "dataLockFields", "Catalog.Goods.StandardAttribute.Owner"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A chart of characteristic types declares its own hierarchical, with the same rule for Parent. */
    @Test
    public void testHierarchicalDecidesAChartOfCharacteristicTypesParent()
    {
        assertSeparateCall(batchRefusal(kinds(true), flag("hierarchical", false), //$NON-NLS-1$
            property("dataLockFields", array("ChartOfCharacteristicTypes.Kinds.StandardAttribute.Parent"))), //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchical", "dataLockFields", "ChartOfCharacteristicTypes.Kinds.StandardAttribute.Parent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** RU: ...and for IsFolder, here making a flat chart hierarchical. */
    @Test
    public void testHierarchicalDecidesAChartOfCharacteristicTypesIsFolder()
    {
        // ChartOfCharacteristicTypes.Kinds.StandardAttribute.ЭтоГруппа
        assertSeparateCall(batchRefusal(kinds(false), flag("hierarchical", true), //$NON-NLS-1$
            property("dataLockFields", array("ChartOfCharacteristicTypes.Kinds.StandardAttribute.\u042D\u0442\u043E\u0413\u0440\u0443\u043F\u043F\u0430"))), //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchical", "dataLockFields", "ChartOfCharacteristicTypes.Kinds.StandardAttribute.IsFolder"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * RU: a chart of calculation types' ActionPeriodIsBasic is lockable only while actionPeriodUse - and
     * EDT's validator accepts a stale one with no marker, so nothing would catch it afterwards.
     */
    @Test
    public void testActionPeriodUseDecidesActionPeriodIsBasic()
    {
        // ChartOfCalculationTypes.Accruals.StandardAttribute.ПериодДействияБазовый
        assertSeparateCall(batchRefusal(accruals(true), flag("actionPeriodUse", false), //$NON-NLS-1$
            property("dataLockFields", //$NON-NLS-1$
                array("ChartOfCalculationTypes.Accruals.StandardAttribute.\u041F\u0435\u0440\u0438\u043E\u0434\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u044F\u0411\u0430\u0437\u043E\u0432\u044B\u0439"))), //$NON-NLS-1$
            "actionPeriodUse", "dataLockFields", //$NON-NLS-1$ //$NON-NLS-2$
            "ChartOfCalculationTypes.Accruals.StandardAttribute.ActionPeriodIsBasic"); //$NON-NLS-1$
    }

    /** A deciding property and a list that leaves its field out share one call. */
    @Test
    public void testADecidingPropertyAndAListWithoutItsFieldShareOneCall() throws Exception
    {
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(hierarchicalCatalog(), changes, flag("hierarchical", false), //$NON-NLS-1$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Description", //$NON-NLS-1$ //$NON-NLS-2$
                "Catalog.Goods.StandardAttribute.Code")))); //$NON-NLS-1$
        assertEquals(2, changes.size());

        assertNull(batchRefusal(catalog(false, HierarchyType.HIERARCHY_FOLDERS_AND_ITEMS, true),
            property("owners", new JsonArray()), //$NON-NLS-1$
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
        assertNull(batchRefusal(hierarchicalCatalog(), flag("hierarchical", false), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$

        String error = errorText(validateAndPrepare(hierarchicalCatalog(), new ArrayList<>(),
            flag("hierarchical", false), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(error, "does not offer it for 'inputByString'"); //$NON-NLS-1$
        assertFalse(error, error.contains("separate call")); //$NON-NLS-1$
    }

    /** A deciding property the target does not have keeps its own refusal: a document has no hierarchy. */
    @Test
    public void testTheGuardIgnoresADecidingPropertyTheTargetDoesNotHave()
    {
        Document order = MdClassFactory.eINSTANCE.createDocument();
        order.setName("Order"); //$NON-NLS-1$
        standard(order, "Number", "\u041D\u043E\u043C\u0435\u0440", SourceType.STANDARD_FIELDS, SourceType.INPUT_BY_STRING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);
        assertNull(batchRefusal(order, flag("hierarchical", false), //$NON-NLS-1$
            property("dataLockFields", array("Document.Order.StandardAttribute.Parent")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the batch guard: its whole table, cell by cell ====================

    /** Every standard field a cell asks about: each one a property decides, and DeletionMark, which none does. */
    private static final List<String> ASKED_FIELDS = Arrays.asList("Code", "Description", "Number", "Parent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "IsFolder", "Owner", "ActionPeriodIsBasic", "DeletionMark"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private static List<String> fields(String... names)
    {
        return Arrays.asList(names);
    }

    /** Document.Order: its Number offered for both lists, as with numberLength above 0. */
    private static Document order()
    {
        Document order = MdClassFactory.eINSTANCE.createDocument();
        order.setName("Order"); //$NON-NLS-1$
        standard(order, "Number", "\u041D\u043E\u043C\u0435\u0440", SourceType.STANDARD_FIELDS, SourceType.INPUT_BY_STRING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);
        return order;
    }

    /** {@code object} named {@code name}, with no fields computed: the guard needs only its properties. */
    private static BasicDbObject named(BasicDbObject object, String name)
    {
        object.setName(name);
        return object;
    }

    /**
     * Asks the guard about every cell of {@code property} on {@code target} - each list, each field of
     * {@link #ASKED_FIELDS}: a field of {@code forInputByString} / {@code forDataLock} must get the whole
     * separate-call refusal for that list, and every other cell must pass. Each wrong cell is added to
     * {@code wrong}.
     */
    private static void askEveryCell(MdObject target, String property, JsonElement value,
        List<String> forInputByString, List<String> forDataLock, List<String> wrong)
    {
        String owner = target.eClass().getName() + '.' + target.getName();
        for (String list : new String[] {"inputByString", "dataLockFields"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            List<String> decided = "inputByString".equals(list) ? forInputByString : forDataLock; //$NON-NLS-1$
            for (String field : ASKED_FIELDS)
            {
                String address = owner + ".StandardAttribute." + field; //$NON-NLS-1$
                String refusal = batchRefusal(target, property(property, value), property(list, array(address)));
                String mismatch = decided.contains(field) ? separateCallMismatch(refusal, property, list, address)
                    : refusal == null ? null : "expected the batch to pass, got: " + refusal; //$NON-NLS-1$
                if (mismatch != null)
                {
                    wrong.add(owner + " / " + property + " / " + list + " / " + field + ": " + mismatch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
            }
        }
    }

    /**
     * The guard's whole table, spelled out here rather than read from FieldListSupport - a test that
     * read the production table would follow any change made to it. For each property: the standard
     * fields EDT's MdFieldInferrer gives a list's source type only under that property (md 27.1
     * MdFieldInferrer.xtend, the same rules in md 28.0) - a length decides its own field for BOTH lists,
     * every other property for data locking only. Every cell, kind x property x list x field, is asked
     * on every kind that declares the property, so a dropped cell (one list of a length) and an extra
     * one (a property deciding a field it does not) both fail, with every wrong cell listed at once.
     */
    @Test
    public void testTheGuardRefusesExactlyTheCellsEdtsInferrerGates()
    {
        List<String> none = fields();
        List<String> wrong = new ArrayList<>();
        Catalog goods = catalog(true, HierarchyType.HIERARCHY_FOLDERS_AND_ITEMS, true);
        ChartOfCharacteristicTypes kinds = kinds(true);
        ChartOfCalculationTypes accruals = accruals(true);
        BasicDbObject accounts = named(MdClassFactory.eINSTANCE.createChartOfAccounts(), "Accounts"); //$NON-NLS-1$
        BasicDbObject branches = named(MdClassFactory.eINSTANCE.createExchangePlan(), "Branches"); //$NON-NLS-1$
        for (MdObject target : Arrays.<MdObject> asList(goods, kinds, accruals, accounts, branches))
        {
            askEveryCell(target, "codeLength", new JsonPrimitive(0), fields("Code"), fields("Code"), wrong); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            askEveryCell(target, "descriptionLength", new JsonPrimitive(50), fields("Description"), //$NON-NLS-1$ //$NON-NLS-2$
                fields("Description"), wrong); //$NON-NLS-1$
        }
        BasicDbObject approval = named(MdClassFactory.eINSTANCE.createBusinessProcess(), "Approval"); //$NON-NLS-1$
        BasicDbObject job = named(MdClassFactory.eINSTANCE.createTask(), "Job"); //$NON-NLS-1$
        for (MdObject target : Arrays.<MdObject> asList(order(), approval, job))
        {
            askEveryCell(target, "numberLength", new JsonPrimitive(11), fields("Number"), fields("Number"), wrong); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        askEveryCell(job, "descriptionLength", new JsonPrimitive(50), fields("Description"), //$NON-NLS-1$ //$NON-NLS-2$
            fields("Description"), wrong); //$NON-NLS-1$
        for (MdObject target : Arrays.<MdObject> asList(goods, kinds))
        {
            askEveryCell(target, "hierarchical", new JsonPrimitive(false), none, fields("Parent", "IsFolder"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                wrong);
        }
        askEveryCell(goods, "hierarchyType", new JsonPrimitive("HierarchyOfItems"), none, fields("IsFolder"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            wrong);
        askEveryCell(goods, "owners", new JsonArray(), none, fields("Owner"), wrong); //$NON-NLS-1$ //$NON-NLS-2$
        askEveryCell(accruals, "actionPeriodUse", new JsonPrimitive(false), none, //$NON-NLS-1$
            fields("ActionPeriodIsBasic"), wrong); //$NON-NLS-1$
        assertTrue(wrong.size() + " cell(s) differ from EDT's inferrer:\n" + String.join("\n", wrong), //$NON-NLS-1$ //$NON-NLS-2$
            wrong.isEmpty());
    }

    /**
     * Every list and every deciding property of a batch is asked: here the list that names the decided
     * field comes second, after a list that leaves it out, and so does the property that decides it,
     * after one that does not.
     */
    @Test
    public void testTheGuardAsksEveryListAndEveryDecidingPropertyOfTheBatch() throws Exception
    {
        assertSeparateCall(batchRefusal(hierarchicalCatalog(), property("codeLength", "11"), //$NON-NLS-1$ //$NON-NLS-2$
            flag("hierarchical", false), //$NON-NLS-1$
            property("inputByString", array("Catalog.Goods.StandardAttribute.Description")), //$NON-NLS-1$ //$NON-NLS-2$
            property("dataLockFields", array("Catalog.Goods.StandardAttribute.Parent"))), //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchical", "dataLockFields", "Catalog.Goods.StandardAttribute.Parent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Both lists leave the decided fields out: one call, every property prepared.
        List<Object> changes = new ArrayList<>();
        assertNull(validateAndPrepare(hierarchicalCatalog(), changes, flag("hierarchical", false), //$NON-NLS-1$
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
        assertSeparateCall(batchRefusal(hierarchicalCatalog(), flag("Hierarchical", false), //$NON-NLS-1$
            property("DataLockFields", array("Catalog.Goods.StandardAttribute.Parent"))), //$NON-NLS-1$ //$NON-NLS-2$
            "hierarchical", "dataLockFields", "Catalog.Goods.StandardAttribute.Parent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
