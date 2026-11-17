/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/**
 * Tests for {@link PathMask}, the {@code fileMask} of {@code search_in_code} and
 * {@code search_in_dcs}: a substring without {@code *}/{@code ?}, a {@code .gitignore}-style glob
 * with them (issue #956).
 */
public class PathMaskTest
{
    private static final String MY_MODULE = "CommonModules/МойМодуль/Module.bsl"; //$NON-NLS-1$
    private static final String MANAGER = "Catalogs/Товары/ManagerModule.bsl"; //$NON-NLS-1$
    private static final String OBJECT = "Catalogs/Товары/ObjectModule.bsl"; //$NON-NLS-1$
    private static final String FORM = "Catalogs/Товары/Forms/ФормаЭлемента/Module.bsl"; //$NON-NLS-1$
    private static final String DOC = "Documents/SalesOrder/ObjectModule.bsl"; //$NON-NLS-1$

    private static boolean m(String mask, String path)
    {
        return PathMask.of(mask).matches(path);
    }

    // ---- The two examples of the issue ----

    @Test
    public void testIssueExampleStarNameStarSelectsTheModuleFolder()
    {
        assertTrue(m("*МойМодуль*", MY_MODULE)); //$NON-NLS-1$
        assertFalse(m("*МойМодуль*", MANAGER)); //$NON-NLS-1$
    }

    @Test
    public void testIssueExampleDoubleStarFolderFileSelectsTheExactModule()
    {
        assertTrue(m("**/Товары/ManagerModule.bsl", MANAGER)); //$NON-NLS-1$
        assertFalse("the file name is part of the pattern", m("**/Товары/ManagerModule.bsl", OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a form module under the same object is another file", //$NON-NLS-1$
            m("**/Товары/ManagerModule.bsl", FORM)); //$NON-NLS-1$
    }

    // ---- Substring mode is unchanged ----

    @Test
    public void testAMaskWithoutWildcardsIsACaseInsensitiveSubstring()
    {
        assertTrue(m("CommonModules", MY_MODULE)); //$NON-NLS-1$
        assertTrue(m("documents/salesorder", DOC)); //$NON-NLS-1$
        assertTrue("a substring may cut a name in the middle", m("onModules/Мой", MY_MODULE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(m("мОЙмОДУЛЬ", MY_MODULE)); //$NON-NLS-1$
        assertFalse(m("Documents", MY_MODULE)); //$NON-NLS-1$
        assertFalse(PathMask.of("CommonModules").isGlob()); //$NON-NLS-1$
    }

    @Test
    public void testNoMaskSelectsEverything()
    {
        assertTrue(PathMask.of(null).isEmpty());
        assertTrue(PathMask.of("").isEmpty()); //$NON-NLS-1$
        assertTrue(m(null, MY_MODULE));
        assertTrue(m("", DOC)); //$NON-NLS-1$
        assertFalse(PathMask.of("x").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testTheSubstringModeIsLocaleIndependent()
    {
        Locale saved = Locale.getDefault();
        try
        {
            Locale.setDefault(new Locale("tr", "TR")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("dotted I of the Turkish default locale must not break the match", //$NON-NLS-1$
                m("commonmodules/items", "CommonModules/Items/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Locale.setDefault(saved);
        }
    }

    // ---- Glob mode ----

    @Test
    public void testAGlobIsCaseInsensitiveIncludingCyrillic()
    {
        assertTrue(m("*мОЙмодуль*", MY_MODULE)); //$NON-NLS-1$
        assertTrue(m("COMMONMODULES/*", MY_MODULE)); //$NON-NLS-1$
        assertTrue(m("**/товары/MANAGERMODULE.BSL", MANAGER)); //$NON-NLS-1$
    }

    @Test
    public void testAGlobWithoutSlashMatchesAnyOneName()
    {
        assertTrue("a file name", m("*.bsl", DOC)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a whole folder name", m("Товары", MANAGER) && m("Товар?", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(m("Manager*", MANAGER)); //$NON-NLS-1$
        assertFalse("a name is matched whole, not as a substring", m("Module*", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("* does not cross a folder", m("Catalogs*ManagerModule.bsl", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAGlobWithSlashIsMatchedFromThePathStart()
    {
        assertTrue(m("Catalogs/*/ManagerModule.bsl", MANAGER)); //$NON-NLS-1$
        assertFalse("anchored at the start", m("Товары/*", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("* is one name, not two", m("Catalogs/*/Module.bsl", FORM)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a leading / is the path start, as in .gitignore", m("/Catalogs/*/ObjectModule.bsl", OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAMatchedFolderSelectsEverythingUnderIt()
    {
        assertTrue(m("CommonModules/*", MY_MODULE)); //$NON-NLS-1$
        assertTrue(m("Catalogs/Товар*", FORM)); //$NON-NLS-1$
        assertTrue("a trailing / names the folder", m("Catalogs/*/Forms/", FORM)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(m("Documents/*", MANAGER)); //$NON-NLS-1$
    }

    @Test
    public void testQuestionMarkIsExactlyOneCharacterWithinAName()
    {
        assertTrue(m("Catalogs/Товар?/ObjectModule.bsl", OBJECT)); //$NON-NLS-1$
        assertFalse(m("Catalogs/Товар??/ObjectModule.bsl", OBJECT)); //$NON-NLS-1$
        assertFalse("? does not stand for /", m("Catalogs?Товары/ObjectModule.bsl", OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(PathMask.of("Doc?ments").isGlob()); //$NON-NLS-1$
    }

    @Test
    public void testDoubleStarCrossesFolders()
    {
        assertTrue("zero folders", m("Catalogs/Товары/**/ObjectModule.bsl", OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("one folder", m("Catalogs/**/ObjectModule.bsl", OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("two folders", m("Catalogs/**/Module.bsl", FORM)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(m("**/Forms/**", FORM)); //$NON-NLS-1$
        assertFalse(m("**/Forms/**", MANAGER)); //$NON-NLS-1$
        assertTrue(m("**", DOC)); //$NON-NLS-1$
        assertTrue("** inside a name crosses folders too", m("Catalogs**Элемента/Module.bsl", FORM)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testABackslashIsReadAsTheSeparatorInBothModes()
    {
        assertTrue("substring", m("Catalogs\\Товары", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("glob", m("**\\Товары\\ManagerModule.bsl", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(m("CommonModules\\*", MY_MODULE)); //$NON-NLS-1$
    }

    @Test
    public void testASubstringMaskStillMatchesALiteralBackslashInThePath()
    {
        String linuxPath = "Extras/A\\B/Module.bsl"; //$NON-NLS-1$
        assertTrue("the raw substring, as before globs were accepted", m("A\\B", linuxPath)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and the separator reading still applies", m("a\\b", "Extras/A/B/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(m("A\\C", linuxPath)); //$NON-NLS-1$
    }

    @Test
    public void testALeadingSlashAnchorsAGlobAtThePathStart()
    {
        assertFalse("a nested name is not at the start", m("/Товар*", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(m("/Catal*", MANAGER)); //$NON-NLS-1$
        assertFalse("a nested file is not at the start", m("/*.bsl", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("without the slash any name matches", m("Товар*", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testATrailingSlashSelectsFoldersOnlyAtAnyLevel()
    {
        assertTrue("a folder at any level, as in .gitignore", m("Товар*/", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a file name is not a folder", m("*.bsl/", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("without the slash the file name matches", m("*.bsl", MANAGER)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(m("Forms*/", MANAGER)); //$NON-NLS-1$
    }

    @Test
    public void testRegexCharactersInTheMaskAreLiteral()
    {
        assertFalse("a dot is a dot, not any character", m("*.bsl", "Catalogs/X/Modulexbsl")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(m("*(1)*", "Catalogs/A(1)/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(m("*(1)*", "Catalogs/A1/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(m("*$[x]+*", "Catalogs/a$[x]+b/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheTextIsTheMaskAsSent()
    {
        org.junit.Assert.assertEquals("**\\X", PathMask.of("**\\X").text()); //$NON-NLS-1$ //$NON-NLS-2$
        org.junit.Assert.assertEquals("", PathMask.of(null).text()); //$NON-NLS-1$
    }
}
