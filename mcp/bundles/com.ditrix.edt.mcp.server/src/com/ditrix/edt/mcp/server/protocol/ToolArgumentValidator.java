/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Checks tools/call arguments against the tool's declared {@code inputSchema} before the tool runs.
 *
 * <p>Top-level arguments keep the leniency the tools' readers ({@link JsonUtils}) always had: a numeric
 * or boolean value may arrive as text, {@code "yes"}/{@code "no"}/{@code 1}/{@code 0} are booleans, an
 * {@code array<string>} may be comma-separated text, an array or object may arrive JSON-encoded, an empty
 * string (also for an enum), the text {@code "null"} for a non-string or JSON {@code null} means "omitted",
 * and unknown or missing top-level arguments are left to the tool (it names the sibling tool in its own
 * message). A few parameters deliberately take more than their schema says; {@link #FREE_VALUE} and
 * {@link #EXTRA_ENUM_VALUES} name them. What is refused is a value a reader would
 * silently drop or replace: non-numeric text for an integer, an unrecognised boolean, a value outside an
 * enum, a scalar where an array or object is declared.</p>
 *
 * <p>Inside an array item or object member the JSON form is checked as declared: {@code required},
 * {@code additionalProperties: false}, nested types (numbers and {@code "true"/"false"} may still be
 * text), enums, bounds. Keywords it does not know are ignored, so an unknown construct never refuses.</p>
 */
public final class ToolArgumentValidator
{
    /** How many violations one refusal lists. */
    static final int MAX_REPORTED = 8;

    private static final int MAX_CACHED_SCHEMAS = 512;

    private static final Map<String, JsonObject> SCHEMAS = new ConcurrentHashMap<>();

    /**
     * {@code tool.parameter}s that are not checked: a documented fallback for any value, or a string
     * parameter that also parses a JSON number, array or object.
     */
    static final Set<String> FREE_VALUE = Set.of(
        "get_module_structure.responseFormat", //$NON-NLS-1$
        "get_platform_documentation.responseFormat", //$NON-NLS-1$
        "get_platform_documentation.language", //$NON-NLS-1$
        "get_project_errors.responseFormat", //$NON-NLS-1$
        "list_projects.format", //$NON-NLS-1$
        "rename_metadata_object.disableIndices", //$NON-NLS-1$
        "project_checks.changes", //$NON-NLS-1$
        "ask_workmate.workmateArgs"); //$NON-NLS-1$

    /** Aliases a tool accepts besides its declared enum, lower-cased, by {@code tool.parameter}. */
    static final Map<String, Set<String>> EXTRA_ENUM_VALUES = Map.of(
        "create_metadata.callType", Set.of("override"), //$NON-NLS-1$ //$NON-NLS-2$
        "step.kind", Set.of("return"), //$NON-NLS-1$ //$NON-NLS-2$
        "list_configurations.type", Set.of("runtime", "runtimeclient")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String TYPE = "type"; //$NON-NLS-1$
    private static final String STRING = "string"; //$NON-NLS-1$
    private static final String INTEGER = "integer"; //$NON-NLS-1$
    private static final String NUMBER = "number"; //$NON-NLS-1$
    private static final String BOOLEAN = "boolean"; //$NON-NLS-1$
    private static final String ARRAY = "array"; //$NON-NLS-1$
    private static final String OBJECT = "object"; //$NON-NLS-1$
    private static final String PROPERTIES = "properties"; //$NON-NLS-1$
    private static final String ITEMS = "items"; //$NON-NLS-1$

    private ToolArgumentValidator()
    {
    }

    /**
     * Validates {@code arguments} against {@code inputSchema}.
     *
     * @param toolName the tool name, for the message
     * @param inputSchema the tool's input schema JSON (may be {@code null} or unparseable: nothing is checked)
     * @param arguments the raw tools/call arguments (may be {@code null})
     * @return the refusal message, or {@code null} when the arguments are acceptable
     */
    public static String validate(String toolName, String inputSchema, Map<String, Object> arguments)
    {
        if (arguments == null || arguments.isEmpty())
        {
            return null;
        }
        JsonObject schema = parse(inputSchema);
        JsonObject properties = schema == null ? null : object(schema, PROPERTIES);
        if (properties == null)
        {
            return null;
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet())
        {
            String key = toolName + "." + entry.getKey(); //$NON-NLS-1$
            JsonObject declared = object(properties, entry.getKey());
            if (declared == null || entry.getValue() == null || FREE_VALUE.contains(key)
                || isAlias(key, entry.getValue()))
            {
                continue;
            }
            checkTopLevel(entry.getKey(), declared, GsonProvider.get().toJsonTree(entry.getValue()), problems);
        }
        return problems.isEmpty() ? null : message(toolName, problems);
    }

    private static boolean isAlias(String key, Object value)
    {
        Set<String> aliases = EXTRA_ENUM_VALUES.get(key);
        return aliases != null && value instanceof String
            && aliases.contains(((String) value).trim().toLowerCase(Locale.ROOT));
    }

    private static String message(String toolName, List<String> problems)
    {
        StringBuilder sb = new StringBuilder("Invalid arguments for '").append(toolName) //$NON-NLS-1$
            .append("'; nothing was run:"); //$NON-NLS-1$
        int shown = Math.min(problems.size(), MAX_REPORTED);
        for (int i = 0; i < shown; i++)
        {
            sb.append("\n- ").append(problems.get(i)); //$NON-NLS-1$
        }
        if (problems.size() > shown)
        {
            sb.append("\n- ... and ").append(problems.size() - shown).append(" more"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.append("\nFix the values to match the tool's inputSchema and call again.").toString(); //$NON-NLS-1$
    }

    // ---- top level: the leniency of the JsonUtils readers --------------------------------------------

    private static void checkTopLevel(String path, JsonObject schema, JsonElement value, List<String> problems)
    {
        boolean notText = !declares(schema, STRING);
        if (value.isJsonNull() || isEmptyText(value) && (notText || schema.has("enum")) //$NON-NLS-1$
            || notText && value.isJsonPrimitive() && "null".equals(value.getAsString().trim())) //$NON-NLS-1$
        {
            return;
        }
        List<String> types = nativeFirst(types(schema), value);
        if (types.isEmpty())
        {
            checkEnum(path, schema, value, problems);
            return;
        }
        String first = null;
        for (String type : types)
        {
            String problem = checkTopLevelType(path, type, schema, value, problems);
            if (problem == null)
            {
                return;
            }
            if (first == null)
            {
                first = problem;
            }
        }
        problems.add(first);
    }

    /** Returns {@code null} when {@code value} is acceptable as {@code type} (nested problems are added). */
    private static String checkTopLevelType(String path, String type, JsonObject schema, JsonElement value,
        List<String> problems)
    {
        switch (type)
        {
            case STRING:
                if (!value.isJsonPrimitive())
                {
                    return expected(path, "a string", value); //$NON-NLS-1$
                }
                checkEnum(path, schema, value, problems);
                checkStringBounds(path, schema, value.getAsString(), problems);
                return null;
            case INTEGER:
            case NUMBER:
            {
                Number number = topLevelNumber(value, INTEGER.equals(type));
                if (number == null)
                {
                    return expected(path, INTEGER.equals(type) ? "an integer" : "a number", value); //$NON-NLS-1$ //$NON-NLS-2$
                }
                checkEnum(path, schema, value, problems);
                checkNumberBounds(path, schema, number.doubleValue(), problems);
                return null;
            }
            case BOOLEAN:
                return topLevelBoolean(value) ? null
                    : expected(path, "true or false", value); //$NON-NLS-1$
            case ARRAY:
                return checkTopLevelArray(path, schema, value, problems);
            case OBJECT:
            {
                JsonElement decoded = decodeJsonText(value);
                if (decoded == null || !decoded.isJsonObject())
                {
                    return expected(path, ("an object " + shape(schema)).trim(), value); //$NON-NLS-1$
                }
                checkObject(path, schema, decoded.getAsJsonObject(), problems);
                return null;
            }
            default:
                return null;
        }
    }

    private static String checkTopLevelArray(String path, JsonObject schema, JsonElement value, List<String> problems)
    {
        JsonElement decoded = decodeJsonText(value);
        if (decoded != null && decoded.isJsonArray())
        {
            checkArray(path, schema, decoded.getAsJsonArray(), problems);
            return null;
        }
        // Text that is not a JSON array is read as a comma-separated list - only for scalar items.
        JsonObject items = object(schema, ITEMS);
        boolean scalarItems = items == null || types(items).isEmpty()
            || !types(items).contains(OBJECT) && !types(items).contains(ARRAY);
        if (value.isJsonPrimitive() && scalarItems)
        {
            return null;
        }
        return expected(path, scalarItems ? "an array" : "an array of " + shape(items) + "objects", value); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static Number topLevelNumber(JsonElement value, boolean integral)
    {
        if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean())
        {
            return null;
        }
        String text = value.getAsString().trim();
        if (integral)
        {
            Long exact = JsonUtils.parseIntegralLong(text);
            return exact != null ? exact : JsonUtils.parseExactDecimalLong(text);
        }
        try
        {
            double d = Double.parseDouble(text);
            return Double.isFinite(d) ? Double.valueOf(d) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static boolean topLevelBoolean(JsonElement value)
    {
        if (!value.isJsonPrimitive())
        {
            return false;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean())
        {
            return true;
        }
        if (primitive.isNumber())
        {
            double d = primitive.getAsDouble();
            return d == 0 || d == 1;
        }
        switch (primitive.getAsString().trim().toLowerCase(Locale.ROOT))
        {
            case "true": //$NON-NLS-1$
            case "false": //$NON-NLS-1$
            case "1": //$NON-NLS-1$
            case "0": //$NON-NLS-1$
            case "yes": //$NON-NLS-1$
            case "no": //$NON-NLS-1$
                return true;
            default:
                return false;
        }
    }

    // ---- nested: the declared JSON form --------------------------------------------------------------

    private static void checkNested(String path, JsonObject schema, JsonElement value, List<String> problems)
    {
        if (isEmptyText(value) && schema.has("enum")) //$NON-NLS-1$
        {
            return;
        }
        List<String> types = nativeFirst(types(schema), value);
        if (types.isEmpty())
        {
            checkEnum(path, schema, value, problems);
            return;
        }
        String first = null;
        for (String type : types)
        {
            String problem = checkNestedType(path, type, schema, value, problems);
            if (problem == null)
            {
                return;
            }
            if (first == null)
            {
                first = problem;
            }
        }
        problems.add(first);
    }

    private static String checkNestedType(String path, String type, JsonObject schema, JsonElement value,
        List<String> problems)
    {
        switch (type)
        {
            case "null": //$NON-NLS-1$
                return value.isJsonNull() ? null : expected(path, "null", value); //$NON-NLS-1$
            case STRING:
                if (!value.isJsonPrimitive())
                {
                    return expected(path, "a string", value); //$NON-NLS-1$
                }
                checkEnum(path, schema, value, problems);
                checkStringBounds(path, schema, value.getAsString(), problems);
                return null;
            case INTEGER:
            case NUMBER:
            {
                Number number = topLevelNumber(value, INTEGER.equals(type));
                if (number == null)
                {
                    return expected(path, INTEGER.equals(type) ? "an integer" : "a number", value); //$NON-NLS-1$ //$NON-NLS-2$
                }
                checkEnum(path, schema, value, problems);
                checkNumberBounds(path, schema, number.doubleValue(), problems);
                return null;
            }
            case BOOLEAN:
                // Gson's getAsBoolean reads any other text as false, so only true/false pass.
                if (value.isJsonPrimitive() && (value.getAsJsonPrimitive().isBoolean()
                    || "true".equalsIgnoreCase(value.getAsString().trim()) //$NON-NLS-1$
                    || "false".equalsIgnoreCase(value.getAsString().trim()))) //$NON-NLS-1$
                {
                    return null;
                }
                return expected(path, "true or false", value); //$NON-NLS-1$
            case ARRAY:
                if (!value.isJsonArray())
                {
                    return expected(path, "an array", value); //$NON-NLS-1$
                }
                checkArray(path, schema, value.getAsJsonArray(), problems);
                return null;
            case OBJECT:
                if (!value.isJsonObject())
                {
                    return expected(path, ("an object " + shape(schema)).trim(), value); //$NON-NLS-1$
                }
                checkObject(path, schema, value.getAsJsonObject(), problems);
                return null;
            default:
                return null;
        }
    }

    private static void checkArray(String path, JsonObject schema, JsonArray array, List<String> problems)
    {
        Integer min = integer(schema, "minItems"); //$NON-NLS-1$
        Integer max = integer(schema, "maxItems"); //$NON-NLS-1$
        if (min != null && array.size() < min)
        {
            problems.add(path + " needs at least " + min + " item(s); got " + array.size() + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (max != null && array.size() > max)
        {
            problems.add(path + " takes at most " + max + " item(s); got " + array.size() + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        JsonObject items = object(schema, ITEMS);
        if (items == null)
        {
            return;
        }
        for (int i = 0; i < array.size(); i++)
        {
            JsonElement item = array.get(i);
            String itemPath = path + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            if (item.isJsonNull())
            {
                problems.add(itemPath + " is null; remove it or give a value."); //$NON-NLS-1$
                continue;
            }
            checkNested(itemPath, items, item, problems);
        }
    }

    private static void checkObject(String path, JsonObject schema, JsonObject value, List<String> problems)
    {
        JsonObject properties = object(schema, PROPERTIES);
        JsonElement required = schema.get("required"); //$NON-NLS-1$
        if (required != null && required.isJsonArray())
        {
            for (JsonElement name : required.getAsJsonArray())
            {
                // JSON null fills a required member only where the member declares a null type.
                JsonElement member = value.get(name.getAsString());
                JsonObject declared = properties == null ? null : object(properties, name.getAsString());
                boolean nullable = declared != null && types(declared).contains("null"); //$NON-NLS-1$
                if (member == null || member.isJsonNull() && !nullable)
                {
                    problems.add(path + "." + name.getAsString() + " is required."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        JsonElement additional = schema.get("additionalProperties"); //$NON-NLS-1$
        for (Map.Entry<String, JsonElement> member : value.entrySet())
        {
            String memberPath = path + "." + member.getKey(); //$NON-NLS-1$
            JsonObject declared = properties == null ? null : object(properties, member.getKey());
            if (declared != null)
            {
                if (!member.getValue().isJsonNull())
                {
                    checkNested(memberPath, declared, member.getValue(), problems);
                }
            }
            else if (additional != null && additional.isJsonPrimitive() && !additional.getAsBoolean())
            {
                problems.add(memberPath + " is not a known key; accepted: " //$NON-NLS-1$
                    + (properties == null ? "none" : String.join(", ", properties.keySet())) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            else if (additional != null && additional.isJsonObject() && !member.getValue().isJsonNull())
            {
                checkNested(memberPath, additional.getAsJsonObject(), member.getValue(), problems);
            }
        }
    }

    // ---- shared keyword checks -----------------------------------------------------------------------

    private static void checkEnum(String path, JsonObject schema, JsonElement value, List<String> problems)
    {
        JsonElement allowed = schema.get("enum"); //$NON-NLS-1$
        if (allowed == null || !allowed.isJsonArray() || !value.isJsonPrimitive())
        {
            return;
        }
        String text = value.getAsString().trim();
        List<String> names = new ArrayList<>();
        for (JsonElement option : allowed.getAsJsonArray())
        {
            if (!option.isJsonPrimitive())
            {
                return;
            }
            String candidate = option.getAsString();
            if (candidate.equalsIgnoreCase(text) || sameNumber(candidate, text))
            {
                return;
            }
            names.add("'" + candidate + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        problems.add(path + " must be one of " + String.join(", ", names) + "; got " + describe(value) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static boolean sameNumber(String a, String b)
    {
        Long left = JsonUtils.parseIntegralLong(a);
        return left != null && left.equals(JsonUtils.parseIntegralLong(b));
    }

    private static void checkNumberBounds(String path, JsonObject schema, double value, List<String> problems)
    {
        JsonElement min = schema.get("minimum"); //$NON-NLS-1$
        JsonElement max = schema.get("maximum"); //$NON-NLS-1$
        if (min != null && min.isJsonPrimitive() && value < min.getAsDouble())
        {
            problems.add(path + " must be at least " + min.getAsString() + "; got " + format(value) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (max != null && max.isJsonPrimitive() && value > max.getAsDouble())
        {
            problems.add(path + " must be at most " + max.getAsString() + "; got " + format(value) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static void checkStringBounds(String path, JsonObject schema, String value, List<String> problems)
    {
        Integer min = integer(schema, "minLength"); //$NON-NLS-1$
        if (min != null && value.length() < min)
        {
            problems.add(path + " must have at least " + min + " character(s)."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** An array or object may arrive as its JSON text; returns the decoded value, or the value itself. */
    private static JsonElement decodeJsonText(JsonElement value)
    {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            return value;
        }
        String text = value.getAsString().trim();
        if (!text.startsWith("[") && !text.startsWith("{")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return value;
        }
        try
        {
            return JsonParser.parseString(text);
        }
        catch (RuntimeException e)
        {
            return value;
        }
    }

    private static boolean isEmptyText(JsonElement value)
    {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().trim().isEmpty();
    }

    private static boolean declares(JsonObject schema, String type)
    {
        return types(schema).contains(type);
    }

    /** Tries the declared type that matches the value's own JSON kind first, so {@code true} is a boolean. */
    private static List<String> nativeFirst(List<String> types, JsonElement value)
    {
        if (types.size() < 2 || !value.isJsonPrimitive())
        {
            return types;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        String own = primitive.isBoolean() ? BOOLEAN : primitive.isNumber() ? NUMBER : STRING;
        List<String> ordered = new ArrayList<>(types);
        String match = NUMBER.equals(own) && !types.contains(NUMBER) ? INTEGER : own;
        if (ordered.remove(match))
        {
            ordered.add(0, match);
        }
        return ordered;
    }

    private static List<String> types(JsonObject schema)
    {
        List<String> result = new ArrayList<>();
        JsonElement type = schema.get(TYPE);
        if (type == null)
        {
            return result;
        }
        if (type.isJsonArray())
        {
            for (JsonElement t : type.getAsJsonArray())
            {
                result.add(t.getAsString());
            }
        }
        else if (type.isJsonPrimitive())
        {
            result.add(type.getAsString());
        }
        return result;
    }

    private static JsonObject object(JsonObject parent, String key)
    {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static Integer integer(JsonObject schema, String key)
    {
        JsonElement element = schema.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
            ? Integer.valueOf(element.getAsInt()) : null;
    }

    /** The declared members as {@code "{a, b?} "} (optional ones marked), or {@code ""} when none are declared. */
    private static String shape(JsonObject schema)
    {
        JsonObject properties = schema == null ? null : object(schema, PROPERTIES);
        if (properties == null || properties.size() == 0)
        {
            return ""; //$NON-NLS-1$
        }
        List<String> required = new ArrayList<>();
        JsonElement names = schema.get("required"); //$NON-NLS-1$
        if (names != null && names.isJsonArray())
        {
            names.getAsJsonArray().forEach(n -> required.add(n.getAsString()));
        }
        List<String> members = new ArrayList<>();
        for (String name : properties.keySet())
        {
            members.add(required.contains(name) ? name : name + "?"); //$NON-NLS-1$
        }
        return "{" + String.join(", ", members) + "} "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String expected(String path, String what, JsonElement value)
    {
        return path + " must be " + what + "; got " + describe(value) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String describe(JsonElement value)
    {
        if (value.isJsonArray())
        {
            return "an array"; //$NON-NLS-1$
        }
        if (value.isJsonObject())
        {
            return "an object"; //$NON-NLS-1$
        }
        if (value.isJsonNull())
        {
            return "null"; //$NON-NLS-1$
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString())
        {
            String text = primitive.getAsString();
            return "the string '" + (text.length() > 60 ? text.substring(0, 60) + "..." : text) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return primitive.isNumber() ? format(primitive.getAsDouble()) : primitive.getAsString();
    }

    private static String format(double value)
    {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }

    private static JsonObject parse(String inputSchema)
    {
        if (inputSchema == null || inputSchema.isBlank())
        {
            return null;
        }
        JsonObject cached = SCHEMAS.get(inputSchema);
        if (cached != null)
        {
            return cached;
        }
        try
        {
            JsonElement element = JsonParser.parseString(inputSchema);
            if (!element.isJsonObject())
            {
                return null;
            }
            if (SCHEMAS.size() >= MAX_CACHED_SCHEMAS)
            {
                SCHEMAS.clear();
            }
            SCHEMAS.put(inputSchema, element.getAsJsonObject());
            return element.getAsJsonObject();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
