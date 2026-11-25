/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

/**
 * MCP protocol constants.
 * Implements MCP 2025-11-25 specification.
 */
public final class McpConstants
{
    /** JSON-RPC version */
    public static final String JSONRPC_VERSION = "2.0"; //$NON-NLS-1$

    /** MCP protocol version - updated to 2025-11-25 */
    public static final String PROTOCOL_VERSION = "2025-11-25"; //$NON-NLS-1$

    /**
     * MCP protocol versions this server is compatible with, latest first.
     * Per the MCP spec, during {@code initialize} the server echoes the client's
     * requested version only when it is one of these; for an unsupported (e.g.
     * future) version the server responds with its latest supported version
     * ({@link #PROTOCOL_VERSION}, the first entry). The ordering reflects
     * newest-to-oldest. Backed by an insertion-ordered, unmodifiable set.
     */
    public static final Set<String> SUPPORTED_VERSIONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.<String> asList(
            PROTOCOL_VERSION, // 2025-11-25
            "2025-06-18", //$NON-NLS-1$
            "2025-03-26", //$NON-NLS-1$
            "2024-11-05" //$NON-NLS-1$
        )));

    /**
     * Returns {@code true} when {@code version} is a protocol version this server
     * supports (see {@link #SUPPORTED_VERSIONS}).
     *
     * @param version the client-requested protocol version (may be {@code null})
     * @return whether the version is supported
     */
    public static boolean isSupportedVersion(String version)
    {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }
    
    /** Server name */
    public static final String SERVER_NAME = "edt-mcp-server"; //$NON-NLS-1$
    
    /** Plugin author */
    public static final String AUTHOR = "DitriX, Diversus23, Jimmo910"; //$NON-NLS-1$
    
    /** Plugin version - read from Bundle-Version at runtime, set by tycho-versions-plugin */
    public static final String PLUGIN_VERSION;

    static
    {
        org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(McpConstants.class);
        if (bundle != null)
        {
            Version v = bundle.getVersion();
            PLUGIN_VERSION = v.getMajor() + "." + v.getMinor() + "." + v.getMicro(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            PLUGIN_VERSION = "unknown"; //$NON-NLS-1$
        }
    }
    
    // JSON-RPC error codes
    /** Parse error */
    public static final int ERROR_PARSE = -32700;
    
    /** Invalid request */
    public static final int ERROR_INVALID_REQUEST = -32600;
    
    /** Method not found */
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    
    /** Invalid params */
    public static final int ERROR_INVALID_PARAMS = -32602;
    
    /** Internal error */
    public static final int ERROR_INTERNAL = -32603;
    
    // HTTP Headers
    /** MCP Protocol Version header */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"; //$NON-NLS-1$
    
    /** MCP Session ID header */
    public static final String HEADER_SESSION_ID = "MCP-Session-Id"; //$NON-NLS-1$
    
    // MCP methods
    /** Initialize method */
    public static final String METHOD_INITIALIZE = "initialize"; //$NON-NLS-1$
    
    /** Initialized notification */
    public static final String METHOD_INITIALIZED = "notifications/initialized"; //$NON-NLS-1$

    /**
     * Client-requested host shutdown. A plugin extension to MCP, not a protocol method: it is
     * accepted only by a host that armed a shutdown channel ({@code StdioHostApplication}), and it
     * is answered with {@link #METHOD_HOST_SHUTDOWN_ACCEPTED} or {@link #METHOD_HOST_SHUTDOWN_REFUSED}
     * before the host starts closing, so a caller can tell "this host is going down" from "this host
     * will not go down" instead of guessing from a timeout.
     */
    public static final String METHOD_HOST_SHUTDOWN = "notifications/host_shutdown"; //$NON-NLS-1$

    /**
     * The host accepted {@link #METHOD_HOST_SHUTDOWN} and is draining. Sent immediately, before any
     * cleanup, so a caller can wait for the process exit with a full budget instead of racing it.
     */
    public static final String METHOD_HOST_SHUTDOWN_ACCEPTED = "notifications/host_shutdown_accepted"; //$NON-NLS-1$

    /**
     * The host will not shut down - an interactive EDT has no shutdown channel, or the request
     * could not be resolved. The host keeps running and must never be force-killed on this answer.
     */
    public static final String METHOD_HOST_SHUTDOWN_REFUSED = "notifications/host_shutdown_refused"; //$NON-NLS-1$

    /** The host finished its cleanup; carries the boolean {@code success} of the full save. */
    public static final String METHOD_HOST_SHUTDOWN_COMPLETE = "notifications/host_shutdown_complete"; //$NON-NLS-1$
    
    /** Tools list method */
    public static final String METHOD_TOOLS_LIST = "tools/list"; //$NON-NLS-1$
    
    /** Tools call method */
    public static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$

    /** Resources list method (serves the per-tool guide:// documents) */
    public static final String METHOD_RESOURCES_LIST = "resources/list"; //$NON-NLS-1$

    /** Resources read method (returns the Markdown body of one guide:// document) */
    public static final String METHOD_RESOURCES_READ = "resources/read"; //$NON-NLS-1$

    /** Ping utility method (MCP basic utilities): respond promptly with an empty result {}. */
    public static final String METHOD_PING = "ping"; //$NON-NLS-1$

    /** URI scheme prefix for a per-tool how-to guide resource ({@code guide://<toolName>}) */
    public static final String GUIDE_URI_SCHEME = "guide://"; //$NON-NLS-1$

    /**
     * Name of the on-demand tool-guide tool. Lives here (not only on the tool impl) so
     * the protocol layer can reference it without depending on {@code tools.impl}.
     */
    public static final String TOOL_GET_TOOL_GUIDE = "get_tool_guide"; //$NON-NLS-1$

    private McpConstants()
    {
        // Utility class
    }
}
