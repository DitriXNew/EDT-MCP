// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.stream.XMLStreamException;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.URI;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com.ditrix.edt.mcp.server.utils.SpreadsheetRenameGuard.Candidate;
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
        var candidate = new Candidate("Invoice", null, Set.of(RUSSIAN));
        String refusal = SpreadsheetRenameGuard.refusal("Base", "DataProcessor.Processor", List.of(candidate),
            false, item -> ProjectSpreadsheetReader.read(project, template, item.blank()));
        assertTrue(refusal, refusal.contains("would erase the saved language description"));
        Files.writeString(template, saved("<description/>"));
        assertNull(SpreadsheetRenameGuard.refusal("Base", "DataProcessor.Processor", List.of(candidate),
            false, item -> ProjectSpreadsheetReader.read(project, template, item.blank())));
    }

    @Test
    public void missingOrMalformedSavedEvidenceDoesNotInventLossForTheAddressedOwner() throws Exception
    {
        Path project = temporary.newFolder("project").toPath().toRealPath();
        Path template = project.resolve("Template.mxlx");
        Path control = Files.createFile(project.resolve("Control.mxlx"));
        Assume.assumeTrue(ProjectSpreadsheetReader.read(project, control, Set.of(RUSSIAN)).availability()
            != ProjectSpreadsheetReader.Availability.UNSUPPORTED_PROVIDER);
        var candidate = new Candidate("Invoice", null, Set.of(RUSSIAN));

        var missing = ProjectSpreadsheetReader.read(project, template, candidate.blank());
        assertEquals(ProjectSpreadsheetReader.Availability.UNVERIFIED, missing.availability());
        assertNull(SpreadsheetRenameGuard.refusal("Base", "DataProcessor.Processor", List.of(candidate),
            false, item -> missing));
        String extensionRefusal = SpreadsheetRenameGuard.refusal("Extension", "DataProcessor.Processor",
            List.of(candidate), true, item -> missing);
        assertTrue(extensionRefusal, extensionRefusal.contains("cannot be verified safely"));

        Files.writeString(template, "<document xmlns='" + NS + "'><languageSettings><languageInfo>");
        var malformed = ProjectSpreadsheetReader.read(project, template, candidate.blank());
        assertEquals(ProjectSpreadsheetReader.Availability.SECURE, malformed.availability());
        assertNull(malformed.loss());
        assertNull(SpreadsheetRenameGuard.refusal("Base", "DataProcessor.Processor", List.of(candidate),
            false, item -> malformed));
    }

    @Test
    public void nonSecureProviderRefusesOnlyCandidatesAndNeverModifiesTheirFiles() throws Exception
    {
        Path archive = temporary.getRoot().toPath().resolve("project.zip");
        try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true")))
        {
            Path project = Files.createDirectory(filesystem.getPath("/project"));
            Path template = project.resolve("Template.mxlx");
            Files.writeString(template, saved("<description>Русский</description>"));
            byte[] before = Files.readAllBytes(template);
            var read = ProjectSpreadsheetReader.read(project, template, Set.of(RUSSIAN));
            assertEquals(ProjectSpreadsheetReader.Availability.UNSUPPORTED_PROVIDER, read.availability());
            assertNull(read.loss());

            // Like the stock Windows provider, ZIPFS provides DirectoryStream but not
            // SecureDirectoryStream. Both owners must refuse without trying an alternate read.
            var candidate = new Candidate("Invoice", null, Set.of(RUSSIAN));
            for (boolean extension : List.of(false, true))
            {
                String refusal = SpreadsheetRenameGuard.refusal("Example", "DataProcessor.Processor",
                    List.of(candidate), extension,
                    item -> ProjectSpreadsheetReader.read(project, template, item.blank()));
                assertTrue(refusal, refusal.contains("saved description cannot be verified safely"));
                assertTrue(refusal, refusal.contains("Nothing was renamed"));
                assertTrue(refusal, refusal.contains("Invoice") && refusal.contains("Example"));
                assertTrue(refusal, !refusal.contains("would erase"));
            }
            assertNull(SpreadsheetRenameGuard.refusal("Example", "DataProcessor.Processor", List.of(), false,
                candidateWithoutBlankKey -> { throw new AssertionError("No candidate should read a file"); }));
            assertArrayEquals(before, Files.readAllBytes(template));
        }
    }

    @Test
    public void onlyFilesWithinTheOwningProjectAreEligible() throws Exception
    {
        Path root = temporary.newFolder("project").toPath();
        Path outside = temporary.newFile("other.mxlx").toPath();
        assertEquals(ProjectSpreadsheetReader.Availability.UNVERIFIED,
            ProjectSpreadsheetReader.read(root, outside, Set.of(RUSSIAN)).availability());
        assertEquals(ProjectSpreadsheetReader.Availability.UNVERIFIED,
            ProjectSpreadsheetReader.read(root, root, Set.of(RUSSIAN)).availability());
    }

    @Test
    public void missingGeneratorRefusesOwnedSpreadsheetForBothOwnerKinds()
    {
        var processor = mock(DataProcessor.class);
        var external = mock(ExternalDataProcessor.class);
        var template = mock(Template.class);
        when(template.getTemplateType()).thenReturn(TemplateType.SPREADSHEET_DOCUMENT);
        when(processor.getTemplates()).thenReturn(new BasicEList<>(List.of(template)));
        when(external.getTemplates()).thenReturn(new BasicEList<>(List.of(template)));

        for (var owner : List.of(processor, external))
        {
            // No attempt to look up an attached workbook or read a source file without the service.
            var scan = SpreadsheetRenameGuard.scanOwner(null, owner, null, null);
            for (boolean extension : List.of(false, true))
            {
                String refusal = scan.refusal("Example", "DataProcessor.Processor", extension);
                assertTrue(refusal, refusal.contains("template naming service is unavailable"));
                assertTrue(refusal, refusal.contains("Nothing was renamed"));
            }
        }
        verify(template, never()).getTemplate();
    }

    @Test
    public void missingGeneratorDoesNotRefuseOtherOwnersOrNonSpreadsheetTemplates()
    {
        var processor = mock(DataProcessor.class);
        var template = mock(Template.class);
        when(template.getTemplateType()).thenReturn(TemplateType.TEXT_DOCUMENT);
        when(processor.getTemplates()).thenReturn(new BasicEList<>(List.of(template)));
        for (var owner : List.of(processor, mock(Catalog.class)))
        {
            assertNull(SpreadsheetRenameGuard.scanOwner(null, owner, null, null)
                .refusal("Example", "DataProcessor.Processor", false));
        }
        verify(template, never()).getTemplate();
    }

    @Test
    public void unattachedAndHealthyWorkbooksNeverResolveTheSourceFile()
    {
        var project = mock(IProject.class);
        var tx = mock(IBmTransaction.class);
        var generator = mock(ITopObjectFqnGenerator.class);
        var owner = mock(DataProcessor.class, withSettings().extraInterfaces(IBmObject.class));
        var template = mock(Template.class);
        var workbook = mock(SpreadsheetDocument.class, withSettings().extraInterfaces(IBmObject.class));
        var settings = MoxelFactory.eINSTANCE.createLanguageSettings();
        var language = MoxelFactory.eINSTANCE.createLanguageInfo();
        language.setLanguageId("ru");
        language.setLanguageCode("Русский");
        language.setDescription("Русский");
        settings.getLanguages().add(language);

        String fqn = "DataProcessor.Processor.Template.Invoice.Template";
        when(((IBmObject)owner).bmIsTop()).thenReturn(true);
        when(owner.getTemplates()).thenReturn(new BasicEList<>(List.of(template)));
        when(template.getTemplateType()).thenReturn(TemplateType.SPREADSHEET_DOCUMENT);
        when(generator.generateExternalPropertyFqn(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE))
            .thenReturn(fqn);
        when(((IBmObject)workbook).bmIsTop()).thenReturn(true);
        when(((IBmObject)workbook).bmGetNamespace()).thenReturn(mock(IBmNamespace.class));
        when(workbook.getLanguageSettings()).thenReturn(settings);
        when(tx.getTopObjectByFqn(fqn)).thenReturn(null, (IBmObject)workbook);

        var unattached = SpreadsheetRenameGuard.candidates(project, owner, tx, generator);
        var healthy = SpreadsheetRenameGuard.candidates(project, owner, tx, generator);
        assertTrue(unattached.isEmpty());
        assertTrue(healthy.isEmpty());
        verify(template, never()).getTemplate();
        // With no candidate there is no call into the saved-header reader.
        assertNull(SpreadsheetRenameGuard.refusal("Example", "DataProcessor.Processor", healthy, false,
            candidate -> { throw new AssertionError("Healthy rename must not ask for saved evidence"); }));
    }
}
