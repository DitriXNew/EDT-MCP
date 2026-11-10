/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link ReadMethodSourceTool}.
 * <p>
 * Covers tool metadata, the input schema, the on-demand guide, the pure
 * {@code getResultFileName} helper, and the three required-argument validation
 * branches (projectName, modulePath, methodName) — including blank values,
 * check ordering, discovery hints, and the error-JSON envelope — that return
 * before the first {@code PlatformUI.getWorkbench()} call (the EDT boundary).
 * Method extraction needs a live workbench and the loaded BSL model and is
 * covered by the E2E suite.
 */
public class ReadMethodSourceToolTest
{
    @Test
    public void testName()
    {
        assertEquals("read_method_source", new ReadMethodSourceTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ReadMethodSourceTool.NAME, new ReadMethodSourceTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ReadMethodSourceTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ReadMethodSourceTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ReadMethodSourceTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"methodName\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingMethodName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result.contains("methodName is required")); //$NON-NLS-1$
    }

    @Test
    public void testBlankProjectNameIsError()
    {
        // A blank value is treated like a missing one by the required-argument guard.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoStuff"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("blank projectName must be rejected", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testBlankModulePathIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoStuff"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("blank modulePath must be rejected", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testBlankMethodNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("blank methodName must be rejected", //$NON-NLS-1$
            result.contains("methodName is required")); //$NON-NLS-1$
    }

    @Test
    public void testValidationChecksProjectNameFirst()
    {
        // requireArguments checks projectName, then modulePath, then methodName in order and
        // returns the FIRST failure. With ALL three missing, the error must name projectName only.
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("first failure must be projectName", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("must not report modulePath before projectName is satisfied", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testValidationChecksModulePathBeforeMethodName()
    {
        // projectName satisfied, modulePath and methodName both missing → modulePath first.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("second failure must be modulePath", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
        assertFalse("must not report methodName before modulePath is satisfied", //$NON-NLS-1$
            result.contains("methodName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameErrorCarriesDiscoveryHint()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("projectName error must steer to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePathErrorCarriesDiscoveryHint()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("modulePath error must steer to list_modules", //$NON-NLS-1$
            result.contains("list_modules")); //$NON-NLS-1$
    }

    @Test
    public void testMissingMethodNameErrorCarriesDiscoveryHint()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("methodName error must steer to get_module_structure", //$NON-NLS-1$
            result.contains("get_module_structure")); //$NON-NLS-1$
    }

    @Test
    public void testValidationErrorIsStructuredFailureJson()
    {
        // The required-argument guard returns the structured error envelope.
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("error payload must carry success:false", //$NON-NLS-1$
            result.contains("\"success\":false") || result.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error payload must carry an error field", result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== getResultFileName (pure helper, no workbench) ====================

    @Test
    public void testResultFileNameWithMethodName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "MyMethod"); //$NON-NLS-1$ //$NON-NLS-2$
        // Name is lower-cased and wrapped as method-<name>.md.
        assertEquals("method-mymethod.md", new ReadMethodSourceTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameLowercasesMixedCase()
    {
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "CamelCaseName"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("method-camelcasename.md", //$NON-NLS-1$
            new ReadMethodSourceTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameWithoutMethodName()
    {
        Map<String, String> params = new HashMap<>();
        assertEquals("method-source.md", new ReadMethodSourceTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithBlankMethodName()
    {
        // An empty methodName is treated as absent → the generic file name.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("method-source.md", new ReadMethodSourceTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithNullParamsIsSafe()
    {
        // extractStringArgument tolerates a null params map → generic file name, no NPE.
        assertEquals("method-source.md", new ReadMethodSourceTool().getResultFileName(null)); //$NON-NLS-1$
    }

    // ==================== Schema / metadata details ====================

    @Test
    public void testSchemaDeclaresMethodNamesAndRequiresOnlyProjectAndModule()
    {
        JsonObject schema = JsonParser.parseString(new ReadMethodSourceTool().getInputSchema()).getAsJsonObject();
        JsonObject names = schema.getAsJsonObject("properties").getAsJsonObject("methodNames"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("methodNames must be declared", names); //$NON-NLS-1$
        assertEquals("array", names.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("string", names.getAsJsonObject("items").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Either name parameter may carry the request, so neither of them is required.
        Set<String> required = new HashSet<>();
        for (JsonElement element : schema.getAsJsonArray("required")) //$NON-NLS-1$
        {
            required.add(element.getAsString());
        }
        assertEquals(Set.of("projectName", "modulePath"), required); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMethodNamesProseSurvivesCompactionAndStatesTheOneOfRule()
    {
        // methodName left 'required': the only statement that one of the two is needed is
        // methodNames' own description, so it must reach tools/list.
        String raw = new ReadMethodSourceTool().getInputSchema();
        JsonObject wire = InputSchemaCompactor.compact(ReadMethodSourceTool.NAME, JsonParser.parseString(raw))
            .getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        JsonElement description = wire.getAsJsonObject("methodNames").get("description"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("methodNames prose must survive compaction", description); //$NON-NLS-1$
        assertTrue(description.getAsString(), description.getAsString().contains("methodName")); //$NON-NLS-1$
        assertTrue(description.getAsString(), description.getAsString().contains("50")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionSteersToReadModuleSource()
    {
        // The single-method tool advertises its whole-module sibling.
        String desc = new ReadMethodSourceTool().getDescription();
        assertTrue("description must point to read_module_source", //$NON-NLS-1$
            new ReadMethodSourceTool().getGuide().contains("read_module_source")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // MARKDOWN tools return content, not structuredContent → no output schema.
        assertNull(new ReadMethodSourceTool().getOutputSchema());
    }

    @Test
    public void testGuideIsServedAndDocumentsContentHashRoundTrip()
    {
        String guide = new ReadMethodSourceTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must not be empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide must explain the contentHash round-trip", //$NON-NLS-1$
            guide.contains("contentHash")); //$NON-NLS-1$
        assertTrue("guide must note case-insensitive method matching", //$NON-NLS-1$
            guide.toLowerCase().contains("case-insensitive")); //$NON-NLS-1$
    }

    // ==================== methodNames: argument validation ====================

    @Test
    public void testNeitherNameParamIsRefusedNamingBoth()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", PATH); //$NON-NLS-1$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result, result.contains("\"success\":false") || result.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("methodName is required")); //$NON-NLS-1$
        assertTrue("the refusal must name the array alternative", result.contains("methodNames")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("get_module_structure")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyOrBlankMethodNamesAloneIsRefusedNamingBoth()
    {
        for (String listed : List.of("[]", "[\"\", \"  \"]")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("modulePath", PATH); //$NON-NLS-1$
            params.put("methodNames", listed); //$NON-NLS-1$
            String result = new ReadMethodSourceTool().execute(params);
            assertTrue(listed + " -> " + result, result.contains("methodName is required")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(listed + " -> " + result, result.contains("methodNames")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testMoreThanFiftyNamesIsRefused()
    {
        StringBuilder json = new StringBuilder("["); //$NON-NLS-1$
        for (int i = 0; i < ReadMethodSourceTool.MAX_METHOD_NAMES + 1; i++)
        {
            json.append(i == 0 ? "" : ",").append("\"M").append(i).append('"'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", PATH); //$NON-NLS-1$
        params.put("methodNames", json.append(']').toString()); //$NON-NLS-1$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result, result.contains("\"success\":false") || result.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("methodNames accepts at most 50 names, got 51")); //$NON-NLS-1$
        assertTrue("the refusal must name a way forward", result.contains("read_module_source")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFiftyNamesAreAcceptedByTheSizeCheck()
    {
        List<String> fifty = new ArrayList<>();
        for (int i = 0; i < ReadMethodSourceTool.MAX_METHOD_NAMES; i++)
        {
            fifty.add("M" + i); //$NON-NLS-1$
        }
        Map<String, String> params = new HashMap<>();
        params.put("methodNames", String.join(",", fifty)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(fifty, ReadMethodSourceTool.listedNames(params));
    }

    @Test
    public void testDuplicateNamesCollapseCaseInsensitively()
    {
        Map<String, String> params = new HashMap<>();
        params.put("methodNames", "[\"ADD\", \" Test \", \"add\", \"\", \"TEST\", \"Other\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> listed = ReadMethodSourceTool.listedNames(params);
        assertEquals(List.of("ADD", "Test", "add", "TEST", "Other"), listed); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        // methodName first, then the list; the first spelling of each name wins.
        assertEquals(List.of("Add", "Test", "Other"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ReadMethodSourceTool.requestedNames("Add", listed)); //$NON-NLS-1$
        assertEquals(List.of("ADD", "Test", "Other"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ReadMethodSourceTool.requestedNames(null, listed));
    }

    @Test
    public void testDuplicateNamesRenderOneSection()
    {
        CountingAccess access = new CountingAccess();
        String result = new ReadMethodSourceTool(access).readViaText(null, PROJECT, PATH,
            ReadMethodSourceTool.requestedNames("add", List.of("ADD", "Add")), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(result, 1, count(result, "### Add")); //$NON-NLS-1$
        assertTrue(result, result.contains("methodsFound: 1\n")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameForMethodNamesNamesTheModule()
    {
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", "CommonModules/Calc/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodNames", "[\"Add\",\"Test\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("methods-commonmodules-calc-module.bsl.md", //$NON-NLS-1$
            new ReadMethodSourceTool().getResultFileName(params));
    }

    // ==================== methodNames: rendering (shared by both paths) ====================

    private static final String PROJECT = "P"; //$NON-NLS-1$
    private static final String PATH = "CommonModules/Calc/Module.bsl"; //$NON-NLS-1$
    /** Procedure ... Export / EndProcedure in their Russian spelling. */
    private static final String RU_TEST_DECLARATION =
        "Процедура Test() Экспорт"; //$NON-NLS-1$
    private static final String RU_END_PROCEDURE = "КонецПроцедуры"; //$NON-NLS-1$
    private static final List<String> LINES = List.of(
        "#Region Public", //$NON-NLS-1$
        "// Adds two numbers.", //$NON-NLS-1$
        "Function Add(A, B) Export", //$NON-NLS-1$
        "\tReturn A + B;", //$NON-NLS-1$
        "EndFunction", //$NON-NLS-1$
        "#EndRegion", //$NON-NLS-1$
        "", //$NON-NLS-1$
        RU_TEST_DECLARATION,
        "\tResult = Add(1, 2);", //$NON-NLS-1$
        RU_END_PROCEDURE);

    private static final String ADD_FENCE = "```bsl\n// Adds two numbers.\nFunction Add(A, B) Export\n" //$NON-NLS-1$
        + "\tReturn A + B;\nEndFunction\n```\n"; //$NON-NLS-1$
    private static final String TEST_FENCE = "```bsl\n" + RU_TEST_DECLARATION + "\n\tResult = Add(1, 2);\n" //$NON-NLS-1$ //$NON-NLS-2$
        + RU_END_PROCEDURE + "\n```\n"; //$NON-NLS-1$

    /**
     * The answer for methodNames [Test, Add, Nope]: one header, sections in request order.
     * Both declarations carry Export / its Russian spelling, and BOTH paths say so: the text
     * scan reads the modifier after the parameter list, the EMF path reads Method.isExport().
     */
    private static final String MULTI_TEST_ADD_NOPE = "---\n" //$NON-NLS-1$
        + "projectName: P\nmodule: CommonModules/Calc/Module.bsl\ncontentHash: h1\n" //$NON-NLS-1$
        + "totalLines: 10\nmethodsFound: 2\nmethodsNotFound: [\"Nope\"]\n---\n" //$NON-NLS-1$
        + "### Test\nProcedure · export · lines 8-10\n" + TEST_FENCE //$NON-NLS-1$
        + "\n### Add\nFunction · export · lines 2-5 · region Public\n" + ADD_FENCE; //$NON-NLS-1$

    /** {@link #LINES} with the Export modifiers removed: the non-exported twin of the module. */
    private static final List<String> LINES_NOT_EXPORTED = List.of(
        "#Region Public", //$NON-NLS-1$
        "// Adds two numbers.", //$NON-NLS-1$
        "Function Add(A, B)", //$NON-NLS-1$
        "\tReturn A + B;", //$NON-NLS-1$
        "EndFunction", //$NON-NLS-1$
        "#EndRegion", //$NON-NLS-1$
        "", //$NON-NLS-1$
        "Процедура Test()", //$NON-NLS-1$
        "\tResult = Add(1, 2);", //$NON-NLS-1$
        RU_END_PROCEDURE);

    /**
     * Declarations the text scan must classify: exported ones in both dialects and cases, with
     * a string or comment in the way, a wrapped parameter list, the modifier on the next line;
     * non-exported ones whose comment, default value, body or name merely mention the word.
     */
    private static final List<String> EXPORT_SHAPES = List.of(
        "Function Exp1() Export", //$NON-NLS-1$
        "EndFunction", //$NON-NLS-1$
        "Procedure Exp2() экспорт", //$NON-NLS-1$
        "EndProcedure", //$NON-NLS-1$
        "Асинх Функция Exp3(Знач А = \")\") Экспорт // note", //$NON-NLS-1$
        "КонецФункции", //$NON-NLS-1$
        "Function Wrapped(A,", //$NON-NLS-1$
        "\tB = 1) EXPORT", //$NON-NLS-1$
        "EndFunction", //$NON-NLS-1$
        "Procedure NextLine() // comment", //$NON-NLS-1$
        "\tExport", //$NON-NLS-1$
        "EndProcedure", //$NON-NLS-1$
        "Function NoExp1() // Export", //$NON-NLS-1$
        "EndFunction", //$NON-NLS-1$
        "Procedure NoExp2(P = \"Export\")", //$NON-NLS-1$
        "EndProcedure", //$NON-NLS-1$
        "Процедура NoExp3()", //$NON-NLS-1$
        "\tExported = 1;", //$NON-NLS-1$
        "КонецПроцедуры", //$NON-NLS-1$
        "Function ExportFlag()", //$NON-NLS-1$
        "EndFunction"); //$NON-NLS-1$

    /** Counts every read of the module, so a per-name re-read or re-hash is visible. */
    private static final class CountingAccess implements ReadMethodSourceTool.ModuleAccess
    {
        final IFile file = mock(IFile.class);
        final Map<Method, int[]> spans = new HashMap<>();
        final Map<Method, String> footers = new HashMap<>();
        List<String> lines = LINES;
        Module module;
        int loads;
        int resolves;
        int reads;
        int hashes;

        CountingAccess()
        {
            when(file.exists()).thenReturn(true);
        }

        @Override
        public Module load(IProject project, String modulePath)
        {
            loads++;
            return module;
        }

        @Override
        public IFile resolve(IProject project, String modulePath)
        {
            resolves++;
            return file;
        }

        @Override
        public List<String> readLines(IFile f)
        {
            reads++;
            return lines;
        }

        @Override
        public String moduleHash(IFile f)
        {
            hashes++;
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
            return footers.get(method);
        }
    }

    /** A parsed module with Add (lines 3-5) and Test (lines 8-10), as the EMF path sees it. */
    private static CountingAccess emfAccess()
    {
        return emfAccess(true);
    }

    private static CountingAccess emfAccess(boolean export)
    {
        CountingAccess access = new CountingAccess();
        Module module = BslFactory.eINSTANCE.createModule();
        Function add = BslFactory.eINSTANCE.createFunction();
        add.setName("Add"); //$NON-NLS-1$
        add.setExport(export);
        Procedure test = BslFactory.eINSTANCE.createProcedure();
        test.setName("Test"); //$NON-NLS-1$
        test.setExport(export);
        module.getMethods().add(add);
        module.getMethods().add(test);
        access.spans.put(add, new int[] { 3, 5 });
        access.spans.put(test, new int[] { 8, 10 });
        access.module = module;
        return access;
    }

    private static Method method(CountingAccess access, String name)
    {
        for (Method m : access.spans.keySet())
        {
            if (m.getName().equals(name))
            {
                return m;
            }
        }
        throw new AssertionError(name);
    }

    private static int count(String text, String needle)
    {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1))
        {
            n++;
        }
        return n;
    }

    @Test
    public void testMultiRenderingHasOneHeaderAndOneSectionPerMethodInRequestOrder()
    {
        String result = new ReadMethodSourceTool(new CountingAccess()).readViaText(null, PROJECT, PATH,
            List.of("test", "ADD", "Nope"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(MULTI_TEST_ADD_NOPE, result);
    }

    @Test
    public void testEmfPathRendersTheSameSectionsAsTheTextPath()
    {
        String emf = new ReadMethodSourceTool(emfAccess(true)).readViaEmf(null, PROJECT, PATH,
            List.of("test", "ADD", "Nope"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(MULTI_TEST_ADD_NOPE, emf);
    }

    @Test
    public void testBothPathsAgreeOnANonExportedModule()
    {
        CountingAccess text = new CountingAccess();
        text.lines = LINES_NOT_EXPORTED;
        CountingAccess emf = emfAccess(false);
        emf.lines = LINES_NOT_EXPORTED;
        List<String> names = List.of("Test", "Add"); //$NON-NLS-1$ //$NON-NLS-2$
        String viaText = new ReadMethodSourceTool(text).readViaText(null, PROJECT, PATH, names, true);
        assertEquals(viaText, new ReadMethodSourceTool(emf).readViaEmf(null, PROJECT, PATH, names, true));
        assertTrue(viaText, viaText.contains("### Test\nProcedure · lines 8-10\n")); //$NON-NLS-1$
        assertTrue(viaText, viaText.contains("### Add\nFunction · lines 2-5 · region Public\n")); //$NON-NLS-1$
        // The single-name answer of both paths agrees too.
        assertEquals(new ReadMethodSourceTool(emf).readViaEmf(null, PROJECT, PATH, List.of("Add"), false), //$NON-NLS-1$
            new ReadMethodSourceTool(text).readViaText(null, PROJECT, PATH, List.of("Add"), false)); //$NON-NLS-1$
    }

    @Test
    public void testBothPathsAgreeOnTheSingleNameAnswerOfAnExportedMethod()
    {
        CountingAccess emf = emfAccess(true);
        String viaEmf = new ReadMethodSourceTool(emf).readViaEmf(null, PROJECT, PATH, List.of("Test"), false); //$NON-NLS-1$
        String viaText = new ReadMethodSourceTool(new CountingAccess()).readViaText(null, PROJECT, PATH,
            List.of("Test"), false); //$NON-NLS-1$
        assertEquals(viaEmf, viaText);
        assertTrue(viaText, viaText.contains("\nexport: true\n")); //$NON-NLS-1$
    }

    @Test
    public void testTextPathReadsTheExportModifierOfEveryDeclarationShape()
    {
        CountingAccess access = new CountingAccess();
        access.lines = EXPORT_SHAPES;
        String result = new ReadMethodSourceTool(access).readViaText(null, PROJECT, PATH,
            List.of("Exp1", "Exp2", "Exp3", "Wrapped", "NextLine", "NoExp1", "NoExp2", "NoExp3", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                "ExportFlag"), true); //$NON-NLS-1$
        String[] expected = {
            "### Exp1\nFunction · export · lines 1-2\n", //$NON-NLS-1$
            "### Exp2\nProcedure · export · lines 3-4\n", //$NON-NLS-1$
            "### Exp3\nFunction · export · lines 5-6\n", //$NON-NLS-1$
            "### Wrapped\nFunction · export · lines 7-9\n", //$NON-NLS-1$
            "### NextLine\nProcedure · export · lines 10-12\n", //$NON-NLS-1$
            "### NoExp1\nFunction · lines 13-14\n", //$NON-NLS-1$
            "### NoExp2\nProcedure · lines 15-16\n", //$NON-NLS-1$
            "### NoExp3\nProcedure · lines 17-19\n", //$NON-NLS-1$
            "### ExportFlag\nFunction · lines 20-21\n" }; //$NON-NLS-1$
        for (String section : expected)
        {
            assertTrue(section + " in\n" + result, result.contains(section)); //$NON-NLS-1$
        }
        assertTrue(result, result.contains("methodsFound: 9\n")); //$NON-NLS-1$
    }

    @Test
    public void testTextPathSingleNameReportsExportInBothDialects()
    {
        CountingAccess access = new CountingAccess();
        access.lines = EXPORT_SHAPES;
        for (String name : List.of("Exp1", "Exp2", "Exp3", "Wrapped", "NextLine")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            String result = new ReadMethodSourceTool(access).readViaText(null, PROJECT, PATH, List.of(name), false);
            assertTrue(name + " -> " + result, result.contains("\nexport: true\n")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String name : List.of("NoExp1", "NoExp2", "NoExp3", "ExportFlag")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String result = new ReadMethodSourceTool(access).readViaText(null, PROJECT, PATH, List.of(name), false);
            assertTrue(name + " -> " + result, result.contains("\nexport: false\n")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testDedupeCallsEqualExactlyWhatTheLookupCallsEqual()
    {
        // Capital I with a dot above: equalsIgnoreCase (the lookup's rule) calls it equal to a
        // plain 'i', while toLowerCase(Locale.ROOT) turns it into "i" + a combining dot.
        String dotted = "\u0130sim"; //$NON-NLS-1$
        assertTrue("the lookup's own rule calls them one name", dotted.equalsIgnoreCase("isim")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(dotted), ReadMethodSourceTool.requestedNames(null, List.of(dotted, "isim"))); //$NON-NLS-1$
        assertEquals(List.of("isim"), ReadMethodSourceTool.requestedNames("isim", List.of(dotted))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNamesTheLookupCallsEqualRenderOneSection()
    {
        CountingAccess access = new CountingAccess();
        access.lines = List.of("Procedure isim()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool(access).readViaText(null, PROJECT, PATH,
            ReadMethodSourceTool.requestedNames(null, List.of("\u0130sim", "isim")), true); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(result, 1, count(result, "### isim")); //$NON-NLS-1$
        assertTrue(result, result.contains("methodsFound: 1\n")); //$NON-NLS-1$
    }

    @Test
    public void testEmfSectionsCarryExportAndTheirOwnInterceptionFooter()
    {
        CountingAccess access = emfAccess();
        access.footers.put(method(access, "Add"), "\n> Intercepted by Ext.Add\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool(access).readViaEmf(null, PROJECT, PATH,
            List.of("Add", "Test"), true); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("---\n### Add\nFunction · export · lines 2-5 · region Public\n" //$NON-NLS-1$
            + ADD_FENCE + "\n> Intercepted by Ext.Add\n\n### Test\nProcedure · export · lines 8-10\n")); //$NON-NLS-1$
        assertEquals(result, 1, count(result, "Intercepted")); //$NON-NLS-1$
    }

    @Test
    public void testTextPathReadsAndHashesTheModuleOncePerCall()
    {
        CountingAccess access = new CountingAccess();
        new ReadMethodSourceTool(access).readViaText(null, PROJECT, PATH,
            List.of("Add", "Test", "Nope"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("resolve", 1, access.resolves); //$NON-NLS-1$
        assertEquals("read", 1, access.reads); //$NON-NLS-1$
        assertEquals("hash", 1, access.hashes); //$NON-NLS-1$
    }

    @Test
    public void testEmfPathLoadsReadsAndHashesTheModuleOncePerCall()
    {
        CountingAccess access = emfAccess();
        new ReadMethodSourceTool(access).readViaEmf(null, PROJECT, PATH,
            List.of("Add", "Test", "Nope"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("load", 1, access.loads); //$NON-NLS-1$
        assertEquals("read", 1, access.reads); //$NON-NLS-1$
        assertEquals("hash", 1, access.hashes); //$NON-NLS-1$
    }

    @Test
    public void testPartialMissListsNotFoundAndStillRendersTheFound()
    {
        String result = new ReadMethodSourceTool(new CountingAccess()).readViaText(null, PROJECT, PATH,
            List.of("Nope", "Add", "Gone"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result, result.contains("methodsFound: 1\nmethodsNotFound: [\"Nope\", \"Gone\"]\n---\n### Add\n")); //$NON-NLS-1$
        assertTrue(result, result.endsWith(ADD_FENCE));
        assertFalse("a partial miss is not the all-missing listing", result.contains("Available methods")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoMissingNameMeansNoNotFoundKey()
    {
        String result = new ReadMethodSourceTool(new CountingAccess()).readViaText(null, PROJECT, PATH,
            List.of("Add"), true); //$NON-NLS-1$
        assertTrue(result, result.contains("methodsFound: 1\n---\n### Add\n")); //$NON-NLS-1$
        assertFalse(result, result.contains("methodsNotFound")); //$NON-NLS-1$
    }

    @Test
    public void testAllMissingReturnsTheAvailableMethodsListingNamingEveryRequestedName()
    {
        String expected = "Error: Methods 'Nope', 'Gone' not found in " + PATH + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "**Available methods** (2):\n\n- Add\n- Test\n"; //$NON-NLS-1$
        assertEquals(expected, new ReadMethodSourceTool(new CountingAccess()).readViaText(null, PROJECT, PATH,
            List.of("Nope", "Gone"), true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expected, new ReadMethodSourceTool(emfAccess()).readViaEmf(null, PROJECT, PATH,
            List.of("Nope", "Gone"), true)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== methodName alone: the historical answer, byte for byte ====================

    @Test
    public void testLegacySingleNameOutputIsUnchangedButForTheExportFlag()
    {
        String expected = "---\nprojectName: P\nmodule: CommonModules/Calc/Module.bsl\ncontentHash: h1\n" //$NON-NLS-1$
            + "method: add\ntype: Function\nexport: true\nstartLine: 2\nendLine: 5\ntotalLines: 10\n" //$NON-NLS-1$
            + "region: Public\n---\n" + ADD_FENCE; //$NON-NLS-1$
        // The text path echoes the requested spelling, as it always did. The one change: the
        // export flag is now read (it used to be false for every one-line declaration).
        assertEquals(expected, new ReadMethodSourceTool(new CountingAccess()).readViaText(null, PROJECT, PATH,
            List.of("add"), false)); //$NON-NLS-1$
    }

    @Test
    public void testLegacySingleNameEmfOutputIsUnchanged()
    {
        CountingAccess access = emfAccess();
        access.footers.put(method(access, "Test"), "\n> Intercepted by Ext.Test\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String expected = "---\nprojectName: P\nmodule: CommonModules/Calc/Module.bsl\ncontentHash: h1\n" //$NON-NLS-1$
            + "method: Test\ntype: Procedure\nexport: true\nstartLine: 8\nendLine: 10\ntotalLines: 10\n---\n" //$NON-NLS-1$
            + TEST_FENCE + "\n> Intercepted by Ext.Test\n"; //$NON-NLS-1$
        assertEquals(expected, new ReadMethodSourceTool(access).readViaEmf(null, PROJECT, PATH,
            List.of("TEST"), false)); //$NON-NLS-1$
    }

    @Test
    public void testLegacySingleNameNotFoundListingIsUnchanged()
    {
        String expected = "Error: Method 'Nope' not found in " + PATH + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "**Available methods** (2):\n\n- Add\n- Test\n"; //$NON-NLS-1$
        assertEquals(expected, new ReadMethodSourceTool(new CountingAccess()).readViaText(null, PROJECT, PATH,
            List.of("Nope"), false)); //$NON-NLS-1$
        assertEquals(expected, new ReadMethodSourceTool(emfAccess()).readViaEmf(null, PROJECT, PATH,
            List.of("Nope"), false)); //$NON-NLS-1$
    }
}
