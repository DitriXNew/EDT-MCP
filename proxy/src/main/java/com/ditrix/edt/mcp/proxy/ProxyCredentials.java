/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Startup-only credentials. Client and backend trust boundaries never share a header. */
final class ProxyCredentials
{
    static final String ENV_AUTH_TOKEN = "EDT_MCP_PROXY_AUTH_TOKEN";
    private static final Pattern BACKEND_KEY = Pattern.compile("backend\\.([1-9][0-9]{0,4})\\.(token|tokenEnv)");
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    static final int MAX_TOKEN_LENGTH = 4096;

    private final String clientToken;
    private final Map<Integer, String> backendTokens;

    private ProxyCredentials(String clientToken, Map<Integer, String> backendTokens)
    {
        this.clientToken = clientToken;
        this.backendTokens = Map.copyOf(backendTokens);
    }

    static ProxyCredentials parse(Properties properties, Map<String, String> env)
    {
        String clientToken = readClientToken(properties, env);
        TreeSet<Integer> ports = new TreeSet<>();
        for (String key : properties.stringPropertyNames())
        {
            if (!key.startsWith("backend."))
            {
                continue;
            }
            Matcher match = BACKEND_KEY.matcher(key);
            if (!match.matches() || Integer.parseInt(match.group(1)) > 65535)
            {
                throw new IllegalArgumentException("Invalid backend credential key; use backend.<port>.token"
                    + " or backend.<port>.tokenEnv with a port from 1 to 65535.");
            }
            ports.add(Integer.parseInt(match.group(1)));
        }
        Map<Integer, String> backendTokens = new HashMap<>();
        for (int port : ports)
        {
            String key = "backend." + port + ".token";
            backendTokens.put(port, readToken(properties, key, key + "Env", env,
                ProxyCredentials::validateBackendToken));
        }
        if (!backendTokens.isEmpty() && clientToken == null)
        {
            throw new IllegalArgumentException("Backend tokens require proxy client authentication, even on"
                + " loopback. Set authToken, authTokenEnv or " + ENV_AUTH_TOKEN + ".");
        }
        return new ProxyCredentials(clientToken, backendTokens);
    }

    /** CLI clients need only the proxy credential, never the EDT backend credentials. */
    static String readClientToken(Properties properties, Map<String, String> env)
    {
        if (env != null && env.containsKey(ENV_AUTH_TOKEN))
        {
            return validateClientToken(env.get(ENV_AUTH_TOKEN), ENV_AUTH_TOKEN);
        }
        return readToken(properties, "authToken", "authTokenEnv", env, ProxyCredentials::validateClientToken);
    }

    private static String readToken(Properties properties, String key, String envKey, Map<String, String> env,
        BiFunction<String, String, String> validator)
    {
        String value = properties.getProperty(key);
        String variable = properties.getProperty(envKey);
        if (value != null && variable != null)
        {
            throw new IllegalArgumentException("Configure only one of " + key + " and " + envKey + ".");
        }
        if (variable != null)
        {
            if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*") || env == null || !env.containsKey(variable))
            {
                throw new IllegalArgumentException("The environment variable referenced by " + envKey
                    + " is invalid or not set.");
            }
            value = env.get(variable);
            return validator.apply(value, envKey);
        }
        return value == null ? null : validator.apply(value, key);
    }

    private static String validateClientToken(String value, String source)
    {
        // Never include the value (or a nested exception containing it) in diagnostics.
        if (value == null || value.length() > MAX_TOKEN_LENGTH || !CLIENT_TOKEN.matcher(value).matches())
        {
            throw new IllegalArgumentException("Invalid token configured by " + source
                + "; use a non-empty ASCII bearer token without whitespace (maximum 4096 characters).");
        }
        return value;
    }

    private static String validateBackendToken(String value, String source)
    {
        // Match EDT HttpTransport.normalizeToken, not the proxy's stricter client-token
        // contract. All printable ASCII punctuation and internal spaces are valid.
        String token = value == null ? "" : value.trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH)
        {
            throw invalidBackendToken(source);
        }
        for (int i = 0; i < token.length(); i++)
        {
            char c = token.charAt(i);
            // HttpClient's HTTP/1.1 serializer replaces Latin-1 with '?', while HttpServer
            // rewrites internal tabs to spaces. Reject anything that cannot arrive unchanged;
            // do so here, before a JDK header exception could include the secret in its message.
            if (c < 0x20 || c > 0x7E)
            {
                throw invalidBackendToken(source);
            }
        }
        return token;
    }

    private static IllegalArgumentException invalidBackendToken(String source)
    {
        return new IllegalArgumentException("Invalid EDT token configured by " + source
            + "; use a non-empty token after trimming, at most 4096 printable ASCII characters"
            + " (punctuation and internal spaces are allowed). Java's HTTP transport cannot"
            + " preserve non-ASCII characters or internal tabs/control characters.");
    }

    String clientToken()
    {
        return clientToken;
    }

    String backendToken(int port)
    {
        return backendTokens.get(port);
    }
}
