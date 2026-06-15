/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * MCP tools/call response result.
 */
public class ToolCallResult
{
    /**
     * Upper bound (characters) for the success digest placed in
     * {@code content[0].text}. Keeps the textual fallback compact for clients
     * that read {@code content} instead of {@code structuredContent}.
     */
    private static final int DIGEST_MAX_LENGTH = 500;

    private List<ContentItem> content = new ArrayList<>();
    private Object structuredContent;
    private Boolean isError;

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
     * Creates a successful JSON content result with structuredContent.
     */
    public static ToolCallResult json(Object structuredContent)
    {
        return json(structuredContent, false);
    }

    /**
     * Creates a JSON content result with structuredContent. When {@code isError}
     * is true the result is flagged with {@code isError:true} per the MCP
     * tools/call contract, so clients can distinguish a tool-level failure from a
     * success (the shared Gson omits the field when it is false/null).
     */
    public static ToolCallResult json(Object structuredContent, boolean isError)
    {
        ToolCallResult result = new ToolCallResult();
        // On success, the full data lives in structuredContent; the textual
        // content fallback (read by spec-compliant clients and the model, which see
        // content[0].text but may ignore structuredContent) gets a bounded,
        // human-readable digest instead of an opaque "Done". On failure it carries the
        // REAL error message (extracted from the {success:false,error:"..."} payload),
        // not a bare "Error" placeholder, so a client reading only the text channel
        // still sees WHY it failed. structuredContent stays the pure machine payload.
        String text = isError ? buildErrorText(structuredContent) : buildSuccessDigest(structuredContent);
        result.content.add(ContentItem.text(text));
        result.structuredContent = structuredContent;
        if (isError)
        {
            result.isError = Boolean.TRUE;
        }
        return result;
    }

    /**
     * Derives the {@code content[0].text} for a FAILED tool result: the real error
     * message carried in the {@code error} field of the {@code {success:false,
     * error:"..."}} payload, so a client (or the model) that reads only the text
     * channel still sees the reason. Falls back to the literal {@code "Error"} when
     * the payload has no usable error string. The {@code structuredContent} itself is
     * left untouched (it stays the pure machine payload).
     *
     * @param structuredContent the structured error payload (typically a Gson
     *            {@link JsonElement}); may be {@code null}
     * @return the real error message, or {@code "Error"} when none is available
     */
    static String buildErrorText(Object structuredContent)
    {
        if (structuredContent instanceof JsonElement)
        {
            JsonElement element = (JsonElement)structuredContent;
            if (element.isJsonObject())
            {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has(McpKeys.ERROR) && obj.get(McpKeys.ERROR).isJsonPrimitive())
                {
                    String message = obj.get(McpKeys.ERROR).getAsString();
                    if (message != null && !message.isEmpty())
                    {
                        return message;
                    }
                }
            }
        }
        return "Error"; //$NON-NLS-1$
    }

    /**
     * Derives a compact, bounded, human-readable digest of a successful JSON
     * result for the {@code content[0].text} fallback. Generic: it inspects only
     * the shape of the structured content (top-level keys, a primary array's
     * size) without any per-tool knowledge, so it works for every JSON tool. The
     * result never exceeds {@link #DIGEST_MAX_LENGTH} characters and is truncated
     * with an ellipsis when longer. The full data always stays in
     * {@code structuredContent}.
     *
     * @param structuredContent the structured content (typically a Gson
     *            {@link JsonElement}); may be {@code null}
     * @return a non-empty digest string, never the literal {@code "Done"}
     */
    static String buildSuccessDigest(Object structuredContent)
    {
        String digest = describe(structuredContent);
        if (digest == null || digest.isEmpty())
        {
            // Fallback for an unrecognized shape: still better than nothing.
            digest = "OK"; //$NON-NLS-1$
        }
        return truncate(digest, DIGEST_MAX_LENGTH);
    }

    /**
     * Builds the un-truncated digest for the supported structured-content shapes.
     */
    private static String describe(Object structuredContent)
    {
        if (!(structuredContent instanceof JsonElement))
        {
            return "OK"; //$NON-NLS-1$
        }

        JsonElement element = (JsonElement)structuredContent;
        if (element.isJsonObject())
        {
            return describeObject(element.getAsJsonObject());
        }
        if (element.isJsonArray())
        {
            return "OK - " + element.getAsJsonArray().size() + " item(s)"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (element.isJsonPrimitive())
        {
            return element.getAsJsonPrimitive().getAsString();
        }
        // JSON null
        return "OK"; //$NON-NLS-1$
    }

    /**
     * Summarizes a JSON object: a leading OK plus its top-level keys, and the
     * size of the first array-valued field (a common "primary collection"), e.g.
     * {@code "OK - modules: 12 - keys: project, modules"}.
     */
    private static String describeObject(JsonObject obj)
    {
        StringBuilder sb = new StringBuilder("OK"); //$NON-NLS-1$

        // Highlight the first array-valued field as the primary collection count.
        for (Map.Entry<String, JsonElement> entry : obj.entrySet())
        {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonArray())
            {
                sb.append(" - ").append(entry.getKey()) //$NON-NLS-1$
                    .append(": ").append(((JsonArray)value).size()); //$NON-NLS-1$
                break;
            }
        }

        if (!obj.entrySet().isEmpty())
        {
            sb.append(" - keys: "); //$NON-NLS-1$
            boolean first = true;
            for (String key : obj.keySet())
            {
                if (!first)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(key);
                first = false;
            }
        }
        return sb.toString();
    }

    /**
     * Truncates to {@code max} characters, appending an ellipsis when cut.
     */
    private static String truncate(String text, int max)
    {
        if (text.length() <= max)
        {
            return text;
        }
        // U+2026 HORIZONTAL ELLIPSIS, built from its code point so the source
        // stays pure ASCII and is safe under a non-UTF-8 Tycho build.
        String ellipsis = String.valueOf((char)0x2026);
        return text.substring(0, Math.max(0, max - ellipsis.length())) + ellipsis;
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

    public Boolean getIsError()
    {
        return isError;
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
