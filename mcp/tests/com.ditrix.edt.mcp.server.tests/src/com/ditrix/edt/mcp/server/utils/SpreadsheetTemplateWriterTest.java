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

import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.NamedItem;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com._1c.g5.v8.dt.moxel.content.HorizontalAlignment;
import com._1c.g5.v8.dt.moxel.content.TextPlacement;
import com._1c.g5.v8.dt.moxel.content.VerticalAlignment;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter.CellPlan;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter.ParseResult;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests {@link SpreadsheetTemplateWriter}: the pure spec parse / enum resolution / validation (no model),
 * and the typed moxel write onto an in-memory {@link SpreadsheetDocument} built with
 * {@code MoxelFactory.eINSTANCE} - cells land at the right sparse index, text / parameter are set, fonts
 * and formats are pool-interned (identical formatting deduped to one pool entry), a merge / named area is
 * added with the platform's delta {@link Rect} encoding, column widths / row heights land on a
 * format-indexed size, and a bad enum / malformed entry is rejected before anything is written.
 */
public class SpreadsheetTemplateWriterTest
{
    private static JsonObject json(String s)
    {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static SpreadsheetDocument newDocument()
    {
        return MoxelFactory.eINSTANCE.createSpreadsheetDocument();
    }

    private static Cell cellAt(SpreadsheetDocument doc, int row, int col)
    {
        Row r = doc.getRows().get(Integer.valueOf(row));
        assertNotNull("expected a row at " + row, r); //$NON-NLS-1$
        Cell c = r.getCells().get(Integer.valueOf(col));
        assertNotNull("expected a cell at (" + row + "," + col + ")", c); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return c;
    }

    private static Format formatOf(SpreadsheetDocument doc, Cell cell)
    {
        return doc.getFormats().get(cell.getFormatIndex());
    }

    // ==================== cells: content ====================

    @Test
    public void testTextCellLandsAtSparseIndexWithText()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":2,\"col\":3,\"text\":\"Total\"}]}")); //$NON-NLS-1$
        assertFalse("a valid text cell must not error: " + r.error, r.hasError()); //$NON-NLS-1$
        assertEquals(1, r.cells);
        Cell cell = cellAt(doc, 2, 3);
        assertNotNull("a text cell must have a LocalString", cell.getText()); //$NON-NLS-1$
        // v1 stores text under the platform's language-neutral key "#" (SheetFactory.DEFAULT_LANGUAGE),
        // the key the moxel text reader falls back to for any viewing language - NOT the empty key, which
        // the reader never resolves (the cell would render blank).
        assertEquals("Total", cell.getText().getContent().get("#")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a text cell must carry no parameter", cell.getParameter()); //$NON-NLS-1$
    }

    @Test
    public void testTextCellUsesNeutralKeyWhenNoLanguage()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"X\"}]}")); //$NON-NLS-1$
        Cell cell = cellAt(doc, 0, 0);
        // The platform-readable neutral key is "#" (SheetFactory.DEFAULT_LANGUAGE), not the empty key.
        assertEquals("X", cell.getText().getContent().get("#")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("text must NOT be stored under the empty key (never read by the platform)", //$NON-NLS-1$
            cell.getText().getContent().get("")); //$NON-NLS-1$
    }

    @Test
    public void testOverwriteParameterCellWithTextClearsParameterFillAndText()
    {
        SpreadsheetDocument doc = newDocument();
        // 1) author a bold PARAMETER cell (format carries FillType.PARAMETER at a non-zero pool index).
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"parameter\":\"Amount\",\"bold\":true}]}")); //$NON-NLS-1$
        Cell param = cellAt(doc, 0, 0);
        assertEquals("Amount", param.getParameter()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FillType.PARAMETER, formatOf(doc, param).getFillType());

        // 2) overwrite the SAME (row, col) with a plain-text spec: a TRUE REPLACE must clear the
        // parameter, its stale PARAMETER fill and any prior styling - else the text renders blank because
        // the moxel reader dispatches on the format's FillType.
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Total\"}]}")); //$NON-NLS-1$
        Cell cell = cellAt(doc, 0, 0);
        assertNull("overwriting with text must clear the parameter", cell.getParameter()); //$NON-NLS-1$
        assertEquals("Total", cell.getText().getContent().get("#")); //$NON-NLS-1$ //$NON-NLS-2$
        // The overwritten plain-text cell must fall back to the reserved neutral base format (index 0),
        // whose fill is NOT PARAMETER - so getCellText reads the text rather than the null parameter.
        assertEquals("a plain-text overwrite must reset to the neutral base format", //$NON-NLS-1$
            0, cell.getFormatIndex());
        assertTrue("the overwritten text cell must not keep a PARAMETER fill", //$NON-NLS-1$
            formatOf(doc, cell).getFillType() != FillType.PARAMETER);
    }

    @Test
    public void testParameterCellSetsParameterAndParameterFill()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"parameter\":\"Amount\"}]}")); //$NON-NLS-1$
        assertFalse(r.hasError());
        Cell cell = cellAt(doc, 0, 0);
        assertEquals("Amount", cell.getParameter()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a parameter cell carries no literal text", cell.getText()); //$NON-NLS-1$
        // The fillType=PARAMETER on the cell's format is what makes EDT substitute the parameter.
        assertEquals(FillType.PARAMETER, formatOf(doc, cell).getFillType());
    }

    @Test
    public void testTextAndParameterTogetherIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"a\",\"parameter\":\"b\"}]}")); //$NON-NLS-1$
        assertTrue("text + parameter together must error", r.hasError()); //$NON-NLS-1$
    }

    // ==================== cells: formatting + interning ====================

    @Test
    public void testFormattingBuildsFontAndFormatWithCorrectValues()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc, json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\"," //$NON-NLS-1$
            + "\"bold\":true,\"fontSize\":12,\"hAlign\":\"center\",\"vAlign\":\"top\",\"wrap\":true}]}")); //$NON-NLS-1$
        Cell cell = cellAt(doc, 0, 0);
        Format fmt = formatOf(doc, cell);
        assertEquals(HorizontalAlignment.CENTER, fmt.getHorizontalAlignment());
        assertEquals(VerticalAlignment.TOP, fmt.getVerticalAlignment());
        assertEquals(TextPlacement.WRAP, fmt.getTextPlacement());
        Font font = doc.getFonts().get(fmt.getFont());
        assertTrue("font must be bold", font.bold()); //$NON-NLS-1$
        assertEquals(12, font.height());
    }

    @Test
    public void testStyledFormatNeverLandsAtIndexZero()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"bold\":true}]}")); //$NON-NLS-1$
        Cell cell = cellAt(doc, 0, 0);
        assertTrue("a styled cell must not reuse the neutral index 0", cell.getFormatIndex() > 0); //$NON-NLS-1$
        // Index 0 stays neutral (no font set), so an un-formatted cell (default formatIndex 0) is unstyled.
        assertFalse("the reserved base format must have no font", //$NON-NLS-1$
            doc.getFormats().get(0).isSetFont());
    }

    @Test
    public void testIdenticalFormatsAreDedupedToOnePoolEntry()
    {
        SpreadsheetDocument doc = newDocument();
        // Two cells with identical formatting, one with a different font size.
        SpreadsheetTemplateWriter.apply(doc, json("{\"cells\":[" //$NON-NLS-1$
            + "{\"row\":0,\"col\":0,\"text\":\"A\",\"bold\":true,\"fontSize\":12,\"hAlign\":\"center\"}," //$NON-NLS-1$
            + "{\"row\":0,\"col\":1,\"text\":\"B\",\"bold\":true,\"fontSize\":12,\"hAlign\":\"center\"}," //$NON-NLS-1$
            + "{\"row\":1,\"col\":0,\"text\":\"C\",\"fontSize\":14}]}")); //$NON-NLS-1$
        Cell a = cellAt(doc, 0, 0);
        Cell b = cellAt(doc, 0, 1);
        Cell c = cellAt(doc, 1, 0);
        assertEquals("two identical-format cells must share one format index", //$NON-NLS-1$
            a.getFormatIndex(), b.getFormatIndex());
        assertTrue("a different-format cell must get its own format index", //$NON-NLS-1$
            c.getFormatIndex() != a.getFormatIndex());
        // Pools: two distinct fonts (bold-12, plain-14); formats = base(0) + the two distinct formats.
        assertEquals("fonts pool must be deduped to the two distinct fonts", 2, doc.getFonts().size()); //$NON-NLS-1$
        assertEquals("formats pool must be base + two distinct formats", 3, doc.getFormats().size()); //$NON-NLS-1$
    }

    @Test
    public void testPlainTextCellGetsNoFormat()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\"}]}")); //$NON-NLS-1$
        // No styling -> only the reserved neutral base format exists, and the cell keeps default index 0.
        assertEquals(1, doc.getFormats().size());
        assertEquals(0, cellAt(doc, 0, 0).getFormatIndex());
    }

    // ==================== merges ====================

    @Test
    public void testMergeAddedWithDeltaRect()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"merges\":[{\"fromRow\":1,\"fromCol\":1,\"toRow\":2,\"toCol\":3}]}")); //$NON-NLS-1$
        assertFalse(r.hasError());
        assertEquals(1, doc.getMerges().size());
        Merge merge = doc.getMerges().get(0);
        Rect rect = merge.getPosition();
        // x=leftCol, y=topRow, width=right-left, height=bottom-top (the platform delta encoding).
        assertEquals(1, rect.getX());
        assertEquals(1, rect.getY());
        assertEquals(2, rect.getWidth());
        assertEquals(1, rect.getHeight());
    }

    @Test
    public void testSingleCellMergeIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"merges\":[{\"fromRow\":1,\"fromCol\":1,\"toRow\":1,\"toCol\":1}]}")); //$NON-NLS-1$
        assertTrue("a single-cell merge must error", r.hasError()); //$NON-NLS-1$
    }

    // ==================== named areas ====================

    @Test
    public void testNamedAreaRegisteredByName()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"areas\":[{\"name\":\"Header\"," //$NON-NLS-1$
            + "\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":2}]}")); //$NON-NLS-1$
        assertFalse(r.hasError());
        assertEquals(1, r.areas);
        NamedItem item = doc.getNamedItems().get("Header"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the named area must be registered by name", item); //$NON-NLS-1$
        assertTrue("a named cell area must be a NamedItemCells", item instanceof NamedItemCells); //$NON-NLS-1$
        assertTrue("the area must be a RectArea", //$NON-NLS-1$
            ((NamedItemCells)item).getArea() instanceof RectArea);
        Rect rect = ((RectArea)((NamedItemCells)item).getArea()).getPosition();
        assertEquals(0, rect.getX());
        assertEquals(2, rect.getWidth());
    }

    // ==================== column widths / row heights ====================

    @Test
    public void testColumnWidthAndRowHeightLandOnFormat()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":0,\"width\":90}]," //$NON-NLS-1$
            + "\"rowHeights\":[{\"row\":5,\"height\":20}]}")); //$NON-NLS-1$
        assertFalse(r.hasError());
        assertEquals(1, r.columnWidths);
        assertEquals(1, r.rowHeights);
        assertNotNull("the column band must exist", doc.getColumns()); //$NON-NLS-1$
        com._1c.g5.v8.dt.moxel.Column column = doc.getColumns().getColumns().get(Integer.valueOf(0));
        assertNotNull("the sized column must exist", column); //$NON-NLS-1$
        assertEquals(90, doc.getFormats().get(column.getFormatIndex()).getWidth());
        Row row = doc.getRows().get(Integer.valueOf(5));
        assertNotNull("the sized row must exist", row); //$NON-NLS-1$
        assertEquals(20, doc.getFormats().get(row.getFormatIndex()).getHeight());
    }

    // ==================== cells: text rotation / auto indent / auto-mark-incomplete ==============
    // Every Format attribute is UNSETTABLE and V8MoxelSerializer writes each one only when its
    // isSetXxx() holds, so "the feature is set to V" IS the serialization precondition; the exact
    // on-disk element is pinned by tests/e2e/tools/test_modify_metadata_template.py.

    @Test
    public void testTextOrientationLandsOnTheCellFormatInTenthsOfADegree()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Qty\",\"textOrientation\":900}]}")); //$NON-NLS-1$
        assertFalse("a valid rotation must not error: " + r.error, r.hasError()); //$NON-NLS-1$
        Format fmt = formatOf(doc, cellAt(doc, 0, 0));
        assertTrue("the rotation must be SET (the serializer writes it only when isSet)", //$NON-NLS-1$
            fmt.isSetTextOrientation());
        // The platform unit is TENTHS of a degree: 900 IS 90 degrees. The wrong spelling - passing the
        // degree value through - would store 90, so pin that it did NOT happen.
        assertEquals(900, fmt.getTextOrientation());
        assertFalse("90 would be 9 degrees: the value must not be re-scaled", //$NON-NLS-1$
            fmt.getTextOrientation() == 90);
    }

    @Test
    public void testOmittedTextOrientationLeavesTheFeatureUnset()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"bold\":true}]}")); //$NON-NLS-1$
        Format fmt = formatOf(doc, cellAt(doc, 0, 0));
        // Unset != 0: an omitted key must leave the cell inheriting the row / column / document rotation,
        // so the serializer emits no <textOrientation> at all.
        assertFalse("an omitted 'textOrientation' must leave the feature UNSET", //$NON-NLS-1$
            fmt.isSetTextOrientation());
    }

    @Test
    public void testExplicitNullTextOrientationCountsAsOmitted()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"textOrientation\":null}]}")); //$NON-NLS-1$
        assertFalse("an explicit null must be accepted as 'absent': " + r.error, r.hasError()); //$NON-NLS-1$
        // Absent styling -> the cell keeps the neutral base format (index 0) and no format is interned.
        assertEquals("an explicit null must not intern a styled format", 1, doc.getFormats().size()); //$NON-NLS-1$
        assertEquals(0, cellAt(doc, 0, 0).getFormatIndex());
        assertFalse(doc.getFormats().get(0).isSetTextOrientation());
    }

    @Test
    public void testZeroTextOrientationIsAnExplicitValueNotAnOmission()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"textOrientation\":0}]}")); //$NON-NLS-1$
        Cell cell = cellAt(doc, 0, 0);
        // 0 means "explicitly horizontal", which OVERRIDES a rotation inherited from the row / column
        // format - so it must intern its own format rather than fall back to the neutral base (index 0).
        assertTrue("an explicit 0 must not reuse the neutral base format", cell.getFormatIndex() > 0); //$NON-NLS-1$
        Format fmt = formatOf(doc, cell);
        assertTrue("an explicit 0 must be SET, not left unset", fmt.isSetTextOrientation()); //$NON-NLS-1$
        assertEquals(0, fmt.getTextOrientation());
    }

    @Test
    public void testAutoIndentLandsOnTheCellFormat()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"autoIndent\":4}]}")); //$NON-NLS-1$
        assertFalse("a valid autoIndent must not error: " + r.error, r.hasError()); //$NON-NLS-1$
        Format fmt = formatOf(doc, cellAt(doc, 0, 0));
        assertTrue(fmt.isSetAutoIndent());
        assertEquals(4, fmt.getAutoIndent());
        assertFalse("autoIndent must not leak into the rotation", fmt.isSetTextOrientation()); //$NON-NLS-1$
    }

    @Test
    public void testAutoMarkIncompleteFalseIsAnExplicitValue()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"autoMarkIncomplete\":false}]}")); //$NON-NLS-1$
        Cell cell = cellAt(doc, 0, 0);
        assertTrue("an explicit false must intern its own format", cell.getFormatIndex() > 0); //$NON-NLS-1$
        Format fmt = formatOf(doc, cell);
        // false-and-SET is a real value overriding an inherited true; false-and-UNSET says nothing.
        assertTrue("an explicit false must be SET", fmt.isSetAutoMarkIncomplete()); //$NON-NLS-1$
        assertFalse(fmt.isAutoMarkIncomplete());
    }

    @Test
    public void testAutoMarkIncompleteTrueLandsOnTheCellFormat()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"autoMarkIncomplete\":true}]}")); //$NON-NLS-1$
        Format fmt = formatOf(doc, cellAt(doc, 0, 0));
        assertTrue(fmt.isSetAutoMarkIncomplete());
        assertTrue(fmt.isAutoMarkIncomplete());
    }

    @Test
    public void testIdenticalRotatedCellsShareOneInternedFormat()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc, json("{\"cells\":[" //$NON-NLS-1$
            + "{\"row\":0,\"col\":0,\"text\":\"A\",\"textOrientation\":900}," //$NON-NLS-1$
            + "{\"row\":0,\"col\":1,\"text\":\"B\",\"textOrientation\":900}," //$NON-NLS-1$
            + "{\"row\":0,\"col\":2,\"text\":\"C\",\"textOrientation\":450}]}")); //$NON-NLS-1$
        assertEquals("two identically-rotated cells must share one pool entry", //$NON-NLS-1$
            cellAt(doc, 0, 0).getFormatIndex(), cellAt(doc, 0, 1).getFormatIndex());
        assertTrue("a differently-rotated cell must get its own pool entry", //$NON-NLS-1$
            cellAt(doc, 0, 2).getFormatIndex() != cellAt(doc, 0, 0).getFormatIndex());
        assertEquals("formats must be base + the two distinct rotations", 3, doc.getFormats().size()); //$NON-NLS-1$
    }

    // ==================== column widths: auto width + weight factor ==============================

    @Test
    public void testAutoWidthCalculationAndWeightFactorLandOnTheColumnFormat()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":1," //$NON-NLS-1$
            + "\"width\":50,\"autoWidthCalculation\":true,\"widthWeightFactor\":3}]}")); //$NON-NLS-1$
        assertFalse("a valid auto-width column must not error: " + r.error, r.hasError()); //$NON-NLS-1$
        assertEquals(1, r.columnWidths);
        com._1c.g5.v8.dt.moxel.Column column = doc.getColumns().getColumns().get(Integer.valueOf(1));
        assertNotNull("the sized column must exist", column); //$NON-NLS-1$
        Format fmt = doc.getFormats().get(column.getFormatIndex());
        // The platform reads both ONLY from the COLUMN's format (SheetAccessor.isAutoWidthCalculation /
        // getWidthWeightFactor), so this is the format they must land on.
        assertTrue(fmt.isSetAutoWidthCalculation());
        assertTrue(fmt.isAutoWidthCalculation());
        assertTrue(fmt.isSetWidthWeightFactor());
        assertEquals(3, fmt.getWidthWeightFactor());
        assertEquals("an explicit width stays as the auto-width minimum", 50, fmt.getWidth()); //$NON-NLS-1$
    }

    @Test
    public void testColumnMayCarryAutoWidthWithoutAnExplicitWidth()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"autoWidthCalculation\":true}]}")); //$NON-NLS-1$
        assertFalse("auto width alone must be accepted: " + r.error, r.hasError()); //$NON-NLS-1$
        com._1c.g5.v8.dt.moxel.Column column = doc.getColumns().getColumns().get(Integer.valueOf(0));
        Format fmt = doc.getFormats().get(column.getFormatIndex());
        assertTrue(fmt.isAutoWidthCalculation());
        assertFalse("no 'width' was given, so the width must stay UNSET", fmt.isSetWidth()); //$NON-NLS-1$
    }

    @Test
    public void testAutoWidthCalculationFalseIsAnExplicitValue()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"width\":40,\"autoWidthCalculation\":false}]}")); //$NON-NLS-1$
        com._1c.g5.v8.dt.moxel.Column column = doc.getColumns().getColumns().get(Integer.valueOf(0));
        Format fmt = doc.getFormats().get(column.getFormatIndex());
        assertTrue("an explicit false must be SET", fmt.isSetAutoWidthCalculation()); //$NON-NLS-1$
        assertFalse(fmt.isAutoWidthCalculation());
    }

    @Test
    public void testPlainColumnWidthLeavesTheAutoWidthMembersUnset()
    {
        SpreadsheetDocument doc = newDocument();
        SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":0,\"width\":40}]}")); //$NON-NLS-1$
        com._1c.g5.v8.dt.moxel.Column column = doc.getColumns().getColumns().get(Integer.valueOf(0));
        Format fmt = doc.getFormats().get(column.getFormatIndex());
        assertFalse(fmt.isSetAutoWidthCalculation());
        assertFalse(fmt.isSetWidthWeightFactor());
    }

    @Test
    public void testNullCellOnlyKeyOnAColumnEntryCountsAsOmitted()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"width\":40,\"textOrientation\":null}]}")); //$NON-NLS-1$
        assertFalse("an explicit null is omitted, not misplaced: " + r.error, r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testNullColumnOnlyKeyOnACellEntryCountsAsOmitted()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json(
            "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"autoWidthCalculation\":null,\"widthWeightFactor\":null}]}")); //$NON-NLS-1$
        assertFalse("an explicit null is omitted, not misplaced: " + r.error, r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testCellOnlyKeyOnAColumnEntryIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"width\":40,\"textOrientation\":900}]}")); //$NON-NLS-1$
        assertTrue(r.hasError());
        assertTrue(r.error, r.error.contains("'textOrientation', which is a CELL format property")); //$NON-NLS-1$
    }

    @Test
    public void testColumnOnlyKeyOnACellEntryIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"autoWidthCalculation\":false}]}")); //$NON-NLS-1$
        assertTrue(r.hasError());
        assertTrue(r.error, r.error.contains("'autoWidthCalculation', which is a COLUMN property")); //$NON-NLS-1$
    }

    // ==================== grid extent (declared sheet bounds) ====================

    @Test
    public void testGridExtentGrowsToCoverAuthoredCells()
    {
        SpreadsheetDocument doc = newDocument();
        // A fresh document starts at height 0 with no column band; authoring sparse cells up to row 4 /
        // col 3 must grow BOTH declared bounds so the cells fall inside the sheet after a reopen.
        assertEquals("a fresh document starts with height 0", 0, doc.getHeight()); //$NON-NLS-1$
        SpreadsheetTemplateWriter.apply(doc, json("{\"cells\":[" //$NON-NLS-1$
            + "{\"row\":0,\"col\":0,\"text\":\"A\"}," //$NON-NLS-1$
            + "{\"row\":1,\"col\":3,\"text\":\"B\"}," //$NON-NLS-1$
            + "{\"row\":4,\"col\":1,\"text\":\"C\"}]}")); //$NON-NLS-1$
        assertEquals("height must cover the last authored row (4) + 1", 5, doc.getHeight()); //$NON-NLS-1$
        assertNotNull("authoring cells must create the column band", doc.getColumns()); //$NON-NLS-1$
        assertEquals("columns size must cover the last authored column (3) + 1", //$NON-NLS-1$
            4, doc.getColumns().getSize());
    }

    @Test
    public void testGridExtentCoversMergeAndAreaFarCorner()
    {
        SpreadsheetDocument doc = newDocument();
        // No cells at all - the extent must still be grown from the far corner of a merge and a named area.
        SpreadsheetTemplateWriter.apply(doc, json("{" //$NON-NLS-1$
            + "\"merges\":[{\"fromRow\":5,\"fromCol\":1,\"toRow\":8,\"toCol\":6}]," //$NON-NLS-1$
            + "\"areas\":[{\"name\":\"Body\",\"fromRow\":0,\"fromCol\":0,\"toRow\":2,\"toCol\":4}]}")); //$NON-NLS-1$
        assertEquals("height must cover the merge's far row (8) + 1", 9, doc.getHeight()); //$NON-NLS-1$
        assertNotNull("a merge/area must create the column band", doc.getColumns()); //$NON-NLS-1$
        assertEquals("columns size must cover the merge's far column (6) + 1", //$NON-NLS-1$
            7, doc.getColumns().getSize());
    }

    @Test
    public void testGridExtentNeverShrinksAnExistingExtent()
    {
        SpreadsheetDocument doc = newDocument();
        // A pre-existing (designer-authored) extent wider than the newly authored content must be preserved:
        // the extent only ever grows.
        doc.setHeight(20);
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":1,\"col\":0,\"text\":\"A\"}]}")); //$NON-NLS-1$
        assertEquals("a wider pre-existing height must not shrink", 20, doc.getHeight()); //$NON-NLS-1$
    }

    @Test
    public void testAuthoredRowHeightGrowsRowExtent()
    {
        SpreadsheetDocument doc = newDocument();
        // The row-height path used to leave the row extent unset even though a row is authored: a row height
        // at row 5 must still push the declared height to 6 (the symmetric counterpart to a column width).
        SpreadsheetTemplateWriter.apply(doc,
            json("{\"rowHeights\":[{\"row\":5,\"height\":20}]}")); //$NON-NLS-1$
        assertEquals("a row height must grow the declared row extent", 6, doc.getHeight()); //$NON-NLS-1$
    }

    // ==================== errors ====================

    @Test
    public void testBadHorizontalAlignmentEnumIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"hAlign\":\"sideways\"}]}")); //$NON-NLS-1$
        assertTrue("a bad enum token must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the offending token", r.error.contains("sideways")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must list the valid tokens", r.error.contains("CENTER")); //$NON-NLS-1$ //$NON-NLS-2$
        // A rejected spec must not have mutated the model.
        assertTrue("nothing must be written on a validation error", doc.getRows().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testBadWrapTokenIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"wrap\":\"maybe\"}]}")); //$NON-NLS-1$
        assertTrue("a bad wrap token must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testMissingRowIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"col\":0,\"text\":\"A\"}]}")); //$NON-NLS-1$
        assertTrue("a cell without a row index must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testEmptyPayloadIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{}")); //$NON-NLS-1$
        assertTrue("an empty template payload must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testNullDocumentIsError()
    {
        Result r = SpreadsheetTemplateWriter.apply(null,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\"}]}")); //$NON-NLS-1$
        assertTrue("a null document must error", r.hasError()); //$NON-NLS-1$
    }

    @Test
    public void testEmptyCellIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0}]}")); //$NON-NLS-1$
        assertTrue("a cell with no content or formatting must error", r.hasError()); //$NON-NLS-1$
    }

    // ==================== errors: the new formatting keys ========================================

    @Test
    public void testTextOrientationAboveTheMaximumIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"textOrientation\":3601}]}")); //$NON-NLS-1$
        assertTrue("a rotation past 360 degrees must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the offending value: " + r.error, r.error.contains("3601")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must name the range: " + r.error, r.error.contains("0..3600")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must name the platform unit: " + r.error, //$NON-NLS-1$
            r.error.contains("tenths of a degree")); //$NON-NLS-1$
        assertTrue("nothing must be written on a validation error", doc.getRows().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testNegativeTextOrientationIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"textOrientation\":-1}]}")); //$NON-NLS-1$
        assertTrue("a negative rotation must error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("-1")); //$NON-NLS-1$
    }

    @Test
    public void testAutoIndentAboveTheMaximumIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"autoIndent\":101}]}")); //$NON-NLS-1$
        assertTrue("an autoIndent past 100 must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the offending value: " + r.error, r.error.contains("101")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must name the range: " + r.error, r.error.contains("0..100")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonBooleanAutoMarkIncompleteIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"autoMarkIncomplete\":\"maybe\"}]}")); //$NON-NLS-1$
        assertTrue("a non-boolean autoMarkIncomplete must error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("maybe")); //$NON-NLS-1$
    }

    @Test
    public void testWidthWeightFactorWithoutAutoWidthCalculationIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        // The platform shares the free width out by weight ONLY across auto-width columns
        // (PositionHolder.recalcDynamicColumnWidths reads getWidthWeightFactor only for those), and this
        // call REPLACES the column's format - so a lone weight factor would be written where nothing
        // reads it. That is the accepted-but-ignored trap, so it is refused.
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"width\":40,\"widthWeightFactor\":3}]}")); //$NON-NLS-1$
        assertTrue("a weight factor without auto width must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the offending value: " + r.error, r.error.contains("3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must name the fix: " + r.error, //$NON-NLS-1$
            r.error.contains("autoWidthCalculation")); //$NON-NLS-1$
        assertNull("nothing must be written on a validation error", doc.getColumns()); //$NON-NLS-1$
    }

    @Test
    public void testWidthWeightFactorWithAutoWidthCalculationFalseIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":0," //$NON-NLS-1$
            + "\"autoWidthCalculation\":false,\"widthWeightFactor\":3}]}")); //$NON-NLS-1$
        assertTrue("auto width explicitly OFF must refuse a weight factor too", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("autoWidthCalculation")); //$NON-NLS-1$
    }

    @Test
    public void testNegativeWidthWeightFactorIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":0," //$NON-NLS-1$
            + "\"autoWidthCalculation\":true,\"widthWeightFactor\":-2}]}")); //$NON-NLS-1$
        assertTrue("a negative weight factor must error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("-2")); //$NON-NLS-1$
    }

    @Test
    public void testColumnWidthEntryWithNoSizingMemberIsError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":0}]}")); //$NON-NLS-1$
        assertTrue("a column entry with no sizing member must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must still name 'width': " + r.error, r.error.contains("width")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonPositiveColumnWidthIsStillError()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"width\":0}]}")); //$NON-NLS-1$
        assertTrue("a zero width must still error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("positive integer 'width'")); //$NON-NLS-1$
    }

    @Test
    public void testColumnOnlyKeyOnACellIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        // A cell's format is NEVER consulted for auto width calculation, so accepting it would report
        // success for a value the platform never reads.
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"cells\":[{\"row\":0,\"col\":0," //$NON-NLS-1$
            + "\"text\":\"A\",\"autoWidthCalculation\":true}]}")); //$NON-NLS-1$
        assertTrue("a column property on a cell must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the bad key: " + r.error, //$NON-NLS-1$
            r.error.contains("autoWidthCalculation")); //$NON-NLS-1$
        assertTrue("the error must name the fix: " + r.error, r.error.contains("columnWidths")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing must be written on a validation error", doc.getRows().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testWidthWeightFactorOnACellIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"cells\":[{\"row\":0,\"col\":0," //$NON-NLS-1$
            + "\"text\":\"A\",\"widthWeightFactor\":2}]}")); //$NON-NLS-1$
        assertTrue("a width weight factor on a cell must error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("columnWidths")); //$NON-NLS-1$
    }

    @Test
    public void testCellOnlyKeyOnAColumnWidthIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":0," //$NON-NLS-1$
            + "\"width\":40,\"textOrientation\":900}]}")); //$NON-NLS-1$
        assertTrue("a cell property on a column entry must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the bad key: " + r.error, //$NON-NLS-1$
            r.error.contains("textOrientation")); //$NON-NLS-1$
        assertTrue("the error must name the fix: " + r.error, r.error.contains("cells")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("nothing must be written on a validation error", doc.getColumns()); //$NON-NLS-1$
    }

    @Test
    public void testCellOnlyKeyOnARowHeightIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"rowHeights\":[{\"row\":0," //$NON-NLS-1$
            + "\"height\":20,\"autoIndent\":3}]}")); //$NON-NLS-1$
        assertTrue("a cell property on a row entry must error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("autoIndent")); //$NON-NLS-1$
        assertTrue(r.error.contains("cells")); //$NON-NLS-1$
        assertTrue("nothing must be written on a validation error", doc.getRows().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testColumnOnlyKeyOnARowHeightIsRefused()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"rowHeights\":[{\"row\":0," //$NON-NLS-1$
            + "\"height\":20,\"widthWeightFactor\":2}]}")); //$NON-NLS-1$
        assertTrue("a column property on a row entry must error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("columnWidths")); //$NON-NLS-1$
    }

    // ==================== the 8.3.10 floor on the column-sizing members =========================
    // V8MoxelSerializer writes autoWidthCalculation / widthWeightFactor only for a project on 8.3.10+
    // (and its reader drops them again below that), so on an older project they would be accepted,
    // mutated into the model and then silently discarded by the export.

    @Test
    public void testAutoWidthCalculationIsRefusedBelow8310()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"autoWidthCalculation\":true}]}"), Version.V8_3_9); //$NON-NLS-1$
        assertTrue("auto width on a pre-8.3.10 project must error", r.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the key: " + r.error, //$NON-NLS-1$
            r.error.contains("autoWidthCalculation")); //$NON-NLS-1$
        assertTrue("the error must name the floor: " + r.error, r.error.contains("8.3.10")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must name the project's version: " + r.error, //$NON-NLS-1$
            r.error.contains("8.3.9")); //$NON-NLS-1$
        assertNull("nothing must be written on a validation error", doc.getColumns()); //$NON-NLS-1$
    }

    @Test
    public void testWidthWeightFactorIsRefusedBelow8310()
    {
        SpreadsheetDocument doc = newDocument();
        Result r = SpreadsheetTemplateWriter.apply(doc, json("{\"columnWidths\":[{\"col\":0," //$NON-NLS-1$
            + "\"autoWidthCalculation\":true,\"widthWeightFactor\":2}]}"), Version.V8_3_8); //$NON-NLS-1$
        assertTrue("a weight factor on a pre-8.3.10 project must error", r.hasError()); //$NON-NLS-1$
        assertTrue(r.error.contains("8.3.10")); //$NON-NLS-1$
    }

    @Test
    public void testAutoWidthCalculationIsAcceptedFrom8310()
    {
        SpreadsheetDocument doc = newDocument();
        // The other edge of the refusal: 8.3.10 itself is the first version that persists it, so the
        // floor must ADMIT it rather than round the project away.
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"autoWidthCalculation\":true}]}"), Version.V8_3_10); //$NON-NLS-1$
        assertFalse("8.3.10 must be accepted: " + r.error, r.hasError()); //$NON-NLS-1$
        com._1c.g5.v8.dt.moxel.Column column = doc.getColumns().getColumns().get(Integer.valueOf(0));
        assertTrue(doc.getFormats().get(column.getFormatIndex()).isAutoWidthCalculation());
    }

    @Test
    public void testAPlainColumnWidthIsUnaffectedByAnOldProjectVersion()
    {
        SpreadsheetDocument doc = newDocument();
        // The refusal must not over-reach: a fixed width has no version floor at all.
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"columnWidths\":[{\"col\":0,\"width\":40}]}"), Version.V8_3_9); //$NON-NLS-1$
        assertFalse("a plain width must stay legal on an old project: " + r.error, r.hasError()); //$NON-NLS-1$
        com._1c.g5.v8.dt.moxel.Column column = doc.getColumns().getColumns().get(Integer.valueOf(0));
        assertEquals(40, doc.getFormats().get(column.getFormatIndex()).getWidth());
    }

    @Test
    public void testCellRotationIsUnaffectedByAnOldProjectVersion()
    {
        SpreadsheetDocument doc = newDocument();
        // textOrientation / autoIndent / autoMarkIncomplete are written by the serializer with NO version
        // gate, so the 8.3.10 floor must not spill onto them.
        Result r = SpreadsheetTemplateWriter.apply(doc,
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"textOrientation\":900}]}"), //$NON-NLS-1$
            Version.V8_3_9);
        assertFalse("a rotation must stay legal on an old project: " + r.error, r.hasError()); //$NON-NLS-1$
        assertEquals(900, formatOf(doc, cellAt(doc, 0, 0)).getTextOrientation());
    }

    // ==================== pure parse / enum resolution (no model) ====================

    @Test
    public void testParseResolvesEnumsCaseInsensitively()
    {
        ParseResult parsed = SpreadsheetTemplateWriter.parse(json("{\"cells\":[{\"row\":0,\"col\":0," //$NON-NLS-1$
            + "\"text\":\"A\",\"hAlign\":\"CeNtEr\",\"vAlign\":\"bottom\",\"wrap\":true}]}")); //$NON-NLS-1$
        assertNull("a valid spec must parse: " + parsed.error, parsed.error); //$NON-NLS-1$
        assertNotNull(parsed.plan);
        assertEquals(1, parsed.plan.cells.size());
        CellPlan cell = parsed.plan.cells.get(0);
        assertEquals(HorizontalAlignment.CENTER, cell.hAlign);
        assertEquals(VerticalAlignment.BOTTOM, cell.vAlign);
        assertEquals(TextPlacement.WRAP, cell.textPlacement);
    }

    @Test
    public void testParseWrapFalseLeavesPlacementUnset()
    {
        ParseResult parsed = SpreadsheetTemplateWriter.parse(
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"wrap\":false}]}")); //$NON-NLS-1$
        assertNull(parsed.error);
        assertNull("wrap:false must not set a text placement", parsed.plan.cells.get(0).textPlacement); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsBadEnumWithoutModel()
    {
        ParseResult parsed = SpreadsheetTemplateWriter.parse(
            json("{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"vAlign\":\"middle\"}]}")); //$NON-NLS-1$
        assertNotNull("a bad vAlign token must fail the pure parse", parsed.error); //$NON-NLS-1$
        assertNull(parsed.plan);
    }

    @Test
    public void testParseRejectsNonObjectArray()
    {
        ParseResult parsed = SpreadsheetTemplateWriter.parse(json("{\"cells\":\"nope\"}")); //$NON-NLS-1$
        assertNotNull("cells that is not an array must error", parsed.error); //$NON-NLS-1$
    }

    @Test
    public void testParseKeepsTheRotationUnscaledAndTheOtherMembersNull()
    {
        ParseResult parsed = SpreadsheetTemplateWriter.parse(json("{\"cells\":[{\"row\":0,\"col\":0," //$NON-NLS-1$
            + "\"text\":\"A\",\"textOrientation\":900}]}")); //$NON-NLS-1$
        assertNull("a valid spec must parse: " + parsed.error, parsed.error); //$NON-NLS-1$
        CellPlan cell = parsed.plan.cells.get(0);
        assertEquals(Integer.valueOf(900), cell.textOrientation);
        assertNull("an omitted 'autoIndent' must stay null (unset)", cell.autoIndent); //$NON-NLS-1$
        assertNull("an omitted 'autoMarkIncomplete' must stay null (unset)", cell.autoMarkIncomplete); //$NON-NLS-1$
    }

    @Test
    public void testParseAcceptsARotationOnlyCellWithNoContent()
    {
        // Rotation is a style member, so it alone makes a content-less cell entry meaningful (the same
        // rule 'bold' / 'wrap' already follow) rather than tripping the "needs at least one of" refusal.
        ParseResult parsed = SpreadsheetTemplateWriter.parse(
            json("{\"cells\":[{\"row\":0,\"col\":0,\"textOrientation\":900}]}")); //$NON-NLS-1$
        assertNull("a rotation-only cell must parse: " + parsed.error, parsed.error); //$NON-NLS-1$
        assertEquals(Integer.valueOf(900), parsed.plan.cells.get(0).textOrientation);
    }
}
