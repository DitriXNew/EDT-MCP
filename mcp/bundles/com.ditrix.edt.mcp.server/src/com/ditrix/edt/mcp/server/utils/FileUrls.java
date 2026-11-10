/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.URIUtil;

/**
 * Converts {@code file:} URLs that come from the Eclipse platform into filesystem paths.
 * <p>
 * Eclipse locations usually carry a raw, unencoded URL ({@code file:/C:/Program Files/...}), which
 * {@link URL#toURI()} rejects, and which is not a URI at all when the path holds a space, a bare
 * {@code %} or a {@code #}. A correctly encoded URL is possible as well, and the same text can be
 * either form, so the two are told apart by the filesystem:
 * <ol>
 * <li>when the URL parses as a URI and its strictly decoded path exists, that path is returned, as
 * {@code Path.of(url.toURI())} always did: a location that resolved before resolves the same way,
 * even when a sibling carries the literal spelling;</li>
 * <li>otherwise the URL text is read <b>literally</b>, which covers the raw URLs the strict
 * conversion rejects (a space, a bare {@code %}) and a directory really named {@code a%20b} with no
 * {@code a b} beside it;</li>
 * <li>when neither exists the literal path is returned, and the caller's own access to it reports
 * the path as the platform spelled it.</li>
 * </ol>
 */
public final class FileUrls
{
    private static final String FILE_PROTOCOL = "file";

    private FileUrls()
    {
    }

    /**
     * Resolves a {@code file:} URL to a path: the strict decoding when it exists, else the literal
     * reading (see the class comment).
     *
     * @param url the URL to convert
     * @return the path the URL denotes
     * @throws IOException when the URL is {@code null}, is not a {@code file:} URL, or cannot be
     *         represented as a path
     */
    public static Path toPath(URL url) throws IOException
    {
        if (url == null)
        {
            throw new IOException("The file URL is not available");
        }
        if (!FILE_PROTOCOL.equals(url.getProtocol()))
        {
            throw new IOException("Not a file URL: " + url);
        }
        Path strict = strictPath(url);
        if (strict != null && Files.exists(strict))
        {
            return strict;
        }
        return literalPath(url);
    }

    /**
     * Reads the URL text as a literal path: nothing in it is treated as an escape.
     *
     * @param url a {@code file:} URL
     * @return the literal path
     * @throws IOException when the URL cannot be represented as a path
     */
    static Path literalPath(URL url) throws IOException
    {
        try
        {
            return Path.of(URIUtil.toURI(url));
        }
        catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e)
        {
            throw new IOException("Cannot convert the URL to a path: " + url, e);
        }
    }

    /** Decodes the URL as a well-formed URI; {@code null} when it is not one. */
    private static Path strictPath(URL url)
    {
        try
        {
            return Path.of(url.toURI());
        }
        catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e)
        {
            return null;
        }
    }
}
