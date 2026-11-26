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

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Cube;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DimensionTable;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataSource;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.LanguageInfo;
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
    public void repairRestoresOnlyTheExactSavedMissingDescription()
    {
        var book = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        var settings = MoxelFactory.eINSTANCE.createLanguageSettings();
        var russian = languageInfo("ru", "Russian", "");
        var english = languageInfo("en", "English", "Custom English");
        settings.getLanguages().add(russian);
        settings.getLanguages().add(english);
        book.setLanguageSettings(settings);

        SpreadsheetRenameRepair.restore(book, Map.of(new SpreadsheetRenameGuard.LanguageKey("ru", "Russian"),
            "  Сохранено & без изменений  "));

        assertEquals("  Сохранено & без изменений  ", russian.getDescription());
        assertEquals("Custom English", english.getDescription());
    }

    @Test
    public void repairDoesNotPartiallyApplyWhenAnotherLanguageHasChanged()
    {
        var book = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        var settings = MoxelFactory.eINSTANCE.createLanguageSettings();
        var russian = languageInfo("ru", "Russian", "");
        var english = languageInfo("en", "English", "New editor text");
        settings.getLanguages().add(russian);
        settings.getLanguages().add(english);
        book.setLanguageSettings(settings);

        assertThrows(IllegalStateException.class, () -> SpreadsheetRenameRepair.restore(book, Map.of(
            new SpreadsheetRenameGuard.LanguageKey("ru", "Russian"), "Saved Russian",
            new SpreadsheetRenameGuard.LanguageKey("en", "English"), "Old English")));

        assertEquals("", russian.getDescription());
        assertEquals("New editor text", english.getDescription());
    }

    private static LanguageInfo languageInfo(String id, String code, String description)
    {
        var value = MoxelFactory.eINSTANCE.createLanguageInfo();
        value.setLanguageId(id);
        value.setLanguageCode(code);
        value.setDescription(description);
        return value;
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
        assertThrows(XMLStreamException.class,
            () -> SpreadsheetRenameGuard.lostDescription(bytes(source.replace(NS, "urn:other")), Set.of(RUSSIAN)));
        assertThrows(XMLStreamException.class, () ->
            SpreadsheetRenameGuard.lostDescription(bytes(source.replace("languageSettings", "rows")), Set.of(RUSSIAN)));
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
    public void unverifiedEvidenceRefusesOnlyAnIdentifiedCandidateInEitherProject()
    {
        var candidate = new Candidate("Invoice", null, Set.of(RUSSIAN));
        for (boolean extension : List.of(false, true))
        {
            String refusal = SpreadsheetRenameGuard.refusal("Example", "Catalog.Products", List.of(candidate),
                extension, item -> new ProjectSpreadsheetReader.Evidence(
                    ProjectSpreadsheetReader.Availability.UNVERIFIED, null));
            assertTrue(refusal, refusal.contains("cannot be verified safely"));
            assertTrue(refusal, refusal.contains("Nothing was renamed"));
            assertTrue(refusal, !refusal.contains("would erase"));
            assertNull(SpreadsheetRenameGuard.refusal("Example", "Catalog.Products", List.of(), extension,
                item -> { throw new AssertionError("No blank candidate: no file read"); }));
        }
    }

    @Test
    public void missingOrMalformedSavedEvidenceIsUnverified() throws Exception
    {
        Path project = temporary.newFolder("project").toPath().toRealPath();
        Path template = project.resolve("Template.mxlx");
        Path control = Files.createFile(project.resolve("Control.mxlx"));
        Assume.assumeTrue(ProjectSpreadsheetReader.read(project, control, Set.of(RUSSIAN)).availability()
            != ProjectSpreadsheetReader.Availability.UNSUPPORTED_PROVIDER);

        assertEquals(ProjectSpreadsheetReader.Availability.UNVERIFIED,
            ProjectSpreadsheetReader.read(project, template, Set.of(RUSSIAN)).availability());
        Files.writeString(template, "<document xmlns='" + NS + "'><languageSettings><languageInfo>");
        assertEquals(ProjectSpreadsheetReader.Availability.UNVERIFIED,
            ProjectSpreadsheetReader.read(project, template, Set.of(RUSSIAN)).availability());
        assertEquals(ProjectSpreadsheetReader.Availability.UNVERIFIED,
            ProjectSpreadsheetReader.read(project, Files.createDirectory(project.resolve("directory.mxlx")),
                Set.of(RUSSIAN)).availability());
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
        when(processor.eClass()).thenReturn(MdClassPackage.Literals.DATA_PROCESSOR);
        when(processor.eGet(MdClassPackage.Literals.DATA_PROCESSOR__TEMPLATES, false)).thenReturn(List.of(template));
        when(external.eClass()).thenReturn(MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR);
        when(external.eGet(MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR__TEMPLATES, false))
            .thenReturn(List.of(template));

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
        when(processor.eClass()).thenReturn(MdClassPackage.Literals.DATA_PROCESSOR);
        when(processor.eGet(MdClassPackage.Literals.DATA_PROCESSOR__TEMPLATES, false)).thenReturn(List.of(template));
        var unrelated = mock(CommonModule.class);
        when(unrelated.eClass()).thenReturn(MdClassPackage.Literals.COMMON_MODULE);
        assertTrue(!SpreadsheetRenameGuard.mayOwnSpreadsheets(unrelated));
        for (var owner : List.of(processor, unrelated))
        {
            assertNull(SpreadsheetRenameGuard.scanOwner(null, owner, null, null)
                .refusal("Example", "DataProcessor.Processor", false));
        }
        verify(template, never()).getTemplate();
    }

    @Test
    public void catalogAndNestedExternalDataOwnersAreInScope()
    {
        var template = mock(Template.class);
        when(template.getTemplateType()).thenReturn(TemplateType.SPREADSHEET_DOCUMENT);
        var catalog = mock(Catalog.class);
        when(catalog.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(catalog.eGet(MdClassPackage.Literals.CATALOG__TEMPLATES, false)).thenReturn(List.of(template));
        assertTrue(SpreadsheetRenameGuard.mayOwnSpreadsheets(catalog));
        assertTrue(SpreadsheetRenameGuard.scanOwner(null, catalog, null, null).generatorMissing());

        var table = mock(DimensionTable.class);
        when(table.eClass()).thenReturn(MdClassPackage.Literals.DIMENSION_TABLE);
        when(table.eGet(MdClassPackage.Literals.DIMENSION_TABLE__TEMPLATES, false)).thenReturn(List.of(template));
        var cube = mock(Cube.class);
        when(cube.eClass()).thenReturn(MdClassPackage.Literals.CUBE);
        when(cube.getDimensionTables()).thenReturn(new BasicEList<>(List.of(table)));
        var source = mock(ExternalDataSource.class);
        when(source.eClass()).thenReturn(MdClassPackage.Literals.EXTERNAL_DATA_SOURCE);
        when(source.getTables()).thenReturn(new BasicEList<>());
        when(source.getCubes()).thenReturn(new BasicEList<>(List.of(cube)));
        assertTrue(SpreadsheetRenameGuard.mayOwnSpreadsheets(source));
        assertTrue(SpreadsheetRenameGuard.scanOwner(null, source, null, null).generatorMissing());
        verify(template, never()).getTemplate();
    }

    @Test
    public void directOwnedAndCommonTemplateRenamesCheckTheirOwnContent()
    {
        var owned = mock(Template.class);
        var common = mock(CommonTemplate.class);
        when(owned.getTemplateType()).thenReturn(TemplateType.SPREADSHEET_DOCUMENT);
        when(common.getTemplateType()).thenReturn(TemplateType.SPREADSHEET_DOCUMENT);
        for (var template : List.of(owned, common))
        {
            assertTrue(SpreadsheetRenameGuard.mayOwnSpreadsheets(template));
            assertTrue(SpreadsheetRenameGuard.scanOwner(null, template, null, null).generatorMissing());
            verify(template, never()).getTemplate();
        }
        assertTrue(!SpreadsheetRenameGuard.mayOwnSpreadsheets(mock(Configuration.class)));
    }

    @Test
    public void containedTemplateOwnerUsesTheNativeUriInTheDependentProject()
    {
        var owner = mock(DimensionTable.class, withSettings().extraInterfaces(IBmObject.class));
        var bmOwner = (IBmObject)owner;
        String root = "ExternalDataSource.Reporting";
        String containment = "cubes.Cube.dimensionTables.Dimension";
        when(owner.getUuid()).thenReturn(UUID.randomUUID());
        when(owner.eClass()).thenReturn(MdClassPackage.Literals.DIMENSION_TABLE);
        when(bmOwner.bmGetUri()).thenReturn(BmUriUtil.createContainedBmObjectUri("base", root, containment));

        assertEquals(BmUriUtil.createContainedBmObjectUri("extension", root, containment),
            SpreadsheetRenameOwners.baseIdentity(owner, bmOwner).extensionUri("extension"));
    }

    @Test
    public void counterpartRequiresExactAdoptionLinkAndStableCompatibleIdentity()
    {
        var original = UUID.randomUUID();
        var expected = UUID.randomUUID();
        var catalog = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        var uri = BmUriUtil.createTopBmObjectUri("extension", "Catalog.Products");
        var project = mock(IProject.class);
        var tx = mock(IBmTransaction.class);
        when(catalog.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(catalog.getUuid()).thenReturn(expected);
        when(tx.getObjectByUri(uri)).thenReturn((IBmObject)catalog);

        assertNull(SpreadsheetRenameOwners.counterpart(project, uri, original, MdClassPackage.Literals.CATALOG,
            (IBmObject)catalog));
        when(catalog.getExtendedConfigurationObject()).thenReturn(original);
        assertThrows(IllegalStateException.class, () -> SpreadsheetRenameOwners.counterpart(project, uri, original,
            MdClassPackage.Literals.DATA_PROCESSOR, (IBmObject)catalog));
        var target = SpreadsheetRenameOwners.counterpart(project, uri, original, MdClassPackage.Literals.CATALOG,
            (IBmObject)catalog);
        assertEquals(expected, target.uuid());
        assertEquals(catalog, target.resolve(tx, MdClassPackage.Literals.CATALOG));
        when(catalog.getUuid()).thenReturn(UUID.randomUUID());
        assertNull(target.resolve(tx, MdClassPackage.Literals.CATALOG));
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
        when(owner.eClass()).thenReturn(MdClassPackage.Literals.DATA_PROCESSOR);
        when(owner.eGet(MdClassPackage.Literals.DATA_PROCESSOR__TEMPLATES, false)).thenReturn(List.of(template));
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
