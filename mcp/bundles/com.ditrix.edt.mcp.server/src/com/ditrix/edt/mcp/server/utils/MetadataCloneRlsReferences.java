/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Literal source mentions in the copied object's RLS conditions, excluding the role's other rows. */
public final class MetadataCloneRlsReferences
{
    private MetadataCloneRlsReferences()
    {
    }

    public static JsonArray find(String file, String xml, String sourceFqn, String cloneFqn)
        throws XMLStreamException
    {
        String text = xml.startsWith("\ufeff") ? xml.substring(1) : xml;
        XMLStreamReader reader = SecureXml.streamFactory().createXMLStreamReader(new StringReader(text));
        List<String> path = new ArrayList<>();
        Set<Integer> lines = new LinkedHashSet<>();
        JsonArray pending = new JsonArray();
        String owner = null;
        int conditionStart = -1;
        boolean found = false;
        try
        {
            while (reader.hasNext())
            {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD)
                {
                    throw new XMLStreamException("DOCTYPE is not allowed in role rights");
                }
                if (event == XMLStreamConstants.START_ELEMENT)
                {
                    path.add(reader.getLocalName());
                    if (path.equals(List.of("Rights", "object")))
                    {
                        owner = null;
                        pending = new JsonArray();
                    }
                    else if (path.equals(List.of("Rights", "object", "name")))
                    {
                        owner = reader.getElementText();
                        path.remove(path.size() - 1);
                    }
                    else if (condition(path))
                    {
                        conditionStart = reader.getLocation().getCharacterOffset();
                    }
                }
                else if (event == XMLStreamConstants.END_ELEMENT)
                {
                    if (condition(path) && (owner == null || cloneFqn.equals(owner)))
                    {
                        int end = reader.getLocation().getCharacterOffset();
                        if (conditionStart < 0 || end < conditionStart || end > text.length())
                        {
                            throw new XMLStreamException("Cannot locate the exported RLS condition in " + file);
                        }
                        int precedingLines = text.substring(0, conditionStart).split("\\R", -1).length - 1;
                        for (JsonElement hit : MetadataClonePlan.textReferences(file,
                            text.substring(conditionStart, end), sourceFqn))
                        {
                            JsonObject row = hit.getAsJsonObject();
                            row.addProperty("line", precedingLines + row.get("line").getAsInt());
                            pending.add(row);
                        }
                    }
                    if (path.equals(List.of("Rights", "object")) && cloneFqn.equals(owner))
                    {
                        found = true;
                        for (JsonElement hit : pending)
                        {
                            lines.add(hit.getAsJsonObject().get("line").getAsInt());
                        }
                    }
                    path.remove(path.size() - 1);
                }
            }
        }
        finally
        {
            reader.close();
        }
        if (!found)
        {
            throw new IllegalStateException("The copied rights row '" + cloneFqn + "' is missing from '"
                + file + "'. Inspect the role in EDT before recovery.");
        }
        JsonArray result = new JsonArray();
        for (int line : lines)
        {
            JsonObject row = new JsonObject();
            row.addProperty("file", file);
            row.addProperty("line", line);
            result.add(row);
        }
        return result;
    }

    private static boolean condition(List<String> path)
    {
        return path.equals(List.of("Rights", "object", "right", "restrictionByCondition", "condition"));
    }
}
