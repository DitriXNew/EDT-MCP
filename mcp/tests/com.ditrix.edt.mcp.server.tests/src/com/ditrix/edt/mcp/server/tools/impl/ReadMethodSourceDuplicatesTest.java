/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

/**
 * #987: {@code read_method_source} on a module that declares the read name more than once does not pass the first
 * declaration off as the only one - in the single-name answer and in a {@code methodNames} section, on the EDT
 * model path and on the text path alike, it lists every declaration's lines and says which one it shows.
 */
public class ReadMethodSourceDuplicatesTest
{
    private static final String PROJECT = "P"; //$NON-NLS-1$
    private static final String PATH = "CommonModules/Calc/Module.bsl"; //$NON-NLS-1$

    /** ВыборкаДанных twice at top level (lines 1-3 and 8-10), Другая once. */
    private static final List<String> TWICE = List.of(
        "Функция ВыборкаДанных(Параметр) Экспорт", //$NON-NLS-1$
        "\tВозврат Параметр;", //$NON-NLS-1$
        "КонецФункции", //$NON-NLS-1$
        "", //$NON-NLS-1$
        "Процедура Другая()", //$NON-NLS-1$
        "КонецПроцедуры", //$NON-NLS-1$
        "", //$NON-NLS-1$
        "Функция выборкаданных(Параметр)", //$NON-NLS-1$
        "\tВозврат 2;", //$NON-NLS-1$
        "КонецФункции"); //$NON-NLS-1$

    /** Ветка in the two arms of one #Если block. */
    private static final List<String> BRANCHES = List.of(
        "#Если Сервер Тогда", //$NON-NLS-1$
        "Процедура Ветка()", //$NON-NLS-1$
        "КонецПроцедуры", //$NON-NLS-1$
        "#Иначе", //$NON-NLS-1$
        "Процедура Ветка()", //$NON-NLS-1$
        "КонецПроцедуры", //$NON-NLS-1$
        "#КонецЕсли"); //$NON-NLS-1$

    private static final class Access implements ReadMethodSourceTool.ModuleAccess
    {
        final IFile file = mock(IFile.class);
        final Map<Method, int[]> spans = new HashMap<>();
        final List<String> lines;
        Module module;

        Access(List<String> lines)
        {
            this.lines = lines;
            when(file.exists()).thenReturn(true);
        }

        @Override
        public Module load(IProject project, String modulePath)
        {
            return module;
        }

        @Override
        public IFile resolve(IProject project, String modulePath)
        {
            return file;
        }

        @Override
        public List<String> readLines(IFile f)
        {
            return lines;
        }

        @Override
        public String moduleHash(IFile f)
        {
            return "h1"; //$NON-NLS-1$
        }

        @Override
        public int startLine(Method method)
        {
            return spans.get(method)[0];
        }

        @Override
        public int endLine(Method method)
        {
            return spans.get(method)[1];
        }

        @Override
        public String footer(Module m, Method method)
        {
            return null;
        }
    }

    /** The EDT model of {@link #TWICE}: both functions, in source order, as EDT keeps them. */
    private static Access modelOfTwice()
    {
        Access access = new Access(TWICE);
        Module module = BslFactory.eINSTANCE.createModule();
        Function first = BslFactory.eINSTANCE.createFunction();
        first.setName("ВыборкаДанных"); //$NON-NLS-1$
        first.setExport(true);
        Function second = BslFactory.eINSTANCE.createFunction();
        second.setName("выборкаданных"); //$NON-NLS-1$
        module.getMethods().add(first);
        module.getMethods().add(second);
        access.spans.put(first, new int[] { 1, 3 });
        access.spans.put(second, new int[] { 8, 10 });
        access.module = module;
        return access;
    }

    private static final String NOTICE = "> 'ВыборкаДанных' is declared 2 times in this module (lines 1-3, 8-10); " //$NON-NLS-1$
        + "shown: lines 1-3. They share one preprocessor context, so the 1C platform will refuse to load the module"; //$NON-NLS-1$

    @Test
    public void theTextPathSingleAnswerListsEveryDeclaration()
    {
        String result = new ReadMethodSourceTool(new Access(TWICE)).readViaText(null, PROJECT, PATH,
            List.of("ВЫБОРКАДАННЫХ"), false); //$NON-NLS-1$
        assertTrue(result, result.contains("\nduplicateDeclarations: [\"1-3\", \"8-10\"]\n")); //$NON-NLS-1$
        assertTrue(result, result.contains(NOTICE));
        assertTrue(result, result.contains("\nstartLine: 1\nendLine: 3\n")); //$NON-NLS-1$
    }

    @Test
    public void theModelPathSingleAnswerListsEveryDeclaration()
    {
        String result = new ReadMethodSourceTool(modelOfTwice()).readViaEmf(null, PROJECT, PATH,
            List.of("выборкаданных"), false); //$NON-NLS-1$
        assertTrue(result, result.contains("\nduplicateDeclarations: [\"1-3\", \"8-10\"]\n")); //$NON-NLS-1$
        assertTrue(result, result.contains(NOTICE));
    }

    @Test
    public void bothPathsAgreeOnAMethodNamesSection()
    {
        String viaText = new ReadMethodSourceTool(new Access(TWICE)).readViaText(null, PROJECT, PATH,
            List.of("ВыборкаДанных", "Другая"), true); //$NON-NLS-1$ //$NON-NLS-2$
        String viaModel = new ReadMethodSourceTool(modelOfTwice()).readViaEmf(null, PROJECT, PATH,
            List.of("ВыборкаДанных", "Другая"), true); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(viaText, viaText.contains("### ВыборкаДанных\nFunction · export · lines 1-3\n" + NOTICE)); //$NON-NLS-1$
        assertFalse("a name declared once carries no notice", viaText.contains("'Другая' is declared")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the multi answer keeps one module header", viaText.contains("duplicateDeclarations")); //$NON-NLS-1$ //$NON-NLS-2$
        // The model path renders the same section for the first of EDT's two methods.
        assertTrue(viaModel, viaModel.contains("### ВыборкаДанных\nFunction · export · lines 1-3\n" + NOTICE)); //$NON-NLS-1$
    }

    @Test
    public void declarationsInDifferentBranchesAreNamedAsSuch()
    {
        String result = new ReadMethodSourceTool(new Access(BRANCHES)).readViaText(null, PROJECT, PATH,
            List.of("Ветка"), false); //$NON-NLS-1$
        assertTrue(result, result.contains("> 'Ветка' is declared 2 times in this module (lines 2-3, 5-6); " //$NON-NLS-1$
            + "shown: lines 2-3. They sit in different branches of the #If block opened at line 1, so only one of " //$NON-NLS-1$
            + "them is compiled.")); //$NON-NLS-1$
    }

    @Test
    public void aNameDeclaredOnceAnswersAsBefore()
    {
        String result = new ReadMethodSourceTool(new Access(TWICE)).readViaText(null, PROJECT, PATH,
            List.of("Другая"), false); //$NON-NLS-1$
        assertFalse(result, result.contains("duplicateDeclarations")); //$NON-NLS-1$
        assertFalse(result, result.contains("is declared")); //$NON-NLS-1$
        assertEquals("---\nprojectName: P\nmodule: CommonModules/Calc/Module.bsl\ncontentHash: h1\nmethod: Другая\n" //$NON-NLS-1$
            + "type: Procedure\nexport: false\nstartLine: 5\nendLine: 6\ntotalLines: 10\n---\n```bsl\nПроцедура Другая()\n" //$NON-NLS-1$
            + "КонецПроцедуры\n```\n", result); //$NON-NLS-1$
    }
}
