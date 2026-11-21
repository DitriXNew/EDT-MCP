/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.mcore.Color;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.mcore.FontRef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.MutableFont;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.NamedItem;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.CellLine;
import com._1c.g5.v8.dt.moxel.content.CellLineStyle;
import com._1c.g5.v8.dt.moxel.content.ContentFactory;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com._1c.g5.v8.dt.moxel.content.HorizontalAlignment;
import com._1c.g5.v8.dt.moxel.content.LocalString;
import com._1c.g5.v8.dt.moxel.content.SpreadsheetLine;
import com._1c.g5.v8.dt.moxel.content.TextPlacement;
import com._1c.g5.v8.dt.moxel.content.VerticalAlignment;
import com._1c.g5.v8.dt.moxel.validation.NameValidationHelper;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
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
 * <li>Every {@link Format} attribute is UNSETTABLE, so "absent" and "set to the default value" are
 * distinct: an unset feature inherits from the row / column / document format, while an explicit
 * {@code 0} / {@code false} is a real value that overrides that
 * inheritance. The serializer writes each attribute only when {@code isSetXxx()}.</li>
 * <li>Auto width calculation / width weight factor are COLUMN properties: the platform reads them only
 * from the COLUMN's format ({@code SheetAccessor.isAutoWidthCalculation} /
 * {@code getWidthWeightFactor}), never from a cell's, and the weight factor is consulted only for a
 * column whose width IS auto-calculated.</li>
 * <li>A border is a {@link Format} int ({@code leftBorder} / {@code topBorder} / {@code rightBorder} /
 * {@code bottomBorder}) indexing the {@link SpreadsheetDocument#getLines()} pool of {@link CellLine}
 * ({@code style} + {@code width} + {@code gap}); ONE {@code borderColor} serves all four sides. The back
 * and text colours are {@link Format} ints indexing the {@link SpreadsheetDocument#getColors()} pool of
 * mcore {@link Color} (a {@code ColorDef} RGB, or a {@link ColorRef} to a style / palette / web /
 * windows colour). Both pools are interned like the font and format pools.</li>
 * </ul>
 *
 * <p>Removal mirrors EDT's template editor. Unmerge ({@code MergeAndUnmergeOperation}) drops every
 * {@code merges} entry touching the range and hands each freed cell the merged cell's format with only the
 * outer borders ({@code MoxelUtil.prepareRectForRemoveMerge}). Deleting a named area removes its
 * {@link SpreadsheetDocument#getNamedItems()} key ({@code SheetAccessor.removeName}); renaming re-keys the
 * same area, the name checked by EDT's own {@link NameValidationHelper#isCorrectName}.</p>
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
    private static final String KEY_UNMERGES = "unmerges"; //$NON-NLS-1$
    private static final String KEY_REMOVE_AREAS = "removeAreas"; //$NON-NLS-1$
    private static final String KEY_RENAME_AREAS = "renameAreas"; //$NON-NLS-1$

    // ---- per-entry keys -----------------------------------------------------------------------

    private static final String KEY_ROW = "row"; //$NON-NLS-1$
    private static final String KEY_COL = "col"; //$NON-NLS-1$
    private static final String KEY_TEXT = "text"; //$NON-NLS-1$
    private static final String KEY_PARAMETER = "parameter"; //$NON-NLS-1$
    private static final String KEY_REPLACE = "replace"; //$NON-NLS-1$
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
    private static final String KEY_TEXT_ORIENTATION = "textOrientation"; //$NON-NLS-1$
    private static final String KEY_AUTO_INDENT = "autoIndent"; //$NON-NLS-1$
    private static final String KEY_AUTO_MARK_INCOMPLETE = "autoMarkIncomplete"; //$NON-NLS-1$
    private static final String KEY_AUTO_WIDTH_CALCULATION = "autoWidthCalculation"; //$NON-NLS-1$
    private static final String KEY_WIDTH_WEIGHT_FACTOR = "widthWeightFactor"; //$NON-NLS-1$
    private static final String KEY_BORDERS = "borders"; //$NON-NLS-1$
    private static final String KEY_BORDER_COLOR = "borderColor"; //$NON-NLS-1$
    private static final String KEY_BACK_COLOR = "backColor"; //$NON-NLS-1$
    private static final String KEY_TEXT_COLOR = "textColor"; //$NON-NLS-1$
    private static final String KEY_STYLE = "style"; //$NON-NLS-1$
    private static final String KEY_NEW_NAME = "newName"; //$NON-NLS-1$

    /** The four border sides, in the order a {@link BorderPlan} holds them. */
    private static final String[] BORDER_SIDES = { "left", "top", "right", "bottom" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** The three colour keys of a cell, in the order a {@link CellPlan} holds them. */
    private static final String[] COLOR_KEYS = { KEY_BORDER_COLOR, KEY_BACK_COLOR, KEY_TEXT_COLOR };

    /** Cell-only format keys (a column / row band entry carries none of them). */
    private static final String[] CELL_ONLY_KEYS = { KEY_TEXT_ORIENTATION, KEY_AUTO_INDENT, KEY_AUTO_MARK_INCOMPLETE,
        KEY_BORDERS, KEY_BORDER_COLOR, KEY_BACK_COLOR, KEY_TEXT_COLOR };

    /** Column-only format keys (a cell / row entry carries neither). */
    private static final String[] COLUMN_ONLY_KEYS = { KEY_AUTO_WIDTH_CALCULATION, KEY_WIDTH_WEIGHT_FACTOR };

    /** Every key a cells entry may carry. */
    private static final String[] CELL_KEYS = { KEY_ROW, KEY_COL, KEY_REPLACE, KEY_TEXT, KEY_PARAMETER, KEY_BOLD,
        KEY_FONT_SIZE, KEY_H_ALIGN, KEY_V_ALIGN, KEY_WRAP, KEY_TEXT_ORIENTATION, KEY_AUTO_INDENT,
        KEY_AUTO_MARK_INCOMPLETE, KEY_BORDERS, KEY_BORDER_COLOR, KEY_BACK_COLOR, KEY_TEXT_COLOR };

    /**
     * Width bounds of a drawn border line - the spinner range of EDT's line dialog
     * ({@code LineSelectionDialog(shell, line)} delegates with {@code minWidth=1, maxWidth=3}).
     */
    private static final int MIN_LINE_WIDTH = 1;
    private static final int MAX_LINE_WIDTH = 3;

    /** How many existing area names a refusal lists before it summarizes the rest. */
    private static final int MAX_LISTED_NAMES = 25;

    /**
     * The most cells one call's unmerges may re-format. A merge stores only its rectangle, but unmerging
     * it gives EVERY cell of that rectangle its own format (as EDT's editor does), so an accidental
     * whole-sheet merge would otherwise turn into millions of model objects inside the write transaction.
     * A print form's merged header or total spans a few dozen cells.
     */
    static final long MAX_UNMERGE_CELLS = 10_000L;

    /** Every key a columnWidths entry may carry. */
    private static final String[] COLUMN_KEYS = { KEY_COL, KEY_WIDTH, KEY_AUTO_WIDTH_CALCULATION,
        KEY_WIDTH_WEIGHT_FACTOR };

    /** Every key a rowHeights entry may carry. */
    private static final String[] ROW_KEYS = { KEY_ROW, KEY_HEIGHT };

    /**
     * Upper bound of {@link Format#setTextOrientation(int)}. The platform stores the rotation in TENTHS of
     * a degree ({@code MoxelControl} writes the editor's degree value times ten), and EDT's cell-property
     * spinner accepts 0..360 degrees - so the model range is 0..3600.
     */
    private static final int MAX_TEXT_ORIENTATION = 3600;

    /** Upper bound of {@link Format#setAutoIndent(int)} - EDT's cell-property spinner is 0..100. */
    private static final int MAX_AUTO_INDENT = 100;

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
        /** Number of cells written (created, merged or replaced). */
        public final int cells;
        /** Number of merges added. */
        public final int merges;
        /** Number of named areas added. */
        public final int areas;
        /** Number of column widths applied. */
        public final int columnWidths;
        /** Number of row heights applied. */
        public final int rowHeights;
        /** Number of existing merges the {@code unmerges} ranges removed. */
        public final int unmerges;
        /** Number of named areas removed. */
        public final int removedAreas;
        /** Number of named areas renamed. */
        public final int renamedAreas;

        private Result(String error, Plan plan, int unmerges)
        {
            this.error = error;
            this.cells = plan == null ? 0 : plan.cells.size();
            this.merges = plan == null ? 0 : plan.merges.size();
            this.areas = plan == null ? 0 : plan.areas.size();
            this.columnWidths = plan == null ? 0 : plan.columnWidths.size();
            this.rowHeights = plan == null ? 0 : plan.rowHeights.size();
            this.unmerges = unmerges;
            this.removedAreas = plan == null ? 0 : plan.removeAreas.size();
            this.renamedAreas = plan == null ? 0 : plan.renameAreas.size();
        }

        static Result failed(String error)
        {
            return new Result(error, null, 0);
        }

        static Result ok(Plan plan, int unmerges)
        {
            return new Result(null, plan, unmerges);
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
        return apply(document, spec, null);
    }

    /**
     * Applies a {@code template} spec, refusing the members the project's 1C runtime version cannot
     * persist. The moxel serializer writes {@code autoWidthCalculation} / {@code widthWeightFactor} only
     * for a project on 8.3.10 or later, so on an older one they would be accepted, mutated into the model
     * and then silently dropped on export.
     *
     * @param document the template's SpreadsheetDocument content (must not be {@code null})
     * @param spec the {@code template} payload (see the class javadoc for the shape)
     * @param projectVersion the project's 1C runtime version ({@code IV8Project.getVersion()}), or
     *            {@code null} to skip the version check
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    public static Result apply(SpreadsheetDocument document, JsonObject spec, Version projectVersion)
    {
        // The catalogue is looked up only when a colour is given: a payload without one never touches it.
        return apply(document, spec, projectVersion,
            raw -> AppearanceValueBuilder.buildColor(raw, null, AppearanceValueBuilder.colorCatalogue(projectVersion)));
    }

    /**
     * Builds a cell colour ({@code borderColor} / {@code backColor} / {@code textColor}) from its
     * {@code {color: ...}}-wrapped JSON, in the grammar of a form item's colour. The caller owning the
     * write transaction supplies one that also resolves (and binds) the configuration's style / palette
     * items; {@link SpreadsheetTemplateWriter#apply(SpreadsheetDocument, JsonObject, Version)} resolves
     * platform colours only.
     */
    @FunctionalInterface
    public interface ColorBuilder
    {
        /**
         * @param wrapped the colour as {@code {color: <value>}}
         * @return the built colour, a clear ({@code 'auto'}) or an actionable error
         */
        AppearanceValueBuilder.Result build(JsonElement wrapped);
    }

    /**
     * The colour builder for a template of a project. EDT's spreadsheet reader resolves a stored
     * {@code Style.<Name>} / {@code Palette.<Name>} to the PLATFORM colour first and only then to the
     * configuration's item ({@code MoxelUtil.getRegistryColor}), the reverse of the form designer. The
     * reference is built in that same order, so the colour a cell shows does not change when the template
     * is read back.
     *
     * @param configuration supplies the configuration read in the caller's write transaction, or
     *            {@code null} for one that offers platform colours only (an external-objects project)
     * @param projectVersion the project's 1C runtime version (selects the platform colour catalogue)
     * @return the builder
     */
    public static ColorBuilder projectColors(Supplier<Configuration> configuration, Version projectVersion)
    {
        return wrapped -> {
            IEObjectProvider catalogue = AppearanceValueBuilder.colorCatalogue(projectVersion);
            AppearanceValueBuilder.Result platform = AppearanceValueBuilder.buildColor(wrapped, null, catalogue);
            Configuration own = platform.lookupStatus != PlatformCatalogue.Status.NOT_FOUND || configuration == null
                ? null : configuration.get();
            return own == null ? platform : AppearanceValueBuilder.buildColor(wrapped, own, catalogue);
        };
    }

    /**
     * Applies a {@code template} spec with the given colour builder (see {@link ColorBuilder}).
     *
     * @param document the template's SpreadsheetDocument content (must not be {@code null})
     * @param spec the {@code template} payload (see the class javadoc for the shape)
     * @param projectVersion the project's 1C runtime version, or {@code null} to skip the version check
     * @param colors builds a cell colour; must not be {@code null}
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    public static Result apply(SpreadsheetDocument document, JsonObject spec, Version projectVersion,
        ColorBuilder colors)
    {
        if (document == null)
        {
            return Result.failed(ToolResult.error(
                "The template has no SpreadsheetDocument content to write to.").toJson()); //$NON-NLS-1$
        }
        ParseResult parsed = parse(spec, projectVersion, colors);
        if (parsed.error != null)
        {
            return Result.failed(ToolResult.error(parsed.error).toJson());
        }
        Plan plan = parsed.plan;
        // The area names are checked against the document before anything changes, so a refused
        // removal / rename leaves the document untouched like every other refusal.
        String areaError = areaNamesError(document, plan);
        if (areaError == null)
        {
            areaError = unmergeSizeError(document, plan);
        }
        if (areaError != null)
        {
            return Result.failed(ToolResult.error(areaError).toJson());
        }

        // Reserve a neutral format at index 0 on an empty pool: a cell's formatIndex defaults to 0, so a
        // styled format must never be interned at index 0 (it would silently style every un-formatted
        // cell). Only acts on an empty pool, so it never shifts existing formatIndex references.
        ensureBaseFormat(document);

        // Removals run first, so one call can clear an old layout and author the new one.
        // Which merges have a top-left cell is decided once, before anything changes - the same snapshot
        // the size check counted, so a cell materialized by an earlier unmerge cannot enlarge the work.
        java.util.Set<Merge> anchored = anchoredMerges(document, plan);
        int unmerged = 0;
        for (MergePlan unmerge : plan.unmerges)
        {
            unmerged += applyUnmerge(document, unmerge, anchored);
        }
        for (String name : plan.removeAreas)
        {
            document.getNamedItems().removeKey(name);
        }
        for (String[] rename : plan.renameAreas)
        {
            // Read the area BEFORE removing its key: the value removeKey hands back is not the
            // contained area object (measured on moxel 19.0.0), so re-keying it would put a stand-in.
            NamedItem item = document.getNamedItems().get(rename[0]);
            document.getNamedItems().removeKey(rename[0]);
            document.getNamedItems().put(rename[1], item);
        }

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

        return Result.ok(plan, unmerged);
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
        boolean mergeExisting = !plan.replace && row.getCells().containsKey(Integer.valueOf(plan.col));
        Cell cell = getOrCreateCell(row, plan.col);

        if (plan.replace)
        {
            cell.setText(null);
            cell.setParameter(null);
            cell.setFormatIndex(0);
        }

        applyCellContent(cell, plan);
        applyCellFormat(document, cell, plan, mergeExisting);
    }

    /**
     * Sets the cell's content: its report parameter name, or its literal text as a {@link LocalString}
     * keyed by the neutral language code. Empty strings clear the named content; omitted keys keep it.
     */
    private static void applyCellContent(Cell cell, CellPlan plan)
    {
        if (plan.parameterPresent)
        {
            cell.setParameter(plan.parameter);
            if (plan.parameter != null)
            {
                cell.setText(null);
            }
        }
        if (plan.textPresent)
        {
            LocalString text = null;
            if (plan.text != null)
            {
                text = ContentFactory.eINSTANCE.createLocalString();
                text.getContent().put(NEUTRAL_LANGUAGE_KEY, plan.text);
                cell.setParameter(null);
            }
            cell.setText(text);
        }
    }

    /**
     * Points the cell's {@code formatIndex} at an interned {@link Format} carrying the plan's font /
     * alignment / wrap / fill, preserving omitted features and never mutating a pooled entry in place.
     */
    private static void applyCellFormat(SpreadsheetDocument document, Cell cell, CellPlan plan, boolean mergeExisting)
    {
        Format format = plan.replace ? MoxelFactory.eINSTANCE.createFormat()
            : EcoreUtil.copy(document.getFormats().get(cell.getFormatIndex()));
        Integer fontIndex = null;
        if (plan.boldPresent || plan.fontSize != null)
        {
            Format fontFormat = mergeExisting && !format.isSetFont() ? inheritedFontFormat(document, plan) : format;
            fontIndex = internFont(document, fontFormat, plan);
        }
        FillType fillType = null;
        if (plan.parameter != null)
        {
            fillType = FillType.PARAMETER;
        }
        else if (plan.text != null || (plan.parameterPresent && format.getFillType() == FillType.PARAMETER))
        {
            fillType = FillType.TEXT;
        }
        applyCellFormatMembers(format, fontIndex, plan, fillType);
        applyCellBordersAndColors(document, format, plan);
        cell.setFormatIndex(internFormat(document, format));
    }

    /**
     * Sets the supplied border sides (interned {@link CellLine}s) and colours (interned {@link Color}s)
     * on a private format copy; an omitted side / colour keeps its set-state, a cleared colour
     * ({@code 'auto'}) is unset so it inherits again.
     */
    private static void applyCellBordersAndColors(SpreadsheetDocument document, Format format, CellPlan plan)
    {
        if (plan.borders != null)
        {
            BorderPlan[] sides = plan.borders.sides;
            if (sides[0] != null)
            {
                format.setLeftBorder(internLine(document, sides[0]));
            }
            if (sides[1] != null)
            {
                format.setTopBorder(internLine(document, sides[1]));
            }
            if (sides[2] != null)
            {
                format.setRightBorder(internLine(document, sides[2]));
            }
            if (sides[3] != null)
            {
                format.setBottomBorder(internLine(document, sides[3]));
            }
        }
        ColorPlan border = plan.colors[0];
        if (border != null)
        {
            if (border.color == null)
            {
                format.unsetBorderColor();
            }
            else
            {
                format.setBorderColor(internColor(document, border.color));
            }
        }
        ColorPlan back = plan.colors[1];
        if (back != null)
        {
            if (back.color == null)
            {
                format.unsetBackColor();
            }
            else
            {
                format.setBackColor(internColor(document, back.color));
            }
        }
        ColorPlan text = plan.colors[2];
        if (text != null)
        {
            if (text.color == null)
            {
                format.unsetTextColor();
            }
            else
            {
                format.setTextColor(internColor(document, text.color));
            }
        }
    }

    /**
     * Applies only supplied members to a private format copy; omitted features retain their set-state.
     */
    private static void applyCellFormatMembers(Format format, Integer fontIndex, CellPlan plan, FillType fillType)
    {
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
        else if (plan.wrapPresent)
        {
            format.unsetTextPlacement();
        }
        if (plan.textOrientation != null)
        {
            format.setTextOrientation(plan.textOrientation.intValue());
        }
        if (plan.autoIndent != null)
        {
            format.setAutoIndent(plan.autoIndent.intValue());
        }
        if (plan.autoMarkIncomplete != null)
        {
            format.setAutoMarkIncomplete(plan.autoMarkIncomplete.booleanValue());
        }
        if (fillType != null)
        {
            if (fillType == FillType.TEXT)
            {
                format.unsetFillType();
            }
            else
            {
                format.setFillType(fillType);
            }
        }
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
     * Unmerges a range the way EDT's template editor does ({@code MergeAndUnmergeOperation} with
     * {@code doMerge=false} over a cell selection): every cell {@link Merge} touching the range is removed
     * ({@code SheetAccessor.getMergesInRect} collects the merge of each cell, {@code removeCellsMerge}
     * drops it), and the cells it covered are re-formatted by {@link #distributeMergedFormat}.
     *
     * @return how many merges were removed
     */
    private static int applyUnmerge(SpreadsheetDocument document, MergePlan plan, java.util.Set<Merge> anchored)
    {
        List<Merge> touching = touchingMerges(document, plan);
        for (Merge merge : touching)
        {
            Rect position = merge.getPosition();
            document.getMerges().remove(merge);
            if (anchored.contains(merge))
            {
                distributeMergedFormat(document, position);
            }
        }
        return touching.size();
    }

    /** The merges the unmerges touch that have a top-left cell now, before anything changes. */
    private static java.util.Set<Merge> anchoredMerges(SpreadsheetDocument document, Plan plan)
    {
        java.util.Set<Merge> anchored = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (MergePlan unmerge : plan.unmerges)
        {
            for (Merge merge : touchingMerges(document, unmerge))
            {
                if (topLeftCell(document, merge.getPosition()) != null)
                {
                    anchored.add(merge);
                }
            }
        }
        return anchored;
    }

    /** The cell merges of the document whose rectangle shares at least one cell with the range. */
    private static List<Merge> touchingMerges(SpreadsheetDocument document, MergePlan plan)
    {
        List<Merge> touching = new ArrayList<>();
        for (Merge merge : document.getMerges())
        {
            Rect position = merge.getPosition();
            // long arithmetic: a stored far corner may sit at the top of the int range.
            if (position != null && position.getX() <= plan.toCol
                && (long)position.getX() + position.getWidth() >= plan.fromCol && position.getY() <= plan.toRow
                && (long)position.getY() + position.getHeight() >= plan.fromRow)
            {
                touching.add(merge);
            }
        }
        return touching;
    }

    /**
     * Refuses unmerges that would re-format more than {@link #MAX_UNMERGE_CELLS} cells in total, before
     * anything changes. Each merge counts once, however many ranges touch it, and a merge without a top-left
     * cell counts nothing: removing it re-formats no cell ({@link #distributeMergedFormat}).
     */
    static String unmergeSizeError(SpreadsheetDocument document, Plan plan)
    {
        java.util.Set<Merge> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<Merge> anchored = anchoredMerges(document, plan);
        long cells = 0;
        for (MergePlan unmerge : plan.unmerges)
        {
            for (Merge merge : touchingMerges(document, unmerge))
            {
                if (counted.add(merge) && anchored.contains(merge))
                {
                    Rect position = merge.getPosition();
                    cells += ((long)position.getWidth() + 1) * ((long)position.getHeight() + 1);
                }
                if (cells > MAX_UNMERGE_CELLS)
                {
                    Rect position = merge.getPosition();
                    return "The unmerges of this call would re-format " + cells + " cells (the merge from row " //$NON-NLS-1$ //$NON-NLS-2$
                        + position.getY() + ", col " + position.getX() + " to row " //$NON-NLS-1$ //$NON-NLS-2$
                        + ((long)position.getY() + position.getHeight()) + ", col " //$NON-NLS-1$
                        + ((long)position.getX() + position.getWidth())
                        + " among them); at most " + MAX_UNMERGE_CELLS + " are allowed, because every freed cell " //$NON-NLS-1$ //$NON-NLS-2$
                        + "gets its own format. Unmerge it in EDT's template editor, or delete the template " //$NON-NLS-1$
                        + "and create it again."; //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /**
     * Mirrors EDT's {@code MoxelUtil.prepareRectForRemoveMerge}: the merged range showed the format of its
     * top-left cell, so once the merge is gone every cell of the range receives that format, keeping a
     * border only on the side that lies on the range's outer edge (inner sides explicitly bind the empty line).
     *
     * <p>Two deliberate departures, both because a merge this tool writes may cover POPULATED cells (EDT's
     * editor empties the covered cells when it merges, so it never meets content there): a covered cell
     * that holds text or a parameter keeps its own fill type (the top-left cell's parameter fill would make
     * the serializer drop that cell's text, and vice versa), and a range whose top-left cell does not exist
     * keeps its cells as they are (EDT removes them, which would discard their content).</p>
     */
    private static void distributeMergedFormat(SpreadsheetDocument document, Rect range)
    {
        int top = range.getY();
        int left = range.getX();
        Cell topLeft = topLeftCell(document, range);
        if (topLeft == null)
        {
            return; // anchored at the start of the call; an overlapping earlier unmerge cannot remove a cell
        }
        Format source = document.getFormats().get(topLeft.getFormatIndex());
        int emptyLine = internLine(document, new BorderPlan(CellLineStyle.NONE, 0));
        // Offsets, not coordinates: a far corner at the top of the int range must not wrap the loop.
        for (int dy = 0; dy <= range.getHeight(); dy++)
        {
            for (int dx = 0; dx <= range.getWidth(); dx++)
            {
                Format format = EcoreUtil.copy(source);
                if (dy != 0)
                {
                    format.setTopBorder(emptyLine);
                }
                if (dy != range.getHeight())
                {
                    format.setBottomBorder(emptyLine);
                }
                if (dx != 0)
                {
                    format.setLeftBorder(emptyLine);
                }
                if (dx != range.getWidth())
                {
                    format.setRightBorder(emptyLine);
                }
                Cell cell = getOrCreateCell(getOrCreateRow(document, top + dy), left + dx);
                if (cell != topLeft && (cell.getText() != null || cell.getParameter() != null))
                {
                    keepOwnFill(document.getFormats().get(cell.getFormatIndex()), format);
                }
                cell.setFormatIndex(internFormat(document, format));
            }
        }
    }

    /** The stored top-left cell of a merged range, or {@code null} when that cell does not exist. */
    private static Cell topLeftCell(SpreadsheetDocument document, Rect range)
    {
        Row topRow = document.getRows().get(Integer.valueOf(range.getY()));
        return topRow == null ? null : topRow.getCells().get(Integer.valueOf(range.getX()));
    }

    /** Gives {@code target} the fill-type set-state of a populated cell's own {@code current} format. */
    private static void keepOwnFill(Format current, Format target)
    {
        if (current.isSetFillType())
        {
            target.setFillType(current.getFillType());
        }
        else
        {
            target.unsetFillType();
        }
    }

    /**
     * Refuses an area removal / rename the document cannot take, simulating the requested order: a
     * removed or renamed name must exist as a cell area, a new name must be a valid area name (EDT's own
     * {@link NameValidationHelper#isCorrectName}) and must not clash with a name still in use - compared
     * ignoring case, as BSL looks an area up. The refusal lists the existing area names.
     */
    static String areaNamesError(SpreadsheetDocument document, Plan plan)
    {
        if (plan.removeAreas.isEmpty() && plan.renameAreas.isEmpty())
        {
            return null;
        }
        Map<String, NamedItem> existing = document.getNamedItems().map();
        TreeSet<String> names = new TreeSet<>(existing.keySet());
        java.util.Set<String> renamedTo = new java.util.LinkedHashSet<>();
        for (int i = 0; i < plan.removeAreas.size(); i++)
        {
            String name = plan.removeAreas.get(i);
            String error = areaToChangeError(existing, names, name, KEY_REMOVE_AREAS + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (error != null)
            {
                return error;
            }
            names.remove(name);
        }
        for (int i = 0; i < plan.renameAreas.size(); i++)
        {
            String[] rename = plan.renameAreas.get(i);
            String where = KEY_RENAME_AREAS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            String error = areaToChangeError(existing, names, rename[0], where);
            if (error != null)
            {
                return error;
            }
            names.remove(rename[0]);
            String clash = ignoringCase(names, rename[1]);
            if (clash != null)
            {
                return "A '" + where + "' entry renames '" + rename[0] + "' to '" + rename[1] //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "', but an area named '" + clash + "' already exists (area names ignore case). Remove " //$NON-NLS-1$ //$NON-NLS-2$
                    + "it first with removeAreas, or choose another name."; //$NON-NLS-1$
            }
            names.add(rename[1]);
            renamedTo.remove(rename[0]);
            renamedTo.add(rename[1]);
        }
        // Additions run after every rename, so only the names the renamed areas END UP with can clash.
        for (String target : renamedTo)
        {
            for (AreaPlan added : plan.areas)
            {
                if (added.name.equalsIgnoreCase(target))
                {
                    return "The renameAreas entries leave an area named '" + target + "', and the same call adds " //$NON-NLS-1$ //$NON-NLS-2$
                        + "the area '" + added.name + "' (area names ignore case), which would replace the renamed " //$NON-NLS-1$ //$NON-NLS-2$
                        + "one. Choose another name for one of them."; //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /** The refusal for a removed / renamed name that is not (any longer) a cell area of the document. */
    private static String areaToChangeError(Map<String, NamedItem> existing, TreeSet<String> names, String name,
        String where)
    {
        if (names.contains(name))
        {
            NamedItem item = existing.get(name);
            if (item != null && !(item instanceof NamedItemCells))
            {
                return "A '" + where + "' entry names '" + name + "', which is a " + item.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ", not a cell area; only cell areas can be removed or renamed here - edit it in " //$NON-NLS-1$
                    + "EDT's template editor."; //$NON-NLS-1$
            }
            return null;
        }
        String similar = ignoringCase(names, name);
        String hint = similar == null ? "" //$NON-NLS-1$
            : " Did you mean '" + similar + "'? Area names are matched exactly here."; //$NON-NLS-1$ //$NON-NLS-2$
        return "A '" + where + "' entry names the area '" + name + "', which the template does not have" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (existing.containsKey(name) ? " any longer (an earlier entry removed or renamed it)" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + "." + hint + " Existing areas: " + listNames(names) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The name in {@code names} equal to {@code name} ignoring case, or {@code null}. */
    private static String ignoringCase(TreeSet<String> names, String name)
    {
        for (String candidate : names)
        {
            if (candidate.equalsIgnoreCase(name))
            {
                return candidate;
            }
        }
        return null;
    }

    private static String listNames(TreeSet<String> names)
    {
        if (names.isEmpty())
        {
            return "none"; //$NON-NLS-1$
        }
        List<String> listed = new ArrayList<>(names);
        String shown = String.join(", ", listed.subList(0, Math.min(MAX_LISTED_NAMES, listed.size()))); //$NON-NLS-1$
        return listed.size() > MAX_LISTED_NAMES
            ? shown + ", ... (" + (listed.size() - MAX_LISTED_NAMES) + " more)" : shown; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets a column's sizing: the width, the auto-width flag and the width weight factor all live on a
     * {@link Format} referenced by the {@link Column}'s {@code formatIndex}. The column band's declared
     * size is grown to cover the column index so the sized column is part of the sheet. The column's
     * format is REPLACED (not merged), mirroring the cell overwrite semantics.
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
        if (plan.width != null)
        {
            format.setWidth(plan.width.intValue());
        }
        if (plan.autoWidthCalculation != null)
        {
            format.setAutoWidthCalculation(plan.autoWidthCalculation.booleanValue());
        }
        if (plan.widthWeightFactor != null)
        {
            format.setWidthWeightFactor(plan.widthWeightFactor.intValue());
        }
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
     * Resolves an existing cell's inherited font in EDT's order (SheetAccessor.getCellFullFormat and
     * ReadOnlyCellFullFormat.getFont): row, row-specific or document column, then document default.
     * The column band's own format is not a cell-font source.
     */
    private static Format inheritedFontFormat(SpreadsheetDocument document, CellPlan plan)
    {
        Row row = document.getRows().get(Integer.valueOf(plan.row));
        Format rowFormat = document.getFormats().get(row.getFormatIndex());
        if (rowFormat.isSetFont())
        {
            return rowFormat;
        }
        Columns columns = row.getColumns() != null ? row.getColumns() : document.getColumns();
        Column column = columns == null ? null : columns.getColumns().get(Integer.valueOf(plan.col));
        if (column != null)
        {
            Format columnFormat = document.getFormats().get(column.getFormatIndex());
            if (columnFormat.isSetFont())
            {
                return columnFormat;
            }
        }
        return document.getFormats().get(document.getDefaultFormatIndex());
    }

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
     * Copies the existing font and applies supplied members through the shared font writer, then interns it.
     */
    private static int internFont(SpreadsheetDocument document, Format format, CellPlan plan)
    {
        Font desired = format.isSetFont() ? EcoreUtil.copy(document.getFonts().get(format.getFont()))
            : McoreFactory.eINSTANCE.createFontDef();
        if (!(desired instanceof MutableFont))
        {
            FontRef reference = McoreFactory.eINSTANCE.createFontRef();
            reference.setFont(document.getFonts().get(format.getFont()));
            desired = reference;
        }
        JsonObject members = new JsonObject();
        if (plan.boldPresent)
        {
            members.addProperty(KEY_BOLD, plan.bold);
        }
        if (plan.fontSize != null)
        {
            members.addProperty(KEY_HEIGHT, plan.fontSize);
        }
        StyleValueBuilder.applyFontMembers(desired, StyleValueBuilder.parseFontMembers(members, false));
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
     * Interns a border line into the document's lines pool. An equal line already pooled is reused -
     * {@link EcoreUtil#equals} is the comparison EDT's own {@code SheetAccessor.getCellLineIndex} makes.
     */
    private static int internLine(SpreadsheetDocument document, BorderPlan plan)
    {
        CellLine line = ContentFactory.eINSTANCE.createCellLine();
        line.setStyle(plan.style);
        line.setWidth(plan.width);
        line.setGap(false);
        EList<SpreadsheetLine> lines = document.getLines();
        for (int i = 0; i < lines.size(); i++)
        {
            if (lines.get(i) instanceof CellLine && EcoreUtil.equals(lines.get(i), line))
            {
                return i;
            }
        }
        lines.add(line);
        return lines.size() - 1;
    }

    /** Interns a colour into the document's colours pool (see {@link #sameColor}). */
    private static int internColor(SpreadsheetDocument document, Color color)
    {
        EList<Color> colors = document.getColors();
        for (int i = 0; i < colors.size(); i++)
        {
            if (sameColor(colors.get(i), color))
            {
                return i;
            }
        }
        colors.add(color);
        return colors.size() - 1;
    }

    /**
     * Whether two pooled colours are the same colour. A {@link ColorRef} is the same only when it names
     * the same target (the same object, or a proxy of the same URI): a structural compare would follow the
     * reference into the target's RGB and wrongly reuse another style item that happens to hold the same
     * value. Any other colour (an RGB {@code ColorDef}) compares structurally.
     */
    private static boolean sameColor(Color a, Color b)
    {
        if (a.eClass() != b.eClass())
        {
            return false;
        }
        if (a instanceof ColorRef)
        {
            Object targetA = a.eGet(McorePackage.Literals.COLOR_REF__COLOR, false);
            Object targetB = b.eGet(McorePackage.Literals.COLOR_REF__COLOR, false);
            if (targetA == targetB)
            {
                return targetA != null;
            }
            if (targetA instanceof EObject && targetB instanceof EObject
                && (((EObject)targetA).eIsProxy() || ((EObject)targetB).eIsProxy()))
            {
                return Objects.equals(EcoreUtil.getURI((EObject)targetA), EcoreUtil.getURI((EObject)targetB));
            }
            return false;
        }
        return EcoreUtil.equals(a, b);
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
        return parse(spec, null);
    }

    /**
     * Parses + validates a {@code template} spec, additionally refusing the column-sizing members a
     * project older than 8.3.10 cannot persist.
     *
     * @param spec the {@code template} payload
     * @param projectVersion the project's 1C runtime version, or {@code null} to skip the version check
     * @return a {@link ParseResult} - its {@link ParseResult#error} is non-null on invalid input
     */
    static ParseResult parse(JsonObject spec, Version projectVersion)
    {
        return parse(spec, projectVersion, raw -> AppearanceValueBuilder.buildColor(raw, null, null));
    }

    /**
     * Parses + validates a {@code template} spec, building each cell colour with {@code colors}. Touches
     * no model: a colour is built detached (a named one is only looked up).
     *
     * @param spec the {@code template} payload
     * @param projectVersion the project's 1C runtime version, or {@code null} to skip the version check
     * @param colors builds a cell colour
     * @return a {@link ParseResult} - its {@link ParseResult#error} is non-null on invalid input
     */
    static ParseResult parse(JsonObject spec, Version projectVersion, ColorBuilder colors)
    {
        if (spec == null)
        {
            return ParseResult.failed("A 'template' payload is required, e.g. {cells:[{row:0,col:0," //$NON-NLS-1$
                + "text:'Total'}]}."); //$NON-NLS-1$
        }
        Plan plan = new Plan();

        String error = parseRemovals(spec, plan);
        if (error == null)
        {
            error = parseCells(spec, plan, colors);
        }
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
            error = parseColumnWidths(spec, plan, projectVersion);
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
                + "'merges', 'areas', 'columnWidths', 'rowHeights', 'unmerges', 'removeAreas' or " //$NON-NLS-1$
                + "'renameAreas', e.g. {cells:[{row:0,col:0,text:'Total'}]}."); //$NON-NLS-1$
        }
        return ParseResult.ok(plan);
    }

    /**
     * Parses the removals: {@code unmerges} ranges (a single cell is a valid range), {@code removeAreas}
     * names and {@code renameAreas} {@code {name, newName}} pairs. Whether a name exists is checked later
     * against the document ({@link #areaNamesError}); here only the shape and the new name's validity.
     */
    private static String parseRemovals(JsonObject spec, Plan plan)
    {
        List<JsonObject> unmerges = objectArray(spec, KEY_UNMERGES);
        if (unmerges == null)
        {
            return notAnObjectArray(KEY_UNMERGES);
        }
        for (int i = 0; i < unmerges.size(); i++)
        {
            String where = KEY_UNMERGES + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            String unknown = unknownKeyError(unmerges.get(i), where, KEY_FROM_ROW, KEY_FROM_COL, KEY_TO_ROW, KEY_TO_COL);
            if (unknown != null)
            {
                return unknown;
            }
            RegionResult region = parseRegion(unmerges.get(i), where);
            if (region.error != null)
            {
                return region.error;
            }
            plan.unmerges.add(new MergePlan(region.fromRow, region.fromCol, region.toRow, region.toCol));
        }
        String error = parseRemoveAreas(spec, plan);
        return error != null ? error : parseRenameAreas(spec, plan);
    }

    private static String parseRemoveAreas(JsonObject spec, Plan plan)
    {
        if (!has(spec, KEY_REMOVE_AREAS))
        {
            return null;
        }
        JsonElement element = spec.get(KEY_REMOVE_AREAS);
        String shape = "'" + KEY_REMOVE_AREAS + "' must be an array of area names, e.g. " //$NON-NLS-1$ //$NON-NLS-2$
            + "removeAreas:['Header']."; //$NON-NLS-1$
        if (!element.isJsonArray())
        {
            return shape;
        }
        for (JsonElement name : element.getAsJsonArray())
        {
            if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()
                || name.getAsString().isEmpty())
            {
                return shape + " Got " + name + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            plan.removeAreas.add(name.getAsString());
        }
        return null;
    }

    private static String parseRenameAreas(JsonObject spec, Plan plan)
    {
        List<JsonObject> entries = objectArray(spec, KEY_RENAME_AREAS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_RENAME_AREAS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            JsonObject entry = entries.get(i);
            String where = KEY_RENAME_AREAS + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            String unknown = unknownKeyError(entry, where, KEY_NAME, KEY_NEW_NAME);
            if (unknown != null)
            {
                return unknown;
            }
            String name = nonEmptyString(entry, KEY_NAME);
            String newName = nonEmptyString(entry, KEY_NEW_NAME);
            if (name == null || newName == null)
            {
                return "A '" + where + "' entry needs a non-empty 'name' (the existing area) and 'newName'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!NameValidationHelper.isCorrectName(newName))
            {
                return "A '" + where + "' entry's newName '" + newName + "' is not a valid area name: EDT's " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "template editor allows only Latin letters, Russian letters other than yo, digits and " //$NON-NLS-1$
                    + "'_'."; //$NON-NLS-1$
            }
            plan.renameAreas.add(new String[] { name, newName });
        }
        return null;
    }

    private static String parseCells(JsonObject spec, Plan plan, ColorBuilder colors)
    {
        List<JsonObject> entries = objectArray(spec, KEY_CELLS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_CELLS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            String error = parseCell(entries.get(i), i, plan, colors);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    private static String parseCell(JsonObject entry, int index, Plan plan, ColorBuilder colors)
    {
        String where = KEY_CELLS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        String misplaced = misplacedColumnKeyError(entry, where);
        if (misplaced == null)
        {
            misplaced = unknownKeyError(entry, where, CELL_KEYS);
        }
        if (misplaced != null)
        {
            return misplaced;
        }
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

        if (has(entry, KEY_REPLACE) && (!entry.get(KEY_REPLACE).isJsonPrimitive()
            || !entry.getAsJsonPrimitive(KEY_REPLACE).isBoolean()))
        {
            return ERR_CELL + where + ") 'replace' must be a boolean, got " + entry.get(KEY_REPLACE) //$NON-NLS-1$
                + "; use replace:true for full replacement."; //$NON-NLS-1$
        }

        for (String key : new String[] { KEY_TEXT, KEY_PARAMETER })
        {
            if (has(entry, key) && (!entry.get(key).isJsonPrimitive()
                || !entry.getAsJsonPrimitive(key).isString()))
            {
                return ERR_CELL + where + ") '" + key + "' must be a string, got " + entry.get(key) //$NON-NLS-1$ //$NON-NLS-2$
                    + "; use an empty string to clear it."; //$NON-NLS-1$
            }
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
        BordersResult borders = parseBorders(entry, where);
        if (borders.error != null)
        {
            return borders.error;
        }
        ColorPlan[] cellColors = new ColorPlan[COLOR_KEYS.length];
        for (int i = 0; i < COLOR_KEYS.length; i++)
        {
            if (has(entry, COLOR_KEYS[i]))
            {
                ColorResult color = parseColor(entry, where, COLOR_KEYS[i], colors);
                if (color.error != null)
                {
                    return color.error;
                }
                cellColors[i] = color.value;
            }
        }
        boolean decorated = borders.value != null || cellColors[0] != null || cellColors[1] != null
            || cellColors[2] != null;
        if (!has(entry, KEY_TEXT) && !has(entry, KEY_PARAMETER) && !has(entry, KEY_BOLD)
            && !has(entry, KEY_WRAP) && !style.hasAnyStyle() && !decorated
            && !Boolean.TRUE.equals(boolMember(entry, KEY_REPLACE)))
        {
            return ERR_CELL + where + ") needs at least one of 'text', 'parameter', 'bold', " //$NON-NLS-1$
                + "'fontSize', 'hAlign', 'vAlign', 'wrap', 'textOrientation', 'autoIndent', " //$NON-NLS-1$
                + "'autoMarkIncomplete', 'borders', 'borderColor', 'backColor' or 'textColor'."; //$NON-NLS-1$
        }

        CellPlan cell = new CellPlan(row.intValue(), col.intValue(), text, parameter, style, entry);
        cell.borders = borders.value;
        System.arraycopy(cellColors, 0, cell.colors, 0, cellColors.length);
        plan.cells.add(cell);
        return null;
    }

    /**
     * Parses a cell's {@code borders}: ONE line for all four sides, or {@code {left?, top?, right?,
     * bottom?}} with a line per side. A line is a {@link CellLineStyle} name ({@code 'Solid'}) or
     * {@code {style, width?}}; {@code width} is 1..3 (EDT's line dialog range, default 1) and {@code None}
     * - EDT's empty line - takes no width.
     */
    private static BordersResult parseBorders(JsonObject entry, String where)
    {
        if (!has(entry, KEY_BORDERS))
        {
            return BordersResult.ok(null);
        }
        JsonElement element = entry.get(KEY_BORDERS);
        String prefix = "A '" + where + "' entry's 'borders'"; //$NON-NLS-1$ //$NON-NLS-2$
        BordersPlan plan = new BordersPlan();
        if (element.isJsonObject() && !has(element.getAsJsonObject(), KEY_STYLE))
        {
            JsonObject sides = element.getAsJsonObject();
            String unknown = unknownKeyError(sides, where + "." + KEY_BORDERS, BORDER_SIDES); //$NON-NLS-1$
            if (unknown != null)
            {
                return BordersResult.failed(unknown + " A line for all four sides is a style name or " //$NON-NLS-1$
                    + "{style, width?}."); //$NON-NLS-1$
            }
            boolean any = false;
            for (int i = 0; i < BORDER_SIDES.length; i++)
            {
                if (has(sides, BORDER_SIDES[i]))
                {
                    LineResult line = parseLine(sides.get(BORDER_SIDES[i]), prefix + " side '" //$NON-NLS-1$
                        + BORDER_SIDES[i] + "'"); //$NON-NLS-1$
                    if (line.error != null)
                    {
                        return BordersResult.failed(line.error);
                    }
                    plan.sides[i] = line.value;
                    any = true;
                }
            }
            if (!any)
            {
                return BordersResult.failed(prefix + " names no side: give one line for all four sides " //$NON-NLS-1$
                    + "('Solid' or {style:'Solid', width:2}) or {left?, top?, right?, bottom?}."); //$NON-NLS-1$
            }
            return BordersResult.ok(plan);
        }
        LineResult line = parseLine(element, prefix);
        if (line.error != null)
        {
            return BordersResult.failed(line.error);
        }
        Arrays.fill(plan.sides, line.value);
        return BordersResult.ok(plan);
    }

    /** Parses one border line: a style name, or {@code {style, width?}}. */
    private static LineResult parseLine(JsonElement element, String what)
    {
        String token;
        Integer width = null;
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
        {
            token = element.getAsString();
        }
        else if (element != null && element.isJsonObject())
        {
            JsonObject line = element.getAsJsonObject();
            String unknown = unknownKeyError(line, what, KEY_STYLE, KEY_WIDTH);
            if (unknown != null)
            {
                return LineResult.failed(unknown);
            }
            token = stringMember(line, KEY_STYLE);
            if (has(line, KEY_WIDTH))
            {
                JsonElement raw = line.get(KEY_WIDTH);
                width = raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber()
                    ? JsonUtils.parseIntegralInt(raw.getAsString()) : null;
                if (width == null)
                {
                    return LineResult.failed(what + " has width " + line.get(KEY_WIDTH) //$NON-NLS-1$
                        + "; a border line width is an integer " + MIN_LINE_WIDTH + ".." + MAX_LINE_WIDTH + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        else
        {
            return LineResult.failed(what + " must be a line style name or {style, width?}, got " + element //$NON-NLS-1$
                + ". Styles: " + lineStyles() + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (token == null)
        {
            return LineResult.failed(what + " needs a 'style'. Styles: " + lineStyles() + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        CellLineStyle style = resolveEnum(CellLineStyle.values(), token);
        if (style == null)
        {
            return LineResult.failed(what + " has the unknown line style '" + token + "'. Styles: " //$NON-NLS-1$ //$NON-NLS-2$
                + lineStyles() + "."); //$NON-NLS-1$
        }
        if (style == CellLineStyle.NONE)
        {
            if (width != null)
            {
                return LineResult.failed(what + " is 'None', which draws no line and takes no width; drop " //$NON-NLS-1$
                    + "'width'."); //$NON-NLS-1$
            }
            // EDT's empty line (SheetAccessor.createEmptyCellLine): style None, width 0.
            return LineResult.ok(new BorderPlan(style, 0));
        }
        int resolvedWidth = width == null ? MIN_LINE_WIDTH : width.intValue();
        if (resolvedWidth < MIN_LINE_WIDTH || resolvedWidth > MAX_LINE_WIDTH)
        {
            return LineResult.failed(what + " has width " + resolvedWidth + "; a border line is " //$NON-NLS-1$ //$NON-NLS-2$
                + MIN_LINE_WIDTH + ".." + MAX_LINE_WIDTH + " wide, as in EDT's line dialog."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return LineResult.ok(new BorderPlan(style, resolvedWidth));
    }

    /** The {@link CellLineStyle} tokens in the platform's own spelling, for a refusal. */
    private static String lineStyles()
    {
        StringBuilder sb = new StringBuilder();
        for (CellLineStyle style : CellLineStyle.values())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(style.getLiteral());
        }
        return sb.toString();
    }

    /**
     * Parses a cell colour with the injected builder; {@code 'auto'} becomes a clear (the feature is
     * unset and inherits again).
     */
    private static ColorResult parseColor(JsonObject entry, String where, String key, ColorBuilder colors)
    {
        JsonObject wrapped = new JsonObject();
        wrapped.add(AppearanceValueBuilder.COLOR, entry.get(key));
        AppearanceValueBuilder.Result built = colors.build(wrapped);
        if (built.error != null)
        {
            return ColorResult.failed("A '" + where + "' entry's '" + key + "' is not a colour: " + built.error //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " Here the value is the inner part, e.g. " + key + ":{red:255,green:0,blue:0} or " + key //$NON-NLS-1$ //$NON-NLS-2$
                + ":'Web.Red'."); //$NON-NLS-1$
        }
        if (built.clear)
        {
            return ColorResult.ok(new ColorPlan(null));
        }
        if (!(built.value instanceof Color))
        {
            return ColorResult.failed("A '" + where + "' entry's '" + key + "' did not build a colour."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (built.configurationItem != null)
        {
            // A configuration style / palette colour: the builder found the item in the caller's write
            // transaction, so the reference is bound to it here.
            AppearanceValueBuilder.bind(built.value, built.configurationItem);
        }
        return ColorResult.ok(new ColorPlan((Color)built.value));
    }

    /**
     * Parses + validates a cell entry's optional style members: {@code bold}, a positive
     * {@code fontSize}, the {@code hAlign} / {@code vAlign} alignment tokens, the {@code wrap} text
     * placement, the {@code textOrientation} rotation, the {@code autoIndent} and the
     * {@code autoMarkIncomplete} flag. A bad value is a ready error.
     */
    private static CellStyleResult parseCellStyle(JsonObject entry, String where)
    {
        BoolResult bold = optionalBool(entry, where, KEY_BOLD);
        if (bold.error != null)
        {
            return CellStyleResult.failed(bold.error);
        }
        Integer fontSize = intMember(entry, KEY_FONT_SIZE);
        if (fontSize != null && fontSize.intValue() <= 0)
        {
            return CellStyleResult.failed(
                ERR_CELL + where + ") 'fontSize' must be a positive integer, got " + fontSize + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        HorizontalAlignment hAlign = null;
        if (has(entry, KEY_H_ALIGN))
        {
            hAlign = resolveEnum(HorizontalAlignment.values(), stringMember(entry, KEY_H_ALIGN));
            if (hAlign == null)
            {
                return CellStyleResult.failed(enumError(where, KEY_H_ALIGN,
                    stringMember(entry, KEY_H_ALIGN), HorizontalAlignment.values()));
            }
        }
        VerticalAlignment vAlign = null;
        if (has(entry, KEY_V_ALIGN))
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

        // Rotation is the platform's own unit - TENTHS of a degree on Format.textOrientation - so the
        // value is passed through unscaled; 0 is a real value (explicitly horizontal), distinct from an
        // omitted key (inherit).
        IntResult textOrientation = boundedInt(entry, where, KEY_TEXT_ORIENTATION, MAX_TEXT_ORIENTATION,
            "tenths of a degree, so 90 degrees is 900"); //$NON-NLS-1$
        if (textOrientation.error != null)
        {
            return CellStyleResult.failed(textOrientation.error);
        }
        IntResult autoIndent = boundedInt(entry, where, KEY_AUTO_INDENT, MAX_AUTO_INDENT, null);
        if (autoIndent.error != null)
        {
            return CellStyleResult.failed(autoIndent.error);
        }
        BoolResult autoMarkIncomplete = optionalBool(entry, where, KEY_AUTO_MARK_INCOMPLETE);
        if (autoMarkIncomplete.error != null)
        {
            return CellStyleResult.failed(autoMarkIncomplete.error);
        }

        return CellStyleResult.ok(Boolean.TRUE.equals(bold.value), fontSize, hAlign, vAlign, wrap.value, textOrientation.value,
            autoIndent.value, autoMarkIncomplete.value);
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

    private static String parseColumnWidths(JsonObject spec, Plan plan, Version projectVersion)
    {
        List<JsonObject> entries = objectArray(spec, KEY_COLUMN_WIDTHS);
        if (entries == null)
        {
            return notAnObjectArray(KEY_COLUMN_WIDTHS);
        }
        for (int i = 0; i < entries.size(); i++)
        {
            String error = parseColumnWidth(entries.get(i), i, plan, projectVersion);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    /**
     * Parses one {@code columnWidths} entry: the column index plus at least one of the column's sizing
     * members - an explicit {@code width}, the {@code autoWidthCalculation} flag and the
     * {@code widthWeightFactor}. The weight factor is REFUSED without {@code autoWidthCalculation: true}:
     * the platform consults it only for a column whose width IS auto-calculated, and this call replaces
     * the column's format, so the flag cannot come from a previous write.
     */
    private static String parseColumnWidth(JsonObject entry, int index, Plan plan, Version projectVersion)
    {
        String where = KEY_COLUMN_WIDTHS + "[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        String misplaced = misplacedCellKeyError(entry, where);
        if (misplaced == null)
        {
            misplaced = unknownKeyError(entry, where, COLUMN_KEYS);
        }
        if (misplaced != null)
        {
            return misplaced;
        }
        Integer col = nonNegative(entry, KEY_COL);
        if (col == null)
        {
            return indexError(where, KEY_COL);
        }

        Integer width = null;
        if (has(entry, KEY_WIDTH))
        {
            width = intMember(entry, KEY_WIDTH);
            if (width == null || width.intValue() <= 0)
            {
                return "A column width (" + where + ") needs a positive integer 'width'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        BoolResult autoWidth = optionalBool(entry, where, KEY_AUTO_WIDTH_CALCULATION);
        if (autoWidth.error != null)
        {
            return autoWidth.error;
        }
        IntResult weightFactor = boundedInt(entry, where, KEY_WIDTH_WEIGHT_FACTOR, Integer.MAX_VALUE, null);
        if (weightFactor.error != null)
        {
            return weightFactor.error;
        }
        if (width == null && autoWidth.value == null && weightFactor.value == null)
        {
            return "A column width (" + where + ") needs a positive integer 'width', an " //$NON-NLS-1$ //$NON-NLS-2$
                + "'autoWidthCalculation' flag or a 'widthWeightFactor'."; //$NON-NLS-1$
        }
        String unsupported =
            autoWidthUnsupportedError(where, autoWidth.value, weightFactor.value, projectVersion);
        if (unsupported != null)
        {
            return unsupported;
        }
        if (weightFactor.value != null && !Boolean.TRUE.equals(autoWidth.value))
        {
            return "A column width (" + where + ") sets 'widthWeightFactor' to " + weightFactor.value //$NON-NLS-1$ //$NON-NLS-2$
                + " without 'autoWidthCalculation': the platform shares the free width out by this " //$NON-NLS-1$
                + "factor ONLY across columns whose width is auto-calculated, and this call replaces " //$NON-NLS-1$
                + "the column's format - add \"autoWidthCalculation\": true to the same entry, or drop " //$NON-NLS-1$
                + "'widthWeightFactor' and set a fixed 'width'."; //$NON-NLS-1$
        }
        plan.columnWidths.add(
            new ColumnWidthPlan(col.intValue(), width, autoWidth.value, weightFactor.value));
        return null;
    }

    /**
     * Refuses the two column-sizing members on a project older than 8.3.10. The moxel serializer writes
     * {@code autoWidthCalculation} / {@code widthWeightFactor} only from that runtime version on (and its
     * reader drops them again below it), so accepting them there would report success for a value the
     * export silently discards.
     */
    private static String autoWidthUnsupportedError(String where, Boolean autoWidth, Integer weightFactor,
        Version projectVersion)
    {
        if (projectVersion == null || !projectVersion.isLessThan(Version.V8_3_10)
            || (autoWidth == null && weightFactor == null))
        {
            return null;
        }
        String key = autoWidth != null ? KEY_AUTO_WIDTH_CALCULATION : KEY_WIDTH_WEIGHT_FACTOR;
        return "A column width (" + where + ") sets '" + key + "', which 1C:Enterprise only stores " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "from version 8.3.10; this project targets " + projectVersion //$NON-NLS-1$
            + ", where the export would silently drop it. Use a fixed 'width' instead, or raise the " //$NON-NLS-1$
            + "project's 1C:Enterprise version."; //$NON-NLS-1$
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
            String misplaced = misplacedCellKeyError(entry, where);
            if (misplaced == null)
            {
                misplaced = misplacedColumnKeyError(entry, where);
            }
            if (misplaced == null)
            {
                misplaced = unknownKeyError(entry, where, ROW_KEYS);
            }
            if (misplaced != null)
            {
                return misplaced;
            }
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
        if (!has(entry, KEY_WRAP))
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

    /** Whether a member is present with a non-null value (an explicit {@code null} counts as absent). */
    private static boolean has(JsonObject obj, String key)
    {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull();
    }

    /** Refuses a key outside {@code accepted}; like {@link #has}, an explicit {@code null} counts as absent. */
    private static String unknownKeyError(JsonObject entry, String where, String... accepted)
    {
        JsonObject present = new JsonObject();
        for (Map.Entry<String, JsonElement> member : entry.entrySet())
        {
            if (!member.getValue().isJsonNull())
            {
                present.add(member.getKey(), member.getValue());
            }
        }
        return JsonUtils.unknownMemberError(present, where, accepted);
    }

    /**
     * Refuses a CELL-only format key found on a column / row band entry. Such a key is never written by
     * this writer's band path, so accepting it would report success for a value stored nowhere.
     */
    private static String misplacedCellKeyError(JsonObject entry, String where)
    {
        for (String key : CELL_ONLY_KEYS)
        {
            if (has(entry, key))
            {
                return "A '" + where + "' entry sets '" + key + "', which is a CELL format property: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "put it on a 'cells' entry, e.g. cells:[{row:0,col:0,text:'Total'," + key //$NON-NLS-1$
                    + ":...}]."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Refuses a COLUMN-only sizing key found on a cell / row entry. The platform reads auto width
     * calculation and the width weight factor only from a COLUMN's format, so a cell carrying one would
     * serialize a value nothing ever reads.
     */
    private static String misplacedColumnKeyError(JsonObject entry, String where)
    {
        for (String key : COLUMN_ONLY_KEYS)
        {
            if (has(entry, key))
            {
                return "A '" + where + "' entry sets '" + key + "', which is a COLUMN property the " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "platform reads only from a column's format: put it on a 'columnWidths' entry, " //$NON-NLS-1$
                    + "e.g. columnWidths:[{col:0," + key + ":...}]."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * Reads an optional integer member bounded to {@code [0, max]}. Absent (or an explicit {@code null})
     * yields a {@code null} value - the feature stays UNSET - while {@code 0} is a real value.
     *
     * @param unitHint an extra clause naming the platform's unit, appended to the range error; may be
     *            {@code null}
     */
    private static IntResult boundedInt(JsonObject entry, String where, String key, int max, String unitHint)
    {
        if (!has(entry, key))
        {
            return IntResult.ok(null);
        }
        Integer value = intMember(entry, key);
        if (value == null || value.intValue() < 0 || value.intValue() > max)
        {
            String range = max == Integer.MAX_VALUE ? "a non-negative integer" //$NON-NLS-1$
                : "an integer in 0.." + max; //$NON-NLS-1$
            return IntResult.failed("A '" + where + "' entry's '" + key + "' must be " + range //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (unitHint == null ? "" : " (" + unitHint + ")") + ", got " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + entry.get(key) + "."); //$NON-NLS-1$
        }
        return IntResult.ok(value);
    }

    /**
     * Reads an optional boolean member. Absent (or an explicit {@code null}) yields {@code null} - the
     * feature stays UNSET - while an explicit {@code false} is a real value that overrides inheritance.
     */
    private static BoolResult optionalBool(JsonObject entry, String where, String key)
    {
        if (!has(entry, key))
        {
            return BoolResult.ok(null);
        }
        Boolean value = boolMember(entry, key);
        if (value == null)
        {
            return BoolResult.failed("A '" + where + "' entry's '" + key + "' must be a boolean, got " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + entry.get(key) + "."); //$NON-NLS-1$
        }
        return BoolResult.ok(value);
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
        String s = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
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
        /** Ranges whose touching merges are removed (inclusive corners). */
        final List<MergePlan> unmerges = new ArrayList<>();
        /** Area names to remove. */
        final List<String> removeAreas = new ArrayList<>();
        /** Area renames as {@code {name, newName}}. */
        final List<String[]> renameAreas = new ArrayList<>();

        boolean isEmpty()
        {
            return cells.isEmpty() && merges.isEmpty() && areas.isEmpty() && columnWidths.isEmpty()
                && rowHeights.isEmpty() && unmerges.isEmpty() && removeAreas.isEmpty() && renameAreas.isEmpty();
        }
    }

    /** One validated border line: a {@link CellLineStyle} and its width (0 only for {@code None}). */
    static final class BorderPlan
    {
        final CellLineStyle style;
        final int width;

        BorderPlan(CellLineStyle style, int width)
        {
            this.style = style;
            this.width = width;
        }
    }

    /** A cell's validated border lines, left / top / right / bottom; a {@code null} side is untouched. */
    static final class BordersPlan
    {
        final BorderPlan[] sides = new BorderPlan[BORDER_SIDES.length];
    }

    /** A validated cell colour: the detached {@link Color} to pool, or {@code null} to unset ('auto'). */
    static final class ColorPlan
    {
        final Color color;

        ColorPlan(Color color)
        {
            this.color = color;
        }
    }

    /**
     * A validated cell patch. Presence distinguishes explicit empty/false values from omitted members.
     */
    static final class CellPlan
    {
        final int row;
        final int col;
        final boolean replace;
        final boolean textPresent;
        final boolean parameterPresent;
        final boolean boldPresent;
        final boolean wrapPresent;
        final String text;
        final String parameter;
        final boolean bold;
        final Integer fontSize;
        final HorizontalAlignment hAlign;
        final VerticalAlignment vAlign;
        final TextPlacement textPlacement;
        /** Rotation in TENTHS of a degree ({@link Format#setTextOrientation(int)}), or {@code null}. */
        final Integer textOrientation;
        final Integer autoIndent;
        final Boolean autoMarkIncomplete;
        /** The border lines to set, or {@code null} when {@code borders} was omitted. */
        BordersPlan borders;
        /** {@code borderColor}, {@code backColor}, {@code textColor} - {@code null} when omitted. */
        final ColorPlan[] colors = new ColorPlan[COLOR_KEYS.length];

        CellPlan(int row, int col, String text, String parameter, CellStyleResult style, JsonObject entry)
        {
            this.row = row;
            this.col = col;
            this.replace = Boolean.TRUE.equals(boolMember(entry, KEY_REPLACE));
            this.textPresent = has(entry, KEY_TEXT);
            this.parameterPresent = has(entry, KEY_PARAMETER);
            this.boldPresent = has(entry, KEY_BOLD);
            this.wrapPresent = has(entry, KEY_WRAP);
            this.text = text;
            this.parameter = parameter;
            this.bold = style.bold;
            this.fontSize = style.fontSize;
            this.hAlign = style.hAlign;
            this.vAlign = style.vAlign;
            this.textPlacement = style.wrap;
            this.textOrientation = style.textOrientation;
            this.autoIndent = style.autoIndent;
            this.autoMarkIncomplete = style.autoMarkIncomplete;
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

    /** A validated column sizing entry; each member is {@code null} when its key was omitted. */
    static final class ColumnWidthPlan
    {
        final int col;
        final Integer width;
        final Boolean autoWidthCalculation;
        final Integer widthWeightFactor;

        ColumnWidthPlan(int col, Integer width, Boolean autoWidthCalculation, Integer widthWeightFactor)
        {
            this.col = col;
            this.width = width;
            this.autoWidthCalculation = autoWidthCalculation;
            this.widthWeightFactor = widthWeightFactor;
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
     * {@code hAlign} / {@code vAlign} / {@code wrap} / {@code textOrientation} / {@code autoIndent} /
     * {@code autoMarkIncomplete}): the resolved values or a ready error.
     */
    private static final class CellStyleResult
    {
        final boolean bold;
        final Integer fontSize;
        final HorizontalAlignment hAlign;
        final VerticalAlignment vAlign;
        final TextPlacement wrap;
        final Integer textOrientation;
        final Integer autoIndent;
        final Boolean autoMarkIncomplete;
        final String error;

        private CellStyleResult(boolean bold, Integer fontSize, HorizontalAlignment hAlign, // NOSONAR S107: this IS the parameter object - an internal immutable holder mirroring the parsed JSON cell style 1:1
            VerticalAlignment vAlign, TextPlacement wrap, Integer textOrientation, Integer autoIndent,
            Boolean autoMarkIncomplete, String error)
        {
            this.bold = bold;
            this.fontSize = fontSize;
            this.hAlign = hAlign;
            this.vAlign = vAlign;
            this.wrap = wrap;
            this.textOrientation = textOrientation;
            this.autoIndent = autoIndent;
            this.autoMarkIncomplete = autoMarkIncomplete;
            this.error = error;
        }

        static CellStyleResult ok(boolean bold, Integer fontSize, HorizontalAlignment hAlign, // NOSONAR S107: mirrors the parsed JSON cell style 1:1
            VerticalAlignment vAlign, TextPlacement wrap, Integer textOrientation, Integer autoIndent,
            Boolean autoMarkIncomplete)
        {
            return new CellStyleResult(bold, fontSize, hAlign, vAlign, wrap, textOrientation, autoIndent,
                autoMarkIncomplete, null);
        }

        static CellStyleResult failed(String error)
        {
            return new CellStyleResult(false, null, null, null, null, null, null, null, error);
        }

        /** Whether any style member was set (what makes a content-less cell entry still meaningful). */
        boolean hasAnyStyle()
        {
            return bold || fontSize != null || hAlign != null || vAlign != null || wrap != null
                || textOrientation != null || autoIndent != null || autoMarkIncomplete != null;
        }
    }

    /** A cell's parsed {@code borders} ({@code null} = absent) or a ready error. */
    private static final class BordersResult
    {
        final BordersPlan value;
        final String error;

        private BordersResult(BordersPlan value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static BordersResult ok(BordersPlan value)
        {
            return new BordersResult(value, null);
        }

        static BordersResult failed(String error)
        {
            return new BordersResult(null, error);
        }
    }

    /** One parsed border line or a ready error. */
    private static final class LineResult
    {
        final BorderPlan value;
        final String error;

        private LineResult(BorderPlan value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static LineResult ok(BorderPlan value)
        {
            return new LineResult(value, null);
        }

        static LineResult failed(String error)
        {
            return new LineResult(null, error);
        }
    }

    /** One parsed cell colour or a ready error. */
    private static final class ColorResult
    {
        final ColorPlan value;
        final String error;

        private ColorResult(ColorPlan value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static ColorResult ok(ColorPlan value)
        {
            return new ColorResult(value, null);
        }

        static ColorResult failed(String error)
        {
            return new ColorResult(null, error);
        }
    }

    /** An optional integer member's resolved value ({@code null} = absent) or a ready error. */
    private static final class IntResult
    {
        final Integer value;
        final String error;

        private IntResult(Integer value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static IntResult ok(Integer value)
        {
            return new IntResult(value, null);
        }

        static IntResult failed(String error)
        {
            return new IntResult(null, error);
        }
    }

    /** An optional boolean member's resolved value ({@code null} = absent) or a ready error. */
    private static final class BoolResult
    {
        final Boolean value;
        final String error;

        private BoolResult(Boolean value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static BoolResult ok(Boolean value)
        {
            return new BoolResult(value, null);
        }

        static BoolResult failed(String error)
        {
            return new BoolResult(null, error);
        }
    }
}
