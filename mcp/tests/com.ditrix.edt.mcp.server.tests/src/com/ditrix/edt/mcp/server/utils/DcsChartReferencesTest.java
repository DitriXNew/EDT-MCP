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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaFieldUseRestriction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChart;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettingsItemState;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldExpression;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.dcs.util.DcsTerms;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link DcsChartReferences}: a chart's points and series must group by schema data and
 * its measures must be resources, in either language, and a write may not leave more broken
 * chart references of any kind than the schema had before it.
 */
public class DcsChartReferencesTest
{
    private static final DcsPresentationParser.LanguageContext LANGUAGES =
        new DcsPresentationParser.LanguageContext(Arrays.asList("en", "uk")); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String ROOT = "Report.Sales"; //$NON-NLS-1$
    private static final String SETTINGS = ROOT + "#/defaultSettings"; //$NON-NLS-1$

    /** Real 1C data paths: Nomenklatura (product) and Vyruchka (revenue). */
    private static final String PRODUCT = "Номенклатура"; //$NON-NLS-1$
    private static final String REVENUE = "Выручка"; //$NON-NLS-1$
    /** Pole1, a user field name. */
    private static final String FIELD1 = "Поле1"; //$NON-NLS-1$
    /** Summa and Massiv, the Russian spellings of the Sum and Array aggregates. */
    private static final String SUM_RU = "Сумма"; //$NON-NLS-1$
    private static final String ARRAY_RU = "Массив"; //$NON-NLS-1$

    @Test
    public void testChartOnSchemaFieldsAndResourcesIsAccepted()
    {
        assertNull(judge(schema(), settings(chart(points("Customer"), series("Period"), //$NON-NLS-1$ //$NON-NLS-2$
            measures("Amount"))))); //$NON-NLS-1$

        assertNull("Russian data paths and a different case resolve like the schema's own", //$NON-NLS-1$
            judge(schema(), settings(chart(points(PRODUCT), series("period"), //$NON-NLS-1$
                measures(REVENUE, "amount"))))); //$NON-NLS-1$
    }

    @Test
    public void testMeasureThatIsNotAResourceIsRefusedWithTheResourcesToChooseFrom()
    {
        String error = judge(schema(),
            settings(chart(points("Customer"), "", measures("Customer", "Missing")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNotNull(error);
        assertTrue(error, error.startsWith("Chart at '" + SETTINGS + "/items/0' refers to data")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.contains("measure 'Customer' at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/selection/items/0': 'Customer' is a field but not a resource.")); //$NON-NLS-1$
        assertTrue(error, error.contains("measure 'Missing' at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/selection/items/1': no resource has this data path.")); //$NON-NLS-1$
        assertTrue(error, error.contains("A measure must be a resource: Amount, " + REVENUE //$NON-NLS-1$
            + ", AverageCheck,")); //$NON-NLS-1$
        assertTrue(error, error.contains("kind='auto'")); //$NON-NLS-1$
        assertTrue(error, error.contains("totalFields")); //$NON-NLS-1$
        assertFalse("no group problem, so no group guidance", error.contains("A point or series")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("all of them are new, so none is called pre-existing", //$NON-NLS-1$
            error.contains("already there")); //$NON-NLS-1$
        assertTrue(error, error.endsWith("Nothing was written.")); //$NON-NLS-1$
    }

    @Test
    public void testPointOrSeriesThatIsAResourceOrMissingIsRefused()
    {
        assertNull("a resource that is also a data-set field groups, as in the platform", //$NON-NLS-1$
            judge(schema(), settings(chart(points("Amount"), "", measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String resource = judge(schema(),
            settings(chart(points("AverageCheck"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(resource);
        assertTrue(resource, resource.contains("point 'AverageCheck' at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/points/0/groupFields/items/0': 'AverageCheck' is a resource; a resource cannot be")); //$NON-NLS-1$
        assertTrue(resource, resource.contains("A point or series groups by a schema field")); //$NON-NLS-1$
        assertTrue(resource, resource.contains("Customer")); //$NON-NLS-1$
        assertFalse("a pure resource is not offered as a grouping choice", //$NON-NLS-1$
            resource.substring(resource.indexOf("A point or series")).contains("AverageCheck")); //$NON-NLS-1$ //$NON-NLS-2$

        String attribute = judge(schema(),
            settings(chart(points("AverageCheck.Currency"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(attribute);
        assertTrue(attribute, attribute.contains("a resource's attributes cannot be grouped")); //$NON-NLS-1$

        String missing = judge(schema(),
            settings(chart(points("Customer"), series("Nowhere"), measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(missing);
        assertTrue(missing, missing.contains("series 'Nowhere' at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/series/0/groupFields/items/0': no schema field, calculated field or user field")); //$NON-NLS-1$
    }

    @Test
    public void testDottedAttributesAndCalculatedFieldsFollowTheSchemaUseRestrictions()
    {
        assertNull("an attribute of a groupable field is accepted", //$NON-NLS-1$
            judge(schema(), settings(chart(points("Customer.Region"), "", measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("a calculated field and its attributes group", //$NON-NLS-1$
            judge(schema(), settings(chart(points("Margin"), series("Margin.Level"), measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String restricted = judge(schema(),
            settings(chart(points("Restricted"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(restricted);
        assertTrue(restricted, restricted.contains("'Restricted' is not available for grouping")); //$NON-NLS-1$
        assertFalse("a restricted field is not offered as a grouping choice", //$NON-NLS-1$
            restricted.substring(restricted.indexOf("A point or series")).contains("Restricted")); //$NON-NLS-1$ //$NON-NLS-2$

        String attributes = judge(schema(),
            settings(chart(points("Locked.Owner"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(attributes);
        assertTrue(attributes, attributes.contains("the attributes of 'Locked' are not available")); //$NON-NLS-1$
        assertNull("the field itself stays groupable", //$NON-NLS-1$
            judge(schema(), settings(chart(points("Locked"), "", measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * EDT lists percent fields only under a numeric resource of {@code totalFields}; a user field
     * never gets them, however it aggregates.
     */
    @Test
    public void testPercentMeasuresBelongToTotalFieldsResourcesOnly()
    {
        assertNull(judge(schema(), settings(chart(points("Customer"), "", measures( //$NON-NLS-1$ //$NON-NLS-2$
            "Amount." + DcsTerms.kDCSSystemFieldsOverallPercent[0], //$NON-NLS-1$
            REVENUE + "." + DcsTerms.kDCSSystemFieldsGroupPercent[1]))))); //$NON-NLS-1$

        String field = judge(schema(), settings(chart(points("Customer"), "", //$NON-NLS-1$ //$NON-NLS-2$
            measures("Customer." + DcsTerms.kDCSSystemFieldsOverallPercent[0])))); //$NON-NLS-1$
        assertNotNull("a field's percent is not a measure", field); //$NON-NLS-1$
        assertTrue(field, field.contains("no resource has this data path")); //$NON-NLS-1$

        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        String userPercent = judge(schema(), plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Margin\",\"totalExpression\":\"Sum(Amount)\"}]}," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"items\":[" + chart(points("Customer"), "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                measures(en + ".Margin." + DcsTerms.kDCSSystemFieldsOverallPercent[0])) + "]}"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a user-field resource has no percent fields", userPercent); //$NON-NLS-1$
        assertTrue(userPercent, userPercent.contains("percent fields belong to totalFields resources; " //$NON-NLS-1$
            + "user field '" + en + ".Margin' has none")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(userPercent, userPercent.contains("a totalFields resource's percent field")); //$NON-NLS-1$
    }

    @Test
    public void testUserFieldsResolveWithEitherFolderSpellingAndMeasureOnlyWithAnAggregate()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        String ru = DcsTerms.kDCSSUserFieldsTerm[1];
        JsonObject body = json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Margin\",\"totalExpression\":\"Sum(Amount)\"}," //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + ru + "." + FIELD1 + "\",\"detailExpression\":\"" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + SUM_RU + "(Amount)\"}," //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Tags\",\"detailExpression\":\"" //$NON-NLS-1$ //$NON-NLS-2$
            + ARRAY_RU + " (Customer)\"}," //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Label\",\"detailExpression\":\"Customer\"}]}," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"items\":[" + chart(points(en + ".Label"), "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                measures(ru + ".Margin", en + "." + FIELD1, en + ".Tags")) + "]}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull("the folder term resolves in either language, and any platform aggregate " //$NON-NLS-1$
            + "makes a resource", judge(schema(), plan(body))); //$NON-NLS-1$

        JsonObject detailOnly = body.deepCopy();
        detailOnly.getAsJsonArray("items").set(0, JsonParser.parseString( //$NON-NLS-1$
            chart(points("Customer"), "", measures(en + ".Label")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String error = judge(schema(), plan(detailOnly));
        assertNotNull(error);
        assertTrue(error, error.contains("user field '" + en + ".Label' has no total expression " //$NON-NLS-1$ //$NON-NLS-2$
            + "and no aggregate in its expression")); //$NON-NLS-1$
        assertTrue("resource user fields are offered as measures", //$NON-NLS-1$
            error.contains(en + ".Margin") && error.contains(ru + "." + FIELD1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An aggregate spelled inside a string literal is text, not an aggregate. */
    @Test
    public void testAnAggregateInsideAStringLiteralDoesNotMakeAResource()
    {
        assertTrue(DcsChartReferences.aggregates("Sum (Amount)")); //$NON-NLS-1$
        assertTrue(DcsChartReferences.aggregates("\"x\" + " + SUM_RU + "(Amount)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(DcsChartReferences.aggregates("JoinStrings(Customer)")); //$NON-NLS-1$
        assertFalse(DcsChartReferences.aggregates("\"Sum(Amount)\"")); //$NON-NLS-1$
        assertFalse(DcsChartReferences.aggregates("\"say \"\"Sum(\"\" \" + Amount")); //$NON-NLS-1$
        assertFalse("an aggregate name must be the whole token", //$NON-NLS-1$
            DcsChartReferences.aggregates("Summary(Amount) + Amount.Sum(1)")); //$NON-NLS-1$
        assertFalse(DcsChartReferences.aggregates(null));

        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        String quoted = judge(schema(), plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Text\",\"detailExpression\":\"\\\"Sum(Amount)\\\"\"}]}," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"items\":[" + chart(points("Customer"), "", measures(en + ".Text")) + "]}"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNotNull("a quoted aggregate does not make the user field a resource", quoted); //$NON-NLS-1$
        assertTrue(quoted, quoted.contains("user field '" + en + ".Text' has no total expression")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** EDT lists a user field as a leaf, so only the user field itself groups, even with auto-fill. */
    @Test
    public void testAUserFieldGroupsAsItselfButHasNoAttributes()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        String ru = DcsTerms.kDCSSUserFieldsTerm[1];
        String userFields = "{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Label\",\"detailExpression\":\"Customer\"}]},"; //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(judge(schema(), plan(json(userFields + "\"items\":[" //$NON-NLS-1$
            + chart(points(ru + ".label"), "", measures("Amount")) + "]}")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        DataCompositionSchema autoFill = schema();
        ((DataCompositionSchemaDataSetQuery)autoFill.getDataSets().get(0)).setAutoFillAvailableFields(true);
        String attribute = judge(autoFill, plan(json(userFields + "\"items\":[" //$NON-NLS-1$
            + chart(points(ru + ".Label.Region"), "", measures("Amount")) + "]}"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull("an attribute of a user field", attribute); //$NON-NLS-1$
        assertTrue(attribute, attribute.contains("point '" + ru + ".Label.Region' at '" + SETTINGS //$NON-NLS-1$ //$NON-NLS-2$
            + "/items/0/points/0/groupFields/items/0': user field '" + en + ".Label' has no attributes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(attribute, attribute.contains("an attribute of a schema or calculated field")); //$NON-NLS-1$

        String unknown = judge(autoFill, plan(json("{\"items\":[" //$NON-NLS-1$
            + chart(points(en + ".Nobody"), "", measures("Amount")) + "]}"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull("a missing user field", unknown); //$NON-NLS-1$
        assertTrue(unknown, unknown.contains("no user field of these settings has this data path")); //$NON-NLS-1$
    }

    @Test
    public void testACaseUserFieldIsAnUnprovableMeasure()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        DataCompositionSettings settings = plan(json("{\"items\":[" //$NON-NLS-1$
            + chart(points("Customer"), "", measures(en + ".Band")) + "]}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        settings.setUserFields(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionUserFields());
        com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldCase band =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createDataCompositionUserFieldCase();
        band.setDataPath(en + ".Band"); //$NON-NLS-1$
        settings.getUserFields().getItems().add(band);
        assertNull("whether a case field is a resource depends on its variants' filters", //$NON-NLS-1$
            judge(schema(), settings));
    }

    @Test
    public void testAutoMeasureNeedsAResourceAndAChartNeedsAMeasure()
    {
        assertNull(judge(schema(), settings(chart(points("Customer"), "", AUTO)))); //$NON-NLS-1$ //$NON-NLS-2$

        DataCompositionSchema noResources = schema();
        noResources.getTotalFields().clear();
        String auto = judge(noResources, settings(chart(points("Customer"), "", AUTO))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(auto);
        assertTrue(auto, auto.contains("measure at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/selection/items/0': the automatic measure expands to the schema's resources")); //$NON-NLS-1$
        assertTrue(auto, auto.contains("A measure must be a resource: (none in this schema)")); //$NON-NLS-1$

        String none = judge(schema(), settings(chart(points("Customer"), "", ""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(none);
        assertTrue(none, none.contains("measure at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/selection': the chart has no measure, so it has nothing to draw")); //$NON-NLS-1$
    }

    /**
     * An auto-fill query set derives fields it does not list, from its select list, its DCS
     * brace clauses and the virtual tables it reads, so no unlisted name can be proven missing.
     */
    @Test
    public void testAnAutoFillQueryMakesUnlistedGroupFieldsUnprovable()
    {
        DataCompositionSchema schema = schema();
        DataCompositionSchemaDataSetQuery derived = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        derived.setName("Derived"); //$NON-NLS-1$
        derived.setQuery("SELECT Goods.Brand AS Brand FROM Catalog.Goods AS Goods"); //$NON-NLS-1$
        derived.setAutoFillAvailableFields(false);
        DataCompositionSchemaDataSetUnion union = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetUnion();
        union.setName("Union"); //$NON-NLS-1$
        union.getItems().add(derived);
        schema.getDataSets().add(union);

        assertNotNull("without auto-fill only listed fields exist", //$NON-NLS-1$
            judge(schema, settings(chart(points("Brand"), "", measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull("without auto-fill a pure resource does not group", //$NON-NLS-1$
            judge(schema, settings(chart(points("AverageCheck"), "", measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        derived.setAutoFillAvailableFields(true);
        assertNull("an auto-fill set nested in a union still derives fields", //$NON-NLS-1$
            judge(schema, settings(chart(points("Brand"), series("Company.Country"), measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("the set may derive a field under a resource's name", //$NON-NLS-1$
            judge(schema, settings(chart(points("AverageCheck"), "", measures("Amount"))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String attribute = judge(schema, settings(chart(points("AverageCheck.Currency"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("a resource's attributes stay ungroupable", attribute); //$NON-NLS-1$
        assertTrue(attribute, attribute.contains("a resource's attributes cannot be grouped")); //$NON-NLS-1$
        String restricted = judge(schema, settings(chart(points("Restricted"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("a listed field keeps its use restriction", restricted); //$NON-NLS-1$
        String measure = judge(schema, settings(chart(points("Brand"), "", measures("Brand")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("a measure is still judged: auto-fill never makes a resource", measure); //$NON-NLS-1$
        assertTrue(measure, measure.contains("measure 'Brand'")); //$NON-NLS-1$
    }

    /** A broken reference the schema already had never blocks an edit that adds no new one. */
    @Test
    public void testABrokenReferenceAlreadyThereNeverBlocksAnUnrelatedEdit()
    {
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(settings(chart(points("Customer"), "", measures("Customer")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals(1, before.size());

        ((DataCompositionChart)schema.getDefaultSettings().getItems().get(0)).setName("Renamed"); //$NON-NLS-1$
        assertNull("renaming the broken chart", after(before, schema)); //$NON-NLS-1$

        schema.getDefaultSettings().getItems().add(0,
            (DataCompositionChart)settings(chart(points("Period"), "", measures("Amount"))).getItems().get(0)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("inserting a valid chart before it shifts its address", after(before, schema)); //$NON-NLS-1$

        SettingsVariant variant =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
        variant.setName("Main"); //$NON-NLS-1$
        variant.setSettings(settings());
        variant.getSettings().getItems().add(schema.getDefaultSettings().getItems().remove(1));
        schema.getSettingsVariants().add(variant);
        // Counts are per settings tree, so a broken chart moved into another tree is a new break there.
        String moved = after(before, schema);
        assertNotNull("moving it into a variant", moved); //$NON-NLS-1$
        assertTrue(moved, moved.startsWith("Chart at '" + ROOT + "#/variants/Main/settings/items/0'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A fix in one settings tree cannot pay for a break of the same reference in another tree. */
    @Test
    public void testAFixInOneTreeCannotPayForABreakInAnother()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        String resource = "{\"userFields\":{\"items\":[{\"kind\":\"expression\",\"dataPath\":\"" + en //$NON-NLS-1$
            + ".X\",\"totalExpression\":\"Sum(Amount)\"}]},"; //$NON-NLS-1$
        String plain = "{\"userFields\":{\"items\":[{\"kind\":\"expression\",\"dataPath\":\"" + en //$NON-NLS-1$
            + ".X\",\"detailExpression\":\"Customer\"}]},"; //$NON-NLS-1$
        String items = "\"items\":[" + chart(points("Customer"), "", measures(en + ".X")) + "]}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(plan(json(plain + items)));
        SettingsVariant variant =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
        variant.setName("Main"); //$NON-NLS-1$
        variant.setSettings(plan(json(resource + items)));
        schema.getSettingsVariants().add(variant);
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals("only the default chart measures a non-resource", 1, before.size()); //$NON-NLS-1$

        schema.setDefaultSettings(plan(json(resource + items)));
        variant.setSettings(plan(json(plain + items)));
        String error = after(before, schema);
        assertNotNull("the variant's chart is newly broken", error); //$NON-NLS-1$
        assertTrue(error, error.contains("measure '" + en + ".X' at '" + ROOT //$NON-NLS-1$ //$NON-NLS-2$
            + "#/variants/Main/settings/items/0/selection/items/0'")); //$NON-NLS-1$
    }

    /** The same broken user field spelled with the other folder term is not a new break. */
    @Test
    public void testRespellingABrokenUserFieldInTheOtherLanguageIsNotANewBreak()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        String ru = DcsTerms.kDCSSUserFieldsTerm[1];
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(settings(chart(points("Customer"), "", measures(en + ".Missing")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals(1, before.size());

        schema.setDefaultSettings(settings(chart(points("Customer"), "", measures(ru + ".missing")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(after(before, schema));
    }

    /** A switched-off measure is not drawn, so it neither breaks a chart nor gives it a measure. */
    @Test
    public void testSwitchedOffMeasuresNeitherBreakNorMeasureAChart()
    {
        DataCompositionSchema schema = schema();
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        schema.setDefaultSettings(settings(chart(points("Customer"), "", measures("Amount", "Missing")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        selected(schema, 1).setUse(false);
        assertNull("a disabled missing measure beside a valid one", after(before, schema)); //$NON-NLS-1$

        selected(schema, 0).setUse(false);
        String error = after(before, schema);
        assertNotNull("only disabled measures", error); //$NON-NLS-1$
        assertTrue(error, error.contains("the chart has no measure")); //$NON-NLS-1$
    }

    @Test
    public void testASwitchedOffPointIsNotJudged()
    {
        DataCompositionSchema schema = schema();
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        schema.setDefaultSettings(settings(chart(points("Missing"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionChart chart = (DataCompositionChart)schema.getDefaultSettings().getItems().get(0);
        ((DataCompositionGroupField)chart.getPoints().get(0).getGroupFields().getItems().get(0)).setUse(false);
        assertNull("a disabled point field", after(before, schema)); //$NON-NLS-1$

        ((DataCompositionGroupField)chart.getPoints().get(0).getGroupFields().getItems().get(0)).setUse(true);
        chart.getPoints().get(0).setUse(false);
        assertNull("a point field under a disabled chart group", after(before, schema)); //$NON-NLS-1$
    }

    @Test
    public void testASwitchedOffChartIsNotJudged()
    {
        DataCompositionSchema schema = schema();
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        schema.setDefaultSettings(settings(chart(points("Missing"), "", ""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionChart chart = (DataCompositionChart)schema.getDefaultSettings().getItems().get(0);
        assertNotNull("the enabled chart is broken", after(before, schema)); //$NON-NLS-1$

        chart.setUse(false);
        assertNull("a switched-off chart", after(before, schema)); //$NON-NLS-1$
    }

    @Test
    public void testAChartUnderASwitchedOffGroupIsJudgedOnceTheGroupIsSwitchedOn()
    {
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(settings(chart(points("Missing"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionSettings settings = schema.getDefaultSettings();
        DataCompositionGroup group = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionGroup();
        group.getItems().add(settings.getItems().get(0));
        settings.getItems().add(group);
        group.setUse(false);
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals("a chart under a switched-off group is not drawn", 0, before.size()); //$NON-NLS-1$

        group.setUse(true);
        assertNotNull("switching the group on draws the broken chart", after(before, schema)); //$NON-NLS-1$

        group.setGroupState(DataCompositionSettingsItemState.DISABLED);
        DcsChartReferences.Census disabledState = DcsChartReferences.census(schema, ROOT);
        assertEquals("a group in the Disabled state is not drawn either", 0, disabledState.size()); //$NON-NLS-1$
        group.setGroupState(DataCompositionSettingsItemState.ENABLED);
        assertNotNull("enabling the group state draws the broken chart", after(disabledState, schema)); //$NON-NLS-1$
    }

    @Test
    public void testAPointInADisabledAxisGroupStateIsNotJudged()
    {
        DataCompositionSchema schema = schema();
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        schema.setDefaultSettings(settings(chart(points("Missing"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionChart chart = (DataCompositionChart)schema.getDefaultSettings().getItems().get(0);
        chart.getPoints().get(0).setGroupState(DataCompositionSettingsItemState.DISABLED);
        assertNull("a point group in the Disabled state", after(before, schema)); //$NON-NLS-1$
    }

    /** An unnamed variant and one named like its index are separate trees, whatever moves. */
    @Test
    public void testAnUnnamedVariantAndANumericNameStayApartWhenVariantsShift()
    {
        DataCompositionSchema schema = schema();
        for (String name : new String[] {"Foo", null, "1"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            SettingsVariant variant =
                com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
            variant.setName(name);
            variant.setSettings("1".equals(name) //$NON-NLS-1$
                ? settings(chart(points("Customer"), "", measures("Missing"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : settings());
            schema.getSettingsVariants().add(variant);
        }
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals(1, before.size());

        schema.getSettingsVariants().remove(0);
        assertNull("removing an unrelated variant", after(before, schema)); //$NON-NLS-1$
    }

    /** A field literally named like an internal marker is still a field. */
    @Test
    public void testAFieldNamedLikeAMarkerIsNotTheMissingMeasure()
    {
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(settings(chart(points("Customer"), "", ""), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            chart(points("Customer"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals("the measureless chart", 1, before.size()); //$NON-NLS-1$

        schema.setDefaultSettings(settings(chart(points("Customer"), "", measures("Amount")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            chart(points("Customer"), "", measures("#none")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String error = after(before, schema);
        assertNotNull("a missing field named '#none' is a new break", error); //$NON-NLS-1$
        assertTrue(error, error.contains("measure '#none'")); //$NON-NLS-1$
    }

    private static DataCompositionSelectedField selected(DataCompositionSchema schema, int index)
    {
        DataCompositionChart chart = (DataCompositionChart)schema.getDefaultSettings().getItems().get(0);
        return (DataCompositionSelectedField)chart.getSelection().getItems().get(index);
    }

    /** The count is per kind, so a second broken reference like the first one is still new. */
    @Test
    public void testEachAddedBrokenReferenceCountsEvenWhenOneLikeItWasThere()
    {
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(settings(chart(points("Customer"), "", measures("Customer")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);

        DataCompositionSchema twice = EcoreUtil.copy(schema);
        twice.setDefaultSettings(settings(chart(points("Customer"), "", measures("Customer", "customer")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String added = after(before, twice);
        assertNotNull("a second measure on the same missing resource", added); //$NON-NLS-1$
        assertTrue(added, added.contains("measure 'customer' at '" + SETTINGS + "/items/0/selection/items/1'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(added, added.contains("The write adds 1 of these 2; the others were already there.")); //$NON-NLS-1$

        DataCompositionSchema copied = EcoreUtil.copy(schema);
        copied.getDefaultSettings().getItems().add(EcoreUtil.copy(copied.getDefaultSettings().getItems().get(0)));
        String copy = after(before, copied);
        assertNotNull("a copy of a broken chart", copy); //$NON-NLS-1$
        assertTrue(copy, copy.startsWith("Charts at '" + SETTINGS + "/items/0', '" + SETTINGS + "/items/1' refer")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        DataCompositionSchema fixedOneBrokeOther = EcoreUtil.copy(schema);
        fixedOneBrokeOther.setDefaultSettings(settings(chart(points("Customer"), "", measures("Amount", "Missing")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String traded = after(before, fixedOneBrokeOther);
        assertNotNull("a different broken reference is a new kind", traded); //$NON-NLS-1$
        assertTrue(traded, traded.contains("measure 'Missing'")); //$NON-NLS-1$
    }

    /** A schema edit is judged against every chart, changed or not. */
    @Test
    public void testASchemaEditThatBreaksAnUnchangedChartIsRefused()
    {
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(settings(chart(points("Customer"), series("Period.Year"), measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals(0, before.size());

        DataCompositionSchema restricted = EcoreUtil.copy(schema);
        field(restricted, "Customer").setUseRestriction(groupRestriction()); //$NON-NLS-1$
        String grouping = after(before, restricted);
        assertNotNull("forbidding grouping on the point's field", grouping); //$NON-NLS-1$
        assertTrue(grouping, grouping.contains("point 'Customer' at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/points/0/groupFields/items/0': 'Customer' is not available for grouping")); //$NON-NLS-1$

        DataCompositionSchema noResource = EcoreUtil.copy(schema);
        noResource.getTotalFields().remove(0);
        String resource = after(before, noResource);
        assertNotNull("removing the resource the chart measures", resource); //$NON-NLS-1$
        assertTrue(resource, resource.contains("measure 'Amount'")); //$NON-NLS-1$
        assertTrue(resource, resource.contains("'Amount' is a field but not a resource")); //$NON-NLS-1$

        DataCompositionSchema noParent = EcoreUtil.copy(schema);
        noParent.getDataSets().get(0).getFields().remove(field(noParent, "Period")); //$NON-NLS-1$
        String attribute = after(before, noParent);
        assertNotNull("removing the field whose attribute a series groups by", attribute); //$NON-NLS-1$
        assertTrue(attribute, attribute.contains("series 'Period.Year'")); //$NON-NLS-1$
    }

    @Test
    public void testAUserFieldEditThatBreaksAnUnchangedChartIsRefused()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Margin\",\"totalExpression\":\"Sum(Amount)\"}]}," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"items\":[" + chart(points("Customer"), "", measures(en + ".Margin")) + "]}"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals(0, before.size());

        ((DataCompositionUserFieldExpression)schema.getDefaultSettings().getUserFields().getItems().get(0))
            .setTotalExpression(""); //$NON-NLS-1$
        String error = after(before, schema);
        assertNotNull("the measured user field stops being a resource", error); //$NON-NLS-1$
        assertTrue(error, error.contains("measure '" + en + ".Margin'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSwitchingOffAMeasuredUserFieldIsRefused()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Margin\",\"totalExpression\":\"Sum(Amount)\"}]}," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"items\":[" + chart(points("Customer"), "", measures(en + ".Margin")) + "]}"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals(0, before.size());

        schema.getDefaultSettings().getUserFields().getItems().get(0).setUse(false);
        String error = after(before, schema);
        assertNotNull("a switched-off user field is not available to the chart", error); //$NON-NLS-1$
        assertTrue(error, error.contains("measure '" + en + ".Margin'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Each variant resolves its own user fields, and its problems carry the variant's address. */
    @Test
    public void testVariantsAreJudgedWithTheirOwnUserFieldsAndAddresses()
    {
        String en = DcsTerms.kDCSSUserFieldsTerm[0];
        DataCompositionSchema schema = schema();
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        SettingsVariant main =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
        main.setName("Main"); //$NON-NLS-1$
        main.setSettings(plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Label\",\"detailExpression\":\"Customer\"}]}," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"items\":[" + chart(points(en + ".Label"), "", measures("Amount")) + "]}"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        schema.getSettingsVariants().add(main);
        assertNull("the variant's own user field resolves", after(before, schema)); //$NON-NLS-1$

        schema.setDefaultSettings(settings(chart(points(en + ".Label"), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String error = after(before, schema);
        assertNotNull("another tree does not see the variant's user field", error); //$NON-NLS-1$
        assertTrue(error, error.startsWith("Chart at '" + SETTINGS + "/items/0'")); //$NON-NLS-1$ //$NON-NLS-2$

        SettingsVariant unnamed =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
        unnamed.setSettings(settings(chart(points("Customer"), "", measures("Missing")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        schema.setDefaultSettings(null);
        schema.getSettingsVariants().add(unnamed);
        String indexed = after(before, schema);
        assertNotNull(indexed);
        assertTrue(indexed, indexed.startsWith("Chart at '" + ROOT + "#/variants/1/settings/items/0'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testVariantsSharingANameAreBothCounted()
    {
        DataCompositionSchema schema = schema();
        for (String point : new String[] {"Customer", "Period"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            SettingsVariant twin =
                com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
            twin.setName("Twin"); //$NON-NLS-1$
            twin.setSettings(settings(chart(points(point), "", measures("Amount")))); //$NON-NLS-1$ //$NON-NLS-2$
            schema.getSettingsVariants().add(twin);
        }
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        assertEquals(0, before.size());

        field(schema, "Customer").setUseRestriction(groupRestriction()); //$NON-NLS-1$
        String error = after(before, schema);
        assertNotNull("the first of two same-named variants is judged too", error); //$NON-NLS-1$
        assertTrue(error, error.contains("point 'Customer' at '" + ROOT //$NON-NLS-1$
            + "#/variants/Twin/settings/items/0/points/0/groupFields/items/0'")); //$NON-NLS-1$
    }

    @Test
    public void testChangesChartsSeesAddedAndChangedChartsButNotRemovedOnes()
    {
        DataCompositionSettings withChart = settings(chart(points("Customer"), "", measures("Amount"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionSettings empty = plan(json("{}")); //$NON-NLS-1$

        assertTrue(DcsChartReferences.changesCharts(null, withChart));
        assertTrue(DcsChartReferences.changesCharts(empty, withChart));
        assertFalse(DcsChartReferences.changesCharts(withChart, EcoreUtil.copy(withChart)));
        assertFalse(DcsChartReferences.changesCharts(withChart, empty));
        DataCompositionSettings twice = settings(chart(points("Customer"), "", measures("Amount")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            chart(points("Customer"), "", measures("Amount"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("a copy of an existing chart is a new chart", //$NON-NLS-1$
            DcsChartReferences.changesCharts(withChart, twice));
        DataCompositionSettings changed = EcoreUtil.copy(withChart);
        ((DataCompositionChart)changed.getItems().get(0)).setUse(false);
        assertTrue(DcsChartReferences.changesCharts(withChart, changed));
    }

    @Test
    public void testReferencesCoverNestedAxisGroupsAndSelectionFolders()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"name\":\"Outer\"," //$NON-NLS-1$
            + "\"items\":[{\"kind\":\"chart\",\"points\":[{\"groupFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"field\":{\"kind\":\"field\",\"value\":\"Customer\"}}]},\"items\":[{\"groupFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"field\":{\"kind\":\"field\",\"value\":\"Customer.Region\"}}]}}]}]," //$NON-NLS-1$
            + "\"selection\":{\"items\":[{\"kind\":\"group\",\"items\":[" //$NON-NLS-1$
            + "{\"field\":{\"kind\":\"field\",\"value\":\"Amount\"}}]},{\"kind\":\"auto\"}]}}]}]}")); //$NON-NLS-1$
        DataCompositionChart chart = (DataCompositionChart)DcsChartReferences.charts(settings)
            .get("items/0/items/0"); //$NON-NLS-1$
        assertNotNull("a chart nested in a grouping is found", chart); //$NON-NLS-1$

        List<DcsChartReferences.Reference> references = DcsChartReferences.references(chart, "C"); //$NON-NLS-1$
        assertEquals(4, references.size());
        assertReference(references.get(0), "points", "Customer", "C/points/0/groupFields/items/0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReference(references.get(1), "points", "Customer.Region", //$NON-NLS-1$ //$NON-NLS-2$
            "C/points/0/items/0/groupFields/items/0"); //$NON-NLS-1$
        assertReference(references.get(2), "selection", "Amount", "C/selection/items/0/items/0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertReference(references.get(3), "selection", null, "C/selection/items/1"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The tool takes the census before the write and again after its schema and settings halves
     * are both applied, so a chart may measure a resource that same call declares.
     */
    @Test
    public void testTheEndStateOfTheWholeWriteIsJudged()
    {
        DataCompositionSchema schema = schema();
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        JsonObject bonusChart = JsonParser.parseString(chart(points("Customer"), "", //$NON-NLS-1$ //$NON-NLS-2$
            measures("Bonus"))).getAsJsonObject(); //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult planned = DcsSettingsWriter.planSchema(schema, "upsert", //$NON-NLS-1$
            "chart", address(SETTINGS), bonusChart, LANGUAGES); //$NON-NLS-1$
        assertTrue(planned.error(), planned.isSuccess());
        planned.plan().commit(schema);

        String error = after(before, schema);
        assertNotNull("Bonus is not a resource yet", error); //$NON-NLS-1$
        assertTrue(error, error.startsWith("Chart at '" + SETTINGS + "/items/0'")); //$NON-NLS-1$ //$NON-NLS-2$

        DataCompositionSchemaTotalField bonus = DcsFactory.eINSTANCE.createDataCompositionSchemaTotalField();
        bonus.setDataPath("Bonus"); //$NON-NLS-1$
        bonus.setExpression("Sum(Bonus)"); //$NON-NLS-1$
        schema.getTotalFields().add(bonus);
        assertNull("the resource the same write declares satisfies the chart", after(before, schema)); //$NON-NLS-1$
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static final String AUTO = "{\"kind\":\"auto\"}"; //$NON-NLS-1$

    /** The refusal for putting {@code settings} into {@code schema} as its default settings. */
    private static String judge(DataCompositionSchema schema, DataCompositionSettings settings)
    {
        DcsChartReferences.Census before = DcsChartReferences.census(schema, ROOT);
        schema.setDefaultSettings(settings);
        return after(before, schema);
    }

    private static String after(DcsChartReferences.Census before, DataCompositionSchema schema)
    {
        return DcsChartReferences.error(before, DcsChartReferences.census(schema, ROOT));
    }

    /**
     * Customer, Period, Product, Restricted (no grouping), Locked (no attribute grouping) and
     * the calculated Margin; resources Amount and Revenue, which are also fields, and AverageCheck.
     */
    private static DataCompositionSchema schema()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery set = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        set.setName("Sales"); //$NON-NLS-1$
        set.setQuery("SELECT 1"); //$NON-NLS-1$
        set.setAutoFillAvailableFields(false);
        for (String path : new String[] {"Customer", "Period", "Amount", PRODUCT, REVENUE}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            set.getFields().add(field(path));
        }
        DataCompositionSchemaDataSetField restricted = field("Restricted"); //$NON-NLS-1$
        restricted.setUseRestriction(groupRestriction());
        set.getFields().add(restricted);
        DataCompositionSchemaDataSetField locked = field("Locked"); //$NON-NLS-1$
        locked.setAttributeUseRestriction(groupRestriction());
        set.getFields().add(locked);
        schema.getDataSets().add(set);

        DataCompositionSchemaCalculatedField margin = DcsFactory.eINSTANCE.createDataCompositionSchemaCalculatedField();
        margin.setDataPath("Margin"); //$NON-NLS-1$
        margin.setExpression("Amount - 1"); //$NON-NLS-1$
        schema.getCalculatedFields().add(margin);

        for (String path : new String[] {"Amount", REVENUE, "AverageCheck"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            DataCompositionSchemaTotalField total = DcsFactory.eINSTANCE.createDataCompositionSchemaTotalField();
            total.setDataPath(path);
            total.setExpression("Avg(Amount)"); //$NON-NLS-1$
            schema.getTotalFields().add(total);
        }
        return schema;
    }

    private static DataCompositionSchemaDataSetField field(String path)
    {
        DataCompositionSchemaDataSetField field = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField();
        field.setDataPath(path);
        field.setField(path);
        return field;
    }

    private static DataCompositionSchemaDataSetField field(DataCompositionSchema schema, String path)
    {
        for (Object field : schema.getDataSets().get(0).getFields())
        {
            if (field instanceof DataCompositionSchemaDataSetField
                && path.equals(((DataCompositionSchemaDataSetField)field).getDataPath()))
            {
                return (DataCompositionSchemaDataSetField)field;
            }
        }
        throw new AssertionError("no field " + path); //$NON-NLS-1$
    }

    private static DataCompositionSchemaFieldUseRestriction groupRestriction()
    {
        DataCompositionSchemaFieldUseRestriction restriction =
            DcsFactory.eINSTANCE.createDataCompositionSchemaFieldUseRestriction();
        restriction.setGroup(true);
        return restriction;
    }

    /** A chart body; an empty axis or measure list is left out of it. */
    private static String chart(String points, String series, String measures)
    {
        StringBuilder body = new StringBuilder("{\"kind\":\"chart\""); //$NON-NLS-1$
        if (!points.isEmpty()) body.append(",\"points\":[").append(points).append(']'); //$NON-NLS-1$
        if (!series.isEmpty()) body.append(",\"series\":[").append(series).append(']'); //$NON-NLS-1$
        if (!measures.isEmpty()) body.append(",\"selection\":{\"items\":[").append(measures).append("]}"); //$NON-NLS-1$ //$NON-NLS-2$
        return body.append('}').toString();
    }

    private static String points(String path)
    {
        return axisGroup(path);
    }

    private static String series(String path)
    {
        return axisGroup(path);
    }

    private static String axisGroup(String path)
    {
        return "{\"groupFields\":{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"" + path + "\"}}]}}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String measures(String... paths)
    {
        StringBuilder result = new StringBuilder();
        for (String path : paths)
        {
            if (result.length() > 0) result.append(',');
            result.append("{\"field\":{\"kind\":\"field\",\"value\":\"").append(path).append("\"}}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result.toString();
    }

    private static DataCompositionSettings settings(String... charts)
    {
        return plan(json("{\"items\":[" + String.join(",", charts) + "]}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static DataCompositionSettings plan(JsonObject body)
    {
        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(null,
            Collections.emptyList(), "upsert", "userSettings", body, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error(), result.isSuccess());
        return result.settings();
    }

    private static void assertReference(DcsChartReferences.Reference reference, String role, String field,
        String address)
    {
        assertEquals(role, reference.role);
        assertEquals(field, reference.field);
        assertEquals(address, reference.address);
    }

    private static JsonObject json(String source)
    {
        return JsonParser.parseString(source).getAsJsonObject();
    }

    private static DcsAddress address(String source)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(source);
        assertTrue(parsed.failure() == null ? source : parsed.failure().message(), parsed.isSuccess());
        return parsed.address();
    }
}
