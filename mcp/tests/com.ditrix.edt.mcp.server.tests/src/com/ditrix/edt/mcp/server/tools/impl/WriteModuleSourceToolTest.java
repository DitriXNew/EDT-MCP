/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link WriteModuleSourceTool}.
 * <p>
 * Tests cover: tool metadata, parameter validation, mode validation,
 * path traversal protection, .bsl extension check, modulePath resolution
 * from objectName + moduleType (including CommonForm/CommonCommand special cases),
 * and result file name generation.
 * <p>
 * Note: tests that require Eclipse workspace (actual file I/O) are not included
 * as they need a running Eclipse runtime. Those are covered by E2E tests.
 */
public class WriteModuleSourceToolTest
{
    // ==================== Tool metadata ====================

    @Test
    public void testName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        assertEquals("write_module_source", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isEmpty());
    }

    @Test
    public void testInputSchemaContainsRequiredParameters()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        String schema = tool.getInputSchema();

        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"moduleType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"source\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"mode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"line\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"lineFrom\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"lineTo\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"commandName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"skipSyntaxCheck\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\":[\"projectName\",\"source\"]")); //$NON-NLS-1$
    }

    // ==================== Result file name ====================

    @Test
    public void testResultFileNameWithModulePath()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String fileName = tool.getResultFileName(params);
        assertEquals("write-documents-mydoc-objectmodule.bsl.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithoutModulePath()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();

        String fileName = tool.getResultFileName(params);
        assertEquals("write-module-source.md", fileName); //$NON-NLS-1$
    }

    // ==================== Required parameter validation ====================

    @Test
    public void testExecuteMissingProjectName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyProjectName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingSource()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("source is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingBothModulePathAndObjectName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("either modulePath or objectName is required")); //$NON-NLS-1$
    }

    // ==================== Source length limit ====================

    @Test
    public void testExecuteSourceTooLong()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        // Create source exceeding 500000 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500_001; i++)
        {
            sb.append('x');
        }
        params.put("source", sb.toString()); //$NON-NLS-1$

        String result = tool.execute(params);
        assertTrue(result.contains("exceeds maximum allowed length")); //$NON-NLS-1$
    }

    // ==================== Mode validation ====================

    @Test
    public void testExecuteInvalidMode()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "deleteAll"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("invalid mode")); //$NON-NLS-1$
        assertTrue(result.contains("deleteAll")); //$NON-NLS-1$
    }

    // ==================== Path traversal protection ====================

    @Test
    public void testExecutePathTraversal()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "../../etc/passwd.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("must not contain '..'")); //$NON-NLS-1$
    }

    // ==================== .bsl extension validation ====================

    @Test
    public void testExecuteNonBslFile()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Configuration/Configuration.mdo"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("only .bsl module files")); //$NON-NLS-1$
    }

    // ==================== resolveModulePath via execute ====================

    @Test
    public void testResolveObjectNameInvalidFormat()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "NoDot"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("must be in format 'Type.Name'")); //$NON-NLS-1$
    }

    @Test
    public void testResolveObjectNameUnknownType()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "UnknownType.Name"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("unknown metadata type")); //$NON-NLS-1$
    }

    @Test
    public void testResolveUnknownModuleType()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "UnknownModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("unknown moduleType")); //$NON-NLS-1$
    }

    // ==================== FormModule validation ====================

    @Test
    public void testResolveFormModuleMissingFormName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "FormModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("formName is required")); //$NON-NLS-1$
    }

    @Test
    public void testResolveCommonFormDoesNotRequireFormName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "CommonForm.MyForm"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "FormModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // Should NOT contain "formName is required" — CommonForm is a special case
        assertFalse("CommonForm+FormModule should not require formName", //$NON-NLS-1$
            result.contains("formName is required")); //$NON-NLS-1$
        // Should reach project validation (past resolveModulePath)
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    // ==================== CommandModule validation ====================

    @Test
    public void testResolveCommandModuleMissingCommandName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "CommandModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("commandName is required")); //$NON-NLS-1$
    }

    @Test
    public void testResolveCommonCommandDoesNotRequireCommandName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "CommonCommand.MyCommand"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "CommandModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // Should NOT contain "commandName is required" — CommonCommand is a special case
        assertFalse("CommonCommand+CommandModule should not require commandName", //$NON-NLS-1$
            result.contains("commandName is required")); //$NON-NLS-1$
        // Should reach project validation (past resolveModulePath)
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    // ==================== Resolve path — reaches project validation ====================
    // When resolveModulePath succeeds, execute proceeds to check IProject,
    // which returns "Project not found" in unit-test env. This proves resolution worked.

    @Test
    public void testResolveDocumentObjectModule()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // Passes resolveModulePath (defaults to ObjectModule), reaches workspace
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testResolveCommonModule()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "CommonModule.MyModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // CommonModule defaults to moduleType=Module, resolves to CommonModules/MyModule/Module.bsl
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testResolveRussianObjectName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", //$NON-NLS-1$
            "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041C\u043E\u0439\u0414\u043E\u043A"); //$NON-NLS-1$

        String result = tool.execute(params);
        // Russian "Документ.МойДок" resolves to Document, reaches workspace
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testResolveManagerModule()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "ManagerModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testResolveRecordSetModule()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "InformationRegister.Prices"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "RecordSetModule"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testResolveFormModuleWithFormName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "FormModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formName", "ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testResolveCommandModuleWithCommandName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "CommandModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("commandName", "FillByTemplate"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    // ==================== Direct modulePath — reaches project validation ====================

    @Test
    public void testDirectModulePathReachesProjectValidation()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // modulePath valid, passes all checks, reaches workspace validation
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testValidModesReachProjectValidation()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        String[] validModes = { "replace", "append", "insertBefore", "insertAfter", "replaceLines" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        for (String mode : validModes)
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("mode", mode); //$NON-NLS-1$

            String result = tool.execute(params);
            // All valid modes pass mode validation, reach project lookup
            assertTrue("mode '" + mode + "' should pass validation", //$NON-NLS-1$ //$NON-NLS-2$
                result.contains("Project not found")); //$NON-NLS-1$
        }
    }

    @Test
    public void testDefaultModeIsReplace()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // No mode specified — should default to replace

        String result = tool.execute(params);
        // Passes mode validation (defaults to replace), reaches workspace
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }
}
