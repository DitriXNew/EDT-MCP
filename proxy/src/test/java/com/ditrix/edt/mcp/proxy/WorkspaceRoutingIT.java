/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.*;
import static org.junit.Assert.*;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.McpTestClient;
import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Exercises the public proxy endpoint with duplicate projects in distinct workspaces. */
public class WorkspaceRoutingIT
{
    @Rule
    public final Timeout timeout = Timeout.seconds(60);

    private static final String FIRST = "C:\\Work\\ERP-task-1";
    private static final String SECOND = "C:\\Work\\ERP-task-2";
    private int[] ports;
    private FakeBackend first;
    private FakeBackend second;
    private FakeBackend third;
    private java.util.concurrent.ScheduledExecutorService scheduler;
    private ProxyFixture proxy;
    private McpTestClient client;

    @Before
    public void setUp() throws Exception
    {
        ports = reserveFreePorts(3);
        first = new FakeBackend(ports[0], List.of("ERP", "Unique"));
        first.setWorkspacePath(FIRST);
        first.start();
        second = new FakeBackend(ports[1], List.of("ERP"));
        second.setWorkspacePath(SECOND);
        second.start();
        proxy = new ProxyFixture(ports[0], ports[2], 1);
        proxy.start();
        client = new McpTestClient(proxy.port());
        client.handshake();
    }

    @After
    public void tearDown()
    {
        if (scheduler != null)
        {
            scheduler.shutdownNow();
        }
        if (proxy != null)
        {
            proxy.stop();
        }
        stopQuietly(first);
        stopQuietly(second);
        stopQuietly(third);
    }

    @Test
    public void duplicateRequiresWorkspaceAndEachCopyIsAddressable() throws Exception
    {
        JsonObject ambiguous = client.callTool("echo_port", projectArgs("ERP"));
        assertTrue(isToolError(ambiguous));
        String message = errorText(ambiguous);
        assertTrue(message, message.contains("workspacePath"));
        assertTrue(message, message.contains(FIRST));
        assertTrue(message, message.contains(SECOND));
        assertPort(ports[0], client.callTool("echo_port", scoped("ERP", FIRST)));
        assertPort(ports[1], client.callTool("echo_port", scoped("ERP", SECOND)));
        assertFalse(second.lastToolArguments().has("workspacePath"));
        assertEquals("ERP", second.lastToolArguments().get("projectName").getAsString());
        assertPort(ports[0], client.callTool("echo_port", projectArgs("Unique")));
    }

    @Test
    public void exposesWorkspaceInStatusMachineListAndHumanList() throws Exception
    {
        JsonArray backends = structuredContent(client.callTool("router_status", new JsonObject()))
            .getAsJsonArray("backends");
        assertEquals(FIRST, backends.get(0).getAsJsonObject().get("workspacePath").getAsString());
        assertEquals(SECOND, backends.get(1).getAsJsonObject().get("workspacePath").getAsString());
        JsonObject args = new JsonObject();
        args.addProperty("format", "json");
        JsonObject list = structuredContent(client.callTool("list_projects", args));
        assertFalse("a merged list cannot claim the first backend's workspace", list.has("workspacePath"));
        JsonArray projects = list.getAsJsonArray("projects");
        assertEquals(3, projects.size());
        assertEquals(FIRST, projects.get(0).getAsJsonObject().get("workspacePath").getAsString());
        assertEquals(SECOND, projects.get(2).getAsJsonObject().get("workspacePath").getAsString());
        String human = client.callTool("list_projects", new JsonObject()).toString();
        assertTrue(human, human.contains("Workspace"));
        assertTrue(human, human.contains("ERP-task-1"));
        assertTrue(human, human.contains("ERP-task-2"));
    }

    @Test
    public void selectorIsDiscoverableAndInjectionIsIdempotent() throws Exception
    {
        JsonObject list = client.request("tools/list", new JsonObject());
        for (JsonElement item : list.getAsJsonObject("result").getAsJsonArray("tools"))
        {
            JsonObject tool = item.getAsJsonObject();
            JsonObject schema = tool.getAsJsonObject("inputSchema");
            if (tool.get("name").getAsString().startsWith("router_"))
            {
                assertFalse(schema.getAsJsonObject("properties").has("workspacePath"));
            }
            else
            {
                assertEquals("string", schema.getAsJsonObject("properties")
                    .getAsJsonObject("workspacePath").get("type").getAsString());
            }
        }
        String injected = RouterTools.injectIntoToolsList(list.toString());
        assertEquals(Json.parseObject(list.toString()), Json.parseObject(injected));
    }

    @Test
    public void selectorMustNeverFallBackToAnotherWorkspace() throws Exception
    {
        JsonObject missing = client.callTool("echo_port", scoped("Unique", SECOND));
        assertTrue(isToolError(missing));
        assertTrue(errorText(missing), errorText(missing).contains(SECOND));
        JsonObject typo = client.callTool("echo_port", scoped("ERP", FIRST + "-typo"));
        assertTrue(isToolError(typo));
        assertTrue(errorText(typo), errorText(typo).contains("router_refresh"));
        for (JsonElement invalid : List.of(Json.parseObject("{\"x\":4}").get("x"),
            new JsonObject(), com.google.gson.JsonNull.INSTANCE, new com.google.gson.JsonPrimitive(" ")))
        {
            JsonObject args = projectArgs("Unique");
            args.add("workspacePath", invalid);
            JsonObject failure = client.callTool("echo_port", args);
            assertTrue(failure.toString(), isToolError(failure));
            assertTrue(errorText(failure), errorText(failure).contains("workspacePath"));
        }
    }

    @Test
    public void workspaceCanScopeUnscopedCallsAndProjectLists() throws Exception
    {
        JsonObject args = new JsonObject();
        args.addProperty("workspacePath", SECOND);
        assertPort(ports[1], client.callTool("echo_port", args));
        args.addProperty("format", "json");
        JsonArray projects = structuredContent(client.callTool("list_projects", args)).getAsJsonArray("projects");
        assertEquals(1, projects.size());
        assertEquals("ERP", projects.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(SECOND, projects.get(0).getAsJsonObject().get("workspacePath").getAsString());
    }

    @Test
    public void restartInDifferentWorkspaceOnSamePortDoesNotReceiveSelectedCall() throws Exception
    {
        assertPort(ports[1], client.callTool("echo_port", scoped("ERP", SECOND)));
        second.stop();
        second = new FakeBackend(ports[1], List.of("ERP"));
        second.setWorkspacePath("C:\\Work\\Other");
        second.invalidateSessions();
        second.start();
        JsonObject failure = client.callTool("echo_port", scoped("ERP", SECOND));
        assertTrue(failure.toString(), isToolError(failure));
        assertTrue(errorText(failure), errorText(failure).contains("workspace"));
        assertFalse("only discovery may reach the replacement", second.lastToolArguments().has("projectName"));
    }

    @Test
    public void selectedWorkspaceRecoversOnNewPortWithSameClientSession() throws Exception
    {
        assertPort(ports[1], client.callTool("echo_port", scoped("ERP", SECOND)));
        second.stop();
        client.callTool("router_refresh", new JsonObject());
        assertTrue(isToolError(client.callTool("echo_port", scoped("ERP", SECOND))));
        first.stop();
        first = new FakeBackend(ports[0], List.of("ERP"));
        first.setWorkspacePath(SECOND);
        first.invalidateSessions();
        first.start();
        assertPort(ports[0], client.callTool("echo_port", scoped("ERP", SECOND)));
    }

    @Test
    public void workspaceChangeBetweenVerificationAndCallCannotReplayIntoAnotherCopy() throws Exception
    {
        second.afterNextListProjects(() -> {
            second.setWorkspacePath("C:\\Work\\Replacement");
            second.invalidateSessions();
        });
        JsonObject failure = client.callTool("echo_port", scoped("ERP", SECOND));
        assertTrue(failure.toString(), isToolError(failure));
        assertTrue(errorText(failure), errorText(failure).contains("router_refresh"));
        assertFalse(second.lastToolArguments().has("projectName"));
        assertEquals("the stale session must have required a new handshake", 2, second.getInitializeCount());
    }

    @Test
    public void duplicateWorkspaceIdentitiesAndLegacyBackendsFailClosed() throws Exception
    {
        second.setWorkspacePath(FIRST);
        client.callTool("router_refresh", new JsonObject());
        assertTrue(isToolError(client.callTool("echo_port", scoped("ERP", FIRST))));
        second.setWorkspacePath(null);
        client.callTool("router_refresh", new JsonObject());
        JsonObject ambiguous = client.callTool("echo_port", projectArgs("ERP"));
        assertTrue(isToolError(ambiguous));
        assertTrue(errorText(ambiguous), errorText(ambiguous).contains("update"));
        assertTrue(isToolError(client.callTool("echo_port", scoped("ERP", SECOND))));
        assertPort(ports[0], client.callTool("echo_port", scoped("ERP", FIRST)));
    }

    @Test
    public void periodicRefreshDiscoversThirdWorkspaceWithoutClientReconnect() throws Exception
    {
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        proxy.startPeriodicRefresh(scheduler);
        third = new FakeBackend(ports[2], List.of("Third"));
        third.setWorkspacePath("/work/third");
        third.start();
        awaitBackendCount(3);
        assertPort(ports[2], client.callTool("echo_port", scoped("Third", "/work/third")));
        third.stop();
        awaitBackendCount(2);
        third.start();
        third.invalidateSessions();
        awaitBackendCount(3);
        assertPort(ports[2], client.callTool("echo_port", scoped("Third", "/work/third")));
    }

    private void awaitBackendCount(int count) throws Exception
    {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        do
        {
            JsonArray backends = structuredContent(client.callTool("router_status", new JsonObject()))
                .getAsJsonArray("backends");
            if (backends.size() == count)
            {
                return;
            }
            Thread.sleep(50);
        }
        while (System.nanoTime() < deadline);
        fail("periodic discovery did not report " + count + " backends");
    }

    private static JsonObject scoped(String project, String workspace)
    {
        JsonObject args = projectArgs(project);
        args.addProperty("workspacePath", workspace);
        return args;
    }

    private static void assertPort(int expected, JsonObject response)
    {
        assertFalse(response.toString(), isToolError(response));
        assertEquals(expected, structuredContent(response).get("port").getAsInt());
    }
}
