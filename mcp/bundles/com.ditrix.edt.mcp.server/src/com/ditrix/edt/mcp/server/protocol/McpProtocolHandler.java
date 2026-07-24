/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.UserSignal;
import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.InitializeResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcResponse;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolCallResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolsListResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideRenderer;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.Log;
import com.ditrix.edt.mcp.server.utils.OutputSizeGuard;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRedactor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Handles MCP JSON-RPC protocol messages.
 * Implements the MCP 2025-11-25 specification (latest of
 * {@link McpConstants#SUPPORTED_VERSIONS}) over Streamable HTTP transport, and
 * negotiates the protocol version with the client during {@code initialize}.
 * Uses GsonProvider for JSON serialization/deserialization.
 */
public class McpProtocolHandler
{
    /**
     * A tools/call slower than this (wall-clock, ms) is logged at WARNING so an
     * operator can spot it in the EDT log without enabling debug. Failed calls are
     * also logged at WARNING regardless of duration.
     */
    static final long SLOW_TOOL_CALL_MS = 5000L;

    /** MIME type for Markdown resource bodies. */
    private static final String MIME_TEXT_MARKDOWN = "text/markdown"; //$NON-NLS-1$

    /** JSON key flagging a structured success/failure outcome. */
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    private final McpToolRegistry toolRegistry;

    /**
     * Capabilities the connected client declared in its last {@code initialize}
     * request. Server-scoped (NOT per session) because this EDT MCP server is
     * effectively single-client over localhost: one EDT workbench serves one
     * connected MCP client at a time, so the last initialize wins. {@code volatile}
     * because initialize and tools/call can be processed on different transport
     * threads. Defaults to {@link ClientCapabilities#ABSENT} so the behaviour
     * before any initialize (and for a client that sends no capabilities) is the
     * permissive default — in particular structuredContent stays emitted.
     */
    private final AtomicReference<ClientCapabilities> clientCapabilities =
        new AtomicReference<>(ClientCapabilities.ABSENT);

    /**
     * Creates a new protocol handler.
     */
    public McpProtocolHandler()
    {
        this.toolRegistry = McpToolRegistry.getInstance();
    }

    /**
     * The capabilities declared by the connected client in its last
     * {@code initialize}, never {@code null} (defaults to
     * {@link ClientCapabilities#ABSENT}). Exposed so current and future protocol
     * features can gate on what the client said it supports.
     *
     * @return the stored client capabilities
     */
    public ClientCapabilities getClientCapabilities()
    {
        return clientCapabilities.get();
    }
    
    /**
     * Processes an MCP JSON-RPC request. This is the single choke point through
     * which EVERY MCP message (initialize / tools/list / tools/call / notifications
     * / ping / resources) flows, so the request/response HISTORY recorder (#254) is
     * hooked here — once per message, in a {@code finally}, and strictly guarded so
     * it can never alter the returned response nor propagate a failure onto the wire
     * path. The returned value is {@link #dispatch(JsonRpcRequest)}'s EXACT String (no
     * envelope re-serialization).
     * <p>
     * The request is parsed ONCE here and the parsed form is reused for both dispatch
     * and the recorder, so a large tool-call payload is never parsed twice on this hot
     * path — {@link #dispatch(JsonRpcRequest)} takes the already-parsed request and the
     * {@code finally} reads method/toolName from it instead of re-parsing.
     *
     * @param requestBody the JSON request body
     * @return JSON response with correct id from request ({@code null} for a
     *         notification answered with 202 Accepted)
     */
    public String processRequest(String requestBody)
    {
        long startNanos = System.nanoTime();
        // Parse once at the choke point; parse() swallows a JSON syntax error and
        // returns null, and dispatch() treats a null request as an invalid request —
        // exactly as before, when the parse happened inside dispatch.
        JsonRpcRequest request = parse(requestBody);
        String response = null;
        try
        {
            response = dispatch(request);
        }
        finally
        {
            // Record this exchange into the in-memory history at the choke point.
            // Best-effort and strictly non-intrusive: a recorder failure, a
            // null/unparseable request, a notification's null response, or a missing
            // plugin context (Activator.getDefault()==null during a shutdown race or
            // in a headless unit test) are ALL swallowed here so the returned value —
            // dispatch's exact String — is never altered.
            try
            {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                String method = request != null ? request.getMethod() : null;
                String toolName = (request != null && McpConstants.METHOD_TOOLS_CALL.equals(method))
                    ? request.getToolName() : null;
                recordToHistory(method, toolName, requestBody, response, durationMs);
            }
            catch (Exception recordingFailure) // NOSONAR: history recording must never break a call
            {
                // Intentionally swallowed — the wire path must be unaffected by the
                // recorder (see the contract above).
            }
        }
        return response;
    }

    /**
     * Appends one recorded exchange to the shared in-memory {@link McpCallHistory}
     * at the choke point. Delegates to the history singleton, tolerating a
     * {@code null} singleton (e.g. the plugin is not active). Package-private and
     * overridable so the guard around it in {@link #processRequest} is unit-testable
     * without a live history buffer. The argument order mirrors
     * {@link com.ditrix.edt.mcp.server.history.McpCallRecord} (minus its
     * recorder-stamped timestamp).
     *
     * @param method the JSON-RPC method (may be {@code null} for an unparseable request)
     * @param toolName the tool name for a {@code tools/call}, else {@code null}
     * @param requestJson the raw request body (may be {@code null})
     * @param responseJson the response body, or {@code null} for a notification
     * @param durationMs the wall-clock exchange duration in milliseconds
     */
    void recordToHistory(String method, String toolName, String requestJson, String responseJson,
        long durationMs)
    {
        McpCallHistory history = McpCallHistory.getInstance();
        if (history != null)
        {
            history.record(method, toolName, requestJson, responseJson, durationMs);
        }
    }

    /**
     * Dispatches a parsed MCP JSON-RPC request to the matching handler and returns
     * the serialized response. Split out of {@link #processRequest} so it can be
     * wrapped with the history recorder without altering any dispatch behaviour; it
     * takes the already-parsed request ({@code null} for an unparseable body) so the
     * body is parsed only once at the choke point. Never throws: every error is turned
     * into a JSON-RPC error response here.
     *
     * @param request the parsed JSON-RPC request, or {@code null} for an unparseable
     *        body (handled as an invalid request)
     * @return JSON response with correct id from request ({@code null} for a
     *         notification answered with 202 Accepted)
     */
    private String dispatch(JsonRpcRequest request)
    {
        // Per JSON-RPC 2.0: when the id cannot be determined (parse error /
        // invalid request) the error response id MUST be null. A real id from
        // the parsed request overwrites this below.
        Object requestId = null;

        try
        {
            if (request != null)
            {
                requestId = normalizeId(request.getId());
            }

            // Validate JSON-RPC version
            if (request == null || !McpConstants.JSONRPC_VERSION.equals(request.getJsonrpc()))
            {
                return buildErrorResponse(McpConstants.ERROR_INVALID_REQUEST, 
                    "Invalid JSON-RPC version, expected 2.0", requestId); //$NON-NLS-1$
            }
            
            String method = request.getMethod();
            
            // Check for initialize method
            if (McpConstants.METHOD_INITIALIZE.equals(method))
            {
                // Per spec: echo back the client's requested protocol version if it is a
                // known/supported version; otherwise, fall back to our latest version.
                String clientVersion = request.getStringParam("protocolVersion"); //$NON-NLS-1$
                // Mirror the version handling for the client's declared capabilities:
                // read the optional "capabilities" object from the same params and
                // store it server-scoped so later tools/call (and future protocol
                // features) can gate on it. Absent / malformed capabilities resolve
                // to ClientCapabilities.ABSENT, which keeps every default permissive.
                clientCapabilities.set(parseClientCapabilities(request));
                return buildInitializeResponse(requestId, clientVersion);
            }
            
            // Check for initialized notification (no response needed, but return 202)
            if (McpConstants.METHOD_INITIALIZED.equals(method))
            {
                return null; // Signal for 202 Accepted with no body
            }
            
            // Check for tools/list method
            if (McpConstants.METHOD_TOOLS_LIST.equals(method))
            {
                return buildToolsListResponse(requestId);
            }
            
            // Check for tools/call method
            if (McpConstants.METHOD_TOOLS_CALL.equals(method))
            {
                return handleToolCall(request, requestId);
            }

            // Check for resources/list method (serves the per-tool guide:// docs)
            if (McpConstants.METHOD_RESOURCES_LIST.equals(method))
            {
                return buildResourcesListResponse(requestId);
            }

            // Check for resources/read method (returns one guide:// Markdown body)
            if (McpConstants.METHOD_RESOURCES_READ.equals(method))
            {
                return handleResourcesRead(request, requestId);
            }

            // Ping utility (MCP basic utilities): a connection-health check that takes no
            // params and MUST respond promptly with an empty result object.
            if (McpConstants.METHOD_PING.equals(method))
            {
                return GsonProvider.toJson(JsonRpcResponse.success(requestId, new JsonObject()));
            }

            // Method not found
            return buildErrorResponse(McpConstants.ERROR_METHOD_NOT_FOUND, "Method not found", requestId); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error processing MCP request", e); //$NON-NLS-1$
            return buildErrorResponse(McpConstants.ERROR_INTERNAL, e.getMessage(), requestId);
        }
    }
    
    /**
     * Parses a JSON-RPC request using GsonProvider. Shared by both the protocol
     * dispatch path ({@link #processRequest}) and the transport's interruptible
     * tool executor, so JSON id/name extraction lives in one place.
     *
     * @param requestBody the raw request body
     * @return the parsed request, or {@code null} on a JSON syntax error
     */
    public JsonRpcRequest parse(String requestBody)
    {
        try
        {
            return GsonProvider.fromJson(requestBody, JsonRpcRequest.class);
        }
        catch (JsonSyntaxException e)
        {
            Activator.logError("Failed to parse JSON-RPC request", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Normalizes a JSON-RPC request id. Gson deserializes JSON numbers into
     * {@code Object} fields as {@link Double}; whole-number Doubles are converted
     * to {@link Long} so {@code "id":0} serializes back as {@code 0} (not
     * {@code 0.0}), which is required for JSON-RPC id matching. Strings, nulls,
     * and non-whole numbers are returned unchanged.
     *
     * @param id the raw id from a parsed request (may be {@code null})
     * @return the normalized id
     */
    public static Object normalizeId(Object id)
    {
        if (id instanceof Double)
        {
            double d = (Double) id;
            if (!Double.isInfinite(d) && d == Math.floor(d)
                && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE)
            {
                return ((Double) id).longValue();
            }
        }
        return id;
    }
    
    /**
     * Handles a tools/call request.
     */
    private String handleToolCall(JsonRpcRequest request, Object requestId)
    {
        String toolName = request != null ? request.getToolName() : null;
        
        // Find tool by name
        IMcpTool tool = toolRegistry.getTool(toolName);
        if (tool == null)
        {
            return buildErrorResponse(McpConstants.ERROR_METHOD_NOT_FOUND, "Tool not found: " + toolName, requestId); //$NON-NLS-1$
        }

        // Check if tool is enabled
        if (!toolRegistry.isToolEnabled(toolName))
        {
            String msg = "Tool '" + toolName + "' is disabled by the user. " //$NON-NLS-1$ //$NON-NLS-2$
                + "If this functionality is needed, ask the user to enable it: " //$NON-NLS-1$
                + "EDT Preferences \u2192 MCP Server \u2192 Tools tab \u2192 check '" + toolName + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            return buildToolCallTextResponse(msg, requestId);
        }
        
        Activator.logInfo("Processing tools/call: " + tool.getName()); //$NON-NLS-1$
        
        // Extract parameters from request arguments
        Map<String, String> params = extractToolParams(request);
        
        // Set current tool name for status bar display
        McpServer server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        if (server != null)
        {
            server.setCurrentToolName(tool.getName());
        }
        
        // Execute the tool (timed + logged + status-bar cleared in one place).
        String result = executeToolTimed(tool, params, server);

        // PII redaction (#242): the single wire-serialization choke point.
        // A no-op unless redaction is enabled AND the tool is flagged returnsInfobaseData -
        // returns the same reference otherwise, so output stays byte-identical.
        result = PiiRedactor.redactIfEnabled(tool, params, result);

        // Check if user sent a signal during execution
        UserSignal signal = null;
        if (server != null)
        {
            signal = server.consumeUserSignal();
        }
        
        // Check if plain text mode is enabled (Cursor compatibility).
        // Activator.getDefault() can be null during a shutdown race; in that
        // case fall back to the safe default (structured content, not plain text).
        boolean plainTextMode = Activator.getDefault() != null
            && Activator.getDefault().getPreferenceStore()
                .getBoolean(PreferenceConstants.PREF_PLAIN_TEXT_MODE);

        // Return response based on tool's declared response type
        return buildToolCallResponse(tool, result, signal, plainTextMode, requestId, params);
    }

    /**
     * Executes {@code tool} while timing the call, clearing the status-bar tool name in a
     * {@code finally}, and emitting exactly one completion log line (plus, on failure, the
     * extracted error message at WARNING). Extracted verbatim from {@link #handleToolCall} so
     * the tool-result hot path stays a thin orchestrator. A thrown exception escaping
     * {@code execute} is propagated to the caller unchanged AFTER the {@code finally} has run
     * — same behaviour as the original inline try/finally.
     *
     * @param tool the resolved tool to run
     * @param params the extracted tool params
     * @param server the MCP server (may be {@code null} during a shutdown race)
     * @return the raw tool result payload (may be {@code null} only if {@code execute} returned
     *         {@code null})
     */
    private String executeToolTimed(IMcpTool tool, Map<String, String> params, McpServer server)
    {
        String result = null;
        long startNanos = System.nanoTime();
        boolean threw = true;
        // Ensure EDT's blocking "Configure Infobase access Settings" credentials dialog is
        // auto-cancelled (#194). Installed once (lazily, when the workbench display is ready) and
        // kept for the server's lifetime, so it also catches the dialog raised by EDT's BACKGROUND
        // update-state jobs (get_applications / create_infobase read-back), not just a synchronous
        // connect — a per-call arm would miss those. Idempotent + cheap after first install; no-op
        // headless. set_infobase_credentials provides the credentials so the dialog never needs to
        // appear on a correctly configured base.
        InfobaseAuthDialogSuppressor.ensureInstalled();
        // Scope the auth-dialog suppression to tools that can actually reach an infobase
        // connection (issue #270): only a tool flagged connectsToInfobase() marks this
        // dispatch in-flight, so the suppressor auto-cancels an auth dialog raised during
        // (and briefly after, via the trailing grace window that bridges async read-back
        // Jobs) the call, while a human who opens the same dialog in the GUI is not fought
        // by continuous MCP polling from read-only tools that never touch a connection.
        // markActivityEnd() runs in the finally below, guarded by the SAME flag, so the
        // start/end pair always stays balanced (end called iff start was called).
        boolean connectsToInfobase = tool.connectsToInfobase();
        if (connectsToInfobase)
        {
            InfobaseAuthDialogSuppressor.markActivityStart();
        }
        try
        {
            result = tool.execute(params);
            threw = false;
            return result;
        }
        finally
        {
            if (connectsToInfobase)
            {
                InfobaseAuthDialogSuppressor.markActivityEnd();
            }
            // Clear current tool name after execution
            if (server != null)
            {
                server.setCurrentToolName(null);
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            // A thrown exception (escaping execute, contract violation) counts as an
            // error outcome; otherwise reuse the same JSON-error detection the
            // response path uses so the logged outcome matches the wire isError.
            boolean isError = threw || isJsonErrorPayload(result);
            // On a tool-level failure the error payload is diverted into an isError
            // response and would otherwise leave no server-side trace of WHY it
            // failed. Log the extracted message once (WARNING) alongside the
            // outcome=error completion line so an operator can see the cause.
            if (isError)
            {
                Log.warning(formatErrorLogLine(tool.getName(), extractErrorMessage(result)));
            }
            logToolCallCompletion(tool.getName(), elapsedMs, isError);
        }
    }

    /**
     * Builds the tools/call response from the raw tool {@code result}, dispatching on
     * the tool's declared {@link IMcpTool.ResponseType}. Holds the per-type delivery
     * logic extracted verbatim from {@link #handleToolCall}: signal augmentation, the
     * plain-text fallback, the structuredContent capability gate (JSON), and the
     * error-diversion to a structured JSON error (non-JSON types). Returns the same
     * response in the same case as the original inline switch.
     *
     * @param tool the resolved tool
     * @param result the raw tool result payload (may be augmented per type)
     * @param signal a user signal raised during execution, or {@code null}
     * @param plainTextMode whether Cursor-compatible plain-text mode is enabled
     * @param requestId the JSON-RPC request id to echo
     * @param params the tool params (used only for the result file name)
     * @return the serialized JSON-RPC response
     */
    private String buildToolCallResponse(IMcpTool tool, String result, UserSignal signal,
        boolean plainTextMode, Object requestId, Map<String, String> params)
    {
        switch (tool.getResponseType())
        {
            case JSON:
                return buildJsonToolResponse(tool, result, signal, plainTextMode, requestId);
            case MARKDOWN:
                return buildMarkdownToolResponse(tool, result, signal, plainTextMode, requestId,
                    params);
            case YAML:
                return buildYamlToolResponse(tool, result, signal, plainTextMode, requestId, params);
            case IMAGE:
                return buildImageToolResponse(tool, result, requestId, params);
            case TEXT:
            default:
                return buildTextToolResponse(tool, result, signal, requestId);
        }
    }

    /**
     * Delivers a {@code ResponseType.MARKDOWN} tools/call result, holding the MARKDOWN-case
     * logic extracted verbatim from {@link #buildToolCallResponse}: a {@code ToolResult.error}
     * JSON payload is diverted to a structured JSON error (machine-detectable regardless of
     * the declared response type); otherwise a user signal is appended as markdown, plain-text
     * mode delivers the body as text, and the default is an embedded {@code text/markdown}
     * resource. Returns the same response in the same case as the original inline branch.
     */
    private String buildMarkdownToolResponse(IMcpTool tool, String result, UserSignal signal,
        boolean plainTextMode, Object requestId, Map<String, String> params)
    {
        // A ToolResult.error JSON payload is delivered as a structured JSON
        // error (isError:true) instead of a markdown resource, so failures
        // are machine-detectable regardless of the declared response type.
        if (isJsonErrorPayload(result))
        {
            return buildToolCallJsonResponse(result, requestId, tool.getName());
        }
        // Append user signal as markdown
        if (signal != null)
        {
            result = result + "\n\n---\n**USER SIGNAL:** " + signal.getMessage();
        }
        // A markdown tool may ALSO expose a machine-readable structuredContent (issue #302);
        // it rides alongside the human content and is null for tools that do not opt in.
        String structuredJson = tool.getStructuredContent(params);
        // In plain text mode, return markdown as plain text instead of embedded resource
        if (plainTextMode)
        {
            return buildToolCallTextResponse(result, requestId, structuredJson);
        }
        String fileName = tool.getResultFileName(params);
        return buildToolCallResourceResponse(result, MIME_TEXT_MARKDOWN, fileName, requestId, structuredJson);
    }

    /**
     * Delivers a {@code ResponseType.YAML} tools/call result, holding the YAML-case logic
     * extracted verbatim from {@link #buildToolCallResponse}: same delivery as
     * {@link #buildMarkdownToolResponse} (error diversion, signal append, plain-text
     * fallback) but the signal banner uses the YAML comment form and the embedded resource
     * advertises a {@code text/yaml} mimeType so it agrees with the {@code .yaml} resource
     * URI and body. Returns the same response in the same case as the original inline branch.
     */
    private String buildYamlToolResponse(IMcpTool tool, String result, UserSignal signal,
        boolean plainTextMode, Object requestId, Map<String, String> params)
    {
        // Same delivery as MARKDOWN (error diversion, signal append, plain
        // text fallback) but the embedded resource advertises a YAML
        // mimeType so it agrees with the .yaml resource URI and the body.
        if (isJsonErrorPayload(result))
        {
            return buildToolCallJsonResponse(result, requestId, tool.getName());
        }
        if (signal != null)
        {
            result = result + "\n\n---\n# USER SIGNAL: " + signal.getMessage();
        }
        if (plainTextMode)
        {
            return buildToolCallTextResponse(result, requestId);
        }
        String yamlFileName = tool.getResultFileName(params);
        return buildToolCallResourceResponse(result, "text/yaml", yamlFileName, requestId); //$NON-NLS-1$
    }

    /**
     * Delivers a {@code ResponseType.IMAGE} tools/call result, holding the IMAGE-case logic
     * extracted verbatim from {@link #buildToolCallResponse}: an error payload is diverted to
     * a structured JSON error; otherwise the (base64) image is returned as an embedded
     * {@code image/png} resource blob — plain-text mode and user signals are ignored for
     * images. Returns the same response in the same case as the original inline branch.
     */
    private String buildImageToolResponse(IMcpTool tool, String result, Object requestId,
        Map<String, String> params)
    {
        // Images always returned as embedded resource (ignore plain text mode)
        // For images, user signals are ignored
        if (isJsonErrorPayload(result))
        {
            return buildToolCallJsonResponse(result, requestId, tool.getName());
        }
        String imageFileName = tool.getResultFileName(params);
        return buildToolCallResourceBlobResponse(result, "image/png", imageFileName, requestId); //$NON-NLS-1$
    }

    /**
     * Delivers a {@code ResponseType.TEXT} (and default) tools/call result, holding the
     * TEXT-case logic extracted verbatim from {@link #buildToolCallResponse}: an error payload
     * is diverted to a structured JSON error; otherwise a user signal is appended as plain
     * text and the body is delivered as a text result. Returns the same response in the same
     * case as the original inline branch.
     */
    private String buildTextToolResponse(IMcpTool tool, String result, UserSignal signal,
        Object requestId)
    {
        // See MARKDOWN: a ToolResult.error JSON payload is delivered as a
        // structured JSON error regardless of the declared response type.
        if (isJsonErrorPayload(result))
        {
            return buildToolCallJsonResponse(result, requestId, tool.getName());
        }
        // Append user signal as text
        if (signal != null)
        {
            result = result + "\n\n---\nUSER SIGNAL: " + signal.getMessage();
        }
        return buildToolCallTextResponse(result, requestId);
    }

    /**
     * Builds the {@code ResponseType.JSON} tools/call response, holding the JSON-case
     * logic extracted verbatim from {@link #handleToolCall}: a user signal is merged in
     * as a {@code userSignal} field; plain-text mode delivers the payload as text; the
     * structuredContent capability gate suppresses structured content only when the
     * client explicitly opted out (default keeps it). Returns the same response in the
     * same case as the original inline {@code JSON} branch.
     *
     * @param tool the resolved tool (its name is used for the JSON response)
     * @param result the raw JSON tool result payload
     * @param signal a user signal raised during execution, or {@code null}
     * @param plainTextMode whether Cursor-compatible plain-text mode is enabled
     * @param requestId the JSON-RPC request id to echo
     * @return the serialized JSON-RPC response
     */
    private String buildJsonToolResponse(IMcpTool tool, String result, UserSignal signal,
        boolean plainTextMode, Object requestId)
    {
        // For JSON, add signal as a separate field if present
        if (signal != null)
        {
            // Parse JSON and add userSignal field
            result = addUserSignalToJson(result, signal);
        }
        // In plain text mode, return markdown as plain text instead of structured content
        if (plainTextMode)
        {
            return buildToolCallTextResponse(result, requestId);
        }
        // Capability gate for structuredContent. By DEFAULT (no capabilities,
        // or a client that does not explicitly opt out) this is true, so the
        // structuredContent response below is emitted exactly as before — the
        // no-regression guarantee. Only a client that EXPLICITLY declared it
        // cannot accept structuredContent suppresses it; the JSON payload is
        // then delivered as text so the data is still returned.
        if (!clientCapabilities.get().allowsStructuredContent())
        {
            return buildToolCallTextResponse(result, requestId);
        }
        return buildToolCallJsonResponse(result, requestId, tool.getName());
    }
    
    /**
     * Emits the single per-call completion log line. Routed to WARNING when the
     * outcome is an error or the call was slow (so an operator sees it without
     * enabling debug), INFO otherwise. The line content is produced by the pure
     * {@link #formatCompletionLine(String, long, boolean)} so it can be unit-tested.
     *
     * @param toolName the tool name
     * @param elapsedMs wall-clock duration in milliseconds
     * @param isError whether the call ended in an error outcome
     */
    private void logToolCallCompletion(String toolName, long elapsedMs, boolean isError)
    {
        String line = formatCompletionLine(toolName, elapsedMs, isError);
        if (isWarnWorthy(elapsedMs, isError))
        {
            Log.warning(line);
        }
        else
        {
            Log.info(line);
        }
    }

    /**
     * Pure classifier: a completion is logged at WARNING when it failed or when it
     * exceeded {@link #SLOW_TOOL_CALL_MS}. Exposed (package-private) for testing.
     *
     * @param elapsedMs wall-clock duration in milliseconds
     * @param isError whether the call ended in an error outcome
     * @return {@code true} if the completion should be logged at WARNING
     */
    static boolean isWarnWorthy(long elapsedMs, boolean isError)
    {
        return isError || elapsedMs >= SLOW_TOOL_CALL_MS;
    }

    /**
     * Pure formatter for the per-call completion log line. Kept side-effect-free so
     * the content (tool name, duration, outcome) is unit-testable without a live log.
     *
     * @param toolName the tool name (may be {@code null})
     * @param elapsedMs wall-clock duration in milliseconds
     * @param isError whether the call ended in an error outcome
     * @return the formatted completion line
     */
    static String formatCompletionLine(String toolName, long elapsedMs, boolean isError)
    {
        return "Completed tools/call: " + toolName //$NON-NLS-1$
            + " in " + elapsedMs + "ms" //$NON-NLS-1$ //$NON-NLS-2$
            + ", outcome=" + (isError ? "error" : "ok"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Pure formatter for the per-call error log line emitted (once, at WARNING) when
     * a tools/call ends in an error outcome. Carries the tool name plus the error
     * message extracted from the diverted payload so an operator can see WHY a call
     * failed (the completion line only records {@code outcome=error}). Kept
     * side-effect-free so its content is unit-testable without a live log.
     *
     * @param toolName the tool name (may be {@code null})
     * @param errorMessage the extracted error message (may be {@code null} or empty)
     * @return the formatted error line
     */
    static String formatErrorLogLine(String toolName, String errorMessage)
    {
        String detail = (errorMessage == null || errorMessage.isEmpty())
            ? "(no message)" //$NON-NLS-1$
            : errorMessage;
        return "Failed tools/call: " + toolName + " - " + detail; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Pure extractor of a human-readable error message from a tool result payload.
     * Reads the {@code error} field of a {@code ToolResult.error} JSON payload
     * (string error, or an object/array error rendered back to its JSON text);
     * returns {@code null} when the payload is not a JSON object, has no usable
     * {@code error} field, or cannot be parsed (e.g. a thrown exception left no
     * payload). Never throws. Does not handle secrets: tool error messages must not
     * embed the auth token, which is enforced at the source, not here.
     *
     * @param result the tool result string (may be {@code null})
     * @return the extracted error message, or {@code null} if none is available
     */
    static String extractErrorMessage(String result)
    {
        if (result == null || result.isEmpty())
        {
            return null;
        }

        try
        {
            JsonElement element = JsonParser.parseString(result);
            if (!element.isJsonObject())
            {
                return null;
            }

            com.google.gson.JsonObject obj = element.getAsJsonObject();
            if (!obj.has(McpKeys.ERROR))
            {
                return null;
            }

            JsonElement error = obj.get(McpKeys.ERROR);
            if (error.isJsonNull())
            {
                return null;
            }
            if (error.isJsonPrimitive())
            {
                return error.getAsString();
            }
            // An object/array error: keep the structured detail as its JSON text.
            return error.toString();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Adds user signal to a JSON result string using Gson for proper JSON handling.
     */
    private String addUserSignalToJson(String jsonResult, UserSignal signal)
    {
        try
        {
            // Parse the original JSON
            JsonElement element = JsonParser.parseString(jsonResult);
            if (element.isJsonObject())
            {
                com.google.gson.JsonObject jsonObject = element.getAsJsonObject();
                
                // Create userSignal object
                com.google.gson.JsonObject signalObject = new com.google.gson.JsonObject();
                signalObject.addProperty("type", signal.getType().name());
                signalObject.addProperty("message", signal.getMessage());
                
                // Add to result
                jsonObject.add("userSignal", signalObject);
                
                return new com.google.gson.Gson().toJson(jsonObject);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to add user signal to JSON", e);
        }
        return jsonResult;
    }
    
    /**
     * Extracts tool parameters from request.
     */
    private Map<String, String> extractToolParams(JsonRpcRequest request)
    {
        Map<String, String> params = new HashMap<>();
        
        Map<String, Object> arguments = request != null ? request.getArguments() : null;
        if (arguments == null)
        {
            return params;
        }
        
        // Convert all arguments to strings
        for (Map.Entry<String, Object> entry : arguments.entrySet())
        {
            Object value = entry.getValue();
            if (value != null)
            {
                if (value instanceof List || value instanceof Map)
                {
                    // Serialize complex types back to JSON
                    params.put(entry.getKey(), GsonProvider.toJson(value));
                }
                else
                {
                    params.put(entry.getKey(), value.toString());
                }
            }
        }
        
        return params;
    }
    
    /**
     * Builds initialize response.
     * Echoes back the client's requested protocol version (per spec) only when it
     * is one this server supports ({@link McpConstants#SUPPORTED_VERSIONS}); for an
     * unsupported (e.g. future) or missing version, responds with our latest
     * supported version ({@link McpConstants#PROTOCOL_VERSION}) so the client can
     * decide whether it can proceed.
     */
    private String buildInitializeResponse(Object requestId, String clientVersion)
    {
        // Echo the client's version only if we actually support it; otherwise
        // negotiate down to our latest supported version.
        String version = McpConstants.isSupportedVersion(clientVersion)
            ? clientVersion : McpConstants.PROTOCOL_VERSION;
        InitializeResult result = new InitializeResult(
            version,
            McpConstants.SERVER_NAME,
            McpConstants.PLUGIN_VERSION,
            McpConstants.AUTHOR
        );
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, result));
    }

    /**
     * Reads the optional {@code capabilities} object from an initialize request's
     * params and wraps it in a {@link ClientCapabilities} holder. Gson deserializes
     * the nested object into a {@code Map}, so it is converted back to a Gson tree
     * for a uniform inspection API. Never throws: a missing, {@code null}, or
     * malformed (non-object) capabilities value yields {@link ClientCapabilities#ABSENT}
     * so the permissive default behaviour is preserved.
     *
     * @param request the parsed initialize request (may be {@code null})
     * @return the parsed capabilities, never {@code null}
     */
    private ClientCapabilities parseClientCapabilities(JsonRpcRequest request)
    {
        Map<String, Object> params = request != null ? request.getParams() : null;
        if (params == null)
        {
            return ClientCapabilities.ABSENT;
        }
        Object capabilities = params.get("capabilities"); //$NON-NLS-1$
        if (capabilities == null)
        {
            return ClientCapabilities.ABSENT;
        }
        try
        {
            JsonElement tree = GsonProvider.get().toJsonTree(capabilities);
            return ClientCapabilities.from(tree);
        }
        catch (RuntimeException e)
        {
            // A malformed capabilities value must not fail initialize; fall back to
            // the permissive default and record why for an operator.
            Activator.logError("Failed to parse client capabilities; using defaults", e); //$NON-NLS-1$
            return ClientCapabilities.ABSENT;
        }
    }

    /**
     * Builds tools/list response dynamically from registry.
     */
    private String buildToolsListResponse(Object requestId)
    {
        ToolsListResult result = new ToolsListResult();

        for (IMcpTool tool : toolRegistry.getVisibleTools())
        {
            // Parse inputSchema from JSON string to JsonElement
            JsonElement schema = JsonParser.parseString(tool.getInputSchema());
            // A tool may supply explicit annotations; otherwise the central
            // classifier derives the MCP behavioral hints from the tool name.
            Object annotations = tool.getAnnotations() != null
                ? tool.getAnnotations()
                : ToolAnnotationClassifier.classify(tool.getName());
            // JSON tools declare the shape of their structuredContent; other tools
            // return content (not structured data) and leave outputSchema null, in
            // which case the shared Gson omits the field entirely.
            String outputSchemaJson = tool.getOutputSchema();
            JsonElement outputSchema = outputSchemaJson != null
                ? JsonParser.parseString(outputSchemaJson)
                : null;
            result.addTool(tool.getName(), tool.getDescription(), schema, annotations, outputSchema);
        }
        
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, result));
    }

    /**
     * Builds the {@code resources/list} response: one MCP resource per enabled tool,
     * each addressing that tool's how-to as {@code guide://<toolName>} with a
     * {@code text/markdown} mimeType. The list mirrors the lean {@code tools/list}
     * surface so a client can pull the full per-tool depth on demand without it
     * being always-loaded. The resource bodies are served by
     * {@link #handleResourcesRead}.
     *
     * @param requestId the JSON-RPC request id to echo
     * @return the serialized JSON-RPC response
     */
    private String buildResourcesListResponse(Object requestId)
    {
        JsonArray resources = new JsonArray();
        for (IMcpTool tool : toolRegistry.getVisibleTools())
        {
            String name = tool.getName();
            JsonObject resource = new JsonObject();
            resource.addProperty("uri", McpConstants.GUIDE_URI_SCHEME + name); //$NON-NLS-1$
            resource.addProperty("name", name + " guide"); //$NON-NLS-1$ //$NON-NLS-2$
            resource.addProperty("description", "Full how-to for " + name); //$NON-NLS-1$ //$NON-NLS-2$
            resource.addProperty("mimeType", MIME_TEXT_MARKDOWN); //$NON-NLS-1$
            resources.add(resource);
        }

        JsonObject result = new JsonObject();
        result.add("resources", resources); //$NON-NLS-1$
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, result));
    }

    /**
     * Handles a {@code resources/read} request for a {@code guide://<toolName>} URI:
     * resolves the tool from the registry and returns its rendered Markdown how-to
     * as a single {@code text/markdown} resource content entry. A URI that is not a
     * {@code guide://} scheme, that names no tool, or that names an unknown tool is
     * answered with a JSON-RPC error (mirroring how an unknown method/tool is
     * reported), not a partial result.
     *
     * @param request the parsed request (its {@code params.uri} is read)
     * @param requestId the JSON-RPC request id to echo
     * @return the serialized JSON-RPC response (result or error)
     */
    private String handleResourcesRead(JsonRpcRequest request, Object requestId)
    {
        String uri = request != null ? request.getStringParam("uri") : null; //$NON-NLS-1$
        if (uri == null || !uri.startsWith(McpConstants.GUIDE_URI_SCHEME))
        {
            return buildErrorResponse(McpConstants.ERROR_INVALID_PARAMS,
                "Unsupported resource uri: " + uri //$NON-NLS-1$
                    + ". Expected a guide://<toolName> uri (see resources/list).", requestId); //$NON-NLS-1$
        }

        String toolName = uri.substring(McpConstants.GUIDE_URI_SCHEME.length());
        IMcpTool tool = toolRegistry.getTool(toolName);
        if (tool == null)
        {
            return buildErrorResponse(McpConstants.ERROR_INVALID_PARAMS,
                "Unknown guide resource: " + uri //$NON-NLS-1$
                    + ". Call resources/list (or tools/list) for valid names.", requestId); //$NON-NLS-1$
        }

        JsonObject content = new JsonObject();
        content.addProperty("uri", uri); //$NON-NLS-1$
        content.addProperty("mimeType", MIME_TEXT_MARKDOWN); //$NON-NLS-1$
        content.addProperty("text", GuideRenderer.render(tool)); //$NON-NLS-1$

        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject result = new JsonObject();
        result.add("contents", contents); //$NON-NLS-1$
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, result));
    }

    /**
     * Builds tool call response for text result.
     * <p>
     * This and {@link #buildToolCallResourceResponse} are the single central point
     * where a tool's human-readable CONTENT TEXT is finalized into a tools/call
     * result, so the absolute output-size guard is applied here (and only here).
     * The guard is a pure no-op below its budget, so sub-cap output is byte-for-byte
     * identical to before. It is deliberately NOT applied on the JSON
     * structuredContent path ({@link #buildToolCallJsonResponse}), where the textual
     * content is only a bounded digest and the full data must round-trip intact in
     * structuredContent; nor to the JSON-RPC envelope itself (capping that would
     * corrupt the wire frame).
     */
    private String buildToolCallTextResponse(String result, Object requestId)
    {
        return buildToolCallTextResponse(result, requestId, null);
    }

    /**
     * As {@link #buildToolCallTextResponse(String, Object)}, but also attaches an optional
     * machine-readable {@code structuredContent} JSON alongside the text (issue #302). A
     * {@code null}/blank {@code structuredJson} yields the exact same response as the 2-arg form.
     */
    private String buildToolCallTextResponse(String result, Object requestId, String structuredJson)
    {
        ToolCallResult toolResult = ToolCallResult.text(OutputSizeGuard.cap(result));
        attachStructuredContent(toolResult, structuredJson);
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, toolResult));
    }

    /**
     * Parses {@code structuredJson} and attaches it to {@code toolResult} as its structured
     * content. A {@code null}/blank value is a no-op, so a tool that does not opt in keeps a
     * pure content result. Kept defensive at the tool layer (a tool's
     * {@code getStructuredContent} returns {@code null} rather than throwing), so this only ever
     * sees valid JSON or nothing.
     */
    private static void attachStructuredContent(ToolCallResult toolResult, String structuredJson)
    {
        if (structuredJson != null && !structuredJson.isEmpty())
        {
            toolResult.withStructuredContent(JsonParser.parseString(structuredJson));
        }
    }
    
    /**
     * Builds tool call response for JSON result.
     * Uses structuredContent per MCP 2025-11-25. A {@code ToolResult.error} JSON
     * payload (success:false / error field) is flagged with {@code isError:true}
     * so MCP clients can detect a tool-level failure instead of treating every
     * tools/call as successful.
     * <p>
     * On a tool-level failure the human-readable error TEXT channel
     * ({@code content[0].text}) is augmented with a one-line pointer to
     * {@code get_tool_guide("<toolName>")} so a caller that hit a bad/missing
     * parameter is told exactly where to find the full parameter list and examples.
     * This touches ONLY the text shown to the user — {@code structuredContent} and
     * the {@code isError} flag (the machine-readable error semantics) are unchanged.
     *
     * @param jsonResult the tool's JSON payload
     * @param requestId the JSON-RPC request id to echo
     * @param toolName the name of the tool that produced this result (for the hint)
     * @return the serialized JSON-RPC response
     */
    private String buildToolCallJsonResponse(String jsonResult, Object requestId, String toolName)
    {
        // Parse the JSON string to JsonElement for proper nesting
        JsonElement structured = JsonParser.parseString(jsonResult);
        boolean isError = isJsonErrorPayload(jsonResult);
        ToolCallResult toolResult = ToolCallResult.json(structured, isError);
        JsonRpcResponse response = JsonRpcResponse.success(requestId, toolResult);
        if (isError)
        {
            String hinted = appendGuideHintToErrorText(response, toolName);
            if (hinted != null)
            {
                return hinted;
            }
        }
        return GsonProvider.toJson(response);
    }

    /**
     * Appends the guide hint to the error TEXT channel of an already-built
     * tools/call response. Serializes the response, then (only when the failing tool
     * is NOT {@code get_tool_guide}, to avoid suggesting itself) appends
     * {@code  For full parameters and examples, call get_tool_guide("<toolName>").}
     * to {@code result.content[0].text}. The {@code structuredContent} and
     * {@code isError} fields are left untouched: only the human-readable text is
     * changed. Returns {@code null} when no hint applies or the text channel cannot
     * be located, so the caller serializes the unmodified response instead.
     *
     * @param response the built JSON-RPC success envelope carrying the tool result
     * @param toolName the failing tool's name (may be {@code null})
     * @return the re-serialized response with the hint appended, or {@code null}
     *         when no change was made
     */
    private String appendGuideHintToErrorText(JsonRpcResponse response, String toolName)
    {
        // Never suggest get_tool_guide for a get_tool_guide failure (circular).
        if (toolName == null || McpConstants.TOOL_GET_TOOL_GUIDE.equals(toolName))
        {
            return null;
        }
        try
        {
            JsonElement tree = GsonProvider.get().toJsonTree(response);
            if (!tree.isJsonObject())
            {
                return null;
            }
            JsonElement resultEl = tree.getAsJsonObject().get("result"); //$NON-NLS-1$
            if (resultEl == null || !resultEl.isJsonObject())
            {
                return null;
            }
            JsonElement contentEl = resultEl.getAsJsonObject().get("content"); //$NON-NLS-1$
            if (contentEl == null || !contentEl.isJsonArray() || contentEl.getAsJsonArray().size() == 0)
            {
                return null;
            }
            JsonElement first = contentEl.getAsJsonArray().get(0);
            if (!first.isJsonObject())
            {
                return null;
            }
            JsonObject firstObj = first.getAsJsonObject();
            String text = firstObj.has("text") && firstObj.get("text").isJsonPrimitive() //$NON-NLS-1$ //$NON-NLS-2$
                ? firstObj.get("text").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$
            firstObj.addProperty("text", text + guideHint(toolName)); //$NON-NLS-1$
            return GsonProvider.toJson(tree);
        }
        catch (RuntimeException e)
        {
            // A hint is best-effort: never let it break the error response.
            return null;
        }
    }

    /**
     * The one-line pointer appended to a failing tool's error text, directing the
     * caller to the full parameter list and examples via {@code get_tool_guide}.
     *
     * @param toolName the failing tool's name
     * @return the hint text (leading space so it reads as a continuation)
     */
    static String guideHint(String toolName)
    {
        return " For full parameters and examples, call get_tool_guide(\"" //$NON-NLS-1$
            + toolName + "\")."; //$NON-NLS-1$
    }
    
    /**
     * Builds tool call response for resource with MIME type (e.g., Markdown).
     * <p>
     * The embedded resource body is the human-readable content text for a
     * MARKDOWN/YAML tool, so the absolute output-size guard is applied here too
     * (see {@link #buildToolCallTextResponse} for the contract). A no-op below the
     * budget keeps the resource body byte-for-byte identical.
     */
    private String buildToolCallResourceResponse(String content, String mimeType, String fileName, Object requestId)
    {
        return buildToolCallResourceResponse(content, mimeType, fileName, requestId, null);
    }

    /**
     * As {@link #buildToolCallResourceResponse(String, String, String, Object)}, but also
     * attaches an optional machine-readable {@code structuredContent} JSON alongside the embedded
     * resource (issue #302). A {@code null}/blank {@code structuredJson} yields the exact same
     * response as the 4-arg form (the resource body stays byte-for-byte identical).
     */
    private String buildToolCallResourceResponse(String content, String mimeType, String fileName,
        Object requestId, String structuredJson)
    {
        ToolCallResult toolResult =
            ToolCallResult.resource("embedded://" + fileName, mimeType, OutputSizeGuard.cap(content)); //$NON-NLS-1$
        attachStructuredContent(toolResult, structuredJson);
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, toolResult));
    }
    
    /**
     * Builds tool call response for resource with blob data (e.g., images).
     */
    private String buildToolCallResourceBlobResponse(String base64Blob, String mimeType, String fileName, Object requestId)
    {
        ToolCallResult toolResult = ToolCallResult.resourceBlob("embedded://" + fileName, mimeType, base64Blob); //$NON-NLS-1$
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, toolResult));
    }

    /**
     * Checks whether tool result is a JSON error payload (ToolResult.error JSON).
     */
    private boolean isJsonErrorPayload(String result)
    {
        if (result == null)
        {
            return false;
        }

        try
        {
            JsonElement element = JsonParser.parseString(result);
            if (!element.isJsonObject())
            {
                return false;
            }

            // An error is detected ONLY by an explicit success==false boolean (the
            // canonical ToolResult.error payload is {"success":false,"error":...}).
            // Deliberately NOT flagged on mere "error" key presence: a SUCCESSFUL
            // JSON result that happens to carry an "error" field (e.g. a diagnostics
            // list) must stay isError:false, otherwise every future field name would
            // be latently coupled to the error-detection contract.
            com.google.gson.JsonObject obj = element.getAsJsonObject();
            return obj.has(KEY_SUCCESS) && obj.get(KEY_SUCCESS).isJsonPrimitive()
                && obj.get(KEY_SUCCESS).getAsJsonPrimitive().isBoolean()
                && !obj.get(KEY_SUCCESS).getAsBoolean();
        }
        catch (Exception e)
        {
            return false;
        }
    }
    
    /**
     * Builds error response.
     */
    private String buildErrorResponse(int code, String message, Object requestId)
    {
        if (requestId == null)
        {
            // JSON-RPC 2.0: when the request id cannot be determined (parse error /
            // invalid request) the error response MUST carry id:null. The shared
            // Gson omits null fields, so build this envelope explicitly with a
            // serialize-nulls writer. Only this path is affected; every id-bearing
            // response keeps the normal (null-omitting) shape.
            com.google.gson.JsonObject envelope = new com.google.gson.JsonObject();
            envelope.addProperty("jsonrpc", McpConstants.JSONRPC_VERSION); //$NON-NLS-1$
            envelope.add("id", com.google.gson.JsonNull.INSTANCE); //$NON-NLS-1$
            com.google.gson.JsonObject err = new com.google.gson.JsonObject();
            err.addProperty("code", code); //$NON-NLS-1$
            if (message != null)
            {
                err.addProperty("message", message); //$NON-NLS-1$
            }
            envelope.add(McpKeys.ERROR, err);
            return GsonProvider.toJsonSerializeNulls(envelope);
        }
        return GsonProvider.toJson(JsonRpcResponse.error(requestId, code, message));
    }
}
