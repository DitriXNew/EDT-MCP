/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetPoint;
import com._1c.g5.v8.dt.moxel.content.ContentFactory;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SpreadsheetTemplateReaderTest
{
    private static final MoxelFactory MOXEL = MoxelFactory.eINSTANCE;

    @Test
    public void filterAndPageOverSparseCellsWithoutChangingModel()
    {
        SpreadsheetDocument doc = MOXEL.createSpreadsheetDocument();
        var write = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString("""
            {"cells":[{"row":0,"col":1,"text":"<LooksLikeParameter>"},
              {"row":1,"col":1,"parameter":"ДолжностьРуководителя"},
              {"row":1,"col":7,"text":"Outside"},
              {"row":2,"col":1,"bold":true}],
             "merges":[{"fromRow":0,"fromCol":1,"toRow":0,"toCol":3}],
             "areas":[{"name":"Header","fromRow":0,"fromCol":1,"toRow":2,"toCol":3}]}
             """).getAsJsonObject());
        assertFalse(write.hasError());
        var templateFormat = MOXEL.createFormat();
        templateFormat.setFillType(FillType.TEMPLATE);
        doc.getFormats().add(templateFormat);
        var mixed = MOXEL.createCell();
        var text = ContentFactory.eINSTANCE.createLocalString();
        text.getContent().put("ru", "Номер [НомерДокумента]");
        mixed.setText(text);
        mixed.setFormatIndex(doc.getFormats().size() - 1);
        doc.getRows().get(Integer.valueOf(2)).getCells().put(3, mixed);

        var before = EcoreUtil.copy(doc);
        JsonObject first = SpreadsheetTemplateReader.read(doc, "header", 0, 2);
        assertEquals("Header", first.get("areaName").getAsString());
        JsonObject literal = at(first, "cells", 0);
        assertEquals("text", literal.get("type").getAsString());
        assertEquals("<LooksLikeParameter>", literal.get("text").getAsString());
        JsonObject param = at(first, "cells", 1);
        assertEquals("parameter", param.get("type").getAsString());
        assertEquals("ДолжностьРуководителя", param.get("parameter").getAsString());
        assertEquals(3, at(first, "merges", 0).get("toCol").getAsInt());
        assertEquals(2, first.get("nextOffset").getAsInt());
        JsonObject last = SpreadsheetTemplateReader.read(doc, "Header", 2, 2);
        assertEquals(1, last.getAsJsonArray("cells").size());
        assertEquals("template", at(last, "cells", 0).get("type").getAsString());
        assertEquals("Номер [НомерДокумента]", at(last, "cells", 0).get("text").getAsString());
        assertFalse(last.has("nextOffset"));
        assertTrue(EcoreUtil.equals(before, doc));
    }

    @Test
    public void readWholeAxisAreasAndNamedDrawingAnchors()
    {
        var doc = MOXEL.createSpreadsheetDocument();
        var rows = MOXEL.createRowsArea();
        rows.setBegin(2);
        rows.setEnd(5);
        var rowName = MOXEL.createNamedItemCells();
        rowName.setArea(rows);
        doc.getNamedItems().put("Rows", rowName);
        var cols = MOXEL.createColumnsArea();
        cols.setBegin(7);
        cols.setEnd(8);
        var colName = MOXEL.createNamedItemCells();
        colName.setArea(cols);
        doc.getNamedItems().put("Columns", colName);
        var box = MOXEL.createPictureDrawing();
        box.setDrawingId(7);
        var anchor = MOXEL.createSpreadsheetRect();
        anchor.setBegin(point(4, 2));
        anchor.setEnd(point(6, 3));
        box.setPosition(anchor);
        doc.getDrawings().add(box);
        var alias = MOXEL.createNamedItemDrawing();
        alias.setDrawingId(7);
        doc.getNamedItems().put("Logo", alias);

        var result = SpreadsheetTemplateReader.read(doc, "rows", 0, 10);
        assertEquals("rows", at(result, "areas", 0).get("type").getAsString());
        assertFalse(at(result, "areas", 0).has("fromCol"));
        JsonObject drawing = at(result, "drawings", 0);
        assertEquals("Logo", drawing.get("name").getAsString());
        assertEquals(2, drawing.getAsJsonObject("anchor").get("fromCol").getAsInt());
        var outside = SpreadsheetTemplateReader.read(doc, "Columns", 0, 10);
        assertTrue(outside.getAsJsonArray("drawings").isEmpty());
        assertEquals(8, at(outside, "areas", 0).get("toCol").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetTemplateReader.read(doc, "Logo", 0, 10));
    }

    @Test
    public void emptyAndOversizeTextHaveExplicitShapes()
    {
        assertTrue(SpreadsheetTemplateReader.read(null, null, 0, 1).getAsJsonArray("cells").isEmpty());
        var doc = MOXEL.createSpreadsheetDocument();
        var row = MOXEL.createRow();
        var cell = MOXEL.createCell();
        var text = ContentFactory.eINSTANCE.createLocalString();
        text.getContent().put("#", "a".repeat(2047) + "😀");
        cell.setText(text);
        row.getCells().put(0, cell);
        doc.getRows().put(0, row);
        var read = at(SpreadsheetTemplateReader.read(doc, null, 0, 1), "cells", 0);
        assertEquals(2047, read.get("text").getAsString().length());
        assertTrue(read.get("textTruncated").getAsBoolean());
    }

    @Test
    public void largeEscapedPayloadFitsAndPagesEveryCollectionWithoutSkipping()
    {
        var doc = MOXEL.createSpreadsheetDocument();
        var row = MOXEL.createRow();
        doc.getRows().put(0, row);
        String longText = String.valueOf((char)1).repeat(2048);
        for (int i = 0; i < 21; i++)
        {
            var cell = MOXEL.createCell();
            var text = ContentFactory.eINSTANCE.createLocalString();
            text.getContent().put("#", longText);
            cell.setText(text);
            row.getCells().put(i, cell);
            var merge = MOXEL.createMerge();
            var range = MOXEL.createRect();
            range.setX(i);
            range.setHeight(1);
            merge.setPosition(range);
            doc.getMerges().add(merge);
        }
        int offset = 0;
        do
        {
            var result = SpreadsheetTemplateReader.read(doc, null, offset, 200);
            // The actual escaped JSON length must leave room for the tool's outer result envelope.
            assertTrue(result.toString().length() < OutputSizeGuard.MAX_CONTENT_CHARS - 1000);
            int count = result.getAsJsonArray("cells").size();
            assertTrue(count > 0 && count < 21);
            assertEquals(count, result.getAsJsonArray("merges").size());
            for (int i = 0; i < count; i++)
            {
                assertEquals(offset + i, at(result, "cells", i).get("col").getAsInt());
                assertEquals(offset + i, at(result, "merges", i).get("fromCol").getAsInt());
            }
            offset += count;
            if (result.has("nextOffset"))
            {
                assertEquals(offset, result.get("nextOffset").getAsInt());
            }
            else
            {
                assertEquals(21, offset);
            }
        }
        while (offset < 21);
    }

    private static SpreadsheetPoint point(int row, int col)
    {
        var value = McoreFactory.eINSTANCE.createPoint();
        value.setX(col);
        value.setY(row);
        var point = MOXEL.createSpreadsheetPoint();
        point.setCell(value);
        return point;
    }

    private static JsonObject at(JsonObject object, String name, int index)
    {
        return object.getAsJsonArray(name).get(index).getAsJsonObject();
    }
}
