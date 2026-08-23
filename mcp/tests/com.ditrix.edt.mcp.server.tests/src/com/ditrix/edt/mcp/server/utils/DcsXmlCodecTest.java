/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
    public void testMalformedXmlReturnsActionableError()
    {
        DcsXmlCodec codec = new DcsXmlCodec(new DcsV8Serializer((IResourceLookup)null), null);

        DcsXmlCodec.SchemaResult result = codec.deserialize("<DataCompositionSchema><dataSets>"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("body.xml")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("malformed")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("format='xml'")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("without editing or truncating")); //$NON-NLS-1$
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
