/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP tools/call response result.
 */
public class ToolCallResult
{
    private List<ContentItem> content = new ArrayList<>();
    private Object structuredContent;
    
    private ToolCallResult()
    {
    }
    
    /**
     * Creates a text content result.
     */
    public static ToolCallResult text(String text)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.text(text));
        return result;
    }
    
    /**
     * Creates a JSON content result with structuredContent.
     */
    public static ToolCallResult json(Object structuredContent)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.text("Done")); //$NON-NLS-1$
        result.structuredContent = structuredContent;
        return result;
    }
    
    /**
     * Creates a resource content result (for Markdown, etc.).
     */
    public static ToolCallResult resource(String uri, String mimeType, String text)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.resource(uri, mimeType, text, null));
        return result;
    }
    
    /**
     * Creates a resource content result with blob data (for images, etc.).
     */
    public static ToolCallResult resourceBlob(String uri, String mimeType, String base64Blob)
    {
        ToolCallResult result = new ToolCallResult();
        result.content.add(ContentItem.resource(uri, mimeType, null, base64Blob));
        return result;
    }
    
    public List<ContentItem> getContent()
    {
        return content;
    }
    
    public Object getStructuredContent()
    {
        return structuredContent;
    }
    
    /**
     * MCP content item.
     */
    public static class ContentItem
    {
        private String type;
        private String text;
        private ResourceInfo resource;
        
        private ContentItem()
        {
        }
        
        public static ContentItem text(String text)
        {
            ContentItem item = new ContentItem();
            item.type = "text"; //$NON-NLS-1$
            item.text = text;
            return item;
        }
        
        public static ContentItem resource(String uri, String mimeType, String text, String blob)
        {
            ContentItem item = new ContentItem();
            item.type = "resource"; //$NON-NLS-1$
            item.resource = new ResourceInfo(uri, mimeType, text, blob);
            return item;
        }
        
        public String getType()
        {
            return type;
        }
        
        public String getText()
        {
            return text;
        }
        
        public ResourceInfo getResource()
        {
            return resource;
        }
    }
    
    /**
     * Embedded resource info.
     */
    public static class ResourceInfo
    {
        private String uri;
        private String mimeType;
        private String text;
        private String blob;
        
        public ResourceInfo(String uri, String mimeType, String text, String blob)
        {
            this.uri = uri;
            this.mimeType = mimeType;
            this.text = text;
            this.blob = blob;
        }
        
        public String getUri()
        {
            return uri;
        }
        
        public String getMimeType()
        {
            return mimeType;
        }
        
        public String getText()
        {
            return text;
        }
        
        public String getBlob()
        {
            return blob;
        }
    }
}
