// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.SpreadsheetRenameGuard.LanguageKey;

public class SpreadsheetRenameGuardTest
{
    private static final String NS = "http://v8.1c.ru/8.2/data/spreadsheet";
    private static final LanguageKey RUSSIAN = new LanguageKey("ru", "Русский");

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private static ByteArrayInputStream bytes(String source)
    {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    private static String saved(String description)
    {
        return "<document xmlns='" + NS + "'><languageSettings>"
            + "<currentLanguage>en</currentLanguage><defaultLanguage>ru</defaultLanguage>"
            + "<languageInfo><id>en</id><code>English</code><description>English</description></languageInfo>"
            + "<languageInfo><id>ru</id><code>Русский</code>" + description + "</languageInfo>"
            + "</languageSettings></document>";
    }

    @Test
    public void savedDescriptionWouldBeLostOnlyForMatchingBlankIdAndCode() throws Exception
    {
        String source = saved("<description>Русский &amp; extra</description>");
        assertEquals(RUSSIAN, SpreadsheetRenameGuard.lostDescription(bytes(source), Set.of(RUSSIAN)));
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(source), Set.of(new LanguageKey("ru", "Russian"))));
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(source), Set.of(new LanguageKey("en", "Русский"))));
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(source), Set.of()));
    }

    @Test
    public void absentOrAlreadyEmptyOnDiskIsNotLoss() throws Exception
    {
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(saved("<description/>")), Set.of(RUSSIAN)));
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(saved("")), Set.of(RUSSIAN)));
    }

    @Test
    public void sameNamesInUnrelatedXmlAreNotLanguageSettings() throws Exception
    {
        String source = saved("<description>Русский</description>");
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(source.replace(NS, "urn:other")), Set.of(RUSSIAN)));
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(source.replace("languageSettings", "rows")),
            Set.of(RUSSIAN)));
    }

    @Test
    public void declarationWithAnExternalEntityIsRejected() throws Exception
    {
        String source = "<!DOCTYPE document [<!ENTITY x SYSTEM 'https://example.invalid/external'>]>"
            + saved("<description>&x;</description>");
        assertThrows(XMLStreamException.class, () -> SpreadsheetRenameGuard.lostDescription(bytes(source),
            Set.of(RUSSIAN)));
    }

    @Test
    public void malformedOrTruncatedLanguageHeaderCannotProveLoss()
    {
        String source = saved("<description>Русский</description>");
        assertThrows(XMLStreamException.class, () -> SpreadsheetRenameGuard.lostDescription(
            bytes(source.substring(0, source.indexOf("</languageSettings>"))), Set.of(RUSSIAN)));
    }

    @Test
    public void verificationNeverScansLargeCellContents() throws Exception
    {
        String source = saved("<description>Русский</description>").replace("</document>",
            "<rows>" + "x".repeat(1024 * 1024) + "</rows></document>");
        ByteArrayInputStream input = bytes(source);
        assertEquals(RUSSIAN, SpreadsheetRenameGuard.lostDescription(input, Set.of(RUSSIAN)));
        assertTrue("only the bounded language header is read", input.available() >= 896 * 1024);
    }

    @Test
    public void onlyFilesWithinTheOwningProjectAreEligible() throws Exception
    {
        Path root = temporary.newFolder("project").toPath();
        Path inside = Files.createFile(root.resolve("Template.mxlx"));
        Path outside = temporary.newFile("other.mxlx").toPath();
        assertEquals(inside.toRealPath(), SpreadsheetRenameGuard.projectFile(root, inside));
        assertNull(SpreadsheetRenameGuard.projectFile(root, outside));
        assertNull(SpreadsheetRenameGuard.projectFile(root, root));
    }

    @Test
    public void symbolicLinksAreNotFollowedEvenInsideTheProject() throws Exception
    {
        Path root = temporary.newFolder("project").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Files.createFile(outside.resolve("Template.mxlx"));
        Path link = root.resolve("TemplateLink");
        try
        {
            Files.createSymbolicLink(link, outside);
        }
        catch (IOException | UnsupportedOperationException | SecurityException e)
        {
            Assume.assumeNoException(e);
        }
        assertNull(SpreadsheetRenameGuard.projectFile(root, link.resolve("Template.mxlx")));
    }
}
