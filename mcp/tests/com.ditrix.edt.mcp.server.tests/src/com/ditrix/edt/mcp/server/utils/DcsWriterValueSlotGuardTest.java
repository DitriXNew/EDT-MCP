/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldCase;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Writer-side value sinks must all receive the same lossless dry run during replace. */
public class DcsWriterValueSlotGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final String CASE = "#/defaultSettings/userFields/items/0";
    private static final String CASE_VALUE = CASE + "/variants/items/0/value";
    private static final String BAD_PRECISION = "12345678901234567890.1234567890123456789";
    private static final com._1c.g5.v8.dt.dcs.model.schema.DcsFactory SCHEMA =
        com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.core.DcsFactory CORE =
        com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE;

    @Test
    public void testXmlCaseVariantWithExcessPrecisionBlocksUserFieldReplace() throws Exception
    {
        assertInvalidCase(BAD_PRECISION, CASE);
    }

    @Test
    public void testXmlCaseVariantWithExcessScaleBlocksEnclosingSettingsReplace() throws Exception
    {
        assertInvalidCase("1e-39", "#/defaultSettings");
    }

    @Test
    public void testValidXmlCaseVariantPassesEveryEnclosingReplace() throws Exception
    {
        var schema = caseSchema("0.12345678901234567890123456789012345678");
        for (String target : List.of(CASE, "#/defaultSettings", ""))
            assertNull(guard(schema, target));
        assertEquals(new BigDecimal("0.12345678901234567890123456789012345678"),
            ((NumberValue)caseValue(schema)).getValue());
    }

    @Test
    public void testSchemaParameterDefaultsUseTheDeclaredWriter()
    {
        var schema = declaredSchema("values:[{kind:'number',value:1}]");
        var parameter = schema.getParameters().get(0);
        assertNull(guard(schema, ""));
        var stored = (NumberValue)parameter.getValues().get(0);
        stored.setValue(new BigDecimal(BAD_PRECISION));
        assertNumberRefused(schema, "#/parameters/P/values/0", stored);
    }

    @Test
    public void testParameterAvailableValuesUseTheDeclaredWriter()
    {
        assertAvailableValue(SCHEMA.createDataCompositionSchemaParameter());
    }

    @Test
    public void testDataSetFieldAvailableValuesUseTheDeclaredWriter()
    {
        assertAvailableValue(SCHEMA.createDataCompositionSchemaDataSetField());
    }

    @Test
    public void testCalculatedFieldAvailableValuesUseTheDeclaredWriter()
    {
        assertAvailableValue(SCHEMA.createDataCompositionSchemaCalculatedField());
    }

    @Test
    public void testStoredSchemaDefaultOfAnotherDeclaredTypeIsRefused()
    {
        var schema = declaredSchema("values:[{kind:'number',value:1}]");
        var parameter = schema.getParameters().get(0);
        parameter.setValueType(stringType());
        assertDeclaredMismatch(schema, "#/parameters/P/values/0");
        // The writer also refuses this body even though the Number itself is supported.
        var result = DcsWriter.apply(schema, json("{parameters:[{name:'P',values:[{kind:'number',value:1}]}]}"));
        assertTrue(result.error, result.hasError());
        assertTrue(result.error, result.error.contains("cannot be mapped to the declared valueType"));
        assertEquals(BigDecimal.ONE, ((NumberValue)parameter.getValues().get(0)).getValue());
    }

    @Test
    public void testGroupFieldReferenceUsesTheFieldWriter()
    {
        assertField(DcsFactory.eINSTANCE.createDataCompositionGroupField());
    }

    @Test
    public void testSelectedFieldReferenceUsesTheFieldWriter()
    {
        assertField(DcsFactory.eINSTANCE.createDataCompositionSelectedField());
    }

    @Test
    public void testSelectedFieldGroupReferenceUsesTheFieldWriter()
    {
        assertField(DcsFactory.eINSTANCE.createDataCompositionSelectedFieldGroup());
    }

    @Test
    public void testOrderFieldReferenceUsesTheFieldWriter()
    {
        assertField(DcsFactory.eINSTANCE.createDataCompositionOrderItem());
    }

    @Test
    public void testAppearanceFieldReferenceUsesTheFieldWriter()
    {
        assertField(DcsFactory.eINSTANCE.createDataCompositionAppearanceField());
    }

    @Test
    public void testPeriodBeginCannotRestateAStoredNullValue()
    {
        assertNullBound("periodAdditionBegin");
    }

    @Test
    public void testPeriodEndCannotRestateAStoredNullValue()
    {
        assertNullBound("periodAdditionEnd");
    }

    @Test
    public void testPresentationLocalValueUsesItsOwnParserAndPreservesLanguageContext()
    {
        var languages = new DcsPresentationParser.LanguageContext(List.of("en"));
        var parsed = DcsPresentationParser.parse(json("{en:'Sales'}"), languages.dryRun(), "title");
        assertTrue(parsed.error(), parsed.isSuccess());
        var presentation = DcsPresentationParser.build(parsed.plan());
        var context = new DcsWriterCatalogues(DcsStoredXmlTestSupport.VERSION,
            DcsSettingsWriter.AppearanceCatalogue.SCHEMA, "en", languages,
            (kind, version, language) -> DcsReplaceTestCatalogues.available("Known", "Alias", null));
        assertTrue(DcsReadProjection.unmodellableNodes(presentation, ROOT, context).isEmpty());
        LocalString stored = presentation.getLocalValue();
        stored.setFormattedString(true);
        var snapshot = EcoreUtil.copy(presentation);
        var refused = DcsReadProjection.unmodellableNodes(presentation, ROOT, context);
        assertEquals("formattedString has no presentation body", 1, refused.size());
        assertEquals(ROOT + "#/localValue", refused.get(0).address());
        assertTrue(refused.get(0).reason(), refused.get(0).reason().contains("different LocalString"));
        assertTrue(EcoreUtil.equals(snapshot, presentation));
        assertEquals("the dry run must not record a language", java.util.Set.of(), languages.usedCodes());
    }

    @Test
    public void testPresentationWithAnUndeclaredLanguageIsRefusedByItsParser()
    {
        var presentation = CORE.createPresentation();
        var stored = CORE.createLocalString();
        stored.getContent().put("zz", "Sales");
        presentation.setLocalValue(stored);
        var context = new DcsWriterCatalogues(DcsStoredXmlTestSupport.VERSION,
            DcsSettingsWriter.AppearanceCatalogue.SCHEMA,
            "en", new DcsPresentationParser.LanguageContext(List.of("en")),
            (kind, version, language) -> DcsReplaceTestCatalogues.available("Known", "Alias", null));
        var refused = DcsReadProjection.unmodellableNodes(presentation, ROOT, context);
        assertEquals(1, refused.size());
        assertTrue(refused.get(0).reason(), refused.get(0).reason().contains("Unknown language 'zz'"));
    }

    @Test
    public void testAnAvailableValueEntryNeedsItsRequiredValue()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var parameter = SCHEMA.createDataCompositionSchemaParameter();
        parameter.setName("P");
        parameter.getAvailableValues().add(SCHEMA.createAvailableValue());
        schema.getParameters().add(parameter);
        String refused = guard(schema, "");
        assertNotNull("an available-value body requires a ValueSpec", refused);
        assertTrue(refused, refused.contains("AvailableValue at " + ROOT + "#/parameters/P/availableValues/0"));
        assertTrue(refused, refused.contains("the stored value is null"));
        var written = DcsWriter.apply(schema,
            json("{parameters:[{name:'P',availableValues:[{}]}]}"));
        assertTrue(written.error, written.hasError());
        assertTrue(written.error, written.error.contains("needs a 'value' ValueSpec"));
        assertNull(parameter.getAvailableValues().get(0).getValue());
    }

    @Test
    public void testAnAdditionalPropertyEntryNeedsItsRequiredValue()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var properties = McoreFactory.eINSTANCE.createStructure();
        var property = McoreFactory.eINSTANCE.createStructureProperty();
        property.setName("Empty");
        properties.getProperty().add(property);
        schema.getDefaultSettings().setAdditionalProperties(properties);
        String refused = guard(schema, "");
        assertNotNull("an additional-property entry requires a ValueSpec", refused);
        assertTrue(refused, refused.contains("StructureProperty at " + ROOT
            + "#/defaultSettings/additionalProperties/property/0"));
        assertTrue(refused, refused.contains("the stored value is null"));
        var written = DcsSettingsWriter.planSettings(schema.getDefaultSettings(), List.of(),
            "upsert", "userSettings", json("{additionalProperties:{Empty:null}}"), null);
        assertFalse(written.error(), written.isSuccess());
        assertTrue(written.error(), written.error().contains("ValueSpec"));
        assertNull(property.getValue());
    }

    @Test
    public void testAnUnregisteredValueSlotFailsClosed()
    {
        var pack = EcoreFactory.eINSTANCE.createEPackage();
        pack.setName("futureDcs");
        pack.setNsPrefix("futureDcs");
        pack.setNsURI("urn:test:futureDcs");
        var ownerType = EcoreFactory.eINSTANCE.createEClass();
        ownerType.setName("FutureDcsWriterNode");
        pack.getEClassifiers().add(ownerType);
        var slot = EcoreFactory.eINSTANCE.createEReference();
        slot.setName("futureValue");
        slot.setContainment(true);
        slot.setEType(McorePackage.Literals.VALUE);
        ownerType.getEStructuralFeatures().add(slot);
        var owner = pack.getEFactoryInstance().create(ownerType);
        var stored = McoreFactory.eINSTANCE.createNumberValue();
        stored.setValue(BigDecimal.ONE);
        owner.eSet(slot, stored);
        String refused = guard(owner, "");
        assertNotNull("new writer slots require an explicit guard registration", refused);
        assertTrue(refused, refused.contains("no registered writer"));
        assertTrue(refused, refused.contains("#/futureValue"));
    }

    private static void assertInvalidCase(String literal, String target) throws Exception
    {
        var schema = caseSchema(literal);
        var stored = (NumberValue)caseValue(schema);
        var snapshot = EcoreUtil.copy(schema);
        String refused = guard(schema, target);
        assertNotNull("an XML-loaded case variant cannot be lost by " + target, refused);
        assertTrue(refused, refused.contains("NumberValue at " + ROOT + CASE_VALUE));
        assertTrue(refused, refused.contains("cannot be stored as a 1C Number"));
        assertTrue(EcoreUtil.equals(snapshot, schema));
        assertSame(stored, caseValue(schema));
        assertNull("a sibling replace does not reach this value", guard(schema, "#/defaultSettings/selection"));
    }

    private static DataCompositionSchema caseSchema(String literal) throws Exception
    {
        var result = DcsSettingsWriter.planSettings(null, List.of(), "upsert", "userSettings",
            json("{userFields:{items:[{kind:'case',dataPath:'Choice',variants:{items:["
                + "{value:{kind:'number',value:1}}]}}]}}"), null, DcsStoredXmlTestSupport.VERSION);
        assertTrue(result.error(), result.isSuccess());
        var schema = DcsStoredXmlTestSupport.schema();
        schema.setDefaultSettings(result.settings());
        ((NumberValue)caseValue(schema)).setValue(new BigDecimal(literal));
        return DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(schema));
    }

    private static EObject caseValue(DataCompositionSchema schema)
    {
        return ((DataCompositionUserFieldCase)schema.getDefaultSettings().getUserFields().getItems().get(0))
            .getVariants().getItems().get(0).getValue();
    }

    private static DataCompositionSchema declaredSchema(String member)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var parameter = SCHEMA.createDataCompositionSchemaParameter();
        parameter.setName("P");
        var type = McoreFactory.eINSTANCE.createTypeDescription();
        type.setNumberQualifiers(McoreFactory.eINSTANCE.createNumberQualifiers());
        parameter.setValueType(type);
        schema.getParameters().add(parameter);
        var result = DcsWriter.apply(schema, json("{parameters:[{name:'P'," + member + "}]}"));
        assertFalse(result.error, result.hasError());
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static void assertAvailableValue(EObject owner)
    {
        var type = McoreFactory.eINSTANCE.createTypeDescription();
        type.setNumberQualifiers(McoreFactory.eINSTANCE.createNumberQualifiers());
        owner.eSet(owner.eClass().getEStructuralFeature("valueType"), type);
        var available = SCHEMA.createAvailableValue();
        var stored = McoreFactory.eINSTANCE.createNumberValue();
        stored.setValue(BigDecimal.ONE);
        available.setValue(stored);
        ((List<EObject>)owner.eGet(owner.eClass().getEStructuralFeature("availableValues"))).add(available);
        assertNull(guard(owner, ""));
        stored.setValue(new BigDecimal("1e-39"));
        assertNumberRefused(owner, "#/availableValues/0/value", stored);
        stored.setValue(BigDecimal.ONE);
        owner.eSet(owner.eClass().getEStructuralFeature("valueType"), stringType());
        assertDeclaredMismatch(owner, "#/availableValues/0/value");
        owner.eSet(owner.eClass().getEStructuralFeature("valueType"), null);
        assertNull("without valueType the writer uses buildValue", guard(owner, ""));
    }

    private static com._1c.g5.v8.dt.mcore.TypeDescription stringType()
    {
        var type = McoreFactory.eINSTANCE.createTypeDescription();
        type.setStringQualifiers(McoreFactory.eINSTANCE.createStringQualifiers());
        return type;
    }

    private static void assertNumberRefused(EObject root, String address, NumberValue stored)
    {
        String refused = guard(root, "");
        assertNotNull("stored Number must be checked at " + address, refused);
        assertTrue(refused, refused.contains("NumberValue at " + ROOT + address));
        assertTrue(refused, refused.contains("cannot be stored as a 1C Number"));
        assertEquals(new BigDecimal(stored.getValue().toString()), stored.getValue());
    }

    private static void assertDeclaredMismatch(EObject root, String address)
    {
        String refused = guard(root, "");
        assertNotNull("the schema's declared writer must be used", refused);
        assertTrue(refused, refused.contains(ROOT + address));
        assertTrue(refused, refused.contains("cannot be mapped to the declared valueType"));
    }

    private static void assertField(EObject owner)
    {
        DataCompositionField stored = CORE.createDataCompositionField();
        stored.setValue("Amount");
        owner.eSet(owner.eClass().getEStructuralFeature("field"), stored);
        assertNull(guard(owner, ""));
        stored.setValue("");
        String refused = guard(owner, "");
        assertNotNull(owner.eClass().getName() + ".field needs a field-writer dry run", refused);
        assertTrue(refused, refused.contains("DataCompositionField at " + ROOT + "#/field"));
        assertTrue(refused, refused.contains("needs a non-empty string"));
        assertSame(stored, owner.eGet(owner.eClass().getEStructuralFeature("field")));
    }

    private static void assertNullBound(String member)
    {
        DataCompositionGroupField field = DcsFactory.eINSTANCE.createDataCompositionGroupField();
        field.eSet(field.eClass().getEStructuralFeature(member), McoreFactory.eINSTANCE.createNullValue());
        assertNull("the generic value parser alone accepts NullValue",
            DcsSettingsWriter.untypedValueRefusal((com._1c.g5.v8.dt.mcore.Value)
                field.eGet(field.eClass().getEStructuralFeature(member))));
        String refused = guard(field, "");
        assertNotNull("the period writer clears this slot instead of rebuilding NullValue", refused);
        assertTrue(refused, refused.contains("NullValue at " + ROOT + "#/" + member));
        assertTrue(refused, refused.contains("different NullValue"));
    }

    private static String guard(EObject root, String pointer)
    {
        return DcsMutationGuard.replaceError(root, DcsAddress.parse(ROOT + pointer).address(),
            DcsReplaceTestCatalogues.schema());
    }

    private static JsonObject json(String source)
    {
        return JsonParser.parseString(source).getAsJsonObject();
    }
}
