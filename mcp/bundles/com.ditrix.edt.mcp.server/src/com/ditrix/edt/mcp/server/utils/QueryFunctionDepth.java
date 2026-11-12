/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;

/** Linear lexical guard; CAST and type constructors count conservatively as calls. */
final class QueryFunctionDepth
{
    private static final Set<String> KEYWORDS = Set.of(
        "LIKE",
        "\u041f\u041e\u0414\u041e\u0411\u041d\u041e",
        "ESCAPE",
        "\u0421\u041f\u0415\u0426\u0421\u0418\u041c\u0412\u041e\u041b",
        "BETWEEN",
        "\u041c\u0415\u0416\u0414\u0423",
        "SETS",
        "\u041d\u0410\u0411\u041e\u0420\u0410\u041c",
        "\u041d\u0410\u0411\u041e\u0420\u042b",
        "PERIODS",
        "\u041f\u0415\u0420\u0418\u041e\u0414\u0410\u041c\u0418",
        "CHARACTERISTICTYPES",
        "\u0412\u0418\u0414\u042b\u0425\u0410\u0420\u0410\u041a\u0422\u0415\u0420\u0418\u0421\u0422\u0418\u041a",
        "LIST",
        "\u0421\u041f\u0418\u0421\u041e\u041a",
        "CHARACTERISTICVALUES",
        "\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f\u0425\u0410\u0420\u0410\u041a\u0422\u0415\u0420\u0418\u0421\u0422\u0418\u041a",
        "VALUES",
        "\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f",
        "KEYFIELD",
        "\u041f\u041e\u041b\u0415\u041a\u041b\u042e\u0427\u0410",
        "ID",
        "\u0418\u0414\u0415\u041d\u0422\u0418\u0424\u0418\u041a\u0410\u0422\u041e\u0420",
        "NAMEFIELD",
        "\u041f\u041e\u041b\u0415\u0418\u041c\u0415\u041d\u0418",
        "NAME",
        "\u0418\u041c\u042f",
        "VALUETYPEFIELD",
        "\u041f\u041e\u041b\u0415\u0422\u0418\u041f\u0410\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f",
        "OBJECTFIELD",
        "\u041f\u041e\u041b\u0415\u041e\u0411\u042a\u0415\u041a\u0422\u0410",
        "OBJECT",
        "\u041e\u0411\u042a\u0415\u041a\u0422",
        "TYPEFIELD",
        "\u041f\u041e\u041b\u0415\u0412\u0418\u0414\u0410",
        "CHARACTERISTIC",
        "\u0425\u0410\u0420\u0410\u041a\u0422\u0415\u0420\u0418\u0421\u0422\u0418\u041a\u0410",
        "VALUEFIELD",
        "\u041f\u041e\u041b\u0415\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f",
        "SELECT", "FROM", "WHERE", "IN", "AND", "OR", "NOT", "ON", "AS", "BY", "HAVING",
        "WHEN", "THEN", "ELSE", "CASE", "JOIN", "DISTINCT", "UNION", "ALL", "INTO", "EXISTS",
        "ALLOWED", "TOP", "GROUP", "ORDER", "TOTALS", "AUTOORDER", "INDEX", "INNER",
        "FULL", "OUTER", "FOR", "UPDATE", "HIERARCHY", "END", "REFS", "IS", "NULL",
        "TRUE", "FALSE", "EMPTYTABLE", "DROP", "ADD",
        "\u0412\u042b\u0411\u0420\u0410\u0422\u042c", "\u0418\u0417", "\u0413\u0414\u0415",
        "\u0412", "\u0418", "\u0418\u041b\u0418", "\u041d\u0415", "\u041f\u041e",
        "\u041a\u0410\u041a", "\u0418\u041c\u0415\u042e\u0429\u0418\u0415",
        "\u041a\u041e\u0413\u0414\u0410", "\u0422\u041e\u0413\u0414\u0410",
        "\u0418\u041d\u0410\u0427\u0415", "\u0412\u042b\u0411\u041e\u0420",
        "\u041e\u0411\u042a\u0415\u0414\u0418\u041d\u0418\u0422\u042c",
        "\u0412\u0421\u0415", "\u041f\u041e\u041c\u0415\u0421\u0422\u0418\u0422\u042c",
        "\u0420\u0410\u0417\u0420\u0415\u0428\u0415\u041d\u041d\u042b\u0415",
        "\u0420\u0410\u0417\u041b\u0418\u0427\u041d\u042b\u0415", "\u041f\u0415\u0420\u0412\u042b\u0415",
        "\u0421\u0413\u0420\u0423\u041f\u041f\u0418\u0420\u041e\u0412\u0410\u0422\u042c",
        "\u0423\u041f\u041e\u0420\u042f\u0414\u041e\u0427\u0418\u0422\u042c",
        "\u0418\u0422\u041e\u0413\u0418", "\u0410\u0412\u0422\u041e\u0423\u041f\u041e\u0420\u042f\u0414\u041e\u0427\u0418\u0412\u0410\u041d\u0418\u0415",
        "\u0418\u041d\u0414\u0415\u041a\u0421\u0418\u0420\u041e\u0412\u0410\u0422\u042c",
        "\u0421\u041e\u0415\u0414\u0418\u041d\u0415\u041d\u0418\u0415",
        "\u0412\u041d\u0423\u0422\u0420\u0415\u041d\u041d\u0415\u0415", "\u041b\u0415\u0412\u041e\u0415",
        "\u041f\u0420\u0410\u0412\u041e\u0415", "\u041f\u041e\u041b\u041d\u041e\u0415",
        "\u0412\u041d\u0415\u0428\u041d\u0415\u0415", "\u0414\u041b\u042f",
        "\u0418\u0417\u041c\u0415\u041d\u0415\u041d\u0418\u042f", "\u0418\u0415\u0420\u0410\u0420\u0425\u0418\u0418",
        "\u041a\u041e\u041d\u0415\u0426", "\u0421\u0421\u042b\u041b\u041a\u0410", "\u0415\u0421\u0422\u042c",
        "\u0418\u0421\u0422\u0418\u041d\u0410", "\u041b\u041e\u0416\u042c",
        "\u041f\u0423\u0421\u0422\u0410\u042f\u0422\u0410\u0411\u041b\u0418\u0426\u0410",
        "\u0423\u041d\u0418\u0427\u0422\u041e\u0416\u0418\u0422\u042c", "\u0414\u041e\u0411\u0410\u0412\u0418\u0422\u042c");

    private QueryFunctionDepth() { }

    static int maximum(String text)
    {
        var brackets = new ArrayDeque<Boolean>();
        int depth = 0;
        int maximum = 0;
        String identifier = null;
        for (int i = 0; i < text.length();)
        {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')
            {
                while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
                continue;
            }
            if (c == '"')
            {
                i++;
                while (i < text.length())
                {
                    if (text.charAt(i++) != '"') continue;
                    if (i < text.length() && text.charAt(i) == '"') i++;
                    else break;
                }
                identifier = null;
                continue;
            }
            if (Character.isLetter(c) || c == '_')
            {
                int start = i++;
                while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i))
                    || text.charAt(i) == '_')) i++;
                identifier = text.substring(start, i).toUpperCase(Locale.ROOT);
                continue;
            }
            if (c == '(')
            {
                boolean call = identifier != null && !KEYWORDS.contains(identifier);
                brackets.push(call);
                if (call) maximum = Math.max(maximum, ++depth);
            }
            else if (c == ')' && !brackets.isEmpty() && brackets.pop()) depth--;
            identifier = null;
            i++;
        }
        return maximum;
    }
}
