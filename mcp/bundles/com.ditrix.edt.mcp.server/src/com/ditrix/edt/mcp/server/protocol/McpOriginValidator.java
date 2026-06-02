/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

/**
 * Origin allow-list policy for the MCP HTTP transport.
 *
 * <p>Extracted from {@code McpServer} as a self-contained, pure-logic security
 * policy: it decides whether a request's {@code Origin} header is permitted.
 * It performs no socket / SSE / HTTP work itself — the transport layer
 * ({@code McpServer.addCorsHeaders}) consults this class and then sets the
 * CORS response headers. Keeping the policy here makes it independently
 * testable and keeps the transport class focused on transport.</p>
 *
 * <p>The MCP server is a local-only endpoint, so only loopback / local origins
 * are allowed (localhost, 127.0.0.1 over http or https), plus {@code file://}
 * origins, the literal {@code "null"} (sent by local HTML files), and the
 * {@code vscode-webview://} scheme used by VS Code extensions.</p>
 */
public final class McpOriginValidator
{
    private McpOriginValidator()
    {
        // Utility class
    }

    /**
     * Validates an {@code Origin} header value for security.
     * Allows localhost origins, file:// origins, and "null" (for local file HTML).
     *
     * @param origin the Origin header value (must be non-null)
     * @return true if origin is allowed
     */
    public static boolean isValidOrigin(String origin)
    {
        return isLoopbackHost(origin, "http://localhost") || //$NON-NLS-1$
               isLoopbackHost(origin, "http://127.0.0.1") || //$NON-NLS-1$
               isLoopbackHost(origin, "https://localhost") || //$NON-NLS-1$
               isLoopbackHost(origin, "https://127.0.0.1") || //$NON-NLS-1$
               origin.startsWith("file://") || //$NON-NLS-1$
               origin.equals("null") || //$NON-NLS-1$ // Local HTML files send "null" as origin
               origin.startsWith("vscode-webview://"); //$NON-NLS-1$
    }

    /**
     * Exact host match: the origin must be exactly {@code prefix} (scheme://host)
     * or {@code prefix} immediately followed by {@code ':'} (a port). This rejects
     * look-alike hosts such as {@code http://localhost.attacker.com} that a naive
     * {@code startsWith} would accept. An {@code Origin} header carries no path
     * component, so no {@code '/'} handling is required.
     *
     * @param origin the Origin header value (non-null)
     * @param prefix the scheme://host prefix to match exactly
     * @return true if the origin is exactly the prefix or the prefix + port
     */
    private static boolean isLoopbackHost(String origin, String prefix)
    {
        return origin.equals(prefix) || origin.startsWith(prefix + ":"); //$NON-NLS-1$
    }
}
