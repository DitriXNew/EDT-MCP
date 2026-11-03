/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Strict, model-free preflight for the single-form delete subset. Indexes are zero-based. */
public final class MetadataDeleteBatch
{
    public static final String OPERATIONS = "operations";
    public static final String ATOMIC = "atomic";
    public static final int MAX_OPERATIONS = 100;
    private static final Set<String> KEYS = Set.of("projectName", OPERATIONS, ATOMIC, "confirm", "force");

    public final List<Operation> operations = new ArrayList<>();
    public final List<Map<String, Object>> errors = new ArrayList<>();

    public static final class Operation
    {
        public final int index;
        public final String fqn;
        public final FormElementWriter.FormMemberRef ref;

        private Operation(int index, String fqn, FormElementWriter.FormMemberRef ref)
        {
            this.index = index;
            this.fqn = fqn;
            this.ref = ref;
        }
    }

    private MetadataDeleteBatch()
    {
    }

    public static MetadataDeleteBatch parse(Map<String, String> params)
    {
        MetadataDeleteBatch batch = new MetadataDeleteBatch();
        for (String key : params.keySet())
        {
            if (!KEYS.contains(key))
            {
                batch.error(-1, "invalidNow", "Parameter '" + key
                    + "' is not supported in a batch. Pass operations instead of fqn; omit timeout.");
            }
        }
        String project = JsonUtils.extractStringArgument(params, "projectName");
        if (project == null || project.isBlank())
        {
            batch.error(-1, "invalidNow", "Provide a non-empty projectName.");
        }
        for (String key : List.of(ATOMIC, "confirm", "force"))
        {
            if (params.containsKey(key) && !"true".equals(params.get(key)) && !"false".equals(params.get(key)))
            {
                batch.error(-1, "invalidNow", "Parameter '" + key + "' must be a boolean; pass true or false.");
            }
        }
        if ("false".equals(params.get(ATOMIC)) || "true".equals(params.get("force")))
        {
            batch.error(-1, "unsupportedBatch", "Only atomic=true and force=false are supported. "
                + "Omit these parameters to use the safe defaults.");
        }
        try
        {
            JsonElement input = JsonParser.parseString(params.get(OPERATIONS));
            if (!input.isJsonArray() || input.getAsJsonArray().isEmpty()
                || input.getAsJsonArray().size() > MAX_OPERATIONS)
            {
                batch.error(-1, "invalidNow", "operations must be an array of 1..100 {action:'delete', fqn:'...'} objects.");
                return batch;
            }
            int index = 0;
            for (JsonElement item : input.getAsJsonArray())
            {
                if (!item.isJsonObject())
                {
                    batch.error(index, "invalidNow", "operations[" + index + "] must be an object with action and fqn.");
                }
                else
                {
                    batch.parseOperation(index, item.getAsJsonObject());
                }
                index++;
            }
        }
        catch (RuntimeException e)
        {
            batch.error(-1, "invalidNow", "Cannot parse operations. Pass a JSON array of {action:'delete', fqn:'...'} objects.");
        }
        return batch;
    }

    private void parseOperation(int index, JsonObject item)
    {
        for (String key : item.keySet())
        {
            if (!Set.of("action", "fqn").contains(key))
            {
                error(index, "invalidNow", "Unknown operation parameter '" + key + "'. Use only action and fqn.");
            }
        }
        if (!"delete".equals(string(item.get("action"))))
        {
            error(index, "unsupportedBatch", "Unsupported action '" + item.get("action")
                + "'. This batch supports only action='delete'; use create_metadata or modify_metadata separately.");
        }
        String fqn = string(item.get("fqn"));
        if (fqn == null || fqn.isBlank())
        {
            error(index, "invalidNow", "Provide a non-empty string fqn for operations[" + index + "].");
            return;
        }
        try
        {
            String normalized = MetadataTypeUtils.normalizeFqn(fqn);
            FormElementWriter.FormMemberRef ref = FormElementWriter.parse(normalized);
            if (ref == null || ref.ownerAttributeName != null
                || FormElementWriter.kindForToken(ref.kindToken) == FormElementWriter.Kind.ATTRIBUTE)
            {
                error(index, "unsupportedBatch", "Unsupported batch target '" + fqn
                    + "'. Use items, commands, parameters or handlers of ONE form; delete attributes/columns separately.");
                return;
            }
            if (!operations.isEmpty() && !operations.get(0).ref.formPath.equalsIgnoreCase(ref.formPath))
            {
                error(index, "unsupportedBatch", "Target '" + fqn + "' belongs to another form. Split the batch by form.");
            }
            operations.add(new Operation(index, normalized, ref));
        }
        catch (RuntimeException e)
        {
            error(index, "invalidNow", "Invalid fqn '" + fqn + "'. Use get_metadata_details to find a form member address.");
        }
    }

    private static String string(JsonElement value)
    {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    public void error(int index, String reason, String message)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("operationIndex", index);
        entry.put("reason", reason);
        entry.put("error", message);
        errors.add(entry);
    }

    public String refusal()
    {
        return ToolResult.error("Batch preflight failed. Correct all listed errors and preview again; nothing was changed.")
            .put("reason", "invalidBatch").put("errors", errors).put("applied", Collections.emptyList())
            .put("committed", false).put("rollbackPerformed", false).put("persistenceState", "unchanged")
            .put("remainingState", "Project unchanged by this request.")
            .put("safeRetry", "Correct the listed errors, then preview the complete batch again.").toJson();
    }

    public List<Integer> indexes()
    {
        return operations.stream().map(op -> op.index).toList();
    }
}
