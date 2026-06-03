/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link McpProtocolHandler}.
 * Verifies JSON-RPC protocol handling for initialize, tools/list, and error cases.
 * <p>
 * Note: tools/call with successful execution cannot be fully tested without
 * OSGi runtime (Activator.getDefault() returns null), but error paths are testable.
 * </p>
 */
public class McpProtocolHandlerTest
{
    private McpProtocolHandler handler;
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        registry = McpToolRegistry.getInstance();
        registry.clear();
        handler = new McpProtocolHandler();
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    // === Initialize ===

    @Test
    public void testInitialize()
    {
        String request = buildJsonRpcRequest(1, "initialize", null);
        String response = handler.processRequest(request);

        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertEquals("2.0", json.get("jsonrpc").getAsString());
        assertNotNull(json.get("result"));

        JsonObject result = json.getAsJsonObject("result");
        assertNotNull("Should have protocolVersion", result.get("protocolVersion"));
        assertNotNull("Should have capabilities", result.get("capabilities"));
        assertNotNull("Should have serverInfo", result.get("serverInfo"));

        JsonObject serverInfo = result.getAsJsonObject("serverInfo");
        assertNotNull(serverInfo.get("name"));
        assertNotNull(serverInfo.get("version"));
    }

    @Test
    public void testInitializeEchosClientProtocolVersion()
    {
        // Per MCP spec: server must echo back client's requested protocol version
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"lmstudio\",\"version\":\"1.0.0\"}}}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        String echoed = json.getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals("Server must echo back client's protocol version", "2025-06-18", echoed);
    }

    @Test
    public void testInitializeUsesOwnVersionWhenClientVersionMissing()
    {
        // When no protocolVersion in params, fall back to server's latest
        String request = buildJsonRpcRequest(1, "initialize", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        String version = json.getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals(McpConstants.PROTOCOL_VERSION, version);
    }

    @Test
    public void testInitializeEchosSupportedOlderVersion()
    {
        // An older but still-supported version (per SUPPORTED_VERSIONS) is echoed back.
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"client\",\"version\":\"1.0.0\"}}}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        String echoed = json.getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals("A supported version must be echoed back", "2024-11-05", echoed);
        assertTrue("2024-11-05 must be in SUPPORTED_VERSIONS",
            McpConstants.SUPPORTED_VERSIONS.contains("2024-11-05"));
    }

    @Test
    public void testInitializeNegotiatesUnsupportedFutureVersionToLatest()
    {
        // A well-formed but unsupported (future) version must NOT be echoed; the
        // server negotiates down to its latest supported version (PROTOCOL_VERSION).
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2030-01-01\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"client\",\"version\":\"1.0.0\"}}}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        String version = json.getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals("Unsupported future version must negotiate to latest supported",
            McpConstants.PROTOCOL_VERSION, version);
        assertFalse("2030-01-01 must not be a supported version",
            McpConstants.SUPPORTED_VERSIONS.contains("2030-01-01"));
    }

    @Test
    public void testInitializeNegotiatesUnknownNonDateVersionToLatest()
    {
        // A malformed / non-date version is also unsupported and negotiates to latest.
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"not-a-version\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"client\",\"version\":\"1.0.0\"}}}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        String version = json.getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals(McpConstants.PROTOCOL_VERSION, version);
    }

    @Test
    public void testSupportedVersionsContainsCurrentAsFirstEntry()
    {
        // The latest supported version is the current PROTOCOL_VERSION and is first.
        assertTrue("current PROTOCOL_VERSION must be supported",
            McpConstants.SUPPORTED_VERSIONS.contains(McpConstants.PROTOCOL_VERSION));
        String first = McpConstants.SUPPORTED_VERSIONS.iterator().next();
        assertEquals("latest supported version must be listed first",
            McpConstants.PROTOCOL_VERSION, first);
        assertTrue("the echo-test version 2025-06-18 must stay supported",
            McpConstants.SUPPORTED_VERSIONS.contains("2025-06-18"));
    }

    @Test
    public void testInitializePreservesRequestId()
    {
        String request = buildJsonRpcRequest(42, "initialize", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertEquals(42, json.get("id").getAsInt());
    }

    @Test
    public void testInitializeWithIdZeroPreservesIntegerType()
    {
        // LM Studio sends "id":0 - must not become "id":0.0 in response
        String request = buildJsonRpcRequest(0, "initialize", null);
        String response = handler.processRequest(request);

        assertNotNull(response);
        assertFalse("Response must not contain 0.0 as id", response.contains("\"id\":0.0"));
        JsonObject json = parseResponse(response);
        assertEquals(0, json.get("id").getAsInt());
    }

    @Test
    public void testInitializeStringId()
    {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"abc-123\",\"method\":\"initialize\"}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertEquals("abc-123", json.get("id").getAsString());
    }

    // === Initialized notification ===

    @Test
    public void testInitializedNotification()
    {
        String request = buildJsonRpcRequest(1, "notifications/initialized", null);
        String response = handler.processRequest(request);
        assertNull("notifications/initialized should return null (202 Accepted)", response);
    }

    // === Tools/List ===

    @Test
    public void testToolsListEmpty()
    {
        String request = buildJsonRpcRequest(1, "tools/list", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        JsonObject result = json.getAsJsonObject("result");
        assertNotNull(result.get("tools"));
        assertEquals(0, result.getAsJsonArray("tools").size());
    }

    @Test
    public void testToolsListWithTools()
    {
        registry.register(new StubTool("tool_alpha", "Alpha tool", "{\"type\":\"object\"}"));
        registry.register(new StubTool("tool_beta", "Beta tool",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"));

        String request = buildJsonRpcRequest(1, "tools/list", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        JsonObject result = json.getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("tools").size());

        // Verify tool entries have required fields
        for (JsonElement toolEl : result.getAsJsonArray("tools"))
        {
            JsonObject tool = toolEl.getAsJsonObject();
            assertNotNull("Tool should have name", tool.get("name"));
            assertNotNull("Tool should have description", tool.get("description"));
            assertNotNull("Tool should have inputSchema", tool.get("inputSchema"));
        }
    }

    // === Invalid Requests ===

    @Test
    public void testInvalidJsonRpcVersion()
    {
        String request = "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"initialize\"}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        assertEquals(McpConstants.ERROR_INVALID_REQUEST,
            json.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void testMissingJsonRpcVersion()
    {
        String request = "{\"id\":1,\"method\":\"initialize\"}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
    }

    @Test
    public void testMethodNotFound()
    {
        String request = buildJsonRpcRequest(1, "unknown/method", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        assertEquals(McpConstants.ERROR_METHOD_NOT_FOUND,
            json.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void testInvalidVersionDoesNotFabricateIdOne()
    {
        // Per JSON-RPC 2.0: a version-mismatch / invalid-request error whose id
        // cannot be determined must carry id:null (present, not a fabricated value
        // that could be mis-correlated to a pending request, and not omitted).
        String request = "{\"jsonrpc\":\"1.0\",\"method\":\"initialize\"}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        assertTrue("id field must be present per spec", json.has("id"));
        assertTrue("undeterminable id must serialize as JSON null", json.get("id").isJsonNull());
    }

    @Test
    public void testValidRequestIdStillEchoedOnError()
    {
        // A request that carries a real id but an unknown method must echo that id.
        String request = buildJsonRpcRequest(7, "unknown/method", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        assertEquals("Real request id must be echoed back", 7, json.get("id").getAsInt());
    }

    @Test
    public void testInvalidJson()
    {
        String response = handler.processRequest("not valid json {{{");
        // Should return an error response (either parse error or invalid request)
        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        // Parse error => id cannot be determined => id:null present per JSON-RPC 2.0
        assertTrue("parse-error response must carry id:null",
            json.has("id") && json.get("id").isJsonNull());
    }

    @Test
    public void testEmptyBody()
    {
        String response = handler.processRequest("");
        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
    }

    @Test
    public void testNullBody()
    {
        String response = handler.processRequest(null);
        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
    }

    // === Tools/Call Error Cases ===

    @Test
    public void testToolCallToolNotFound()
    {
        String request = buildToolCallRequest(1, "nonexistent_tool", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        String message = json.getAsJsonObject("error").get("message").getAsString();
        assertTrue("Error should mention tool name", message.contains("nonexistent_tool"));
    }

    @Test
    public void testToolCallNullToolName()
    {
        String request = buildJsonRpcRequest(1, "tools/call",
            "{\"arguments\":{}}");
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull("Should return error for null tool name", json.get("error"));
    }

    @Test
    public void testToolCallErrorPayloadFlagsIsError()
    {
        // A JSON tool returning a ToolResult.error payload (success:false) must be
        // surfaced with isError:true so clients can detect a tool-level failure.
        registry.register(new StubJsonTool("failing_tool",
            "{\"success\":false,\"error\":\"boom\"}"));

        String request = buildToolCallRequest(1, "failing_tool", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNull("tool-level failure is not a JSON-RPC error", json.get("error"));
        JsonObject result = json.getAsJsonObject("result");
        assertTrue("error payload must set isError:true",
            result.get("isError").getAsBoolean());
        assertFalse("structuredContent must carry the failure",
            result.getAsJsonObject("structuredContent").get("success").getAsBoolean());
    }

    @Test
    public void testToolCallSuccessPayloadOmitsIsError()
    {
        registry.register(new StubJsonTool("ok_tool", "{\"value\":7}"));

        String request = buildToolCallRequest(1, "ok_tool", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        JsonObject result = json.getAsJsonObject("result");
        assertFalse("success result must not carry isError", result.has("isError"));
        assertEquals(7, result.getAsJsonObject("structuredContent").get("value").getAsInt());

        // The content fallback must carry a bounded, non-empty digest derived
        // from the structuredContent (NOT the opaque "Done"), so a spec-compliant
        // client that reads content[0].text still sees something meaningful.
        String contentText = result.getAsJsonArray("content").get(0)
            .getAsJsonObject().get("text").getAsString();
        assertFalse("content fallback must not be empty", contentText.isEmpty());
        assertNotEquals("content fallback must not be the opaque \"Done\"", "Done", contentText);
        assertTrue("digest must be bounded", contentText.length() <= 500);
        assertTrue("digest should reference the structured key", contentText.contains("value"));
    }

    @Test
    public void testToolCallSuccessPayloadWithErrorFieldOmitsIsError()
    {
        // Regression for the isJsonErrorPayload tightening: a SUCCESSFUL JSON result
        // (success:true) that ALSO carries a populated "error" field must NOT be
        // flagged isError. Detection is by success==false only, never by the mere
        // presence of an "error" key, so future field names stay decoupled from the
        // error-detection contract.
        registry.register(new StubJsonTool("ok_with_error_field",
            "{\"success\":true,\"error\":\"this is data, not a failure\",\"value\":7}"));

        String request = buildToolCallRequest(1, "ok_with_error_field", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNull("tool-level success is not a JSON-RPC error", json.get("error"));
        JsonObject result = json.getAsJsonObject("result");
        assertFalse("a success payload carrying an 'error' field must not set isError",
            result.has("isError"));
        assertEquals("the structured content must round-trip intact", 7,
            result.getAsJsonObject("structuredContent").get("value").getAsInt());
    }

    @Test
    public void testToolResultErrorPayloadFlagsIsError()
    {
        // The actual ToolResult.error(...) output ({"success":false,"error":...})
        // must still be classified as an error: the tightened predicate keys on the
        // canonical success==false produced by the shared error builder.
        String errorPayload = ToolResult.error("module not found").toJson();
        registry.register(new StubJsonTool("toolresult_error", errorPayload));

        String request = buildToolCallRequest(1, "toolresult_error", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNull("tool-level failure is not a JSON-RPC error", json.get("error"));
        JsonObject result = json.getAsJsonObject("result");
        assertTrue("ToolResult.error payload must set isError:true",
            result.get("isError").getAsBoolean());
        assertFalse("structuredContent must carry the failure",
            result.getAsJsonObject("structuredContent").get("success").getAsBoolean());
    }

    @Test
    public void testMarkdownErrorPayloadDeliveredAsJsonError()
    {
        // A MARKDOWN tool that returns a ToolResult.error JSON payload is delivered
        // as a structured JSON error (isError:true), not wrapped as a markdown
        // resource — failures are machine-detectable regardless of response type.
        registry.register(new StubTypedTool("md_fail",
            "{\"success\":false,\"error\":\"boom\"}", IMcpTool.ResponseType.MARKDOWN));

        String response = handler.processRequest(buildToolCallRequest(1, "md_fail", null));
        JsonObject result = parseResponse(response).getAsJsonObject("result");
        assertTrue("markdown error payload must set isError:true",
            result.has("isError") && result.get("isError").getAsBoolean());
        assertNotNull("error must carry structuredContent, not a resource",
            result.get("structuredContent"));
    }

    @Test
    public void testMarkdownNormalDeliveredAsResource()
    {
        // Normal markdown output is unaffected: embedded resource, no isError.
        registry.register(new StubTypedTool("md_ok", "# Title", IMcpTool.ResponseType.MARKDOWN));

        String response = handler.processRequest(buildToolCallRequest(1, "md_ok", null));
        JsonObject result = parseResponse(response).getAsJsonObject("result");
        assertFalse("normal markdown must not carry isError", result.has("isError"));
        assertEquals("resource",
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    public void testYamlDeliveredAsResourceWithYamlMimeType()
    {
        // A YAML-responseType tool is delivered as an embedded resource whose
        // mimeType agrees with the YAML body (text/yaml), not text/markdown.
        registry.register(new StubTypedTool("yaml_ok", "key: value\n", IMcpTool.ResponseType.YAML)); //$NON-NLS-1$ //$NON-NLS-2$

        String response = handler.processRequest(buildToolCallRequest(1, "yaml_ok", null)); //$NON-NLS-1$
        JsonObject result = parseResponse(response).getAsJsonObject("result");
        assertFalse("normal yaml must not carry isError", result.has("isError")); //$NON-NLS-1$
        assertEquals("resource",
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
        assertTrue("yaml resource must advertise text/yaml", response.contains("text/yaml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("yaml resource must not be text/markdown", response.contains("text/markdown")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTextErrorPayloadDeliveredAsJsonError()
    {
        registry.register(new StubTypedTool("txt_fail",
            "{\"success\":false,\"error\":\"boom\"}", IMcpTool.ResponseType.TEXT));

        String response = handler.processRequest(buildToolCallRequest(1, "txt_fail", null));
        JsonObject result = parseResponse(response).getAsJsonObject("result");
        assertTrue("text error payload must set isError:true",
            result.has("isError") && result.get("isError").getAsBoolean());
    }

    // === Per-call completion log line (pure helpers) ===

    @Test
    public void testCompletionLineCarriesNameDurationAndOkOutcome()
    {
        String line = McpProtocolHandler.formatCompletionLine("list_projects", 12, false);
        assertTrue("must reference the tool name", line.contains("list_projects"));
        assertTrue("must carry the duration in ms", line.contains("12ms"));
        assertTrue("a non-error outcome must read ok", line.contains("outcome=ok"));
        assertFalse("a non-error outcome must not read error", line.contains("outcome=error"));
    }

    @Test
    public void testCompletionLineReflectsErrorOutcome()
    {
        String line = McpProtocolHandler.formatCompletionLine("write_module_source", 3, true);
        assertTrue("must reference the tool name", line.contains("write_module_source"));
        assertTrue("must carry the duration in ms", line.contains("3ms"));
        assertTrue("an error outcome must read error", line.contains("outcome=error"));
    }

    @Test
    public void testCompletionLineToleratesNullToolName()
    {
        // Defensive: a null tool name must not throw while formatting the line.
        String line = McpProtocolHandler.formatCompletionLine(null, 0, false);
        assertNotNull(line);
        assertTrue("zero duration is still rendered", line.contains("0ms"));
    }

    @Test
    public void testWarnWorthyOnError()
    {
        // An error outcome is warn-worthy even when the call was fast.
        assertTrue("a fast error must still warn", McpProtocolHandler.isWarnWorthy(1, true));
    }

    @Test
    public void testWarnWorthyOnSlowSuccess()
    {
        // A success at/over the slow threshold is warn-worthy.
        assertTrue("at the threshold must warn",
            McpProtocolHandler.isWarnWorthy(McpProtocolHandler.SLOW_TOOL_CALL_MS, false));
        assertTrue("over the threshold must warn",
            McpProtocolHandler.isWarnWorthy(McpProtocolHandler.SLOW_TOOL_CALL_MS + 1, false));
    }

    @Test
    public void testNotWarnWorthyOnFastSuccess()
    {
        // A fast success is logged at INFO, not WARNING.
        assertFalse("a fast success must not warn",
            McpProtocolHandler.isWarnWorthy(McpProtocolHandler.SLOW_TOOL_CALL_MS - 1, false));
    }

    // === Per-call error log line (pure helpers) ===

    @Test
    public void testErrorLogLineCarriesNameAndMessage()
    {
        String line = McpProtocolHandler.formatErrorLogLine("write_module_source", "module not found");
        assertTrue("must reference the tool name", line.contains("write_module_source"));
        assertTrue("must carry the error message", line.contains("module not found"));
    }

    @Test
    public void testErrorLogLineToleratesNullMessage()
    {
        // A thrown exception can leave no payload (null message); the line must still
        // render with the tool name and a placeholder instead of throwing.
        String line = McpProtocolHandler.formatErrorLogLine("list_projects", null);
        assertNotNull(line);
        assertTrue("must reference the tool name", line.contains("list_projects"));
        assertTrue("a missing message renders a placeholder", line.contains("(no message)"));
    }

    @Test
    public void testErrorLogLineToleratesEmptyMessage()
    {
        // An empty error string is treated like a missing message.
        String line = McpProtocolHandler.formatErrorLogLine("list_projects", "");
        assertNotNull(line);
        assertTrue("an empty message renders a placeholder", line.contains("(no message)"));
    }

    @Test
    public void testExtractErrorMessageFromErrorPayload()
    {
        // A ToolResult.error payload carries the human-readable cause in "error".
        String msg = McpProtocolHandler.extractErrorMessage(
            "{\"success\":false,\"error\":\"module not found\"}");
        assertEquals("module not found", msg);
    }

    @Test
    public void testExtractErrorMessageFromObjectError()
    {
        // A structured (object) error is preserved as its JSON text, not dropped.
        String msg = McpProtocolHandler.extractErrorMessage(
            "{\"success\":false,\"error\":{\"code\":42,\"detail\":\"boom\"}}");
        assertNotNull(msg);
        assertTrue("structured error must retain its detail", msg.contains("boom"));
        assertTrue("structured error must retain its code", msg.contains("42"));
    }

    @Test
    public void testExtractErrorMessageNullForSuccessPayload()
    {
        // A success payload has no error field => no message to extract.
        assertNull("a success payload yields no error message",
            McpProtocolHandler.extractErrorMessage("{\"value\":7}"));
    }

    @Test
    public void testExtractErrorMessageNullForNullAndEmpty()
    {
        // A thrown execute can leave no payload at all; tolerate null/empty.
        assertNull("null payload yields no message",
            McpProtocolHandler.extractErrorMessage(null));
        assertNull("empty payload yields no message",
            McpProtocolHandler.extractErrorMessage(""));
    }

    @Test
    public void testExtractErrorMessageNullForNonJson()
    {
        // A non-JSON / non-object result (e.g. markdown) is not an error payload.
        assertNull("markdown text yields no error message",
            McpProtocolHandler.extractErrorMessage("# Title"));
        assertNull("a JSON array is not an error object",
            McpProtocolHandler.extractErrorMessage("[1,2,3]"));
    }

    @Test
    public void testExtractErrorMessageNullForNullErrorValue()
    {
        // An explicit null error value yields no message.
        assertNull("a null error value yields no message",
            McpProtocolHandler.extractErrorMessage("{\"success\":false,\"error\":null}"));
    }

    // === Helpers ===

    private String buildJsonRpcRequest(Object id, String method, String paramsJson)
    {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\"");
        if (id instanceof String)
        {
            sb.append(",\"id\":\"").append(id).append("\"");
        }
        else
        {
            sb.append(",\"id\":").append(id);
        }
        sb.append(",\"method\":\"").append(method).append("\"");
        if (paramsJson != null)
        {
            sb.append(",\"params\":").append(paramsJson);
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildToolCallRequest(Object id, String toolName, String argsJson)
    {
        StringBuilder params = new StringBuilder("{\"name\":\"").append(toolName).append("\"");
        if (argsJson != null)
        {
            params.append(",\"arguments\":").append(argsJson);
        }
        else
        {
            params.append(",\"arguments\":{}");
        }
        params.append("}");
        return buildJsonRpcRequest(id, "tools/call", params.toString());
    }

    private JsonObject parseResponse(String response)
    {
        return JsonParser.parseString(response).getAsJsonObject();
    }

    /**
     * Minimal IMcpTool stub for testing.
     */
    private static class StubTool implements IMcpTool
    {
        private final String name;
        private final String description;
        private final String inputSchema;

        StubTool(String name, String description, String inputSchema)
        {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return description; }

        @Override
        public String getInputSchema() { return inputSchema; }

        @Override
        public String execute(Map<String, String> params) { return "{}"; }
    }

    /**
     * IMcpTool stub with a JSON response type returning a fixed payload, used to
     * exercise the isError flagging on the tools/call JSON path.
     */
    private static class StubJsonTool implements IMcpTool
    {
        private final String name;
        private final String payload;

        StubJsonTool(String name, String payload)
        {
            this.name = name;
            this.payload = payload;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return "stub json tool"; }

        @Override
        public String getInputSchema() { return "{\"type\":\"object\"}"; }

        @Override
        public ResponseType getResponseType() { return ResponseType.JSON; }

        @Override
        public String execute(Map<String, String> params) { return payload; }
    }

    /**
     * IMcpTool stub with a configurable response type returning a fixed payload,
     * used to exercise the isJsonErrorPayload diversion on the MARKDOWN/TEXT paths.
     */
    private static class StubTypedTool implements IMcpTool
    {
        private final String name;
        private final String payload;
        private final ResponseType responseType;

        StubTypedTool(String name, String payload, ResponseType responseType)
        {
            this.name = name;
            this.payload = payload;
            this.responseType = responseType;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return "stub typed tool"; }

        @Override
        public String getInputSchema() { return "{\"type\":\"object\"}"; }

        @Override
        public ResponseType getResponseType() { return responseType; }

        @Override
        public String execute(Map<String, String> params) { return payload; }
    }
}
