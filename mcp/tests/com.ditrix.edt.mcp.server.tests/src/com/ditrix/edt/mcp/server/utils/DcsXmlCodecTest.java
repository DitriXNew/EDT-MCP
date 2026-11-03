/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mockito.Mockito;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Unit contract for EDT-native DCS XML decoding and root-preserving wholesale replacement. */
public class DcsXmlCodecTest
{
    private static final String HASH = "0123456789abcdef0123"; //$NON-NLS-1$

    @Test
    @SuppressWarnings("unchecked")
    public void testReplacementDetachesRootChildrenBeforeBmAttachment()
    {
        var target = Mockito.spy(DcsFactory.eINSTANCE.createDataCompositionSchema());
        var feature = com._1c.g5.v8.dt.dcs.model.schema.DcsPackage.Literals
            .DATA_COMPOSITION_SCHEMA__PARAMETERS;
        EList<Object> parameters = Mockito.spy((EList<Object>)target.eGet(feature));
        Mockito.doReturn(parameters).when(target).eGet(feature);
        Mockito.doAnswer(invocation ->
        {
            java.util.Collection<?> incoming = invocation.getArgument(0);
            for (Object value : incoming)
            {
                // BM attachment switches storage delegates; a temporary parent must not retain it.
                assertNull(((EObject)value).eContainer());
            }
            return invocation.callRealMethod();
        }).when(parameters).addAll(Mockito.anyCollection());
        var settingsFeature = com._1c.g5.v8.dt.dcs.model.schema.DcsPackage.Literals
            .DATA_COMPOSITION_SCHEMA__DEFAULT_SETTINGS;
        Mockito.doAnswer(invocation ->
        {
            EObject incoming = invocation.getArgument(1);
            assertNull(incoming.eContainer());
            return invocation.callRealMethod();
        }).when(target).eSet(Mockito.eq(settingsFeature), Mockito.any(EObject.class));
        var imported = DcsFactory.eINSTANCE.createDataCompositionSchema();
        imported.setDefaultSettings(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings());
        for (int i = 0; i < 4; i++)
        {
            var parameter = DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
            parameter.setName("P" + i);
            imported.getParameters().add(parameter);
        }

        DcsXmlCodec.replaceContent(target, imported);

        assertEquals(4, target.getParameters().size());
        assertEquals(4, imported.getParameters().size());
        assertNotSame(imported.getDefaultSettings(), target.getDefaultSettings());
    }

    @Test
    public void testNativeXmlImportKeepsFourParametersAndVariantValuesAfterRepeatedReplacement() throws Exception
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var applied = DcsWriter.apply(schema, JsonParser.parseString("""
            {"dataSources":[{"name":"Main","type":"Local"}],
             "dataSets":[{"name":"Balances","query":"SELECT 1 AS Amount","autoFillFields":false,
                "fields":[{"dataPath":"Amount","role":{"periodNumber":1,"periodType":"Main"},
                    "useRestriction":{"field":true},"attributeUseRestriction":{"condition":true}}]}],
             "parameters":[{"name":"Period"},{"name":"Start","expression":"&Period.StartDate"},
                {"name":"End","expression":"&Period.EndDate"},{"name":"Дата"}]}
            """).getAsJsonObject());
        assertFalse(applied.error, applied.hasError());
        for (int i = 1; i < 14; i++)
        {
            var field = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField();
            field.setDataPath("Field" + i);
            schema.getDataSets().get(0).getFields().add(field);
        }
        var period = DcsSettingsWriter.buildValue(JsonParser.parseString("""
            {"kind":"standardPeriod","value":{"variant":"ThisYear"}}
            """), "period");
        assertNull(period.error);
        schema.getParameters().get(0).getValues().add(period.value);
        var variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
        variant.setName("Main");
        variant.setSettings(DcsStoredXmlTestSupport.operand(false,
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createStringValue()).getDefaultSettings());
        schema.getSettingsVariants().add(variant);
        var imported = DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(schema));
        assertEquals(4, imported.getParameters().size());
        var target = DcsFactory.eINSTANCE.createDataCompositionSchema();
        for (int pass = 0; pass < 2; pass++)
        {
            DcsXmlCodec.replaceContent(target, imported);
            assertEquals(4, target.getParameters().size());
            assertEquals(new DcsReadProjection.SchemaCounts(1, 1, 14, 4, 1),
                DcsReadProjection.schemaCounts(target));
            assertEquals("Дата", target.getParameters().get(3).getName());
            assertEquals("&Period.StartDate", target.getParameters().get(1).getExpression());
            assertEquals(1, target.getSettingsVariants().get(0).getSettings()
                .getDataParameters().getItems().size());
            assertEquals(null, DcsModelComparison.firstDifference(imported, target));
            assertNull(DcsMutationGuard.xmlReplaceError(imported, target,
                DcsAddress.parse("Report.Sales").address()));
            assertEquals(null, DcsXmlRoundTripComparator.firstMissingPath(
                DcsStoredXmlTestSupport.serialize(imported), DcsStoredXmlTestSupport.serialize(target)));
            assertEquals(4, imported.getParameters().size());
        }
    }

    @Test
    public void testMalformedXmlReturnsActionableError()
    {
        DcsXmlCodec codec = new DcsXmlCodec(new DcsV8Serializer((IResourceLookup)null), null);

        DcsXmlCodec.SchemaResult result = codec.deserialize("<DataCompositionSchema><dataSets>"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("body.xml")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("malformed")); //$NON-NLS-1$
        assertInvalidXmlRecovery(result);
    }

    @Test
    public void testUnknownXmlElementPreservesItsNameAndLineAndExplainsRecovery()
    {
        var codec = new DcsXmlCodec(new DcsV8Serializer((IResourceLookup)null), null);
        var result = codec.deserialize("""
            <DataCompositionSchema xmlns="http://v8.1c.ru/8.1/data-composition-system/schema">
            <Issue664Unsupported/>
            </DataCompositionSchema>
            """);

        assertInvalidXmlRecovery(result);
        assertTrue(result.error(), result.error().contains("Issue664Unsupported"));
        assertTrue(result.error(), result.error().contains("Line number = 2"));
    }

    @Test
    public void testEmptyXmlExplainsNothingWasWrittenAndHowToRetry()
    {
        var codec = new DcsXmlCodec(new DcsV8Serializer((IResourceLookup)null), null);
        for (String xml : new String[] {null, "", " \r\n "})
        {
            var result = codec.deserialize(xml);
            assertTrue(result.error(), result.error().contains("body.xml is empty"));
            assertInvalidXmlRecovery(result);
        }
    }

    @Test
    public void testMissingSchemaRootExplainsNothingWasWrittenAndHowToRetry() throws Exception
    {
        var serializer = Mockito.mock(DcsV8Serializer.class);
        Mockito.when(serializer.deserializeXML(Mockito.any(java.io.InputStream.class))).thenReturn(null);
        var codec = new DcsXmlCodec(serializer, null);

        var result = codec.deserialize("<!-- no schema root -->");

        assertTrue(result.error(), result.error().contains("does not contain a DataCompositionSchema root"));
        assertInvalidXmlRecovery(result);
    }

    private static void assertInvalidXmlRecovery(DcsXmlCodec.SchemaResult result)
    {
        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("nothing was written"));
        assertTrue(result.error(), result.error().contains("Remove or correct"));
        assertTrue(result.error(), result.error().contains("reported line"));
        assertTrue(result.error(), result.error().contains("retry with the complete DataCompositionSchema XML"));
        assertTrue(result.error(), result.error().contains("action='get', type='schema', format='xml'"));
    }

    @Test
    public void testReplaceContentKeepsRootAndCopiesAllPopulatedSections()
    {
        DataCompositionSchema target = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery oldDataSet = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        oldDataSet.setName("Old"); //$NON-NLS-1$
        target.getDataSets().add(oldDataSet);

        DataCompositionSchema imported = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery importedDataSet = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        importedDataSet.setName("Sales"); //$NON-NLS-1$
        importedDataSet.setQuery("SELECT 1 AS Amount"); //$NON-NLS-1$
        imported.getDataSets().add(importedDataSet);
        imported.setDefaultSettings(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings());
        SettingsVariant importedVariant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        importedVariant.setName("Manager"); //$NON-NLS-1$
        importedVariant.setSettings(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings());
        imported.getSettingsVariants().add(importedVariant);

        DcsXmlCodec.replaceContent(target, imported);

        assertEquals(1, target.getDataSets().size());
        assertEquals("Sales", target.getDataSets().get(0).getName()); //$NON-NLS-1$
        assertEquals("SELECT 1 AS Amount", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetQuery)target.getDataSets().get(0)).getQuery());
        assertEquals(1, target.getSettingsVariants().size());
        assertEquals("Manager", target.getSettingsVariants().get(0).getName()); //$NON-NLS-1$
        assertTrue(target.getDefaultSettings() != null);
        assertNotSame(importedDataSet, target.getDataSets().get(0));
        assertNotSame(importedVariant, target.getSettingsVariants().get(0));
        assertEquals(1, imported.getDataSets().size());
        assertEquals(1, imported.getSettingsVariants().size());
    }

    @Test
    public void testImportedDanglingLinkIsRefusedBeforeAttachedRootReplacement()
    {
        DataCompositionSchema target = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery original = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        original.setName("Original"); //$NON-NLS-1$
        target.getDataSets().add(original);
        String beforeHash = DcsHash.compute(target);

        DataCompositionSchema imported = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery source = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        source.setName("Source"); //$NON-NLS-1$
        imported.getDataSets().add(source);
        DataCompositionSchemaDataSetLink link = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("Source"); //$NON-NLS-1$
        link.setDestinationDataSet("Missing"); //$NON-NLS-1$
        imported.getDataSetLinks().add(link);

        String error = DcsSchemaWriter.validateAssembledReferences(imported, "Report.Sales"); //$NON-NLS-1$

        assertTrue(error, error.contains("dangling destinationDataSet 'Missing'")); //$NON-NLS-1$
        assertTrue(error, error.contains("Report.Sales#/dataSetLinks/0")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(target));
        assertEquals("Original", target.getDataSets().get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void testPageEnvelopeUsesExactChunkBoundariesAndNextOffsetArithmetic()
    {
        JsonObject first = page("0123456789", 2, 4); //$NON-NLS-1$

        assertTrue(first.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(10, first.get("totalChars").getAsInt()); //$NON-NLS-1$
        assertEquals(2, first.get("offset").getAsInt()); //$NON-NLS-1$
        assertEquals("2345", first.get("xml").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.get("hasMore").getAsBoolean()); //$NON-NLS-1$
        assertEquals(6, first.get("nextOffset").getAsInt()); //$NON-NLS-1$
        assertEquals(first.get("offset").getAsInt() + first.get("xml").getAsString().length(), //$NON-NLS-1$ //$NON-NLS-2$
            first.get("nextOffset").getAsInt()); //$NON-NLS-1$
        assertEquals(HASH, first.get("hash").getAsString()); //$NON-NLS-1$

        JsonObject last = page("0123456789", first.get("nextOffset").getAsInt(), 20); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(6, last.get("offset").getAsInt()); //$NON-NLS-1$
        assertEquals("6789", last.get("xml").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(last.get("hasMore").getAsBoolean()); //$NON-NLS-1$
        assertFalse(last.has("nextOffset")); //$NON-NLS-1$
    }

    @Test
    public void testPageEnvelopeNeverSplitsUtf16SurrogatePairs()
    {
        String xml = "A\uD83D\uDE00BC"; //$NON-NLS-1$
        JsonObject first = page(xml, 0, 2);
        JsonObject second = page(xml, first.get("nextOffset").getAsInt(), 1); //$NON-NLS-1$
        JsonObject third = page(xml, second.get("nextOffset").getAsInt(), 20); //$NON-NLS-1$

        assertEquals("A", first.get("xml").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("\uD83D\uDE00", second.get("xml").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BC", third.get("xml").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(xml, first.get("xml").getAsString() + second.get("xml").getAsString() //$NON-NLS-1$ //$NON-NLS-2$
            + third.get("xml").getAsString()); //$NON-NLS-1$
        assertFalse(third.get("hasMore").getAsBoolean()); //$NON-NLS-1$
        assertFalse(third.has("nextOffset")); //$NON-NLS-1$

        JsonObject insidePair = page(xml, 2, 1);
        assertEquals(1, insidePair.get("offset").getAsInt()); //$NON-NLS-1$
        assertEquals("\uD83D\uDE00", insidePair.get("xml").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPageEnvelopeMeasuresJsonEscapingAndShrinksUntilGuardIsNoOp()
    {
        String xml = "\n\t".repeat(40_000); //$NON-NLS-1$

        String envelope = DcsXmlCodec.serializePageEnvelope(xml, HASH, 0, xml.length());
        JsonObject page = JsonParser.parseString(envelope).getAsJsonObject();

        assertTrue("the escaped full candidate must be shrunk", //$NON-NLS-1$
            page.get("xml").getAsString().length() < xml.length()); //$NON-NLS-1$
        assertEquals(page.get("xml").getAsString().length(), //$NON-NLS-1$
            page.get("nextOffset").getAsInt()); //$NON-NLS-1$
        assertTrue(envelope.length() <= OutputSizeGuard.MAX_CONTENT_CHARS);
        assertSame("the central guard must have nothing left to trim", //$NON-NLS-1$
            envelope, OutputSizeGuard.cap(envelope));
        assertFalse(envelope.contains("so the response stays under the size cap.")); //$NON-NLS-1$
    }

    @Test
    public void testMaximumXmlPageFitsGuardAfterWorstCaseUserSignalAugmentation()
    {
        String xml = "<".repeat(OutputSizeGuard.MAX_CONTENT_CHARS * 2); //$NON-NLS-1$
        String envelope = DcsXmlCodec.serializePageEnvelope(xml, HASH, 0, xml.length());
        JsonObject augmented = JsonParser.parseString(envelope).getAsJsonObject();
        JsonObject signal = new JsonObject();
        signal.addProperty("type", "BACKGROUND"); //$NON-NLS-1$ //$NON-NLS-2$
        signal.addProperty("message", "\u0000".repeat( //$NON-NLS-1$ //$NON-NLS-2$
            McpProtocolHandler.MAX_USER_SIGNAL_MESSAGE_CHARS));
        augmented.add("userSignal", signal); //$NON-NLS-1$
        String serialized = GsonProvider.toJson(augmented);

        assertEquals(McpProtocolHandler.MAX_USER_SIGNAL_JSON_AUGMENTATION_CHARS,
            serialized.length() - envelope.length());
        assertTrue(serialized.length() <= OutputSizeGuard.MAX_CONTENT_CHARS);
        assertSame("the central guard must not trim the worst-case augmented XML page", //$NON-NLS-1$
            serialized, OutputSizeGuard.cap(serialized));
    }

    @Test
    public void testEmptyDocumentEnvelopeHasNoNextOffset()
    {
        JsonObject empty = page("", 0, DcsXmlCodec.DEFAULT_CHUNK_CHARS); //$NON-NLS-1$

        assertEquals(0, empty.get("totalChars").getAsInt()); //$NON-NLS-1$
        assertEquals("", empty.get("xml").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(empty.get("hasMore").getAsBoolean()); //$NON-NLS-1$
        assertFalse(empty.has("nextOffset")); //$NON-NLS-1$
    }

    private static JsonObject page(String xml, int offset, int limit)
    {
        return JsonParser.parseString(DcsXmlCodec.serializePageEnvelope(xml, HASH, offset, limit))
            .getAsJsonObject();
    }
}
