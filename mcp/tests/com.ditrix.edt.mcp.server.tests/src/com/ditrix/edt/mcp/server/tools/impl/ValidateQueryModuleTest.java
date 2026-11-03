/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.BslStandaloneSetup;
import com.ditrix.edt.mcp.server.utils.QueryValidationService;
import com.ditrix.edt.mcp.server.utils.QueryValidationFixture;
import com.google.gson.JsonParser;

public class ValidateQueryModuleTest
{
    @BeforeClass
    public static void setupParser()
    {
        new BslStandaloneSetup().createInjectorAndDoEMFRegistration();
    }

    @Test
    public void moduleModeUsesSharedServiceForEveryStaticQueryWithDcsAndMapsIssues()
    {
        IProject project = mock(IProject.class);
        QueryValidationService service = mock(QueryValidationService.class);
        var verdict = QueryValidationFixture.result(true,
            new QueryValidationService.Problem("ERROR", "bad", 2, 1, -1, "validator", null, false));
        String query = "SELECT" + System.lineSeparator() + "Missing AS Value";
        when(service.validate(project, query, true)).thenReturn(verdict);
        String source = "Procedure Queries()\n Q = \"SELECT\n |Missing AS Value\";\n"
            + " D = \"SELECT 1\" + Condition;\n Q2 = \"SELECT\n |Missing AS Value\";\nEndProcedure\n";
        var result = JsonParser.parseString(new ValidateQueryTool(service)
            .validateModuleText(project, "Module.bsl", source, "QUERIES", true)).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(2, result.get("foundCount").getAsInt());
        assertEquals(1, result.get("skippedCount").getAsInt());
        var queries = result.getAsJsonArray("queries");
        assertEquals("Queries", queries.get(0).getAsJsonObject().get("method").getAsString());
        assertEquals(2, queries.get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(3, queries.get(0).getAsJsonObject().getAsJsonArray("issues")
            .get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(6, queries.get(1).getAsJsonObject().getAsJsonArray("issues")
            .get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals("dynamic", result.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString());
        verify(service, times(2)).validate(project, query, true);
        verifyNoMoreInteractions(service);
    }

    @Test
    public void moduleReadUsesStoredFileContents()
        throws Exception
    {
        IProject project = mock(IProject.class);
        IFile file = mock(IFile.class);
        when(project.getFile(any(org.eclipse.core.runtime.IPath.class))).thenReturn(file);
        when(file.exists()).thenReturn(true);
        when(file.getCharset()).thenReturn("UTF-8");
        when(file.getContents()).thenReturn(new java.io.ByteArrayInputStream(
            "Procedure Saved()\nQ = \"SELECT 1\";\nEndProcedure\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        QueryValidationService service = mock(QueryValidationService.class);
        var verdict = QueryValidationFixture.result(false);
        when(service.validate(project, "SELECT 1", false)).thenReturn(verdict);
        var result = JsonParser.parseString(new ValidateQueryTool(service)
            .doValidateModule(project, "CommonModules/Saved/Module.bsl", "sAVED", false)).getAsJsonObject();
        assertEquals(1, result.get("foundCount").getAsInt());
        verify(file).getContents();
        verify(service).validate(project, "SELECT 1", false);
    }

    @Test
    public void emptyModuleDoesNotClaimValidation()
    {
        QueryValidationService service = mock(QueryValidationService.class);
        var result = JsonParser.parseString(new ValidateQueryTool(service).validateModuleText(mock(IProject.class),
            "Module.bsl", "Procedure Empty()\nEndProcedure", null, false)).getAsJsonObject();
        assertEquals(0, result.get("foundCount").getAsInt());
        assertEquals(0, result.get("skippedCount").getAsInt());
        assertTrue(result.get("message").getAsString().contains("no query literal found"));
        assertTrue(result.get("message").getAsString().contains("queryText"));
        verifyNoMoreInteractions(service);
    }

    @Test
    public void queryTextModeRemainsByteExact()
    {
        IProject project = mock(IProject.class);
        QueryValidationService service = mock(QueryValidationService.class);
        var verdict = QueryValidationFixture.result(true);
        String text = "  ВЫБРАТЬ\r\n 1 КАК Число ";
        String originalJson = verdict.toJson();
        when(service.validate(project, text, true)).thenReturn(verdict);
        assertEquals(originalJson, new ValidateQueryTool(service).doValidateQuery(project, text, true));
        verify(service).validate(project, text, true);
    }

    @Test
    public void nativeFailureRetainsPerLiteralIncompleteVerdictAndErrorsTheCall()
    {
        IProject project = mock(IProject.class);
        QueryValidationService service = mock(QueryValidationService.class);
        when(service.validate(project, "SELECT 1", false)).thenReturn(QueryValidationFixture.unavailable(false));
        var result = JsonParser.parseString(new ValidateQueryTool(service).validateModuleText(project,
            "Module.bsl", "Procedure Queries()\nQ = \"SELECT 1\";\nEndProcedure", null, false)).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("retry"));
        var query = result.getAsJsonArray("queries").get(0).getAsJsonObject();
        assertEquals("PARTIAL", query.get("validationState").getAsString());
        assertFalse(query.get("validationComplete").getAsBoolean());
        assertTrue(query.get("incompleteReason").getAsString().contains("Wait for project loading"));
    }
}
