/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableCell;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableRow;
import com._1c.g5.v8.dt.dcs.model.common.DataCompositionAreaTemplateType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaGroupTemplate;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;

/**
 * #662: typed writes of area templates and grouping bindings through {@link DcsSchemaWriter}
 * (prepare + apply), at the addresses the read prints and in the root body. On master every one of
 * these answered "type='schema' targets the bare root".
 */
public class DcsTemplateWriterTest
{
    private static final String ROOT = "Report.Sales"; //$NON-NLS-1$
    private static final String ROWS = "\"rows\":[{\"cells\":[{\"text\":\"Total\",\"appearance\":{\"HorizontalAlign\":\"Right\"}},"
        + "{\"parameter\":\"Amount\"}]}]"; //$NON-NLS-1$

    @Test
    public void testUpsertAtTheCollectionCreatesATemplateByName()
    {
        DataCompositionSchema schema = schema();
        DcsSchemaWriter.Result result = write(schema, "upsert", ROOT + "#/templates",
            "{\"name\":\"Totals\"," + ROWS + ",\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount\"}]}");
        success(result);
        assertEquals(1, schema.getTemplates().size());
        assertEquals("Totals", schema.getTemplates().get(0).getName());
        assertEquals("templates=1, groupTemplates=0, groupHeaderTemplates=0", result.templates().counts());
        assertTrue(result.templates().warnings().toString(), result.templates().warnings().isEmpty());
    }

    @Test
    public void testPartialUpdateChangesOneTemplateAndKeepsTheOthersObjects()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        DataCompositionSchemaTemplateDescription untouched = schema.getTemplates().get(1);
        DataCompositionSchemaTemplateDescription before = schema.getTemplates().get(0);
        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount * 2\"}]}"));
        assertSame(untouched, schema.getTemplates().get(1));
        assertNotSame(before, schema.getTemplates().get(0));
        assertEquals(2, ((DataCompositionAreaTemplateTableRow)schema.getTemplates().get(0).getTemplate().getItems()
            .get(0)).getCells().size());
        assertEquals("Amount * 2", ((com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionExpressionAreaParameter)
            schema.getTemplates().get(0).getParameters().get(0)).getExpression());
    }

    @Test
    public void testUpdateRenamesAnUnboundTemplateAndRefusesABoundOne()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        success(write(schema, "update", ROOT + "#/templates/A", "{\"name\":\"Renamed\"}"));
        assertEquals("Renamed", schema.getTemplates().get(0).getName());

        schema.getGroupHeaderTemplates().add(binding("Customer", "B"));
        String refused = failure(write(schema, "update", ROOT + "#/templates/B", "{\"name\":\"B2\"}"));
        assertContains(refused, ROOT + "#/groupHeaderTemplates/0");
        assertEquals("B", schema.getTemplates().get(1).getName());

        String collision = failure(write(schema, "update", ROOT + "#/templates/Renamed", "{\"name\":\"b\"}"));
        assertContains(collision, "collides");
        String numeric = failure(write(schema, "upsert", ROOT + "#/templates", "{\"name\":\"7\"}"));
        assertContains(numeric, "reads as an index");
    }

    @Test
    public void testReplaceRebuildsTheNodeAndRemoveRefusesABoundTemplate()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        success(write(schema, "replace", ROOT + "#/templates/A", "{\"rows\":[]}"));
        DataCompositionSchemaTemplateDescription replaced = schema.getTemplates().get(0);
        assertEquals("A", replaced.getName());
        assertTrue(replaced.getParameters().isEmpty());
        assertTrue(replaced.getTemplate().getItems().isEmpty());

        schema.getGroupTemplates().add(binding("Customer", "B"));
        String refused = failure(write(schema, "remove", ROOT + "#/templates/B", null));
        assertContains(refused, ROOT + "#/groupTemplates/0");
        assertContains(refused, "still refer to it");
        assertEquals(2, schema.getTemplates().size());

        success(write(schema, "remove", ROOT + "#/groupTemplates/0", null));
        success(write(schema, "remove", ROOT + "#/templates/B", null));
        assertEquals(1, schema.getTemplates().size());
    }

    @Test
    public void testRootBatchWritesATemplateAndItsBindingInOneCall()
    {
        DataCompositionSchema schema = schema();
        DcsSchemaWriter.Result result = write(schema, "upsert", ROOT, "{\"templates\":[{\"name\":\"T\"," + ROWS
            + ",\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount\"}]}],"
            + "\"groupTemplates\":[{\"groupName\":\"Customer\",\"templateType\":\"Footer\",\"template\":\"T\"}]}");
        success(result);
        assertEquals(1, schema.getTemplates().size());
        assertEquals(1, schema.getGroupTemplates().size());
        assertEquals(DataCompositionAreaTemplateType.FOOTER, schema.getGroupTemplates().get(0).getTemplateType());
        assertEquals("templates=1, groupTemplates=1, groupHeaderTemplates=0", result.templates().counts());
    }

    @Test
    public void testRootReplaceClearsTemplatesAndBindingsTheBodyOmits()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        schema.getGroupTemplates().add(binding("Customer", "A"));
        success(write(schema, "replace", ROOT, "{\"templates\":[{\"name\":\"Only\"}]}"));
        assertEquals(1, schema.getTemplates().size());
        assertEquals("Only", schema.getTemplates().get(0).getName());
        assertTrue(schema.getGroupTemplates().isEmpty());
    }

    @Test
    public void testBindingUpsertKeysByGroupingAndTemplateType()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        schema.getGroupTemplates().add(binding("Customer", "A"));
        success(write(schema, "upsert", ROOT + "#/groupTemplates", "{\"groupName\":\"customer\",\"template\":\"B\"}"));
        assertEquals(1, schema.getGroupTemplates().size());
        assertEquals("B", schema.getGroupTemplates().get(0).getTemplate());

        success(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"templateType\":\"Footer\",\"template\":\"A\"}"));
        assertEquals(2, schema.getGroupTemplates().size());
    }

    @Test
    public void testGroupNameClearsGroupFieldsAndBothAreRefused()
    {
        DataCompositionSchema schema = schemaWith("A");
        DataCompositionSchemaGroupTemplate byFields = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        byFields.getGroupFields().add(field("Customer"));
        byFields.setTemplate("A");
        schema.getGroupTemplates().add(byFields);
        success(write(schema, "update", ROOT + "#/groupTemplates/0", "{\"groupName\":\"Customer\"}"));
        assertEquals("Customer", schema.getGroupTemplates().get(0).getGroupName());
        assertTrue(schema.getGroupTemplates().get(0).getGroupFields().isEmpty());

        assertContains(failure(write(schema, "update", ROOT + "#/groupTemplates/0",
            "{\"groupName\":\"X\",\"groupFields\":[\"Y\"]}")), "both groupName and groupFields");
    }

    @Test
    public void testCellsPatchChangesOneCellAndMergesItsAppearance()
    {
        DataCompositionSchema schema = schemaWith("A");
        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Changed\"}]}"));
        DataCompositionAreaTemplateTableCell first = cell(schema, 0, 0);
        assertEquals("Changed", ((LocalString)((com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateField)
            first.getItem().get(0)).getValue()).getContent().get("en"));
        // The content patch keeps the cell's appearance.
        assertEquals(1, first.getAppearance().getItems().size());
        assertEquals("HorizontalAlign", first.getAppearance().getItems().get(0).getParameter().getValue());

        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"VerticalAlign\":\"Top\"}}]}"));
        first = cell(schema, 0, 0);
        assertEquals(2, first.getAppearance().getItems().size());
        assertEquals("HorizontalAlign", first.getAppearance().getItems().get(0).getParameter().getValue());
        assertEquals("VerticalAlign", first.getAppearance().getItems().get(1).getParameter().getValue());
        assertEquals("Changed", ((LocalString)((com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateField)
            first.getItem().get(0)).getValue()).getContent().get("en"));

        String outside = failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":5,\"text\":\"x\"}]}"));
        assertContains(outside, "1 row(s) with 2 cell(s)");
    }

    @Test
    public void testRowsInAnUpdateRefuseToDiscardWhatTheWriterCannotRestate()
    {
        DataCompositionSchema schema = schemaWith("A");
        cell(schema, 0, 1).setAppIndex(4);
        String refused = failure(write(schema, "update", ROOT + "#/templates/A", "{" + ROWS + "}"));
        assertContains(refused, "appearance index 4");
        assertContains(refused, "body.cells");
        assertEquals(4, cell(schema, 0, 1).getAppIndex());

        // A cells patch of another cell still works and keeps the unrestatable one.
        success(write(schema, "update", ROOT + "#/templates/A", "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"y\"}]}"));
        assertEquals(4, cell(schema, 0, 1).getAppIndex());

        String replace = failure(write(schema, "replace", ROOT + "#/templates/A", "{" + ROWS + "}"));
        assertContains(replace, "appearance index 4");
    }

    @Test
    public void testIntegrityAndShapeRefusalsNameTheValueAndWriteNothing()
    {
        DataCompositionSchema schema = schemaWith("A");
        assertContains(failure(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"template\":\"Missing\"}")), "names template 'Missing'");
        assertTrue(schema.getGroupTemplates().isEmpty());
        assertContains(failure(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"templateType\":\"Middle\",\"template\":\"A\"}")), "OverallFooter");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"parameters\":[{\"name\":\"D\",\"kind\":\"details\",\"mainAction\":\"Explode\"}]}")), "ApplyAppearance");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{" + ROWS + ",\"cells\":[]}")), "both rows and cells");
        // The same catalogue key in both spellings, and a template-only key twice.
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"HorizontalAlign\":\"Left\",\""
                + com._1c.g5.v8.dt.dcs.util.DcsTerms.kDCSAppearanceHorizontalAlignTerm[1] + "\":\"Right\"}}]}")),
            "appears twice");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"items\":["
                + "{\"parameter\":{\"kind\":\"parameter\",\"value\":\"HorizontalMerge\"},\"values\":[true]},"
                + "{\"parameter\":{\"kind\":\"parameter\",\"value\":\"HorizontalMerge\"},\"values\":[false]}]}}]}")),
            "appears twice");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"Details\":true}}]}")), "name of a template parameter");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"unknown\":1}")), "Unknown member 'unknown'");

        DataCompositionSchemaTemplateDescription dup = DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        dup.setName("A");
        schema.getTemplates().add(dup);
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A", "{\"name\":\"X\"}")), "ambiguous");
        assertEquals("A", schema.getTemplates().get(0).getName());
    }

    @Test
    public void testWarningsNameAnUnknownGroupingAndAnUndeclaredCellParameter()
    {
        DataCompositionSchema schema = schema();
        DataCompositionGroup group = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createDataCompositionGroup();
        group.setName("Known");
        schema.getDefaultSettings().getItems().add(group);
        DcsSchemaWriter.Result result = write(schema, "upsert", ROOT, "{\"templates\":[{\"name\":\"T\"," + ROWS
            + "}],\"groupTemplates\":[{\"groupName\":\"Elsewhere\",\"template\":\"T\"},{\"groupName\":\"known\",\"template\":\"T\",\"templateType\":\"Footer\"}]}");
        success(result);
        String warnings = String.join("\n", result.templates().warnings());
        assertContains(warnings, "names grouping 'Elsewhere'");
        assertContains(warnings, "named groupings: Known");
        assertFalse(warnings, warnings.contains("'known'"));
        assertContains(warnings, "refers to parameter(s) Amount");
    }

    // ----- helpers -----

    static DcsSchemaWriter.Result write(DataCompositionSchema schema, String action, String address, String body)
    {
        DcsSchemaWriter.PrepareResult prepared = DcsSchemaWriter.prepare(action, "schema",
            DcsAddress.parse(address).address(), body == null ? null : DcsTemplateBodyTest.json(body),
            DcsTemplateBodyTest.EN);
        assertTrue(prepared.error(), prepared.isSuccess());
        return DcsSchemaWriter.apply(schema, prepared.request(), null, DcsTemplateBodyTest.VERSION, null,
            DcsReplaceTestCatalogues.schema());
    }

    static DataCompositionSchema schema()
    {
        return DcsStoredXmlTestSupport.schema();
    }

    static DataCompositionSchema schemaWith(String... names)
    {
        DataCompositionSchema schema = schema();
        for (String name : names)
        {
            DataCompositionSchemaTemplateDescription template = DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
            try
            {
                assertNull(DcsTemplateBody.apply(template, DcsTemplateBodyTest.json("{\"name\":\"" + name + "\"," + ROWS
                    + ",\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount\"}]}"),
                    DcsTemplateBodyTest.context(DcsTemplateBodyTest.EN), "seed", true, new LinkedHashSet<>()));
            }
            catch (Exception e)
            {
                throw new AssertionError(e);
            }
            schema.getTemplates().add(template);
        }
        return schema;
    }

    static DataCompositionSchemaGroupTemplate binding(String groupName, String template)
    {
        DataCompositionSchemaGroupTemplate binding = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        binding.setGroupName(groupName);
        binding.setTemplate(template);
        return binding;
    }

    static DataCompositionField field(String value)
    {
        DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
        field.setValue(value);
        return field;
    }

    static DataCompositionAreaTemplateTableCell cell(DataCompositionSchema schema, int row, int col)
    {
        return DcsTemplateBodyTest.cell(schema.getTemplates().get(0), row, col);
    }

    static void success(DcsSchemaWriter.Result result)
    {
        assertTrue(result.error(), result.isSuccess());
        assertNotNull(result.templates());
    }

    static String failure(DcsSchemaWriter.Result result)
    {
        assertFalse("expected a refusal", result.isSuccess());
        return result.error();
    }

    static void assertContains(String text, String expected)
    {
        DcsTemplateBodyTest.assertContains(text, expected);
    }
}
