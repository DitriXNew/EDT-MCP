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
 * searchReplace oldSource validation, and result file name generation.
 * <p>
 * Note: tests that require Eclipse workspace (actual file I/O, searchReplace content matching)
 * are not included as they need a running Eclipse runtime. Those are covered by E2E tests.
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
        assertTrue(schema.contains("\"oldSource\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"mode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"commandName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"skipSyntaxCheck\"")); //$NON-NLS-1$
        // Lost-update guard params for mode=replace over an existing module.
        assertTrue(schema.contains("\"expectedSource\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"overwrite\"")); //$NON-NLS-1$
        // The new params are OPTIONAL — required stays projectName + source only.
        assertTrue(schema.contains("\"required\":[\"projectName\",\"source\"]")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDoesNotContainLineParams()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        String schema = tool.getInputSchema();

        // Line-based params should not be present
        assertFalse(schema.contains("\"line\"")); //$NON-NLS-1$
        assertFalse(schema.contains("\"lineFrom\"")); //$NON-NLS-1$
        assertFalse(schema.contains("\"lineTo\"")); //$NON-NLS-1$
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
        params.put("oldSource", "old"); //$NON-NLS-1$ //$NON-NLS-2$

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

    @Test
    public void testExecuteOldLineModes()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        String[] removedModes = { "insertBefore", "insertAfter", "replaceLines" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        for (String mode : removedModes)
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("mode", mode); //$NON-NLS-1$

            String result = tool.execute(params);
            assertTrue("mode '" + mode + "' should be rejected", //$NON-NLS-1$ //$NON-NLS-2$
                result.contains("invalid mode")); //$NON-NLS-1$
        }
    }

    // ==================== searchReplace: oldSource validation ====================

    @Test
    public void testSearchReplaceMissingOldSource()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "searchReplace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("oldSource is required for searchReplace")); //$NON-NLS-1$
    }

    @Test
    public void testSearchReplaceEmptyOldSource()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "searchReplace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("oldSource", ""); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("oldSource is required for searchReplace")); //$NON-NLS-1$
    }

    @Test
    public void testDefaultModeIsSearchReplace()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // No mode specified — defaults to searchReplace, which requires oldSource

        String result = tool.execute(params);
        assertTrue(result.contains("oldSource is required for searchReplace")); //$NON-NLS-1$
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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // Delimiter-free substring: the message is now wrapped in JSON and Gson
        // escapes the apostrophes around '..' as a unicode escape.
        assertTrue(result.contains("must not contain")); //$NON-NLS-1$
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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // Delimiter-free substring (Gson escapes the apostrophes in 'Type.Name').
        assertTrue(result.contains("must be in format")); //$NON-NLS-1$
    }

    @Test
    public void testResolveObjectNameUnknownType()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "UnknownType.Name"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

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
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // modulePath valid, passes all checks, reaches workspace validation
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testValidModesReachProjectValidation()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();

        // replace mode
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

            String result = tool.execute(params);
            assertTrue("mode 'replace' should pass validation", //$NON-NLS-1$
                result.contains("Project not found")); //$NON-NLS-1$
        }

        // append mode
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("mode", "append"); //$NON-NLS-1$ //$NON-NLS-2$

            String result = tool.execute(params);
            assertTrue("mode 'append' should pass validation", //$NON-NLS-1$
                result.contains("Project not found")); //$NON-NLS-1$
        }

        // searchReplace mode (with oldSource)
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("oldSource", "old code"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("mode", "searchReplace"); //$NON-NLS-1$ //$NON-NLS-2$

            String result = tool.execute(params);
            assertTrue("mode 'searchReplace' should pass validation", //$NON-NLS-1$
                result.contains("Project not found")); //$NON-NLS-1$
        }
    }

    @Test
    public void testSearchReplaceNotRequiredForReplaceMode()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        // No oldSource — should be fine for replace mode

        String result = tool.execute(params);
        // Should reach project validation, NOT complain about oldSource
        assertFalse(result.contains("oldSource is required")); //$NON-NLS-1$
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testSearchReplaceNotRequiredForAppendMode()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "append"); //$NON-NLS-1$ //$NON-NLS-2$
        // No oldSource — should be fine for append mode

        String result = tool.execute(params);
        assertFalse(result.contains("oldSource is required")); //$NON-NLS-1$
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    // ============= Error shape: resolveModulePath failures are structured =============
    // CLAUDE.md rule #8: the resolveModulePath failures (formerly bare "Error:"
    // strings) must reach the client as a ToolResult.error JSON payload, i.e.
    // {"success":false,"error":...}, not a bare string and not an "Error:" prefix.

    private static void assertStructuredError(String result)
    {
        assertTrue("expected a structured JSON error object, got: " + result, //$NON-NLS-1$
            result.startsWith("{")); //$NON-NLS-1$
        assertTrue("expected success:false in: " + result, //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("expected an error field in: " + result, //$NON-NLS-1$
            result.contains("\"error\"")); //$NON-NLS-1$
        // The legacy bare-string sentinel must NOT leak to the client.
        assertFalse("error payload must not start with the bare \"Error:\" sentinel: " + result, //$NON-NLS-1$
            result.startsWith("Error:")); //$NON-NLS-1$
    }

    @Test
    public void testResolveInvalidFormatReturnsStructuredError()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "NoDot"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertStructuredError(tool.execute(params));
    }

    @Test
    public void testResolveUnknownTypeReturnsStructuredError()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "UnknownType.Name"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertStructuredError(tool.execute(params));
    }

    @Test
    public void testResolveUnknownModuleTypeReturnsStructuredError()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "UnknownModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertStructuredError(tool.execute(params));
    }

    @Test
    public void testResolveFormModuleMissingFormNameReturnsStructuredError()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "FormModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertStructuredError(tool.execute(params));
    }

    @Test
    public void testResolveCommandModuleMissingCommandNameReturnsStructuredError()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "CommandModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        assertStructuredError(tool.execute(params));
    }

    // ============= applySearchReplace: pure content-replace (A14) =============

    @Test
    public void testApplySearchReplaceEofFragmentWithTrailingNewline()
    {
        // Regression (A14): an oldSource ending at EOF, including the file's final
        // newline, must be found. Search now runs on the raw content (which keeps
        // the trailing newline) instead of String.join(lines), which dropped it.
        WriteModuleSourceTool.SearchReplaceResult r =
            WriteModuleSourceTool.applySearchReplace("A\nB\n", "B\n", "C\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, r.occurrences);
        assertEquals("A\nC\n", r.newContent); //$NON-NLS-1$
    }

    @Test
    public void testApplySearchReplaceMiddleFragment()
    {
        WriteModuleSourceTool.SearchReplaceResult r =
            WriteModuleSourceTool.applySearchReplace("A\nB\nC\n", "B", "BB"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, r.occurrences);
        assertEquals("A\nBB\nC\n", r.newContent); //$NON-NLS-1$
    }

    @Test
    public void testApplySearchReplaceNotFound()
    {
        WriteModuleSourceTool.SearchReplaceResult r =
            WriteModuleSourceTool.applySearchReplace("A\nB\n", "ZZZ", "X"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(0, r.occurrences);
        assertNull(r.newContent);
    }

    @Test
    public void testApplySearchReplaceAmbiguous()
    {
        WriteModuleSourceTool.SearchReplaceResult r =
            WriteModuleSourceTool.applySearchReplace("X\nX\n", "X\n", "Y\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("duplicate fragment must be reported ambiguous", r.occurrences > 1); //$NON-NLS-1$
        assertNull(r.newContent);
    }

    @Test
    public void testApplySearchReplacePreservesContentWithoutTrailingNewline()
    {
        // When the raw content has no trailing newline, replacing a non-EOF
        // fragment leaves the rest intact.
        WriteModuleSourceTool.SearchReplaceResult r =
            WriteModuleSourceTool.applySearchReplace("A\nB", "A", "Z"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, r.occurrences);
        assertEquals("Z\nB", r.newContent); //$NON-NLS-1$
    }

    // ===== evaluateReplacePrecondition: mode=replace lost-update guard (pure) =====
    // mode=replace over an EXISTING module must not blindly clobber state the agent
    // never saw. searchReplace is already guarded by its oldSource match; replace was
    // not. These tests exercise the pure decision (no Eclipse workspace needed);
    // the file-read wiring + new-file creation are covered by E2E.

    @Test
    public void testReplacePreconditionRejectsBareExistingOverwrite()
    {
        // Existing module, no expectedSource and no overwrite -> rejected with the
        // steer toward expectedSource / overwrite / searchReplace.
        String result =
            WriteModuleSourceTool.evaluateReplacePrecondition("Procedure A()\nEndProcedure\n", null, false); //$NON-NLS-1$
        assertNotNull("a bare replace over an existing module must be rejected", result); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("already exists")); //$NON-NLS-1$
        assertTrue(result.contains("expectedSource")); //$NON-NLS-1$
        assertTrue(result.contains("overwrite")); //$NON-NLS-1$
        assertTrue(result.contains("searchReplace")); //$NON-NLS-1$
    }

    @Test
    public void testReplacePreconditionExpectedSourceMismatchRereadSteer()
    {
        // expectedSource differs from current content -> re-read steer (mirrors the
        // searchReplace not-found "read the file again" steer).
        String current = "Procedure A()\nEndProcedure\n"; //$NON-NLS-1$
        String stale = "Procedure A()\n// old body\nEndProcedure\n"; //$NON-NLS-1$
        String result = WriteModuleSourceTool.evaluateReplacePrecondition(current, stale, false);
        assertNotNull("a stale expectedSource must be rejected", result); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("does not match")); //$NON-NLS-1$
        assertTrue(result.contains("read_module_source")); //$NON-NLS-1$
    }

    @Test
    public void testReplacePreconditionExpectedSourceMatchProceeds()
    {
        // expectedSource equals current content -> proceed (null = no error).
        String current = "Procedure A()\nEndProcedure\n"; //$NON-NLS-1$
        String result = WriteModuleSourceTool.evaluateReplacePrecondition(current, current, false);
        assertNull("a matching expectedSource must let the write proceed", result); //$NON-NLS-1$
    }

    @Test
    public void testReplacePreconditionExpectedSourceMatchIgnoresCrlf()
    {
        // CRLF vs LF alone is not a spurious mismatch — both are \n-normalized.
        // currentContent is already \n-normalized by the caller; expectedSource is
        // normalized inside the decision.
        String current = "Procedure A()\nEndProcedure\n"; //$NON-NLS-1$
        String expectedCrlf = "Procedure A()\r\nEndProcedure\r\n"; //$NON-NLS-1$
        String result = WriteModuleSourceTool.evaluateReplacePrecondition(current, expectedCrlf, false);
        assertNull("a CRLF/LF-only difference must not be a mismatch", result); //$NON-NLS-1$
    }

    @Test
    public void testReplacePreconditionOverwriteForcesProceed()
    {
        // overwrite=true, no expectedSource -> proceed (explicit force).
        String result =
            WriteModuleSourceTool.evaluateReplacePrecondition("anything\n", null, true); //$NON-NLS-1$
        assertNull("overwrite=true must force the write through", result); //$NON-NLS-1$
    }

    @Test
    public void testReplacePreconditionExpectedSourceWinsOverOverwriteFlag()
    {
        // When expectedSource is provided it is authoritative: a mismatch is rejected
        // even if overwrite=true was also passed (the content guard is the stronger
        // signal that the agent had a specific prior state in mind).
        String result =
            WriteModuleSourceTool.evaluateReplacePrecondition("current\n", "stale\n", true); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result);
        assertTrue(result.contains("does not match")); //$NON-NLS-1$
    }

    // ===== execute(): new params do not break resolution (reach project check) =====
    // In the unit-test env there is no workspace, so execute() stops at
    // "Project not found" before any file I/O. These prove the gate params are
    // wired without disturbing modulePath/objectName resolution — and that the
    // guard is language-agnostic (English + Russian object Name).

    @Test
    public void testReplaceWithExpectedSourceReachesProjectValidation()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedSource", "old content"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testReplaceWithOverwriteReachesProjectValidation()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("overwrite", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testReplaceWithExpectedSourceResolvesRussianObjectName()
    {
        // Language-agnostic: a Russian object Name ("Документ.МойДок") still resolves
        // by its programmatic Name; the lost-update guard does not depend on language.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", //$NON-NLS-1$
            "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041C\u043E\u0439\u0414\u043E\u043A"); //$NON-NLS-1$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedSource", "old content"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testReplaceWithOverwriteResolvesRussianObjectName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", //$NON-NLS-1$
            "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041C\u043E\u0439\u0414\u043E\u043A"); //$NON-NLS-1$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("overwrite", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }
}
