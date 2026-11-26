// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import com.ditrix.edt.mcp.server.utils.SpreadsheetRenameGuard.LanguageKey;

/** Descriptor-relative reader for saved spreadsheet headers in a rename. */
final class ProjectSpreadsheetReader
{
    private static final int MAX_PATH_DEPTH = 128;
    enum Availability { SECURE, UNSUPPORTED_PROVIDER, UNVERIFIED }

    record Evidence(Availability availability, LanguageKey loss, Map<LanguageKey, String> descriptions)
    {
        Evidence(Availability availability, LanguageKey loss)
        {
            this(availability, loss, Map.of());
        }

        static Evidence securelyRead(Map<LanguageKey, String> descriptions)
        {
            return new Evidence(Availability.SECURE, descriptions.keySet().stream().findFirst().orElse(null),
                Collections.unmodifiableMap(new LinkedHashMap<>(descriptions)));
        }
    }

    private ProjectSpreadsheetReader()
    {
    }

    static Evidence read(Path project, Path file, Set<LanguageKey> blank)
    {
        Path owner = project.toAbsolutePath().normalize();
        Path source = file.toAbsolutePath().normalize();
        if (!source.startsWith(owner) || source.equals(owner) || source.getNameCount() > MAX_PATH_DEPTH)
        {
            return new Evidence(Availability.UNVERIFIED, null);
        }

        // Start from the filesystem anchor, not a checked-then-opened project pathname.
        // The descriptor-relative traversal rejects links in every component.
        try (DirectoryStream<Path> anchor = Files.newDirectoryStream(owner.getRoot()))
        {
            if (!(anchor instanceof SecureDirectoryStream<Path> safe))
            {
                return new Evidence(Availability.UNSUPPORTED_PROVIDER, null);
            }
            return Evidence.securelyRead(descriptions(safe, source, 0, blank));
        }
        catch (XMLStreamException e)
        {
            // A safe open is not evidence if the language header itself could not be read.
            return new Evidence(Availability.UNVERIFIED, null);
        }
        catch (UnsupportedOperationException e)
        {
            return new Evidence(Availability.UNSUPPORTED_PROVIDER, null);
        }
        catch (IOException e)
        {
            // Do not downgrade to a separate path-based check/open.
            return new Evidence(Availability.UNVERIFIED, null);
        }
    }

    private static Map<LanguageKey, String> descriptions(SecureDirectoryStream<Path> directory, Path file, int index,
        Set<LanguageKey> blank) throws IOException, XMLStreamException
    {
        Path component = file.getName(index);
        if (index + 1 < file.getNameCount())
        {
            try (var child = directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS))
            {
                return descriptions(child, file, index + 1, blank);
            }
        }

        BasicFileAttributeView attrs =
            directory.getFileAttributeView(component, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs == null || !attrs.readAttributes().isRegularFile())
        {
            throw new IOException("Saved spreadsheet is not a verified regular file"); //$NON-NLS-1$
        }
        try (var channel = directory.newByteChannel(component, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        {
            return SpreadsheetRenameGuard.savedDescriptions(Channels.newInputStream(channel), blank);
        }
    }
}
