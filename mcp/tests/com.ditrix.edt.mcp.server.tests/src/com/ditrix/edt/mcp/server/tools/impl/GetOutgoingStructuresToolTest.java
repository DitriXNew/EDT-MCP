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

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FunctionStyleCreator;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.ReturnStatement;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.GetOutgoingStructuresTool.KeyCollector;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link GetOutgoingStructuresTool}.
 * <p>
 * Two layers, both Display-free:
 * <ul>
 * <li>Tool metadata + schema/output-schema parity + the argument-validation branches that
 * return before the first {@code PlatformUI.getWorkbench()} call ({@code projectName}/{@code
 * modulePath} guards).</li>
 * <li>The package-visible pure statics driven with in-memory {@code BslFactory} model objects
 * (no live workbench, editor, Xtext resource or injector): the {@code Вставить}/{@code Insert}
 * recognition, the qualifier filter, the qualified-call/first-arg shape checks, and the
 * StringLiteral-&gt;key reader including the multi-part-&gt;partial case.</li>
 * </ul>
 * Project/module resolution and the whole-method AST walk need a live workbench and are covered
 * by the E2E suite.
 */
public class GetOutgoingStructuresToolTest
{
    // ==================== Tool metadata ====================

    @Test
    public void testName()
    {
        assertEquals("get_outgoing_structures", new GetOutgoingStructuresTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetOutgoingStructuresTool.NAME, new GetOutgoingStructuresTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetOutgoingStructuresTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsAtGuide()
    {
        String desc = new GetOutgoingStructuresTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue(desc.contains("get_tool_guide('get_outgoing_structures')")); //$NON-NLS-1$
    }

    // ==================== Input schema ====================

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetOutgoingStructuresTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"method\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"qualifier\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"limit\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaMarksRequired()
    {
        String schema = new GetOutgoingStructuresTool().getInputSchema();
        // projectName + modulePath are the only required parameters.
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
    }

    // ==================== Output schema (success envelope) ====================

    @Test
    public void testOutputSchemaDeclaresSuccessEnvelope()
    {
        String schema = new GetOutgoingStructuresTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"success\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"structures\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"structureCount\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"truncated\"")); //$NON-NLS-1$
        // Output schema must stay permissive (see IMcpTool.getOutputSchema contract).
        assertFalse(schema.contains("additionalProperties")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetOutgoingStructuresTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetOutgoingStructuresTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    // ==================== method-not-found envelope must be parseable JSON ====================

    @Test
    public void testMethodNotFoundEnvelopeIsParseableJson()
    {
        // This tool declares ResponseType.JSON, so the not-found body (a bare markdown-ish string
        // from buildMethodNotFoundResponse) MUST ride inside a {"success":false,"error":"..."}
        // envelope — a raw string would make the JSON delivery path (JsonParser.parseString) throw.
        Module module = BslFactory.eINSTANCE.createModule();
        Procedure existing = BslFactory.eINSTANCE.createProcedure();
        existing.setName("Запуск"); //$NON-NLS-1$
        module.getMethods().add(existing);

        String body = BslModuleUtils.buildMethodNotFoundResponse(
            module, "CommonModules/MyModule/Module.bsl", "Отсутствует"); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ToolResult.error(body).toJson();

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertFalse("not-found must surface as success=false", parsed.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        String error = parsed.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("error names the missing method", error.contains("Отсутствует")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error carries the available-methods list", error.contains("Запуск")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== isInsert — the .Вставить/.Insert recognition ====================

    @Test
    public void testIsInsertRussian()
    {
        assertTrue(GetOutgoingStructuresTool.isInsert("Вставить")); //$NON-NLS-1$
    }

    @Test
    public void testIsInsertEnglish()
    {
        assertTrue(GetOutgoingStructuresTool.isInsert("Insert")); //$NON-NLS-1$
    }

    @Test
    public void testIsInsertCaseInsensitive()
    {
        assertTrue(GetOutgoingStructuresTool.isInsert("вСТАВИТЬ")); //$NON-NLS-1$
        assertTrue(GetOutgoingStructuresTool.isInsert("insert")); //$NON-NLS-1$
    }

    @Test
    public void testIsInsertRejectsOtherNames()
    {
        assertFalse(GetOutgoingStructuresTool.isInsert("Add")); //$NON-NLS-1$
        assertFalse(GetOutgoingStructuresTool.isInsert("Property")); //$NON-NLS-1$
        assertFalse(GetOutgoingStructuresTool.isInsert(null));
    }

    // ==================== qualifierMatches — the optional filter ====================

    @Test
    public void testQualifierNullFilterAcceptsEverything()
    {
        assertTrue(GetOutgoingStructuresTool.qualifierMatches("Модуль", null)); //$NON-NLS-1$
        assertTrue(GetOutgoingStructuresTool.qualifierMatches(null, null));
    }

    @Test
    public void testQualifierEqualityCaseInsensitive()
    {
        assertTrue(GetOutgoingStructuresTool.qualifierMatches("Модуль", "модуль")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testQualifierPrefixMatch()
    {
        assertTrue(GetOutgoingStructuresTool.qualifierMatches("CommonServer", "Common")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testQualifierNoMatch()
    {
        assertFalse(GetOutgoingStructuresTool.qualifierMatches("Other", "Common")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testQualifierNullWithFilterFails()
    {
        // An unresolved qualifier (e.g. a non-var source) never passes an explicit filter.
        assertFalse(GetOutgoingStructuresTool.qualifierMatches(null, "Common")); //$NON-NLS-1$
    }

    // ==================== asQualifiedCall / qualifierName / firstArgVar (call shape) ====================

    @Test
    public void testAsQualifiedCallForDynamicAccess()
    {
        StaticFeatureAccess source = BslFactory.eINSTANCE.createStaticFeatureAccess();
        source.setName("Модуль"); //$NON-NLS-1$
        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName("Метод"); //$NON-NLS-1$
        dfa.setSource(source);
        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(dfa);

        DynamicFeatureAccess got = GetOutgoingStructuresTool.asQualifiedCall(inv);
        assertNotNull(got);
        assertEquals("Модуль", GetOutgoingStructuresTool.qualifierName(got)); //$NON-NLS-1$
    }

    @Test
    public void testAsQualifiedCallNullForStaticAccess()
    {
        // An unqualified call (Метод(...)) has a StaticFeatureAccess method access: not outgoing-qualified.
        StaticFeatureAccess called = BslFactory.eINSTANCE.createStaticFeatureAccess();
        called.setName("Метод"); //$NON-NLS-1$
        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(called);

        assertNull(GetOutgoingStructuresTool.asQualifiedCall(inv));
    }

    @Test
    public void testFirstArgVarReturnsLocalVarRef()
    {
        StaticFeatureAccess arg = BslFactory.eINSTANCE.createStaticFeatureAccess();
        arg.setName("П"); //$NON-NLS-1$
        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(BslFactory.eINSTANCE.createStaticFeatureAccess());
        inv.getParams().add(arg);

        StaticFeatureAccess got = GetOutgoingStructuresTool.firstArgVar(inv);
        assertNotNull(got);
        assertEquals("П", got.getName()); //$NON-NLS-1$
    }

    @Test
    public void testFirstArgVarNullWhenNoArguments()
    {
        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(BslFactory.eINSTANCE.createStaticFeatureAccess());
        assertNull(GetOutgoingStructuresTool.firstArgVar(inv));
    }

    @Test
    public void testFirstArgVarNullWhenArgIsLiteral()
    {
        // A literal first argument (Модуль.Метод("x")) is not a local var ref → no keys to collect.
        StringLiteral literal = newLiteral("\"x\""); //$NON-NLS-1$
        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(BslFactory.eINSTANCE.createStaticFeatureAccess());
        inv.getParams().add(literal);
        assertNull(GetOutgoingStructuresTool.firstArgVar(inv));
    }

    // ==================== singleLiteralOrNull / addLiteralKey (StringLiteral -> key) ====================

    @Test
    public void testSingleLiteralCleanKey()
    {
        // A single-part literal "Дата" yields exactly the clean key.
        assertEquals("Дата", GetOutgoingStructuresTool.singleLiteralOrNull(newLiteral("\"Дата\""))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAddLiteralKeyCollectsCleanKey()
    {
        KeyCollector out = new KeyCollector();
        GetOutgoingStructuresTool.addLiteralKey(newLiteral("\"Сумма\""), out); //$NON-NLS-1$
        assertEquals(1, out.keys.size());
        assertEquals("Сумма", out.keys.get(0)); //$NON-NLS-1$
        assertFalse("clean literal must not flag partial", out.partial); //$NON-NLS-1$
    }

    @Test
    public void testAddLiteralKeyMultiPartFlagsPartialAndSkips()
    {
        // A multi-part/multi-line literal is unreliable: no key added, partial set.
        StringLiteral multi = BslFactory.eINSTANCE.createStringLiteral();
        multi.getLines().add("\"a"); //$NON-NLS-1$
        multi.getLines().add("|b\""); //$NON-NLS-1$

        KeyCollector out = new KeyCollector();
        GetOutgoingStructuresTool.addLiteralKey(multi, out);
        assertTrue(out.keys.isEmpty());
        assertTrue("multi-part literal must flag partial", out.partial); //$NON-NLS-1$
    }

    @Test
    public void testAddLiteralKeyNonLiteralFlagsPartial()
    {
        // A computed/variable key (not a StringLiteral) cannot be reported literally.
        StaticFeatureAccess varKey = BslFactory.eINSTANCE.createStaticFeatureAccess();
        varKey.setName("КлючПеременная"); //$NON-NLS-1$

        KeyCollector out = new KeyCollector();
        GetOutgoingStructuresTool.addLiteralKey(varKey, out);
        assertTrue(out.keys.isEmpty());
        assertTrue("non-literal key must flag partial", out.partial); //$NON-NLS-1$
    }

    // ==================== returnedVariableName (helper one-level expansion seam) ====================

    @Test
    public void testReturnedVariableNameFromSimpleReturn()
    {
        StaticFeatureAccess returned = BslFactory.eINSTANCE.createStaticFeatureAccess();
        returned.setName("Результат"); //$NON-NLS-1$
        ReturnStatement ret = BslFactory.eINSTANCE.createReturnStatement();
        ret.setExpression(returned);

        Procedure helper = BslFactory.eINSTANCE.createProcedure();
        helper.setName("Хелпер"); //$NON-NLS-1$
        helper.getStatements().add(ret);

        assertEquals("Результат", GetOutgoingStructuresTool.returnedVariableName(helper)); //$NON-NLS-1$
    }

    @Test
    public void testReturnedVariableNameNullWhenNoSimpleReturn()
    {
        Procedure helper = BslFactory.eINSTANCE.createProcedure();
        helper.setName("Хелпер"); //$NON-NLS-1$
        assertNull(GetOutgoingStructuresTool.returnedVariableName(helper));
    }

    // ==================== collectHelperSeed — Новый Структура(...) seed flags partial ====================

    @Test
    public void testOperatorStyleStructureSeedWithArgsFlagsPartial()
    {
        // П = Новый Структура("Дата,Сумма"): an OperatorStyleCreator with a non-empty constructor
        // argument list. Its constructor-string keys are not parsed, so the record is flagged partial.
        OperatorStyleCreator creator = BslFactory.eINSTANCE.createOperatorStyleCreator();
        creator.getParams().add(newLiteral("\"Дата,Сумма\"")); //$NON-NLS-1$
        SimpleStatement seed = seedAssignment("П", creator); //$NON-NLS-1$

        KeyCollector out = new KeyCollector();
        new GetOutgoingStructuresTool().collectHelperSeed(ownerMethod(seed), seed, "П", out); //$NON-NLS-1$

        assertTrue("Новый Структура(\"a,b\") seed must flag partial", out.partial); //$NON-NLS-1$
        assertTrue("no keys are parsed from the constructor string", out.keys.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testFunctionStyleStructureSeedWithArgsFlagsPartial()
    {
        // П = Новый("Структура", "Дата,Сумма"): a FunctionStyleCreator with a params expression.
        FunctionStyleCreator creator = BslFactory.eINSTANCE.createFunctionStyleCreator();
        StaticFeatureAccess typeName = BslFactory.eINSTANCE.createStaticFeatureAccess();
        typeName.setName("Структура"); //$NON-NLS-1$
        creator.setTypeNameExpression(typeName);
        creator.setParamsExpression(newLiteral("\"Дата,Сумма\"")); //$NON-NLS-1$
        SimpleStatement seed = seedAssignment("П", creator); //$NON-NLS-1$

        KeyCollector out = new KeyCollector();
        new GetOutgoingStructuresTool().collectHelperSeed(ownerMethod(seed), seed, "П", out); //$NON-NLS-1$

        assertTrue("Новый(\"Структура\", \"a,b\") seed must flag partial", out.partial); //$NON-NLS-1$
        assertTrue("no keys are parsed from the constructor string", out.keys.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testBareStructureSeedDoesNotFlagPartial()
    {
        // П = Новый Структура (no arguments): carries no keys, so the record stays non-partial —
        // this is the core happy-path seed and must not be mislabelled.
        OperatorStyleCreator creator = BslFactory.eINSTANCE.createOperatorStyleCreator();
        SimpleStatement seed = seedAssignment("П", creator); //$NON-NLS-1$

        KeyCollector out = new KeyCollector();
        new GetOutgoingStructuresTool().collectHelperSeed(ownerMethod(seed), seed, "П", out); //$NON-NLS-1$

        assertFalse("a bare Новый Структура seed must NOT flag partial", out.partial); //$NON-NLS-1$
        assertTrue(out.keys.isEmpty());
    }

    // ==================== core happy path (in-memory method walk) ====================

    @Test
    public void testCoreHappyPathSingleStructureWithTwoKeys()
    {
        // П.Вставить("Дата", …); П.Вставить("Сумма", …); Модуль.Метод(П);
        Procedure method = BslFactory.eINSTANCE.createProcedure();
        method.setName("Метод"); //$NON-NLS-1$
        method.getStatements().add(insertStatement("П", "Дата")); //$NON-NLS-1$ //$NON-NLS-2$
        method.getStatements().add(insertStatement("П", "Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        method.getStatements().add(outgoingCallStatement("Модуль", "Метод", "П")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonArray records = new JsonArray();
        boolean truncated = new GetOutgoingStructuresTool().collectMethodRecords(method, null, 100, records);

        assertFalse(truncated);
        // EXACTLY one structure: the two .Вставить calls are structure-building, not outgoing consumers.
        assertEquals(1, records.size());
        JsonObject rec = records.get(0).getAsJsonObject();
        assertEquals("Модуль", rec.get("qualifier").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Метод", rec.get("method").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("П", rec.get("arg").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray keys = rec.getAsJsonArray("keys"); //$NON-NLS-1$
        assertEquals(2, keys.size());
        assertEquals("Дата", keys.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("Сумма", keys.get(1).getAsString()); //$NON-NLS-1$
        // A clean single-part-literal build must NOT flag partial or viaHelper.
        assertFalse("partial must be unset", rec.has("partial")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("viaHelper must be unset", rec.has("viaHelper")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testQualifierFilterExcludesNonMatchingCall()
    {
        Procedure method = BslFactory.eINSTANCE.createProcedure();
        method.setName("Метод"); //$NON-NLS-1$
        method.getStatements().add(outgoingCallStatement("Модуль", "Метод", "П")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonArray records = new JsonArray();
        new GetOutgoingStructuresTool().collectMethodRecords(method, "Other", 100, records); //$NON-NLS-1$
        assertTrue("a non-matching qualifier filter drops the call", records.isEmpty()); //$NON-NLS-1$
    }

    // ==================== test helpers ====================

    /** Builds the statement {@code <var>.Вставить("<key>", ...)} as a SimpleStatement wrapping the call. */
    private static com._1c.g5.v8.dt.bsl.model.SimpleStatement insertStatement(String var, String key)
    {
        StaticFeatureAccess source = BslFactory.eINSTANCE.createStaticFeatureAccess();
        source.setName(var);
        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName("Вставить"); //$NON-NLS-1$
        dfa.setSource(source);
        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(dfa);
        inv.getParams().add(newLiteral("\"" + key + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        com._1c.g5.v8.dt.bsl.model.SimpleStatement stmt = BslFactory.eINSTANCE.createSimpleStatement();
        stmt.setLeft(inv);
        return stmt;
    }

    /** Builds the statement {@code <qualifier>.<method>(<argVar>)} as a SimpleStatement wrapping the call. */
    private static com._1c.g5.v8.dt.bsl.model.SimpleStatement outgoingCallStatement(String qualifier,
        String method, String argVar)
    {
        StaticFeatureAccess source = BslFactory.eINSTANCE.createStaticFeatureAccess();
        source.setName(qualifier);
        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName(method);
        dfa.setSource(source);
        StaticFeatureAccess arg = BslFactory.eINSTANCE.createStaticFeatureAccess();
        arg.setName(argVar);
        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(dfa);
        inv.getParams().add(arg);
        com._1c.g5.v8.dt.bsl.model.SimpleStatement stmt = BslFactory.eINSTANCE.createSimpleStatement();
        stmt.setLeft(inv);
        return stmt;
    }

    /** Builds the seed assignment {@code <var> = <right>} as a SimpleStatement. */
    private static SimpleStatement seedAssignment(String var,
        com._1c.g5.v8.dt.bsl.model.Expression right)
    {
        StaticFeatureAccess left = BslFactory.eINSTANCE.createStaticFeatureAccess();
        left.setName(var);
        SimpleStatement stmt = BslFactory.eINSTANCE.createSimpleStatement();
        stmt.setLeft(left);
        stmt.setRight(right);
        return stmt;
    }

    /**
     * Wraps {@code stmt} in a Procedure so {@code collectHelperSeed} has an enclosing method. For a
     * structure-constructor seed the partial flag is set before any module/helper lookup, so the
     * method need not be attached to a Module.
     */
    private static Procedure ownerMethod(SimpleStatement stmt)
    {
        Procedure method = BslFactory.eINSTANCE.createProcedure();
        method.setName("Метод"); //$NON-NLS-1$
        method.getStatements().add(stmt);
        return method;
    }

    /**
     * Builds a single-line {@link StringLiteral} from its raw stored token (INCLUDING the
     * enclosing quotes, e.g. {@code "\"Дата\""}), matching how EDT stores a BSL string
     * literal's {@code getLines()} so {@code BslUtil.getStringLiteralContent} yields the
     * clean content.
     */
    private static StringLiteral newLiteral(String rawLineWithQuotes)
    {
        StringLiteral literal = BslFactory.eINSTANCE.createStringLiteral();
        literal.getLines().add(rawLineWithQuotes);
        return literal;
    }
}
