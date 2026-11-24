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
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import com.ditrix.edt.mcp.server.utils.SpreadsheetRenameGuard.LanguageKey;

/** Descriptor-relative reader for saved spreadsheet headers in a rename. */
final class ProjectSpreadsheetReader
{
    private static final int MAX_PATH_DEPTH = 128;
    enum Availability { SECURE, UNSUPPORTED_PROVIDER, UNVERIFIED }

    record Evidence(Availability availability, LanguageKey loss)
    {
        boolean safeReaderAvailable()
        {
            return availability == Availability.SECURE;
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
            return new Evidence(Availability.SECURE, loss(safe, source, 0, blank));
        }
        catch (XMLStreamException e)
        {
            // A securely opened but unsupported header does not establish a description loss.
            return new Evidence(Availability.SECURE, null);
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

    private static LanguageKey loss(SecureDirectoryStream<Path> directory, Path file, int index,
        Set<LanguageKey> blank) throws IOException, XMLStreamException
    {
        Path component = file.getName(index);
        if (index + 1 < file.getNameCount())
        {
            try (var child = directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS))
            {
                return loss(child, file, index + 1, blank);
            }
        }

        BasicFileAttributeView attrs =
            directory.getFileAttributeView(component, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs == null || !attrs.readAttributes().isRegularFile())
        {
            return null;
        }
        try (var channel = directory.newByteChannel(component, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        {
            return SpreadsheetRenameGuard.lostDescription(Channels.newInputStream(channel), blank);
        }
    }
}
