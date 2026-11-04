/* Copyright (C) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class McpProtocolDialogNoticeTest
{
    private static final String NOTICE = "EDT dialog open: \"Application update\". Call edt_dialog with action=list.";
    private static final String ITEM = "{\"type\":\"text\",\"text\":\"EDT dialog open: \\\"Application update\\\". Call edt_dialog with action=list.\"}";

    @After
    public void clear()
    {
        McpToolRegistry.getInstance().clear();
    }

    private static String call(String name, String result, String notice, boolean throwsError)
    {
        McpToolRegistry.getInstance().register(new IMcpTool()
        {
            public String getName() { return name; }
            public String getDescription() { return "Notice test"; }
            public String getInputSchema() { return "{}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String getOutputSchema() { return "{\"type\":\"object\"}"; }
            public String execute(Map<String, String> params)
            {
                if (throwsError) { throw new IllegalStateException("tool exploded"); }
                return result;
            }
        });
        return new McpProtocolHandler()
        {
            @Override String dialogNotice() { return notice; }
        }.processRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}");
    }

    @Test
    public void successWireFormIsPinnedAndStructuredPayloadStaysPure()
    {
        String success = call("notice_probe", "{\"success\":true}", NOTICE, false);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"OK - keys: success\"},"
            + ITEM + "],\"structuredContent\":{\"success\":true}}}", success);
    }

    @Test
    public void errorWireFormIsPinnedAndStructuredPayloadStaysPure()
    {
        String error = call("notice_probe", "{\"success\":false,\"error\":\"bad input\"}", NOTICE, false);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\","
            + "\"text\":\"bad input For full parameters and examples, call get_tool_guide(\\\"notice_probe\\\").\"},"
            + ITEM + "],\"structuredContent\":{\"success\":false,\"error\":\"bad input\"},\"isError\":true}}", error);
    }

    @Test
    public void noDialogLeavesExactWireUnchanged()
    {
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"OK - keys: success\"}],"
            + "\"structuredContent\":{\"success\":true}}}", call("notice_probe", "{\"success\":true}", null, false));
        String response = " arbitrary existing bytes ";
        assertSame(response, McpProtocolHandler.appendDialogNotice(response, null));
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\","
            + "\"text\":\"bad input For full parameters and examples, call get_tool_guide(\\\"notice_probe\\\").\"}],"
            + "\"structuredContent\":{\"success\":false,\"error\":\"bad input\"},\"isError\":true}}",
            call("notice_probe", "{\"success\":false,\"error\":\"bad input\"}", null, false));
    }

    @Test
    public void thrownToolFailureAlsoCarriesNotice()
    {
        JsonObject error = JsonParser.parseString(call("notice_probe", "", NOTICE, true)).getAsJsonObject()
            .getAsJsonObject("error");
        assertEquals("tool exploded\n" + NOTICE, error.get("message").getAsString());
        assertEquals(-32603, error.get("code").getAsInt());
    }

    @Test
    public void serverStatusIsByteIdenticalWithDialog()
    {
        assertEquals(call("get_server_status", "{\"success\":true}", null, false),
            call("get_server_status", "{\"success\":true}", NOTICE, false));
    }

    @Test
    public void dialogListHasExactlyItsOwnContentWithNoticeSet()
    {
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\","
            + "\"text\":\"OK - dialogs: 1 - keys: success, dialogs\"}],\"structuredContent\":{\"success\":true,"
            + "\"dialogs\":[{\"title\":\"Application update\"}]}}}",
            call("edt_dialog", "{\"success\":true,\"dialogs\":[{\"title\":\"Application update\"}]}", NOTICE, false));
    }

    private static void unchangedWithOneWarning(String response)
    {
        List<IStatus> logs = new ArrayList<>();
        ILogListener listener = (status, plugin) -> {
            if (Activator.PLUGIN_ID.equals(status.getPlugin())) { logs.add(status); }
        };
        Platform.addLogListener(listener);
        try
        {
            assertSame(response, McpProtocolHandler.appendDialogNotice(response, NOTICE));
            assertEquals(logs.toString(), 1, logs.size());
            assertEquals(IStatus.WARNING, logs.get(0).getSeverity());
        }
        finally
        {
            Platform.removeLogListener(listener);
        }
    }

    @Test
    public void malformedEnvelopeIsUnchanged()
    {
        unchangedWithOneWarning(" {\"result\":{\"content\":{}}} ");
    }

    @Test
    public void missingResultAndErrorIsUnchanged()
    {
        unchangedWithOneWarning(" {\"jsonrpc\":\"2.0\",\"id\":1} ");
    }

    @Test
    public void missingContentIsUnchanged()
    {
        unchangedWithOneWarning(" {\"result\":{\"structuredContent\":{\"success\":true}}} ");
    }

    @Test
    public void nonObjectResponseIsUnchanged()
    {
        unchangedWithOneWarning(" [1,2] ");
    }

    @Test
    public void invalidJsonIsUnchanged()
    {
        unchangedWithOneWarning(" {broken ");
    }

    @Test
    public void missingExceptionMessageStillReportsDialog()
    {
        String response = McpProtocolHandler.appendDialogNotice(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603}}", NOTICE);
        assertEquals("Tool call failed\n" + NOTICE, JsonParser.parseString(response).getAsJsonObject()
            .getAsJsonObject("error").get("message").getAsString());
    }
}
