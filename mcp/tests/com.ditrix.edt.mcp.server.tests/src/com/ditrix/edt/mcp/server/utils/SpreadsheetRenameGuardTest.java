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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.stream.XMLStreamException;

import org.eclipse.emf.common.util.URI;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com._1c.g5.v8.dt.moxel.MoxelFactory;
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
    public void duplicateSavedPairNeverProvesLossButAnotherUniquePairCan() throws Exception
    {
        String source = saved("<description>Русский</description>").replace("</languageSettings>",
            "<languageInfo><id>ru</id><code>Русский</code><description/></languageInfo></languageSettings>");
        assertNull(SpreadsheetRenameGuard.lostDescription(bytes(source), Set.of(RUSSIAN)));
        var english = new LanguageKey("en", "English");
        assertEquals(english, SpreadsheetRenameGuard.lostDescription(bytes(source), Set.of(english, RUSSIAN)));
    }

    @Test
    public void duplicateModelPairNeverProvesLossEvenWithOneBlankEntry()
    {
        var first = MoxelFactory.eINSTANCE.createLanguageInfo();
        first.setLanguageId("ru");
        first.setLanguageCode("Русский");
        var second = MoxelFactory.eINSTANCE.createLanguageInfo();
        second.setLanguageId("ru");
        second.setLanguageCode("Русский");
        second.setDescription("Русский");
        assertEquals(Set.of(RUSSIAN), SpreadsheetRenameGuard.uniquelyBlank(List.of(first)));
        assertTrue(SpreadsheetRenameGuard.uniquelyBlank(List.of(first, second)).isEmpty());
        assertTrue(SpreadsheetRenameGuard.uniquelyBlank(List.of(second, first)).isEmpty());
    }

    @Test
    public void aDifferentExtensionCounterpartOrNativeGroupCountInvalidatesThePlan()
    {
        var original = new SpreadsheetRenameOwners.Counterpart(null, URI.createURI("urn:original"),
            UUID.randomUUID(), UUID.randomUUID());
        var replacement = new SpreadsheetRenameOwners.Counterpart(null, original.uri(),
            UUID.randomUUID(), original.baseUuid());
        var plan = new SpreadsheetRenameOwners.Plan(List.of(original));
        assertTrue(plan.covers(new SpreadsheetRenameOwners.Plan(List.of(original)), 2));
        assertTrue(!plan.covers(new SpreadsheetRenameOwners.Plan(List.of(original)), 1));
        assertTrue(!plan.covers(new SpreadsheetRenameOwners.Plan(List.of(replacement)), 2));
        assertTrue(!plan.covers(new SpreadsheetRenameOwners.Plan(null), 2));
    }

    @Test
    public void secureReaderFindsDescriptionInAnOrdinaryOwnedFileWhenSupported() throws Exception
    {
        Path project = temporary.newFolder("project").toPath().toRealPath();
        Path template = project.resolve("Template.mxlx");
        Files.writeString(template, saved("<description>Русский</description>"));
        var read = ProjectSpreadsheetReader.read(project, template, Set.of(RUSSIAN));
        Assume.assumeTrue(read.availability() != ProjectSpreadsheetReader.Availability.UNSUPPORTED_PROVIDER);
        assertEquals(ProjectSpreadsheetReader.Availability.SECURE, read.availability());
        assertEquals(RUSSIAN, read.loss());
    }

    @Test
    public void nonSecureProviderDoesNotReadTheCandidate() throws Exception
    {
        Path archive = temporary.getRoot().toPath().resolve("project.zip");
        try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true")))
        {
            Path project = Files.createDirectory(filesystem.getPath("/project"));
            Path template = project.resolve("Template.mxlx");
            Files.writeString(template, saved("<description>Русский</description>"));
            var read = ProjectSpreadsheetReader.read(project, template, Set.of(RUSSIAN));
            assertEquals(ProjectSpreadsheetReader.Availability.UNSUPPORTED_PROVIDER, read.availability());
            assertNull(read.loss());
        }
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
