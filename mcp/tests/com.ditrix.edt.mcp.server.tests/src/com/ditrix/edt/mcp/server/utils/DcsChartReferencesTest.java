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
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaFieldUseRestriction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChart;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.util.DcsTerms;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link DcsChartReferences}: a chart's points and series must group by schema data and
 * its measures must be resources, judged against the end state of a write, in either language.
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
    /** Summa, the Russian spelling of the Sum aggregate. */
    private static final String SUM_RU = "Сумма"; //$NON-NLS-1$

    @Test
    public void testChartOnSchemaFieldsAndResourcesIsAccepted()
    {
        DataCompositionSettings planned = settings(chart(points("Customer"), series("Period"), //$NON-NLS-1$ //$NON-NLS-2$
            measures("Amount"))); //$NON-NLS-1$
        assertNull(DcsChartReferences.error(schema(), null, planned, SETTINGS));

        DataCompositionSettings russian = settings(chart(points(PRODUCT), series("period"), //$NON-NLS-1$
            measures(REVENUE, "amount"))); //$NON-NLS-1$
        assertNull("Russian data paths and a different case resolve like the schema's own", //$NON-NLS-1$
            DcsChartReferences.error(schema(), null, russian, SETTINGS));
    }

    @Test
    public void testMeasureThatIsNotAResourceIsRefusedWithTheResourcesToChooseFrom()
    {
        String error = DcsChartReferences.error(schema(), null,
            settings(chart(points("Customer"), "", measures("Customer", "Missing"))), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

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
        assertTrue(error, error.endsWith("Nothing was written.")); //$NON-NLS-1$
    }

    @Test
    public void testPointOrSeriesThatIsAResourceOrMissingIsRefused()
    {
        assertNull("a resource that is also a data-set field groups, as in the platform", //$NON-NLS-1$
            DcsChartReferences.error(schema(), null,
                settings(chart(points("Amount"), "", measures("Amount"))), SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String resource = DcsChartReferences.error(schema(), null,
            settings(chart(points("AverageCheck"), "", measures("Amount"))), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(resource);
        assertTrue(resource, resource.contains("point 'AverageCheck' at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/points/0/groupFields/items/0': 'AverageCheck' is a resource; a resource cannot be")); //$NON-NLS-1$
        assertTrue(resource, resource.contains("A point or series groups by a schema field")); //$NON-NLS-1$
        assertTrue(resource, resource.contains("Customer")); //$NON-NLS-1$
        assertFalse("a pure resource is not offered as a grouping choice", //$NON-NLS-1$
            resource.substring(resource.indexOf("A point or series")).contains("AverageCheck")); //$NON-NLS-1$ //$NON-NLS-2$

        String attribute = DcsChartReferences.error(schema(), null,
            settings(chart(points("AverageCheck.Currency"), "", measures("Amount"))), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(attribute);
        assertTrue(attribute, attribute.contains("a resource's attributes cannot be grouped")); //$NON-NLS-1$

        String missing = DcsChartReferences.error(schema(), null,
            settings(chart(points("Customer"), series("Nowhere"), measures("Amount"))), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(missing);
        assertTrue(missing, missing.contains("series 'Nowhere' at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/series/0/groupFields/items/0': no schema field, calculated field or user field")); //$NON-NLS-1$
    }

    @Test
    public void testDottedAttributesAndCalculatedFieldsFollowTheSchemaUseRestrictions()
    {
        assertNull("an attribute of a groupable field is accepted", //$NON-NLS-1$
            DcsChartReferences.error(schema(), null,
                settings(chart(points("Customer.Region"), "", measures("Amount"))), SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("a calculated field and its attributes group", //$NON-NLS-1$
            DcsChartReferences.error(schema(), null,
                settings(chart(points("Margin"), series("Margin.Level"), measures("Amount"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                SETTINGS));

        String restricted = DcsChartReferences.error(schema(), null,
            settings(chart(points("Restricted"), "", measures("Amount"))), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(restricted);
        assertTrue(restricted, restricted.contains("'Restricted' is not available for grouping")); //$NON-NLS-1$
        assertFalse("a restricted field is not offered as a grouping choice", //$NON-NLS-1$
            restricted.substring(restricted.indexOf("A point or series")).contains("Restricted")); //$NON-NLS-1$ //$NON-NLS-2$

        String attributes = DcsChartReferences.error(schema(), null,
            settings(chart(points("Locked.Owner"), "", measures("Amount"))), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(attributes);
        assertTrue(attributes, attributes.contains("the attributes of 'Locked' are not available")); //$NON-NLS-1$
        assertNull("the field itself stays groupable", DcsChartReferences.error(schema(), null, //$NON-NLS-1$
            settings(chart(points("Locked"), "", measures("Amount"))), SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPercentMeasuresOfAResourceAreAcceptedInEitherLanguage()
    {
        assertNull(DcsChartReferences.error(schema(), null,
            settings(chart(points("Customer"), "", measures( //$NON-NLS-1$ //$NON-NLS-2$
                "Amount." + DcsTerms.kDCSSystemFieldsOverallPercent[0], //$NON-NLS-1$
                REVENUE + "." + DcsTerms.kDCSSystemFieldsGroupPercent[1]))), //$NON-NLS-1$
            SETTINGS));

        String field = DcsChartReferences.error(schema(), null,
            settings(chart(points("Customer"), "", //$NON-NLS-1$ //$NON-NLS-2$
                measures("Customer." + DcsTerms.kDCSSystemFieldsOverallPercent[0]))), SETTINGS); //$NON-NLS-1$
        assertNotNull("a field's percent is not a measure", field); //$NON-NLS-1$
        assertTrue(field, field.contains("no resource has this data path")); //$NON-NLS-1$
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
            + "{\"kind\":\"expression\",\"dataPath\":\"" + en + ".Label\",\"detailExpression\":\"Customer\"}]}," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"items\":[" + chart(points(en + ".Label"), "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                measures(ru + ".Margin", en + "." + FIELD1)) + "]}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DataCompositionSettings planned = plan(body);
        assertNull("the folder term resolves in either language, an aggregate makes a resource", //$NON-NLS-1$
            DcsChartReferences.error(schema(), null, planned, SETTINGS));

        JsonObject detailOnly = body.deepCopy();
        detailOnly.getAsJsonArray("items").set(0, JsonParser.parseString( //$NON-NLS-1$
            chart(points("Customer"), "", measures(en + ".Label")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String error = DcsChartReferences.error(schema(), null, plan(detailOnly), SETTINGS);
        assertNotNull(error);
        assertTrue(error, error.contains("user field '" + en + ".Label' has no total expression")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("resource user fields are offered as measures", //$NON-NLS-1$
            error.contains(en + ".Margin") && error.contains(ru + "." + FIELD1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAutoMeasureNeedsAResourceAndAChartNeedsAMeasure()
    {
        assertNull(DcsChartReferences.error(schema(), null,
            settings(chart(points("Customer"), "", AUTO)), SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$

        DataCompositionSchema noResources = schema();
        noResources.getTotalFields().clear();
        String auto = DcsChartReferences.error(noResources, null,
            settings(chart(points("Customer"), "", AUTO)), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(auto);
        assertTrue(auto, auto.contains("measure at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/selection/items/0': the automatic measure expands to the schema's resources")); //$NON-NLS-1$
        assertTrue(auto, auto.contains("A measure must be a resource: (none in this schema)")); //$NON-NLS-1$

        String none = DcsChartReferences.error(schema(), null,
            settings(chart(points("Customer"), "", "")), SETTINGS); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(none);
        assertTrue(none, none.contains("measure at '" + SETTINGS //$NON-NLS-1$
            + "/items/0/selection': the chart has no measure, so it has nothing to draw")); //$NON-NLS-1$
    }

    @Test
    public void testAutoFillQueryFieldsAreAcceptedByWholeWordOnly()
    {
        DataCompositionSchema schema = schema();
        DataCompositionSchemaDataSetQuery derived = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        derived.setName("Derived"); //$NON-NLS-1$
        derived.setQuery("SELECT Goods.Brand AS Brand FROM Catalog.Goods AS Goods"); //$NON-NLS-1$
        derived.setAutoFillAvailableFields(true);
        schema.getDataSets().add(derived);

        assertNull("an auto-fill query derives fields it does not list", //$NON-NLS-1$
            DcsChartReferences.error(schema, null,
                settings(chart(points("Brand"), series("brand.Country"), measures("Amount"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                SETTINGS));
        assertNotNull("a fragment of a word in the query is not a field", //$NON-NLS-1$
            DcsChartReferences.error(schema, null,
                settings(chart(points("Bran"), "", measures("Amount"))), SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        derived.setAutoFillAvailableFields(false);
        assertNotNull("without auto-fill only listed fields exist", //$NON-NLS-1$
            DcsChartReferences.error(schema, null,
                settings(chart(points("Brand"), "", measures("Amount"))), SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testOnlyNewOrChangedChartsAreCheckedAndProblemsTheChartHadAreKept()
    {
        DataCompositionSettings original = settings(chart(points("Customer"), "", measures("Customer"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull("an unchanged chart is not re-judged", //$NON-NLS-1$
            DcsChartReferences.error(schema(), original, EcoreUtil.copy(original), SETTINGS));

        DataCompositionSettings renamed = EcoreUtil.copy(original);
        ((DataCompositionChart)renamed.getItems().get(0)).setName("Renamed"); //$NON-NLS-1$
        assertNull("an unrelated edit does not fail over a problem the chart already had", //$NON-NLS-1$
            DcsChartReferences.error(schema(), original, renamed, SETTINGS));

        DataCompositionSettings worse = settings(chart(points("Customer"), "", //$NON-NLS-1$ //$NON-NLS-2$
            measures("Customer", "Missing"))); //$NON-NLS-1$ //$NON-NLS-2$
        String added = DcsChartReferences.error(schema(), original, worse, SETTINGS);
        assertNotNull(added);
        assertTrue(added, added.contains("measure 'Missing'")); //$NON-NLS-1$
        assertFalse("the kept problem is not reported again", added.contains("measure 'Customer'")); //$NON-NLS-1$ //$NON-NLS-2$

        DataCompositionSettings second = settings(chart(points("Customer"), "", measures("Customer")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            chart(points("Customer"), "", measures("Customer"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String copied = DcsChartReferences.error(schema(), original, second, SETTINGS);
        assertNotNull("a new chart is judged in full, even as a copy of a broken one", copied); //$NON-NLS-1$
        assertTrue(copied, copied.startsWith("Chart at '" + SETTINGS + "/items/1'")); //$NON-NLS-1$ //$NON-NLS-2$

        DataCompositionSettings inserted = settings(chart(points("Period"), "", measures("Customer")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            chart(points("Customer"), "", measures("Customer"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String shifted = DcsChartReferences.error(schema(), original, inserted, SETTINGS);
        assertNotNull("a chart inserted before a moved one does not inherit its problems", shifted); //$NON-NLS-1$
        assertTrue(shifted, shifted.startsWith("Chart at '" + SETTINGS + "/items/0'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the moved original is still unchanged", DcsChartReferences.error(schema(), //$NON-NLS-1$
            original, settings(chart(points("Period"), "", measures("Amount")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                chart(points("Customer"), "", measures("Customer"))), SETTINGS)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
     * The tool checks a settings write after the schema half of the same call, so a chart may
     * measure a resource that call declares, and the error names the chart's canonical address.
     */
    @Test
    public void testSchemaPlanJudgesTheEndStateOfTheWrite()
    {
        DataCompositionSchema schema = schema();
        JsonObject bonusChart = JsonParser.parseString(chart(points("Customer"), "", //$NON-NLS-1$ //$NON-NLS-2$
            measures("Bonus"))).getAsJsonObject(); //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult planned = DcsSettingsWriter.planSchema(schema, "upsert", //$NON-NLS-1$
            "chart", address(SETTINGS), bonusChart, LANGUAGES); //$NON-NLS-1$
        assertTrue(planned.error(), planned.isSuccess());

        String error = planned.plan().chartReferenceError(schema, ROOT);
        assertNotNull("Bonus is not a resource yet", error); //$NON-NLS-1$
        assertTrue(error, error.contains("Chart at '" + SETTINGS + "/items/0'")); //$NON-NLS-1$ //$NON-NLS-2$

        DataCompositionSchemaTotalField bonus = DcsFactory.eINSTANCE.createDataCompositionSchemaTotalField();
        bonus.setDataPath("Bonus"); //$NON-NLS-1$
        bonus.setExpression("Sum(Bonus)"); //$NON-NLS-1$
        schema.getTotalFields().add(bonus);
        assertNull("the resource the same write declares satisfies the chart", //$NON-NLS-1$
            planned.plan().chartReferenceError(schema, ROOT));

        DcsSettingsWriter.SchemaResult variant = DcsSettingsWriter.planSchema(schema, "upsert", //$NON-NLS-1$
            "variant", address(ROOT), json("{\"name\":\"Main\",\"presentation\":{\"EN\":\"Main\"}}"), //$NON-NLS-1$ //$NON-NLS-2$
            LANGUAGES);
        assertTrue(variant.error(), variant.isSuccess());
        variant.plan().commit(schema);
        String variantSettings = ROOT + "#/variants/Main/settings"; //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult inVariant = DcsSettingsWriter.planSchema(schema, "upsert", //$NON-NLS-1$
            "chart", address(variantSettings), JsonParser.parseString(chart(points("Customer"), //$NON-NLS-1$ //$NON-NLS-2$
                "", measures("Missing"))).getAsJsonObject(), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(inVariant.error(), inVariant.isSuccess());
        String variantError = inVariant.plan().chartReferenceError(schema, ROOT);
        assertNotNull(variantError);
        assertTrue(variantError, variantError.contains("Chart at '" + variantSettings + "/items/0'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static final String AUTO = "{\"kind\":\"auto\"}"; //$NON-NLS-1$

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
