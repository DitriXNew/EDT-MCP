/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;

/** A bearer check on every request, before session lookup or reading a request body. */
final class BearerAuthentication
{
    private static final String HEADER = "Authorization";
    private final byte[] expectedDigest;

    BearerAuthentication(String token)
    {
        expectedDigest = token == null ? null : digest(token);
    }

    boolean accepts(List<String> headers)
    {
        if (expectedDigest == null)
        {
            return true;
        }
        if (headers == null || headers.size() != 1)
        {
            return false;
        }
        String header = headers.get(0);
        if (header == null || header.length() > ProxyCredentials.MAX_TOKEN_LENGTH + 7
            || !header.regionMatches(true, 0, "Bearer ", 0, 7))
        {
            return false;
        }
        // Fixed-size hashes avoid comparisons whose running time depends on the secret's length.
        return MessageDigest.isEqual(expectedDigest, digest(header.substring(7)));
    }

    boolean authorize(HttpExchange exchange) throws IOException
    {
        if (accepts(exchange.getRequestHeaders().get(HEADER)))
        {
            return true;
        }
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
            + "\"message\":\"Unauthorized\"}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"edt-mcp-proxy\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(401, body.length);
        exchange.getResponseBody().write(body);
        return false;
    }

    static HttpRequest.Builder addHeader(HttpRequest.Builder builder, String token)
    {
        if (token != null)
        {
            builder.header(HEADER, "Bearer " + token);
        }
        return builder;
    }

    private static byte[] digest(String value)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
