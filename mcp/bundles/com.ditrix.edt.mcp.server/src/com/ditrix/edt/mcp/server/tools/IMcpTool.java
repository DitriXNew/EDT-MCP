/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.utils.GuideLoader;

/**
 * Interface for MCP tool implementations.
 * Each tool provides a specific capability to MCP clients.
 */
public interface IMcpTool
{
    /**
     * Response content type for tool results.
     */
    enum ResponseType
    {
        /** Plain text response */
        TEXT,
        /** JSON response with structuredContent */
        JSON,
        /** Markdown response returned as EmbeddedResource with mimeType */
        MARKDOWN,
        /** YAML response returned as EmbeddedResource with a text/yaml mimeType */
        YAML,
        /** Image response returned as EmbeddedResource with image/* mimeType */
        IMAGE
    }
    
    /**
     * Returns the unique name of the tool.
     * This name is used in MCP protocol to identify the tool.
     * 
     * @return tool name (e.g., "get_edt_version", "list_projects")
     */
    String getName();
    
    /**
     * Returns a human-readable description of the tool.
     * This description is sent to MCP clients in tools/list response.
     * 
     * @return tool description
     */
    String getDescription();
    
    /**
     * Returns the JSON Schema for input parameters.
     * Used by MCP clients to validate input before calling the tool.
     *
     * @return input schema as JSON string
     */
    String getInputSchema();

    /**
     * Returns the JSON Schema (2020-12) describing this tool's {@code structuredContent}
     * on success, or {@code null} when the tool returns no structured output.
     * <p>
     * Serialized into {@code tools/list} as the per-tool {@code outputSchema} so a
     * client knows the shape of the structured result without calling the tool. The
     * default is {@code null}: only {@link ResponseType#JSON} tools (which put their
     * data in {@code structuredContent}) should override it; {@code TEXT}/{@code
     * MARKDOWN}/{@code YAML}/{@code IMAGE} tools return content, not structured data,
     * so they leave it {@code null}.
     * <p>
     * The schema MUST stay permissive — describe the success envelope ({@code success}
     * plus the known top-level fields and their types) but do NOT set {@code
     * additionalProperties:false} and do NOT mark conditional (branch-specific) fields
     * as {@code required}. An over-strict schema would make a conformant client reject
     * a valid response. Error results carry {@code isError:true} and are not validated
     * against this schema. Build it with {@link com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder}.
     *
     * @return output schema as a JSON string, or {@code null} when there is none
     */
    default String getOutputSchema()
    {
        return null;
    }
    
    /**
     * Executes the tool with the given parameters.
     * 
     * @param params map of parameter name to value
     * @return result string (format depends on getResponseType())
     */
    String execute(Map<String, String> params);
    
    /**
     * Returns the response content type for this tool.
     * Default is MARKDOWN for better context efficiency.
     * 
     * @return response type
     */
    default ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
    
    /**
     * Returns the result file name for EmbeddedResource URI.
     * Used when response type is MARKDOWN.
     * Default returns tool name with .md extension.
     * Override to provide dynamic file name based on parameters.
     * 
     * @param params the execution parameters
     * @return file name with extension (e.g., "begin-transaction.md")
     */
    default String getResultFileName(Map<String, String> params)
    {
        return getName() + ".md"; //$NON-NLS-1$
    }

    /**
     * Returns the MCP behavioral annotations (hints) for this tool, included in
     * the {@code tools/list} response. Default is {@code null}, which lets the
     * central {@code ToolAnnotationClassifier} derive the hints from the tool
     * name. Override to provide explicit annotations for a specific tool.
     *
     * @return the tool annotations, or {@code null} to use the central classifier
     */
    default ToolAnnotations getAnnotations()
    {
        return null;
    }

    /**
     * Returns an extended, on-demand how-to guide for this tool: worked examples,
     * preconditions, edge cases and bilingual (ru/en) nuances — the detail that
     * would otherwise bloat the always-loaded {@code tools/list}.
     * <p>
     * This text is NOT sent in {@code tools/list}; it is served only when a client
     * explicitly asks for it via the {@code get_tool_guide} tool (or the resource
     * {@code guide://<toolName>}), so the {@link #getDescription()} and
     * {@link #getInputSchema()} can stay lean (what + when + next + one-line param
     * help) while the depth lives here.
     * <p>
     * The default loads the guide from a bundled Markdown resource
     * {@code guides/<name>.md} via {@link com.ditrix.edt.mcp.server.utils.GuideLoader}
     * — the single source of truth for tool guides. Adding a guide is dropping a
     * Markdown file; a tool with no such file simply returns {@code ""} (its
     * description and schema are then self-contained). Override only for a guide
     * that must be computed at runtime.
     *
     * @return the extended guide as Markdown, or {@code ""} when there is none
     */
    default String getGuide()
    {
        return GuideLoader.load(getName());
    }
}
