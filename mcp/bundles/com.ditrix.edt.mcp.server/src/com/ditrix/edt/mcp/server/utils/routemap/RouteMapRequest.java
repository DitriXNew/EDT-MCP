/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.common.StringUtils;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Validates the entire replacement before any project or BM access. */
public record RouteMapRequest(List<PointPlan> points, List<ConnectionPlan> connections)
{
    public static final String ACCEPTED = "Start, Completion, Activity, Condition, Split, Join, Processing";
    public static final String DEFERRED = "Switch, SubBusinessProcess, Decoration, event handlers, "
        + "addressing-attribute values, line points/segments, colours and fonts are deferred to a follow-up. "
        + "Use the EDT route-map editor for these features.";

    /** The members a points entry writes. */
    public static final List<String> POINT_KEYS = List.of("name", "type", "title", "x", "y", "width", "height",
        "taskDescription", "group");

    /** The members a connections entry writes. */
    public static final List<String> CONNECTION_KEYS = List.of("from", "to", "name", "title", "fromPort", "toPort",
        "branch");

    /** Read-only members of a get entry, ignored so a read entry can be sent back. */
    private static final List<String> POINT_READ_ONLY = List.of("id", "truePort", "falsePort");

    private static final List<String> CONNECTION_READ_ONLY = List.of("id", "fromId", "toId");

    public RouteMapRequest
    {
        points = List.copyOf(points);
        connections = List.copyOf(connections);
    }

    public record PointPlan(String name, GraphicalSchemeItemType type, String title,
        Integer x, Integer y, Integer width, Integer height, String taskDescription, boolean group) { }

    public record ConnectionPlan(String from, String to, String name, String title,
        int fromPort, int toPort, String branch) { }

    public static RouteMapRequest parse(String points, String connections)
    {
        List<PointPlan> pointPlans = new ArrayList<>();
        Map<String, PointPlan> byName = new LinkedHashMap<>();
        JsonArray pointArray = array(points, "points");
        for (int index = 0; index < pointArray.size(); index++)
        {
            JsonObject point = object(pointArray.get(index), "points entry");
            rejectDeferred(point);
            rejectUnknown(point, "points[" + index + "]", POINT_KEYS, POINT_READ_ONLY);
            String name = identifier(point, "name", "point");
            String kind = text(point, "type", true);
            GraphicalSchemeItemType type = GraphicalSchemeItemType.get(kind);
            if (type == null || !writable(type))
            {
                throw Refusals.argument("Unsupported point type '" + kind + "'. Use: "
                    + ACCEPTED + ". " + DEFERRED);
            }
            if (type != GraphicalSchemeItemType.ACTIVITY
                && ((point.has("taskDescription") && !point.get("taskDescription").isJsonNull())
                    || (point.has("group") && !point.get("group").isJsonNull())))
            {
                throw Refusals.argument("Point '" + name + "' is " + kind
                    + ". Use taskDescription and group only on Activity points.");
            }
            PointPlan plan = new PointPlan(name, type, text(point, "title", false),
                positive(point, "x"), positive(point, "y"), positive(point, "width"),
                positive(point, "height"), text(point, "taskDescription", false), bool(point, "group"));
            if (byName.putIfAbsent(name, plan) != null)
            {
                throw Refusals.argument("Duplicate point name '" + name + "'. Use unique point names.");
            }
            pointPlans.add(plan);
        }
        List<ConnectionPlan> connectionPlans = new ArrayList<>();
        Set<String> branches = new HashSet<>();
        Set<String> names = new HashSet<>(byName.keySet());
        JsonArray connectionArray = array(connections, "connections");
        for (int index = 0; index < connectionArray.size(); index++)
        {
            JsonObject connection = object(connectionArray.get(index), "connections entry");
            rejectDeferred(connection);
            rejectUnknown(connection, "connections[" + index + "]", CONNECTION_KEYS, CONNECTION_READ_ONLY);
            String from = text(connection, "from", true);
            String to = text(connection, "to", true);
            String label = "Connection '" + from + " -> " + to + "'";
            if (!byName.containsKey(from) || !byName.containsKey(to))
            {
                throw Refusals.argument(label + " names an unknown point. Use names from points.");
            }
            String branch = text(connection, "branch", false);
            boolean condition = byName.get(from).type() == GraphicalSchemeItemType.CONDITION;
            if (condition && branch == null)
            {
                throw Refusals.argument(label + " requires branch='true' or 'false' for Condition '"
                    + from + "'. Pass branch explicitly.");
            }
            if (branch != null && (!condition || !(branch.equals("true") || branch.equals("false"))))
            {
                throw Refusals.argument(label + " has invalid branch '" + branch
                    + "'. Use true or false only for a Condition source.");
            }
            if (condition && !branches.add(from + ":" + branch))
            {
                throw Refusals.argument(label + " duplicates branch '" + branch
                    + "'. Use at most one connection per Condition branch.");
            }
            String name = text(connection, "name", false);
            if (name != null && (!StringUtils.isValidName(name) || !names.add(name)))
            {
                throw Refusals.argument("Invalid or duplicate connection name '" + name
                    + "'. Use a unique platform identifier.");
            }
            int fromPort = port(connection, "fromPort", condition ? branchPort(branch) : 4);
            if (condition && fromPort != branchPort(branch))
            {
                throw Refusals.argument(label + " has fromPort=" + fromPort + " for branch='" + branch
                    + "'. Use fromPort=" + branchPort(branch) + " or omit it.");
            }
            // Fixed distinct branch ports plus the duplicate-branch check forbid sharing a port.
            connectionPlans.add(new ConnectionPlan(from, to, name, text(connection, "title", false),
                fromPort, port(connection, "toPort", 2), branch));
        }
        return new RouteMapRequest(pointPlans, connectionPlans);
    }

    public static boolean writable(GraphicalSchemeItemType type)
    {
        return switch (type)
        {
            case START, COMPLETION, ACTIVITY, CONDITION, SPLIT, JOIN, PROCESSING -> true;
            default -> false;
        };
    }

    private static int branchPort(String branch)
    {
        return "true".equals(branch) ? 3 : 1;
    }

    private static void rejectDeferred(JsonObject value)
    {
        for (String key : List.of("eventHandlers", "handlers", "addressingAttributes", "cases", "subprocess",
            "decorative", "bounds", "line", "points", "segments", "backColor", "textColor", "lineColor", "font"))
        {
            if (value.has(key) && !value.get(key).isJsonNull())
            {
                throw Refusals.argument("Cannot write '" + key + "'. "
                    + (key.equals("bounds") ? "Pass x, y, width and height directly on the point. " : "") + DEFERRED);
            }
        }
    }

    /** Runs after {@link #rejectDeferred}, so a deferred key keeps its follow-up message. */
    private static void rejectUnknown(JsonObject value, String where, List<String> written, List<String> readOnly)
    {
        List<String> accepted = new ArrayList<>(written);
        accepted.addAll(readOnly);
        JsonObject present = new JsonObject();
        value.entrySet().stream().filter(member -> !member.getValue().isJsonNull())
            .forEach(member -> present.add(member.getKey(), member.getValue()));
        String unknown = JsonUtils.unknownMemberError(present, where, accepted.toArray(String[]::new));
        if (unknown != null)
        {
            throw Refusals.argument(unknown);
        }
    }

    private static JsonArray array(String raw, String key)
    {
        if (raw != null)
        {
            try
            {
                JsonElement value = JsonParser.parseString(raw);
                if (value.isJsonArray())
                {
                    return value.getAsJsonArray();
                }
            }
            catch (RuntimeException e)
            {
                IllegalArgumentException refusal = Refusals.argument("Invalid '" + key
                    + "'. Pass a JSON array of objects.");
                refusal.initCause(e);
                throw refusal;
            }
        }
        throw Refusals.argument("Missing or invalid '" + key + "'. Pass a JSON array, even when empty.");
    }

    private static JsonObject object(JsonElement value, String label)
    {
        if (!value.isJsonObject())
        {
            throw Refusals.argument("Invalid " + label + ". Pass a JSON object.");
        }
        return value.getAsJsonObject();
    }

    private static String text(JsonObject value, String key, boolean required)
    {
        JsonElement field = value.get(key);
        if (field == null || field.isJsonNull())
        {
            if (required)
            {
                throw Refusals.argument("Missing '" + key + "'. Pass a non-empty string.");
            }
            return null;
        }
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()
            || (required && field.getAsString().isBlank()))
        {
            throw Refusals.argument("Invalid '" + key + "': " + field + ". Pass a non-empty string.");
        }
        return field.getAsString();
    }

    private static String identifier(JsonObject value, String key, String subject)
    {
        String name = text(value, key, true);
        if (!StringUtils.isValidName(name))
        {
            throw Refusals.argument("Invalid " + subject + " name '" + name
                + "'. Use a platform identifier starting with a letter or underscore.");
        }
        return name;
    }

    private static Integer positive(JsonObject value, String key)
    {
        JsonElement field = value.get(key);
        if (field == null || field.isJsonNull())
        {
            return null;
        }
        try
        {
            if (field.isJsonPrimitive() && field.getAsJsonPrimitive().isNumber())
            {
                int number = field.getAsBigDecimal().intValueExact();
                if (number > 0)
                {
                    return number;
                }
            }
        }
        catch (ArithmeticException e)
        {
            // Fall through to the same value-naming refusal.
        }
        throw Refusals.argument("Invalid '" + key + "': " + field + ". Pass a positive integer.");
    }

    private static int port(JsonObject value, String key, int fallback)
    {
        Integer number = positive(value, key);
        if (number != null && number > 4)
        {
            throw Refusals.argument("Invalid '" + key + "': " + number + ". Use an anchor from 1 to 4.");
        }
        return number == null ? fallback : number;
    }

    private static boolean bool(JsonObject value, String key)
    {
        JsonElement field = value.get(key);
        if (field == null || field.isJsonNull())
        {
            return false;
        }
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isBoolean())
        {
            throw Refusals.argument("Invalid '" + key + "': " + field + ". Pass a boolean.");
        }
        return field.getAsBoolean();
    }
}
