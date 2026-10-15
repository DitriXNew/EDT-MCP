/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.mcore.DerivedField;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.SourceType;
import com._1c.g5.v8.dt.metadata.mdclass.AddressingAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.BasicDbObject;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * {@link FieldListSupport}: the field-list value of {@code inputByString} / {@code dataLockFields}
 * (issue #740).
 *
 * <p>Detached objects only. The fields EDT would infer are put into {@code getFields()} by hand, with
 * what EDT gives them: a standard field carries the prefix {@code StandardAttribute}, its English
 * {@code name} and its Russian {@code nameRu} (MdFieldInferrer.newFixedField); an attribute's derived
 * field carries the feature it stands for as {@code source}, and that feature's programmatic Name as
 * BOTH name and nameRu (MdFieldInferrer.newDerivedField); a common attribute's field carries its Name
 * and NO nameRu at all - CommonAttributeFieldInferrer sets it to null (md 27.1 and 28.0).</p>
 */
public class FieldListSupportTest
{
    private static final EReference INPUT = MdClassPackage.Literals.BASIC_DB_OBJECT__INPUT_BY_STRING;

    private static final EReference LOCK = MdClassPackage.Literals.BASIC_DB_OBJECT__DATA_LOCK_FIELDS;

    // ==================== builders ====================

    private static Catalog catalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    /** A standard field the way MdFieldInferrer.newFixedField builds it. */
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
     * An attribute's derived field the way MdFieldInferrer.newDerivedField builds it: nameRu is the
     * Name too. A common attribute's field is built by {@link #common} instead.
     */
    private static DerivedField derived(BasicDbObject owner, MdObject source, String prefix,
        SourceType... sources)
    {
        DerivedField field = McoreFactory.eINSTANCE.createDerivedField();
        field.setName(source.getName());
        field.setNameRu(source.getName());
        field.setSource(source);
        field.setFieldIdPrefix(prefix);
        field.getSourceTypes().addAll(Arrays.asList(sources));
        owner.getFields().add(field);
        return field;
    }

    /**
     * A common attribute's field the way CommonAttributeFieldInferrer.newDerivedField builds it: its
     * Name, the prefix {@code CommonAttribute} and NO nameRu - the inferrer stores null there.
     */
    private static DerivedField common(BasicDbObject owner, CommonAttribute source, SourceType... sources)
    {
        DerivedField field = McoreFactory.eINSTANCE.createDerivedField();
        field.setName(source.getName());
        field.setNameRu(null);
        field.setSource(source);
        field.setFieldIdPrefix("CommonAttribute"); //$NON-NLS-1$
        field.getSourceTypes().addAll(Arrays.asList(sources));
        owner.getFields().add(field);
        return field;
    }

    /** Task.Job with a String addressing attribute Executor, as EDT infers it (offered for both lists). */
    private static Task job()
    {
        Task job = MdClassFactory.eINSTANCE.createTask();
        job.setName("Job"); //$NON-NLS-1$
        AddressingAttribute executor = MdClassFactory.eINSTANCE.createAddressingAttribute();
        executor.setName("Executor"); //$NON-NLS-1$
        job.getAddressingAttributes().add(executor);
        derived(job, executor, "AddressingAttribute", SourceType.INPUT_BY_STRING, //$NON-NLS-1$
            SourceType.FIELDS_FOR_LOCKING);
        return job;
    }

    private static CatalogAttribute attribute(Catalog owner, String name)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName(name);
        owner.getAttributes().add(attribute);
        return attribute;
    }

    /** Catalog.Goods as EDT infers it with codeLength and descriptionLength above 0. */
    private static Catalog goods()
    {
        Catalog goods = catalog("Goods"); //$NON-NLS-1$
        standard(goods, "Description", "\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.INPUT_BY_STRING, SourceType.FIELDS_FOR_LOCKING);
        standard(goods, "Code", "\u041A\u043E\u0434", SourceType.STANDARD_FIELDS, SourceType.INPUT_BY_STRING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);
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

    private static List<String> resolved(EObject owner, EReference feature, JsonElement value)
    {
        List<String> ids = new ArrayList<>();
        String refusal = FieldListSupport.resolve(owner, feature, feature.getName(), value, ids);
        assertNull("expected " + value + " to resolve, got: " + refusal, refusal); //$NON-NLS-1$ //$NON-NLS-2$
        return ids;
    }

    private static String refused(EObject owner, EReference feature, JsonElement value)
    {
        List<String> ids = new ArrayList<>();
        String refusal = FieldListSupport.resolve(owner, feature, feature.getName(), value, ids);
        assertNotNull("expected a refusal for " + value, refusal); //$NON-NLS-1$
        assertTrue("a refused value prepares nothing: " + ids, ids.isEmpty()); //$NON-NLS-1$
        return refusal;
    }

    private static void assertContains(String text, String expected)
    {
        assertTrue("expected <" + expected + "> in: " + text, text.contains(expected)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@code fragment} is in the refusal, and the guide quotes it as it is: {@code ("<fragment>")}. */
    private static void assertQuotedVerbatim(String guide, String refusal, String fragment)
    {
        assertContains(refusal, fragment);
        assertContains(guide, "(\"" + fragment + "\")"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== addresses resolve to field ids ====================

    @Test
    public void testAnEnglishStandardAddressResolvesToItsFieldId()
    {
        assertEquals(Collections.singletonList("StandardAttribute:Description"), //$NON-NLS-1$
            resolved(goods(), INPUT, array("Catalog.Goods.StandardAttribute.Description"))); //$NON-NLS-1$
    }

    /** RU: the type, the kind and the field name all in Russian; nameRu is the model's own spelling. */
    @Test
    public void testARussianAddressResolvesToTheSameFieldId()
    {
        // Справочник.Goods.СтандартныйРеквизит.Наименование
        assertEquals(Collections.singletonList("StandardAttribute:Description"), //$NON-NLS-1$
            resolved(goods(), INPUT, array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435"))); //$NON-NLS-1$
    }

    @Test
    public void testTokensAreCaseInsensitiveAndPluralSpellingsAreAccepted()
    {
        assertEquals(Collections.singletonList("StandardAttribute:Description"), //$NON-NLS-1$
            resolved(goods(), INPUT, array("catalog.GOODS.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.description"))); //$NON-NLS-1$
        assertEquals(Collections.singletonList("StandardAttribute:Code"), //$NON-NLS-1$
            resolved(goods(), INPUT, array("Catalogs.Goods.StandardAttributes.Code"))); //$NON-NLS-1$
        // Справочники.Goods.СтандартныеРеквизиты.КОД - Russian plurals, the name upper-cased
        assertEquals(Collections.singletonList("StandardAttribute:Code"), //$NON-NLS-1$
            resolved(goods(), INPUT, array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A\u0438.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0435\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442\u044B.\u041A\u041E\u0414"))); //$NON-NLS-1$
    }

    @Test
    public void testAnAttributeAddressResolvesInBothLanguages()
    {
        Catalog goods = goods();
        derived(goods, attribute(goods, "Weight"), "Attribute", SourceType.INPUT_BY_STRING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);

        assertEquals(Collections.singletonList("Attribute:Weight"), //$NON-NLS-1$
            resolved(goods, INPUT, array("Catalog.Goods.Attribute.Weight"))); //$NON-NLS-1$
        // Справочник.Goods.Реквизит.Weight
        assertEquals(Collections.singletonList("Attribute:Weight"), //$NON-NLS-1$
            resolved(goods, INPUT, array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.Weight"))); //$NON-NLS-1$
    }

    /**
     * EDT stores a common attribute's field as its own two-segment name, never under the owner. The
     * field carries no nameRu (CommonAttributeFieldInferrer stores null), so a lookup of ANOTHER name
     * that reaches it must not trip over the null.
     */
    @Test
    public void testACommonAttributeIsAddressedByItsOwnTwoSegmentName()
    {
        Catalog goods = goods();
        CommonAttribute org = MdClassFactory.eINSTANCE.createCommonAttribute();
        org.setName("Org"); //$NON-NLS-1$
        common(goods, org, SourceType.INPUT_BY_STRING);

        assertEquals(Collections.singletonList("CommonAttribute:Org"), //$NON-NLS-1$
            resolved(goods, INPUT, array("CommonAttribute.Org"))); //$NON-NLS-1$
        assertContains(refused(goods, INPUT, array("CommonAttribute.Nope")), //$NON-NLS-1$
            "'CommonAttribute.Nope': Catalog.Goods has no field 'CommonAttribute.Nope'"); //$NON-NLS-1$
        // ОбщийРеквизит.Org
        assertEquals(Collections.singletonList("CommonAttribute:Org"), //$NON-NLS-1$
            resolved(goods, INPUT, array("\u041E\u0431\u0449\u0438\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.Org"))); //$NON-NLS-1$
    }

    @Test
    public void testADocumentNumberResolvesByItsRussianName()
    {
        Document order = MdClassFactory.eINSTANCE.createDocument();
        order.setName("Order"); //$NON-NLS-1$
        standard(order, "Number", "\u041D\u043E\u043C\u0435\u0440", SourceType.STANDARD_FIELDS, SourceType.INPUT_BY_STRING); //$NON-NLS-1$ //$NON-NLS-2$

        // Документ.Order.СтандартныйРеквизит.Номер
        assertEquals(Collections.singletonList("StandardAttribute:Number"), //$NON-NLS-1$
            resolved(order, INPUT, array("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.Order.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041D\u043E\u043C\u0435\u0440"))); //$NON-NLS-1$
    }

    /**
     * A task's addressing attribute: EDT's property editor offers a String one for both lists, but
     * EDT's model validator accepts a derived input-by-string field only from an object attribute or a
     * common attribute and marks anything else with error 4. The intersection is what is offered here.
     */
    @Test
    public void testATaskAddressingAttributeIsLockableButNeverOfferedForInputByString()
    {
        Task job = MdClassFactory.eINSTANCE.createTask();
        job.setName("Job"); //$NON-NLS-1$
        AddressingAttribute executor = MdClassFactory.eINSTANCE.createAddressingAttribute();
        executor.setName("Executor"); //$NON-NLS-1$
        job.getAddressingAttributes().add(executor);
        derived(job, executor, "AddressingAttribute", SourceType.INPUT_BY_STRING, //$NON-NLS-1$
            SourceType.FIELDS_FOR_LOCKING);

        // Задача.Job.РеквизитАдресации.Executor
        assertEquals(Collections.singletonList("AddressingAttribute:Executor"), //$NON-NLS-1$
            resolved(job, LOCK, array("\u0417\u0430\u0434\u0430\u0447\u0430.Job.\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442\u0410\u0434\u0440\u0435\u0441\u0430\u0446\u0438\u0438.Executor"))); //$NON-NLS-1$
        String refusal = refused(job, INPUT, array("Task.Job.AddressingAttribute.Executor")); //$NON-NLS-1$
        assertContains(refusal, "Task.Job.AddressingAttribute.Executor"); //$NON-NLS-1$
        assertContains(refusal, "addressing attribute"); //$NON-NLS-1$
        // By cause: EDT's editor DOES offer it, only the validator rejects it.
        assertContains(refusal, "is a field of Task.Job that EDT's validator rejects in 'inputByString'"); //$NON-NLS-1$
        assertFalse(refusal, refusal.contains("does not offer it")); //$NON-NLS-1$
        assertFalse(FieldListSupport.eligibleAddresses(job, INPUT)
            .contains("Task.Job.AddressingAttribute.Executor")); //$NON-NLS-1$
        assertTrue(FieldListSupport.eligibleAddresses(job, LOCK)
            .contains("Task.Job.AddressingAttribute.Executor")); //$NON-NLS-1$
    }

    @Test
    public void testTheOrderIsKeptAnEmptyArrayClearsAndABareStringIsOneElement()
    {
        assertEquals(Arrays.asList("StandardAttribute:Code", "StandardAttribute:Description"), //$NON-NLS-1$ //$NON-NLS-2$
            resolved(goods(), INPUT, array("Catalog.Goods.StandardAttribute.Code", //$NON-NLS-1$
                "Catalog.Goods.StandardAttribute.Description"))); //$NON-NLS-1$
        assertEquals(Arrays.asList("StandardAttribute:Description", "StandardAttribute:Code"), //$NON-NLS-1$ //$NON-NLS-2$
            resolved(goods(), INPUT, array("Catalog.Goods.StandardAttribute.Description", //$NON-NLS-1$
                "Catalog.Goods.StandardAttribute.Code"))); //$NON-NLS-1$
        assertTrue(resolved(goods(), INPUT, new JsonArray()).isEmpty());
        assertEquals(Collections.singletonList("StandardAttribute:Code"), //$NON-NLS-1$
            resolved(goods(), INPUT, new JsonPrimitive("Catalog.Goods.StandardAttribute.Code"))); //$NON-NLS-1$
    }

    /** Clearing needs no field at all, so it is not held up by fields EDT has not computed yet. */
    @Test
    public void testAnEmptyArrayClearsEvenBeforeTheFieldsAreComputed()
    {
        assertTrue(resolved(catalog("Goods"), INPUT, new JsonArray()).isEmpty()); //$NON-NLS-1$
    }

    // ==================== refusals ====================

    @Test
    public void testAStandardFieldWithoutItsLengthIsRefusedWithTheRule()
    {
        Catalog goods = catalog("Goods"); //$NON-NLS-1$
        standard(goods, "Description", "\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.INPUT_BY_STRING, SourceType.FIELDS_FOR_LOCKING);
        // codeLength = 0: EDT keeps the Code field but gives it STANDARD_FIELDS only
        standard(goods, "Code", "\u041A\u043E\u0434", SourceType.STANDARD_FIELDS); //$NON-NLS-1$ //$NON-NLS-2$

        String refusal = refused(goods, INPUT, array("Catalog.Goods.StandardAttribute.Code")); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        assertContains(refusal, "codeLength"); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Description"); //$NON-NLS-1$
    }

    @Test
    public void testEligibilityIsDecidedPerList()
    {
        Catalog goods = goods();
        standard(goods, "DeletionMark", "\u041F\u043E\u043C\u0435\u0442\u043A\u0430\u0423\u0434\u0430\u043B\u0435\u043D\u0438\u044F", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);

        assertEquals(Collections.singletonList("StandardAttribute:DeletionMark"), //$NON-NLS-1$
            resolved(goods, LOCK, array("Catalog.Goods.StandardAttribute.DeletionMark"))); //$NON-NLS-1$
        String refusal = refused(goods, INPUT, array("Catalog.Goods.StandardAttribute.DeletionMark")); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.DeletionMark"); //$NON-NLS-1$
        assertFalse(FieldListSupport.eligibleAddresses(goods, INPUT)
            .contains("Catalog.Goods.StandardAttribute.DeletionMark")); //$NON-NLS-1$
        assertTrue(FieldListSupport.eligibleAddresses(goods, LOCK)
            .contains("Catalog.Goods.StandardAttribute.DeletionMark")); //$NON-NLS-1$
    }

    /** RU: a field of ANOTHER object is refused, naming both objects canonically. */
    @Test
    public void testAnotherObjectsFieldIsRefusedNamingBothObjects()
    {
        // Справочник.Other.СтандартныйРеквизит.Код
        String refusal = refused(goods(), INPUT, array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Other.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434")); //$NON-NLS-1$
        assertContains(refusal, "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Other.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434"); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Other"); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods"); //$NON-NLS-1$

        String otherType = refused(goods(), INPUT, array("Document.Goods.StandardAttribute.Code")); //$NON-NLS-1$
        assertContains(otherType, "Document.Goods"); //$NON-NLS-1$
        assertContains(otherType, "Catalog.Goods"); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownFieldListsTheOfferedAddresses()
    {
        String refusal = refused(goods(), INPUT, array("Catalog.Goods.StandardAttribute.Nope")); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Nope"); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Description"); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
    }

    @Test
    public void testTheSameFieldTwiceIsRefusedWhateverTheSpelling()
    {
        // ... and Справочник.Goods.СтандартныйРеквизит.Код
        String refusal = refused(goods(), INPUT, array("Catalog.Goods.StandardAttribute.Code", //$NON-NLS-1$
            "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434")); //$NON-NLS-1$
        assertContains(refusal, "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434"); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        assertContains(refusal, "twice"); //$NON-NLS-1$
    }

    // Each malformed shape has a test of its own, so each refusal is shown to matter on its own.

    @Test
    public void testAnAddressOfTheWrongShapeIsRefusedNamingIt()
    {
        assertContains(refused(goods(), INPUT, array("Catalog.Goods.Code")), //$NON-NLS-1$
            "'Catalog.Goods.Code' is not a field address"); //$NON-NLS-1$
    }

    @Test
    public void testANonStringElementIsRefusedNamingItAndItsIndex()
    {
        JsonArray number = new JsonArray();
        number.add(42);
        assertContains(refused(goods(), INPUT, number), "Element at index 0 of 'inputByString' (42)"); //$NON-NLS-1$
    }

    /**
     * A value that is no array is refused ECHOING it. The fixed text says "address" too ("a JSON array
     * of field addresses"), so only the quoted JSON of the value proves the echo.
     */
    @Test
    public void testAnObjectValueIsRefusedEchoingTheValue()
    {
        JsonElement object = JsonParser.parseString("{\"address\":\"Catalog.Goods.StandardAttribute.Code\"}"); //$NON-NLS-1$
        String refusal = refused(goods(), INPUT, object);
        assertContains(refusal, "Got: {\"address\":\"Catalog.Goods.StandardAttribute.Code\"}"); //$NON-NLS-1$
        assertContains(refusal, "JSON array"); //$NON-NLS-1$
    }

    @Test
    public void testANestedArrayIsRefusedNamingTheInnerValue()
    {
        JsonElement nested = JsonParser.parseString("[[\"Catalog.Goods.StandardAttribute.Code\"]]"); //$NON-NLS-1$
        assertContains(refused(goods(), INPUT, nested), "([\"Catalog.Goods.StandardAttribute.Code\"])"); //$NON-NLS-1$
    }

    @Test
    public void testAMissingValueIsRefusedNamingTheProperty()
    {
        String refusal = refused(goods(), INPUT, null);
        assertContains(refusal, "'inputByString' takes a JSON array"); //$NON-NLS-1$
        assertContains(refusal, "Got: nothing"); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownKindTokenIsRefusedNamingIt()
    {
        assertContains(refused(goods(), INPUT, array("Catalog.Goods.Nope.Code")), //$NON-NLS-1$
            "'Nope' is not a field kind"); //$NON-NLS-1$
    }

    /** A type token that is no metadata type is reported as exactly that - not as "another object". */
    @Test
    public void testAnUnknownTypeTokenIsReportedAsSuch()
    {
        String refusal = refused(goods(), INPUT, array("Catalg.Goods.StandardAttribute.Code")); //$NON-NLS-1$
        assertContains(refusal, "'Catalg.Goods.StandardAttribute.Code': 'Catalg' is not a metadata type"); //$NON-NLS-1$
        assertContains(refusal, "addressed as 'Catalog.Goods.<Kind>.<Name>'"); //$NON-NLS-1$
        assertFalse(refusal, refusal.contains("names a field of")); //$NON-NLS-1$
    }

    /**
     * The kind segment is part of a field's identity: a standard field named under another kind is no
     * field at all. Without that check Attribute.Code, CommonAttribute.Code or the Russian Реквизит.Код
     * would resolve to the STANDARD Code and be stored under a kind the caller never named.
     */
    @Test
    public void testTheKindSegmentMustBeTheFieldsOwnKind()
    {
        assertContains(refused(goods(), INPUT, array("Catalog.Goods.Attribute.Code")), //$NON-NLS-1$
            "'Catalog.Goods.Attribute.Code': Catalog.Goods has no field 'Attribute.Code'"); //$NON-NLS-1$
        assertContains(refused(goods(), INPUT, array("CommonAttribute.Code")), //$NON-NLS-1$
            "'CommonAttribute.Code': Catalog.Goods has no field 'CommonAttribute.Code'"); //$NON-NLS-1$
        // Справочник.Goods.Реквизит.Код - the standard Code's Russian name under the attribute kind
        assertContains(refused(goods(), INPUT, array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods.\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434")), //$NON-NLS-1$
            "Catalog.Goods has no field 'Attribute.\u041A\u043E\u0434'"); //$NON-NLS-1$
    }

    /** An empty field list is NOT knowledge that nothing is offered - it is derived data not built yet. */
    @Test
    public void testFieldsNotComputedYetAreNotReportedAsIneligible()
    {
        String refusal = refused(catalog("Goods"), INPUT, //$NON-NLS-1$
            array("Catalog.Goods.StandardAttribute.Code")); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        assertContains(refusal, "not computed"); //$NON-NLS-1$
        assertContains(refusal, "retry"); //$NON-NLS-1$
        assertFalse("not computed is not \"not offered\": " + refusal, refusal.contains("offer")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** C4: re-sending Current is refused once EDT stopped offering an entry, and the text says why. */
    @Test
    public void testACurrentEntryNoLongerOfferedIsRefusedAsDropIt()
    {
        Catalog goods = catalog("Goods"); //$NON-NLS-1$
        Field description = standard(goods, "Description", "\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.INPUT_BY_STRING);
        Field code = standard(goods, "Code", "\u041A\u043E\u0434", SourceType.STANDARD_FIELDS); //$NON-NLS-1$ //$NON-NLS-2$
        goods.getInputByString().add(code);
        goods.getInputByString().add(description);

        String refusal = refused(goods, INPUT, array("Catalog.Goods.StandardAttribute.Code", //$NON-NLS-1$
            "Catalog.Goods.StandardAttribute.Description")); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.StandardAttribute.Code"); //$NON-NLS-1$
        assertContains(refusal, "current list"); //$NON-NLS-1$
        assertContains(refusal, "drop it"); //$NON-NLS-1$
    }

    /**
     * C4, the second wording: an entry of Current whose field EDT no longer has at all (its attribute
     * was deleted) is refused as "drop it" too, and the text says the field is GONE rather than "no
     * longer offered".
     */
    @Test
    public void testACurrentEntryWhoseFieldIsGoneIsRefusedAsDropIt()
    {
        Catalog goods = goods();
        Field gone = McoreFactory.eINSTANCE.createField();
        ((InternalEObject)gone).eSetProxyURI(
            FieldListSupport.proxyUri("engine-1", "Catalog.Goods", "Attribute:Gone")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        goods.getInputByString().add(gone);

        String refusal = refused(goods, INPUT, array("Catalog.Goods.Attribute.Gone")); //$NON-NLS-1$
        assertContains(refusal, "'Catalog.Goods.Attribute.Gone' is in the current list, but Catalog.Goods no " //$NON-NLS-1$
            + "longer has that field - drop it from the list."); //$NON-NLS-1$
    }

    /**
     * C6 stated by cause: a task's addressing attribute held in Current is rejected by EDT's VALIDATOR -
     * EDT's editor still offers it, so "no longer offered" would contradict the rule printed after it.
     */
    @Test
    public void testACurrentEntryTheValidatorRejectsIsRefusedNamingTheValidator()
    {
        Task job = job();
        job.getInputByString().add(job.getFields().get(0));

        String refusal = refused(job, INPUT, array("Task.Job.AddressingAttribute.Executor")); //$NON-NLS-1$
        assertContains(refusal, "'Task.Job.AddressingAttribute.Executor' is in the current list, but EDT's " //$NON-NLS-1$
            + "validator rejects it in 'inputByString' - drop it."); //$NON-NLS-1$
        assertFalse(refusal, refusal.contains("no longer offers")); //$NON-NLS-1$
    }

    /** C2: an extension cannot change an adopted object's lists; Current stays readable, Allowed empty. */
    @Test
    public void testAnAdoptedTargetIsRefusedAndOffersNothing()
    {
        Catalog goods = goods();
        goods.setObjectBelonging(ObjectBelonging.ADOPTED);

        String refusal = refused(goods, INPUT, array("Catalog.Goods.StandardAttribute.Code")); //$NON-NLS-1$
        assertContains(refusal, "adopted"); //$NON-NLS-1$
        assertContains(refusal, "base configuration"); //$NON-NLS-1$
        assertTrue(FieldListSupport.allowedValues(goods, INPUT).isEmpty());
        assertFalse("the offered list itself is still read for a native object", //$NON-NLS-1$
            FieldListSupport.allowedValues(goods(), INPUT).isEmpty());
    }

    /**
     * A data-lock refusal of a standard field EDT does not offer names what would bring the field: an
     * IsFolder needs a hierarchical object with folders, an ActionPeriodIsBasic needs actionPeriodUse -
     * so the second call of the batch guard's recipe does not end in a rule that leaves the field out.
     */
    @Test
    public void testADataLockRefusalNamesWhatEachStandardFieldNeeds()
    {
        Catalog flat = goods();
        standard(flat, "IsFolder", "\u042D\u0442\u043E\u0413\u0440\u0443\u043F\u043F\u0430", SourceType.STANDARD_FIELDS); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(refused(flat, LOCK, array("Catalog.Goods.StandardAttribute.IsFolder")), //$NON-NLS-1$
            "an IsFolder on a hierarchical catalog of HierarchyFoldersAndItems or a hierarchical chart of " //$NON-NLS-1$
                + "characteristic types"); //$NON-NLS-1$

        ChartOfCalculationTypes accruals = MdClassFactory.eINSTANCE.createChartOfCalculationTypes();
        accruals.setName("Accruals"); //$NON-NLS-1$
        standard(accruals, "ActionPeriodIsBasic", "\u041F\u0435\u0440\u0438\u043E\u0434\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u044F\u0411\u0430\u0437\u043E\u0432\u044B\u0439", SourceType.STANDARD_FIELDS); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(refused(accruals, LOCK,
            array("ChartOfCalculationTypes.Accruals.StandardAttribute.ActionPeriodIsBasic")), //$NON-NLS-1$
            "an ActionPeriodIsBasic while actionPeriodUse is true"); //$NON-NLS-1$
    }

    // ==================== an exchange plan's data lock fields: EDT's validator ====================

    /**
     * ExchangePlan.Branches as EDT infers it in a compatibility mode above 8.3.21 (MdFieldInferrer, md
     * 27.1 and 28.0): ExchangeDate, SentNo, DeletionMark, Description and Code all carry
     * FIELDS_FOR_LOCKING whatever the lengths, and Description and Code INPUT_BY_STRING too.
     */
    private static ExchangePlan branches(int codeLength, int descriptionLength)
    {
        ExchangePlan plan = MdClassFactory.eINSTANCE.createExchangePlan();
        plan.setName("Branches"); //$NON-NLS-1$
        plan.setCodeLength(codeLength);
        plan.setDescriptionLength(descriptionLength);
        standard(plan, "ExchangeDate", "\u0414\u0430\u0442\u0430\u041E\u0431\u043C\u0435\u043D\u0430", SourceType.FIELDS_FOR_LOCKING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.STANDARD_FIELDS);
        standard(plan, "SentNo", "\u041D\u043E\u043C\u0435\u0440\u041E\u0442\u043F\u0440\u0430\u0432\u043B\u0435\u043D\u043D\u043E\u0433\u043E", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);
        standard(plan, "DeletionMark", "\u041F\u043E\u043C\u0435\u0442\u043A\u0430\u0423\u0434\u0430\u043B\u0435\u043D\u0438\u044F", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING);
        standard(plan, "Description", "\u041D\u0430\u0438\u043C\u0435\u043D\u043E\u0432\u0430\u043D\u0438\u0435", SourceType.STANDARD_FIELDS, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.FIELDS_FOR_LOCKING, SourceType.INPUT_BY_STRING);
        standard(plan, "Code", "\u041A\u043E\u0434", SourceType.STANDARD_FIELDS, SourceType.FIELDS_FOR_LOCKING, //$NON-NLS-1$ //$NON-NLS-2$
            SourceType.INPUT_BY_STRING);
        return plan;
    }

    /**
     * RU: EDT's property editor offers an exchange plan's ExchangeDate for data locking, and its Code
     * and Description whatever their lengths, but EDT's validator admits Code and Description only
     * while the length is not 0 and ExchangeDate never (FixedFieldDescriptorProvider
     * .createExchangePlanDataLockFields, metadata 19.0 and 19.1) and marks the rest with error 81. The
     * intersection is what is offered here; input by string, checked against a table that ignores the
     * lengths, is not narrowed.
     */
    @Test
    public void testAnExchangePlansDataLockFieldsFollowEdtsValidator()
    {
        ExchangePlan plan = branches(0, 25);

        assertEquals(Arrays.asList("ExchangePlan.Branches.StandardAttribute.SentNo", //$NON-NLS-1$
            "ExchangePlan.Branches.StandardAttribute.DeletionMark", //$NON-NLS-1$
            "ExchangePlan.Branches.StandardAttribute.Description"), //$NON-NLS-1$
            FieldListSupport.eligibleAddresses(plan, LOCK));
        String refusal = refused(plan, LOCK, array("ExchangePlan.Branches.StandardAttribute.ExchangeDate")); //$NON-NLS-1$
        assertContains(refusal, "is a field of ExchangePlan.Branches that EDT's validator rejects in " //$NON-NLS-1$
            + "'dataLockFields'"); //$NON-NLS-1$
        assertContains(refusal, "never its ExchangeDate"); //$NON-NLS-1$
        assertFalse(refusal, refusal.contains("does not offer it")); //$NON-NLS-1$
        // ПланОбмена.Branches.СтандартныйРеквизит.Код - the Code of a plan whose codeLength is 0
        refusal = refused(plan, LOCK, array("\u041F\u043B\u0430\u043D\u041E\u0431\u043C\u0435\u043D\u0430.Branches.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434")); //$NON-NLS-1$
        assertContains(refusal, "is a field of ExchangePlan.Branches that EDT's validator rejects in " //$NON-NLS-1$
            + "'dataLockFields'"); //$NON-NLS-1$
        assertContains(refusal, "codeLength"); //$NON-NLS-1$

        // A Current entry the validator rejects is refused as "drop it".
        ExchangePlan stale = branches(0, 25);
        stale.getDataLockFields().add(stale.getFields().get(4));
        assertContains(refused(stale, LOCK, array("ExchangePlan.Branches.StandardAttribute.Code")), //$NON-NLS-1$
            "is in the current list, but EDT's validator rejects it in 'dataLockFields' - drop it."); //$NON-NLS-1$

        // With its length, Code is lockable again; without one, Description is not.
        assertEquals(Collections.singletonList("StandardAttribute:Code"), //$NON-NLS-1$
            resolved(branches(9, 25), LOCK, array("ExchangePlan.Branches.StandardAttribute.Code"))); //$NON-NLS-1$
        assertFalse(FieldListSupport.eligibleAddresses(branches(9, 0), LOCK)
            .contains("ExchangePlan.Branches.StandardAttribute.Description")); //$NON-NLS-1$
        // Input by string is not narrowed: both are offered there whatever the lengths.
        assertEquals(Arrays.asList("StandardAttribute:Code", "StandardAttribute:Description"), //$NON-NLS-1$ //$NON-NLS-2$
            resolved(branches(0, 0), INPUT, array("ExchangePlan.Branches.StandardAttribute.Code", //$NON-NLS-1$
                "ExchangePlan.Branches.StandardAttribute.Description"))); //$NON-NLS-1$
    }

    // ==================== bilingual minimum ====================

    /** RU: a Cyrillic programmatic name stays as it is in the canonical, English-structured address. */
    @Test
    public void testACyrillicNamedObjectGetsTheCanonicalAddress()
    {
        // Справочник.Товары with an attribute Вес
        Catalog goods = catalog("\u0422\u043E\u0432\u0430\u0440\u044B"); //$NON-NLS-1$
        derived(goods, attribute(goods, "\u0412\u0435\u0441"), "Attribute", SourceType.INPUT_BY_STRING); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.singletonList("Attribute:\u0412\u0435\u0441"), //$NON-NLS-1$
            resolved(goods, INPUT, array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u0422\u043E\u0432\u0430\u0440\u044B.\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u0412\u0435\u0441"))); //$NON-NLS-1$
        assertEquals(Collections.singletonList("Catalog.\u0422\u043E\u0432\u0430\u0440\u044B.Attribute.\u0412\u0435\u0441"), //$NON-NLS-1$
            FieldListSupport.eligibleAddresses(goods, INPUT));
    }

    /** RU: an attribute is named by its programmatic Name, never by its synonym. */
    @Test
    public void testASynonymIsNotAccepted()
    {
        Catalog goods = goods();
        CatalogAttribute weight = attribute(goods, "Weight"); //$NON-NLS-1$
        weight.getSynonym().put("ru", "\u0412\u0435\u0441"); //$NON-NLS-1$ //$NON-NLS-2$
        derived(goods, weight, "Attribute", SourceType.INPUT_BY_STRING); //$NON-NLS-1$

        String refusal = refused(goods, INPUT, array("Catalog.Goods.Attribute.\u0412\u0435\u0441")); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.Attribute.\u0412\u0435\u0441"); //$NON-NLS-1$
        assertContains(refusal, "Catalog.Goods.Attribute.Weight"); //$NON-NLS-1$
    }

    /**
     * The ё pins, both of them. The OWNER part is only a self-check of an object already resolved by
     * the call's fqn, so it is compared ё-insensitively; a FIELD name chooses between fields, so it is
     * compared exactly (case aside), as reference values are everywhere else.
     */
    @Test
    public void testTheOwnerIsComparedYoInsensitivelyAndTheFieldNameExactly()
    {
        Catalog account = catalog("\u0421\u0447\u0451\u0442"); //$NON-NLS-1$
        standard(account, "Code", "\u041A\u043E\u0434", SourceType.INPUT_BY_STRING); //$NON-NLS-1$ //$NON-NLS-2$
        derived(account, attribute(account, "\u041E\u0431\u044A\u0451\u043C"), "Attribute", SourceType.INPUT_BY_STRING); //$NON-NLS-1$ //$NON-NLS-2$

        // Справочник.Счет.СтандартныйРеквизит.Код - the owner written with е
        assertEquals(Collections.singletonList("StandardAttribute:Code"), //$NON-NLS-1$
            resolved(account, INPUT, array("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u0421\u0447\u0435\u0442.\u0421\u0442\u0430\u043D\u0434\u0430\u0440\u0442\u043D\u044B\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.\u041A\u043E\u0434"))); //$NON-NLS-1$
        assertEquals(Collections.singletonList("Attribute:\u041E\u0431\u044A\u0451\u043C"), //$NON-NLS-1$
            resolved(account, INPUT, array("Catalog.\u0421\u0447\u0451\u0442.Attribute.\u041E\u0431\u044A\u0451\u043C"))); //$NON-NLS-1$
        // ...but Объем (with е) is not the field Объём
        String refusal = refused(account, INPUT, array("Catalog.\u0421\u0447\u0451\u0442.Attribute.\u041E\u0431\u044A\u0435\u043C")); //$NON-NLS-1$
        assertContains(refusal, "Catalog.\u0421\u0447\u0451\u0442.Attribute.\u041E\u0431\u044A\u0435\u043C"); //$NON-NLS-1$
    }

    // ==================== the stored proxy and its address ====================

    /**
     * The proxy URI is the platform's own form, literally - measured with jshell against bm.core
     * 9.2.100 (2026.1) and 9.2.101 (2026.2): the field id is encoded, so its ':' becomes %3A while
     * Cyrillic stays as it is. The round trip below cannot see that step, because the decoder reads a
     * raw field id the same.
     */
    @Test
    public void testTheProxyUriIsThePlatformsEncodedForm()
    {
        assertEquals("bm://engine-1/Catalog.Goods#/fields:StandardAttribute%3ADescription", //$NON-NLS-1$
            FieldListSupport.proxyUri("engine-1", "Catalog.Goods", "StandardAttribute:Description").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Catalog.Товары, Attribute:Вес
        assertEquals("bm://engine-1/Catalog.\u0422\u043E\u0432\u0430\u0440\u044B#/fields:Attribute%3A\u0412\u0435\u0441", //$NON-NLS-1$
            FieldListSupport.proxyUri("engine-1", "Catalog.\u0422\u043E\u0432\u0430\u0440\u044B", "Attribute:\u0412\u0435\u0441").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("bm://engine-1/Catalog.Goods#/fields:CommonAttribute%3AOrg", //$NON-NLS-1$
            FieldListSupport.proxyUri("engine-1", "Catalog.Goods", "CommonAttribute:Org").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The write side and the read side meet in one value: the proxy the platform's own initializer
     * recipe builds must read back as the address EDT writes for it. A round trip, not a copy of the
     * recipe: the URI is taken apart again through the platform's own accessors. The literal form of
     * the URI, which this cannot see, is pinned by {@link #testTheProxyUriIsThePlatformsEncodedForm}.
     */
    @Test
    public void testAProxyBuiltByTheRecipeReadsBackAsItsAddress()
    {
        URI description = FieldListSupport.proxyUri("engine-1", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            "StandardAttribute:Description"); //$NON-NLS-1$
        assertTrue(BmUriUtil.isBmUri(description));
        assertEquals("Catalog.Goods", BmUriUtil.extractTopObjectFqn(description)); //$NON-NLS-1$
        assertTrue(BmUriUtil.extractContainmentPath(description).startsWith("/fields:")); //$NON-NLS-1$
        assertEquals("Catalog.Goods.StandardAttribute.Description", //$NON-NLS-1$
            FieldListSupport.addressOfUri(description));

        // Catalog.Товары.Attribute.Вес
        assertEquals("Catalog.\u0422\u043E\u0432\u0430\u0440\u044B.Attribute.\u0412\u0435\u0441", FieldListSupport.addressOfUri( //$NON-NLS-1$
            FieldListSupport.proxyUri("engine-1", "Catalog.\u0422\u043E\u0432\u0430\u0440\u044B", "Attribute:\u0412\u0435\u0441"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("CommonAttribute.Org", FieldListSupport.addressOfUri( //$NON-NLS-1$
            FieldListSupport.proxyUri("engine-1", "Catalog.Goods", "CommonAttribute:Org"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAResolvedFieldAndItsProxyHaveTheSameAddress()
    {
        Catalog goods = goods();
        Field code = goods.getFields().get(1);
        CommonAttribute org = MdClassFactory.eINSTANCE.createCommonAttribute();
        org.setName("Org"); //$NON-NLS-1$
        Field commonField = common(goods, org, SourceType.INPUT_BY_STRING);
        Field weight = derived(goods, attribute(goods, "Weight"), "Attribute", SourceType.INPUT_BY_STRING); //$NON-NLS-1$ //$NON-NLS-2$

        Field proxy = McoreFactory.eINSTANCE.createField();
        ((InternalEObject)proxy).eSetProxyURI(
            FieldListSupport.proxyUri("engine-1", "Catalog.Goods", "StandardAttribute:Code")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("Catalog.Goods.StandardAttribute.Code", FieldListSupport.addressOf(code)); //$NON-NLS-1$
        assertEquals(FieldListSupport.addressOf(code), FieldListSupport.addressOf(proxy));
        assertEquals("CommonAttribute.Org", FieldListSupport.addressOf(commonField)); //$NON-NLS-1$
        assertEquals("Catalog.Goods.Attribute.Weight", FieldListSupport.addressOf(weight)); //$NON-NLS-1$
    }

    @Test
    public void testAProxyThatIsNotABmFieldHasNoAddress()
    {
        Field proxy = McoreFactory.eINSTANCE.createField();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("unresolved:/x")); //$NON-NLS-1$
        assertNull(FieldListSupport.addressOf(proxy));
        assertNull(FieldListSupport.addressOf(null));
        assertNull(FieldListSupport.addressOf("Catalog.Goods.StandardAttribute.Code")); //$NON-NLS-1$
        // A BM URI into something other than the top object's fields is no field address either.
        assertNull(FieldListSupport.addressOfUri(BmUriUtil.createContainedBmObjectUri("engine-1", //$NON-NLS-1$
            "Catalog.Goods", "/attributes:Weight"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * C3: the list is replaced THROUGH change notifications. EDT's own initializer brackets its write
     * in eSetDeliver(false) because it fills a brand-new object; on an existing one the notification
     * is what tells BM's transaction, and EDT's onChange revalidation, that the list changed.
     */
    @Test
    public void testTheListIsReplacedThroughNotificationsInOrder()
    {
        Catalog goods = goods();
        goods.getInputByString().add(goods.getFields().get(0));
        List<Notification> seen = new ArrayList<>();
        goods.eAdapters().add(new EContentAdapter()
        {
            @Override
            public void notifyChanged(Notification notification)
            {
                super.notifyChanged(notification);
                if (notification.getFeature() == INPUT)
                {
                    seen.add(notification);
                }
            }
        });
        URI code = FieldListSupport.proxyUri("engine-1", "Catalog.Goods", "StandardAttribute:Code"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        URI description = FieldListSupport.proxyUri("engine-1", "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            "StandardAttribute:Description"); //$NON-NLS-1$

        FieldListSupport.replace(goods, INPUT, Arrays.asList(code, description));

        assertFalse("the replacement must reach the object's listeners", seen.isEmpty()); //$NON-NLS-1$
        EList<Field> stored = goods.getInputByString();
        assertEquals(2, stored.size());
        assertTrue(stored.get(0).eIsProxy());
        assertEquals(code, ((InternalEObject)stored.get(0)).eProxyURI());
        assertEquals(description, ((InternalEObject)stored.get(1)).eProxyURI());
    }

    /** A detached object has no BM engine to address its fields through: a programming error, loudly. */
    @Test
    public void testApplyRefusesAnObjectOutsideBm()
    {
        try
        {
            FieldListSupport.apply(goods(), INPUT, Collections.singletonList("StandardAttribute:Code")); //$NON-NLS-1$
            fail("a detached object cannot hold a BM proxy of its own fields"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertContains(e.getMessage(), "inputByString"); //$NON-NLS-1$
        }
    }

    // ==================== classification and listing ====================

    @Test
    public void testOnlyTheTwoMeasuredListsAreFieldLists()
    {
        assertTrue(FieldListSupport.isFieldListFeature(INPUT));
        assertTrue(FieldListSupport.isFieldListFeature(LOCK));
        assertFalse(FieldListSupport.isFieldListFeature(MdClassPackage.Literals.TABLE__INPUT_BY_STRING));
        assertFalse(FieldListSupport.isFieldListFeature(MdClassPackage.Literals.TABLE__KEY_FIELDS));
        assertFalse(FieldListSupport.isFieldListFeature(MdClassPackage.Literals.TABLE__DATA_LOCK_FIELDS));
        assertFalse(FieldListSupport.isFieldListFeature(MdClassPackage.Literals.BASIC_DB_OBJECT__BASED_ON));
        assertFalse(FieldListSupport.isFieldListFeature(null));
        assertTrue(FieldListSupport.eligibleAddresses(MdClassFactory.eINSTANCE.createTable(),
            MdClassPackage.Literals.TABLE__INPUT_BY_STRING).isEmpty());
    }

    /**
     * missing 6: a refusal lists what IS offered through the shared bounded listing - the first
     * {@link FieldListSupport#MAX_LISTED} addresses in model order, then the rest counted - so a
     * data-lock refusal on an ERP-size document stays short.
     */
    @Test
    public void testARefusalListsALongOfferedListBoundedWithTheRestCounted()
    {
        Catalog goods = catalog("Goods"); //$NON-NLS-1$
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < FieldListSupport.MAX_LISTED + 5; i++)
        {
            derived(goods, attribute(goods, "A" + i), "Attribute", SourceType.FIELDS_FOR_LOCKING); //$NON-NLS-1$ //$NON-NLS-2$
            if (i < FieldListSupport.MAX_LISTED)
            {
                shown.append(i == 0 ? "" : ", ").append("Catalog.Goods.Attribute.A").append(i); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        String refusal = refused(goods, LOCK, array("Catalog.Goods.Attribute.Nope")); //$NON-NLS-1$
        assertTrue(refusal, refusal.endsWith(" Offered for 'dataLockFields': " + shown + ", ... (5 more).")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The modify_metadata guide quotes the three "drop it" refusals of a Current entry by fragments of
     * their real text, so a reader can match a refusal to the paragraph that explains it (the first
     * wording quoted texts the code never printed): each fragment must be in the refusal it stands for
     * AND, quoted, in the guide.
     */
    @Test
    public void testTheGuideQuotesTheCurrentEntryRefusalsByExactFragments()
    {
        String guide = new ModifyMetadataTool().getGuide();

        // EDT no longer offers it: codeLength went to 0 while Code stayed in the list
        Catalog stale = catalog("Goods"); //$NON-NLS-1$
        stale.getInputByString().add(standard(stale, "Code", "\u041A\u043E\u0434", SourceType.STANDARD_FIELDS)); //$NON-NLS-1$ //$NON-NLS-2$
        assertQuotedVerbatim(guide, refused(stale, INPUT, array("Catalog.Goods.StandardAttribute.Code")), //$NON-NLS-1$
            "is in the current list, but EDT no longer offers it"); //$NON-NLS-1$

        // EDT's validator rejects it: a task's addressing attribute
        Task job = job();
        job.getInputByString().add(job.getFields().get(0));
        assertQuotedVerbatim(guide, refused(job, INPUT, array("Task.Job.AddressingAttribute.Executor")), //$NON-NLS-1$
            "is in the current list, but EDT's validator rejects it"); //$NON-NLS-1$

        // the field is gone: the attribute behind a stored proxy was deleted
        Catalog goods = goods();
        Field gone = McoreFactory.eINSTANCE.createField();
        ((InternalEObject)gone).eSetProxyURI(
            FieldListSupport.proxyUri("engine-1", "Catalog.Goods", "Attribute:Gone")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        goods.getInputByString().add(gone);
        assertQuotedVerbatim(guide, refused(goods, INPUT, array("Catalog.Goods.Attribute.Gone")), //$NON-NLS-1$
            "no longer has that field"); //$NON-NLS-1$
    }
}
