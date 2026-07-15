/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.ContentFactory;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com._1c.g5.v8.dt.moxel.content.HorizontalAlignment;
import com._1c.g5.v8.dt.moxel.content.LocalString;
import com._1c.g5.v8.dt.moxel.content.TextPlacement;
import com._1c.g5.v8.dt.moxel.content.VerticalAlignment;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Authors the CONTENT of a 1C SpreadsheetDocument ("moxel") print-form template - the model behind a
 * {@code .mxlx} resource - from the structured {@code template} JSON a client passes to
 * {@code modify_metadata}. The writer is a pure, typed EMF transformation: it takes an already-resolved
 * {@link SpreadsheetDocument} (reached by the caller inside a BM boundary from
 * {@code BasicTemplate.getTemplate()}) and applies a parsed spec of cells / merges / named areas /
 * column widths / row heights with the TYPED moxel API ({@link MoxelFactory} / {@link ContentFactory} /
 * {@link McoreFactory}) - never reflective {@code eSet}, never opening a transaction or force-exporting
 * (the caller owns both).
 *
 * <p>The SpreadsheetDocument model is NORMALIZED / POOLED, not a naive grid:</p>
 * <ul>
 * <li>{@link SpreadsheetDocument#getFonts()} (an {@code EList<mcore.Font>}) and
 * {@link SpreadsheetDocument#getFormats()} (an {@code EList<Format>}) are shared FLYWEIGHT pools; a cell
 * references its formatting only by an {@code int} index ({@link Cell#setFormatIndex(int)}) into the
 * formats pool, and a {@link Format} references its font by an {@code int} index
 * ({@link Format#setFont(int)}) into the fonts pool. This writer INTERNS both: an identical font /
 * format is added to its pool once and reused (dedup), so authoring "bold size-12 centered wrapped" for
 * many cells grows each pool by a single entry.</li>
 * <li>{@link SpreadsheetDocument#getRows()} is an {@code EMap<Integer,Row>} and
 * {@link Row#getCells()} an {@code EMap<Integer,Cell>} - SPARSE, index-keyed: a cell / row is
 * {@code put(index, ...)}, never {@code add(...)}.</li>
 * <li>A merge is a {@link Merge} whose {@link Merge#setPosition(Rect) position} is a {@link Rect}
 * {@code (x=leftColumn, y=topRow, width=rightColumn-leftColumn, height=bottomRow-topRow)} - the
 * width/height are DELTAS (last minus first), the same encoding EDT's own
 * {@code SheetFactory.createRect} uses.</li>
 * <li>A named area is a {@link NamedItemCells} over a {@link RectArea} (same delta {@link Rect}),
 * registered in {@link SpreadsheetDocument#getNamedItems()} keyed by its name.</li>
 * <li>A column width / row height lives on a {@link Format} ({@link Format#setWidth(int)} /
 * {@link Format#setHeight(int)}) referenced by the column's / row's {@code formatIndex}.</li>
 * </ul>
 *
 * <p>A cell holds literal text as a {@link LocalString} ({@link Cell#setText(LocalString)}, keyed by
 * language code) OR a report parameter name ({@link Cell#setParameter(String)}); a parameter cell is
 * additionally marked by its format's {@link FillType#PARAMETER} fill (this is what makes EDT render and
 * substitute the parameter rather than show empty text).</p>
 *
 * <p>The whole spec is PARSED + VALIDATED up front ({@link #parse(JsonObject)}) - required indices,
 * mutually-exclusive text/parameter, and every alignment / wrap enum resolved by literal name
 * (case-insensitive, with an actionable error naming the valid tokens) - so a malformed spec fails
 * before ANY model mutation. Parsing is pure (no EMF factory, no model) and separately unit-testable;
 * only {@link #apply(SpreadsheetDocument, JsonObject)} touches the model. A rejected spec leaves the
 * document untouched and reports a ready {@link ToolResult#error} JSON string (which the calling tool
 * returns verbatim, rolling its write transaction back).</p>
 */
public final class SpreadsheetTemplateWriter
{
    // ---- top-level spec keys ------------------------------------------------------------------

    private static final String KEY_CELLS = "cells"; //$NON-NLS-1$
    private static final String KEY_MERGES = "merges"; //$NON-NLS-1$
    private static final String KEY_AREAS = "areas"; //$NON-NLS-1$
    private static final String KEY_COLUMN_WIDTHS = "columnWidths"; //$NON-NLS-1$
    private static final String KEY_ROW_HEIGHTS = "rowHeights"; //$NON-NLS-1$

    // ---- per-entry keys -----------------------------------------------------------------------

    private static final String KEY_ROW = "row"; //$NON-NLS-1$
    private static final String KEY_COL = "col"; //$NON-NLS-1$
    private static final String KEY_TEXT = "text"; //$NON-NLS-1$
    private static final String KEY_PARAMETER = "parameter"; //$NON-NLS-1$
    private static final String KEY_BOLD = "bold"; //$NON-NLS-1$
    private static final String KEY_FONT_SIZE = "fontSize"; //$NON-NLS-1$
    private static final String KEY_H_ALIGN = "hAlign"; //$NON-NLS-1$
    private static final String KEY_V_ALIGN = "vAlign"; //$NON-NLS-1$
    private static final String KEY_WRAP = "wrap"; //$NON-NLS-1$
    private static final String KEY_FROM_ROW = "fromRow"; //$NON-NLS-1$
    private static final String KEY_FROM_COL = "fromCol"; //$NON-NLS-1$
    private static final String KEY_TO_ROW = "toRow"; //$NON-NLS-1$
    private static final String KEY_TO_COL = "toCol"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_WIDTH = "width"; //$NON-NLS-1$
    private static final String KEY_HEIGHT = "height"; //$NON-NLS-1$

    /** Stem of every cell-validation error message (java:S1192). */
    private static final String ERR_CELL = "A cell ("; //$NON-NLS-1$

    /**
     * Language key for a cell's text {@link LocalString}. The v1 {@code template} payload carries no
     * per-cell language, so text is stored under the platform's language-neutral key {@code "#"}
     * ({@code SheetFactory.DEFAULT_LANGUAGE}). The moxel text reader ({@code SheetAccessor.getString})
     * resolves a cell string as {@code content.get(currentLanguage)}, then falls back to
     * {@code content.get("#")} and only then to the empty string - so a neutral {@code "#"} entry renders
     * for EVERY viewing language, whereas text stored under the empty key {@code ""} is never read (the
     * cell would flush to the {@code .mxlx} yet show blank in the editor and in a screenshot render).
     */
    private static final String NEUTRAL_LANGUAGE_KEY = "#"; //$NON-NLS-1$

    private SpreadsheetTemplateWriter()
    {
        // Utility class
    }

    // ---- result -------------------------------------------------------------------------------

    /**
     * The outcome of applying a {@code template} spec: either an actionable {@link ToolResult#error} JSON
     * string in {@link #error} (an up-front validation failure - nothing was mutated) or the counts of what
     * was applied.
     * Exactly one of {@code error} / the counts is meaningful; check {@link #hasError()} first.
     */
    public static final class Result
    {
        /** Non-null when the spec was rejected up front (nothing mutated): a ready ToolResult.error JSON. */
        public final String error;
        /** Number of cells written (created or overwritten). */
        public final int cells;
        /** Number of merges added. */
        public final int merges;
        /** Number of named areas added. */
        public final int areas;
        /** Number of column widths applied. */
        public final int columnWidths;
        /** Number of row heights applied. */
        public final int rowHeights;

        private Result(String error, int cells, int merges, int areas, int columnWidths, int rowHeights)
        {
            this.error = error;
            this.cells = cells;
            this.merges = merges;
            this.areas = areas;
            this.columnWidths = columnWidths;
            this.rowHeights = rowHeights;
        }

        static Result failed(String error)
        {
            return new Result(error, 0, 0, 0, 0, 0);
        }

        static Result ok(int cells, int merges, int areas, int columnWidths, int rowHeights)
        {
            return new Result(null, cells, merges, areas, columnWidths, rowHeights);
        }

        public boolean hasError()
        {
            return error != null;
        }
    }

    // ---- entry point --------------------------------------------------------------------------

    /**
     * Applies a parsed {@code template} spec to the given SpreadsheetDocument. Validates the whole spec
     * up front (so a malformed entry mutates nothing), then writes cells / merges / named areas / column
     * widths / row heights with the typed moxel API, interning fonts and formats into the document's
     * shared pools. Does NOT open a transaction and does NOT force-export - the caller
     * ({@code ModifyMetadataTool}) reaches the document inside its own BM write boundary and drains it to
     * the {@code .mxlx} after this returns.
     *
     * @param document the template's SpreadsheetDocument content (must not be {@code null})
     * @param spec the {@code template} payload (see the class javadoc for the shape)
     * @return a {@link Result} - check {@link Result#hasError()} first; {@link Result#error} is a ready
     *         {@link ToolResult#error} JSON string the caller returns verbatim
     */
    public static Result apply(SpreadsheetDocument document, JsonObject spec)
    {
        if (document == null)
        {
            return Result.failed(ToolResult.error(
                "The template has no SpreadsheetDocument content to write to.").toJson()); //$NON-NLS-1$
        }
        ParseResult parsed = parse(spec);
        if (parsed.error != null)
        {
            return Result.failed(ToolResult.error(parsed.error).toJson());
        }
        Plan plan = parsed.plan;

        // Reserve a neutral format at index 0 on an empty pool: a cell's formatIndex defaults to 0, so a
        // styled format must never be interned at index 0 (it would silently style every un-formatted
        // cell). Only acts on an empty pool, so it never shifts existing formatIndex references.
        ensureBaseFormat(document);

        for (CellPlan cell : plan.cells)
        {
            applyCell(document, cell);
        }
        for (MergePlan merge : plan.merges)
        {
            applyMerge(document, merge);
        }
        for (AreaPlan area : plan.areas)
        {
            applyArea(document, area);
        }
        for (ColumnWidthPlan columnWidth : plan.columnWidths)
        {
            applyColumnWidth(document, columnWidth);
        }
        for (RowHeightPlan rowHeight : plan.rowHeights)
        {
            applyRowHeight(document, rowHeight);
        }
        applyGridExtent(document, plan);

        return Result.ok(plan.cells.size(), plan.merges.size(), plan.areas.size(),
            plan.columnWidths.size(), plan.rowHeights.size());
    }

    // ---- model mutation (typed moxel API) -----------------------------------------------------

    /**
     * Writes one cell: creates-or-reuses the {@link Cell} at its sparse {@code (row, col)} index, sets
     * its literal text (as a {@link LocalString} keyed by the language code) or its parameter name, and
     * points its {@code formatIndex} at an interned {@link Format} carrying the font / alignment / wrap /
     * fill. A parameter cell is marked with {@link FillType#PARAMETER} on its format (what makes EDT
     * substitute the parameter instead of rendering empty text).
     */
    private static void applyCell(SpreadsheetDocument document, CellPlan plan)
    {
        Row row = getOrCreateRow(document, plan.row);
        Cell cell = getOrCreateCell(row, plan.col);

        // Overwrite semantics: writing a (row, col) is a TRUE REPLACE, so first clear any prior content
        // and formatting - a re-authored cell must reproduce EXACTLY the plan, with no stale fill / text /
        // style surviving. Resetting the formatIndex to the reserved neutral base format (index 0, a TEXT
        // fill) is what makes overwriting a PARAMETER cell with plain text actually render the text: the
        // moxel reader dispatches on the format's FillType, so keeping the old FillType.PARAMETER format
        // would return the now-null parameter and show blank.
        cell.setText(null);
        cell.setParameter(null);
        cell.setFormatIndex(0);

        applyCellContent(cell, plan);
        applyCellFormat(document, cell, plan);
    }

    /**
     * Sets the cell's content: its report parameter name, or its literal text as a {@link LocalString}
     * keyed by the neutral language code. A content-less (style-only) plan leaves the cell cleared.
     */
    private static void applyCellContent(Cell cell, CellPlan plan)
    {
        if (plan.parameter != null)
        {
            cell.setParameter(plan.parameter);
        }
        else if (plan.text != null)
        {
            LocalString text = ContentFactory.eINSTANCE.createLocalString();
            text.getContent().put(NEUTRAL_LANGUAGE_KEY, plan.text);
            cell.setText(text);
        }
    }

    /**
     * Points the cell's {@code formatIndex} at an interned {@link Format} carrying the plan's font /
     * alignment / wrap / fill. A plan with no styling and no parameter keeps the neutral base format
     * (index 0) already reset by the caller.
     */
    private static void applyCellFormat(SpreadsheetDocument document, Cell cell, CellPlan plan)
    {
        Integer fontIndex = null;
        if (plan.bold || plan.fontSize != null)
        {
            fontIndex = internFont(document, plan.bold, plan.fontSize);
        }
        FillType fillType = plan.parameter != null ? FillType.PARAMETER : null;
        if (fontIndex != null || plan.hAlign != null || plan.vAlign != null || plan.textPlacement != null
            || fillType != null)
        {
            cell.setFormatIndex(internFormat(document, buildCellFormat(fontIndex, plan, fillType)));
        }
    }

    /** Builds the cell {@link Format} carrying the resolved font index / alignments / wrap / fill type. */
    private static Format buildCellFormat(Integer fontIndex, CellPlan plan, FillType fillType)
    {
        Format format = MoxelFactory.eINSTANCE.createFormat();
        if (fontIndex != null)
        {
            format.setFont(fontIndex.intValue());
        }
        if (plan.hAlign != null)
        {
            format.setHorizontalAlignment(plan.hAlign);
        }
        if (plan.vAlign != null)
        {
            format.setVerticalAlignment(plan.vAlign);
        }
        if (plan.textPlacement != null)
        {
            format.setTextPlacement(plan.textPlacement);
        }
        if (fillType != null)
        {
            format.setFillType(fillType);
        }
        return format;
    }

    /** Adds a merged region as a {@link Merge} over the delta-encoded {@link Rect}. */
    private static void applyMerge(SpreadsheetDocument document, MergePlan plan)
    {
        Merge merge = MoxelFactory.eINSTANCE.createMerge();
        merge.setPosition(rect(plan.fromRow, plan.fromCol, plan.toRow, plan.toCol));
        document.getMerges().add(merge);
    }

    /**
     * Registers a named cell area: a {@link NamedItemCells} over a {@link RectArea} (same delta
     * {@link Rect} as a merge), keyed by its name in {@link SpreadsheetDocument#getNamedItems()}.
     */
    private static void applyArea(SpreadsheetDocument document, AreaPlan plan)
    {
        RectArea rectArea = MoxelFactory.eINSTANCE.createRectArea();
        rectArea.setPosition(rect(plan.fromRow, plan.fromCol, plan.toRow, plan.toCol));
        NamedItemCells namedItem = MoxelFactory.eINSTANCE.createNamedItemCells();
        namedItem.setArea(rectArea);
        document.getNamedItems().put(plan.name, namedItem);
    }

    /**
     * Sets a column's width: the width lives on a {@link Format} ({@link Format#setWidth(int)}) referenced
     * by the {@link Column}'s {@code formatIndex}. The column band's declared size is grown to cover the
     * column index so the sized column is part of the sheet.
     */
    private static void applyColumnWidth(SpreadsheetDocument document, ColumnWidthPlan plan)
    {
        Columns columns = ensureColumns(document);
        if (columns.getSize() < plan.col + 1)
        {
            columns.setSize(plan.col + 1);
        }
        EMap<Integer, Column> columnMap = columns.getColumns();
        Column column = columnMap.get(Integer.valueOf(plan.col));
        if (column == null)
        {
            column = MoxelFactory.eINSTANCE.createColumn();
            columnMap.put(Integer.valueOf(plan.col), column);
        }
        Format format = MoxelFactory.eINSTANCE.createFormat();
        format.setWidth(plan.width);
        column.setFormatIndex(internFormat(document, format));
    }

    /**
     * Sets a row's height: the height lives on a {@link Format} ({@link Format#setHeight(int)}) referenced
     * by the {@link Row}'s {@code formatIndex}.
     */
    private static void applyRowHeight(SpreadsheetDocument document, RowHeightPlan plan)
    {
        Row row = getOrCreateRow(document, plan.row);
        Format format = MoxelFactory.eINSTANCE.createFormat();
        format.setHeight(plan.height);
        row.setFormatIndex(internFormat(document, format));
    }

    /**
     * Grows the document's declared grid extent to cover every authored coordinate, maintaining BOTH grid
     * bounds symmetrically - not just the single-column case {@link #applyColumnWidth} already handles. A
     * SpreadsheetDocument carries an explicit grid extent that is always serialized: the row count
     * ({@link SpreadsheetDocument#getHeight() height}) and the column-band size ({@link Columns#getSize()});
     * both real fixture templates write these equal to {@code maxUsedRow + 1} / {@code maxUsedCol + 1}. The
     * platform clamps the sheet to that declared extent on reopen, so a cell / merge / named area authored
     * outside it (e.g. a fresh empty template starts at {@code height == 0} with no column band, yet
     * {@code create_metadata} authoring cells at rows 0, 1, 4 leaves {@code height} at 0) would render blank
     * after a refresh - silent content loss. This maintains the extent from the far corner of every authored
     * cell, row height, column width, merge and named area. It only ever grows (a {@code max}), so it never
     * shrinks a designer-authored extent already wider than the newly authored content.
     */
    private static void applyGridExtent(SpreadsheetDocument document, Plan plan)
    {
        int maxRow = -1;
        int maxCol = -1;
        for (CellPlan cell : plan.cells)
        {
            maxRow = Math.max(maxRow, cell.row);
            maxCol = Math.max(maxCol, cell.col);
        }
        for (RowHeightPlan rowHeight : plan.rowHeights)
        {
            maxRow = Math.max(maxRow, rowHeight.row);
        }
        for (ColumnWidthPlan columnWidth : plan.columnWidths)
        {
            maxCol = Math.max(maxCol, columnWidth.col);
        }
        for (MergePlan merge : plan.merges)
        {
            maxRow = Math.max(maxRow, merge.toRow);
            maxCol = Math.max(maxCol, merge.toCol);
        }
        for (AreaPlan area : plan.areas)
        {
            maxRow = Math.max(maxRow, area.toRow);
            maxCol = Math.max(maxCol, area.toCol);
        }
        if (maxRow >= 0 && document.getHeight() < maxRow + 1)
        {
            document.setHeight(maxRow + 1);
        }
        if (maxCol >= 0)
        {
            Columns columns = ensureColumns(document);
            if (columns.getSize() < maxCol + 1)
            {
                columns.setSize(maxCol + 1);
            }
        }
    }

    // ---- pooled interning ---------------------------------------------------------------------

    /**
     * Ensures the formats pool has a neutral entry at index 0 (only on an empty pool). A cell that gets no
     * explicit format keeps the default {@code formatIndex} 0, so index 0 must be a neutral format, never
     * a styled one.
     */
    private static void ensureBaseFormat(SpreadsheetDocument document)
    {
        if (document.getFormats().isEmpty())
        {
            document.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        }
    }

    /**
     * Interns a font (bold + optional size) into the shared fonts pool, returning its index. An identical
     * font already in the pool is reused (dedup); otherwise a fresh {@link FontDef} is appended.
     */
    private static int internFont(SpreadsheetDocument document, boolean bold, Integer fontSize)
    {
        FontDef desired = McoreFactory.eINSTANCE.createFontDef();
        desired.setBold(bold);
        if (fontSize != null)
        {
            desired.setHeight(fontSize.intValue());
        }
        EList<Font> fonts = document.getFonts();
        for (int i = 0; i < fonts.size(); i++)
        {
            if (emfEquals(fonts.get(i), desired))
            {
                return i;
            }
        }
        fonts.add(desired);
        return fonts.size() - 1;
    }

    /**
     * Interns a format into the shared formats pool, returning its index. An identical format already in
     * the pool is reused (dedup); otherwise the given {@link Format} is appended.
     */
    private static int internFormat(SpreadsheetDocument document, Format desired)
    {
        EList<Format> formats = document.getFormats();
        for (int i = 0; i < formats.size(); i++)
        {
            if (emfEquals(formats.get(i), desired))
            {
                return i;
            }
        }
        formats.add(desired);
        return formats.size() - 1;
    }

    /**
     * Structural, {@code eIsSet}-aware equality of two flat EMF objects (a font or a format) used for
     * pool dedup: same {@link EObject#eClass() eClass}, and for every persisted feature the same
     * set-state and, when set, the same value. {@code eIsSet} (not a value compare) is deliberate - a
     * {@link Format#setFont(int) font index} of {@code 0} is a real reference that must NOT be conflated
     * with an unset font (whose default is also {@code 0}), and an extra feature set on a pre-existing
     * (designer-authored) pool entry makes it correctly UNequal to a freshly-built one.
     */
    private static boolean emfEquals(EObject a, EObject b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null || a.eClass() != b.eClass())
        {
            return false;
        }
        for (EStructuralFeature feature : a.eClass().getEAllStructuralFeatures())
        {
            if (feature.isDerived() || feature.isTransient())
            {
                continue;
            }
            boolean setA = a.eIsSet(feature);
            boolean setB = b.eIsSet(feature);
            if (setA != setB)
            {
                return false;
            }
            if (setA && !Objects.equals(a.eGet(feature), b.eGet(feature)))
            {
                return false;
            }
        }
        return true;
    }

    // ---- sparse get-or-create -----------------------------------------------------------------

    private static Row getOrCreateRow(SpreadsheetDocument document, int rowIndex)
    {
        EMap<Integer, Row> rows = document.getRows();
        Row row = rows.get(Integer.valueOf(rowIndex));
        if (row == null)
        {
            row = MoxelFactory.eINSTANCE.createRow();
            rows.put(Integer.valueOf(rowIndex), row);
        }
        return row;
    }

    private static Cell getOrCreateCell(Row row, int colIndex)
    {
        EMap<Integer, Cell> cells = row.getCells();
        Cell cell = cells.get(Integer.valueOf(colIndex));
        if (cell == null)
        {
            cell = MoxelFactory.eINSTANCE.createCell();
            cells.put(Integer.valueOf(colIndex), cell);
        }
        return cell;
    }

    private static Columns ensureColumns(SpreadsheetDocument document)
    {
        Columns columns = document.getColumns();
        if (columns == null)
        {
            columns = MoxelFactory.eINSTANCE.createColumns();
            document.setColumns(columns);
        }
        return columns;
    }

    /**
     * Builds a moxel {@link Rect} for a cell region, in the platform's delta encoding: {@code x} = left
     * column, {@code y} = top row, {@code width} = right minus left, {@code height} = bottom minus top
     * (so {@code x + width} / {@code y + height} address the inclusive far corner). This mirrors EDT's own
     * {@code SheetFactory.createRect}.
     */
    private static Rect rect(int topRow, int leftCol, int bottomRow, int rightCol)
    {
        Rect rect = MoxelFactory.eINSTANCE.createRect();
        rect.setX(leftCol);
        rect.setY(topRow);
        rect.setWidth(rightCol - leftCol);
        rect.setHeight(bottomRow - topRow);
        return rect;
    }

    // ---- parsing / validation (pure, no model) ------------------------------------------------

    /**
     * Parses + validates a {@code template} spec into a {@link Plan} of resolved entries, or a ready
     * error message. Pure: touches no EMF factory and no model, so it is independently unit-testable. Every
     * alignment / wrap token is resolved to its enum literal here (a bad token fails the parse); required
     * indices and mutually-exclusive text/parameter are enforced here too.
     *
     * @param spec the {@code template} payload
     * @return a {@link ParseResult} - its {@link ParseResult#error} is non-null on invalid input
     */
    static ParseResult parse(JsonObject spec)
    {
        if (spec == null)
        {
            return ParseResult.failed("A 'template' payload is required, e.g. {cells:[{row:0,col:0," //$NON-NLS-1$
                + "text:'Total'}]}."); //$NON-NLS-1$
        }
        Plan plan = new Plan();

        String error = parseCells(spec, plan);
        if (error == null)
        {
            error = parseMerges(spec, plan);
        }
        if (error == null)
        {
            error = parseAreas(spec, plan);
        }
        if (error == null)
        {
            error = parseColumnWidths(spec, plan);
        }
        if (error == null)
        {
            error = parseRowHeights(spec, plan);
        }
        if (error != null)
        {
            return ParseResult.failed(error);
        }
        if (plan.isEmpty())
        {
            return ParseResult.failed("The 'template' payload is empty: provide at least one of 'cells', " //$NON-NLS-1$
                + "'merges', 'areas', 'columnWidths' or 'rowHeights', e.g. " //$NON-NLS-1$
                + "{cells:[{row:0,col:0,text:'Total'}]}."); //$NON-NLS-1$
        }
        return ParseResult.ok(plan);
    }

    private static String parseCells(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_CELLS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_CELLS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            String error = parseCell(entries.get(i), i, plan);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String parseCell(JsonObject entry, int index, Plan plan)
    {
        String where = KEY_CELLS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        Integer row = nonNegative(entry, KEY_ROW);
        if (row == null)
        {
            return indexError(where, KEY_ROW);
        }
        Integer col = nonNegative(entry, KEY_COL);
        if (col == null)
        {
            return indexError(where, KEY_COL);
        }

        String text = nonEmptyString(entry, KEY_TEXT);
        String parameter = nonEmptyString(entry, KEY_PARAMETER);
        if (text != null && parameter != null)
        {
            return ERR_CELL + where + ") cannot set both 'text' and 'parameter'; a cell holds EITHER " //$NON-NLS-1$
                + "literal text OR a report parameter name."; //$NON-NLS-1$
        }

        CellStyleResult style = parseCellStyle(entry, where);
        if (style.error != null)
        {
            return style.error;
        }
        if (text == null && parameter == null && !style.hasAnyStyle())
        {
            return ERR_CELL + where + ") needs at least one of 'text', 'parameter', 'bold', " //$NON-NLS-1$
                + "'fontSize', 'hAlign', 'vAlign' or 'wrap'."; //$NON-NLS-1$
        }

        plan.cells.add(new CellPlan(row.intValue(), col.intValue(), text, parameter, style.bold,
            style.fontSize, style.hAlign, style.vAlign, style.wrap));
        return null;
    }

    /**
     * Parses + validates a cell entry's optional style members: {@code bold}, a positive
     * {@code fontSize}, the {@code hAlign} / {@code vAlign} alignment tokens and the {@code wrap} text
     * placement. A bad value is a ready error.
     */
    private static CellStyleResult parseCellStyle(JsonObject entry, String where)
    {
        boolean bold = Boolean.TRUE.equals(boolMember(entry, KEY_BOLD));
        Integer fontSize = intMember(entry, KEY_FONT_SIZE);
        if (fontSize != null && fontSize.intValue() <= 0)
        {
            return CellStyleResult.failed(
                ERR_CELL + where + ") 'fontSize' must be a positive integer, got " + fontSize + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        HorizontalAlignment hAlign = null;
        if (entry.has(KEY_H_ALIGN))
        {
            hAlign = resolveEnum(HorizontalAlignment.values(), stringMember(entry, KEY_H_ALIGN));
            if (hAlign == null)
            {
                return CellStyleResult.failed(enumError(where, KEY_H_ALIGN,
                    stringMember(entry, KEY_H_ALIGN), HorizontalAlignment.values()));
            }
        }
        VerticalAlignment vAlign = null;
        if (entry.has(KEY_V_ALIGN))
        {
            vAlign = resolveEnum(VerticalAlignment.values(), stringMember(entry, KEY_V_ALIGN));
            if (vAlign == null)
            {
                return CellStyleResult.failed(enumError(where, KEY_V_ALIGN,
                    stringMember(entry, KEY_V_ALIGN), VerticalAlignment.values()));
            }
        }
        WrapResult wrap = parseWrap(entry, where);
        if (wrap.error != null)
        {
            return CellStyleResult.failed(wrap.error);
        }
        return CellStyleResult.ok(bold, fontSize, hAlign, vAlign, wrap.value);
    }

    private static String parseMerges(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_MERGES);
        if (entries == null)
        {
            return notAnObjectArray(KEY_MERGES);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_MERGES + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            RegionResult region = parseRegion(entry, where);
            if (region.error != null)
            {
                return region.error;
            }
            if (region.fromRow == region.toRow && region.fromCol == region.toCol)
            {
                return "A merge (" + where + ") must span at least two cells (fromRow/fromCol must " //$NON-NLS-1$ //$NON-NLS-2$
                    + "differ from toRow/toCol)."; //$NON-NLS-1$
            }
            plan.merges.add(new MergePlan(region.fromRow, region.fromCol, region.toRow, region.toCol));
        }
        return null;
    }

    private static String parseAreas(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_AREAS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_AREAS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_AREAS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            String name = nonEmptyString(entry, KEY_NAME);
            if (name == null)
            {
                return "A named area (" + where + ") needs a non-empty 'name'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            RegionResult region = parseRegion(entry, where);
            if (region.error != null)
            {
                return region.error;
            }
            plan.areas.add(new AreaPlan(name, region.fromRow, region.fromCol, region.toRow, region.toCol));
        }
        return null;
    }

    private static String parseColumnWidths(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_COLUMN_WIDTHS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_COLUMN_WIDTHS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_COLUMN_WIDTHS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            Integer col = nonNegative(entry, KEY_COL);
            if (col == null)
            {
                return indexError(where, KEY_COL);
            }
            Integer width = intMember(entry, KEY_WIDTH);
            if (width == null || width.intValue() <= 0)
            {
                return "A column width (" + where + ") needs a positive integer 'width'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            plan.columnWidths.add(new ColumnWidthPlan(col.intValue(), width.intValue()));
        }
        return null;
    }

    private static String parseRowHeights(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_ROW_HEIGHTS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_ROW_HEIGHTS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_ROW_HEIGHTS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            Integer row = nonNegative(entry, KEY_ROW);
            if (row == null)
            {
                return indexError(where, KEY_ROW);
            }
            Integer height = intMember(entry, KEY_HEIGHT);
            if (height == null || height.intValue() <= 0)
            {
                return "A row height (" + where + ") needs a positive integer 'height'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            plan.rowHeights.add(new RowHeightPlan(row.intValue(), height.intValue()));
        }
        return null;
    }

    /**
     * Parses a rectangular region {@code {fromRow, fromCol, toRow, toCol}} shared by a merge and a named
     * area, validating that each index is a non-negative integer and that the first corner is not past
     * the last ({@code fromRow <= toRow}, {@code fromCol <= toCol}).
     */
    private static RegionResult parseRegion(JsonObject entry, String where)
    {
        Integer fromRow = nonNegative(entry, KEY_FROM_ROW);
        if (fromRow == null)
        {
            return RegionResult.failed(indexError(where, KEY_FROM_ROW));
        }
        Integer fromCol = nonNegative(entry, KEY_FROM_COL);
        if (fromCol == null)
        {
            return RegionResult.failed(indexError(where, KEY_FROM_COL));
        }
        Integer toRow = nonNegative(entry, KEY_TO_ROW);
        if (toRow == null)
        {
            return RegionResult.failed(indexError(where, KEY_TO_ROW));
        }
        Integer toCol = nonNegative(entry, KEY_TO_COL);
        if (toCol == null)
        {
            return RegionResult.failed(indexError(where, KEY_TO_COL));
        }
        if (toRow.intValue() < fromRow.intValue() || toCol.intValue() < fromCol.intValue())
        {
            return RegionResult.failed("A region (" + where + ") must have toRow >= fromRow and " //$NON-NLS-1$ //$NON-NLS-2$
                + "toCol >= fromCol; got fromRow=" + fromRow + ", fromCol=" + fromCol + ", toRow=" + toRow //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", toCol=" + toCol + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return RegionResult.ok(fromRow.intValue(), fromCol.intValue(), toRow.intValue(), toCol.intValue());
    }

    /**
     * Parses the optional {@code wrap} of a cell: a boolean ({@code true} -> {@link TextPlacement#WRAP},
     * {@code false} -> unset) or a {@link TextPlacement} literal name (case-insensitive). A bad token is a
     * ready error.
     */
    private static WrapResult parseWrap(JsonObject entry, String where)
    {
        if (!entry.has(KEY_WRAP))
        {
            return WrapResult.ok(null);
        }
        JsonElement element = entry.get(KEY_WRAP);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive())
        {
            return WrapResult.failed(wrapError(where, String.valueOf(element)));
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean())
        {
            return WrapResult.ok(primitive.getAsBoolean() ? TextPlacement.WRAP : null);
        }
        String token = primitive.getAsString();
        TextPlacement placement = resolveEnum(TextPlacement.values(), token);
        if (placement == null)
        {
            return WrapResult.failed(wrapError(where, token));
        }
        return WrapResult.ok(placement);
    }

    // ---- enum resolution ----------------------------------------------------------------------

    /**
     * Resolves an EMF enum literal by name, case-insensitively, matching the Java constant name, the EMF
     * literal, or the EMF name. Returns {@code null} for a blank or unknown token (the caller builds the
     * actionable error).
     */
    private static <E extends Enum<E> & Enumerator> E resolveEnum(E[] values, String token)
    {
        if (token == null)
        {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        for (E value : values)
        {
            if (trimmed.equalsIgnoreCase(value.name()) || trimmed.equalsIgnoreCase(value.getLiteral())
                || trimmed.equalsIgnoreCase(value.getName()))
            {
                return value;
            }
        }
        return null;
    }

    private static <E extends Enum<E> & Enumerator> String enumError(String where, String field,
        String token, E[] values)
    {
        return ERR_CELL + where + ") '" + field + "' must be one of " + enumTokens(values) + "; got '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + token + "'."; //$NON-NLS-1$
    }

    private static String wrapError(String where, String token)
    {
        return ERR_CELL + where + ") 'wrap' must be a boolean or one of " //$NON-NLS-1$
            + enumTokens(TextPlacement.values()) + "; got '" + token + "'."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static <E extends Enum<E>> String enumTokens(E[] values)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(values[i].name());
        }
        return sb.toString();
    }

    // ---- JSON member helpers ------------------------------------------------------------------

    /**
     * Reads a top-level key as an array of JSON objects: {@code null} when the key is present but is not
     * an array of objects (a shape error); an empty list when the key is absent.
     */
    private static List<JsonObject> objectArray(JsonObject spec, String key)
    {
        if (!spec.has(key) || spec.get(key).isJsonNull())
        {
            return new ArrayList<>();
        }
        JsonElement element = spec.get(key);
        if (!element.isJsonArray())
        {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : array)
        {
            if (item == null || !item.isJsonObject())
            {
                return null;
            }
            result.add(item.getAsJsonObject());
        }
        return result;
    }

    private static String notAnObjectArray(String key)
    {
        return "'" + key + "' must be an array of objects."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String indexError(String where, String field)
    {
        return "A '" + where + "' entry needs a non-negative integer '" + field + "'."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Reads a required non-negative integer, or {@code null} when absent / not a non-negative int (a
     * row / column index or a width / height are all non-negative integers). The caller builds the
     * actionable error from the entry's context.
     */
    private static Integer nonNegative(JsonObject obj, String name)
    {
        Integer value = intMember(obj, name);
        if (value == null || value.intValue() < 0)
        {
            return null;
        }
        return value;
    }

    private static Integer intMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement element = obj.get(name);
        if (element == null || !element.isJsonPrimitive())
        {
            return null;
        }
        try
        {
            double d = element.getAsDouble();
            if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            {
                return null;
            }
            return Integer.valueOf((int)d);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String stringMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement element = obj.get(name);
        return (element != null && element.isJsonPrimitive()) ? element.getAsString() : null;
    }

    private static String nonEmptyString(JsonObject obj, String name)
    {
        String value = stringMember(obj, name);
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static Boolean boolMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null; // NOSONAR intentional tri-state Boolean; null (absent) is distinct from false
        }
        JsonElement element = obj.get(name);
        if (element == null || !element.isJsonPrimitive())
        {
            return null; // NOSONAR intentional tri-state Boolean; null (absent) is distinct from false
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean())
        {
            return Boolean.valueOf(primitive.getAsBoolean());
        }
        String s = primitive.getAsString().trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.TRUE;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR intentional tri-state Boolean; an unrecognized token is treated as absent
    }

    // ---- parsed spec (pure data) --------------------------------------------------------------

    /** A parsed {@link Plan} OR a ready error message from up-front validation. */
    static final class ParseResult
    {
        final Plan plan;
        final String error;

        private ParseResult(Plan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static ParseResult ok(Plan plan)
        {
            return new ParseResult(plan, null);
        }

        static ParseResult failed(String error)
        {
            return new ParseResult(null, error);
        }
    }

    /** The validated, resolved spec ready to apply to a SpreadsheetDocument. */
    static final class Plan
    {
        final List<CellPlan> cells = new ArrayList<>();
        final List<MergePlan> merges = new ArrayList<>();
        final List<AreaPlan> areas = new ArrayList<>();
        final List<ColumnWidthPlan> columnWidths = new ArrayList<>();
        final List<RowHeightPlan> rowHeights = new ArrayList<>();

        boolean isEmpty()
        {
            return cells.isEmpty() && merges.isEmpty() && areas.isEmpty() && columnWidths.isEmpty()
                && rowHeights.isEmpty();
        }
    }

    /** A validated cell: its index, content (text XOR parameter) and resolved formatting. */
    static final class CellPlan
    {
        final int row;
        final int col;
        final String text;
        final String parameter;
        final boolean bold;
        final Integer fontSize;
        final HorizontalAlignment hAlign;
        final VerticalAlignment vAlign;
        final TextPlacement textPlacement;

        CellPlan(int row, int col, String text, String parameter, boolean bold, Integer fontSize, // NOSONAR S107: this IS the parameter object - an internal immutable holder mirroring the parsed JSON cell spec 1:1
            HorizontalAlignment hAlign, VerticalAlignment vAlign, TextPlacement textPlacement)
        {
            this.row = row;
            this.col = col;
            this.text = text;
            this.parameter = parameter;
            this.bold = bold;
            this.fontSize = fontSize;
            this.hAlign = hAlign;
            this.vAlign = vAlign;
            this.textPlacement = textPlacement;
        }
    }

    /** A validated merged region (inclusive corners). */
    static final class MergePlan
    {
        final int fromRow;
        final int fromCol;
        final int toRow;
        final int toCol;

        MergePlan(int fromRow, int fromCol, int toRow, int toCol)
        {
            this.fromRow = fromRow;
            this.fromCol = fromCol;
            this.toRow = toRow;
            this.toCol = toCol;
        }
    }

    /** A validated named area (a name + inclusive corners). */
    static final class AreaPlan
    {
        final String name;
        final int fromRow;
        final int fromCol;
        final int toRow;
        final int toCol;

        AreaPlan(String name, int fromRow, int fromCol, int toRow, int toCol)
        {
            this.name = name;
            this.fromRow = fromRow;
            this.fromCol = fromCol;
            this.toRow = toRow;
            this.toCol = toCol;
        }
    }

    /** A validated column width. */
    static final class ColumnWidthPlan
    {
        final int col;
        final int width;

        ColumnWidthPlan(int col, int width)
        {
            this.col = col;
            this.width = width;
        }
    }

    /** A validated row height. */
    static final class RowHeightPlan
    {
        final int row;
        final int height;

        RowHeightPlan(int row, int height)
        {
            this.row = row;
            this.height = height;
        }
    }

    /** The outcome of parsing a rectangular region (inclusive corners) or a ready error. */
    private static final class RegionResult
    {
        final int fromRow;
        final int fromCol;
        final int toRow;
        final int toCol;
        final String error;

        private RegionResult(int fromRow, int fromCol, int toRow, int toCol, String error)
        {
            this.fromRow = fromRow;
            this.fromCol = fromCol;
            this.toRow = toRow;
            this.toCol = toCol;
            this.error = error;
        }

        static RegionResult ok(int fromRow, int fromCol, int toRow, int toCol)
        {
            return new RegionResult(fromRow, fromCol, toRow, toCol, null);
        }

        static RegionResult failed(String error)
        {
            return new RegionResult(0, 0, 0, 0, error);
        }
    }

    /** The outcome of parsing a cell's {@code wrap} (a resolved {@link TextPlacement} or a ready error). */
    private static final class WrapResult
    {
        final TextPlacement value;
        final String error;

        private WrapResult(TextPlacement value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static WrapResult ok(TextPlacement value)
        {
            return new WrapResult(value, null);
        }

        static WrapResult failed(String error)
        {
            return new WrapResult(null, error);
        }
    }

    /**
     * The outcome of parsing a cell entry's optional style members ({@code bold} / {@code fontSize} /
     * {@code hAlign} / {@code vAlign} / {@code wrap}): the resolved values or a ready error.
     */
    private static final class CellStyleResult
    {
        final boolean bold;
        final Integer fontSize;
        final HorizontalAlignment hAlign;
        final VerticalAlignment vAlign;
        final TextPlacement wrap;
        final String error;

        private CellStyleResult(boolean bold, Integer fontSize, HorizontalAlignment hAlign,
            VerticalAlignment vAlign, TextPlacement wrap, String error)
        {
            this.bold = bold;
            this.fontSize = fontSize;
            this.hAlign = hAlign;
            this.vAlign = vAlign;
            this.wrap = wrap;
            this.error = error;
        }

        static CellStyleResult ok(boolean bold, Integer fontSize, HorizontalAlignment hAlign,
            VerticalAlignment vAlign, TextPlacement wrap)
        {
            return new CellStyleResult(bold, fontSize, hAlign, vAlign, wrap, null);
        }

        static CellStyleResult failed(String error)
        {
            return new CellStyleResult(false, null, null, null, null, error);
        }

        /** Whether any style member was set (what makes a content-less cell entry still meaningful). */
        boolean hasAnyStyle()
        {
            return bold || fontSize != null || hAlign != null || vAlign != null || wrap != null;
        }
    }
}
