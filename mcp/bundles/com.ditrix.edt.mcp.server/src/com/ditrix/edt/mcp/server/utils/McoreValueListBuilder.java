/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com.google.gson.JsonElement;

/**
 * Parses a wire array for a many-valued containment of mcore {@code Value} objects. An existing
 * configuration XDTO-package FQN becomes a reference entry; a platform namespace URI becomes a
 * string entry. This helper only resolves and classifies entries: the caller reduces reference
 * targets to BM ids and creates the actual mcore values inside its write transaction.
 *
 * <p>The namespace test is deliberately conservative. Only absolute {@code http://} and
 * {@code https://} URIs with a host, and non-empty {@code urn:} URIs, are accepted; whitespace is
 * refused. Consequently a misspelled metadata FQN cannot silently become a StringValue.</p>
 */
public final class McoreValueListBuilder
{
    /** Canonical FQN prefix of a configuration XDTO package. */
    public static final String XDTO_PREFIX = "XDTOPackage."; //$NON-NLS-1$

    /** One ordered list entry: exactly one of {@link #namespaceUri}/{@link #referenceTarget} is set. */
    public static final class Item
    {
        /** A platform namespace URI to store in a StringValue. */
        public final String namespaceUri;

        /** A live configuration XDTO package; the caller must retain only its BM id. */
        public final XDTOPackage referenceTarget;

        private Item(String namespaceUri, XDTOPackage referenceTarget)
        {
            this.namespaceUri = namespaceUri;
            this.referenceTarget = referenceTarget;
        }

        /** Creates a platform-namespace entry. */
        public static Item namespace(String namespaceUri)
        {
            return new Item(namespaceUri, null);
        }

        /** Creates a configuration-package reference entry. */
        public static Item reference(XDTOPackage target)
        {
            return new Item(null, target);
        }
    }

    /** An ordered parsed list, or an actionable error. Exactly one field is non-null. */
    public static final class Result
    {
        /** Parsed entries in caller-supplied order. */
        public final List<Item> items;

        /** Actionable validation/resolution error, or {@code null} on success. */
        public final String error;

        private Result(List<Item> items, String error)
        {
            this.items = items;
            this.error = error;
        }

        static Result ok(List<Item> items)
        {
            return new Result(Collections.unmodifiableList(new ArrayList<>(items)), null);
        }

        static Result error(String error)
        {
            return new Result(null, error);
        }
    }

    private McoreValueListBuilder()
    {
        // utility class
    }

    /**
     * Parses an ordered JSON array of XDTO-package FQNs and platform namespace URIs.
     *
     * @param raw wire value
     * @param scope metadata resolution root
     * @return parsed entries or an actionable error
     */
    public static Result build(JsonElement raw, MetadataScope scope)
    {
        if (raw == null || !raw.isJsonArray())
        {
            String value = raw == null || raw.isJsonNull() ? "null" : raw.toString(); //$NON-NLS-1$
            return Result.error("Mcore Value-list value " + value + " is invalid. Provide a JSON " //$NON-NLS-1$ //$NON-NLS-2$
                + "array of strings. Use an existing " //$NON-NLS-1$
                + "configuration package FQN such as 'XDTOPackage.PackageName', or a platform " //$NON-NLS-1$
                + "namespace URI beginning with 'http://', 'https://', or 'urn:'."); //$NON-NLS-1$
        }

        List<Item> items = new ArrayList<>();
        for (JsonElement element : raw.getAsJsonArray())
        {
            String value = strictString(element);
            if (value == null || value.isEmpty())
            {
                return invalidEntry(element == null ? "null" : element.toString()); //$NON-NLS-1$
            }

            MetadataNodeResolver.MetadataNode node = null;
            try
            {
                node = MetadataNodeResolver.resolveExisting(scope, value);
            }
            catch (RuntimeException e)
            {
                // URI classification below remains available when a non-FQN makes a resolver reject.
                node = null;
            }
            if (node != null && node.object instanceof XDTOPackage)
            {
                items.add(Item.reference((XDTOPackage)node.object));
            }
            else if (isSupportedNamespaceUri(value))
            {
                items.add(Item.namespace(value));
            }
            else
            {
                return invalidEntry("'" + value + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return Result.ok(items);
    }

    private static String strictString(JsonElement raw)
    {
        return raw != null && !raw.isJsonNull() && raw.isJsonPrimitive()
            && raw.getAsJsonPrimitive().isString() ? raw.getAsString() : null;
    }

    private static Result invalidEntry(String displayedValue)
    {
        return Result.error("Mcore Value-list entry " + displayedValue //$NON-NLS-1$
            + " could not be resolved " //$NON-NLS-1$
            + "as a configuration XDTO package and is not a supported namespace URI. Use an " //$NON-NLS-1$
            + "existing 'XDTOPackage.<Name>' FQN (the type token may also be Russian), or a " //$NON-NLS-1$
            + "platform namespace URI beginning with 'http://', 'https://', or 'urn:'."); //$NON-NLS-1$
    }

    private static boolean isSupportedNamespaceUri(String value)
    {
        if (value == null || value.isEmpty() || containsWhitespace(value))
        {
            return false;
        }
        try
        {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null)
            {
                return false;
            }
            switch (scheme.toLowerCase(Locale.ROOT))
            {
                case "http": //$NON-NLS-1$
                case "https": //$NON-NLS-1$
                    return uri.getHost() != null && !uri.getHost().isEmpty();
                case "urn": //$NON-NLS-1$
                {
                    String part = uri.getRawSchemeSpecificPart();
                    return part != null && !part.isEmpty();
                }
                default:
                    return false;
            }
        }
        catch (URISyntaxException e)
        {
            return false;
        }
    }

    private static boolean containsWhitespace(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            if (Character.isWhitespace(value.charAt(i)))
            {
                return true;
            }
        }
        return false;
    }
}
