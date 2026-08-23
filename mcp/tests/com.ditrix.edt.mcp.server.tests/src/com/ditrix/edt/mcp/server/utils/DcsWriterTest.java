/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterUse;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com.ditrix.edt.mcp.server.utils.DcsWriter.Result;
import com.ditrix.edt.mcp.server.utils.DcsWriter.TypeResolution;
import com.ditrix.edt.mcp.server.utils.DcsWriter.TypeResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests {@link DcsWriter}: the pure spec parse / enum resolution / validation (no model), and the typed DCS
 * write onto an in-memory {@link DataCompositionSchema} built with {@code DcsFactory.eINSTANCE} - a query
 * data set lands with its query text / auto-created data source / auto-fill flag, explicit fields land with
 * their data path / source field / title / role, schema parameters land with a typed value type (built via
 * an injected {@link TypeResolver}) / title / use, a calculated field lands with its expression / title and
 * is UPDATED (not duplicated) when re-applied with the same {@code dataPath}, find-or-create is idempotent,
 * and a bad enum / malformed shape is rejected before anything is written.
 */
public class DcsWriterTest
{
    private static JsonObject json(String s)
    {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static DataCompositionSchema newSchema()
    {
        return DcsFactory.eINSTANCE.createDataCompositionSchema();
    }

    /** A resolver that returns a fixed headless String(10) type - proves the writer wires a typed value. */
    private static final TypeResolver STRING10_RESOLVER = spec -> {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        StringQualifiers q = McoreFactory.eINSTANCE.createStringQualifiers();
        q.setLength(10);
        td.setStringQualifiers(q);
        return TypeResolution.of(td);
    };

    private static DataCompositionSchemaDataSetQuery firstQuery(DataCompositionSchema schema)
    {
        for (DataSet set : schema.getDataSets())
        {
            if (set instanceof DataCompositionSchemaDataSetQuery)
            {
                return (DataCompositionSchemaDataSetQuery)set;
            }
        }
        return null;
    }

    // ==================== query data set ====================

    @Test
    public void testQueryDataSetLandsWithQueryAndAutoSource()
    {
        DataCompositionSchema schema = newSchema();
        // The Cyrillic query text (SELECT Goods) must round-trip byte-for-byte (bilingual query text).
        String query = "\u0412\u042B\u0411\u0420\u0410\u0422\u042C \u0422\u043E\u0432\u0430\u0440"; //$NON-NLS-1$
        JsonObject spec = json("{\"dataSets\":[{\"name\":\"DataSet1\",\"type\":\"query\"}]}"); //$NON-NLS-1$
        spec.getAsJsonArray("dataSets").get(0).getAsJsonObject().addProperty("query", query); //$NON-NLS-1$ //$NON-NLS-2$
        Result r = DcsWriter.apply(schema, spec, null);
        assertFalse("a valid query data set must not error: " + r.error, r.hasError()); //$NON-NLS-1$
        assertEquals(1, r.dataSets);

        DataCompositionSchemaDataSetQuery dataSet = firstQuery(schema);
        assertNotNull("a query data set must be created", dataSet); //$NON-NLS-1$
        assertEquals("DataSet1", dataSet.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the Cyrillic query text must round-trip", query, dataSet.getQuery()); //$NON-NLS-1$
        // No explicit fields -> EDT derives the available fields from the query.
        assertTrue("autoFillAvailableFields must default to true with no explicit fields", //$NON-NLS-1$
            dataSet.isAutoFillAvailableFields());
        // A query data set references a data source by name; the writer auto-creates a default local one.
        assertEquals("DataSource1", dataSet.getDataSource()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, r.dataSources);
        assertEquals("DataSource1", schema.getDataSources().get(0).getName()); //$NON-NLS-1$ //$NON-NLS-2$
        // The platform-canonical local-infobase token is "Local" (capital L), as EDT's own DCS designer writes.
        assertEquals("Local", schema.getDataSources().get(0).getDataSourceType()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDeclaredDataSourceIsUsedAsDefault()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema, json("{\"dataSources\":[{\"name\":\"Src\",\"type\":\"local\"}]," //$NON-NLS-1$
            + "\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\",\"query\":\"SELECT 1\"}]}"), null); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertEquals("the declared data source becomes the query's default source", //$NON-NLS-1$
            "Src", firstQuery(schema).getDataSource()); //$NON-NLS-1$
        // Only the declared source exists - no spurious auto-created default.
        assertEquals(1, schema.getDataSources().size());
    }

    @Test
    public void testFirstNestedMemberSourceBecomesDefaultForFollowingSiblings()
    {
        DataCompositionSchema schema = newSchema();
        Result result = DcsWriter.apply(schema, json("{\"dataSets\":[{" //$NON-NLS-1$
            + "\"name\":\"AllSales\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
            + "{\"name\":\"A\",\"type\":\"query\",\"query\":\"SELECT 1\"," //$NON-NLS-1$
            + "\"dataSource\":\"MySource\"}," //$NON-NLS-1$
            + "{\"name\":\"B\",\"type\":\"query\",\"query\":\"SELECT 2\"}]}]}"), null); //$NON-NLS-1$
        assertFalse(result.error, result.hasError());

        DataCompositionSchemaDataSetUnion union = (DataCompositionSchemaDataSetUnion)
            schema.getDataSets().get(0);
        DataCompositionSchemaDataSetQuery first = (DataCompositionSchemaDataSetQuery)
            union.getItems().get(0);
        DataCompositionSchemaDataSetQuery second = (DataCompositionSchemaDataSetQuery)
            union.getItems().get(1);
        assertEquals("MySource", first.getDataSource()); //$NON-NLS-1$
        assertEquals("MySource", second.getDataSource()); //$NON-NLS-1$
        assertEquals(1, schema.getDataSources().size());
        assertEquals("MySource", schema.getDataSources().get(0).getName()); //$NON-NLS-1$
    }

    // ==================== explicit fields ====================

    @Test
    public void testExplicitFieldsLandWithPathTitleAndRole()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema, json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT Goods\",\"fields\":[" //$NON-NLS-1$
            + "{\"dataPath\":\"Goods\",\"field\":\"Goods\",\"title\":\"Goods\",\"role\":{\"dimension\":true}}," //$NON-NLS-1$
            + "{\"dataPath\":\"Amount\"}]}]}"), null); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertEquals(2, r.fields);

        DataCompositionSchemaDataSetQuery dataSet = firstQuery(schema);
        // Explicit fields -> auto-fill is turned OFF.
        assertFalse("explicit fields must turn autoFillAvailableFields off", //$NON-NLS-1$
            dataSet.isAutoFillAvailableFields());
        assertEquals(2, dataSet.getFields().size());

        DataCompositionSchemaDataSetField goods = (DataCompositionSchemaDataSetField)dataSet.getFields().get(0);
        assertEquals("Goods", goods.getDataPath()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Goods", goods.getField()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a titled field must carry a Presentation", goods.getTitle()); //$NON-NLS-1$
        assertEquals("Goods", goods.getTitle().getValue()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a role must be created", goods.getRole()); //$NON-NLS-1$
        assertTrue("the dimension role flag must be set", goods.getRole().isDimension()); //$NON-NLS-1$

        DataCompositionSchemaDataSetField amount = (DataCompositionSchemaDataSetField)dataSet.getFields().get(1);
        // 'field' defaults to 'dataPath' when omitted.
        assertEquals("field must default to the data path", "Amount", amount.getField()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("an untitled field carries no Presentation", amount.getTitle()); //$NON-NLS-1$
    }

    // ==================== parameters ====================

    @Test
    public void testParameterLandsWithTypedValueTitleAndUse()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema, json("{\"parameters\":[{\"name\":\"Period\"," //$NON-NLS-1$
            + "\"valueType\":{\"types\":[{\"kind\":\"String\"}]},\"title\":\"Period\",\"use\":\"Auto\"}]}"), //$NON-NLS-1$
            STRING10_RESOLVER);
        assertFalse(r.error, r.hasError());
        assertEquals(1, r.parameters);

        DataCompositionSchemaParameter param = schema.getParameters().get(0);
        assertEquals("Period", param.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the parameter must carry a typed value", param.getValueType()); //$NON-NLS-1$
        assertNotNull("the resolved String(10) qualifier must be present", //$NON-NLS-1$
            param.getValueType().getStringQualifiers());
        assertEquals(10, param.getValueType().getStringQualifiers().getLength());
        assertNotNull("a titled parameter must carry a Presentation", param.getTitle()); //$NON-NLS-1$
        assertEquals("Period", param.getTitle().getValue()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(DataCompositionParameterUse.AUTO, param.getUse());
    }

    @Test
    public void testParameterWithoutValueTypeNeedsNoResolver()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema,
            json("{\"parameters\":[{\"name\":\"Flag\",\"use\":\"Always\"}]}"), null); //$NON-NLS-1$
        assertFalse("a parameter with no valueType must not need a resolver: " + r.error, r.hasError()); //$NON-NLS-1$
        DataCompositionSchemaParameter param = schema.getParameters().get(0);
        assertNull("no valueType must leave the type unset", param.getValueType()); //$NON-NLS-1$
        assertEquals(DataCompositionParameterUse.ALWAYS, param.getUse());
    }

    @Test
    public void testLocalizedTitleKeyedByLanguageCode()
    {
        DataCompositionSchema schema = newSchema();
        // A bilingual title object {ru:..., en:...} lands in a LocalString keyed by language code.
        String ru = "\u041F\u0435\u0440\u0438\u043E\u0434"; // Period (ru) //$NON-NLS-1$
        JsonObject spec = json("{\"parameters\":[{\"name\":\"Period\",\"title\":{\"en\":\"Period\"}}]}"); //$NON-NLS-1$
        spec.getAsJsonArray("parameters").get(0).getAsJsonObject() //$NON-NLS-1$
            .getAsJsonObject("title").addProperty("ru", ru); //$NON-NLS-1$ //$NON-NLS-2$
        Result r = DcsWriter.apply(schema, spec, null);
        assertFalse(r.error, r.hasError());
        DataCompositionSchemaParameter param = schema.getParameters().get(0);
        assertNotNull("a localized title must carry a LocalString", param.getTitle().getLocalValue()); //$NON-NLS-1$
        assertEquals(ru, param.getTitle().getLocalValue().getContent().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Period", param.getTitle().getLocalValue().getContent().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== calculated fields ====================

    @Test
    public void testCalculatedFieldLandsWithExpressionAndTitle()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema, json("{\"calculatedFields\":[{\"dataPath\":\"Margin\"," //$NON-NLS-1$
            + "\"expression\":\"Revenue - Cost\",\"title\":\"Margin\"}]}"), null); //$NON-NLS-1$
        assertFalse("a valid calculated field must not error: " + r.error, r.hasError()); //$NON-NLS-1$
        assertEquals(1, r.calculatedFields);
        assertEquals(1, schema.getCalculatedFields().size());

        DataCompositionSchemaCalculatedField field = schema.getCalculatedFields().get(0);
        assertEquals("Margin", field.getDataPath()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Revenue - Cost", field.getExpression()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a titled calculated field must carry a Presentation", field.getTitle()); //$NON-NLS-1$
        assertEquals("Margin", field.getTitle().getValue()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCalculatedFieldWithoutTitleCarriesNoPresentation()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema,
            json("{\"calculatedFields\":[{\"dataPath\":\"Margin\",\"expression\":\"Revenue - Cost\"}]}"), //$NON-NLS-1$
            null);
        assertFalse(r.error, r.hasError());
        assertNull("an untitled calculated field carries no Presentation", //$NON-NLS-1$
            schema.getCalculatedFields().get(0).getTitle());
    }

    @Test
    public void testCalculatedFieldReapplyUpdatesExpressionInPlace()
    {
        DataCompositionSchema schema = newSchema();
        DcsWriter.apply(schema, json("{\"calculatedFields\":[{\"dataPath\":\"Margin\"," //$NON-NLS-1$
            + "\"expression\":\"Revenue - Cost\",\"title\":\"Margin\"}]}"), null); //$NON-NLS-1$
        Result r = DcsWriter.apply(schema, json("{\"calculatedFields\":[{\"dataPath\":\"Margin\"," //$NON-NLS-1$
            + "\"expression\":\"Revenue - Cost * 2\"}]}"), null); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertEquals("a re-applied calculated field must not duplicate", 1, //$NON-NLS-1$
            schema.getCalculatedFields().size());
        DataCompositionSchemaCalculatedField field = schema.getCalculatedFields().get(0);
        assertEquals("the second apply must UPDATE the expression in place", //$NON-NLS-1$
            "Revenue - Cost * 2", field.getExpression()); //$NON-NLS-1$ //$NON-NLS-2$
        // The first apply's title is left alone (the second apply's entry carries none), matching a
        // data set field's find-or-update discipline: only supplied members are overwritten.
        assertNotNull("a title set by an earlier apply must survive an update that omits it", //$NON-NLS-1$
            field.getTitle());
        assertEquals("Margin", field.getTitle().getValue()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingCalculatedFieldDataPathIsError()
    {
        Result r = DcsWriter.apply(newSchema(),
            json("{\"calculatedFields\":[{\"expression\":\"Revenue - Cost\"}]}"), null); //$NON-NLS-1$
        assertTrue("a calculated field without a dataPath must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must mention 'dataPath'", r.error.contains("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingCalculatedFieldExpressionIsError()
    {
        Result r = DcsWriter.apply(newSchema(),
            json("{\"calculatedFields\":[{\"dataPath\":\"Margin\"}]}"), null); //$NON-NLS-1$
        assertTrue("a calculated field without an expression must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must mention 'expression'", r.error.contains("expression")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCalculatedFieldsNotAnArrayIsError()
    {
        Result r = DcsWriter.apply(newSchema(), json("{\"calculatedFields\":\"nope\"}"), null); //$NON-NLS-1$
        assertTrue("calculatedFields that is not an array must error", r.hasError()); //$NON-NLS-1$
    }

    // ==================== total fields / restrictions ====================

    @Test
    public void testTotalFieldLandsWithExpressionAndGroups()
    {
        DataCompositionSchema schema = newSchema();
        Result result = DcsWriter.apply(schema, json("{\"totalFields\":[{\"dataPath\":\"Amount\"," //$NON-NLS-1$
            + "\"expression\":\"Sum(Amount)\",\"groups\":[\"Goods\",\"Warehouse\"]}]}"), null); //$NON-NLS-1$

        assertFalse(result.error, result.hasError());
        assertEquals(1, result.totalFields);
        DataCompositionSchemaTotalField total = schema.getTotalFields().get(0);
        assertEquals("Amount", total.getDataPath()); //$NON-NLS-1$
        assertEquals("Sum(Amount)", total.getExpression()); //$NON-NLS-1$
        assertEquals(2, total.getGroups().size());
        assertEquals("Warehouse", total.getGroups().get(1)); //$NON-NLS-1$
    }

    @Test
    public void testFieldUseRestrictionLandsWithAllFlags()
    {
        DataCompositionSchema schema = newSchema();
        Result result = DcsWriter.apply(schema, json("{\"dataSets\":[{\"name\":\"DS\"," //$NON-NLS-1$
            + "\"type\":\"query\",\"query\":\"SELECT 1\",\"fields\":[{\"dataPath\":\"Amount\"," //$NON-NLS-1$
            + "\"useRestriction\":{\"field\":true,\"condition\":true,\"group\":false," //$NON-NLS-1$
            + "\"order\":true}}]}]}"), null); //$NON-NLS-1$

        assertFalse(result.error, result.hasError());
        DataCompositionSchemaDataSetField field =
            (DataCompositionSchemaDataSetField)firstQuery(schema).getFields().get(0);
        assertNotNull(field.getUseRestriction());
        assertTrue(field.getUseRestriction().isField());
        assertTrue(field.getUseRestriction().isCondition());
        assertFalse(field.getUseRestriction().isGroup());
        assertTrue(field.getUseRestriction().isOrder());
    }

    @Test
    public void testLocalizedTitleUsesCanonicalLanguageCodeSpellings()
    {
        DataCompositionSchema schema = newSchema();
        String russianTitle = MetadataLanguageUtils.cp(0x0418, 0x043c, 0x044f);
        JsonObject spec = json("{\"parameters\":[{\"name\":\"Name\",\"title\":{\"EN\":\"Name\"}}]}"); //$NON-NLS-1$
        spec.getAsJsonArray("parameters").get(0).getAsJsonObject() //$NON-NLS-1$
            .getAsJsonObject("title").addProperty("RU", russianTitle); //$NON-NLS-1$ //$NON-NLS-2$
        DcsPresentationParser.LanguageContext languages =
            new DcsPresentationParser.LanguageContext(java.util.Arrays.asList("en", "ru")); //$NON-NLS-1$ //$NON-NLS-2$

        Result result = DcsWriter.apply(schema, spec, null, languages);

        assertFalse(result.error, result.hasError());
        java.util.Map<String, String> content = schema.getParameters().get(0).getTitle()
            .getLocalValue().getContent().map();
        assertEquals("Name", content.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(russianTitle, content.get("ru")); //$NON-NLS-1$
        assertFalse(content.containsKey("EN")); //$NON-NLS-1$
        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList("en", "ru")), //$NON-NLS-1$ //$NON-NLS-2$
            languages.usedCodes());
    }

    // ==================== idempotency ====================

    @Test
    public void testReapplyIsIdempotentByName()
    {
        DataCompositionSchema schema = newSchema();
        String spec = "{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\",\"query\":\"SELECT 1\"," //$NON-NLS-1$
            + "\"fields\":[{\"dataPath\":\"A\"}]}],\"parameters\":[{\"name\":\"P\"}]," //$NON-NLS-1$
            + "\"calculatedFields\":[{\"dataPath\":\"CF\",\"expression\":\"1 + 1\"}]}"; //$NON-NLS-1$
        DcsWriter.apply(schema, json(spec), null);
        DcsWriter.apply(schema, json(spec), null);
        assertEquals("a re-applied data set must not duplicate", 1, schema.getDataSets().size()); //$NON-NLS-1$
        assertEquals("a re-applied field must not duplicate", 1, firstQuery(schema).getFields().size()); //$NON-NLS-1$
        assertEquals("a re-applied parameter must not duplicate", 1, schema.getParameters().size()); //$NON-NLS-1$
        assertEquals("a re-applied data source must not duplicate", 1, schema.getDataSources().size()); //$NON-NLS-1$
        assertEquals("a re-applied calculated field must not duplicate", 1, //$NON-NLS-1$
            schema.getCalculatedFields().size());
    }

    // ==================== errors ====================

    @Test
    public void testNullSchemaIsError()
    {
        Result r = DcsWriter.apply(null,
            json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\"}]}"), null); //$NON-NLS-1$
        assertTrue("a null schema must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testEmptyPayloadIsError()
    {
        Result r = DcsWriter.apply(newSchema(), json("{}"), null); //$NON-NLS-1$
        assertTrue("an empty dcs schema body must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testMissingDataSetNameIsError()
    {
        Result r = DcsWriter.apply(newSchema(),
            json("{\"dataSets\":[{\"type\":\"query\",\"query\":\"SELECT 1\"}]}"), null); //$NON-NLS-1$
        assertTrue("a data set without a name must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must mention 'name'", r.error.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testQueryDataSetNeedsQuery()
    {
        Result r = DcsWriter.apply(newSchema(),
            json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\"}]}"), null); //$NON-NLS-1$
        assertTrue("a query data set without a query must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must point at 'query'", r.error.contains("query")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testObjectDataSetIsAuthoredWithItsObjectName()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema,
            json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"object\",\"objectName\":\"Catalog.Products\"}]}"), //$NON-NLS-1$
            null);
        assertFalse(r.hasError());
        assertEquals(1, schema.getDataSets().size());
        DataSet authored = schema.getDataSets().get(0);
        assertTrue("an 'object' data set must land as DataSetObject, not a query set", //$NON-NLS-1$
            authored instanceof DataCompositionSchemaDataSetObject);
        assertEquals("Catalog.Products", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetObject)authored).getObjectName());
    }

    @Test
    public void testUnknownDataSetTypeErrorsAndListsEveryAcceptedKind()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema,
            json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"spreadsheet\"}]}"), null); //$NON-NLS-1$
        assertTrue("an unknown data set type must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the offending token", r.error.contains("spreadsheet")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String accepted : new String[] { "query", "object", "union" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("the error must list the accepted kind '" + accepted + "'", //$NON-NLS-1$ //$NON-NLS-2$
                r.error.contains(accepted));
        }
        assertTrue("a rejected spec must not mutate the schema", schema.getDataSets().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testMissingFieldDataPathIsError()
    {
        Result r = DcsWriter.apply(newSchema(), json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT 1\",\"fields\":[{\"title\":\"X\"}]}]}"), null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a field without a dataPath must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must mention 'dataPath'", r.error.contains("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBadUseEnumIsError()
    {
        DataCompositionSchema schema = newSchema();
        Result r = DcsWriter.apply(schema,
            json("{\"parameters\":[{\"name\":\"P\",\"use\":\"Sometimes\"}]}"), null); //$NON-NLS-1$
        assertTrue("a bad use token must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the offending token", r.error.contains("Sometimes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must list the valid tokens", r.error.contains("AUTO")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing must be written on a validation error", schema.getParameters().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testBadRolePeriodTypeIsError()
    {
        Result r = DcsWriter.apply(newSchema(), json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT 1\",\"fields\":[{\"dataPath\":\"A\",\"role\":{\"periodType\":\"Weekly\"}}]}]}"), //$NON-NLS-1$ //$NON-NLS-2$
            null);
        assertTrue("a bad periodType token must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the offending token", r.error.contains("Weekly")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyRoleIsError()
    {
        Result r = DcsWriter.apply(newSchema(), json("{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT 1\",\"fields\":[{\"dataPath\":\"A\",\"role\":{}}]}]}"), null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a role with no recognized flag must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testValueTypeWithoutResolverIsError()
    {
        Result r = DcsWriter.apply(newSchema(), json("{\"parameters\":[{\"name\":\"P\"," //$NON-NLS-1$
            + "\"valueType\":{\"types\":[{\"kind\":\"String\"}]}}]}"), null); //$NON-NLS-1$
        assertTrue("a valueType with no resolver must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testResolverErrorIsSurfaced()
    {
        TypeResolver failing = spec -> TypeResolution.failed("bad type spec"); //$NON-NLS-1$
        Result r = DcsWriter.apply(newSchema(), json("{\"parameters\":[{\"name\":\"P\"," //$NON-NLS-1$
            + "\"valueType\":{\"types\":[{\"kind\":\"Nope\"}]}}]}"), failing); //$NON-NLS-1$
        assertTrue("a resolver error must fail the apply", r.hasError()); //$NON-NLS-1$
        assertTrue("the resolver's message must be surfaced", r.error.contains("bad type spec")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonObjectValueTypeIsShapeError()
    {
        Result r = DcsWriter.apply(newSchema(),
            json("{\"parameters\":[{\"name\":\"P\",\"valueType\":\"String\"}]}"), STRING10_RESOLVER); //$NON-NLS-1$
        assertTrue("a non-object valueType must be a shape error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testDataSetsNotAnArrayIsError()
    {
        Result r = DcsWriter.apply(newSchema(), json("{\"dataSets\":\"nope\"}"), null); //$NON-NLS-1$
        assertTrue("dataSets that is not an array must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testUnknownMemberRejectsWholePayloadBeforeMutation()
    {
        DataCompositionSchema schema = newSchema();
        Result result = DcsWriter.apply(schema, json("{\"dataSets\":[{\"name\":\"DS\"," //$NON-NLS-1$
            + "\"type\":\"query\",\"query\":\"SELECT 1\"}],\"typo\":true}"), null); //$NON-NLS-1$

        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("typo")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Accepted members")); //$NON-NLS-1$
        assertTrue("the valid first section must not be applied", schema.getDataSets().isEmpty()); //$NON-NLS-1$
        assertTrue("its auto-created source must not leak either", schema.getDataSources().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testNonQueryNaturalKeyCollisionIsRefusedWithoutDuplicate()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetObject objectSet =
            DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetObject();
        objectSet.setName("DS"); //$NON-NLS-1$
        schema.getDataSets().add(objectSet);

        Result result = DcsWriter.apply(schema, json("{\"dataSets\":[{\"name\":\"DS\"," //$NON-NLS-1$
            + "\"type\":\"query\",\"query\":\"SELECT 1\"}]}"), null); //$NON-NLS-1$

        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("DS")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("object")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Rename")); //$NON-NLS-1$
        assertEquals("the clashing subtype must remain the only data set", 1, //$NON-NLS-1$
            schema.getDataSets().size());
        assertTrue(schema.getDataSets().get(0) instanceof DataCompositionSchemaDataSetObject);
        assertTrue(schema.getDataSources().isEmpty());
    }

    @Test
    public void testSharedTypeResolverReportsMissingVersionBeforeBuilding()
    {
        TypeResolution result = DcsWriter.typeResolver(null, null)
            .resolve(json("{\"types\":[{\"kind\":\"String\"}]}")); //$NON-NLS-1$

        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("platform version")); //$NON-NLS-1$
        assertNull(result.typeDescription);
    }

    // ==================== pure parse (no model) ====================

    @Test
    public void testParseResolvesUseCaseInsensitively()
    {
        DcsWriter.ParseResult parsed =
            DcsWriter.parse(json("{\"parameters\":[{\"name\":\"P\",\"use\":\"aUtO\"}]}")); //$NON-NLS-1$
        assertNull("a valid spec must parse: " + parsed.error, parsed.error); //$NON-NLS-1$
        assertNotNull(parsed.plan);
        assertEquals(1, parsed.plan.parameters.size());
        assertEquals(DataCompositionParameterUse.AUTO, parsed.plan.parameters.get(0).use);
    }

    @Test
    public void testParseRejectsBadEnumWithoutModel()
    {
        DcsWriter.ParseResult parsed =
            DcsWriter.parse(json("{\"parameters\":[{\"name\":\"P\",\"use\":\"maybe\"}]}")); //$NON-NLS-1$
        assertNotNull("a bad use token must fail the pure parse", parsed.error); //$NON-NLS-1$
        assertNull(parsed.plan);
    }
}
