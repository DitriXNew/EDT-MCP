/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateField;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableCell;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableRow;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionDetailsAreaParameter;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionExpressionAreaParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.details.DataCompositionDetailsProcessingAction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaGroupTemplate;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.util.DcsTerms;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #662: the template body builds what EDT's template designer builds, and the reproducibility guard
 * (restate, rebuild, compare) refuses every stored value the writer cannot rebuild exactly.
 */
public class DcsTemplateBodyTest
{
    static final Version VERSION = Version.V8_3_27;
    static final DcsPresentationParser.LanguageContext EN =
        new DcsPresentationParser.LanguageContext(List.of("en", "ru"), "en", "en", false);
    static final DcsPresentationParser.LanguageContext RU =
        new DcsPresentationParser.LanguageContext(List.of("ru", "en"), "ru", "ru", false);

    static final String FULL_BODY = """
        {"name":"Totals","rows":[{"cells":[
          {"text":"Total"},
          {"parameter":"Amount","appearance":{"HorizontalAlign":"Right",
            "BorderStyle.Left":{"kind":"line","value":{"width":2,"style":"Solid"}}}},
          {"items":[{"text":{"en":"Note","ru":"Note ru"},"appearance":{"HorizontalAlign":"Center"}},{"parameter":"Amount"}]},
          {}
        ]}],
        "parameters":[{"name":"Amount","expression":"Sum(Amount)"},
          {"name":"Drill","kind":"details","fieldExpression":[{"field":"Customer","expression":"Customer"}],
           "mainAction":"DrillDown"}]}
        """;

    static DcsAvailableParameterCollection catalogue() throws Exception
    {
        return DcsReplaceTestCatalogues.load(DcsSettingsWriter.AppearanceCatalogue.SCHEMA, VERSION, "en");
    }

    static DcsTemplateBody.Context context(DcsPresentationParser.LanguageContext languages) throws Exception
    {
        return DcsTemplateBody.Context.of(VERSION, languages, catalogue(), null);
    }

    static DataCompositionSchemaTemplateDescription build(String body,
        DcsPresentationParser.LanguageContext languages) throws Exception
    {
        DataCompositionSchemaTemplateDescription template =
            DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        String error = DcsTemplateBody.apply(template, json(body), context(languages), "body", true,
            new LinkedHashSet<>());
        assertNull(error, error);
        return template;
    }

    @Test
    public void testBuildsEveryMemberTheWayEdtsDesignerDoes() throws Exception
    {
        DataCompositionSchemaTemplateDescription template = build(FULL_BODY, EN);
        assertEquals("Totals", template.getName());
        DataCompositionAreaTemplateTableRow row =
            (DataCompositionAreaTemplateTableRow)template.getTemplate().getItems().get(0);
        assertNull(row.getTableID());
        assertEquals(4, row.getCells().size());

        // A static text is a core LocalString, as DataCompositionAreaTemplateBuilder writes it.
        DataCompositionAreaTemplateField text = field(row.getCells().get(0));
        assertTrue(text.getValue().getClass().getName(), text.getValue() instanceof LocalString);
        assertEquals("Total", ((LocalString)text.getValue()).getContent().get("en"));

        DataCompositionAreaTemplateTableCell parameterCell = row.getCells().get(1);
        assertEquals("Amount", ((DataCompositionParameter)field(parameterCell).getValue()).getValue());
        assertEquals(2, parameterCell.getAppearance().getItems().size());
        assertEquals("HorizontalAlign", parameterCell.getAppearance().getItems().get(0).getParameter().getValue());
        // Every item, the dotted-key holder and its nested side included, is EDT's core class.
        assertEquals("BorderStyle", parameterCell.getAppearance().getItems().get(1).getParameter().getValue());
        assertCoreItems(parameterCell.getAppearance().getItems());

        DataCompositionAreaTemplateTableCell twoFields = row.getCells().get(2);
        assertEquals(2, twoFields.getItem().size());
        DataCompositionAreaTemplateField note = field(twoFields);
        assertEquals("Note ru", ((LocalString)note.getValue()).getContent().get("ru"));
        assertEquals(1, note.getAppearance().getItems().size());
        assertCoreItems(note.getAppearance().getItems());
        assertTrue(row.getCells().get(3).getItem().isEmpty());
        assertNull(row.getCells().get(3).getAppearance());

        DataCompositionExpressionAreaParameter expression =
            (DataCompositionExpressionAreaParameter)template.getParameters().get(0);
        assertEquals("Sum(Amount)", expression.getExpression());
        DataCompositionDetailsAreaParameter details =
            (DataCompositionDetailsAreaParameter)template.getParameters().get(1);
        assertEquals(DataCompositionDetailsProcessingAction.DRILL_DOWN, details.getMainAction());
        assertEquals("Customer", details.getFieldExpression().get(0).getField());
        assertNull(DcsTemplateBody.restatementRefusal(template, context(EN)));
    }

    @Test
    public void testTemplateOnlyKeysAreSpelledInTheConfigurationLanguage() throws Exception
    {
        String body = "{\"name\":\"T\",\"rows\":[{\"cells\":[{\"parameter\":\"Drill\",\"appearance\":"
            + "{\"HorizontalMerge\":true,\"VerticalMerge\":false,\"Details\":\"Drill\"}}]}]}";
        for (DcsPresentationParser.LanguageContext languages : List.of(EN, RU))
        {
            int index = languages == RU ? 1 : 0;
            List<DataCompositionParameterValue> items = cell(build(body, languages), 0, 0).getAppearance().getItems();
            assertEquals(DcsTerms.kDCSAppearanceHorizontalMergeTerm[index], items.get(0).getParameter().getValue());
            assertEquals(DcsTerms.kDCSAppearanceVerticalMergeTerm[index], items.get(1).getParameter().getValue());
            assertEquals(DcsTerms.kDCSAppearanceDetailTerm[index], items.get(2).getParameter().getValue());
            assertTrue(((BooleanValue)items.get(0).getValues().get(0)).isValue());
            assertEquals("Drill", ((DataCompositionParameter)items.get(2).getValues().get(0)).getValue());
            assertCoreItems(items);
        }
        // Either spelling is read; the configuration's spelling is written.
        String russianInput = "{\"name\":\"T\",\"rows\":[{\"cells\":[{\"appearance\":{\"items\":[{\"parameter\":"
            + "{\"kind\":\"parameter\",\"value\":\"" + DcsTerms.kDCSAppearanceHorizontalMergeTerm[1]
            + "\"},\"values\":[{\"kind\":\"boolean\",\"value\":true}]}]}}]}]}";
        assertEquals("HorizontalMerge",
            cell(build(russianInput, EN), 0, 0).getAppearance().getItems().get(0).getParameter().getValue());
    }

    @Test
    public void testABuiltTemplateRoundTripsThroughEdtsSerializerAndItsOwnRestatement() throws Exception
    {
        String body = FULL_BODY.replace("{}\n", "{\"appearance\":{\"HorizontalMerge\":true,\"Details\":\"Drill\"}}\n");
        DataCompositionSchemaTemplateDescription built = build(body, EN);
        DataCompositionSchema schema = DcsStoredXmlTestSupport.schema();
        schema.getTemplates().add(built);
        DataCompositionSchemaGroupTemplate binding = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        assertNull(DcsTemplateBody.applyBinding(binding,
            json("{\"groupName\":\"Customer\",\"templateType\":\"Footer\",\"template\":\"Totals\"}"), "b", true));
        schema.getGroupTemplates().add(binding);

        String xml = DcsStoredXmlTestSupport.serialize(schema);
        assertTrue(xml, xml.contains("Totals") && xml.contains("Footer"));
        DataCompositionSchema read = DcsStoredXmlTestSupport.deserialize(xml);

        assertNull(DcsModelComparison.firstDifference(built, read.getTemplates().get(0)));
        assertNull(DcsTemplateBody.restatementRefusal(read.getTemplates().get(0), context(EN)));
        assertNull(DcsTemplateBody.restatementRefusal(read.getGroupTemplates().get(0), context(EN)));
        assertEquals(DcsTemplateBody.restate(built).body, DcsTemplateBody.restate(read.getTemplates().get(0)).body);
    }

    @Test
    public void testStoredXmlRestatedAndRebuiltLosesNothing() throws Exception
    {
        // The fixture is EDT's own serialization of a designer-shaped template (core text, a
        // parameter cell with a merge and a details key, an expression parameter, a binding).
        DataCompositionSchema seeded = DcsStoredXmlTestSupport.schema();
        seeded.getTemplates().add(build("{\"name\":\"Footer662\",\"rows\":[{\"cells\":["
            + "{\"text\":{\"en\":\"Sum\",\"ru\":\"Sum ru\"}},"
            + "{\"parameter\":\"P\",\"appearance\":{\"HorizontalAlign\":\"Right\",\"HorizontalMerge\":true,\"Details\":\"D\"}}]}],"
            + "\"parameters\":[{\"name\":\"P\",\"expression\":\"Amount\"},{\"name\":\"D\",\"kind\":\"details\"}]}", EN));
        DataCompositionSchemaGroupTemplate binding = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        assertNull(DcsTemplateBody.applyBinding(binding,
            json("{\"groupFields\":[\"Customer\",\"Period\"],\"templateType\":\"OverallFooter\",\"template\":\"Footer662\"}"), "b", true));
        seeded.getGroupHeaderTemplates().add(binding);
        String fixture = DcsStoredXmlTestSupport.serialize(seeded);

        DataCompositionSchema read = DcsStoredXmlTestSupport.deserialize(fixture);
        DataCompositionSchema rebuilt = DcsStoredXmlTestSupport.schema();
        DataCompositionSchemaTemplateDescription template = DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        assertNull(DcsTemplateBody.apply(template, DcsTemplateBody.restate(read.getTemplates().get(0)).body,
            context(EN), "restated", true, new LinkedHashSet<>()));
        rebuilt.getTemplates().add(template);
        DataCompositionSchemaGroupTemplate rebuiltBinding = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        assertNull(DcsTemplateBody.applyBinding(rebuiltBinding,
            DcsTemplateBody.restate(read.getGroupHeaderTemplates().get(0)).body, "restated", true));
        rebuilt.getGroupHeaderTemplates().add(rebuiltBinding);

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(fixture, DcsStoredXmlTestSupport.serialize(rebuilt)));
    }

    @Test
    public void testTheGuardRefusesAnotherSpellingOfAnAppearanceKey() throws Exception
    {
        // Stored by a Russian configuration; an English one would rebuild the English name.
        DataCompositionSchemaTemplateDescription russian = build(
            "{\"name\":\"T\",\"rows\":[{\"cells\":[{\"text\":\"x\",\"appearance\":{\"HorizontalAlign\":\"Right\"}}]}]}", RU);
        assertNull(DcsTemplateBody.restatementRefusal(russian, context(RU)));
        String refusal = DcsTemplateBody.restatementRefusal(russian, context(EN));
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("rebuild it differently"));
    }

    @Test
    public void testTheGuardRefusesAnOldAliasAndAnUnknownName() throws Exception
    {
        DataCompositionSchemaTemplateDescription template = build(
            "{\"name\":\"T\",\"rows\":[{\"cells\":[{\"text\":\"x\",\"appearance\":{\"MinWidth\":10}}]}]}", EN);
        DataCompositionParameter name = cell(template, 0, 0).getAppearance().getItems().get(0).getParameter();
        assertEquals("MinWidth", name.getValue());
        assertNull(DcsTemplateBody.restatementRefusal(template, context(EN)));

        name.setValue("MinimumWidth"); // the pre-8.3.22 term, still indexed by the catalogue
        String alias = DcsTemplateBody.restatementRefusal(template, context(EN));
        assertNotNull(alias);
        assertTrue(alias, alias.contains("rebuild it differently") && alias.contains("MinimumWidth"));

        name.setValue("NoSuchKey662");
        String unknown = DcsTemplateBody.restatementRefusal(template, context(EN));
        assertNotNull(unknown);
        assertTrue(unknown, unknown.contains("NoSuchKey662") && unknown.contains("rejects its own restatement"));
    }

    @Test
    public void testTheGuardRefusesRuntimeMembersSettingsItemsAndDoubleGroupings() throws Exception
    {
        DataCompositionSchemaTemplateDescription template = build(FULL_BODY, EN);
        cell(template, 0, 0).setAppIndex(1);
        assertContains(DcsTemplateBody.restatementRefusal(template, context(EN)), "appearance index 1");

        template = build(FULL_BODY, EN);
        ((DataCompositionDetailsAreaParameter)template.getParameters().get(1)).setGroupID("g");
        assertContains(DcsTemplateBody.restatementRefusal(template, context(EN)), "groupID");

        template = build(FULL_BODY, EN);
        var settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsParameterValue();
        DataCompositionParameterValue stored = cell(template, 0, 1).getAppearance().getItems().get(0);
        settings.setParameter(org.eclipse.emf.ecore.util.EcoreUtil.copy(stored.getParameter()));
        settings.getValues().addAll(org.eclipse.emf.ecore.util.EcoreUtil.copyAll(stored.getValues()));
        cell(template, 0, 1).getAppearance().getItems().set(0, settings);
        assertContains(DcsTemplateBody.restatementRefusal(template, context(EN)), "settings parameter value");

        DataCompositionSchemaGroupTemplate binding = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        binding.setGroupName("G");
        DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
        field.setValue("Customer");
        binding.getGroupFields().add(field);
        binding.setTemplate("T");
        assertContains(DcsTemplateBody.restatementRefusal(binding, context(EN)), "both by name and by fields");
    }

    /**
     * EDT's Template.dcs serializer writes no value into a template field other than a text or a
     * parameter (a string, number, boolean or date field is exported empty), so the body offers
     * no typed constant, and a stored one has no body form.
     */
    @Test
    public void testAFieldHoldsOnlyATextOrAParameter() throws Exception
    {
        DataCompositionSchemaTemplateDescription template =
            DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        String error = DcsTemplateBody.apply(template, json("{\"rows\":[{\"cells\":[{\"items\":"
            + "[{\"value\":{\"kind\":\"number\",\"value\":5}}]}]}]}"), context(EN), "body", true,
            new LinkedHashSet<>());
        assertContains(error, "Unknown member 'value'");

        DataCompositionSchemaTemplateDescription built = build(FULL_BODY, EN);
        var number = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createNumberValue();
        number.setValue(java.math.BigDecimal.TEN);
        field(cell(built, 0, 0)).setValue(number);
        assertContains(DcsTemplateBody.restatementRefusal(built, context(EN)), "its value is a NumberValue");

        // Nor does it write a row's tableID.
        assertContains(DcsTemplateBody.apply(DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription(),
            json("{\"rows\":[{\"cells\":[],\"tableID\":\"T1\"}]}"), context(EN), "body", true, new LinkedHashSet<>()),
            "Unknown member 'tableID'");
        built = build(FULL_BODY, EN);
        ((DataCompositionAreaTemplateTableRow)built.getTemplate().getItems().get(0)).setTableID("T1");
        assertContains(DcsTemplateBody.restatementRefusal(built, context(EN)), "tableID 'T1'");
    }

    @Test
    public void testRestatementShowsWhatHasNoBodyForm() throws Exception
    {
        DataCompositionSchemaTemplateDescription template = build(FULL_BODY, EN);
        cell(template, 0, 2).setAppIndex(3);
        DcsTemplateBody.Restatement restated = DcsTemplateBody.restate(template);
        assertEquals(1, restated.unsupported.size());
        String cell = restated.body.getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonArray("cells")
            .get(2).getAsJsonObject().get("unsupported").getAsString();
        assertTrue(cell, cell.startsWith("DataCompositionAreaTemplateTableCell: appearance index 3"));
        // Canonical form: a shorthand cell is restated as items, appearance as {items:[...]}.
        JsonObject parameterCell = restated.body.getAsJsonArray("rows").get(0).getAsJsonObject()
            .getAsJsonArray("cells").get(1).getAsJsonObject();
        assertTrue(parameterCell.toString(), parameterCell.has("items") && parameterCell.getAsJsonObject("appearance").has("items"));
    }

    // ----- helpers -----

    static DataCompositionAreaTemplateTableCell cell(DataCompositionSchemaTemplateDescription template, int row, int col)
    {
        return ((DataCompositionAreaTemplateTableRow)template.getTemplate().getItems().get(row)).getCells().get(col);
    }

    static DataCompositionAreaTemplateField field(DataCompositionAreaTemplateTableCell cell)
    {
        return (DataCompositionAreaTemplateField)cell.getItem().get(0);
    }

    static void assertCoreItems(List<DataCompositionParameterValue> items)
    {
        for (DataCompositionParameterValue item : items)
        {
            assertSame(item.getParameter().getValue(),
                com._1c.g5.v8.dt.dcs.model.core.DcsPackage.Literals.DATA_COMPOSITION_PARAMETER_VALUE, item.eClass());
            assertCoreItems(item.getNestedParameterValues());
        }
    }

    static void assertContains(String text, String expected)
    {
        assertNotNull(expected, text);
        assertTrue("expected <" + expected + "> in: " + text, text.contains(expected));
    }

    static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
