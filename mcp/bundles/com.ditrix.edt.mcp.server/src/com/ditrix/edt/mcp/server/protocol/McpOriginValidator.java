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
        return origin.startsWith("http://localhost") || //$NON-NLS-1$
               origin.startsWith("http://127.0.0.1") || //$NON-NLS-1$
               origin.startsWith("https://localhost") || //$NON-NLS-1$
               origin.startsWith("https://127.0.0.1") || //$NON-NLS-1$
               origin.startsWith("file://") || //$NON-NLS-1$
               origin.equals("null") || //$NON-NLS-1$ // Local HTML files send "null" as origin
               origin.startsWith("vscode-webview://"); //$NON-NLS-1$
    }
}
