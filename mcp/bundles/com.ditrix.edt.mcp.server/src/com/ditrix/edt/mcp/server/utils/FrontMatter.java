/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builder for YAML frontmatter blocks prepended to Markdown tool responses.
 * <p>
 * Produces output in the format:
 * <pre>
 * ---
 * key1: value1
 * key2: value2
 * ---
 * </pre>
 * <p>
 * Fields are rendered in insertion order. String values are automatically escaped
 * for safe YAML scalar output.
 *
 * @see com.ditrix.edt.mcp.server.protocol.ToolResult
 */
public final class FrontMatter
{
    /** Pattern matching YAML special characters that require quoting. */
    private static final Pattern YAML_SPECIAL =
        Pattern.compile("[:\\#\\[\\]\\{\\},&*?|>@`!%'\"\\\\]"); //$NON-NLS-1$

    /** Pattern matching strings that look like numbers and should be quoted to stay as strings. */
    private static final Pattern NUMERIC_PATTERN =
        Pattern.compile("^[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$"); //$NON-NLS-1$

    /** YAML reserved words that must be quoted to avoid misinterpretation. */
    private static final String[] YAML_RESERVED =
    {
        "true", "false", "null", "yes", "no", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "True", "False", "Null", "Yes", "No", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "TRUE", "FALSE", "NULL", "YES", "NO" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private FrontMatter()
    {
        // Use create() factory method
    }

    /**
     * Creates a new FrontMatter builder.
     *
     * @return new builder instance
     */
    public static FrontMatter create()
    {
        return new FrontMatter();
    }

    /**
     * Adds a string field.
     *
     * @param key field name
     * @param value field value (will be YAML-escaped if needed)
     * @return this builder for chaining
     */
    public FrontMatter put(String key, String value)
    {
        validateKey(key);
        fields.put(key, value);
        return this;
    }

    /**
     * Adds an integer field.
     *
     * @param key field name (must not be {@code null})
     * @param value field value
     * @return this builder for chaining
     */
    public FrontMatter put(String key, int value)
    {
        validateKey(key);
        fields.put(key, Integer.valueOf(value));
        return this;
    }

    /**
     * Adds a long field.
     *
     * @param key field name (must not be {@code null})
     * @param value field value
     * @return this builder for chaining
     */
    public FrontMatter put(String key, long value)
    {
        validateKey(key);
        fields.put(key, Long.valueOf(value));
        return this;
    }

    /**
     * Adds a boolean field.
     *
     * @param key field name (must not be {@code null})
     * @param value field value
     * @return this builder for chaining
     */
    public FrontMatter put(String key, boolean value)
    {
        validateKey(key);
        fields.put(key, Boolean.valueOf(value));
        return this;
    }

    /**
     * Renders the YAML frontmatter block.
     *
     * @return formatted frontmatter string ending with {@code ---\n}
     */
    public String build()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n"); //$NON-NLS-1$
        for (Map.Entry<String, Object> entry : fields.entrySet())
        {
            sb.append(entry.getKey());
            sb.append(": "); //$NON-NLS-1$
            sb.append(formatValue(entry.getValue()));
            sb.append('\n');
        }
        sb.append("---\n"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Renders the YAML frontmatter block and prepends it to the given markdown body.
     *
     * @param markdownBody the markdown content to wrap
     * @return frontmatter + body
     */
    public String wrapContent(String markdownBody)
    {
        return build() + markdownBody;
    }

    /**
     * Validates that the key is not {@code null}.
     *
     * @param key the key to validate
     * @throws IllegalArgumentException if key is {@code null}
     */
    private static void validateKey(String key)
    {
        if (key == null)
        {
            throw new IllegalArgumentException("FrontMatter key must not be null"); //$NON-NLS-1$
        }
    }

    /**
     * Formats a value for YAML output.
     */
    private static String formatValue(Object value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long)
        {
            return value.toString();
        }
        return escapeYamlValue(value.toString());
    }

    /**
     * Escapes a string value for safe YAML scalar output.
     * <p>
     * If the value contains YAML special characters, starts/ends with whitespace,
     * or is a YAML reserved word, it is wrapped in double quotes with internal
     * {@code "} and {@code \} characters escaped.
     *
     * @param value the string value to escape
     * @return YAML-safe scalar representation
     */
    static String escapeYamlValue(String value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (value.isEmpty())
        {
            return "\"\""; //$NON-NLS-1$
        }

        // Check if quoting is needed
        boolean needsQuoting = false;

        // Starts or ends with whitespace
        if (Character.isWhitespace(value.charAt(0))
            || Character.isWhitespace(value.charAt(value.length() - 1)))
        {
            needsQuoting = true;
        }

        // Contains YAML special characters
        if (!needsQuoting && YAML_SPECIAL.matcher(value).find())
        {
            needsQuoting = true;
        }

        // Is a YAML reserved word
        if (!needsQuoting)
        {
            for (String reserved : YAML_RESERVED)
            {
                if (reserved.equals(value))
                {
                    needsQuoting = true;
                    break;
                }
            }
        }

        // Contains newlines
        if (!needsQuoting && (value.contains("\n") || value.contains("\r"))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            needsQuoting = true;
        }

        // Looks like a number — quote to preserve string type
        if (!needsQuoting && NUMERIC_PATTERN.matcher(value).matches())
        {
            needsQuoting = true;
        }

        if (!needsQuoting)
        {
            return value;
        }

        // Wrap in double quotes, escaping \ and "
        return "\"" //$NON-NLS-1$
            + value.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "\\\"") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\r", "\\r") //$NON-NLS-1$ //$NON-NLS-2$
            + "\""; //$NON-NLS-1$
    }
}
