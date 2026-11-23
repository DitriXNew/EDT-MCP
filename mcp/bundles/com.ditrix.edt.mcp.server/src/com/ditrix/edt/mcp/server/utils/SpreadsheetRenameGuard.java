// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
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

    private record Candidate(String templateName, IFile file, Set<LanguageKey> blank)
    {
    }

    /**
     * Read the model and the saved header, never update either. The native moxel editor can clear
     * descriptions in memory during initialization when a synonym for the editing language is
     * absent; a later rename then makes that loss permanent (#1015).
     */
    public static String refusal(IProject project, MdObject owner, String ownerFqn)
    {
        if (HostKind.current() != HostKind.WINDOWED || !(owner instanceof DataProcessor
            || owner instanceof ExternalDataProcessor))
        {
            return null;
        }
        List<Candidate> candidates = ReadToolExecution.readOnHost(project, "Read saved spreadsheet language risk", //$NON-NLS-1$
            () -> candidates(project, owner));
        for (Candidate candidate : candidates)
        {
            LanguageKey lost = savedDescription(candidate);
            if (lost != null)
            {
                return "Renaming '" + MetadataTypeUtils.toCanonicalEnglishFqn(ownerFqn) //$NON-NLS-1$
                    + "' would erase the saved language description for '" + lost.id() //$NON-NLS-1$
                    + "' in spreadsheet template '" + candidate.templateName() //$NON-NLS-1$
                    + "'. Nothing was renamed. Preserve any unsaved edits, close the affected template " //$NON-NLS-1$
                    + "editor and reload the template from disk before retrying. EDT may otherwise keep " //$NON-NLS-1$
                    + "clearing the description unless that language has a synonym in the active editing language."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /** Only the addressed owner's attached spreadsheets; never extend the scan to the project. */
    private static List<Candidate> candidates(IProject project, MdObject owner)
    {
        if (!(owner instanceof IBmObject bmOwner) || !bmOwner.bmIsTop())
        {
            return List.of();
        }
        List<? extends BasicTemplate> templates = owner instanceof DataProcessor processor
            ? processor.getTemplates() : ((ExternalDataProcessor)owner).getTemplates();
        List<Candidate> candidates = new ArrayList<>();
        for (BasicTemplate template : templates)
        {
            if (template.getTemplateType() != TemplateType.SPREADSHEET_DOCUMENT
                || !(template.getTemplate() instanceof SpreadsheetDocument book)
                || !(book instanceof IBmObject bmBook) || !bmBook.bmIsTop() || bmBook.bmGetNamespace() == null
                || book.getLanguageSettings() == null)
            {
                continue;
            }
            Set<LanguageKey> blank = book.getLanguageSettings().getLanguages().stream()
                .filter(language -> blank(language.getDescription()))
                .filter(language -> !blank(language.getLanguageId()) && !blank(language.getLanguageCode()))
                .map(language -> new LanguageKey(language.getLanguageId(), language.getLanguageCode()))
                .collect(Collectors.toSet());
            if (!blank.isEmpty())
            {
                try
                {
                    IFile file = TemplateContentSupport.contentFile(project, template);
                    if (project.equals(file.getProject()) && "mxlx".equals(file.getFileExtension()) //$NON-NLS-1$
                        && !file.isLinked(IResource.CHECK_ANCESTORS))
                    {
                        candidates.add(new Candidate(template.getName(), file, blank));
                    }
                }
                catch (IllegalStateException e)
                {
                    // No trustworthy source path: never claim a loss from a model value alone.
                }
            }
        }
        return candidates;
    }

    private static boolean blank(String description)
    {
        return description == null || description.isEmpty();
    }

    private static LanguageKey savedDescription(Candidate candidate)
    {
        IFile file = candidate.file();
        if (file.getLocation() == null || file.getProject().getLocation() == null)
        {
            return null;
        }
        try
        {
            Path source = projectFile(file.getProject().getLocation().toFile().toPath(),
                file.getLocation().toFile().toPath());
            if (source == null)
            {
                return null;
            }
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS))
            {
                return lostDescription(input, candidate.blank());
            }
        }
        catch (IOException | XMLStreamException e)
        {
            // A linked, unreadable or unrecognized header cannot establish the reported loss.
            // Leave EDT's ordinary rename path in charge; no disk repair is attempted here.
            return null;
        }
    }

    /** Never traverse a linked resource or filesystem symlink below the configured project root. */
    static Path projectFile(Path root, Path file) throws IOException
    {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        if (!absoluteFile.startsWith(absoluteRoot))
        {
            return null;
        }
        Path realRoot = absoluteRoot.toRealPath();
        Path source = realRoot;
        for (Path component : absoluteRoot.relativize(absoluteFile))
        {
            source = source.resolve(component);
            if (Files.isSymbolicLink(source))
            {
                return null;
            }
        }
        Path realSource = source.toRealPath();
        return realSource.startsWith(realRoot) && Files.isRegularFile(realSource, LinkOption.NOFOLLOW_LINKS)
            ? realSource : null;
    }

    /** Verify only the language header. In particular, never load the rest of a large workbook. */
    static LanguageKey lostDescription(InputStream input, Set<LanguageKey> modelBlank)
        throws IOException, XMLStreamException
    {
        XMLStreamReader xml = SecureXml.streamFactory().createXMLStreamReader(
            new ByteArrayInputStream(input.readNBytes(MAX_HEADER_BYTES)));
        try
        {
            if (xml.nextTag() != XMLStreamReader.START_ELEMENT || !element(xml, "document") //$NON-NLS-1$
                || xml.nextTag() != XMLStreamReader.START_ELEMENT || !element(xml, "languageSettings")) //$NON-NLS-1$
            {
                return null;
            }
            LanguageKey loss = null;
            while (xml.nextTag() == XMLStreamReader.START_ELEMENT)
            {
                if (element(xml, "currentLanguage") || element(xml, "defaultLanguage")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    xml.getElementText();
                    continue;
                }
                if (!element(xml, "languageInfo")) //$NON-NLS-1$
                {
                    return null;
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
                        return null;
                    }
                }
                LanguageKey key = new LanguageKey(id, code);
                if (!blank(description) && modelBlank.contains(key) && loss == null)
                {
                    loss = key;
                }
            }
            return loss;
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
