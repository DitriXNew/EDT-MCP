/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.bp.scheme.services.EntitySizeProvider;
import com._1c.g5.v8.dt.diagram.framework.model.Point;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Platform sizes and deterministic 20-pixel-grid placement for omitted geometry. */
public final class RouteMapGeometry
{
    private RouteMapGeometry() { }

    public record Rectangle(int x, int y, int width, int height) { }

    public static Point defaultSize(GraphicalSchemeItemType type)
    {
        return EntitySizeProvider.getDefaultSize(type.getValue());
    }

    public static JsonObject defaults()
    {
        JsonObject sizes = new JsonObject();
        for (GraphicalSchemeItemType type : GraphicalSchemeItemType.VALUES)
        {
            if (type == GraphicalSchemeItemType.CONNECTION_LINE || type == GraphicalSchemeItemType.DUMMY)
            {
                continue;
            }
            Point size = defaultSize(type);
            JsonArray pair = new JsonArray();
            pair.add(size.getX());
            pair.add(size.getY());
            sizes.add(type.getLiteral(), pair);
        }
        JsonObject ports = new JsonObject();
        ports.addProperty("source", 4);
        ports.addProperty("target", 2);
        ports.addProperty("conditionTrue", 3);
        ports.addProperty("conditionFalse", 1);
        JsonObject defaults = new JsonObject();
        defaults.add("sizes", sizes);
        defaults.add("ports", ports);
        return defaults;
    }

    public static Map<String, Rectangle> place(RouteMapRequest request)
    {
        Map<String, Integer> depths = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (var point : request.points())
        {
            if (point.type() == GraphicalSchemeItemType.START)
            {
                depths.put(point.name(), 0);
                queue.add(point.name());
            }
        }
        Map<String, java.util.List<String>> outgoing = new LinkedHashMap<>();
        for (var connection : request.connections())
        {
            outgoing.computeIfAbsent(connection.from(), ignored -> new java.util.ArrayList<>()).add(connection.to());
        }
        while (!queue.isEmpty())
        {
            String source = queue.remove();
            for (String target : outgoing.getOrDefault(source, java.util.List.of()))
            {
                if (!depths.containsKey(target))
                {
                    depths.put(target, depths.get(source) + 1);
                    queue.add(target);
                }
            }
        }
        Map<Integer, Integer> columns = new LinkedHashMap<>();
        Map<String, Rectangle> result = new LinkedHashMap<>();
        for (var point : request.points())
        {
            int depth = depths.getOrDefault(point.name(), 0);
            int column = columns.getOrDefault(depth, 0);
            columns.put(depth, column + 1);
            Point size = defaultSize(point.type());
            result.put(point.name(), new Rectangle(point.x() == null ? 40 + column * 160 : point.x(),
                point.y() == null ? 40 + depth * 120 : point.y(),
                point.width() == null ? size.getX() : point.width(),
                point.height() == null ? size.getY() : point.height()));
        }
        return result;
    }
}
