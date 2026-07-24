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
     * The response type for ONE call, given its arguments — the per-call form of
     * {@link #getResponseType()}. The default ignores the arguments and returns the tool's fixed
     * {@link #getResponseType()}, so a tool with a single output format needs no override.
     * <p>
     * Override it for a tool that lets the caller choose the output format, e.g.
     * {@code list_projects}' {@code format} parameter: {@code md} (the default) renders the human
     * Markdown table, {@code json} returns the machine payload in {@code structuredContent}. The
     * dispatcher calls THIS method (never the no-arg one) when delivering a tool result.
     *
     * @param params the same parameters passed to {@link #execute(Map)}
     * @return the response type for this call
     */
    default ResponseType getResponseType(Map<String, String> params)
    {
        return getResponseType();
    }

    /**
     * Whether this tool's result can carry personal data read from a live 1C
     * infobase (debug variables, evaluated expressions, suspended stack frames,
     * and — once it lands — event-log entries), as opposed to EDT/workspace
     * metadata. The single wire-serialization choke point runs the PII
     * redactor ONLY on the result of a tool that returns {@code true} here; every
     * other tool's output is passed through untouched.
     * <p>
     * The default is {@code false}: a tool returns configuration/workspace
     * metadata, not live infobase data. This is a behavioral default only — it is
     * NOT part of the {@code tools/list} surface (not serialized into
     * {@code ToolInfo}), so overriding it never changes the tools/list golden.
     *
     * @return {@code true} if the tool may return infobase personal data
     */
    default boolean returnsInfobaseData()
    {
        return false;
    }

    /**
     * Opt-in marker for tools whose execution — synchronously or via an asynchronous
     * read-back {@link org.eclipse.core.runtime.jobs.Job Job} it launches — may open an
     * infobase connection and thus raise EDT's blocking "Configure Infobase access
     * Settings" credentials dialog. Gates whether the dispatch brackets the call with
     * {@link com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor#markActivityStart()}
     * / {@code markActivityEnd()} (issue #270): only a tool flagged here arms the
     * suppressor's activity window, so continuous MCP polling by a tool that never
     * touches a connection (e.g. {@code list_projects}, {@code get_metadata_details})
     * no longer keeps the window permanently hot and blocking a human's manual dialog.
     * <p>
     * The default is {@code false}: a tool reads/writes EDT workspace or metadata state
     * without reaching a live infobase connection. This is a behavioral default only — it
     * is NOT part of the {@code tools/list} surface (not serialized into {@code ToolInfo}),
     * so overriding it never changes the tools/list golden.
     *
     * @return {@code true} if the tool may open an infobase connection and raise the
     *         access-settings dialog
     */
    default boolean connectsToInfobase()
    {
        return false;
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
