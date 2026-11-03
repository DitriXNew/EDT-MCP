/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

/**
 * MCP initialize response result.
 */
public class InitializeResult
{
    private String protocolVersion;
    private Capabilities capabilities;
    private ServerInfo serverInfo;
    
    public InitializeResult(String protocolVersion, String serverName, String serverVersion, String author)
    {
        this.protocolVersion = protocolVersion;
        this.capabilities = new Capabilities();
        this.serverInfo = new ServerInfo(serverName, serverVersion, author);
    }
    
    public String getProtocolVersion()
    {
        return protocolVersion;
    }
    
    public Capabilities getCapabilities()
    {
        return capabilities;
    }
    
    public ServerInfo getServerInfo()
    {
        return serverInfo;
    }
    
    /**
     * The SERVER's declared capabilities, returned to the client in the
     * initialize result. This server advertises the {@code tools} and
     * {@code resources} capabilities. Guided workflows optionally enable prompts
     * and completion (otherwise omitted by Gson). The CLIENT's capabilities are a separate concern: they
     * arrive in the initialize REQUEST and are parsed/stored by the protocol
     * handler (see {@code McpProtocolHandler#getClientCapabilities()}), not
     * modelled here.
     */
    public static class Capabilities
    {
        private Tools tools = new Tools();
        private Resources resources = new Resources();
        private Object prompts;
        private Object completions;

        /** Enables only capabilities defined by the negotiated protocol revision. */
        public void enableGuidedWorkflows(String protocolVersion)
        {
            prompts = java.util.Map.of("listChanged", false); //$NON-NLS-1$
            // Completion exists in 2024-11-05 but has no capability declaration there.
            if (!"2024-11-05".equals(protocolVersion)) //$NON-NLS-1$
            {
                completions = java.util.Map.of();
            }
        }

        public Tools getTools()
        {
            return tools;
        }

        public Resources getResources()
        {
            return resources;
        }
    }

    /**
     * Tools capability. {@code listChanged} is {@code true}: the server can emit
     * {@code notifications/tools/list_changed} over an open SSE stream when the
     * exposed tool set changes (progressive tool disclosure — {@code enable_toolset}
     * revealing a toolset). Clients that keep a GET SSE stream open receive the push;
     * others re-request {@code tools/list}.
     */
    public static class Tools
    {
        private boolean listChanged = true;

        public boolean isListChanged()
        {
            return listChanged;
        }
    }

    /**
     * Resources capability. Signals that the server serves MCP resources
     * (resources/list + resources/read) — here the per-tool {@code guide://<name>}
     * how-to documents. {@code listChanged} is {@code false} because the guide set
     * is static for the lifetime of a session (it mirrors the registered tools), so
     * the server never emits a {@code notifications/resources/list_changed}.
     */
    public static class Resources
    {
        private boolean listChanged = false;

        public boolean isListChanged()
        {
            return listChanged;
        }
    }
    
    /**
     * Server info.
     */
    public static class ServerInfo
    {
        private String name;
        private String version;
        private String author;
        
        public ServerInfo(String name, String version, String author)
        {
            this.name = name;
            this.version = version;
            this.author = author;
        }
        
        public String getName()
        {
            return name;
        }
        
        public String getVersion()
        {
            return version;
        }
        
        public String getAuthor()
        {
            return author;
        }
    }
}
