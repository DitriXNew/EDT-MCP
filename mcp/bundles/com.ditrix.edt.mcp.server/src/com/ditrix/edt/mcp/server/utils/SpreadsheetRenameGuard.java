// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Cube;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataSource;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.LanguageInfo;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;

/** Protects an owned spreadsheet when EDT's editor has blanked its in-memory language description. */
public final class SpreadsheetRenameGuard
{
    private static final String MOXEL = "http://v8.1c.ru/8.2/data/spreadsheet"; //$NON-NLS-1$
    private static final int MAX_HEADER_BYTES = 128 * 1024;

    private SpreadsheetRenameGuard()
    {
    }

    record LanguageKey(String id, String code)
    {
    }

    record Candidate(String templateName, IFile file, Set<LanguageKey> blank, long workbookId)
    {
        Candidate(String templateName, IFile file, Set<LanguageKey> blank)
        {
            this(templateName, file, blank, IBmObject.BM_NULL_ID);
        }
    }

    record OwnerScan(List<Candidate> candidates, boolean generatorMissing)
    {
        String refusal(String projectName, String ownerFqn, boolean extension)
        {
            return refusal(projectName, ownerFqn, extension, null);
        }

        String refusal(String projectName, String ownerFqn, boolean extension, SpreadsheetRenameRepair.Scope repair)
        {
            return generatorMissing ? unavailableNames(projectName, ownerFqn)
                : SpreadsheetRenameGuard.refusal(projectName, ownerFqn, candidates, extension,
                    SpreadsheetRenameGuard::secureEvidence, repair);
        }
    }

    /**
     * Read the model and the saved header, never update either. The native moxel editor can clear
     * descriptions in memory during initialization when a synonym for the editing language is
     * absent; a later rename then makes that loss permanent (#1015).
     */
    public static String refusal(IProject project, MdObject owner, String ownerFqn,
        SpreadsheetRenameOwners.Plan planned, int groups)
    {
        return refusal(project, owner, ownerFqn, planned, groups, null);
    }

    public static String refusal(IProject project, MdObject owner, String ownerFqn,
        SpreadsheetRenameOwners.Plan planned, int groups, SpreadsheetRenameRepair.Scope repair)
    {
        if (HostKind.current() != HostKind.WINDOWED || !mayOwnSpreadsheets(owner))
        {
            return null;
        }
        var activator = Activator.getDefault();
        if (activator == null)
        {
            return unavailableNames(project.getName(), ownerFqn);
        }
        var generator = activator.getTopObjectFqnGenerator();
        String task = "Read spreadsheet language risk"; //$NON-NLS-1$
        OwnerScan base = ReadToolExecution.readModel(project, task, model -> BmTransactions.read(model, task,
            (tx, monitor) -> scanOwner(project, owner, tx, generator)));
        String baseRefusal = base.refusal(project.getName(), ownerFqn, false, groups == 1 ? repair : null);
        if (baseRefusal != null || groups == 1)
        {
            return baseRefusal;
        }
        if (planned == null || !planned.covers(SpreadsheetRenameOwners.capture(project, owner), groups))
        {
            return SpreadsheetRenameOwners.changedScope();
        }
        EClass ownerType = owner.eClass();
        for (var counterpart : planned.counterparts())
        {
            var model = BmModelResolver.resolve(counterpart.project());
            if (!model.isAvailable())
            {
                return SpreadsheetRenameOwners.changedScope();
            }
            OwnerScan extension = BmTransactions.read(model.getModel(), "Read extension spreadsheet language risk", //$NON-NLS-1$
                (tx, monitor) -> {
                    MdObject affectedOwner = counterpart.resolve(tx, ownerType);
                    if (affectedOwner == null)
                    {
                        return null;
                    }
                    return scanOwner(counterpart.project(), affectedOwner, tx, generator);
                });
            if (extension == null)
            {
                return SpreadsheetRenameOwners.changedScope();
            }
            String extensionRefusal = extension.refusal(counterpart.project().getName(), ownerFqn, true, null);
            if (extensionRefusal != null)
            {
                return extensionRefusal;
            }
        }
        return null;
    }

    private static String unavailableNames(String projectName, String ownerFqn)
    {
        return "Cannot verify the spreadsheet language descriptions for '" //$NON-NLS-1$
            + MetadataTypeUtils.toCanonicalEnglishFqn(ownerFqn) + "' in project '" + projectName //$NON-NLS-1$
            + "' because EDT's template naming service is unavailable. Nothing was renamed. " //$NON-NLS-1$
            + "Wait for EDT to finish loading and retry."; //$NON-NLS-1$
    }

    static OwnerScan scanOwner(IProject project, MdObject owner, IBmTransaction tx,
        ITopObjectFqnGenerator generator)
    {
        if (generator != null)
        {
            return new OwnerScan(candidates(project, owner, tx, generator), false);
        }
        // Without names we cannot distinguish a loaded workbook from an unloaded one. Only
        // owners with an actual spreadsheet template need this lookup; do not scan other owners.
        boolean hasSpreadsheet = ownedTemplates(owner).stream()
            .anyMatch(template -> template.getTemplateType() == TemplateType.SPREADSHEET_DOCUMENT);
        return new OwnerScan(List.of(), hasSpreadsheet);
    }

    static boolean mayOwnSpreadsheets(MdObject owner)
    {
        // Common templates are separate top-level models, not resources owned by the
        // configuration root (which is not a target of rename_metadata_object).
        return owner != null && !(owner instanceof Configuration)
            && (owner instanceof BasicTemplate || owner instanceof ExternalDataSource
                || templateContainments(owner.eClass()).findAny().isPresent());
    }

    private static java.util.stream.Stream<EReference> templateContainments(EClass type)
    {
        return type.getEAllContainments().stream()
            .filter(feature -> MdClassPackage.Literals.BASIC_TEMPLATE.isSuperTypeOf(feature.getEReferenceType()));
    }

    private static List<BasicTemplate> ownedTemplates(MdObject owner)
    {
        if (owner instanceof BasicTemplate template)
        {
            // A direct Template/CommonTemplate rename moves its own external content.
            return List.of(template);
        }
        List<BasicTemplate> result = new ArrayList<>();
        addDirectTemplates(owner, result);
        // Only these SDK model relationships nest one template-owning metadata object under
        // another target here. Exporting an external data source or cube can export those
        // descendants. Never climb to siblings or enumerate the configuration collection.
        if (owner instanceof ExternalDataSource source)
        {
            source.getTables().forEach(table -> addDirectTemplates(table, result));
            source.getCubes().forEach(cube -> {
                addDirectTemplates(cube, result);
                addDimensionTableTemplates(cube, result);
            });
        }
        else if (owner instanceof Cube cube)
        {
            addDimensionTableTemplates(cube, result);
        }
        return result;
    }

    private static void addDimensionTableTemplates(Cube cube, List<BasicTemplate> result)
    {
        cube.getDimensionTables().forEach(table -> addDirectTemplates(table, result));
    }

    private static void addDirectTemplates(MdObject owner, List<BasicTemplate> result)
    {
        templateContainments(owner.eClass()).forEach(feature -> {
            Object value = owner.eGet(feature, false);
            if (value instanceof Iterable<?> list)
            {
                for (Object item : list)
                {
                    if (item instanceof BasicTemplate template)
                    {
                        result.add(template);
                    }
                }
            }
            else if (value instanceof BasicTemplate template)
            {
                result.add(template);
            }
        });
    }

    static String refusal(String projectName, String ownerFqn, List<Candidate> candidates, boolean extension,
        Function<Candidate, ProjectSpreadsheetReader.Evidence> reader)
    {
        return refusal(projectName, ownerFqn, candidates, extension, reader, null);
    }

    private static String refusal(String projectName, String ownerFqn, List<Candidate> candidates, boolean extension,
        Function<Candidate, ProjectSpreadsheetReader.Evidence> reader, SpreadsheetRenameRepair.Scope repair)
    {
        List<SpreadsheetRenameRepair.Entry> entries = new ArrayList<>();
        for (Candidate candidate : candidates)
        {
            var evidence = reader.apply(candidate);
            if (evidence.availability() != ProjectSpreadsheetReader.Availability.SECURE)
            {
                return "EDT has a blank language description in spreadsheet template '" //$NON-NLS-1$
                    + candidate.templateName() + (extension ? "' in extension project '" : "' in project '") //$NON-NLS-1$ //$NON-NLS-2$
                    + projectName
                    + "', and its saved description cannot be verified safely. Nothing was renamed. " //$NON-NLS-1$
                    + "Preserve unsaved work. Close the spreadsheet editors before retrying. If the error " //$NON-NLS-1$
                    + "persists, use clean_project to reload the saved files without reopening the template."; //$NON-NLS-1$
            }
            LanguageKey lost = evidence.loss();
            if (lost != null)
            {
                if (repair != null && repair.canRestore() && candidate.workbookId() != IBmObject.BM_NULL_ID
                    && !evidence.descriptions().isEmpty())
                {
                    entries.add(new SpreadsheetRenameRepair.Entry(candidate.workbookId(), candidate.file(),
                        candidate.file().getModificationStamp(), evidence.descriptions()));
                    continue;
                }
                return "Renaming '" + MetadataTypeUtils.toCanonicalEnglishFqn(ownerFqn) //$NON-NLS-1$
                    + "' would erase the saved language description for '" + lost.id() //$NON-NLS-1$
                    + "' in spreadsheet template '" + candidate.templateName() //$NON-NLS-1$
                    + "' in project '" + projectName + "'. Nothing was renamed. Preserve any unsaved work, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "close the spreadsheet editors and retry. If the error persists, use clean_project " //$NON-NLS-1$
                    + "to reload the saved files without reopening the template."; //$NON-NLS-1$
            }
        }
        if (!entries.isEmpty())
        {
            repair.arm(entries);
        }
        return null;
    }

    /** Only the addressed owner's attached spreadsheets; never extend the scan to the project. */
    static List<Candidate> candidates(IProject project, MdObject owner, IBmTransaction tx,
        ITopObjectFqnGenerator generator)
    {
        if (!(owner instanceof IBmObject))
        {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (BasicTemplate template : ownedTemplates(owner))
        {
            if (template.getTemplateType() != TemplateType.SPREADSHEET_DOCUMENT
                || !(attachedWorkbook(tx, generator, template) instanceof SpreadsheetDocument book)
                || !(book instanceof IBmObject bmBook) || !bmBook.bmIsTop() || bmBook.bmGetNamespace() == null
                || book.getLanguageSettings() == null)
            {
                continue;
            }
            Set<LanguageKey> blank = uniquelyBlank(book.getLanguageSettings().getLanguages());
            if (!blank.isEmpty())
            {
                candidates.add(new Candidate(template.getName(), verifiedWorkspaceFile(project, template), blank, bmBook.bmGetId()));
            }
        }
        return candidates;
    }

    private static IFile verifiedWorkspaceFile(IProject project, BasicTemplate template)
    {
        try
        {
            IFile file = TemplateContentSupport.contentFile(project, template);
            return project.equals(file.getProject()) && "mxlx".equals(file.getFileExtension()) //$NON-NLS-1$
                && !file.isLinked(IResource.CHECK_ANCESTORS) ? file : null;
        }
        catch (IllegalStateException e)
        {
            // An identified candidate with no verifiable path still needs a refusal, not a guess.
            return null;
        }
    }

    /** Look up registered BM data only; the external property's getter can resolve its source. */
    private static EObject attachedWorkbook(IBmTransaction tx, ITopObjectFqnGenerator generator,
        BasicTemplate template)
    {
        String fqn = generator.generateExternalPropertyFqn(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
        return fqn == null ? null : tx.getTopObjectByFqn(fqn);
    }

    /**
     * Count occurrences before considering their descriptions. Multiple entries with the same key
     * are ambiguous, even when only one of them has a blank description.
     */
    static Set<LanguageKey> uniquelyBlank(Iterable<? extends LanguageInfo> languages)
    {
        Set<LanguageKey> visited = new HashSet<>();
        Set<LanguageKey> blankKeys = new HashSet<>();
        for (LanguageInfo language : languages)
        {
            var key = new LanguageKey(language.getLanguageId(), language.getLanguageCode());
            if (blank(key.id()) || blank(key.code()))
            {
                continue;
            }
            if (!visited.add(key))
            {
                blankKeys.remove(key);
            }
            else if (blank(language.getDescription()))
            {
                blankKeys.add(key);
            }
        }
        return blankKeys;
    }

    private static boolean blank(String description)
    {
        return description == null || description.isEmpty();
    }

    private static ProjectSpreadsheetReader.Evidence secureEvidence(Candidate candidate)
    {
        // The stock Windows provider does not implement SecureDirectoryStream. Do not even
        // open the volume's directory when no safe descriptor-relative read can follow.
        if (Platform.OS_WIN32.equals(Platform.getOS()))
        {
            return new ProjectSpreadsheetReader.Evidence(
                ProjectSpreadsheetReader.Availability.UNSUPPORTED_PROVIDER, null);
        }
        IFile file = candidate.file();
        if (file == null || file.getLocation() == null || file.getProject().getLocation() == null)
        {
            return new ProjectSpreadsheetReader.Evidence(ProjectSpreadsheetReader.Availability.UNVERIFIED, null);
        }
        return ProjectSpreadsheetReader.read(file.getProject().getLocation().toFile().toPath(),
            file.getLocation().toFile().toPath(), candidate.blank());
    }

    /** Verify only the language header. In particular, never load the rest of a large workbook. */
    static LanguageKey lostDescription(InputStream input, Set<LanguageKey> modelBlank)
        throws IOException, XMLStreamException
    {
        return savedDescriptions(input, modelBlank).keySet().stream().findFirst().orElse(null);
    }

    static Map<LanguageKey, String> savedDescriptions(InputStream input, Set<LanguageKey> modelBlank)
        throws IOException, XMLStreamException
    {
        XMLStreamReader xml = SecureXml.streamFactory().createXMLStreamReader(
            new ByteArrayInputStream(input.readNBytes(MAX_HEADER_BYTES)));
        try
        {
            if (xml.nextTag() != XMLStreamReader.START_ELEMENT || !element(xml, "document") //$NON-NLS-1$
                || xml.nextTag() != XMLStreamReader.START_ELEMENT || !element(xml, "languageSettings")) //$NON-NLS-1$
            {
                throw new XMLStreamException("Unrecognized spreadsheet language header"); //$NON-NLS-1$
            }
            Set<LanguageKey> visited = new HashSet<>();
            Map<LanguageKey, String> possibleLoss = new LinkedHashMap<>();
            while (xml.nextTag() == XMLStreamReader.START_ELEMENT)
            {
                if (element(xml, "currentLanguage") || element(xml, "defaultLanguage")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    xml.getElementText();
                    continue;
                }
                if (!element(xml, "languageInfo")) //$NON-NLS-1$
                {
                    throw new XMLStreamException("Unrecognized spreadsheet language entry"); //$NON-NLS-1$
                }
                String id = null;
                String code = null;
                String description = null;
                while (xml.nextTag() == XMLStreamReader.START_ELEMENT)
                {
                    if (element(xml, "id") && id == null) //$NON-NLS-1$
                    {
                        id = xml.getElementText();
                    }
                    else if (element(xml, "code") && code == null) //$NON-NLS-1$
                    {
                        code = xml.getElementText();
                    }
                    else if (element(xml, "description") && description == null) //$NON-NLS-1$
                    {
                        description = xml.getElementText();
                    }
                    else
                    {
                        throw new XMLStreamException("Unrecognized spreadsheet language field"); //$NON-NLS-1$
                    }
                }
                if (blank(id) || blank(code))
                {
                    throw new XMLStreamException("Spreadsheet language key is incomplete"); //$NON-NLS-1$
                }
                LanguageKey key = new LanguageKey(id, code);
                if (!visited.add(key))
                {
                    possibleLoss.remove(key);
                }
                else if (modelBlank.contains(key) && !blank(description))
                {
                    possibleLoss.put(key, description);
                }
            }
            return possibleLoss;
        }
        finally
        {
            xml.close();
        }
    }

    private static boolean element(XMLStreamReader xml, String name)
    {
        return name.equals(xml.getLocalName()) && MOXEL.equals(xml.getNamespaceURI());
    }
}
