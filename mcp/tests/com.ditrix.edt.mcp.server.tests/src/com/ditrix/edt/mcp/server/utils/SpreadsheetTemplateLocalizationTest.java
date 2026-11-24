/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com.google.gson.JsonParser;

/** Regression coverage with actual EDT moxel model objects; the E2E pins the resource export. */
public class SpreadsheetTemplateLocalizationTest
{
    @Test
    public void baseLanguagesAreIncludedForAnExtensionWithoutAdoptedLanguages()
    {
        var manager = mock(IV8ProjectManager.class);
        var project = mock(IProject.class);
        var parent = mock(IProject.class);
        var extension = mock(IExtensionProject.class);
        var base = mock(IV8Project.class);
        when(manager.getProject(project)).thenReturn(extension);
        when(extension.getParentProject()).thenReturn(parent);
        when(manager.getProject(parent)).thenReturn(base);
        var english = language("English", "en");
        var french = language("French", "fr");
        when(base.getDefaultLanguage()).thenReturn(english);
        when(base.getLanguages()).thenReturn(List.of(french, english, language("Incomplete", "")));

        var codes = TemplateContentSupport.spreadsheetLanguageCodes(manager, project);
        assertEquals(List.of("en", "fr"), codes);
        var doc = SheetFactory.createSpreadsheetDocument(); // no cached language hints
        apply(doc, codes, """
            {"cells":[{"row":0,"col":0,"text":"Invoice"},{"row":0,"col":1,"parameter":"Number"}]}
            """);
        assertEquals(Map.of("#", "Invoice", "en", "Invoice", "fr", "Invoice"),
            cell(doc, 0).getText().getContent().map());
        assertEquals(FillType.TEXT, fill(doc, cell(doc, 0)));
        assertEquals("Number", cell(doc, 1).getParameter());
        assertNull(cell(doc, 1).getText());
        assertEquals(FillType.PARAMETER, fill(doc, cell(doc, 1)));

        var spanish = language("Spanish", "es");
        when(extension.getLanguages()).thenReturn(List.of(spanish, english));
        assertEquals(List.of("en", "es", "fr"),
            TemplateContentSupport.spreadsheetLanguageCodes(manager, project));
    }

    @Test
    public void textPatchRefreshesScalarCopiesAndActiveLanguageButKeepsOtherTranslations()
    {
        var doc = SheetFactory.createSpreadsheetDocument();
        var languages = MoxelFactory.eINSTANCE.createLanguageSettings();
        languages.setDefaultLanguage("en");
        languages.setCurrentLanguage("de");
        doc.setLanguageSettings(languages);
        var codes = List.of("en", "de", "fr", "es");
        apply(doc, codes, """
            {"cells":[{"row":0,"col":0,"text":"Old [Number]"}]}
            """);
        var cell = cell(doc, 0);
        cell.getText().getContent().put("fr", "Facture [Number]");
        cell.getText().getContent().put("de", "Rechnung [Number]");
        var mixed = MoxelFactory.eINSTANCE.createFormat();
        mixed.setFillType(FillType.TEMPLATE);
        doc.getFormats().add(mixed);
        cell.setFormatIndex(doc.getFormats().size() - 1);

        apply(doc, codes, """
            {"cells":[{"row":0,"col":0,"text":"New [Number]"}]}
            """);
        var result = cell(doc, 0);
        assertEquals(FillType.TEMPLATE, fill(doc, result));
        assertEquals(Map.of("#", "New [Number]", "en", "New [Number]", "es", "New [Number]",
            "de", "New [Number]", "fr", "Facture [Number]"), result.getText().getContent().map());

        apply(doc, codes, """
            {"cells":[{"row":0,"col":0,"text":"Replace","replace":true}]}
            """);
        assertEquals(FillType.TEXT, fill(doc, cell));
        assertEquals("Replace", cell.getText().getContent().get("fr"));
        apply(doc, codes, """
            {"cells":[{"row":0,"col":0,"text":""}]}
            """);
        assertNull(cell.getText());

        apply(doc, codes, """
            {"cells":[{"row":0,"col":0,"text":"Text"}]}
            """);
        apply(doc, codes, """
            {"cells":[{"row":0,"col":0,"parameter":"Number"}]}
            """);
        assertNull(cell.getText());
        assertEquals("Number", cell.getParameter());
        assertEquals(FillType.PARAMETER, fill(doc, cell));
    }

    @Test
    public void fallsBackToSavedLanguageIdsNotLanguageNamesOnlyWhenProjectCodesUnavailable()
    {
        var doc = SheetFactory.createSpreadsheetDocument();
        var settings = MoxelFactory.eINSTANCE.createLanguageSettings();
        settings.setDefaultLanguage("en");
        var french = MoxelFactory.eINSTANCE.createLanguageInfo();
        french.setLanguageCode("FrenchName");
        french.setLanguageId("fr");
        settings.getLanguages().add(french);
        doc.setLanguageSettings(settings);
        apply(doc, List.of(), """
            {"cells":[{"row":0,"col":0,"text":"First"}]}
            """);
        assertEquals(Map.of("#", "First", "en", "First", "fr", "First"),
            cell(doc, 0).getText().getContent().map());
        apply(doc, List.of("de"), """
            {"cells":[{"row":0,"col":1,"text":"Second"}]}
            """);
        assertEquals(Map.of("#", "Second", "de", "Second"),
            cell(doc, 1).getText().getContent().map());
    }

    private static Language language(String name, String code)
    {
        var result = MdClassFactory.eINSTANCE.createLanguage();
        result.setName(name);
        result.setLanguageCode(code);
        return result;
    }

    private static void apply(SpreadsheetDocument doc, List<String> codes, String json)
    {
        var result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(json).getAsJsonObject(), null,
            raw -> { throw new AssertionError("unexpected colour"); }, codes);
        assertFalse(result.error, result.hasError());
    }

    private static Cell cell(SpreadsheetDocument doc, int col)
    {
        return doc.getRows().get(Integer.valueOf(0)).getCells().get(Integer.valueOf(col));
    }

    private static FillType fill(SpreadsheetDocument doc, Cell cell)
    {
        return doc.getFormats().get(cell.getFormatIndex()).getFillType();
    }
}
