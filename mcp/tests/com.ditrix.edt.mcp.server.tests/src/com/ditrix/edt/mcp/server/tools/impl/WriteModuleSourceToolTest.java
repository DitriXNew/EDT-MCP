/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.ContentHash;

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

    /**
     * The central XOR rule must stay discoverable upfront from the slimmed
     * description (the modulePath/objectName mutual exclusion drives tool selection).
     * The full conditional-requiredness contract now lives in the on-demand guide
     * (see {@link #testGuideDocumentsConditionalRules}), so the always-loaded
     * description stays short.
     */
    @Test
    public void testDescriptionDocumentsXorRule()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        String desc = tool.getDescription();
        // XOR pair is named (the central rule that drives selection).
        assertTrue(new WriteModuleSourceTool().getGuide().contains("modulePath")); //$NON-NLS-1$
        assertTrue(new WriteModuleSourceTool().getGuide().contains("objectName")); //$NON-NLS-1$
        assertTrue(new WriteModuleSourceTool().getGuide().contains("mutually exclusive")); //$NON-NLS-1$
        // The closing pointer steers to the on-demand guide for the full detail.
        assertTrue(desc.contains("get_tool_guide")); //$NON-NLS-1$
    }

    /**
     * The exhaustive per-parameter detail (the conditional-requiredness contract,
     * the lost-update guards, the mode semantics) moved OUT of the always-loaded
     * description/schema and INTO the on-demand guide. Assert it is non-empty and
     * still carries the conditional params and their conditions, proving the detail
     * moved rather than vanished.
     */
    @Test
    public void testGuideDocumentsConditionalRules()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        String guide = tool.getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        // Each conditional param is documented with its condition in the guide.
        assertTrue(guide.contains("moduleType")); //$NON-NLS-1$
        assertTrue(guide.contains("oldSource")); //$NON-NLS-1$
        assertTrue(guide.contains("formName")); //$NON-NLS-1$
        assertTrue(guide.contains("commandName")); //$NON-NLS-1$
        // The lost-update and mode detail migrated too.
        assertTrue(guide.contains("expectedHash")); //$NON-NLS-1$
        assertTrue(guide.contains("searchReplace")); //$NON-NLS-1$
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
        assertTrue(schema.contains("\"methodName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"commandName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"skipSyntaxCheck\"")); //$NON-NLS-1$
        // Lost-update guard params for mode=replace over an existing module.
        assertTrue(schema.contains("\"expectedSource\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"overwrite\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expectedHash\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"replaceMethod\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"insertBefore\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"insertAfter\"")); //$NON-NLS-1$
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

    // ============= XOR / conditional-requiredness (one precise error each) =============
    // A flat Map schema cannot express XOR, so each violation must return ONE error
    // naming the exact conflicting/missing param. Substrings stay delimiter-free per
    // the Gson HTML-escape contract (no < > & = or apostrophe), which Gson would
    // unicode-escape in the JSON error payload.

    @Test
    public void testExecuteBothModulePathAndObjectName()
    {
        // Both targeting params given — the XOR is violated; the error must name BOTH.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue("both modulePath and objectName must be rejected", //$NON-NLS-1$
            result.contains("mutually exclusive")); //$NON-NLS-1$
        assertTrue(result.contains("modulePath")); //$NON-NLS-1$
        assertTrue(result.contains("objectName")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteModuleTypeWithModulePath()
    {
        // moduleType decorates objectName resolution only — with an explicit
        // modulePath it is meaningless and was silently ignored; now it is rejected
        // with a precise error naming moduleType.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "ManagerModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue("moduleType with modulePath must be rejected", //$NON-NLS-1$
            result.contains("moduleType applies only with objectName")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteSearchReplaceMissingOldSourceConditional()
    {
        // oldSource is conditionally required ONLY for mode=searchReplace; the error
        // names oldSource and the mode that needs it.
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
    public void testExecuteFormModuleMissingFormNameConditional()
    {
        // formName is conditionally required ONLY for moduleType=FormModule; the error
        // names formName and the moduleType that needs it.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "FormModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        // Names the exact missing param + its moduleType condition. "FormModule"
        // appears verbatim (no delimiter chars); the surrounding message has no
        // < > & = or apostrophe so it survives Gson HTML-escaping intact.
        assertTrue(result.contains("formName is required")); //$NON-NLS-1$
        assertTrue(result.contains("FormModule")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteCommandModuleMissingCommandNameConditional()
    {
        // commandName is conditionally required ONLY for moduleType=CommandModule.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("moduleType", "CommandModule"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("commandName is required")); //$NON-NLS-1$
        assertTrue(result.contains("CommandModule")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteBothModulePathAndObjectNameReturnsStructuredError()
    {
        // The XOR-conflict error reaches the client through the structured contract,
        // not a bare string or "Error:" prefix.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "Documents/MyDoc/ObjectModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", "Document.MyDoc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue("expected a structured JSON error object, got: " + result, //$NON-NLS-1$
            result.startsWith("{")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse(result.startsWith("Error:")); //$NON-NLS-1$
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
        String[] removedModes = { "replaceLines" }; //$NON-NLS-1$

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

    @Test
    public void testMethodTargetedModesRequireMethodName()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        for (String mode : new String[] { "replaceMethod", "insertBefore", "insertAfter" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Map<String, String> params = targetedParams(mode);
            params.remove("methodName"); //$NON-NLS-1$
            String result = tool.execute(params);
            assertTrue("mode=" + mode + " must require methodName: " + result, //$NON-NLS-1$ //$NON-NLS-2$
                result.contains("methodName is required")); //$NON-NLS-1$
        }
    }

    @Test
    public void testMethodTargetedModesRequireExpectedHash()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        for (String mode : new String[] { "replaceMethod", "insertBefore", "insertAfter" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Map<String, String> params = targetedParams(mode);
            params.remove("expectedHash"); //$NON-NLS-1$
            String result = tool.execute(params);
            assertTrue("mode=" + mode + " must require expectedHash: " + result, //$NON-NLS-1$ //$NON-NLS-2$
                result.contains("expectedHash is required")); //$NON-NLS-1$
            assertTrue(result.contains("read_module_source")); //$NON-NLS-1$
            assertTrue(result.contains("read_method_source")); //$NON-NLS-1$
        }
    }

    private static Map<String, String> targetedParams(String mode)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", mode); //$NON-NLS-1$
        params.put("methodName", "Target"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", "deadbeefdeadbeef"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "Procedure Target()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    @Test
    public void testMethodTargetedModesReachProjectValidationWithBothGuards()
    {
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        for (String mode : new String[] { "replaceMethod", "insertBefore", "insertAfter" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String result = tool.execute(targetedParams(mode));
            assertTrue("mode=" + mode + " should pass argument validation: " + result, //$NON-NLS-1$ //$NON-NLS-2$
                result.contains("Project not found")); //$NON-NLS-1$
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

    // ===== method-targeted edits: validation, unambiguous addressing, and spans =====

    @Test
    public void testReplaceMethodChangesOnlyTargetAndOwnsAnnotations()
    {
        List<String> current = lines(
            "Procedure First()\nEndProcedure\n\n" + //$NON-NLS-1$
            "// old target documentation\n&AtClient\nProcedure Target()\n\tOld = 1;\nEndProcedure\n\n" + //$NON-NLS-1$
            "Function Last()\nReturn 3;\nEndFunction\n"); //$NON-NLS-1$
        String replacement =
            "// new target documentation\n&AtServer\nProcedure Target()\n\tNew = 2;\nEndProcedure\n"; //$NON-NLS-1$

        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "replaceMethod", "Target", replacement); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.error);
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue(joined.contains("Procedure First()\nEndProcedure")); //$NON-NLS-1$
        assertTrue(joined.contains("Function Last()\nReturn 3;\nEndFunction")); //$NON-NLS-1$
        assertTrue(joined.contains("// new target documentation\n&AtServer\nProcedure Target()")); //$NON-NLS-1$
        assertFalse(joined.contains("old target documentation")); //$NON-NLS-1$
        assertFalse(joined.contains("&AtClient")); //$NON-NLS-1$
        assertFalse(joined.contains("Old = 1")); //$NON-NLS-1$
    }

    @Test
    public void testInsertBeforeLandsBeforeDocCommentAndAnnotation()
    {
        List<String> current = lines(
            "// target documentation\n&AtClient\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        String inserted = "Procedure Added()\nEndProcedure\n"; //$NON-NLS-1$

        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "insertBefore", "Target", inserted); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.error);
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue(joined.startsWith("Procedure Added()\nEndProcedure\n" + //$NON-NLS-1$
            "// target documentation\n&AtClient\nProcedure Target()")); //$NON-NLS-1$
        assertTrue("the annotation must remain adjacent to its declaration", //$NON-NLS-1$
            joined.contains("&AtClient\nProcedure Target()")); //$NON-NLS-1$
    }

    @Test
    public void testInsertBeforeKeepsContiguousDocCommentWithAnchor()
    {
        List<String> current = lines(
            "Procedure First()\nEndProcedure\n// line one\n// line two\n" + //$NON-NLS-1$
            "Procedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
            "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$

        assertNull(result.error);
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue(joined.contains("Procedure Added()\nEndProcedure\n// line one\n// line two\n" + //$NON-NLS-1$
            "Procedure Target()")); //$NON-NLS-1$
    }

    @Test
    public void testInsertAfterUsesRealTerminatorNotLongerIdentifier()
    {
        List<String> current = lines(
            "Procedure Target()\nEndProcedureResult = 1;\nStillInside = 2;\nEndProcedure\n" + //$NON-NLS-1$
            "Procedure Tail()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
            "Function Added()\nReturn 1;\nEndFunction\n"); //$NON-NLS-1$

        assertNull(result.error);
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue(joined.contains("EndProcedureResult = 1;\nStillInside = 2;\nEndProcedure\n" + //$NON-NLS-1$
            "Function Added()\nReturn 1;\nEndFunction\nProcedure Tail()")); //$NON-NLS-1$
    }

    @Test
    public void testMethodSourceWithZeroDeclarationsRejected()
    {
        assertMethodEditError("exactly one", WriteModuleSourceTool.applyMethodTargetedEdit( //$NON-NLS-1$
            simpleModule(), "replaceMethod", "Target", "Value = 1;\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testMissingMethodTargetRejected()
    {
        assertMethodEditError("was not found", WriteModuleSourceTool.applyMethodTargetedEdit( //$NON-NLS-1$
            simpleModule(), "replaceMethod", "Missing", //$NON-NLS-1$ //$NON-NLS-2$
            "Procedure Missing()\nEndProcedure\n")); //$NON-NLS-1$
    }

    @Test
    public void testMethodSourceWithTwoDeclarationsRejected()
    {
        String two = "Procedure Target()\nEndProcedure\nFunction Added()\nEndFunction\n"; //$NON-NLS-1$
        assertMethodEditError("exactly one", WriteModuleSourceTool.applyMethodTargetedEdit( //$NON-NLS-1$
            simpleModule(), "replaceMethod", "Target", two)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMethodSourceWithDetachedModuleCodeRejected()
    {
        String extra = "ModuleValue = 1;\nProcedure Target()\nEndProcedure\n"; //$NON-NLS-1$
        assertMethodEditError("module-level code", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(),
                "replaceMethod", "Target", extra)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMethodSourceWithTerminatorPrefixOnlyRejected()
    {
        String incomplete = "Procedure Target()\nEndProcedureResult = 1;\n"; //$NON-NLS-1$
        assertMethodEditError("no matching method terminator", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(),
                "replaceMethod", "Target", incomplete)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTargetWithTerminatorPrefixOnlyRejected()
    {
        List<String> incomplete = lines(
            "Procedure Target()\nEndProcedureResult = 1;\n"); //$NON-NLS-1$
        assertMethodEditError("target span is incomplete", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(incomplete,
                "replaceMethod", "Target", "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testUnterminatedTargetDoesNotBorrowTheNextMethodTerminator()
    {
        // Unbounded, the search past a missing EndProcedure finds the NEXT method's
        // terminator, calls Target complete, and replaceMethod then deletes Other and
        // its documentation along with the target.
        List<String> current = lines("Procedure Target()\n\tOld = 1;\n" //$NON-NLS-1$
            + "// Other documentation\nProcedure Other()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("target span is incomplete", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(current,
                "replaceMethod", "Target", "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTargetTerminatorSharingItsLineWithModuleCodeRejected()
    {
        // BSL allows module-level statements after the methods, so this terminator does
        // not end the line and the span cannot be measured in whole lines.
        List<String> current = lines("Procedure Target()\nEndProcedure; ModuleValue = 1;\n"); //$NON-NLS-1$
        // Refused by the unaddressable scan now, which names the shape instead of reporting the
        // symptom ("the span is incomplete") a line further on.
        assertMethodEditError("terminator sharing its line", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(current,
                "replaceMethod", "Target", "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testMethodSourceSmugglingCodeOnTheTerminatorLineRejected()
    {
        // The exactly-one-method contract has to hold for the payload too: the module-level
        // statement rides on the terminator line, where a line-based outside check misses it.
        String smuggled = "Procedure Target()\nEndProcedure; ModuleValue = DangerousCall();\n"; //$NON-NLS-1$
        assertMethodEditError("terminator sharing its line", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(),
                "replaceMethod", "Target", smuggled)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInsertBeforeKeepsABlankSeparatedAnnotationWithItsDeclaration()
    {
        List<String> current = lines("&AtClient\n\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "insertBefore", "Target", "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull(result.error);
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue(joined, joined.startsWith("Procedure Added()\nEndProcedure\n&AtClient")); //$NON-NLS-1$
        assertFalse("the inserted method must not capture Target's execution-context directive", //$NON-NLS-1$
            joined.contains("&AtClient\n\nProcedure Added()")); //$NON-NLS-1$
    }

    @Test
    public void testInsertBeforeKeepsAnAnnotationSeparatedByACommentAndABlankLine()
    {
        List<String> current = lines(
            "&AtClient\n// explanation\n\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "insertBefore", "Target", "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull(result.error);
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue(joined, joined.startsWith("Procedure Added()\nEndProcedure\n&AtClient")); //$NON-NLS-1$
    }

    /**
     * A declaration split across lines is real BSL that a whole-line scanner can neither locate
     * nor bound, so the module is REFUSED by name and line rather than edited around: the hidden
     * method is invisible both to the span search and to the duplicate-name check.
     */
    /**
     * The payload gets the same guard as the module: a declaration the scanner cannot address
     * hides INSIDE an otherwise one-method source, the outer method borrows the hidden one's
     * terminator, and the exactly-one-method contract would be satisfied by text that is not.
     */
    @Test
    public void testSourceHidingASplitDeclarationIsRefused()
    {
        String smuggled = "Procedure Added()\nFunction Hidden\n()\nEndFunction\nEndProcedure\n"; //$NON-NLS-1$
        assertMethodEditError("cannot address", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(), "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                smuggled));
    }
    @Test
    public void testAModuleWithASplitDeclarationIsRefusedByName()
    {
        List<String> current = lines("Procedure Target()\nEndProcedure\nProcedure Other\n()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("split across lines at line 3", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(current, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The collision this closes: the hidden declaration names a method the duplicate check could
     * not see, so an insert used to add a SECOND unconditional declaration of the same name.
     */
    @Test
    public void testInsertBesideASplitDeclarationCannotDuplicateItsName()
    {
        List<String> current =
            lines("Procedure Added\n()\nEndProcedure\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("split across lines", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(current, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    @Test
    public void testInsertBeforeDoesNotAbsorbABlankSeparatedCommentBlock()
    {
        // The mirror direction: a blank line carries a directive but still detaches
        // documentation, so the comment block stays where it is.
        List<String> current = lines("// detached documentation\n\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "insertBefore", "Target", "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull(result.error);
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue(joined, joined.startsWith("// detached documentation\n\nProcedure Added()")); //$NON-NLS-1$
    }

    @Test
    public void testReplaceMethodNameMismatchRejected()
    {
        assertMethodEditError("replaceMethod targets", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(),
                "replaceMethod", "Target", "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testReplaceMethodCannotUseAnotherExistingMethodName()
    {
        List<String> current = lines(
            "Procedure Target()\nEndProcedure\nProcedure Added()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("replaceMethod targets", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(current,
                "replaceMethod", "Target", "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testInsertExistingMethodNameRejectedForBothModes()
    {
        List<String> current = lines(
            "Procedure Target()\nEndProcedure\nProcedure Existing()\nEndProcedure\n"); //$NON-NLS-1$
        for (String mode : new String[] { "insertBefore", "insertAfter" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertMethodEditError("already exists", //$NON-NLS-1$
                WriteModuleSourceTool.applyMethodTargetedEdit(current, mode, "Target", //$NON-NLS-1$
                    "Procedure Existing()\nEndProcedure\n")); //$NON-NLS-1$
        }
    }

    @Test
    public void testRepeatedInsertIsRejectedWithoutDuplicate()
    {
        WriteModuleSourceTool.MethodEditResult first = WriteModuleSourceTool.applyMethodTargetedEdit(
            simpleModule(), "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
            "Procedure Once()\nEndProcedure\n"); //$NON-NLS-1$
        assertNull(first.error);
        WriteModuleSourceTool.MethodEditResult repeated =
            WriteModuleSourceTool.applyMethodTargetedEdit(first.newLines, "insertAfter", //$NON-NLS-1$
                "Target", "Procedure Once()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$

        assertMethodEditError("already exists", repeated); //$NON-NLS-1$
        assertEquals(1, countLinesContaining(first.newLines, "Procedure Once()")); //$NON-NLS-1$
    }

    @Test
    public void testAmbiguousMethodTargetInPreprocessorBranchesRejected()
    {
        List<String> current = lines(
            "#If Client Then\nProcedure Target()\nEndProcedure\n#Else\n" + //$NON-NLS-1$
            "Procedure Target()\nEndProcedure\n#EndIf\n"); //$NON-NLS-1$
        assertMethodEditError("ambiguous", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(current, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$
    }

    @Test
    public void testRussianFunctionCanBeReplacedWithRussianKeywords()
    {
        List<String> current = lines(
            "Функция Цель()\nВозврат 1;\nКонецФункции\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            current, "replaceMethod", "Цель", //$NON-NLS-1$ //$NON-NLS-2$
            "Функция Цель()\nВозврат 2;\nКонецФункции\n"); //$NON-NLS-1$
        assertNull(result.error);
        assertTrue(result.newLines.contains("Возврат 2;")); //$NON-NLS-1$
        assertFalse(result.newLines.contains("Возврат 1;")); //$NON-NLS-1$
    }

    private static List<String> simpleModule()
    {
        return lines("Procedure Target()\nEndProcedure\n"); //$NON-NLS-1$
    }

    private static List<String> lines(String source)
    {
        List<String> result = new ArrayList<>(Arrays.asList(source.split("\n", -1))); //$NON-NLS-1$
        if (source.endsWith("\n") && result.size() > 1 && result.get(result.size() - 1).isEmpty()) //$NON-NLS-1$
        {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static void assertMethodEditError(String expected,
        WriteModuleSourceTool.MethodEditResult result)
    {
        assertNotNull("expected method edit refusal", result.error); //$NON-NLS-1$
        assertNull(result.newLines);
        assertTrue(result.error, result.error.contains(expected));
        assertTrue(result.error, result.error.contains("\"success\":false")); //$NON-NLS-1$
    }

    private static int countLinesContaining(List<String> lines, String fragment)
    {
        int count = 0;
        for (String line : lines)
        {
            if (line.contains(fragment))
            {
                count++;
            }
        }
        return count;
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

    // ===== evaluateExpectedHash: cheap any-mode lost-update guard (pure) =====
    // expectedHash round-trips an opaque contentHash from a read tool; a change since
    // then is rejected with the same re-read steer as expectedSource, only cheaper.
    // Pure decision (no Eclipse workspace); the file-read wiring is covered by E2E.

    @Test
    public void testExpectedHashAbsentProceeds()
    {
        assertNull("no expectedHash -> no precondition", //$NON-NLS-1$
            WriteModuleSourceTool.evaluateExpectedHash("anything\n", null, true)); //$NON-NLS-1$
        assertNull("blank expectedHash -> no precondition", //$NON-NLS-1$
            WriteModuleSourceTool.evaluateExpectedHash("anything\n", "", true)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExpectedHashMatchProceeds()
    {
        String current = "Procedure A()\nEndProcedure\n"; //$NON-NLS-1$
        assertNull("a matching expectedHash must let the write proceed", //$NON-NLS-1$
            WriteModuleSourceTool.evaluateExpectedHash(current, ContentHash.of(current), true));
    }

    @Test
    public void testExpectedHashMismatchRereadSteer()
    {
        String current = "Procedure A()\nEndProcedure\n"; //$NON-NLS-1$
        String staleToken = ContentHash.of("Procedure A()\n// old body\nEndProcedure\n"); //$NON-NLS-1$
        String result = WriteModuleSourceTool.evaluateExpectedHash(current, staleToken, true);
        assertNotNull("a stale expectedHash must be rejected", result); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("does not match")); //$NON-NLS-1$
        assertTrue(result.contains("read_module_source")); //$NON-NLS-1$
    }

    @Test
    public void testExpectedHashOnMissingFileRejected()
    {
        // expectedHash given but the module does not exist -> nothing to match; reject
        // and steer (do not silently fall through to new-file creation).
        String result = WriteModuleSourceTool.evaluateExpectedHash(null, "deadbeefdeadbeef", false); //$NON-NLS-1$
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("does not exist")); //$NON-NLS-1$
    }

    @Test
    public void testExpectedHashMatchIgnoresCrlf()
    {
        // The token is line-ending agnostic: a CRLF current file matches an expectedHash
        // computed from LF content, so a CRLF/LF difference alone is not a spurious miss.
        String currentLf = "a\nb\n"; //$NON-NLS-1$
        String token = ContentHash.of("a\r\nb\r\n"); //$NON-NLS-1$
        assertNull(WriteModuleSourceTool.evaluateExpectedHash(currentLf, token, true));
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

    @Test
    public void testExpectedHashReachesProjectValidation()
    {
        // expectedHash is wired into execute() without disturbing resolution: with no
        // workspace, execute() still stops at "Project not found" before any file I/O.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "searchReplace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("oldSource", "x = 0;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", "deadbeefdeadbeef"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void testExpectedHashResolvesRussianObjectName()
    {
        // Language-agnostic: the cheap hash guard does not depend on the object's
        // language; a Russian object Name still resolves by its programmatic Name.
        WriteModuleSourceTool tool = new WriteModuleSourceTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("source", "x = 1;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectName", //$NON-NLS-1$
            "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041C\u043E\u0439\u0414\u043E\u043A"); //$NON-NLS-1$
        params.put("mode", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", "deadbeefdeadbeef"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertTrue(result.contains("Project not found")); //$NON-NLS-1$
    }

    // ==================== line-delimiter detection (issue #305) ====================

    @Test
    public void testDetectDelimiterCrlfDominant()
    {
        assertEquals("\r\n", WriteModuleSourceTool.detectDominantDelimiter("a\r\nb\r\nc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDetectDelimiterLfOnly()
    {
        assertEquals("\n", WriteModuleSourceTool.detectDominantDelimiter("a\nb\nc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDetectDelimiterMixedPrefersMajority()
    {
        // two CRLF vs one bare LF -> CRLF wins
        assertEquals("\r\n", WriteModuleSourceTool.detectDominantDelimiter("a\r\nb\r\nc\nd")); //$NON-NLS-1$ //$NON-NLS-2$
        // two bare LF vs one CRLF -> LF wins
        assertEquals("\n", WriteModuleSourceTool.detectDominantDelimiter("a\nb\nc\r\nd")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDetectDelimiterTieIsCrlf()
    {
        // one CRLF, one bare LF -> tie -> CRLF (the EDT/1C BSL convention)
        assertEquals("\r\n", WriteModuleSourceTool.detectDominantDelimiter("a\r\nb\nc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDetectDelimiterSingleLineOrEmptyIsNull()
    {
        assertNull(WriteModuleSourceTool.detectDominantDelimiter("single line, no break")); //$NON-NLS-1$
        assertNull(WriteModuleSourceTool.detectDominantDelimiter("")); //$NON-NLS-1$
        assertNull(WriteModuleSourceTool.detectDominantDelimiter(null));
    }

    /**
     * The trailing separator is dropped for ALL THREE spellings, not only the newline. A payload
     * ending in a bare carriage return splits into a trailing empty element exactly like one
     * ending in a newline, and keeping it spliced a blank line into the module.
     */
    @Test
    public void testSplitSourceLinesDropsTheTrailingElementForEverySeparator()
    {
        assertEquals(Arrays.asList("a", "b"), //$NON-NLS-1$ //$NON-NLS-2$
            WriteModuleSourceTool.splitSourceLines("a\rb\r")); //$NON-NLS-1$
        assertEquals(Arrays.asList("a", "b"), //$NON-NLS-1$ //$NON-NLS-2$
            WriteModuleSourceTool.splitSourceLines("a\nb\n")); //$NON-NLS-1$
        assertEquals(Arrays.asList("a", "b"), //$NON-NLS-1$ //$NON-NLS-2$
            WriteModuleSourceTool.splitSourceLines("a\r\nb\r\n")); //$NON-NLS-1$
        // No trailing separator at all: nothing to drop.
        assertEquals(Arrays.asList("a", "b"), //$NON-NLS-1$ //$NON-NLS-2$
            WriteModuleSourceTool.splitSourceLines("a\rb")); //$NON-NLS-1$
    }

    /**
     * The other edge of the same rule: exactly ONE trailing element goes, so the three
     * spellings arrive at the SAME list. Without this the widened condition could start eating
     * content instead of an artefact.
     * <p>
     * What this does NOT claim is that a blank line reaches the file: {@code writeFile} joins
     * {@code ["a", ""]} to {@code "a<delim>"} and appends nothing, so a trailing blank line is
     * normalized away - for every separator alike, which is the point. Before this change the
     * bare CR alone kept it, by the very inconsistency the fix removes.
     * </p>
     */
    @Test
    public void testSplitSourceLinesTreatsTheThreeSpellingsAlikeAtTheTail()
    {
        assertEquals(Arrays.asList("a", ""), //$NON-NLS-1$ //$NON-NLS-2$
            WriteModuleSourceTool.splitSourceLines("a\r\r")); //$NON-NLS-1$
        assertEquals(Arrays.asList("a", ""), //$NON-NLS-1$ //$NON-NLS-2$
            WriteModuleSourceTool.splitSourceLines("a\n\n")); //$NON-NLS-1$
        // A lone separator is a single empty line, not an empty list: size > 1 guards that.
        assertEquals(Arrays.asList(""), //$NON-NLS-1$
            WriteModuleSourceTool.splitSourceLines("\r")); //$NON-NLS-1$
    }

    /**
     * A second declaration written AFTER the opener on one line is invisible to every anchored
     * rule here: the scan counts one method, the balance check sees matching pairs, and the
     * result would be a method nested inside a method - invalid BSL reported as a success.
     */
    @Test
    public void testSourceDeclaringTwoMethodsOnOneLineRejected()
    {
        String crowded = "Procedure Added()\tFunction Hidden()\nEndFunction\nEndProcedure\n"; //$NON-NLS-1$
        assertMethodEditError("declares two methods", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(),
                "insertAfter", "Target", crowded)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same blindness in the MODULE: a span would run straight through the hidden declaration
     * and a replaceMethod would take it with the target.
     */
    @Test
    public void testModuleDeclaringTwoMethodsOnOneLineRejected()
    {
        List<String> crowded = lines(
            "Procedure Target() Function Hidden()\nEndFunction\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("declares two methods", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(crowded, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The other edge, and the one that keeps the rule usable: a reserved word is a legal MEMBER
     * name after a dot, so an ordinary statement may carry the keyword twice. Only a DECLARATION
     * line is examined, and only past its own opener.
     */
    @Test
    public void testKeywordsAsMemberNamesInABodyAreNotASecondDeclaration()
    {
        List<String> body = lines(
            "Procedure Target()\n\tX = Object.Procedure + Other.Function;\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(body, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member named after a keyword is not a declaration: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * A malformed target closed by the WRONG terminator must not reach past it for the right one.
     * Doing so made the later closer the target's end, and a replaceMethod deleted the
     * module-level statement standing between them - while the post-edit syntax check passed,
     * because the replacement carried neither closer.
     */
    @Test
    public void testTargetClosedByTheWrongTerminatorIsRefused()
    {
        List<String> malformed = lines(
            "Procedure Target()\nEndFunction\nModuleValue = DangerousCall();\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("no matching method terminator", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(malformed, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The control for that rule: the wrong KIND of terminator in a NEIGHBOURING method is none of
     * this span's business, and refusing there would take away the ordinary case.
     */
    @Test
    public void testANeighbouringFunctionTerminatorDoesNotBreakTheProcedureSpan()
    {
        List<String> module = lines(
            "Procedure Target()\n\tX = 1;\nEndProcedure\n\nFunction Other()\n\tReturn 2;\nEndFunction\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tX = 2;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("the neighbour's terminator is not this span's: " + result.error, result.error); //$NON-NLS-1$
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue("the neighbour must survive untouched: " + joined, //$NON-NLS-1$
            joined.contains("Function Other()\n\tReturn 2;\nEndFunction")); //$NON-NLS-1$
        assertTrue("and the target must have been replaced: " + joined, //$NON-NLS-1$
            joined.contains("X = 2;")); //$NON-NLS-1$
    }

    /**
     * BSL hides line breaks, so a body may begin on the declaration line - and a reserved word is
     * a legal member name after a dot. {@code Procedure Target() X = Object.Function();} is one
     * ordinary method, and the crowded-line rule must not read the member as a second declaration.
     */
    @Test
    public void testABodyStartingOnTheDeclarationLineIsNotASecondDeclaration()
    {
        List<String> module = lines(
            "Procedure Target() X = Object.Function();\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member call on the declaration line is not a declaration: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * The dangling member dot survives hidden trivia. {@code Value = Object.}, a comment line and
     * {@code EndFunction;} are the single expression {@code Object.EndFunction}, so the span must
     * walk past it to the real {@code EndProcedure} instead of calling the method malformed.
     */
    @Test
    public void testAMemberDotSurvivesACommentBeforeTheKeyword()
    {
        List<String> module = lines(
            "Procedure Target()\n\tValue = Object.\n\t// the member\n\tEndFunction;\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member named after a terminator is not a terminator: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * A pragma whose ARGUMENTS are split across lines is REFUSED, not delimited. Four different
     * shapes of it were each delimited wrongly here - a blank line before the declaration, the
     * parenthesis on its own line, a code line whose own balance is negative - and every wrong
     * boundary either rebinds the annotation to an inserted method or deletes the code above it.
     * A whole-line scanner may only edit what it can delimit.
     */
    @Test
    public void testASplitPragmaIsRefusedRatherThanDelimited()
    {
        List<String> module = lines(
            "&Instead(\n\t\"Original\")\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The mirror of the declaration case, on the TERMINATOR: {@code EndProcedure} after a dot is a
     * member name, so a body that starts on the declaration line with
     * {@code X = Object.EndProcedure;} is one ordinary method and must not be read as a method
     * that ends on its own opening line.
     */
    @Test
    public void testATerminatorNamedAsAMemberOnTheDeclarationLineIsNotATerminator()
    {
        List<String> module = lines(
            "Procedure Target() X = Object.EndProcedure;\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member named after a terminator is not a terminator: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * A second declaration whose parenthesis was moved to the next line is still a second
     * declaration: the scan would count one method and the balance check would see matching pairs.
     */
    @Test
    public void testASecondDeclarationWithItsParenthesisOnTheNextLineIsRejected()
    {
        List<String> crowded = lines(
            "Procedure Target() Procedure Hidden\n()\nEndProcedure\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("declares two methods", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(crowded, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * A declaration written after something else on the line - here the tail of a split pragma -
     * is invisible to every anchored rule, so the module holds a method this tool cannot count or
     * check for a duplicate name. It is refused rather than silently skipped.
     */
    @Test
    public void testADeclarationAfterAPragmaTailIsRefusedAsUnaddressable()
    {
        List<String> module = lines(
            "&Instead(\n\t\"Original\") Procedure Added()\nEndProcedure\n\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("cannot address", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The keyword alone, with its name on the next line: hidden whitespace makes it a real method
     * that the anchored scan cannot see, so a duplicate name could be inserted beside it.
     */
    @Test
    public void testADeclarationSplitBeforeItsNameIsRefusedAsUnaddressable()
    {
        List<String> module = lines(
            "Procedure\nAdded()\nEndProcedure\n\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("cannot address", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The edge that keeps that rule honest: a reserved word is a legal member name, so
     * {@code Value = Object.} followed by {@code Procedure} on its own line is ONE expression and
     * not a declaration at all.
     */
    @Test
    public void testAKeywordAloneAfterAMemberDotIsNotADeclaration()
    {
        List<String> module = lines(
            "Procedure Target()\n\tValue = Object.\n\tProcedure;\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member reached across a line break is not a declaration: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * The same refusal with a comment inside the pragma - and the reason it is a refusal rather
     * than another special case: the comment is one more shape the boundary has to be guessed
     * through, and a wrong guess here silently rebinds the annotation.
     */
    @Test
    public void testASplitPragmaWithACommentInsideIsRefusedToo()
    {
        List<String> module = lines(
            "&Instead(\n\t// why\n\t\"Original\")\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * Whitespace around the member dot is hidden trivia on BOTH sides, so neither a fixed-width
     * lookbehind nor a base-token walk that stops at the first space may decide what a word is.
     */
    @Test
    public void testWhitespaceAroundTheMemberDotDoesNotMakeAKeywordSyntax()
    {
        List<String> spacedAfter = lines(
            "Procedure Target() X = Object. EndProcedure;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member name after a spaced dot is not an inline terminator", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(spacedAfter, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n").error); //$NON-NLS-1$

        List<String> spacedBefore = lines(
            "Procedure Target()\n\tValue = Object .\n\tEndFunction;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a base separated from its dot is still a member access", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(spacedBefore, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n").error); //$NON-NLS-1$
    }

    /**
     * Punctuation inside a comment is not syntax, so the refusal must fire on the PRAGMA and not
     * on the comment's stray parenthesis - the reason has to name what is really wrong.
     */
    @Test
    public void testAPragmaWithAParenthesisInItsCommentIsRefusedForTheRightReason()
    {
        List<String> module = lines(
            "&Instead(\n\t// why )\n\t\"Original\")\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * A numeric literal ends in a dot too, and the dot before a terminator does not make it a
     * member name: "Value = 1. EndProcedure" really does end the method on its opening line, and
     * missing that let the span borrow a later closer and delete the code in between.
     */
    @Test
    public void testANumericLiteralBeforeAnInlineTerminatorIsNotAMemberAccess()
    {
        List<String> module = lines(
            "Procedure Target() Value = 1. EndProcedure\nModuleValue = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("cannot address", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The module that used to lose its previous method: a target standing after a method carrying
     * a split pragma was handed that whole method as its preamble and replaceMethod deleted it.
     * Now the module is refused before any of that, which is the outcome that cannot destroy
     * anything.
     */
    @Test
    public void testAModuleWhosePreviousMethodCarriesASplitPragmaIsRefused()
    {
        List<String> module = lines(
            "&Instead(\n\t\"Original\")\nProcedure Other()\nEndProcedure\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * A bare async modifier carrying a trailing comment is still part of the declaration below it.
     * Judged on the raw line the comment hid that, and an insertBefore bound Async to the inserted
     * method while quietly making the target synchronous.
     */
    @Test
    public void testAnAsyncModifierWithATrailingCommentStaysWithItsDeclaration()
    {
        List<String> module = lines(
            "Async // why\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("the insert must be accepted: " + result.error, result.error); //$NON-NLS-1$
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue("the new method must go above the modifier: " + joined, //$NON-NLS-1$
            joined.indexOf("Procedure Added()") < joined.indexOf("Async")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A pragma whose arguments were put on the line BELOW it looks complete on its own line, so a
     * per-line balance admits it - and the preamble then stops at the argument tail, leaving the
     * pragma outside the method it binds to.
     */
    @Test
    public void testAPragmaWhoseArgumentsStartOnTheNextLineIsRefused()
    {
        List<String> module = lines(
            "&Instead\n(\n\t\"Original\")\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * A pragma line carrying CODE after it: the walk owned the whole line as preamble, so a
     * replaceMethod cleared the statement riding on it together with the target - and the balance
     * check saw a healed result.
     */
    @Test
    public void testAPragmaLineCarryingCodeIsRefused()
    {
        List<String> module = lines(
            "&AtClient ModuleValue = DangerousCall();\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The control for the widened declaration shape: whitespace after a member dot is hidden, and
     * an operator is not a method name. "X = Object. Function + (1);" is an ordinary expression.
     */
    @Test
    public void testASpacedMemberExpressionIsNotADeclaration()
    {
        List<String> module = lines(
            "Procedure Target()\n\tX = Object. Function + (1);\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a spaced member expression is not a declaration: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * And the control that keeps ordinary pragmas working: a complete one on its own line, with or
     * without arguments, is still owned by the declaration below it.
     */
    @Test
    public void testACompletePragmaOnItsOwnLineIsStillOwned()
    {
        List<String> module = lines(
            "&Instead(\"Original\")\n&AtClient\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a one-line pragma must still be addressable: " + result.error, result.error); //$NON-NLS-1$
        String joined = String.join("\n", result.newLines); //$NON-NLS-1$
        assertTrue("the new method must go above both pragmas: " + joined, //$NON-NLS-1$
            joined.indexOf("Procedure Added()") < joined.indexOf("&Instead(")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whitespace between a pragma name and its arguments is hidden trivia, so "&Instead (...)" is
     * an ordinary one-line pragma and must stay addressable.
     */
    @Test
    public void testAPragmaWithSpaceBeforeItsArgumentsIsStillOwned()
    {
        List<String> module = lines(
            "&Instead (\"Original\")\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a spaced one-line pragma must stay addressable: " + result.error, result.error); //$NON-NLS-1$
    }

    /**
     * A reserved member reached ACROSS a line break is not a declaration: "X = Object." then
     * "Function And (Condition);" is the single expression Object.Function, and reading it as a
     * declaration named "And" bounded the enclosing method before its real terminator.
     */
    @Test
    public void testAMemberReachedAcrossALineBreakIsNotADeclaration()
    {
        List<String> module = lines(
            "Procedure Target()\n\tX = Object.\n\tFunction And (Condition);\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member across a line break is not a declaration: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * A method NAME has to be an identifier. "Procedure +()" was accepted as one complete method,
     * so the payload satisfied the exactly-one-method contract and invalid BSL was written - the
     * balance check only counts block keywords.
     */
    @Test
    public void testAPayloadWhoseMethodNameIsNotAnIdentifierIsRejected()
    {
        assertMethodEditError("exactly one", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(), "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure +()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The dangling-dot exemption belongs to EVERY per-line rule, not just the span scan: a member
     * call split after its dot ("Value = Object." then "EndFunction(Arg);") is one expression, and
     * the inline-terminator rule used to read it as a method ending on that line.
     */
    @Test
    public void testAMemberCallSplitAfterItsDotIsNotAnInlineTerminator()
    {
        List<String> module = lines(
            "Procedure Target()\n\tValue = Object.\n\tEndFunction(Arg);\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member call across a line break is not a terminator", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n").error); //$NON-NLS-1$
    }

    /**
     * The same expression split once more: "Value = Object.", "Function And", "(Condition);" is
     * still Object.Function And (Condition), and the split-declaration rule read the middle line as
     * a declaration whose parenthesis had been moved.
     */
    @Test
    public void testAMemberNameSplitFromItsArgumentsIsNotASplitDeclaration()
    {
        List<String> module = lines(
            "Procedure Target()\n\tValue = Object.\n\tFunction And\n\t(Condition);\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a member name across a line break is not a declaration", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "replaceMethod", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Target()\n\tNew = 1;\nEndProcedure\n").error); //$NON-NLS-1$
    }

    /**
     * A parameter list that never closes: the span was emitted as complete, and the balance check
     * counts only block keywords, so invalid BSL passed the exactly-one-method contract.
     */
    @Test
    public void testADeclarationWhoseParameterListDoesNotCloseIsRefused()
    {
        assertMethodEditError("parameter list does not close", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(), "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added(\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * A method named after a BLOCK keyword is refused through the checker's own set - the one this
     * tool already treats as authoritative - rather than through a keyword list invented here.
     */
    @Test
    public void testAMethodNamedAfterABlockKeywordIsRefused()
    {
        assertMethodEditError("named after a block keyword", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(simpleModule(), "insertAfter", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure If()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The walk that replaced the pragma regex must not be MORE permissive than it was: a nested
     * opener is not a closed argument list, and taking the first ")" for the matching one accepted
     * a malformed pragma the pattern rejected.
     */
    @Test
    public void testAPragmaWithANestedOpenerIsStillRefused()
    {
        List<String> module = lines(
            "&Instead((\"Original\")\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * And not more permissive about SPACING either: Character.isWhitespace accepts the C0
     * separators, which the replaced pattern did not, so pragmas glued together with a FILE
     * SEPARATOR must still be refused.
     */
    @Test
    public void testPragmasGluedWithAControlCharacterAreStillRefused()
    {
        List<String> module = lines(
            "&AtClient\u001C&AtServer\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("pragma this scanner cannot delimit", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }

    /**
     * The last direction the walk could differ from the pattern it replaced: a pragma name holding
     * a Unicode number outside Nd. The pattern accepted it, so the walk must too - otherwise a
     * module carrying such a pragma is refused for no reason.
     */
    @Test
    public void testAPragmaNameWithANonDecimalNumberIsStillOwned()
    {
        List<String> module = lines(
            "&X\u00B2(\"Original\")\nProcedure Target()\nEndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a pragma name is [\\p{L}\\p{N}_]+, not just letters and Nd: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * A parameter list wrapped onto the next line is ordinary formatting, not a broken
     * declaration: the newline inside it is hidden trivia. Refusing it would take every
     * method-targeted edit away from a module that merely wraps a long signature.
     */
    @Test
    public void testAParameterListThatWrapsOntoTheNextLineIsStillAddressable()
    {
        List<String> module = lines("Procedure ExportData(\n" //$NON-NLS-1$
            + "    Value, Options)\n" //$NON-NLS-1$
            + "EndProcedure\n" //$NON-NLS-1$
            + "\n" //$NON-NLS-1$
            + "Procedure Target()\n" //$NON-NLS-1$
            + "EndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a wrapped parameter list closes, just not on the first line: " + result.error, //$NON-NLS-1$
            result.error);
    }

    /**
     * The inverse of the dangling dot, and just as valid: the dot STARTS the continuation line.
     * "Value = Object" then ".EndFunction();" is one member call - a reserved word is a legal
     * member name - so the terminator spelling on that line does not close a method.
     */
    @Test
    public void testALeadingDotContinuationDoesNotLookLikeATerminator()
    {
        List<String> module = lines("Procedure Target()\n" //$NON-NLS-1$
            + "    Value = Object\n" //$NON-NLS-1$
            + "        .EndFunction();\n" //$NON-NLS-1$
            + "EndProcedure\n"); //$NON-NLS-1$
        WriteModuleSourceTool.MethodEditResult result =
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n"); //$NON-NLS-1$
        assertNull("a leading dot continues the line above it: " + result.error, result.error); //$NON-NLS-1$
    }

    /**
     * And the refusal the first of those two fixes relaxes must still fire on the shape it was
     * written for: a parameter list that never closes at all.
     */
    @Test
    public void testAParameterListThatNeverClosesIsStillRefused()
    {
        List<String> module = lines("Procedure Broken(\n" //$NON-NLS-1$
            + "    Value, Options\n" //$NON-NLS-1$
            + "EndProcedure\n" //$NON-NLS-1$
            + "\n" //$NON-NLS-1$
            + "Procedure Target()\n" //$NON-NLS-1$
            + "EndProcedure\n"); //$NON-NLS-1$
        assertMethodEditError("parameter list does not close", //$NON-NLS-1$
            WriteModuleSourceTool.applyMethodTargetedEdit(module, "insertBefore", "Target", //$NON-NLS-1$ //$NON-NLS-2$
                "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$
    }
}
