/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Credential resolution must fail closed and must never expose a secret in an error. */
public class ProxyCredentialsTest
{
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void noCredentialsPreservesLocalLegacyConfiguration()
    {
        ProxyCredentials credentials = ProxyCredentials.parse(new Properties(), Map.of());
        assertNull(credentials.clientToken());
        assertNull(credentials.backendToken(8765));
    }

    @Test
    public void separatePortsResolveLiteralAndEnvironmentTokens()
    {
        Properties properties = properties("authTokenEnv", "CLIENT_KEY", "backend.8765.token", "first-edt",
            "backend.8766.tokenEnv", "SECOND_KEY");
        ProxyCredentials credentials = ProxyCredentials.parse(properties,
            Map.of("CLIENT_KEY", "proxy-key", "SECOND_KEY", "second-edt"));
        assertEquals("proxy-key", credentials.clientToken());
        assertEquals("first-edt", credentials.backendToken(8765));
        assertEquals("second-edt", credentials.backendToken(8766));
        assertNull("No default credential may leak to an unconfigured port", credentials.backendToken(8767));
    }

    @Test
    public void proxyEnvironmentOverrideDoesNotChangeBackendCredentials()
    {
        ProxyCredentials credentials = ProxyCredentials.parse(
            properties("authToken", "from-file", "backend.8765.token", "edt-key"),
            Map.of(ProxyCredentials.ENV_AUTH_TOKEN, "from-environment"));
        assertEquals("from-environment", credentials.clientToken());
        assertEquals("edt-key", credentials.backendToken(8765));
    }

    @Test
    public void authenticatedBackendsCannotBeExposedThroughAnUnauthenticatedProxy()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ProxyCredentials.parse(properties("backend.8765.token", "edt-secret"), Map.of()));
        assertTrue(error.getMessage().contains("require proxy client authentication"));
        assertFalse(error.getMessage().contains("edt-secret"));
    }

    @Test
    public void missingOrEmptyEnvironmentReferenceNeverFallsBackToNoAuthentication()
    {
        Properties properties = properties("authTokenEnv", "CLIENT_KEY");
        assertThrows(IllegalArgumentException.class, () -> ProxyCredentials.parse(properties, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> ProxyCredentials.parse(properties, Map.of("CLIENT_KEY", "")));
        assertThrows(IllegalArgumentException.class, () -> ProxyCredentials.parse(
            properties("authToken", "fallback-secret"), Map.of(ProxyCredentials.ENV_AUTH_TOKEN, "")));
    }

    @Test
    public void malformedTokensAreRejectedWithoutEchoingTheirContents()
    {
        for (String value : new String[] { "", " secret ", "secret\r\nInjected: yes", "secret\tvalue",
            "secret\u00E9", "x".repeat(4097) })
        {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProxyCredentials.parse(properties("authToken", value), Map.of()));
            assertTrue(error.getMessage().contains("authToken"));
            if (!value.isEmpty())
            {
                assertFalse(error.getMessage().contains(value));
            }
        }
    }

    @Test
    public void backendTokensFollowEdtNormalizationForFileAndEnvironmentValues()
    {
        String[][] cases = {
            { "p@ss!word", "p@ss!word" },
            { "two words", "two words" },
            { "two  spaces", "two  spaces" },
            { " \t padded token \t ", "padded token" },
            { "\r\ntrimmed-token\r\n", "trimmed-token" }
        };
        for (String[] pair : cases)
        {
            ProxyCredentials literal = ProxyCredentials.parse(properties("authToken", "client-key",
                "backend.8765.token", pair[0]), Map.of());
            assertEquals(pair[1], literal.backendToken(8765));
            ProxyCredentials environment = ProxyCredentials.parse(properties("authToken", "client-key",
                "backend.8765.tokenEnv", "EDT_KEY"), Map.of("EDT_KEY", pair[0]));
            assertEquals(pair[1], environment.backendToken(8765));
        }
    }

    @Test
    public void invalidBackendTokensFailAtStartupWithoutLeakingJdkHeaderErrors()
    {
        for (String value : new String[] { "", " \t ", "secret\r\nInjected: yes", "secret\nvalue",
            "secret\u0100value", "secret\u0001value", "secret\u007fvalue", "secret\tvalue",
            "secret\u00e9\u00ff", "x".repeat(4097) })
        {
            for (boolean fromEnvironment : new boolean[] { false, true })
            {
                String key = "backend.8765." + (fromEnvironment ? "tokenEnv" : "token");
                Properties config = properties("authToken", "client-key", key,
                    fromEnvironment ? "EDT_KEY" : value);
                Map<String, String> env = fromEnvironment ? Map.of("EDT_KEY", value) : Map.of();
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ProxyCredentials.parse(config, env));
                assertTrue(error.getMessage().contains(key));
                assertNull("JDK header exceptions can contain the credential", error.getCause());
                if (!value.isBlank())
                {
                    assertFalse(error.toString().contains(value));
                }
            }
        }
    }

    @Test
    public void relaxedBackendSyntaxDoesNotRelaxProxyClientTokens()
    {
        for (String value : new String[] { "p@ss!word", "two words", "caf\u00e9", " padded " })
        {
            assertThrows(IllegalArgumentException.class,
                () -> ProxyCredentials.parse(properties("authToken", value), Map.of()));
            assertThrows(IllegalArgumentException.class,
                () -> ProxyCredentials.parse(new Properties(), Map.of(ProxyCredentials.ENV_AUTH_TOKEN, value)));
        }
    }

    @Test
    public void cliTargetsRespectConcreteAddressesAndWildcardFamilies() throws Exception
    {
        String[][] cases = {
            { "0.0.0.0", "127.0.0.1" }, { "::", "::1" }, { "[::]", "::1" },
            { "::1", "::1" }, { "192.0.2.20", "192.0.2.20" },
            { "2001:db8::20", "2001:db8::20" }, { "127.0.0.2", "127.0.0.2" }
        };
        for (String[] pair : cases)
        {
            ProxyConfig.ClientOptions options = ProxyConfig.parseClient(
                new String[] { "--bind", pair[0], "--port", "8764" }, Map.of());
            for (String path : new String[] { "/health", "/mcp", "/admin/shutdown" })
            {
                var uri = options.uri(path);
                assertEquals(java.net.InetAddress.getByName(pair[1]), java.net.InetAddress.getByName(uri.getHost()));
                assertEquals(8764, uri.getPort());
                assertEquals(path, uri.getPath());
            }
        }
    }

    @Test
    public void cliBindUsesTheSamePrecedenceAsServe() throws Exception
    {
        Path file = temporary.newFile().toPath();
        Files.writeString(file, "bind=192.0.2.20\nport=8764\nauthToken=client-key\n");
        String[] args = { "--config", file.toString() };
        assertEquals("192.0.2.20", ProxyConfig.parseClient(args, Map.of()).uri("/mcp").getHost());
        assertEquals("127.0.0.2", ProxyConfig.parseClient(args,
            Map.of(ProxyConfig.ENV_HOST, "127.0.0.2")).uri("/mcp").getHost());
        assertEquals("127.0.0.1", ProxyConfig.parseClient(
            new String[] { "--config", file.toString(), "--bind", "0.0.0.0" },
            Map.of(ProxyConfig.ENV_HOST, "127.0.0.2")).uri("/mcp").getHost());
        assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parseClient(new String[] { "--bind", "[" }, Map.of()));
    }

    @Test
    public void ambiguousSecretSourcesAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> ProxyCredentials.parse(
            properties("authToken", "literal", "authTokenEnv", "CLIENT_KEY"), Map.of("CLIENT_KEY", "env")));
        assertThrows(IllegalArgumentException.class, () -> ProxyCredentials.parse(
            properties("authToken", "client", "backend.8765.token", "literal",
                "backend.8765.tokenEnv", "EDT_KEY"), Map.of("EDT_KEY", "env")));
    }

    @Test
    public void mistypedBackendKeysCannotSilentlyDisableAuthentication()
    {
        for (String key : new String[] { "backend.0.token", "backend.65536.token", "backend.08765.token",
            "backend.8765.tokens", "backend.wrong.token" })
        {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProxyCredentials.parse(properties("authToken", "client", key, "secret"), Map.of()));
            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test
    public void cliReadsOnlyProxyCredentialAndResolvesPortPrecedence() throws Exception
    {
        Path file = temporary.newFile("proxy.properties").toPath();
        Files.writeString(file, "port=9000\nauthToken=client-key\nbackend.8765.tokenEnv=UNSET_EDT_KEY\n");
        ProxyConfig.ClientOptions options = ProxyConfig.parseClient(
            new String[] { "--config", file.toString(), "--port", "9002" }, Map.of(ProxyConfig.ENV_PORT, "9001"));
        assertEquals(9002, options.port);
        assertEquals("client-key", options.token);
    }

    @Test
    public void cliCanOverrideAnEphemeralServerPort() throws Exception
    {
        Path file = temporary.newFile("ephemeral.properties").toPath();
        Files.writeString(file, "port=0\nauthToken=client-key\n");
        String[] args = { "--config", file.toString() };
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parseClient(args, Map.of()));
        assertTrue(error.getMessage().contains("--port N"));
        assertEquals(9002, ProxyConfig.parseClient(
            new String[] { "--config", file.toString(), "--port", "9002" },
            Map.of(ProxyConfig.ENV_PORT, "0")).port);
        assertEquals(9001, ProxyConfig.parseClient(args, Map.of(ProxyConfig.ENV_PORT, "9001")).port);
    }

    @Test
    public void configFileLoadsBackendCredentialsForTheServer() throws Exception
    {
        Path file = temporary.newFile("server.properties").toPath();
        Files.writeString(file, "authToken=client-key\nbackend.8765.tokenEnv=EDT_KEY\n");
        ProxyConfig config = ProxyConfig.parse(new String[] { "--config", file.toString() },
            Map.of("EDT_KEY", "edt-key"));
        assertEquals("client-key", config.credentials.clientToken());
        assertEquals("edt-key", config.credentials.backendToken(8765));
    }

    static Properties properties(String... pairs)
    {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2)
        {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return properties;
    }
}
