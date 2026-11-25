/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com._1c.g5.v8.dt.moxel.ColumnsArea;
import com._1c.g5.v8.dt.moxel.LanguageSettings;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.NamedItemDrawing;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.RowsArea;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetPoint;
import com._1c.g5.v8.dt.moxel.TextDrawing;
import com._1c.g5.v8.dt.moxel.content.Area;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com._1c.g5.v8.dt.moxel.content.LocalString;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Reads only declarative data of one moxel model, while the caller holds a BM read boundary. */
public final class SpreadsheetTemplateReader
{
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_STRING_LENGTH = 2048;
    // Bound the additional parameter names separately. Even a rank containing maximally JSON-
    // escaped strings in every collection must still fit, so the offset can always advance.
    private static final int MAX_ADDITIONAL_PARAMETER_LENGTH = 1024;
    // Reserve room for the page envelope, escaped area name and FQN. JSON-in-text
    // clients need the whole payload, including the next offset, under the shared cap.
    private static final int PAGE_ITEMS_BUDGET = OutputSizeGuard.MAX_CONTENT_CHARS - 16_000;

    private SpreadsheetTemplateReader()
    {
    }

    /**
     * Each collection has its own matching-entry count, but shares the same page offset and size.
     * A null document denotes an unmaterialized, empty template, not an external file to open.
     */
    public static JsonObject read(SpreadsheetDocument document, String areaName, int offset, int limit)
    {
        Selection selection = areaName == null ? null : findArea(document, areaName);
        Page areas = new Page(offset, limit);
        Page cells = new Page(offset, limit);
        Page merges = new Page(offset, limit);
        Page drawings = new Page(offset, limit);
        if (document != null)
        {
            readAreas(document, selection, areas);
            readCells(document, selection, cells);
            readMerges(document, selection, merges);
            readDrawings(document, selection, drawings);
        }
        // One rank is a slice at the same offset in every collection. Trim all
        // collections at the same rank, so the returned cursor can never skip an item.
        Page[] pages = {areas, cells, merges, drawings};
        int advance = fitPage(limit, pages);
        JsonObject result = new JsonObject();
        result.add("areas", areas.items);
        result.add("cells", cells.items);
        result.add("merges", merges.items);
        result.add("drawings", drawings.items);
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        if (selection != null)
        {
            putString(result, "areaName", selection.name);
        }
        if (advance < limit || areas.more || cells.more || merges.more || drawings.more)
        {
            result.addProperty("nextOffset", (long)offset + advance);
        }
        return result;
    }

    private static int fitPage(int limit, Page[] pages)
    {
        int budget = PAGE_ITEMS_BUDGET;
        for (int rank = 0; rank < limit; rank++)
        {
            for (Page page : pages)
            {
                if (rank < page.items.size())
                {
                    // Gson's escaped JSON length, plus the item's separating comma.
                    budget -= GsonProvider.toJson(page.items.get(rank)).length() + 1;
                }
            }
            if (budget < 0)
            {
                for (Page page : pages)
                {
                    while (page.items.size() > rank)
                    {
                        page.items.remove(page.items.size() - 1);
                    }
                }
                return rank;
            }
        }
        return limit;
    }

    private static Selection findArea(SpreadsheetDocument document, String name)
    {
        if (document != null)
        {
            var direct = document.getNamedItems().get(name);
            if (direct instanceof NamedItemCells named)
            {
                return selectedArea(name, named);
            }
            // An exact name that belongs to a drawing is not a folded alias for an area.
            if (direct == null)
            {
                String selectedName = null;
                NamedItemCells selected = null;
                for (var item : document.getNamedItems())
                {
                    if (!(item.getValue() instanceof NamedItemCells named) || !name.equalsIgnoreCase(item.getKey()))
                    {
                        continue;
                    }
                    if (selected != null)
                    {
                        throw Refusals.argument("Area name '" + name
                            + "' is ambiguous (case-insensitive match). Use the exact name.");
                    }
                    selectedName = item.getKey();
                    selected = named;
                }
                if (selected != null)
                {
                    return selectedArea(selectedName, selected);
                }
            }
        }
        throw Refusals.argument("No named cell area '" + name + "'. Omit areaName to list available areas.");
    }

    private static Selection selectedArea(String name, NamedItemCells named)
    {
        Range range = areaRange(named.getArea());
        if (range == null)
        {
            throw Refusals.argument("Area '" + name + "' does not have a supported row, column or rectangle range.");
        }
        return new Selection(name, range);
    }

    private static void readAreas(SpreadsheetDocument document, Selection selection, Page page)
    {
        for (var item : document.getNamedItems())
        {
            if (item.getValue() instanceof NamedItemCells named
                && (selection == null || selection.name.equals(item.getKey())))
            {
                page.add(() -> {
                    Range range = areaRange(named.getArea());
                    JsonObject data = range == null ? new JsonObject() : range.toJson();
                    if (range == null)
                    {
                        data.addProperty("type", "unsupported");
                    }
                    putString(data, "name", item.getKey());
                    return data;
                });
                if (page.more)
                {
                    return;
                }
            }
        }
    }

    private static void readCells(SpreadsheetDocument document, Selection selection, Page page)
    {
        for (var row : document.getRows())
        {
            int rowIndex = row.getKey();
            if (selection != null && !selection.range.containsRow(rowIndex))
            {
                continue;
            }
            for (var col : row.getValue().getCells())
            {
                int colIndex = col.getKey();
                if (selection != null && !selection.range.containsCol(colIndex))
                {
                    continue;
                }
                var cell = col.getValue();
                // EDT's getCellText uses the *cell's* format, not merged/inherited visual formatting.
                FillType fill = fillType(document, cell.getFormatIndex());
                boolean parameter = fill == FillType.PARAMETER;
                Text text = parameter ? new Text(null, cell.getParameter())
                    : cellText(cell.getText(), document.getLanguageSettings());
                boolean hasText = text != null && notEmpty(text.value);
                String detail = cell.getDetailParameter();
                String picture = cell.getPictureParameter();
                if (!hasText && !notEmpty(detail) && !notEmpty(picture))
                {
                    continue;
                }
                page.add(() -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("row", rowIndex);
                    data.addProperty("col", colIndex);
                    data.addProperty("type", parameter ? "parameter" : fill == FillType.TEMPLATE ? "template" : "text");
                    if (hasText)
                    {
                        putString(data, parameter ? "parameter" : "text", text.value);
                        if (text.language != null)
                        {
                            putString(data, "language", text.language);
                        }
                    }
                    putParameter(data, "detailParameter", detail);
                    putParameter(data, "pictureParameter", picture);
                    return data;
                });
                if (page.more)
                {
                    return;
                }
            }
        }
    }

    private static Text cellText(LocalString text, LanguageSettings langs)
    {
        if (text == null)
        {
            return null;
        }
        var content = text.getContent();
        String current = langs == null ? null : langs.getCurrentLanguage();
        String fallback = langs == null ? null : langs.getDefaultLanguage();
        for (String key : new String[] {current, fallback, "#"})
        {
            String value = key == null ? null : content.get(key);
            if (value != null && !value.isEmpty())
            {
                return new Text(key, value);
            }
        }
        for (var entry : content)
        {
            if (entry.getValue() != null && !entry.getValue().isEmpty())
            {
                return new Text(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    private static void readMerges(SpreadsheetDocument document, Selection selection, Page page)
    {
        for (var merge : document.getMerges())
        {
            addRange(page, rectangle(merge.getPosition()), selection);
            if (page.more)
            {
                return;
            }
        }
        for (var merge : document.getRowMerges())
        {
            addRange(page, rows(merge.getBegin(), merge.getEnd()), selection);
            if (page.more)
            {
                return;
            }
        }
        for (var merge : document.getColumnMerges())
        {
            addRange(page, columns(merge.getBegin(), merge.getEnd()), selection);
            if (page.more)
            {
                return;
            }
        }
    }

    private static void addRange(Page page, Range range, Selection selection)
    {
        if (range != null && (selection == null || selection.range.intersects(range)))
        {
            page.add(range::toJson);
        }
    }

    private static void readDrawings(SpreadsheetDocument document, Selection selection, Page page)
    {
        if (document.getDrawings().isEmpty())
        {
            return;
        }
        Map<Integer, String> names = new HashMap<>();
        for (var item : document.getNamedItems())
        {
            if (item.getValue() instanceof NamedItemDrawing named)
            {
                names.putIfAbsent(named.getDrawingId(), item.getKey());
            }
        }
        for (var drawing : document.getDrawings())
        {
            var pos = drawing.getPosition();
            var begin = pos == null ? null : cellPoint(pos.getBegin());
            var end = pos == null ? null : cellPoint(pos.getEnd());
            Range anchors = begin == null || end == null ? null
                : bounds(begin.getY(), begin.getX(), end.getY(), end.getX());
            if (selection == null || anchors != null && selection.range.intersects(anchors))
            {
                page.add(() -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("id", drawing.getDrawingId());
                    data.addProperty("type", drawing.eClass().getName());
                    String name = names.get(drawing.getDrawingId());
                    if (name != null)
                    {
                        putString(data, "name", name);
                    }
                    if (anchors != null)
                    {
                        data.add("anchor", anchors.toJson());
                    }
                    putParameter(data, "detailParameter", drawing.getDetailParameter());
                    if (drawing instanceof TextDrawing textDrawing
                        && fillType(document, drawing.getFormatIndex()) == FillType.PARAMETER)
                    {
                        putParameter(data, "parameter", textDrawing.getParameter());
                    }
                    return data;
                });
                if (page.more)
                {
                    return;
                }
            }
        }
    }

    private static FillType fillType(SpreadsheetDocument document, int index)
    {
        return index >= 0 && index < document.getFormats().size()
            ? document.getFormats().get(index).getFillType() : FillType.TEXT;
    }

    private static boolean notEmpty(String value)
    {
        return value != null && !value.isEmpty();
    }

    private static void putParameter(JsonObject data, String key, String value)
    {
        if (notEmpty(value))
        {
            putString(data, key, value, MAX_ADDITIONAL_PARAMETER_LENGTH);
        }
    }

    private static com._1c.g5.v8.dt.mcore.Point cellPoint(SpreadsheetPoint point)
    {
        return point == null ? null : point.getCell();
    }

    private static Range areaRange(Area area)
    {
        if (area instanceof RectArea rect)
        {
            return rectangle(rect.getPosition());
        }
        if (area instanceof RowsArea rowsArea)
        {
            return rows(rowsArea.getBegin(), rowsArea.getEnd());
        }
        if (area instanceof ColumnsArea columnsArea)
        {
            return columns(columnsArea.getBegin(), columnsArea.getEnd());
        }
        return null;
    }

    private static Range rectangle(Rect rect)
    {
        return rect == null ? null : bounds(rect.getY(), rect.getX(),
            (long)rect.getY() + rect.getHeight(), (long)rect.getX() + rect.getWidth());
    }

    private static Range bounds(long y1, long x1, long y2, long x2)
    {
        return Math.min(y1, y2) < 0 || Math.min(x1, x2) < 0 ? null
            : new Range("rectangle", Math.min(y1, y2), Math.min(x1, x2), Math.max(y1, y2), Math.max(x1, x2));
    }

    private static Range rows(int begin, int end)
    {
        return Math.min(begin, end) < 0 ? null : new Range("rows", Math.min(begin, end), -1, Math.max(begin, end), -1);
    }

    private static Range columns(int begin, int end)
    {
        return Math.min(begin, end) < 0 ? null : new Range("columns", -1, Math.min(begin, end), -1, Math.max(begin, end));
    }

    private static void putString(JsonObject data, String key, String value)
    {
        putString(data, key, value, MAX_STRING_LENGTH);
    }

    private static void putString(JsonObject data, String key, String value, int maxLength)
    {
        if (value.length() <= maxLength)
        {
            data.addProperty(key, value);
            return;
        }
        int end = Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        data.addProperty(key, value.substring(0, end));
        data.addProperty(key + "Truncated", true);
    }

    private record Text(String language, String value) { }

    private record Selection(String name, Range range) { }

    private record Range(String type, long fromRow, long fromCol, long toRow, long toCol)
    {
        boolean containsRow(int row) { return fromRow < 0 || fromRow <= row && row <= toRow; }

        boolean containsCol(int col) { return fromCol < 0 || fromCol <= col && col <= toCol; }

        boolean intersects(Range other)
        {
            return (fromRow < 0 || other.fromRow < 0 || fromRow <= other.toRow && other.fromRow <= toRow)
                && (fromCol < 0 || other.fromCol < 0 || fromCol <= other.toCol && other.fromCol <= toCol);
        }

        JsonObject toJson()
        {
            JsonObject data = new JsonObject();
            data.addProperty("type", type);
            if (fromRow >= 0)
            {
                data.addProperty("fromRow", fromRow);
                data.addProperty("toRow", toRow);
            }
            if (fromCol >= 0)
            {
                data.addProperty("fromCol", fromCol);
                data.addProperty("toCol", toCol);
            }
            return data;
        }
    }

    private static final class Page
    {
        final JsonArray items = new JsonArray();
        final int limit;
        int skip;
        boolean more;

        Page(int offset, int limit)
        {
            this.skip = offset;
            this.limit = limit;
        }

        void add(Supplier<JsonObject> item)
        {
            if (skip > 0)
            {
                skip--;
            }
            else if (items.size() == limit)
            {
                more = true;
            }
            else
            {
                items.add(item.get());
            }
        }
    }
}
