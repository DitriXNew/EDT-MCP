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
     * {@code resources} capabilities; it does not yet advertise prompts or other
     * optional features, so those fields are intentionally absent (the shared Gson
     * omits null fields). The CLIENT's capabilities are a separate concern: they
     * arrive in the initialize REQUEST and are parsed/stored by the protocol
     * handler (see {@code McpProtocolHandler#getClientCapabilities()}), not
     * modelled here.
     */
    public static class Capabilities
    {
        private Tools tools = new Tools();
        private Resources resources = new Resources();

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
     * Tools capability (an empty object signals that tools are supported, per the
     * MCP capabilities convention).
     */
    public static class Tools
    {
        // Empty - just signals that tools are supported
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
