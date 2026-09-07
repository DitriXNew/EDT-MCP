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
 * ({@code HttpTransport.addCorsHeaders}) consults this class and then sets the
 * CORS response headers. Keeping the policy here makes it independently
 * testable and keeps the transport class focused on transport.</p>
 *
 * <p><b>Only loopback origins are allowed</b>: {@code localhost}, {@code 127.0.0.1}
 * and {@code [::1]}, over {@code http} or {@code https}, with or without a port. A
 * request with NO {@code Origin} at all is a non-browser client and is admitted by
 * the transport without consulting this class; everything else a browser can put in
 * that header is refused.</p>
 *
 * <p><b>Why the list is only that.</b> This header is the whole browser-CSRF defence:
 * on a default install the server listens on loopback with no token, so any origin
 * this class accepts can drive every tool — including {@code evaluate_expression},
 * which runs arbitrary BSL — and, because the reply carries
 * {@code Access-Control-Allow-Origin}, can READ the answer. Three entries used to be
 * accepted that a hostile page can produce at will, and each is gone:</p>
 * <ul>
 *   <li>the literal {@code "null"} — sent by a sandboxed iframe, a {@code data:} URL
 *       and a cross-origin redirect, not only by a local file, so accepting it handed
 *       the allow-list to any page that can open an iframe;</li>
 *   <li>{@code file://} — a page saved to disk is not a supported client, and the
 *       browsers that send it for local files mostly send {@code "null"} instead;</li>
 *   <li>{@code vscode-webview://} — the host part is a per-webview UUID, not an
 *       extension id, so the scheme identifies no particular extension and cannot be
 *       narrowed to a trusted one. A VS Code extension talks to this server from its
 *       extension host, which sends no {@code Origin} at all.</li>
 * </ul>
 *
 * <p>A browser client that genuinely needs a non-loopback origin is served by binding
 * the listener remotely with a shared token, not by widening this list.</p>
 */
public final class McpOriginValidator
{
    private McpOriginValidator()
    {
        // Utility class
    }

    /**
     * Validates an {@code Origin} header value for security. Only loopback origins
     * ({@code localhost}, {@code 127.0.0.1}, {@code [::1]} over http/https, with an
     * optional port) are allowed.
     *
     * @param origin the Origin header value (must be non-null)
     * @return true if origin is allowed
     */
    public static boolean isValidOrigin(String origin)
    {
        return isLoopbackHost(origin, "http://localhost") || //$NON-NLS-1$
               isLoopbackHost(origin, "http://127.0.0.1") || //$NON-NLS-1$
               isLoopbackHost(origin, "http://[::1]") || //$NON-NLS-1$
               isLoopbackHost(origin, "https://localhost") || //$NON-NLS-1$
               isLoopbackHost(origin, "https://127.0.0.1") || //$NON-NLS-1$
               isLoopbackHost(origin, "https://[::1]"); //$NON-NLS-1$
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
