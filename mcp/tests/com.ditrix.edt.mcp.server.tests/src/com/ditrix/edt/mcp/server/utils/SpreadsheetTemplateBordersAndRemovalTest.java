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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.NamedItem;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.NamedItemDrawing;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.CellLine;
import com._1c.g5.v8.dt.moxel.content.CellLineStyle;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Issue #1001: cell borders, border / back / text colours, unmerging and removing / renaming a named
 * area through the {@code template} payload. Drives only the writer's long-standing entry point
 * ({@link SpreadsheetTemplateWriter#apply(SpreadsheetDocument, JsonObject)}) so every test runs - and
 * fails for the right reason - against a writer that does not know these keys yet.
 */
public class SpreadsheetTemplateBordersAndRemovalTest
{
    private static JsonObject json(String s)
    {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static Result apply(SpreadsheetDocument doc, String spec)
    {
        return SpreadsheetTemplateWriter.apply(doc, json(spec));
    }

    private static Result applyOk(SpreadsheetDocument doc, String spec)
    {
        Result result = apply(doc, spec);
        assertFalse("must apply: " + result.error, result.hasError());
        return result;
    }

    private static Cell cellAt(SpreadsheetDocument doc, int row, int col)
    {
        Row r = doc.getRows().get(Integer.valueOf(row));
        assertNotNull("expected a row at " + row, r);
        Cell c = r.getCells().get(Integer.valueOf(col));
        assertNotNull("expected a cell at (" + row + "," + col + ")", c);
        return c;
    }

    private static Format formatAt(SpreadsheetDocument doc, int row, int col)
    {
        return doc.getFormats().get(cellAt(doc, row, col).getFormatIndex());
    }

    private static CellLine line(SpreadsheetDocument doc, int index)
    {
        return (CellLine)doc.getLines().get(index);
    }

    private static Merge merge(int top, int left, int bottom, int right)
    {
        Rect rect = MoxelFactory.eINSTANCE.createRect();
        rect.setX(left);
        rect.setY(top);
        rect.setWidth(right - left);
        rect.setHeight(bottom - top);
        Merge merge = MoxelFactory.eINSTANCE.createMerge();
        merge.setPosition(rect);
        return merge;
    }

    private static void addArea(SpreadsheetDocument doc, String name, int top, int left, int bottom, int right)
    {
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(merge(top, left, bottom, right).getPosition());
        NamedItemCells item = MoxelFactory.eINSTANCE.createNamedItemCells();
        item.setArea(area);
        doc.getNamedItems().put(name, item);
    }

    // ==================== borders ====================

    @Test
    public void aStyleNameBordersAllFourSidesWithOnePooledThinLine()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"borders\":\"Solid\"}]}");
        Format format = formatAt(doc, 0, 0);
        assertTrue(format.isSetLeftBorder() && format.isSetTopBorder() && format.isSetRightBorder()
            && format.isSetBottomBorder());
        assertEquals(format.getLeftBorder(), format.getTopBorder());
        assertEquals(format.getLeftBorder(), format.getRightBorder());
        assertEquals(format.getLeftBorder(), format.getBottomBorder());
        assertEquals("one pooled line serves all four sides", 1, doc.getLines().size());
        CellLine line = line(doc, format.getLeftBorder());
        assertEquals(CellLineStyle.SOLID, line.getStyle());
        assertEquals("the default width is EDT's thinnest line", 1, line.getWidth());
        assertFalse(line.isGap());
    }

    @Test
    public void perSideLinesTouchOnlyTheNamedSides()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"borders\":{\"left\":{\"style\":\"Double\","
            + "\"width\":2},\"bottom\":\"ThinDashed\"}}]}");
        Format format = formatAt(doc, 0, 0);
        assertFalse("an unnamed side stays unset", format.isSetTopBorder());
        assertFalse("an unnamed side stays unset", format.isSetRightBorder());
        CellLine left = line(doc, format.getLeftBorder());
        assertEquals(CellLineStyle.DOUBLE, left.getStyle());
        assertEquals(2, left.getWidth());
        CellLine bottom = line(doc, format.getBottomBorder());
        assertEquals(CellLineStyle.THIN_DASHED, bottom.getStyle());
        assertEquals(1, bottom.getWidth());
    }

    @Test
    public void aNullStyleBesideASideCountsAsOmitted()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":{\"style\":null,\"top\":\"Solid\"}}]}");
        Format format = formatAt(doc, 0, 0);
        assertTrue(format.isSetTopBorder());
        assertFalse(format.isSetLeftBorder());
    }

    @Test
    public void noneIsEdtsEmptyLineWithZeroWidth()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":{\"top\":\"None\"}}]}");
        CellLine top = line(doc, formatAt(doc, 0, 0).getTopBorder());
        assertEquals(CellLineStyle.NONE, top.getStyle());
        assertEquals(0, top.getWidth());
    }

    @Test
    public void anEqualLineOnAnotherCellReusesThePooledLine()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":{\"style\":\"Solid\",\"width\":3}},"
            + "{\"row\":5,\"col\":2,\"borders\":{\"left\":{\"style\":\"Solid\",\"width\":3}}}]}");
        assertEquals(1, doc.getLines().size());
        assertEquals(formatAt(doc, 0, 0).getLeftBorder(), formatAt(doc, 5, 2).getLeftBorder());
    }

    @Test
    public void bordersMergeIntoAnExistingCellKeepingItsTextAndFont()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":1,\"col\":1,\"text\":\"Kept\",\"bold\":true}]}");
        applyOk(doc, "{\"cells\":[{\"row\":1,\"col\":1,\"borders\":\"Dotted\"}]}");
        Cell cell = cellAt(doc, 1, 1);
        assertEquals("Kept", cell.getText().getContent().get("#"));
        Format format = formatAt(doc, 1, 1);
        assertTrue("the bold font survives a borders-only patch", format.isSetFont());
        assertEquals(CellLineStyle.DOTTED, line(doc, format.getRightBorder()).getStyle());
    }

    @Test
    public void anUnknownLineStyleIsRefusedWithThePlatformStylesAndWritesNothing()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Result result = apply(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"borders\":\"Wavy\"}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("Wavy"));
        assertTrue(result.error, result.error.contains("ThinDashed"));
        assertTrue("a refused spec writes nothing", doc.getRows().isEmpty());
        assertTrue(doc.getLines().isEmpty());
    }

    @Test
    public void aWidthOutsideTheLineDialogRangeIsRefused()
    {
        Result result = apply(MoxelFactory.eINSTANCE.createSpreadsheetDocument(),
            "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":{\"style\":\"Solid\",\"width\":4}}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("1..3"));
    }

    @Test
    public void aFractionalWidthIsRefusedNotRounded()
    {
        Result result = apply(MoxelFactory.eINSTANCE.createSpreadsheetDocument(),
            "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":{\"style\":\"Solid\",\"width\":2.0000000000000001}}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("2.0000000000000001"));
    }

    @Test
    public void noneWithAWidthIsRefused()
    {
        Result result = apply(MoxelFactory.eINSTANCE.createSpreadsheetDocument(),
            "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":{\"left\":{\"style\":\"None\",\"width\":1}}}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("None"));
    }

    @Test
    public void anUnknownSideIsRefused()
    {
        Result result = apply(MoxelFactory.eINSTANCE.createSpreadsheetDocument(),
            "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":{\"middle\":\"Solid\"}}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("middle"));
    }

    @Test
    public void bordersOnAColumnEntryAreRefusedAsACellProperty()
    {
        Result result = apply(MoxelFactory.eINSTANCE.createSpreadsheetDocument(),
            "{\"columnWidths\":[{\"col\":0,\"width\":10,\"borders\":\"Solid\"}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("CELL format property"));
    }

    // ==================== colours ====================

    @Test
    public void rgbColoursArePooledOnceAndIndexedFromTheFormat()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"backColor\":{\"red\":255,\"green\":240,"
            + "\"blue\":200},\"textColor\":{\"red\":255,\"green\":240,\"blue\":200},\"borderColor\":{\"red\":0,"
            + "\"green\":0,\"blue\":128}}]}");
        Format format = formatAt(doc, 0, 0);
        assertEquals("an equal colour is pooled once", format.getBackColor(), format.getTextColor());
        assertEquals(2, doc.getColors().size());
        ColorDef back = (ColorDef)doc.getColors().get(format.getBackColor());
        assertEquals(255, back.getRed());
        assertEquals(240, back.getGreen());
        assertEquals(200, back.getBlue());
        ColorDef border = (ColorDef)doc.getColors().get(format.getBorderColor());
        assertEquals(128, border.getBlue());
    }

    @Test
    public void autoUnsetsAColourSoItInheritsAgain()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"backColor\":{\"red\":1,\"green\":2,"
            + "\"blue\":3}}]}");
        assertTrue(formatAt(doc, 0, 0).isSetBackColor());
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"backColor\":\"auto\"}]}");
        assertFalse(formatAt(doc, 0, 0).isSetBackColor());
        assertEquals("A", cellAt(doc, 0, 0).getText().getContent().get("#"));
    }

    @Test
    public void anInvalidColourNamesTheKeyAndWritesNothing()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Result result = apply(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"textColor\":{\"red\":300,"
            + "\"green\":0,\"blue\":0}}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("textColor"));
        assertTrue("the colour grammar names the bad component", result.error.contains("red must be in range 0-255, got 300"));
        assertTrue(doc.getRows().isEmpty());
        assertTrue(doc.getColors().isEmpty());
    }

    // ==================== unmerges ====================

    @Test
    public void unmergeDropsTheTouchingMergeAndSpreadsTheMergedFormatKeepingOuterBorders()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Title\",\"borders\":\"Solid\","
            + "\"backColor\":{\"red\":9,\"green\":9,\"blue\":9}}],"
            + "\"merges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":1,\"toCol\":2}]}");
        int back = formatAt(doc, 0, 0).getBackColor();

        // An inner cell of the merge is enough to unmerge it, as a selection is in EDT's editor.
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":1,\"fromCol\":1,\"toRow\":1,\"toCol\":1}]}");

        assertTrue("the touching merge is removed", doc.getMerges().isEmpty());
        assertEquals("Title", cellAt(doc, 0, 0).getText().getContent().get("#"));
        Format topLeft = formatAt(doc, 0, 0);
        assertTrue(topLeft.isSetLeftBorder() && topLeft.isSetTopBorder());
        assertTrue(topLeft.isSetRightBorder());
        assertEmptyLine(doc, topLeft.getRightBorder());
        assertTrue(topLeft.isSetBottomBorder());
        assertEmptyLine(doc, topLeft.getBottomBorder());
        Format bottomRight = formatAt(doc, 1, 2);
        assertTrue(bottomRight.isSetRightBorder() && bottomRight.isSetBottomBorder());
        assertTrue(bottomRight.isSetLeftBorder());
        assertEmptyLine(doc, bottomRight.getLeftBorder());
        assertTrue(bottomRight.isSetTopBorder());
        assertEmptyLine(doc, bottomRight.getTopBorder());
        Format topMiddle = formatAt(doc, 0, 1);
        assertTrue(topMiddle.isSetTopBorder());
        assertTrue(topMiddle.isSetLeftBorder() && topMiddle.isSetRightBorder() && topMiddle.isSetBottomBorder());
        assertEmptyLine(doc, topMiddle.getLeftBorder());
        assertEmptyLine(doc, topMiddle.getRightBorder());
        assertEmptyLine(doc, topMiddle.getBottomBorder());
        for (int row = 0; row <= 1; row++)
        {
            for (int col = 0; col <= 2; col++)
            {
                assertEquals("every freed cell takes the merged cell's back colour", back,
                    formatAt(doc, row, col).getBackColor());
            }
        }
    }

    @Test
    public void unmergeKeepsAMergeOutsideTheRange()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Merge kept = merge(5, 0, 5, 3);
        doc.getMerges().add(merge(0, 0, 0, 3));
        doc.getMerges().add(kept);
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":2,\"toRow\":4,\"toCol\":9}]}");
        assertEquals(1, doc.getMerges().size());
        assertSame(kept, doc.getMerges().get(0));
    }

    @Test
    public void unmergeOfARangeWithoutATopLeftCellKeepsTheCoveredCells()
    {
        // A merge this tool wrote may cover a populated cell and have no top-left cell; EDT would drop the
        // covered cells, which here would discard their content.
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":1,\"text\":\"Covered\"}]}");
        doc.getMerges().add(merge(0, 0, 0, 1));
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":0}]}");
        assertTrue(doc.getMerges().isEmpty());
        assertEquals("Covered", cellAt(doc, 0, 1).getText().getContent().get("#"));
        assertNull("no top-left cell is invented", doc.getRows().get(Integer.valueOf(0)).getCells()
            .get(Integer.valueOf(0)));
    }

    @Test
    public void aCoveredTextCellKeepsItsFillWhenTheTopLeftCellIsAParameter()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"parameter\":\"Amount\",\"backColor\":{\"red\":1,"
            + "\"green\":2,\"blue\":3}},{\"row\":0,\"col\":1,\"text\":\"Covered\"}],"
            + "\"merges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":1}]}");
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":1}]}");
        Format covered = formatAt(doc, 0, 1);
        assertFalse("the parameter fill would make the serializer drop the covered text",
            covered.isSetFillType() && covered.getFillType() == FillType.PARAMETER);
        assertEquals("the covered cell still takes the merged cell's look", formatAt(doc, 0, 0).getBackColor(),
            covered.getBackColor());
        assertEquals(FillType.PARAMETER, formatAt(doc, 0, 0).getFillType());
        assertEquals("Covered", cellAt(doc, 0, 1).getText().getContent().get("#"));
    }

    @Test(timeout = 20_000)
    public void unmergingAMergeAtTheEndOfTheIntRangeTerminates()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        int last = Integer.MAX_VALUE;
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":" + (last - 1) + ",\"text\":\"Edge\"}]}");
        doc.getMerges().add(merge(0, last - 1, 0, last));
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":" + (last - 1) + ",\"toRow\":0,\"toCol\":"
            + (last - 1) + "}]}");
        assertTrue(doc.getMerges().isEmpty());
        assertNotNull(doc.getRows().get(Integer.valueOf(0)).getCells().get(Integer.valueOf(last)));
    }

    @Test
    public void unmergingAMergeOfMoreThanTenThousandCellsIsRefusedBeforeAnythingChanges()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Huge\"}]}");
        Merge huge = merge(0, 0, 100, 99); // 101 rows x 100 columns = 10 100 cells
        doc.getMerges().add(huge);
        Result result = apply(doc, "{\"unmerges\":[{\"fromRow\":50,\"fromCol\":50,\"toRow\":50,\"toCol\":50}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("10100 cells"));
        assertTrue(result.error, result.error.contains("10000"));
        assertSame("the merge stays", huge, doc.getMerges().get(0));
        assertEquals("no cell was materialized", 1, doc.getRows().size());
    }

    @Test
    public void aBlankMergeOfAnySizeIsRemovedBecauseNoCellIsReformatted()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getMerges().add(merge(0, 0, 200, 200));
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":0}]}");
        assertTrue(doc.getMerges().isEmpty());
        assertTrue("no cell is materialized for a merge without a top-left cell", doc.getRows().isEmpty());
    }

    @Test
    public void aCellAnEarlierUnmergeCreatesDoesNotAnchorAnOverlappingMergeLater()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Anchor\"}]}");
        doc.getMerges().add(merge(0, 0, 0, 1));
        doc.getMerges().add(merge(0, 1, 100, 100)); // overlapping, no top-left cell yet: 10 100 cells
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":0},"
            + "{\"fromRow\":0,\"fromCol\":1,\"toRow\":0,\"toCol\":1}]}");
        assertTrue(doc.getMerges().isEmpty());
        assertEquals("only the first merge's two cells are re-formatted", 1, doc.getRows().size());
        assertEquals(2, doc.getRows().get(Integer.valueOf(0)).getCells().size());
    }

    @Test
    public void unmergingExactlyTenThousandCellsIsAllowed()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Edge\"}]}");
        doc.getMerges().add(merge(0, 0, 99, 99)); // 100 x 100
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":0}]}");
        assertTrue(doc.getMerges().isEmpty());
        assertEquals(100, doc.getRows().size());
    }

    @Test
    public void aMergeTouchedByTwoRangesCountsOnceTowardsTheLimit()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Twice\"}]}");
        doc.getMerges().add(merge(0, 0, 59, 99)); // 6 000 cells; counted twice it would pass the limit
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":0},"
            + "{\"fromRow\":59,\"fromCol\":99,\"toRow\":59,\"toCol\":99}]}");
        assertTrue(doc.getMerges().isEmpty());
    }

    @Test
    public void unmergingAndMergingInOneCallRebuildsTheLayout()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getMerges().add(merge(0, 0, 0, 3));
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":3}],"
            + "\"merges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":0,\"toCol\":1}]}");
        assertEquals("the old merge goes before the new one is added", 1, doc.getMerges().size());
        assertEquals(1, doc.getMerges().get(0).getPosition().getWidth());
    }

    // ==================== named areas ====================

    @Test
    public void removeAreasDeletesTheNamedArea()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        addArea(doc, "Footer", 5, 0, 5, 3);
        applyOk(doc, "{\"removeAreas\":[\"Header\"]}");
        assertFalse(doc.getNamedItems().containsKey("Header"));
        assertTrue(doc.getNamedItems().containsKey("Footer"));
    }

    @Test
    public void renameAreasReKeysTheSameAreaKeepingItsRange()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        NamedItem before = doc.getNamedItems().get("Header");
        applyOk(doc, "{\"renameAreas\":[{\"name\":\"Header\",\"newName\":\"Шапка\"}]}");
        assertFalse(doc.getNamedItems().containsKey("Header"));
        assertSame("the area itself moves to the new name", before, doc.getNamedItems().get("Шапка"));
        Rect rect = ((RectArea)((NamedItemCells)before).getArea()).getPosition();
        assertEquals(3, rect.getWidth());
        assertEquals(1, rect.getHeight());
    }

    @Test
    public void anUnknownAreaIsRefusedListingTheExistingOnesAndNothingIsWritten()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        addArea(doc, "Footer", 5, 0, 5, 3);
        Result result = apply(doc, "{\"cells\":[{\"row\":9,\"col\":0,\"text\":\"New\"}],"
            + "\"removeAreas\":[\"Body\"]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("Body"));
        assertTrue(result.error, result.error.contains("Footer, Header"));
        assertTrue("a refused removal writes no cell either", doc.getRows().isEmpty());
        assertEquals(2, doc.getNamedItems().size());
    }

    @Test
    public void aWrongCaseNameIsRefusedWithTheStoredSpelling()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        Result result = apply(doc, "{\"removeAreas\":[\"header\"]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("Did you mean 'Header'"));
        assertTrue(doc.getNamedItems().containsKey("Header"));
    }

    @Test
    public void renamingOntoAnExistingNameIgnoringCaseIsRefused()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        addArea(doc, "Footer", 5, 0, 5, 3);
        Result result = apply(doc, "{\"renameAreas\":[{\"name\":\"Header\",\"newName\":\"FOOTER\"}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("'Footer' already exists"));
        assertTrue(doc.getNamedItems().containsKey("Header"));
    }

    @Test
    public void renamingAfterRemovingTheClashingNameInTheSameCallIsAllowed()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        addArea(doc, "Footer", 5, 0, 5, 3);
        applyOk(doc, "{\"removeAreas\":[\"Footer\"],\"renameAreas\":[{\"name\":\"Header\",\"newName\":\"Footer\"}]}");
        assertEquals(1, doc.getNamedItems().size());
        Rect rect = ((RectArea)((NamedItemCells)doc.getNamedItems().get("Footer")).getArea()).getPosition();
        assertEquals("the renamed area is the former Header", 0, rect.getY());
    }

    @Test
    public void renamingOntoANameThatTheSameCallAddsIsRefused()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        Result result = apply(doc, "{\"renameAreas\":[{\"name\":\"Header\",\"newName\":\"Title\"}],"
            + "\"areas\":[{\"name\":\"title\",\"fromRow\":5,\"fromCol\":0,\"toRow\":5,\"toCol\":1}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("adds the area 'title'"));
        assertTrue(doc.getNamedItems().containsKey("Header"));
        assertEquals(1, doc.getNamedItems().size());
    }

    @Test
    public void anAdditionMayTakeANameARenameChainMovedOn()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "First", 0, 0, 0, 1);
        applyOk(doc, "{\"renameAreas\":[{\"name\":\"First\",\"newName\":\"Middle\"},"
            + "{\"name\":\"Middle\",\"newName\":\"Last\"}],"
            + "\"areas\":[{\"name\":\"Middle\",\"fromRow\":5,\"fromCol\":0,\"toRow\":5,\"toCol\":1}]}");
        assertEquals(2, doc.getNamedItems().size());
        assertEquals(0, ((RectArea)((NamedItemCells)doc.getNamedItems().get("Last")).getArea()).getPosition().getY());
        assertEquals(5, ((RectArea)((NamedItemCells)doc.getNamedItems().get("Middle")).getArea()).getPosition().getY());
    }

    @Test
    public void anInvalidNewNameIsRefusedByEdtsNameRule()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        Result result = apply(doc, "{\"renameAreas\":[{\"name\":\"Header\",\"newName\":\"Two words\"}]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("Two words"));
        assertTrue(doc.getNamedItems().containsKey("Header"));
    }

    @Test
    public void removingTheSameAreaTwiceIsRefused()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        Result result = apply(doc, "{\"removeAreas\":[\"Header\",\"Header\"]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("any longer"));
        assertTrue(doc.getNamedItems().containsKey("Header"));
    }

    @Test
    public void aNamedDrawingIsNotRemovedAsAnArea()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        NamedItemDrawing drawing = MoxelFactory.eINSTANCE.createNamedItemDrawing();
        doc.getNamedItems().put("D1", drawing);
        Result result = apply(doc, "{\"removeAreas\":[\"D1\"]}");
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("not a cell area"));
        assertSame(drawing, doc.getNamedItems().get("D1"));
    }

    private static void assertEmptyLine(SpreadsheetDocument doc, int index)
    {
        assertEquals(CellLineStyle.NONE, line(doc, index).getStyle());
        assertEquals(0, line(doc, index).getWidth());
    }

    @Test
    public void unmergeExplicitlyBlocksInheritedInteriorBorders()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        applyOk(doc, "{\"cells\":[{\"row\":0,\"col\":0,\"borders\":\"Solid\"}],"
            + "\"merges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":1,\"toCol\":1}]}");
        doc.setDefaultFormatIndex(cellAt(doc, 0, 0).getFormatIndex());
        applyOk(doc, "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":1,\"toCol\":1}]}");
        Format corner = formatAt(doc, 0, 0);
        assertTrue(corner.isSetRightBorder() && corner.isSetBottomBorder());
        assertEmptyLine(doc, corner.getRightBorder());
        assertEmptyLine(doc, corner.getBottomBorder());
        assertEquals(CellLineStyle.SOLID, line(doc, corner.getTopBorder()).getStyle());
        assertEquals("one pooled empty line shared by every interior edge", 2, doc.getLines().size());
    }

    @Test
    public void digitLeadingAreaRenameFollowsEdtsTemplateNameRule()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        addArea(doc, "Header", 0, 0, 1, 3);
        applyOk(doc, "{\"renameAreas\":[{\"name\":\"Header\",\"newName\":\"1Header\"}]}");
        assertFalse(doc.getNamedItems().containsKey("Header"));
        assertTrue(doc.getNamedItems().containsKey("1Header"));
    }
}
