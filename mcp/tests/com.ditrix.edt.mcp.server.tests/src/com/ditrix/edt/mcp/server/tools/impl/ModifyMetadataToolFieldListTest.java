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
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
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
     * re-checks a stale standard field - so the two may not share a call, in either order.
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

    /** The guard is about the lengths only: a field list next to any other property is prepared. */
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
