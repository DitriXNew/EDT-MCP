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
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetMethodCallHierarchyTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live EDT access. {@code execute()} validates
 * projectName, modulePath, the direction value and (for callers/callees) methodName
 * before the first {@code PlatformUI.getWorkbench()} call, so those returns are
 * reachable headlessly. Project/module resolution and the reference-finder walk need
 * a live workbench and are covered by the E2E suite.
 * <p>
 * The {@code outgoing} direction adds an aggregated outgoing-calls mode: methodName
 * is optional there, and the pure qualifier-classification / ext-API helpers
 * ({@link GetMethodCallHierarchyTool#qualifierKey} /
 * {@link GetMethodCallHierarchyTool#isExtApi}) are exercised here with in-memory
 * {@code BslFactory}-built model objects (no live workbench, editor or injector) —
 * matching the seam convention used by {@code SymbolInfoServiceTest}.
 */
public class GetMethodCallHierarchyToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_method_call_hierarchy", new GetMethodCallHierarchyTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMethodCallHierarchyTool.NAME, new GetMethodCallHierarchyTool().getName());
    }

    @Test
    public void testAggregationKeyIsCaseInsensitive()
    {
        // BSL identifiers are case-insensitive: Module.Method and module.method aggregate as one target.
        assertEquals(GetMethodCallHierarchyTool.aggregationKey("MyModule", "DoWork"), //$NON-NLS-1$ //$NON-NLS-2$
            GetMethodCallHierarchyTool.aggregationKey("mymodule", "dowork")); //$NON-NLS-1$ //$NON-NLS-2$
        // Distinct qualifiers or methods still map to distinct keys.
        assertFalse(GetMethodCallHierarchyTool.aggregationKey("A", "X") //$NON-NLS-1$ //$NON-NLS-2$
            .equals(GetMethodCallHierarchyTool.aggregationKey("B", "X"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GetMethodCallHierarchyTool.aggregationKey("A", "X") //$NON-NLS-1$ //$NON-NLS-2$
            .equals(GetMethodCallHierarchyTool.aggregationKey("A", "Y"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetMethodCallHierarchyTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetMethodCallHierarchyTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        // The slim description points at the on-demand guide channel.
        assertTrue(desc.contains("get_tool_guide('get_method_call_hierarchy')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // Exhaustive detail moved out of the description/schema and into getGuide().
        String guide = new GetMethodCallHierarchyTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("callers")); //$NON-NLS-1$
        assertTrue(guide.contains("callees")); //$NON-NLS-1$
        // The caller-discovery rationale (resolved feature entries) now lives only here.
        assertTrue(guide.contains("feature entry")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetMethodCallHierarchyTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"methodName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"direction\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresOutgoingDirectionAndExtApiPrefix()
    {
        // The aggregated outgoing mode adds a third direction enum value and the
        // optional extApiPrefix knob; both must be visible to schema-driven clients.
        String schema = new GetMethodCallHierarchyTool().getInputSchema();
        assertTrue("direction enum includes 'outgoing'", schema.contains("outgoing")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("extApiPrefix property declared", schema.contains("\"extApiPrefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaNoLongerRequiresMethodName()
    {
        // methodName was dropped from required[] (it is optional in outgoing mode).
        // The required[] list still holds projectName + modulePath, so assert methodName
        // is absent from that JSON array specifically (not from the whole schema, where
        // it still appears as a declared-but-optional property).
        String schema = new GetMethodCallHierarchyTool().getInputSchema();
        String required = extractRequiredArray(schema);
        assertNotNull("schema has a required[] array", required); //$NON-NLS-1$
        assertTrue("projectName still required", required.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("modulePath still required", required.contains("modulePath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("methodName is no longer required", required.contains("methodName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingMethodName()
    {
        // With the default direction (callers) a missing methodName is still an error.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("methodName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingMethodNameCalleesStillErrors()
    {
        // direction=callees + blank methodName -> the callers/callees methodName guard.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "callees"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("methodName is required for callers/callees")); //$NON-NLS-1$
    }

    @Test
    public void testOutgoingWithoutMethodNameSkipsMethodNameGuard()
    {
        // direction=outgoing makes methodName OPTIONAL: execute() must NOT return the
        // methodName-required error. It may still fail later on the live model (no
        // workbench here) - that is fine; we only assert it is not the methodName guard.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "outgoing"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = runWithoutLiveModel(() -> new GetMethodCallHierarchyTool().execute(params));
        // Whatever comes back (an error from the live-model path, or null), it must not
        // be the methodName-required message that guards callers/callees.
        if (result != null)
        {
            assertFalse("outgoing must not trip the methodName guard", //$NON-NLS-1$
                result.contains("methodName is required")); //$NON-NLS-1$
        }
    }

    @Test
    public void testInvalidDirection()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        // A non-empty direction that is none of the accepted values is rejected before any
        // workbench access (an empty/missing direction defaults to callers).
        params.put("direction", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("direction must be")); //$NON-NLS-1$
        // The error now enumerates all three accepted values.
        assertTrue(result.contains("outgoing")); //$NON-NLS-1$
    }

    // ==================== Call-qualifier derivation (pure logic) ====================

    @Test
    public void testExtractModuleNameCommonModule()
    {
        // The qualifier used in "Module.Method(...)" calls is the metadata object name,
        // i.e. the folder above the .bsl file.
        assertEquals("AccountingClientServer", //$NON-NLS-1$
            GetMethodCallHierarchyTool.extractModuleName("CommonModules/AccountingClientServer/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testExtractModuleNameManagerModule()
    {
        assertEquals("SalesOrder", //$NON-NLS-1$
            GetMethodCallHierarchyTool.extractModuleName("Documents/SalesOrder/ManagerModule.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testExtractModuleNameDegenerate()
    {
        assertNull(GetMethodCallHierarchyTool.extractModuleName(null));
        assertNull(GetMethodCallHierarchyTool.extractModuleName("Module.bsl")); //$NON-NLS-1$
    }

    // ==================== isExtApi — the bilingual ext-API prefix guard (pure) ====================

    @Test
    public void testIsExtApiDefaultPrefixMatchesCyrillicMember()
    {
        // A qualifier that starts with the default Cyrillic service-API prefix is ext-API.
        assertTrue(GetMethodCallHierarchyTool.isExtApi(
            "ПрограммныйИнтерфейсСервисаТовары", //$NON-NLS-1$ ПрограммныйИнтерфейсСервисаТовары
            GetMethodCallHierarchyTool.DEFAULT_EXT_API_PREFIX));
    }

    @Test
    public void testIsExtApiOrdinaryModuleIsNotExtApi()
    {
        // An ordinary common-module qualifier does not start with the prefix.
        assertFalse(GetMethodCallHierarchyTool.isExtApi(
            "AccountingClientServer", //$NON-NLS-1$
            GetMethodCallHierarchyTool.DEFAULT_EXT_API_PREFIX));
    }

    @Test
    public void testIsExtApiIsCaseInsensitive()
    {
        // The startsWith comparison is case-insensitive: a lower-cased spelling of the
        // default prefix is still recognized as ext-API.
        assertTrue(GetMethodCallHierarchyTool.isExtApi(
            "программныйинтерфейссервиса", //$NON-NLS-1$ программныйинтерфейссервиса (lower)
            GetMethodCallHierarchyTool.DEFAULT_EXT_API_PREFIX));
    }

    @Test
    public void testIsExtApiWithCustomPrefix()
    {
        // A caller-supplied prefix wins over the default (still literal + case-insensitive).
        assertTrue(GetMethodCallHierarchyTool.isExtApi("PublicApiOrders", "publicapi")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GetMethodCallHierarchyTool.isExtApi("AccountingClientServer", "publicapi")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsExtApiLocalAndExprTokensAreNeverExtApi()
    {
        // The synthetic '(local)' / '(expr)' qualifier tokens are always non-ext-API,
        // regardless of prefix (they cannot literally start with a real prefix word).
        assertFalse(GetMethodCallHierarchyTool.isExtApi("(local)", //$NON-NLS-1$
            GetMethodCallHierarchyTool.DEFAULT_EXT_API_PREFIX));
        assertFalse(GetMethodCallHierarchyTool.isExtApi("(expr)", //$NON-NLS-1$
            GetMethodCallHierarchyTool.DEFAULT_EXT_API_PREFIX));
    }

    // ==================== qualifierKey — call-shape classification (pure, in-memory model) ====================

    @Test
    public void testQualifierKeyUnqualifiedLocalCall()
    {
        // An unqualified local call: the method access is a StaticFeatureAccess -> '(local)'.
        StaticFeatureAccess sfa = BslFactory.eINSTANCE.createStaticFeatureAccess();
        sfa.setName("DoWork"); //$NON-NLS-1$
        assertEquals("(local)", GetMethodCallHierarchyTool.qualifierKey(sfa)); //$NON-NLS-1$
    }

    @Test
    public void testQualifierKeyQualifiedModuleCall()
    {
        // "MyModule.DoWork(...)": a DynamicFeatureAccess whose source is a StaticFeatureAccess
        // (the module reference) -> the qualifier is that module name.
        StaticFeatureAccess moduleRef = BslFactory.eINSTANCE.createStaticFeatureAccess();
        moduleRef.setName("MyModule"); //$NON-NLS-1$

        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName("DoWork"); //$NON-NLS-1$
        dfa.setSource(moduleRef);

        assertEquals("MyModule", GetMethodCallHierarchyTool.qualifierKey(dfa)); //$NON-NLS-1$
    }

    @Test
    public void testQualifierKeyChainedExpressionCall()
    {
        // A chained/expression call ("Foo().Bar()" or "a.b.Method()"): the source of the
        // DynamicFeatureAccess is NOT a StaticFeatureAccess -> '(expr)'.
        DynamicFeatureAccess chainedSource = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        chainedSource.setName("Intermediate"); //$NON-NLS-1$

        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName("Method"); //$NON-NLS-1$
        dfa.setSource(chainedSource);

        assertEquals("(expr)", GetMethodCallHierarchyTool.qualifierKey(dfa)); //$NON-NLS-1$
    }

    @Test
    public void testQualifierKeyDynamicWithNoSourceIsExpr()
    {
        // Defensive: a DynamicFeatureAccess with no source (null) must not NPE - it falls
        // through to the '(expr)' token like any non-StaticFeatureAccess source.
        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName("Method"); //$NON-NLS-1$
        assertEquals("(expr)", GetMethodCallHierarchyTool.qualifierKey(dfa)); //$NON-NLS-1$
    }

    // ==================== getResultFileName (pure, no live workbench) ====================

    @Test
    public void testResultFileNameWithMethodAndDirection()
    {
        // methodName + an explicit direction → "call-hierarchy-<method>-<direction>.md",
        // with the method name lower-cased and the direction appended verbatim.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "callees"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-dowork-callees.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameWithMethodDefaultsDirectionToCallers()
    {
        // methodName present but NO direction argument → the direction segment
        // defaults to "callers" (the (direction != null ? direction : KEY_CALLERS) branch).
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "Calculate"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-calculate-callers.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameLowercasesMethodName()
    {
        // The method-name segment is always lower-cased, so a mixed-case ru/en spelling
        // produces a stable, case-insensitive file name.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "ПолучитьДанные"); //$NON-NLS-1$ ПолучитьДанные
        params.put("direction", "callers"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-получитьданные-callers.md", //$NON-NLS-1$ получитьданные
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameOutgoingWithMethod()
    {
        // methodName + direction=outgoing → the direction segment is appended verbatim.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "outgoing"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-dowork-outgoing.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameBlankDirectionDefaultsToCallers()
    {
        // A blank (empty or whitespace-only) direction is normalized to null the same way
        // execute() does, so the direction segment falls back to "callers" instead of
        // leaking whitespace into the file name. Mirrors execute()'s defaulting.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-dowork-callers.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameOutgoingWhenMethodNameMissing()
    {
        // No methodName argument but direction=outgoing → the module-wide outgoing scope
        // has a distinct, descriptive file name (there is no method to name, so the
        // generic per-method pattern does not apply). Pins the dedicated outgoing branch.
        Map<String, String> params = new HashMap<>();
        params.put("direction", "outgoing"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-outgoing.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameDefaultWhenMethodNameEmpty()
    {
        // An explicitly empty methodName also falls back to the generic name
        // (the !methodName.isEmpty() guard).
        Map<String, String> params = new HashMap<>();
        params.put("methodName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameDefaultWhenParamsEmpty()
    {
        // The no-arguments case (neither methodName nor direction) returns the constant fallback.
        assertEquals("call-hierarchy.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(new HashMap<>()));
    }

    // ==================== depth: schema + the callers-only guard ====================

    @Test
    public void testSchemaDeclaresDepth()
    {
        String schema = new GetMethodCallHierarchyTool().getInputSchema();
        assertTrue("depth property declared", schema.contains("\"depth\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // The schema must say that depth>1 changes what `limit` MEANS, because it stops being an
        // output cap and becomes an execution budget.
        assertTrue("schema warns that limit bounds the walk itself", //$NON-NLS-1$
            schema.contains("execution budget")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionWarnsThatDynamicCallsAreInvisible()
    {
        // A caller reading "complete" must not read it as "nothing else calls this method": a
        // static scan cannot see Execute/Eval or a handler named by string. This belongs in the
        // contract the agent reads, not only in the response body.
        String desc = new GetMethodCallHierarchyTool().getDescription();
        assertTrue(desc.contains("STATIC invocations only")); //$NON-NLS-1$
        assertTrue(desc.contains("Execute/Eval")); //$NON-NLS-1$
    }

    @Test
    public void testDepthAboveOneIsRejectedForCallees()
    {
        // Rejected BEFORE any workbench access: the callees path reports raw invocation names and
        // never resolves them to a defining module, so recursing it would invent a graph.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "callees"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("depth", "3"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("depth above 1")); //$NON-NLS-1$
        // The error must point at what DOES work, not just refuse.
        assertTrue(result.contains("callers")); //$NON-NLS-1$
    }

    @Test
    public void testDepthAboveOneIsRejectedForOutgoing()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "outgoing"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("depth", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("depth above 1")); //$NON-NLS-1$
    }

    @Test
    public void testDepthClampedBackToOneIsAcceptedForEveryDirection()
    {
        // depth=0 clamps to 1, i.e. it asks for the single hop every direction supports. Rejecting
        // it would refuse a request that is identical to omitting the parameter.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "callees"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("depth", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = runWithoutLiveModel(() -> new GetMethodCallHierarchyTool().execute(params));
        if (result != null)
        {
            assertFalse("a clamped-to-1 depth must not trip the callers-only guard", //$NON-NLS-1$
                result.contains("depth above 1")); //$NON-NLS-1$
        }
    }

    @Test
    public void testDepthAboveMaxIsClampedNotRejected()
    {
        // A depth beyond the ceiling is clamped (house style for integer knobs), so it must reach
        // the live-model path rather than come back as an argument error.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("depth", "99"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = runWithoutLiveModel(() -> new GetMethodCallHierarchyTool().execute(params));
        if (result != null)
        {
            assertFalse(result.contains("depth above 1")); //$NON-NLS-1$
            assertFalse(result.contains("depth must be")); //$NON-NLS-1$
        }
    }

    @Test
    public void testDepthCeilingIsFive()
    {
        assertEquals(5, GetMethodCallHierarchyTool.MAX_DEPTH);
        assertEquals(1, GetMethodCallHierarchyTool.DEFAULT_DEPTH);
    }

    // ==================== the whole-identifier prefilter (pure) ====================

    @Test
    public void testPrefilterMatchesAWholeIdentifier()
    {
        assertTrue(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "Результат = Add(1, 2);", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$ Результат
    }

    @Test
    public void testPrefilterIsCaseInsensitive()
    {
        // BSL identifiers are case-insensitive, so the prefilter must not care how the call is spelled.
        assertTrue(GetMethodCallHierarchyTool.mentionsAnyIdentifier("X = ADD(1);", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GetMethodCallHierarchyTool.mentionsAnyIdentifier("X = aDd(1);", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPrefilterSurvivesUnusualSpacingAndLineBreaks()
    {
        // The token is the identifier, not the call shape: whitespace, newlines and a gap before the
        // parenthesis must not hide it. This is the "obviously true" claim that has to be pinned,
        // because the whole level-batched prefilter rests on it.
        assertTrue(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "Результат =\r\n\t Add \t (1,\n 2);", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$ Результат
        assertTrue("a call at the very end of the text still ends an identifier", //$NON-NLS-1$
            GetMethodCallHierarchyTool.mentionsAnyIdentifier("Add", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a call at the very start is found too", //$NON-NLS-1$
            GetMethodCallHierarchyTool.mentionsAnyIdentifier("Add(1);", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPrefilterRejectsASubstringOfALongerIdentifier()
    {
        // This is what makes ONE pass per level enough instead of one per name: a substring test
        // would parse this module for nothing. A real call to Add always writes Add as a whole token.
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "AddItem(1); ПодготовитьAddressКлиента();", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$ ПодготовитьAddressКлиента
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier("Add_2();", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier("PreAdd();", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPrefilterMatchesACyrillicIdentifier()
    {
        // 1C code is routinely written in Russian; the identifier class must include Cyrillic
        // letters, or a whole dialect would silently stop being prefiltered.
        assertTrue(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "Х = Вычисление.Маркер();", names("маркер"))); //$NON-NLS-1$ //$NON-NLS-2$ Х = Вычисление.Маркер(); маркер
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "МаркерОтгрузки();", names("маркер"))); //$NON-NLS-1$ //$NON-NLS-2$ МаркерОтгрузки маркер
    }

    @Test
    public void testPrefilterMatchesAnyOfSeveralNamesInOnePass()
    {
        // The level-batched search tests the WHOLE frontier against each file in one scan.
        assertTrue(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "Z = Third();", names("first", "second", "third"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "Z = Fourth();", names("first", "second", "third"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testPrefilterHandlesDegenerateInput()
    {
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier(null, names("add"))); //$NON-NLS-1$
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier("Add(1);", null)); //$NON-NLS-1$
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier("Add(1);", //$NON-NLS-1$
            new java.util.HashSet<>()));
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier("", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("punctuation only must terminate cleanly", //$NON-NLS-1$
            GetMethodCallHierarchyTool.mentionsAnyIdentifier("=== ;;; ///", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPrefilterSplitsOnACharacterBslCannotPutInAnIdentifier()
    {
        // A supplementary-plane letter is NOT a BSL identifier character - EDT's lexer treats it as
        // invalid input and still tokenizes the identifier next to it. The prefilter must therefore
        // SPLIT there, so the real call is still found and the file still becomes a candidate.
        //
        // The two possible mistakes are not symmetric, and that asymmetry is the whole rule:
        // splitting too eagerly costs one wasted module parse, while joining too eagerly hides a
        // real call behind a token that matches nothing - a lost caller with no trace in the
        // report. This test pins the safe direction.
        String outsideBmp = new String(Character.toChars(0x10400));
        assertTrue("the call to Add next to an invalid character must still be found", //$NON-NLS-1$
            GetMethodCallHierarchyTool.mentionsAnyIdentifier(
                "X = " + outsideBmp + "Add(1);", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Ordinary whole-identifier behaviour is unaffected on either side of the split.
        assertFalse(GetMethodCallHierarchyTool.mentionsAnyIdentifier(
            "X = " + outsideBmp + "AddItem(1);", names("add"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ==================== case folding: ONE relation, not two ====================

    @Test
    public void testFoldCaseAgreesWithEqualsIgnoreCase()
    {
        // The batched search looks a target up in a hash map and then judges it with
        // equalsIgnoreCase. If the two relations disagree anywhere, the map never offers the
        // target to the predicate and the caller goes missing with nothing to show it was skipped.
        // Samples the scripts BSL is actually written in, plus a SUPPLEMENTARY-plane pair, which
        // is where a per-char fold and equalsIgnoreCase part company. No BSL identifier reaches
        // outside the BMP, so that pair is defensive rather than a real caller being rescued - it
        // just removes the need to argue about where the two relations diverge.
        String[][] equalPairs = {
            {"DoWork", "dowork"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"DoWork", "DOWORK"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"ПолучитьДанные", "получитьданные"}, //$NON-NLS-1$ ПолучитьДанные получитьданные
            {"Маркер", "МАРКЕР"}, //$NON-NLS-1$ Маркер МАРКЕР
            {"Работа_2", "работа_2"}, //$NON-NLS-1$ Работа_2 работа_2
            // Deseret capital vs small long I: one code point each, outside the BMP.
            {new String(Character.toChars(0x10400)), new String(Character.toChars(0x10428))},
        };
        for (String[] pair : equalPairs)
        {
            assertTrue("equalsIgnoreCase says these are the same identifier: " //$NON-NLS-1$
                + pair[0] + " / " + pair[1], pair[0].equalsIgnoreCase(pair[1])); //$NON-NLS-1$
            assertEquals("so foldCase must map them to the same key: " //$NON-NLS-1$
                + pair[0] + " / " + pair[1], //$NON-NLS-1$
                GetMethodCallHierarchyTool.foldCase(pair[0]),
                GetMethodCallHierarchyTool.foldCase(pair[1]));
        }

        // And the converse: genuinely different identifiers must NOT collide.
        assertFalse(GetMethodCallHierarchyTool.foldCase("Add") //$NON-NLS-1$
            .equals(GetMethodCallHierarchyTool.foldCase("AddItem"))); //$NON-NLS-1$
        assertFalse(GetMethodCallHierarchyTool.foldCase("Маркер") //$NON-NLS-1$ Маркер
            .equals(GetMethodCallHierarchyTool.foldCase("Marker"))); //$NON-NLS-1$
    }

    @Test
    public void testFoldCaseHandlesDegenerateInput()
    {
        assertEquals("", GetMethodCallHierarchyTool.foldCase("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("_", GetMethodCallHierarchyTool.foldCase("_")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("123", GetMethodCallHierarchyTool.foldCase("123")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== helpers ====================

    /**
     * Builds the name set the prefilter takes, folded exactly the way the production caller folds
     * it. Building it with a different folding would test a set the tool never actually passes.
     *
     * @param values the identifiers to look for, in any case
     * @return the folded set
     */
    private static java.util.Set<String> names(String... values)
    {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String v : values)
        {
            out.add(GetMethodCallHierarchyTool.foldCase(v));
        }
        return out;
    }


    /**
     * Extracts the {@code "required":[...]} array literal from a built JSON schema so a
     * test can assert membership of that specific array (not the whole schema, where an
     * optional-but-declared property name also appears under {@code "properties"}).
     *
     * @param schema the JSON schema string
     * @return the text between the {@code required} array's brackets, or {@code null} when absent
     */
    private static String extractRequiredArray(String schema)
    {
        int key = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (key < 0)
        {
            return null;
        }
        int open = schema.indexOf('[', key);
        int close = schema.indexOf(']', open);
        if (open < 0 || close < 0)
        {
            return null;
        }
        return schema.substring(open, close + 1);
    }

    /**
     * Runs a supplier that may touch the live workbench, swallowing the class of failures
     * that only happen headlessly (no {@code PlatformUI} workbench / no injector). The
     * outgoing-mode test only cares that argument validation ran to completion without the
     * methodName guard firing; any downstream live-model failure is not this test's concern.
     *
     * @param call the tool invocation to run
     * @return the tool result, or {@code null} when a headless-only failure was thrown
     */
    private static String runWithoutLiveModel(java.util.function.Supplier<String> call)
    {
        try
        {
            return call.get();
        }
        catch (Exception | LinkageError e)
        {
            // No live workbench in this headless test - the argument-validation branch we
            // care about already ran (and returned an error string if it fired) before any
            // PlatformUI access, so a thrown workbench failure just means we got past it.
            return null;
        }
    }
}
