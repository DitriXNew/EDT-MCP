/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.*;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.McpTestClient;
import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;
import com.google.gson.JsonObject;

/** Real HTTP tests with two independently authenticated EDT backends and a protected LAN listener. */
public class AuthenticationIT
{
    private static final String CLIENT_TOKEN = "test-proxy-client-key";
    private static final String FIRST_TOKEN = "test-first-edt-key";
    private static final String SECOND_TOKEN = "test-second-edt-key";
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
        + "\"clientInfo\":{\"name\":\"auth-test\",\"version\":\"1\"}}}";
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();
    @Rule
    public final Timeout timeout = Timeout.seconds(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private FakeBackend first;
    private FakeBackend second;
    private Properties properties;
    private ProxyFixture proxy;
    private McpTestClient client;

    @Before
    public void setUp() throws Exception
    {
        int[] ports = adjacentPorts();
        first = new FakeBackend(ports[0], "Alpha", "Shared");
        second = new FakeBackend(ports[1], "Beta", "Shared");
        first.setAuthToken(FIRST_TOKEN);
        second.setAuthToken(SECOND_TOKEN);
        first.setWorkspacePath("/work/first");
        second.setWorkspacePath("/work/second");
        first.start();
        second.start();
        properties = ProxyCredentialsTest.properties("port", "0", "scan", ports[0] + "-" + ports[1],
            "refresh", "3600", "timeout", "3", "bind", "0.0.0.0", "authToken", CLIENT_TOKEN,
            "backend." + ports[0] + ".token", FIRST_TOKEN, "backend." + ports[1] + ".token", SECOND_TOKEN);
    }

    @After
    public void tearDown()
    {
        if (proxy != null)
        {
            proxy.stop();
        }
        stopQuietly(first);
        stopQuietly(second);
    }

    @Test
    public void discoversAndRoutesTwoEdtsWithoutForwardingTheClientToken() throws Exception
    {
        connect();
        assertPort(first.getPort(), client.callTool("echo_port", projectArgs("Alpha")));
        assertPort(second.getPort(), client.callTool("echo_port", projectArgs("Beta")));
        JsonObject args = new JsonObject();
        args.addProperty("format", "json");
        assertEquals(4, structuredContent(client.callTool("list_projects", args)).getAsJsonArray("projects").size());
        assertNotNull(client.request("tools/list", new JsonObject()).getAsJsonObject("result"));
        assertEquals("Bearer " + FIRST_TOKEN, first.lastAuthorization());
        assertEquals("Bearer " + SECOND_TOKEN, second.lastAuthorization());
        assertEquals(0, first.rejectedAuthCount());
        assertEquals(0, second.rejectedAuthCount());
        assertNoSecrets(client.callTool("router_status", new JsonObject()).toString());
    }

    @Test
    public void workspaceSelectionAndReconnectPreserveTheCorrectEdtCredential() throws Exception
    {
        connect();
        JsonObject args = projectArgs("Shared");
        args.addProperty("workspacePath", "/work/second");
        assertPort(second.getPort(), client.callTool("echo_port", args));
        int initializations = second.getInitializeCount();
        second.stop();
        second.invalidateSessions();
        second.start();
        assertPort(second.getPort(), client.callTool("echo_port", args));
        assertTrue(second.getInitializeCount() > initializations);
        assertEquals("Bearer " + SECOND_TOKEN, second.lastAuthorization());
        assertEquals(0, second.rejectedAuthCount());
    }

    @Test
    public void refusesUnauthenticatedRequestsBeforeParsingOrAllocatingSessions() throws Exception
    {
        start();
        for (String token : new String[] { null, "wrong-key", FIRST_TOKEN, SECOND_TOKEN })
        {
            HttpResponse<String> response = request("POST", "/mcp", "not-json", token, null);
            assertEquals(401, response.statusCode());
            assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
            assertNoSecrets(response.body());
        }
        assertEquals(0, proxy.activeSessions());
        assertEquals(1, first.getInitializeCount());
        assertEquals(1, second.getInitializeCount());
        assertEquals(200, request("GET", "/health", "", null, null).statusCode());
    }

    @Test
    public void checksAuthenticationOnEveryMethodEvenWithAValidSession() throws Exception
    {
        start();
        HttpResponse<String> initialized = request("POST", "/mcp", INITIALIZE, CLIENT_TOKEN, null);
        assertEquals(200, initialized.statusCode());
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals(1, proxy.activeSessions());
        for (String method : new String[] { "POST", "GET", "DELETE", "OPTIONS" })
        {
            assertEquals(401, request(method, "/mcp", PING, null, session).statusCode());
            assertEquals(401, request(method, "/mcp", PING, "wrong", session).statusCode());
        }
        assertEquals(1, proxy.activeSessions());
        assertEquals(200, request("POST", "/mcp", PING, CLIENT_TOKEN, session).statusCode());
        assertEquals(405, request("GET", "/mcp", "", CLIENT_TOKEN, session).statusCode());
        assertEquals(200, request("DELETE", "/mcp", "", CLIENT_TOKEN, session).statusCode());
        assertEquals(0, proxy.activeSessions());
    }

    @Test
    public void duplicateAuthorizationHeadersCannotBypassTheGate() throws Exception
    {
        start();
        HttpRequest request = HttpRequest.newBuilder(uri("/mcp"))
            .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + CLIENT_TOKEN).header("Authorization", "Bearer wrong")
            .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)).build();
        assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, proxy.activeSessions());
    }

    @Test
    public void wrongBackendTokenReportsAuthenticationAndLeavesTheOtherEdtUsable() throws Exception
    {
        properties.setProperty("backend." + second.getPort() + ".token", "incorrect-edt-key");
        connect();
        assertPort(first.getPort(), client.callTool("echo_port", projectArgs("Alpha")));
        String status = client.callTool("router_status", new JsonObject()).toString();
        assertTrue(status, status.contains("authenticationError"));
        assertTrue(status, status.contains("HTTP 401"));
        String error = errorText(client.callTool("echo_port", projectArgs("Beta")));
        assertTrue(error, error.contains("backend." + second.getPort() + ".token"));
        assertNoSecrets(status + error);
        assertFalse((status + error).contains("incorrect-edt-key"));
        JsonObject listing = client.callTool("list_projects", new JsonObject());
        assertTrue("A partial project list must not look complete", isToolError(listing));
        assertTrue(errorText(listing).contains("HTTP 401"));
    }

    @Test
    public void anUnconfiguredBackendNeverReceivesAnotherCredential() throws Exception
    {
        properties.remove("backend." + second.getPort() + ".token");
        connect();
        assertNull(second.lastAuthorization());
        assertTrue(second.rejectedAuthCount() > 0);
        assertTrue(client.callTool("router_status", new JsonObject()).toString().contains("authenticationError"));
        assertPort(first.getPort(), client.callTool("echo_port", projectArgs("Alpha")));
    }

    @Test
    public void revokedTokenDuringASessionIsNotReportedAsADeadBackendOrRetried() throws Exception
    {
        connect();
        first.setAuthToken("rotated-edt-key");
        first.setAuthFailureStatus(403);
        int initializations = first.getInitializeCount();
        JsonObject response = client.request("tools/list", new JsonObject());
        assertEquals(-32002, response.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(response.toString(), response.toString().contains("HTTP 403"));
        assertFalse(response.toString().contains("stopped responding"));
        assertEquals(1, first.rejectedAuthCount());
        assertEquals(initializations, first.getInitializeCount());
        assertTrue(isToolError(client.callTool("echo_port", projectArgs("Alpha"))));
        assertNoSecrets(response.toString());

        proxy.stop();
        properties.setProperty("backend." + first.getPort() + ".token", "rotated-edt-key");
        connect();
        assertPort(first.getPort(), client.callTool("echo_port", projectArgs("Alpha")));
        assertFalse(client.callTool("router_status", new JsonObject()).toString().contains("authenticationError"));
    }

    @Test
    public void cliStatusAndStopAuthenticateAndStatusClosesItsSession() throws Exception
    {
        start();
        Path config = temporary.newFile("client.properties").toPath();
        Files.writeString(config, "authToken=" + CLIENT_TOKEN + "\nbackend.8765.tokenEnv=UNSET_BACKEND\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8))
        {
            String[] noAuth = { "--port", Integer.toString(proxy.port()) };
            assertEquals(1, CliCommands.status(noAuth, capture, capture));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("authentication"));
            assertEquals(1, CliCommands.stop(noAuth, capture, capture));
            assertEquals(200, request("GET", "/health", "", null, null).statusCode());

            String[] authenticated = { "--port", Integer.toString(proxy.port()), "--config", config.toString() };
            assertEquals(0, CliCommands.status(authenticated, capture, capture));
            assertEquals(0, proxy.activeSessions());
            assertEquals(0, CliCommands.stop(authenticated, capture, capture));
            assertNoSecrets(output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void wildcardBindWithoutAuthenticationIsRefused() throws Exception
    {
        ProxyFixture unsafe = new ProxyFixture(ProxyConfig.parse(new String[] {
            "--port", "0", "--scan", "2-1", "--bind", "0.0.0.0" }, Map.of()));
        try
        {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, unsafe::start);
            assertTrue(error.getMessage().contains("requires client authentication"));
        }
        finally
        {
            unsafe.stop();
        }
    }

    @Test
    public void explicitlySelectedLoopbackRetainsLegacyCompatibility() throws Exception
    {
        ProxyFixture local = new ProxyFixture(ProxyConfig.parse(new String[] {
            "--port", "0", "--scan", "2-1", "--bind", "127.0.0.1" }, Map.of()));
        try
        {
            local.start();
            new McpTestClient(local.port()).handshake();
        }
        finally
        {
            local.stop();
        }
    }

    private void start() throws Exception
    {
        Path file = temporary.newFile().toPath();
        try (var out = Files.newOutputStream(file))
        {
            properties.store(out, "Test credentials only");
        }
        proxy = new ProxyFixture(ProxyConfig.parse(new String[] { "--config", file.toString() }, Map.of()));
        proxy.start();
        client = new McpTestClient(proxy.port(), CLIENT_TOKEN);
    }

    private void connect() throws Exception
    {
        start();
        client.handshake();
    }

    private URI uri(String path)
    {
        return URI.create("http://127.0.0.1:" + proxy.port() + path);
    }

    private HttpResponse<String> request(String method, String path, String body, String token, String session)
        throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody());
        if (token != null)
        {
            request.header("Authorization", "Bearer " + token);
        }
        if (session != null)
        {
            request.header("Mcp-Session-Id", session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertNoSecrets(String text)
    {
        assertFalse(text.contains(CLIENT_TOKEN));
        assertFalse(text.contains(FIRST_TOKEN));
        assertFalse(text.contains(SECOND_TOKEN));
    }

    private static int[] adjacentPorts() throws IOException
    {
        for (int attempt = 0; attempt < 50; attempt++)
        {
            try (ServerSocket first = new ServerSocket(0))
            {
                int port = first.getLocalPort();
                if (port == 65535)
                {
                    continue;
                }
                try (ServerSocket second = new ServerSocket(port + 1))
                {
                    return new int[] { port, port + 1 };
                }
                catch (IOException occupied)
                {
                    // Retry a fresh pair; do not scan unrelated ephemeral listeners.
                }
            }
        }
        throw new IOException("Could not reserve two adjacent test ports");
    }
}
