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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorForm;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool.CommonModuleFlags;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool.CommonModuleKind;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lightweight contract tests for {@link CreateMetadataTool}: tool metadata and JSON schema,
 * without needing the Eclipse/EDT runtime. The {@code execute()} path requires a live workbench
 * and BM model, so the create / duplicate / property-rejection behaviour is covered by the E2E suite.
 */
public class CreateMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("create_metadata", new CreateMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(CreateMetadataTool.NAME, new CreateMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new CreateMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new CreateMetadataTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFqnGeneratorHasADistinctRoleCreationRefusal() throws Exception
    {
        String formMessage = privateStringConstant("ERR_NO_FQN_GENERATOR"); //$NON-NLS-1$
        String roleMessage = privateStringConstant("ERR_NO_ROLE_FQN_GENERATOR"); //$NON-NLS-1$

        assertEquals("ITopObjectFqnGenerator not available (needed to attach the content form under " //$NON-NLS-1$
            + "its canonical FQN)", formMessage); //$NON-NLS-1$
        assertFalse("the role guard must not reuse the form-specific refusal", //$NON-NLS-1$
            roleMessage.equals(formMessage));
        assertTrue("the role refusal must say that the role was not created", //$NON-NLS-1$
            roleMessage.contains("The role was not created")); //$NON-NLS-1$
        assertTrue("the role refusal must name the missing rights-model registration", //$NON-NLS-1$
            roleMessage.contains("register the role's rights model under its canonical FQN")); //$NON-NLS-1$
        assertTrue("the role refusal must explain the configurator-wide consequence", //$NON-NLS-1$
            roleMessage.contains("incremental configuration load would fail for the " //$NON-NLS-1$
                + "whole configuration")); //$NON-NLS-1$
        assertFalse("the role refusal must not name a content form", //$NON-NLS-1$
            roleMessage.contains("content form")); //$NON-NLS-1$
    }

    @Test
    public void testRoleCreationFailureAddsCreateContextWithoutNestingJson()
    {
        String writerMessage = "The registration is stale; run clean_project and retry the same call."; //$NON-NLS-1$

        JsonObject result = JsonParser.parseString(
            CreateMetadataTool.roleCreationFailure("Reader", writerMessage)).getAsJsonObject(); //$NON-NLS-1$

        assertEquals("Role 'Reader' was not created. " + writerMessage, //$NON-NLS-1$
            result.get("error").getAsString()); //$NON-NLS-1$
    }

    private static String privateStringConstant(String name) throws Exception
    {
        Field field = CreateMetadataTool.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String)field.get(null);
    }

    @Test
    public void testTopObjectIsCreatedThroughTheProjectAwareFactoryOverload()
    {
        // Issue #644: the version-only overload hands the type initializer a NULL project, so
        // project-dependent defaults (a catalog's data lock control mode, ...) are skipped.
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        IV8Project project = mock(IV8Project.class);
        EClass eClass = MdClassPackage.Literals.CATALOG;
        MdObject catalog = MdClassFactory.eINSTANCE.createCatalog();
        doReturn(catalog).when(factory).create(eClass, project);

        MdObject created = CreateMetadataTool.newTopObject(factory, eClass, project);

        assertSame("the object the project-aware overload built must be returned", catalog, created); //$NON-NLS-1$
        verify(factory).create(eClass, project);
        verify(factory, never()).create(any(EClass.class), any(Version.class));
    }

    @Test
    public void testExternalRootAddressIsRecognizedOnlyInAnExternalObjectsProject()
    {
        assertEquals(MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR,
            CreateMetadataTool.externalRootEClass(true, "ExternalDataProcessor.MyProc")); //$NON-NLS-1$
        assertEquals(MdClassPackage.Literals.EXTERNAL_REPORT,
            CreateMetadataTool.externalRootEClass(true, "ExternalReport.MyReport")); //$NON-NLS-1$
        assertNull("a configuration project never takes this branch", //$NON-NLS-1$
            CreateMetadataTool.externalRootEClass(false, "ExternalDataProcessor.MyProc")); //$NON-NLS-1$
        assertNull("a member address is not a new root", //$NON-NLS-1$
            CreateMetadataTool.externalRootEClass(true, "ExternalDataProcessor.MyProc.Attribute.A")); //$NON-NLS-1$
        assertNull("a configuration type is not an external root", //$NON-NLS-1$
            CreateMetadataTool.externalRootEClass(true, "Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void testExternalRootAddressAcceptsTheRussianTypeTokens()
    {
        assertEquals(MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR,
            CreateMetadataTool.externalRootEClass(true, "\u0412\u043D\u0435\u0448\u043D\u044F\u044F\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0430.MyProc")); //$NON-NLS-1$
        assertEquals(MdClassPackage.Literals.EXTERNAL_REPORT,
            CreateMetadataTool.externalRootEClass(true, "\u0412\u043D\u0435\u0448\u043D\u0438\u0439\u041E\u0442\u0447\u0435\u0442.MyReport")); //$NON-NLS-1$
    }

    @Test
    public void testExternalRootNameClashRefusesEveryTypeCaseInsensitively()
    {
        ExternalDataProcessor proc = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        proc.setName("Loader"); //$NON-NLS-1$
        ExternalReport report = MdClassFactory.eINSTANCE.createExternalReport();
        report.setName("Summary"); //$NON-NLS-1$
        List<MdObject> roots = Arrays.asList(proc, report);
        EClass edp = MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR;

        assertNull("a free name must pass", CreateMetadataTool.externalRootNameClash(roots, edp, //$NON-NLS-1$
            "Fresh", "ExternalDataProcessor.Fresh", "P", false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String sameType = CreateMetadataTool.externalRootNameClash(roots, edp, "LOADER", //$NON-NLS-1$
            "ExternalDataProcessor.LOADER", "P", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the same name in another case is a duplicate", sameType); //$NON-NLS-1$
        assertTrue(sameType, sameType.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(sameType, sameType.contains("already exists: ExternalDataProcessor.LOADER")); //$NON-NLS-1$

        String stale = CreateMetadataTool.externalRootNameClash(roots, edp, "Loader", //$NON-NLS-1$
            "ExternalDataProcessor.Loader", "P", true); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stale, stale.contains("Precondition failed")); //$NON-NLS-1$

        String otherType = CreateMetadataTool.externalRootNameClash(roots, edp, "Summary", //$NON-NLS-1$
            "ExternalDataProcessor.Summary", "P", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a report of the same name blocks a data processor too", otherType); //$NON-NLS-1$
        assertTrue(otherType, otherType.contains("ExternalReport.Summary")); //$NON-NLS-1$
        assertTrue(otherType, otherType.contains("distinct names")); //$NON-NLS-1$
        assertFalse(otherType, otherType.contains("already exists")); //$NON-NLS-1$
    }

    @Test
    public void testTakenRootIsReadFromTheWriteTransactionForBothRootTypes()
    {
        // The in-transaction re-check must ask the BM for BOTH root FQNs of the name, ignoring case:
        // the project's own root registry can lag a commit.
        IBmTransaction tx = mock(IBmTransaction.class);
        IBmObject report = mock(IBmObject.class);
        doReturn("ExternalReport.Loader").when(report).bmGetFqn(); //$NON-NLS-1$
        doReturn(Collections.emptyIterator()).when(tx)
            .getTopObjectsByFqnIgnoreCase("ExternalDataProcessor.LOADER"); //$NON-NLS-1$
        doReturn(Collections.singletonList(report).iterator()).when(tx)
            .getTopObjectsByFqnIgnoreCase("ExternalReport.LOADER"); //$NON-NLS-1$

        assertEquals("ExternalReport.Loader", CreateMetadataTool.takenRootFqn(tx, "LOADER")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(tx).getTopObjectsByFqnIgnoreCase("ExternalDataProcessor.LOADER"); //$NON-NLS-1$
        verify(tx).getTopObjectsByFqnIgnoreCase("ExternalReport.LOADER"); //$NON-NLS-1$

        IBmTransaction empty = mock(IBmTransaction.class);
        doReturn(Collections.emptyIterator()).when(empty).getTopObjectsByFqnIgnoreCase(any(String.class));
        assertNull("a free name must pass", CreateMetadataTool.takenRootFqn(empty, "Fresh")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"properties\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expectedNotExists\"")); //$NON-NLS-1$
        // The ё->е normalization toggle must be declared (execute() reads it; schema parity).
        assertTrue("schema must declare the normalizeYo toggle", //$NON-NLS-1$
            schema.contains("\"normalizeYo\"")); //$NON-NLS-1$
        // Create-time-only, type-specific options.
        assertTrue(schema.contains("\"commonModuleKind\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"serverCall\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"privileged\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"returnValuesReuse\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"targetNamespace\"")); //$NON-NLS-1$
        // Form-object create flag (execute() reads it; schema parity).
        assertTrue("schema must declare the setAsDefault form-object flag", //$NON-NLS-1$
            schema.contains("\"setAsDefault\"")); //$NON-NLS-1$
        // Form-object content-seeding flag (issue #208; execute() reads it; schema parity).
        assertTrue("schema must declare the generateContent form-object flag", //$NON-NLS-1$
            schema.contains("\"generateContent\"")); //$NON-NLS-1$
        // Form-object bound-field list (issue #208 round 2; execute() reads it; schema parity).
        assertTrue("schema must declare the objectFields form-object list", //$NON-NLS-1$
            schema.contains("\"objectFields\"")); //$NON-NLS-1$
        // Extension event-interception call type (execute() reads it; schema parity).
        assertTrue("schema must declare the callType form-event flag", //$NON-NLS-1$
            schema.contains("\"callType\"")); //$NON-NLS-1$
    }

    @Test
    public void testNestedSubsystemIsAdvertisedOnTheWire()
    {
        // Issue #351: create_metadata now creates a nested subsystem, and the wire surface has to
        // say so - modify_metadata already documents the same chain, and the two must not disagree
        // about what is addressable. Both texts are checked: the tool description is what a client
        // reads in tools/list, the fqn schema is what a schema-driven client builds its input from.
        String desc = new CreateMetadataTool().getDescription();
        assertTrue("the description must advertise the nested-subsystem address", //$NON-NLS-1$
            new CreateMetadataTool().getGuide().contains("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$
        String schema = new CreateMetadataTool().getInputSchema();
        assertTrue("the fqn schema must document the nested-subsystem shape", //$NON-NLS-1$
            schema.contains("'Subsystem.<Parent>.Subsystem.<Child>'")); //$NON-NLS-1$
    }

    @Test
    public void testGenerateContentIsOptional()
    {
        // generateContent is a form-object-create flag, defaults false -> must not be required.
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("generateContent must not be required (defaults false)", //$NON-NLS-1$
            tail.contains("\"generateContent\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresGenerateContent()
    {
        // Output parity: a form-object create echoes generateContent in the result payload.
        String schema = new CreateMetadataTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("output schema must declare generateContent", //$NON-NLS-1$
            schema.contains("\"generateContent\"")); //$NON-NLS-1$
    }

    @Test
    public void testObjectFieldsIsOptionalStringArray()
    {
        // objectFields (issue #208 round 2) is a form-object-create list, optional (defaults to the
        // per-kind fields), declared as an array of strings. Scope the type check to its property block.
        String schema = new CreateMetadataTool().getInputSchema();
        int idx = schema.indexOf("\"objectFields\""); //$NON-NLS-1$
        assertTrue("schema must declare objectFields", idx >= 0); //$NON-NLS-1$
        // The property block runs up to the next property (callType is declared right after it).
        int nextIdx = schema.indexOf("\"callType\"", idx); //$NON-NLS-1$
        String block = nextIdx > idx ? schema.substring(idx, nextIdx) : schema.substring(idx);
        assertTrue("objectFields must be an array", block.contains("\"array\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("objectFields items must be strings", block.contains("\"string\"")); //$NON-NLS-1$ //$NON-NLS-2$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        assertFalse("objectFields must not be required (defaults to the per-kind fields)", //$NON-NLS-1$
            schema.substring(requiredIdx).contains("\"objectFields\"")); //$NON-NLS-1$
    }

    @Test
    public void testSetAsDefaultIsOptional()
    {
        // setAsDefault is a form-object-create flag, defaults false -> must not be required.
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("setAsDefault must not be required (defaults false)", //$NON-NLS-1$
            tail.contains("\"setAsDefault\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresSetAsDefault()
    {
        // Output parity: a form-object create echoes setAsDefault in the result payload.
        String schema = new CreateMetadataTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("output schema must declare setAsDefault", //$NON-NLS-1$
            schema.contains("\"setAsDefault\"")); //$NON-NLS-1$
    }

    @Test
    public void testCallTypeIsOptionalClosedEnum()
    {
        // Extension event interception: callType is optional (defaults to a base handler) and a closed
        // enum offering exactly the three form-event call types.
        String schema = new CreateMetadataTool().getInputSchema();
        int callTypeIdx = schema.indexOf("\"callType\""); //$NON-NLS-1$
        assertTrue("schema must declare callType", callTypeIdx >= 0); //$NON-NLS-1$
        // Scope the enum/literal checks to the callType property block (up to the next property) so the
        // closed-enum assertion is about callType itself, not a later enum property.
        int nextIdx = schema.indexOf("\"commonModuleKind\"", callTypeIdx); //$NON-NLS-1$
        String block = nextIdx > callTypeIdx ? schema.substring(callTypeIdx, nextIdx) : schema.substring(callTypeIdx);
        assertTrue("callType must be a closed enum", block.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("callType enum must offer Before", block.contains("\"Before\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("callType enum must offer After", block.contains("\"After\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("callType enum must offer Instead", block.contains("\"Instead\"")); //$NON-NLS-1$ //$NON-NLS-2$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        assertFalse("callType must not be required (defaults to a base handler)", //$NON-NLS-1$
            schema.substring(requiredIdx).contains("\"callType\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresCallType()
    {
        // Output parity: an extension event handler echoes the written callType.
        String schema = new CreateMetadataTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("output schema must declare callType", //$NON-NLS-1$
            schema.contains("\"callType\"")); //$NON-NLS-1$
    }

    @Test
    public void testCommonModuleKindIsDeclaredAsAClosedEnum()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        // The kind must be a closed JSON-Schema enum carrying every canonical kind token.
        int kindIdx = schema.indexOf("\"commonModuleKind\""); //$NON-NLS-1$
        assertTrue("schema must declare commonModuleKind", kindIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(kindIdx);
        assertTrue("commonModuleKind must be a closed enum", tail.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        for (CommonModuleKind k : CommonModuleKind.values())
        {
            assertTrue("enum must list the '" + k.token() + "' kind", //$NON-NLS-1$ //$NON-NLS-2$
                schema.contains("\"" + k.token() + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // returnValuesReuse is likewise a closed enum.
        assertTrue("returnValuesReuse must offer DuringSession", //$NON-NLS-1$
            schema.contains("\"DuringSession\"")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeYoIsOptional()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("normalizeYo must not be required (defaults true)", //$NON-NLS-1$
            tail.contains("\"normalizeYo\"")); //$NON-NLS-1$
    }

    @Test
    public void testNewOptionalParametersAreNotRequired()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("commonModuleKind must not be required", tail.contains("\"commonModuleKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("targetNamespace must not be required", tail.contains("\"targetNamespace\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ── Pure CommonModule flag-resolution (no workbench / BM model needed) ──────────────────────

    private static Map<String, String> params(String... kv)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void testResolveDefaultsToServerKind()
    {
        // No commonModuleKind -> the default 'Server' canonical combo (the validator-accepted one).
        CommonModuleFlags f = CommonModuleFlags.resolve(params());
        assertEquals(CommonModuleKind.SERVER, f.kind);
        assertTrue("default Server module must be server-side", f.server); //$NON-NLS-1$
        assertFalse("default Server module is not a server call", f.serverCall); //$NON-NLS-1$
        assertTrue("default Server module sets external connection", f.externalConnection); //$NON-NLS-1$
        assertTrue("default Server module sets client-ordinary", f.clientOrdinaryApplication); //$NON-NLS-1$
        assertEquals(ReturnValuesReuse.DONT_USE, f.returnValuesReuse);
    }

    @Test
    public void testResolveServerCallKindSetsServerCallCombo()
    {
        // ServerCall is the canonical server + server-call combo with no client flags.
        CommonModuleFlags f = CommonModuleFlags.resolve(params("commonModuleKind", "ServerCall")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(CommonModuleKind.SERVER_CALL, f.kind);
        assertTrue("ServerCall must be a server module", f.server); //$NON-NLS-1$
        assertTrue("ServerCall must set the server-call flag", f.serverCall); //$NON-NLS-1$
        assertFalse("ServerCall sets no client flags", f.clientManagedApplication); //$NON-NLS-1$
        assertFalse("ServerCall sets no client flags", f.clientOrdinaryApplication); //$NON-NLS-1$
        assertFalse("ServerCall must not set external connection", f.externalConnection); //$NON-NLS-1$
    }

    @Test
    public void testResolveServerCallCachedYieldsDuringSession()
    {
        // ServerCall + DuringSession -> the cached server-call combo (a validator-accepted variant).
        CommonModuleFlags f = CommonModuleFlags.resolve(
            params("commonModuleKind", "ServerCall", "returnValuesReuse", "DuringSession")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(ReturnValuesReuse.DURING_SESSION, f.returnValuesReuse);
        assertTrue(f.serverCall);
    }

    @Test
    public void testResolveServerCallOnClientKindIsRejected()
    {
        // An illegal flag combo (serverCall on a pure client kind) must throw BEFORE any model
        // access - the validator would otherwise reject the arbitrary flag set.
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "ClientManaged", "serverCall", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("serverCall on a client kind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must name serverCall", e.getMessage().contains("serverCall")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testResolveServerCallOnGlobalKindIsRejectedNamingGlobal()
    {
        // The former dedicated Global+serverCall branch was dead code (the non-server-kind
        // check above it throws first); its specificity is folded INTO that first check:
        // for kind 'Global' the message must name Global explicitly and explain why.
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "Global", "serverCall", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("serverCall on the Global kind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must name serverCall", e.getMessage().contains("serverCall")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("message must name the Global kind", e.getMessage().contains("'Global'")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("message must explain the Global incompatibility", //$NON-NLS-1$
                e.getMessage().contains("cannot be a server-call target")); //$NON-NLS-1$
        }
    }

    @Test
    public void testResolvePrivilegedOnNonServerKindIsRejected()
    {
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "Global", "privileged", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("privileged on a non-Server kind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must name privileged", e.getMessage().contains("privileged")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testResolveUnknownKindIsRejected()
    {
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "NotAKind")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an unknown commonModuleKind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must echo the bad token", e.getMessage().contains("NotAKind")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testResolveDuringRequestHasNoCanonicalCombo()
    {
        try
        {
            CommonModuleFlags.resolve(params("returnValuesReuse", "DuringRequest")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("DuringRequest has no standards-compliant combo and must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must mention DuringRequest", //$NON-NLS-1$
                e.getMessage().contains("DuringRequest")); //$NON-NLS-1$
        }
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalParametersNotRequired()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("properties must not be required", tail.contains("\"properties\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("expectedNotExists must not be required", //$NON-NLS-1$
            tail.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideCarriesKeyDetail()
    {
        String guide = new CreateMetadataTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // bilingual synonym detail retained
        assertTrue("guide should keep the language CODE detail", guide.contains("language CODE")); //$NON-NLS-1$ //$NON-NLS-2$
        // member kinds documented
        assertTrue("guide should list member kinds", guide.contains("EnumValue")); //$NON-NLS-1$ //$NON-NLS-2$
        // nested-object members (e.g. a tabular-section attribute) are now supported and documented
        assertTrue("guide should document nested-object members", //$NON-NLS-1$
            guide.contains("tabular-section attribute")); //$NON-NLS-1$
    }

    /**
     * A second create of a command's action is refused, and the guide says what the refusal names -
     * the bound procedure and the route that changes it - so an agent that met an action it did not
     * know about learns the way out before it guesses (issue #628).
     */
    @Test
    public void testTheGuideSaysACommandHasOneActionAndHowToChangeIt()
    {
        String guide = new CreateMetadataTool().getGuide();
        assertTrue(guide.contains("A command has ONE action: a second create on the same FQN is " //$NON-NLS-1$
            + "refused, naming the procedure it is bound to and the route that changes it")); //$NON-NLS-1$
        // The rebind is for a single-handler action only; the route is not promised to every shape.
        assertTrue(guide.contains("modify_metadata on that FQN with `procedure` rebinds a " //$NON-NLS-1$
            + "single-handler action; delete_metadata removes an action of any shape - the only route " //$NON-NLS-1$
            + "for an extension's interception list, which cannot be rebound")); //$NON-NLS-1$
    }

    /**
     * Binding to a procedure that already exists changes only the form, and EDT does not re-check the
     * form module, so its unused-method / event-region warnings stay until the owner is revalidated -
     * measured on EDT 2026.2 for a command action and an item event handler alike. The guide gives the
     * way out, so an agent does not read a working binding as a dead one (issue #628, item 3).
     */
    @Test
    public void testTheGuideSaysHowToClearTheWarningsABindingToAnExistingProcedureLeaves()
    {
        String guide = new CreateMetadataTool().getGuide();
        assertTrue(guide.contains("to a procedure that ALREADY exists changes only the form, and EDT does " //$NON-NLS-1$
            + "not re-check the form module then")); //$NON-NLS-1$
        assertTrue(guide.contains("until the owner object is revalidated (revalidate_objects with the " //$NON-NLS-1$
            + "owner")); //$NON-NLS-1$
        assertTrue(guide.contains("Binding first and writing the procedure afterwards avoids them.")); //$NON-NLS-1$
    }

    // ===== XDTO package member creation (issue #183 stream 1) - schema/description contract ==========
    //
    // create_metadata's execute() needs a live workbench + BM model, so the ObjectType/Property write
    // path itself (XdtoWriter.createObjectType / createProperty / applyObjectTypeProperties /
    // applyPropertyProperties, the FQN grammar XdtoWriter.parseMemberRef) is unit-tested headlessly in
    // XdtoWriterTest; the live materialize + attach + force-export is covered by the E2E suite. Here:
    // the wire-contract surface (description / schema) documents the new FQN shapes and vocabulary.

    @Test
    public void testDescriptionDocumentsXdtoPackageMembers()
    {
        String desc = new CreateMetadataTool().getDescription();
        assertTrue("description should mention the ObjectType member FQN shape", //$NON-NLS-1$
            new CreateMetadataTool().getGuide().contains("ObjectType")); //$NON-NLS-1$
        assertTrue("description should mention a nested Property member FQN shape", //$NON-NLS-1$
            new CreateMetadataTool().getGuide().contains("XDTOPackage.<Package>.ObjectType.<Type>.Property.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void testPropertiesDescriptionDocumentsXdtoVocabulary()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        // The 'properties' array is reused (not a new payload key) for XDTO members - its description
        // must document the different vocabulary (ObjectType flags, Property attributes incl. the
        // REQUIRED 'type').
        int propsIdx = schema.indexOf("\"properties\""); //$NON-NLS-1$
        assertTrue(propsIdx >= 0);
        String tail = schema.substring(propsIdx);
        assertTrue("properties doc should mention the ObjectType 'open' flag", tail.contains("'open'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("properties doc should mention the REQUIRED Property 'type'", //$NON-NLS-1$
            tail.contains("REQUIRES 'type'")); //$NON-NLS-1$
        assertTrue("properties doc should mention 'lowerBound'/'upperBound'", //$NON-NLS-1$
            tail.contains("lowerBound")); //$NON-NLS-1$
    }

    @Test
    public void testCreateMemberOwnerLookupToleratesYoSpelledObjectTypeName()
    {
        // issue #183 P2 #4: createXdtoMemberInTx's OBJECT_TYPE_PROPERTY branch (creating a nested
        // Property) looks up its OWNER ObjectType via XdtoWriter.findObjectType, using the FQN's OWNER
        // segment - which, unlike the FQN LEAF, is NEVER yo-normalized on the way in
        // (CreateMetadataTool#normalizeLeafName only touches the trailing leaf segment). When the
        // ObjectType itself was created earlier from a yo-spelled name (and is therefore stored
        // yo-normalized), a LATER nested-Property create whose FQN still spells the OWNER segment with
        // the original "yo" must still resolve it - findObjectType now falls back to the yo-normalized
        // stored name on an exact miss.
        com._1c.g5.v8.dt.xdto.model.Package pkg =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createPackage();
        com._1c.g5.v8.dt.xdto.model.ObjectType owner =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createObjectType();
        // "Zakaz-e" (a Russian word for "order"), yo-normalized - the spelling create_metadata stores.
        owner.setName(MetadataLanguageUtils.cp(0x0417, 0x0430, 0x043a, 0x0430, 0x0437, 0x0435));
        pkg.getObjects().add(owner);

        // The SAME word, but spelled with the ORIGINAL "yo" - as a later nested-Property create's FQN
        // owner segment might still be.
        String yoSpelledOwnerName = MetadataLanguageUtils.cp(0x0417, 0x0430, 0x043a, 0x0430, 0x0437, 0x0451);
        assertEquals("the OBJECT_TYPE_PROPERTY owner lookup must tolerate a yo-spelled owner segment", //$NON-NLS-1$
            owner, com.ditrix.edt.mcp.server.utils.XdtoWriter.findObjectType(pkg, yoSpelledOwnerName));
    }

    @Test
    public void testOutputSchemaDeclaresApplied()
    {
        String schema = new CreateMetadataTool().getOutputSchema();
        assertTrue("output schema must declare 'applied' (XDTO member create counts)", //$NON-NLS-1$
            schema.contains("\"applied\"")); //$NON-NLS-1$
    }

    // ==================== predefined-item dispatch (issue #293) ====================

    /**
     * The create dispatch routes a 4-part predefined-item FQN ({@code Type.Owner.Predefined.Item}) to
     * its dedicated branch via {@link PredefinedWriter#parseRef} - this asserts the recognizer the
     * dispatch keys off, runtime-free (mirrors {@code DeleteMetadataToolTest
     * .testFormObjectFqnRecognizedByDeleteDispatch}).
     */
    @Test
    public void testPredefinedItemFqnRecognizedByCreateDispatch()
    {
        PredefinedWriter.PredefinedRef ref = PredefinedWriter.parseRef("Catalog.Products.Predefined.Blue"); //$NON-NLS-1$
        assertNotNull("a 4-part predefined-item FQN must be recognized", ref); //$NON-NLS-1$
        assertEquals("Catalog", ref.ownerType); //$NON-NLS-1$
        assertEquals("Products", ref.ownerName); //$NON-NLS-1$
        assertEquals("Blue", ref.itemName); //$NON-NLS-1$
        // A normal mdclass member FQN (Attribute at the same position) must NOT be misread as a
        // predefined item - otherwise the ordinary member-create branch would be unreachable.
        assertNull("a normal member FQN is not a predefined item", //$NON-NLS-1$
            PredefinedWriter.parseRef("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsPredefinedItems()
    {
        String desc = new CreateMetadataTool().getDescription();
        assertTrue("description should mention predefined items", new CreateMetadataTool().getGuide().contains("Predefined")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A ChartOfAccounts predefined item is recognized structurally and now admitted by the owner-type
     * gate (issue #296 phase 3 added ChartOfAccounts predefined-item authoring), so the gate returns
     * {@code null} in lockstep with the create / modify / get_metadata_details / delete callers.
     */
    @Test
    public void testChartOfAccountsPredefinedItemIsSupported()
    {
        PredefinedWriter.PredefinedRef ref =
            PredefinedWriter.parseRef("ChartOfAccounts.Main.Predefined.Cash"); //$NON-NLS-1$
        assertNotNull(ref);
        assertNull("ChartOfAccounts predefined items are now supported (gate must return null)", //$NON-NLS-1$
            PredefinedWriter.unsupportedOwnerTypeError(ref.ownerType));
    }

    // ==================== typed members (issue #700) ====================
    //
    // A member that carries a value type is created through the owner-aware model-object factory, so
    // the owner's type initializer gives it the designer defaults, type included. Where the factory
    // declines (null) or leaves the type empty, the per-kind default the initializers use is set:
    // String(10), Number(10,0) for an accumulation / accounting / calculation register resource,
    // Boolean for a chart of accounts' accounting flags. Driven through the seam the write
    // transaction calls (createMemberChild), on bare MdClassFactory owners and a mocked factory and
    // type provider - the same headless split MetadataTypeBuilderTest uses.

    /** The member name every typed-member test creates. */
    private static final String MEMBER = "Member"; //$NON-NLS-1$

    /** A platform type provider that knows String, Number and Boolean, each as its own type item. */
    private static final class Platform
    {
        final IEObjectProvider provider = mock(IEObjectProvider.class);
        final TypeItem stringType = McoreFactory.eINSTANCE.createType();
        final TypeItem numberType = McoreFactory.eINSTANCE.createType();
        final TypeItem booleanType = McoreFactory.eINSTANCE.createType();

        Platform()
        {
            doReturn(stringType).when(provider).createProxy("String"); //$NON-NLS-1$
            doReturn(numberType).when(provider).createProxy("Number"); //$NON-NLS-1$
            doReturn(booleanType).when(provider).createProxy("Boolean"); //$NON-NLS-1$
        }
    }

    /** Creates {@link #MEMBER} in {@code owner}'s {@code featureName} collection, as the write transaction does. */
    private static MdObject createMember(MdObject owner, String featureName, IModelObjectFactory factory,
        IEObjectProvider provider, CreateMetadataTool.Props props, String synonymLanguage)
    {
        // Exactly the target MetadataNodeResolver.resolveForCreate builds: the owner's containment
        // feature and its declared element type.
        EReference feature = (EReference)owner.eClass().getEStructuralFeature(featureName);
        assertNotNull(owner.eClass().getName() + " must have a '" + featureName + "' collection", feature); //$NON-NLS-1$ //$NON-NLS-2$
        CreateMetadataTool.MemberChildSpec spec = new CreateMetadataTool.MemberChildSpec(factory,
            feature.getEReferenceType(), owner, Version.LATEST, MEMBER, props, synonymLanguage, feature,
            provider);
        return CreateMetadataTool.createMemberChild(spec);
    }

    private static MdObject createMember(MdObject owner, String featureName, IModelObjectFactory factory,
        IEObjectProvider provider)
    {
        return createMember(owner, featureName, factory, provider, new CreateMetadataTool.Props(), null);
    }

    /** The member's {@code type} feature value, read reflectively: SequenceDimension is no BasicFeature. */
    private static TypeDescription typeOf(MdObject member)
    {
        return (TypeDescription)member.eGet(member.eClass().getEStructuralFeature("type")); //$NON-NLS-1$
    }

    private static void assertString10(Platform platform, MdObject member)
    {
        TypeDescription type = typeOf(member);
        assertNotNull(member.eClass().getName() + " must not be created without a type", type); //$NON-NLS-1$
        assertEquals("exactly one type item, String", //$NON-NLS-1$
            Collections.singletonList(platform.stringType), type.getTypes());
        assertNotNull("String(10) carries string qualifiers", type.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getStringQualifiers().getLength());
        assertFalse("the designer default is a variable-length string", type.getStringQualifiers().isFixed()); //$NON-NLS-1$
        assertNull(type.getNumberQualifiers());
    }

    private static void assertNumber10(Platform platform, MdObject member)
    {
        TypeDescription type = typeOf(member);
        assertNotNull(member.eClass().getName() + " must not be created without a type", type); //$NON-NLS-1$
        assertEquals("exactly one type item, Number", //$NON-NLS-1$
            Collections.singletonList(platform.numberType), type.getTypes());
        assertNotNull("Number(10,0) carries number qualifiers", type.getNumberQualifiers()); //$NON-NLS-1$
        assertEquals(10, type.getNumberQualifiers().getPrecision());
        assertEquals(0, type.getNumberQualifiers().getScale());
        assertFalse("the designer default allows negatives", type.getNumberQualifiers().isNonNegative()); //$NON-NLS-1$
        assertNull("a resource must never get a string's qualifiers", type.getStringQualifiers()); //$NON-NLS-1$
        verify(platform.provider, never()).createProxy("String"); //$NON-NLS-1$
    }

    private static void assertBoolean(Platform platform, MdObject member)
    {
        TypeDescription type = typeOf(member);
        assertNotNull(member.eClass().getName() + " must not be created without a type", type); //$NON-NLS-1$
        assertEquals("exactly one type item, Boolean", //$NON-NLS-1$
            Collections.singletonList(platform.booleanType), type.getTypes());
        assertNull("a flag has no string qualifiers", type.getStringQualifiers()); //$NON-NLS-1$
        assertNull("a flag has no number qualifiers", type.getNumberQualifiers()); //$NON-NLS-1$
        verify(platform.provider, never()).createProxy("String"); //$NON-NLS-1$
    }

    @Test
    public void testATypedMemberIsCreatedByTheFactoryAndKeepsTheTypeItsInitializerSet()
    {
        Platform platform = new Platform();
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject register = MdClassFactory.eINSTANCE.createInformationRegister();
        com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension byFactory =
            MdClassFactory.eINSTANCE.createInformationRegisterDimension();
        TypeDescription initializerType = McoreFactory.eINSTANCE.createTypeDescription();
        byFactory.setType(initializerType);
        byFactory.setMainFilter(true);
        doReturn(byFactory).when(factory).create(MdClassPackage.Literals.INFORMATION_REGISTER_DIMENSION,
            register, Version.LATEST);

        MdObject created = createMember(register, "dimensions", factory, platform.provider); //$NON-NLS-1$

        assertSame("the member must be the one the owner's initializer built", byFactory, created); //$NON-NLS-1$
        assertSame("the initializer's type must be kept, not replaced by a default", //$NON-NLS-1$
            initializerType, typeOf(created));
        assertTrue("the initializer's other designer defaults survive", byFactory.isMainFilter()); //$NON-NLS-1$
        assertEquals(MEMBER, created.getName());
        assertNotNull(created.getUuid());
        assertTrue("the member must be attached to its owner", //$NON-NLS-1$
            ((com._1c.g5.v8.dt.metadata.mdclass.InformationRegister)register).getDimensions().contains(created));
        verify(factory).create(MdClassPackage.Literals.INFORMATION_REGISTER_DIMENSION, register, Version.LATEST);
        verify(platform.provider, never()).createProxy(Mockito.anyString());
    }

    @Test
    public void testADimensionTheFactoryDeclinedGetsTheString10Default()
    {
        Platform platform = new Platform();
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject register = MdClassFactory.eINSTANCE.createInformationRegister();

        MdObject created = createMember(register, "dimensions", factory, platform.provider); //$NON-NLS-1$

        assertEquals(MdClassPackage.Literals.INFORMATION_REGISTER_DIMENSION, created.eClass());
        assertString10(platform, created);
        assertNotNull("the bare fallback still gets its UUID", created.getUuid()); //$NON-NLS-1$
        assertTrue(((com._1c.g5.v8.dt.metadata.mdclass.InformationRegister)register).getDimensions()
            .contains(created));
        verify(factory).create(MdClassPackage.Literals.INFORMATION_REGISTER_DIMENSION, register, Version.LATEST);
    }

    @Test
    public void testAnUntypedMemberTheFactoryReturnedGetsTheString10Default()
    {
        // An owner with no type initializer gets the factory's bare fallback object: non-null, but
        // still without a type. A null check on the factory's RESULT alone would ship it untyped.
        Platform platform = new Platform();
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject register = MdClassFactory.eINSTANCE.createInformationRegister();
        MdObject bare = MdClassFactory.eINSTANCE.createInformationRegisterDimension();
        doReturn(bare).when(factory).create(MdClassPackage.Literals.INFORMATION_REGISTER_DIMENSION,
            register, Version.LATEST);

        MdObject created = createMember(register, "dimensions", factory, platform.provider); //$NON-NLS-1$

        assertSame(bare, created);
        assertString10(platform, created);
    }

    @Test
    public void testAnInformationRegisterResourceDefaultsToString10()
    {
        // The one register resource whose designer default is a String, not a Number.
        Platform platform = new Platform();
        MdObject register = MdClassFactory.eINSTANCE.createInformationRegister();

        MdObject created = createMember(register, "resources", mock(IModelObjectFactory.class), //$NON-NLS-1$
            platform.provider);

        assertEquals(MdClassPackage.Literals.INFORMATION_REGISTER_RESOURCE, created.eClass());
        assertString10(platform, created);
    }

    @Test
    public void testAnAccumulationRegisterResourceDefaultsToNumber10And0()
    {
        Platform platform = new Platform();
        MdObject register = MdClassFactory.eINSTANCE.createAccumulationRegister();

        MdObject created = createMember(register, "resources", mock(IModelObjectFactory.class), //$NON-NLS-1$
            platform.provider);

        assertEquals(MdClassPackage.Literals.ACCUMULATION_REGISTER_RESOURCE, created.eClass());
        assertNumber10(platform, created);
    }

    @Test
    public void testAccountingAndCalculationRegisterResourcesDefaultToNumber10And0()
    {
        Platform accounting = new Platform();
        MdObject created = createMember(MdClassFactory.eINSTANCE.createAccountingRegister(), "resources", //$NON-NLS-1$
            mock(IModelObjectFactory.class), accounting.provider);
        assertEquals(MdClassPackage.Literals.ACCOUNTING_REGISTER_RESOURCE, created.eClass());
        assertNumber10(accounting, created);

        Platform calculation = new Platform();
        created = createMember(MdClassFactory.eINSTANCE.createCalculationRegister(), "resources", //$NON-NLS-1$
            mock(IModelObjectFactory.class), calculation.provider);
        assertEquals(MdClassPackage.Literals.CALCULATION_REGISTER_RESOURCE, created.eClass());
        assertNumber10(calculation, created);
    }

    @Test
    public void testAccountingFlagsDefaultToBoolean()
    {
        Platform flag = new Platform();
        MdObject created = createMember(MdClassFactory.eINSTANCE.createChartOfAccounts(), "accountingFlags", //$NON-NLS-1$
            mock(IModelObjectFactory.class), flag.provider);
        assertEquals(MdClassPackage.Literals.ACCOUNTING_FLAG, created.eClass());
        assertBoolean(flag, created);

        Platform extFlag = new Platform();
        created = createMember(MdClassFactory.eINSTANCE.createChartOfAccounts(), "extDimensionAccountingFlags", //$NON-NLS-1$
            mock(IModelObjectFactory.class), extFlag.provider);
        assertEquals(MdClassPackage.Literals.EXT_DIMENSION_ACCOUNTING_FLAG, created.eClass());
        assertBoolean(extFlag, created);
    }

    @Test
    public void testASequenceDimensionIsATypedMemberThoughItIsNoBasicFeature()
    {
        // SequenceDimension carries its own 'contains TypeDescription[1] type' outside BasicFeature, so
        // a selector keyed on BasicFeature would leave it untyped.
        assertFalse(MdClassPackage.Literals.BASIC_FEATURE.isSuperTypeOf(MdClassPackage.Literals.SEQUENCE_DIMENSION));
        assertTrue(CreateMetadataTool.carriesMandatoryType(MdClassPackage.Literals.SEQUENCE_DIMENSION));

        Platform platform = new Platform();
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject sequence = MdClassFactory.eINSTANCE.createSequence();

        MdObject created = createMember(sequence, "dimensions", factory, platform.provider); //$NON-NLS-1$

        assertEquals(MdClassPackage.Literals.SEQUENCE_DIMENSION, created.eClass());
        assertString10(platform, created);
        verify(factory).create(MdClassPackage.Literals.SEQUENCE_DIMENSION, sequence, Version.LATEST);
    }

    @Test
    public void testExplicitSynonymAndCommentStillWinOnAFactoryBuiltMember()
    {
        Platform platform = new Platform();
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject catalog = MdClassFactory.eINSTANCE.createCatalog();
        MdObject byFactory = MdClassFactory.eINSTANCE.createCatalogAttribute();
        byFactory.setComment("set by the initializer"); //$NON-NLS-1$
        byFactory.getSynonym().put("en", "Initializer"); //$NON-NLS-1$ //$NON-NLS-2$
        doReturn(byFactory).when(factory).create(MdClassPackage.Literals.CATALOG_ATTRIBUTE, catalog,
            Version.LATEST);
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        props.synonym = "Weight in kg"; //$NON-NLS-1$
        props.comment = "Explicit comment"; //$NON-NLS-1$

        MdObject created = createMember(catalog, "attributes", factory, platform.provider, props, "en"); //$NON-NLS-1$ //$NON-NLS-2$

        assertSame("the attribute must come from the factory", byFactory, created); //$NON-NLS-1$
        assertEquals("Weight in kg", created.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Explicit comment", created.getComment()); //$NON-NLS-1$
        assertString10(platform, created);
    }

    @Test
    public void testMembersWithoutAMandatoryTypeKeepThePlainPath()
    {
        // A command's parameter type is OPTIONAL (and its own 'Type'-coded feature), an enum value has
        // no type at all: neither is routed through the factory nor given a default type.
        assertFalse(CreateMetadataTool.carriesMandatoryType(MdClassPackage.Literals.CATALOG_COMMAND));
        assertFalse(CreateMetadataTool.carriesMandatoryType(MdClassPackage.Literals.ENUM_VALUE));
        assertFalse(CreateMetadataTool.carriesMandatoryType(MdClassPackage.Literals.CATALOG_TABULAR_SECTION));
        for (EClass typed : new EClass[] {MdClassPackage.Literals.CATALOG_ATTRIBUTE,
            MdClassPackage.Literals.TABULAR_SECTION_ATTRIBUTE, MdClassPackage.Literals.ADDRESSING_ATTRIBUTE,
            MdClassPackage.Literals.INFORMATION_REGISTER_DIMENSION, MdClassPackage.Literals.ACCOUNTING_FLAG,
            MdClassPackage.Literals.DATA_PROCESSOR_TABULAR_SECTION_ATTRIBUTE})
        {
            assertTrue(typed.getName() + " carries a mandatory type", //$NON-NLS-1$
                CreateMetadataTool.carriesMandatoryType(typed));
        }

        Platform platform = new Platform();
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject enumValue = createMember(MdClassFactory.eINSTANCE.createEnum(), "enumValues", factory, //$NON-NLS-1$
            platform.provider);
        MdObject command = createMember(MdClassFactory.eINSTANCE.createCatalog(), "commands", factory, //$NON-NLS-1$
            platform.provider);

        assertEquals(MEMBER, enumValue.getName());
        assertEquals(MEMBER, command.getName());
        assertNull("a command's optional parameter type is not defaulted", //$NON-NLS-1$
            ((com._1c.g5.v8.dt.metadata.mdclass.BasicCommand)command).getCommandParameterType());
        verify(factory, never()).create(any(EClass.class), any(org.eclipse.emf.ecore.EObject.class),
            any(Version.class));
        verify(platform.provider, never()).createProxy(Mockito.anyString());
    }

    @Test
    public void testTheGuideStatesTheDefaultTypeOfEachMemberKind()
    {
        String guide = new CreateMetadataTool().getGuide();
        assertTrue(guide.contains("is created with the designer's defaults, type included: `String(10)` " //$NON-NLS-1$
            + "(`{types:[{kind:'String', length:10}]}`), except an accumulation / accounting / calculation " //$NON-NLS-1$
            + "register resource - `Number(10,0)` - and a ChartOfAccounts accounting flag or ext-dimension " //$NON-NLS-1$
            + "accounting flag - `Boolean`.")); //$NON-NLS-1$
        assertFalse("the old vague promise must be gone", guide.contains("(e.g. a default type)")); //$NON-NLS-1$ //$NON-NLS-2$
        // A characteristic created without a valueType is no longer left untyped; a folder still is,
        // as EDT's folder wizard leaves it.
        assertTrue(guide.contains("Omitted -> a copy of the chart's own value type, as EDT's \"New predefined " //$NON-NLS-1$
            + "characteristic\" wizard gives it; a folder (`isFolder: true`) stays untyped, as EDT's folder " //$NON-NLS-1$
            + "wizard leaves it.")); //$NON-NLS-1$
        assertFalse(guide.contains("Omitted -> left UNSET. A JSON `null` at CREATE")); //$NON-NLS-1$
    }

    @Test
    public void testTheTransactionsSpecResolvesTheTypeProviderForATypedMemberOnly()
    {
        // The spec createMember builds inside the write transaction looks the platform type provider up
        // itself (typeProviderFor) - the provider ensureMandatoryType defaults a member's type with. The
        // live factory types every member it builds, so no e2e reaches that fallback: this is its pin.
        // The registry never answers null (it falls back to an empty provider for the class), so the
        // provider is identified by the class it serves, read from the registry itself.
        IEObjectProvider registered = IEObjectProvider.Registry.INSTANCE.get(
            McorePackage.Literals.TYPE_ITEM, Version.LATEST);
        assertNotNull(registered);
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        MdObject register = MdClassFactory.eINSTANCE.createInformationRegister();
        EReference dimensions = (EReference)register.eClass().getEStructuralFeature("dimensions"); //$NON-NLS-1$
        MdObject catalog = MdClassFactory.eINSTANCE.createCatalog();
        EReference commands = (EReference)catalog.eClass().getEStructuralFeature("commands"); //$NON-NLS-1$

        CreateMetadataTool.MemberChildSpec dimension = new CreateMetadataTool.MemberChildSpec(factory,
            dimensions.getEReferenceType(), register, Version.LATEST, MEMBER, props, null, dimensions);
        CreateMetadataTool.MemberChildSpec command = new CreateMetadataTool.MemberChildSpec(factory,
            commands.getEReferenceType(), catalog, Version.LATEST, MEMBER, props, null, commands);
        CreateMetadataTool.MemberChildSpec unknownVersion = new CreateMetadataTool.MemberChildSpec(factory,
            dimensions.getEReferenceType(), register, null, MEMBER, props, null, dimensions);

        assertNotNull("a member with a value type needs the platform type provider", dimension.typeProvider); //$NON-NLS-1$
        assertSame("the registry's provider for the project's version", //$NON-NLS-1$
            registered.getClass(), dimension.typeProvider.getClass());
        assertEquals("the provider of TypeItems, the key the owner initializers use", //$NON-NLS-1$
            registered.getEClass(), dimension.typeProvider.getEClass());
        assertTrue(McorePackage.Literals.TYPE_ITEM.isSuperTypeOf(
            dimension.typeProvider.getEClass()));
        assertNull("a command carries no mandatory type and needs no provider", command.typeProvider); //$NON-NLS-1$
        assertNull("no platform version, no provider", unknownVersion.typeProvider); //$NON-NLS-1$
    }

    @Test
    public void testTheDefaultTypeFailsLoudlyRatherThanWritingAnUntypedMember()
    {
        // No platform type provider: the create must fail inside the transaction (which rolls it
        // back), never attach a member without its mandatory type.
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        com._1c.g5.v8.dt.metadata.mdclass.InformationRegister register =
            MdClassFactory.eINSTANCE.createInformationRegister();
        try
        {
            createMember(register, "dimensions", factory, null); //$NON-NLS-1$
            fail("a typed member without a buildable default type must not be created"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("type provider")); //$NON-NLS-1$
        }
        assertTrue("nothing may be attached", register.getDimensions().isEmpty()); //$NON-NLS-1$
    }

    // ===== a created form command's unbound action (issue #628) ====================================
    //
    // Creating a form COMMAND never binds its action - only '...Command.C.Handler.Action' does - so a
    // created command is a button that does nothing until that second call. The success message says
    // so and names the address that binds it, after a look into the form module for a method of the
    // command's name: a procedure there is named the way the module writes it, and a function there
    // is never answered with "add that procedure", which would give the module two methods of one name.
    //
    // Every route binds BEFORE a procedure is written: a bind to a procedure that already exists
    // changes only the form, EDT does not re-check the module then, and the procedure keeps its
    // module-unused-method and module-structure-form-event-regions warnings until the form's owner is
    // revalidated. The one route that has to bind an existing procedure names that owner.

    /** A form command's address, as a caller passes it. */
    private static final String COMMAND_FQN = "Catalog.Products.Form.ItemForm.Command.Go"; //$NON-NLS-1$

    /** The address that binds that command's action, quoted the way the message quotes it. */
    private static final String QUOTED_ACTION = "'" + COMMAND_FQN + ".Handler.Action'"; //$NON-NLS-1$ //$NON-NLS-2$

    /** The object that owns that command's form, the way revalidate_objects takes it. */
    private static final String OWNER = "Catalog.Products"; //$NON-NLS-1$

    /** The text every COMMAND variant opens with: the old message, then what it did not say. */
    private static final String UNBOUND = "Created " + COMMAND_FQN //$NON-NLS-1$
        + ". The command has no action yet, so a button bound to it does nothing"; //$NON-NLS-1$

    /** Text A up to its closing period: bind the action first, then write the procedure it names. */
    private static final String ROUTE = UNBOUND + ": bind the action with create_metadata on " //$NON-NLS-1$
        + QUOTED_ACTION + " (its 'procedure' defaults to the command name 'Go'), then add that procedure " //$NON-NLS-1$
        + "with write_module_source"; //$NON-NLS-1$

    /** Text B for the procedure a module spells 'go': bind it, then revalidate the form's owner. */
    private static final String BIND_GO = UNBOUND + ". The form module already has procedure 'go': " //$NON-NLS-1$
        + "create_metadata on " + QUOTED_ACTION + " with procedure 'go' binds it in one call. Then run " //$NON-NLS-1$ //$NON-NLS-2$
        + "revalidate_objects on 'Catalog.Products': EDT does not re-check the form module on that bind, " //$NON-NLS-1$
        + "so until then its module-unused-method and module-structure-form-event-regions warnings on " //$NON-NLS-1$
        + "'go' still treat it as unbound."; //$NON-NLS-1$

    /** Where the module of {@code Catalog.Products.Form.ItemForm} lives, relative to the project. */
    private static final String ITEM_FORM_MODULE = "src/Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    /** The source read to pin the one call site no headless test can drive. */
    private static final String TOOL_SOURCE = "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/" //$NON-NLS-1$
        + "server/tools/impl/CreateMetadataTool.java"; //$NON-NLS-1$

    /** The generated page that mirrors the guide. */
    private static final String GENERATED_PAGE = "docs/tools/create_metadata.md"; //$NON-NLS-1$

    /** The guide sentence that says creating a command binds nothing. */
    private static final String GUIDE_SENTENCE = "Creating the Command never binds an action, even when the " //$NON-NLS-1$
        + "form module already has a procedure of that name: the create response says so, gives the " //$NON-NLS-1$
        + "`Handler.Action` address and names that procedure when it finds one."; //$NON-NLS-1$

    /** What the look into a module made of {@code lines} finds for {@code name}. */
    private static CreateMetadataTool.FormModuleLook look(String name, String... lines)
    {
        return CreateMetadataTool.lookInto(Arrays.asList(lines), name);
    }

    /** The message for the COMMAND {@link #COMMAND_FQN} after {@code look}, its form owned by {@link #OWNER}. */
    private static String commandMessage(CreateMetadataTool.FormModuleLook look)
    {
        return CreateMetadataTool.formMemberCreatedMessage(FormElementWriter.Kind.COMMAND, COMMAND_FQN, "Go", //$NON-NLS-1$
            look.ownedBy(OWNER));
    }

    @Test
    public void testACreatedCommandWithNoSameNamedMethodNamesTheAddressThatBindsItsAction()
    {
        // No module (or a lookup that failed) and a module whose methods are all named otherwise read
        // alike: nothing is there to bind, so both the action and its procedure are still owed - the
        // action first, so the procedure is written for a form that already names it.
        assertEquals(ROUTE + ".", commandMessage(CreateMetadataTool.FormModuleLook.NOTHING)); //$NON-NLS-1$
        assertEquals(ROUTE + ".", commandMessage(look("Go", "Procedure Other()", "EndProcedure"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testACreatedCommandNamesTheSameNamedProcedureAsTheModuleWritesIt()
    {
        // 1C compares identifiers ignoring case, so 'go' IS the command's procedure - and the message
        // names it the way the module spells it: what exists, what to pass, and what to revalidate
        // after the bind, since EDT does not re-check the module for a procedure that came first.
        String message = commandMessage(look("Go", "&AtClient", "Procedure go(Command)", "EndProcedure")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(BIND_GO, message);
        assertFalse("the procedure exists, so writing it again must not be advised: " + message, //$NON-NLS-1$
            message.contains("write_module_source")); //$NON-NLS-1$
    }

    @Test
    public void testWithoutTheOwnersAddressTheRevalidationIsStillNamed()
    {
        // The model gives the owner whenever it gives the module, so this is the defensive branch: the
        // step is still there, on the object that owns the form.
        String message = CreateMetadataTool.formMemberCreatedMessage(FormElementWriter.Kind.COMMAND,
            COMMAND_FQN, "Go", look("Go", "Procedure Go(Command)", "EndProcedure")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(message, message.endsWith(" binds it in one call. Then run revalidate_objects on the " //$NON-NLS-1$
            + "object that owns the form: EDT does not re-check the form module on that bind, so until " //$NON-NLS-1$
            + "then its module-unused-method and module-structure-form-event-regions warnings on 'Go' " //$NON-NLS-1$
            + "still treat it as unbound.")); //$NON-NLS-1$
    }

    @Test
    public void testACreatedCommandWhoseNameAFunctionHoldsAsksForAProcedureUnderAnotherName()
    {
        String message = commandMessage(look("Go", "Function Go()", "Return 1;", "EndFunction")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(UNBOUND + ". The form module's 'Go' is a function, and a command's action is a " //$NON-NLS-1$
            + "procedure, which cannot share that name: bind the action with create_metadata on " //$NON-NLS-1$
            + QUOTED_ACTION + " with a new name as 'procedure', then add a procedure of that new name " //$NON-NLS-1$
            + "with write_module_source.", message); //$NON-NLS-1$
        // A module cannot hold two methods of one name, so the generic advice would break it.
        assertFalse(message, message.contains("add that procedure")); //$NON-NLS-1$
        assertFalse(message, message.contains("procedure 'Go'")); //$NON-NLS-1$
        assertFalse(message, message.contains("defaults to the command name")); //$NON-NLS-1$
        // And the bind comes first, as on the generic route: written first, the new procedure would
        // keep the module warnings of an unbound one after the bind.
        assertTrue(message, message.indexOf("bind the action") < message.indexOf("write_module_source")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEveryCommandVariantEndsAsAClosedSentence()
    {
        // The marker report is appended to the message as a sentence of its own.
        List<String> messages = Arrays.asList(
            commandMessage(CreateMetadataTool.FormModuleLook.NOTHING),
            commandMessage(look("Go", "&AtClient Procedure Go(Command)", "EndProcedure")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            commandMessage(look("Go", "Procedure Go()", "EndProcedure")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            CreateMetadataTool.formMemberCreatedMessage(FormElementWriter.Kind.COMMAND, COMMAND_FQN, "Go", //$NON-NLS-1$
                look("Go", "Procedure Go()", "EndProcedure")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            commandMessage(look("Go", "Function Go()", "EndFunction"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String message : messages)
        {
            assertTrue(message, message.startsWith(UNBOUND));
            assertTrue(message, message.endsWith(".") && !message.endsWith("..")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testEveryOtherMemberKindKeepsThePlainCreatedMessage()
    {
        String fqn = "Catalog.Products.Form.ItemForm.Attribute.Go"; //$NON-NLS-1$
        CreateMetadataTool.FormModuleLook go = look("Go", "Procedure Go()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (FormElementWriter.Kind kind : FormElementWriter.Kind.values())
        {
            if (kind != FormElementWriter.Kind.COMMAND)
            {
                assertEquals(kind + " has no action to report on", "Created " + fqn, //$NON-NLS-1$ //$NON-NLS-2$
                    CreateMetadataTool.formMemberCreatedMessage(kind, fqn, "Go", go.ownedBy(OWNER))); //$NON-NLS-1$
            }
        }
        // The control: the same module, on a COMMAND, is answered with the action it lacks.
        assertTrue(commandMessage(go).startsWith(UNBOUND));
    }

    @Test
    public void testTheLookupMatchesTheCommandNameIgnoringCaseInBothLanguages()
    {
        // &НаКлиенте / Процедура go(Команда) / КонецПроцедуры
        BslModuleUtils.TextMethod russian = CreateMetadataTool.sameNamedMethod(Arrays.asList(
            "&\u041D\u0430\u041A\u043B\u0438\u0435\u043D\u0442\u0435", "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 go(\u041A\u043E\u043C\u0430\u043D\u0434\u0430)", "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B"), "Go"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull("a Russian declaration of the command's name, in another case", russian); //$NON-NLS-1$
        assertEquals("go", russian.matchedName); //$NON-NLS-1$
        assertFalse(russian.isFunction);

        BslModuleUtils.TextMethod english = CreateMetadataTool.sameNamedMethod(Arrays.asList(
            "Procedure GO()", "EndProcedure"), "Go"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("an English declaration of the command's name, in another case", english); //$NON-NLS-1$
        assertEquals("GO", english.matchedName); //$NON-NLS-1$
        assertFalse(english.isFunction);
    }

    @Test
    public void testTheLookupTellsAFunctionFromAProcedure()
    {
        // Функция Go() / Возврат 1; / КонецФункции
        BslModuleUtils.TextMethod function = CreateMetadataTool.sameNamedMethod(Arrays.asList(
            "\u0424\u0443\u043D\u043A\u0446\u0438\u044F Go()", "    \u0412\u043E\u0437\u0432\u0440\u0430\u0442 1;", "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438"), "Go"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull(function);
        assertTrue("a function must be reported as one", function.isFunction); //$NON-NLS-1$

        BslModuleUtils.TextMethod procedure = CreateMetadataTool.sameNamedMethod(Arrays.asList(
            "Procedure Go()", "EndProcedure"), "Go"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(procedure);
        assertFalse("a procedure must not be reported as a function", procedure.isFunction); //$NON-NLS-1$
    }

    @Test
    public void testTheLookupDoesNotTakeACommentOrAStringForADeclaration()
    {
        // A commented-out "Процедура Go()", the same text inside a multi-line string literal and inside
        // a one-line literal - all within a real "Процедура Other()". None of them starts its line the
        // way a declaration does (a string's continuation line starts with '|'), and the scan takes a
        // declaration only where one starts its line: that anchor rejects them here, the whole-name
        // compare rejects a Go2.
        List<String> module = Arrays.asList(
            "// \u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 Go()", //$NON-NLS-1$
            "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 Other()", //$NON-NLS-1$
            "    Text = \"", //$NON-NLS-1$
            "    |\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 Go()", //$NON-NLS-1$
            "    |\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B\";", //$NON-NLS-1$
            "    Message(\"Procedure Go()\");", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B"); //$NON-NLS-1$
        assertNull("a commented-out or quoted declaration is not a method of the module", //$NON-NLS-1$
            CreateMetadataTool.sameNamedMethod(module, "Go")); //$NON-NLS-1$
        assertNotNull("the control: the module's real declaration is found", //$NON-NLS-1$
            CreateMetadataTool.sameNamedMethod(module, "Other")); //$NON-NLS-1$
    }

    @Test
    public void testTheLookupMatchesTheWholeNameOnly()
    {
        // Процедура Go2() / КонецПроцедуры, then an English GoBack
        List<String> module = Arrays.asList("\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 Go2()", "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B", //$NON-NLS-1$ //$NON-NLS-2$
            "Procedure GoBack()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a longer name that starts with the command's is another method", //$NON-NLS-1$
            CreateMetadataTool.sameNamedMethod(module, "Go")); //$NON-NLS-1$
        assertNotNull("the control: that longer name is found by its own name", //$NON-NLS-1$
            CreateMetadataTool.sameNamedMethod(module, "Go2")); //$NON-NLS-1$
    }

    @Test
    public void testTheLookupToleratesAMissingOrEmptyModule()
    {
        List<String> module = Arrays.asList("Procedure Go()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(CreateMetadataTool.sameNamedMethod(null, "Go")); //$NON-NLS-1$
        assertNull(CreateMetadataTool.sameNamedMethod(Collections.<String> emptyList(), "Go")); //$NON-NLS-1$
        assertNull(CreateMetadataTool.sameNamedMethod(Arrays.asList("", "   "), "Go")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(CreateMetadataTool.sameNamedMethod(module, null));
        assertNull(CreateMetadataTool.sameNamedMethod(module, "")); //$NON-NLS-1$
        assertNotNull("the control: the same module answers for the name it declares", //$NON-NLS-1$
            CreateMetadataTool.sameNamedMethod(module, "Go")); //$NON-NLS-1$
    }

    @Test
    public void testAModuleLineTheScanCannotReadIsNoProofTheProcedureIsMissing()
    {
        // "&AtClient Procedure Go(Command)" is legal BSL - a pragma may share the declaration's line -
        // but the text scan only sees a declaration that starts its line. It finds no 'Go' in a module
        // that has one, and "add that procedure" would then append a second Go: the route keeps its
        // advice, with the caveat and the line that earns it.
        List<String> module = Arrays.asList("&AtClient Procedure Go(Command)", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the scan cannot see a declaration behind its pragma", //$NON-NLS-1$
            CreateMetadataTool.sameNamedMethod(module, "Go")); //$NON-NLS-1$

        CreateMetadataTool.FormModuleLook look = CreateMetadataTool.lookInto(module, "Go"); //$NON-NLS-1$
        assertNull(look.sameNamed);
        assertEquals("a pragma sharing its line with the declaration (line 1)", look.unscanned); //$NON-NLS-1$
        assertEquals(ROUTE + " unless the form module already declares it: the module has a pragma " //$NON-NLS-1$
            + "sharing its line with the declaration (line 1), which this look-up cannot read.", //$NON-NLS-1$
            commandMessage(look));
    }

    @Test
    public void testAModuleTheScanReadsWholeCarriesNoCaveat()
    {
        // The usual shape - the pragma on a line of its own - is read whole: nothing to qualify.
        assertSame(CreateMetadataTool.FormModuleLook.NOTHING,
            look("Go", "&AtClient", "Procedure Other(Command)", "EndProcedure")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(CreateMetadataTool.unscannedLine(null));
        assertNull(CreateMetadataTool.unscannedLine(Collections.<String> emptyList()));
        // The control: the same module, the pragma moved onto the declaration's line, is qualified.
        assertEquals("a pragma sharing its line with the declaration (line 2)", //$NON-NLS-1$
            CreateMetadataTool.unscannedLine(Arrays.asList("", "&AtClient Procedure Other(Command)", //$NON-NLS-1$ //$NON-NLS-2$
                "EndProcedure"))); //$NON-NLS-1$
    }

    /** {@code Catalog.Products} owning the form {@code ItemForm}, as the model resolves them. */
    private static CatalogForm resolvedItemForm()
    {
        Catalog products = MdClassFactory.eINSTANCE.createCatalog();
        products.setName("Products"); //$NON-NLS-1$
        CatalogForm itemForm = MdClassFactory.eINSTANCE.createCatalogForm();
        itemForm.setName("ItemForm"); //$NON-NLS-1$
        products.getForms().add(itemForm);
        return itemForm;
    }

    @Test
    public void testTheFormModuleIsAddressedByTheNamesTheModelResolved()
    {
        // The model resolves 'PRODUCTS' and 'itemform' ignoring case; the workspace path does not, so
        // the spelling the caller typed would miss the folder EDT wrote.
        FormElementWriter.FormMemberRef ref =
            FormElementWriter.parse("Catalog.PRODUCTS.Form.itemform.Command.Go"); //$NON-NLS-1$
        assertEquals(ITEM_FORM_MODULE, CreateMetadataTool.formModulePath(ref.formPath, resolvedItemForm()));
    }

    @Test
    public void testACommonFormAndAnExternalDataProcessorFormHaveTheirModulesAddressed()
    {
        CommonForm picker = MdClassFactory.eINSTANCE.createCommonForm();
        picker.setName("Picker"); //$NON-NLS-1$
        assertEquals("src/CommonForms/Picker/Module.bsl", CreateMetadataTool.formModulePath( //$NON-NLS-1$
            FormElementWriter.parse("CommonForm.picker.Command.Go").formPath, picker)); //$NON-NLS-1$
        // ОбщаяФорма.picker.Команда.Go - both tokens Russian
        assertEquals("src/CommonForms/Picker/Module.bsl", CreateMetadataTool.formModulePath( //$NON-NLS-1$
            FormElementWriter.parse("\u041E\u0431\u0449\u0430\u044F\u0424\u043E\u0440\u043C\u0430.picker.\u041A\u043E\u043C\u0430\u043D\u0434\u0430.Go").formPath, picker)); //$NON-NLS-1$

        ExternalDataProcessor proc = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        proc.setName("ExtProc"); //$NON-NLS-1$
        DataProcessorForm main = MdClassFactory.eINSTANCE.createDataProcessorForm();
        main.setName("MainForm"); //$NON-NLS-1$
        proc.getForms().add(main);
        assertEquals("src/ExternalDataProcessors/ExtProc/Forms/MainForm/Module.bsl", //$NON-NLS-1$
            CreateMetadataTool.formModulePath(
                FormElementWriter.parse("ExternalDataProcessor.extproc.Form.mainform.Command.Go").formPath, //$NON-NLS-1$
                main));
    }

    @Test
    public void testAFormWhoseNamesTheModelCannotGiveHasNoModuleOrOwnerAddress()
    {
        String formPath = FormElementWriter.parse(COMMAND_FQN).formPath;
        CatalogForm detached = MdClassFactory.eINSTANCE.createCatalogForm();
        detached.setName("ItemForm"); //$NON-NLS-1$
        assertNull("an owned form outside its owner names no folder", //$NON-NLS-1$
            CreateMetadataTool.formModulePath(formPath, detached));
        assertNull("no form, no folder", CreateMetadataTool.formModulePath(formPath, null)); //$NON-NLS-1$
        assertNull("no form path, no folder", CreateMetadataTool.formModulePath(null, resolvedItemForm())); //$NON-NLS-1$
        assertNull("an owned form outside its owner names no owner", //$NON-NLS-1$
            CreateMetadataTool.formOwnerAddress(formPath, detached));
        assertNull("no form, no owner", CreateMetadataTool.formOwnerAddress(formPath, null)); //$NON-NLS-1$
        assertNull("no form path, no owner", CreateMetadataTool.formOwnerAddress(null, resolvedItemForm())); //$NON-NLS-1$
        // The control: the same path, with the form inside its owner, is addressed both ways.
        assertEquals(ITEM_FORM_MODULE, CreateMetadataTool.formModulePath(formPath, resolvedItemForm()));
        assertEquals(OWNER, CreateMetadataTool.formOwnerAddress(formPath, resolvedItemForm()));
    }

    @Test
    public void testTheFormsOwnerIsItsObjectsOwnFqnWhateverTheCallerTyped()
    {
        // revalidate_objects takes the owner by its FQN. The model resolved 'PRODUCTS' and 'itemform'
        // ignoring case, so the owner is spelled the way the model holds it, under the English TYPE
        // token - in whichever language and case the caller typed the address.
        assertEquals(OWNER, CreateMetadataTool.formOwnerAddress(
            FormElementWriter.parse("Catalog.PRODUCTS.Form.itemform.Command.Go").formPath, resolvedItemForm())); //$NON-NLS-1$
        // Справочник.PRODUCTS.Форма.itemform.Команда.Go
        assertEquals(OWNER, CreateMetadataTool.formOwnerAddress(
            FormElementWriter.parse("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.PRODUCTS.\u0424\u043E\u0440\u043C\u0430.itemform.\u041A\u043E\u043C\u0430\u043D\u0434\u0430.Go").formPath, resolvedItemForm())); //$NON-NLS-1$

        // A common form is its own owner: ОбщаяФорма.picker.Команда.Go
        CommonForm picker = MdClassFactory.eINSTANCE.createCommonForm();
        picker.setName("Picker"); //$NON-NLS-1$
        assertEquals("CommonForm.Picker", CreateMetadataTool.formOwnerAddress( //$NON-NLS-1$
            FormElementWriter.parse("\u041E\u0431\u0449\u0430\u044F\u0424\u043E\u0440\u043C\u0430.picker.\u041A\u043E\u043C\u0430\u043D\u0434\u0430.Go").formPath, picker)); //$NON-NLS-1$

        ExternalDataProcessor proc = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        proc.setName("ExtProc"); //$NON-NLS-1$
        DataProcessorForm main = MdClassFactory.eINSTANCE.createDataProcessorForm();
        main.setName("MainForm"); //$NON-NLS-1$
        proc.getForms().add(main);
        assertEquals("ExternalDataProcessor.ExtProc", CreateMetadataTool.formOwnerAddress( //$NON-NLS-1$
            FormElementWriter.parse("ExternalDataProcessor.extproc.Form.mainform.Command.Go").formPath, main)); //$NON-NLS-1$
    }

    /**
     * A project whose only file is the module at {@code modulePath}, holding {@code text}; every other
     * file it is asked for does not exist.
     */
    private static IProject projectWithModule(String modulePath, String text) throws Exception
    {
        IProject project = mock(IProject.class);
        IFile missing = mock(IFile.class);
        doReturn(false).when(missing).exists();
        doReturn(missing).when(project).getFile(any(IPath.class));
        if (modulePath != null)
        {
            IFile module = mock(IFile.class);
            doReturn(true).when(module).exists();
            doAnswer(invocation -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
                .when(module).getContents();
            doReturn("UTF-8").when(module).getCharset(); //$NON-NLS-1$
            doReturn(module).when(project).getFile(new Path(modulePath));
        }
        return project;
    }

    @Test
    public void testACreatedCommandReadsTheModuleOfItsResolvedFormAndNamesTheProcedure() throws Exception
    {
        // The caller's spelling differs in case from the folder EDT wrote: the module is read from the
        // folder of the names the model resolved, its procedure is named as the module writes it, and
        // the owner to revalidate is the object's own FQN - while the address echoes what was typed.
        IProject project = projectWithModule(ITEM_FORM_MODULE,
            "&AtClient\nProcedure go(Command)\nEndProcedure\n"); //$NON-NLS-1$
        String fqn = "Catalog.PRODUCTS.Form.itemform.Command.Go"; //$NON-NLS-1$

        String message = CreateMetadataTool.formMemberSuccessMessage(project, FormElementWriter.Kind.COMMAND,
            fqn, FormElementWriter.parse(fqn), resolvedItemForm());

        assertEquals("Created " + fqn + ". The command has no action yet, so a button bound to it does " //$NON-NLS-1$ //$NON-NLS-2$
            + "nothing. The form module already has procedure 'go': create_metadata on '" + fqn //$NON-NLS-1$
            + ".Handler.Action' with procedure 'go' binds it in one call. Then run revalidate_objects on " //$NON-NLS-1$
            + "'Catalog.Products': EDT does not re-check the form module on that bind, so until then its " //$NON-NLS-1$
            + "module-unused-method and module-structure-form-event-regions warnings on 'go' still treat " //$NON-NLS-1$
            + "it as unbound.", message); //$NON-NLS-1$
    }

    @Test
    public void testACreatedCommandOnACommonFormFindsAFunctionOfItsName() throws Exception
    {
        CommonForm picker = MdClassFactory.eINSTANCE.createCommonForm();
        picker.setName("Picker"); //$NON-NLS-1$
        IProject project = projectWithModule("src/CommonForms/Picker/Module.bsl", //$NON-NLS-1$
            "Function Go()\n\tReturn 1;\nEndFunction\n"); //$NON-NLS-1$
        // Typed in another case than the model holds it: the folder is the resolved name's.
        String fqn = "CommonForm.picker.Command.Go"; //$NON-NLS-1$

        String message = CreateMetadataTool.formMemberSuccessMessage(project, FormElementWriter.Kind.COMMAND,
            fqn, FormElementWriter.parse(fqn), picker);

        assertTrue(message, message.contains("The form module's 'Go' is a function")); //$NON-NLS-1$
        assertFalse(message, message.contains("add that procedure")); //$NON-NLS-1$
    }

    @Test
    public void testACreatedCommandOnAFormWithoutAModuleGetsTheGenericRoute() throws Exception
    {
        IProject project = projectWithModule(null, null);

        String message = CreateMetadataTool.formMemberSuccessMessage(project, FormElementWriter.Kind.COMMAND,
            COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), resolvedItemForm());

        assertEquals(ROUTE + ".", message); //$NON-NLS-1$
    }

    @Test
    public void testAnUnreadableModuleFallsBackToTheGenericRouteAndStillAnswers() throws Exception
    {
        // The look into the module is advice, not part of the create: a module that cannot be read
        // must cost the hint its precision, never the create its answer.
        IProject project = mock(IProject.class);
        IFile module = mock(IFile.class);
        doReturn(true).when(module).exists();
        doThrow(new CoreException(new Status(IStatus.ERROR, "test", "the file is locked"))) //$NON-NLS-1$ //$NON-NLS-2$
            .when(module).getContents();
        doReturn(module).when(project).getFile(any(IPath.class));

        String message = CreateMetadataTool.formMemberSuccessMessage(project, FormElementWriter.Kind.COMMAND,
            COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), resolvedItemForm());

        assertTrue(message, message.startsWith(UNBOUND + ": bind the action with create_metadata on ")); //$NON-NLS-1$
        assertTrue(message, message.endsWith("then add that procedure with write_module_source.")); //$NON-NLS-1$
    }

    @Test
    public void testAModuleOnDiskThatTheWorkspaceTreeDoesNotShowYetIsReadAllTheSame() throws Exception
    {
        // Written outside EDT and not refreshed: the tree says there is no such file, the disk has it -
        // and it is the module EDT will load, so its procedure must not be advised a second time.
        File onDisk = File.createTempFile("Module", ".bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        onDisk.deleteOnExit();
        Files.write(onDisk.toPath(), "Procedure Go(Command)\nEndProcedure\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IProject project = projectWithModule(null, null);
        IFile module = mock(IFile.class);
        doReturn(false).when(module).exists();
        doReturn(new Path(onDisk.getAbsolutePath())).when(module).getLocation();
        doThrow(new CoreException(new Status(IStatus.ERROR, "test", "Resource does not exist"))) //$NON-NLS-1$ //$NON-NLS-2$
            .when(module).getContents();
        doReturn("UTF-8").when(module).getCharset(); //$NON-NLS-1$
        doReturn(module).when(project).getFile(new Path(ITEM_FORM_MODULE));

        String message = CreateMetadataTool.formMemberSuccessMessage(project, FormElementWriter.Kind.COMMAND,
            COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), resolvedItemForm());
        assertTrue(message, message.contains(". The form module already has procedure 'Go': ")); //$NON-NLS-1$

        // The control: gone from the disk as well, it is no module at all.
        assertTrue(onDisk.delete());
        assertEquals(ROUTE + ".", CreateMetadataTool.formMemberSuccessMessage(project, //$NON-NLS-1$
            FormElementWriter.Kind.COMMAND, COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), resolvedItemForm()));
    }

    @Test
    public void testACreatedCommandOnAModuleTheScanCannotReadKeepsTheRouteWithItsCaveat() throws Exception
    {
        IProject project = projectWithModule(ITEM_FORM_MODULE, "&AtClient Procedure Go(Command)\nEndProcedure\n"); //$NON-NLS-1$

        String message = CreateMetadataTool.formMemberSuccessMessage(project, FormElementWriter.Kind.COMMAND,
            COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), resolvedItemForm());

        assertEquals(ROUTE + " unless the form module already declares it: the module has a pragma sharing " //$NON-NLS-1$
            + "its line with the declaration (line 1), which this look-up cannot read.", message); //$NON-NLS-1$
    }

    @Test
    public void testAFormTheModelCannotReadAfterTheWriteCostsTheHintItsPrecisionNotTheCreateItsAnswer()
        throws Exception
    {
        // The message is built after the write, outside any transaction. Should reading the form the
        // model resolved fail there, the create has still happened: the look gives up - one warning -
        // and the command gets the generic route, the owner unnamed, instead of an exception that would
        // report a written member as a failure.
        CatalogForm unreadable = mock(CatalogForm.class);
        doReturn("ItemForm").when(unreadable).getName(); //$NON-NLS-1$
        doThrow(new IllegalStateException("read outside a transaction")).when(unreadable).eContainer(); //$NON-NLS-1$
        IProject project = projectWithModule(ITEM_FORM_MODULE, "Procedure go(Command)\nEndProcedure\n"); //$NON-NLS-1$

        assertEquals(ROUTE + ".", CreateMetadataTool.formMemberSuccessMessage(project, //$NON-NLS-1$
            FormElementWriter.Kind.COMMAND, COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), unreadable));
        String attribute = "Catalog.Products.Form.ItemForm.Attribute.Go"; //$NON-NLS-1$
        assertEquals("Created " + attribute, CreateMetadataTool.formMemberSuccessMessage(project, //$NON-NLS-1$
            FormElementWriter.Kind.ATTRIBUTE, attribute, FormElementWriter.parse(attribute), unreadable));
        // The control: the same module, its form readable, names the procedure and the owner.
        assertEquals(BIND_GO, CreateMetadataTool.formMemberSuccessMessage(project,
            FormElementWriter.Kind.COMMAND, COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), resolvedItemForm()));
    }

    @Test
    public void testOnlyACreatedCommandLooksIntoTheFormModule() throws Exception
    {
        IProject project = projectWithModule(ITEM_FORM_MODULE, "Procedure Go()\nEndProcedure\n"); //$NON-NLS-1$
        String attribute = "Catalog.Products.Form.ItemForm.Attribute.Go"; //$NON-NLS-1$

        assertEquals("Created " + attribute, CreateMetadataTool.formMemberSuccessMessage(project, //$NON-NLS-1$
            FormElementWriter.Kind.ATTRIBUTE, attribute, FormElementWriter.parse(attribute), resolvedItemForm()));
        verify(project, never()).getFile(any(IPath.class));

        // The control: a COMMAND of that name on the same form does read it.
        assertTrue(CreateMetadataTool.formMemberSuccessMessage(project, FormElementWriter.Kind.COMMAND,
            COMMAND_FQN, FormElementWriter.parse(COMMAND_FQN), resolvedItemForm())
            .contains("already has procedure 'Go'")); //$NON-NLS-1$
    }

    @Test
    public void testCreateFormMemberAnswersThroughTheMemberSuccessMessage()
    {
        // createFormMember needs a workspace project, a BM model and a write transaction, so no headless
        // test drives it, and every test above stays green if it stops calling the helper they pin - or
        // calls it with another kind, or without the form the model resolved. Its call site is therefore
        // read here - comments removed, so a commented-out call is no call.
        File source = locate(TOOL_SOURCE);
        assertNotNull("could not locate " + TOOL_SOURCE + " from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir"), source); //$NON-NLS-1$
        String body = methodBody(read(source), "private String createFormMember("); //$NON-NLS-1$
        assertTrue("the method read is not the one that writes the member: " + body, //$NON-NLS-1$
            body.contains("FormElementWriter.writeEditableForm(")); //$NON-NLS-1$
        String code = body.replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
        // The member's OWN kind: handed COMMAND instead, every created attribute, field or group would be
        // told it has no action yet, with a '...Attribute.X.Handler.Action' address that names nothing.
        assertTrue("createFormMember must answer through formMemberSuccessMessage, with the member's own " //$NON-NLS-1$
            + "kind and the form the model resolved (issue #628): " + code, //$NON-NLS-1$
            code.contains("formMemberSuccessMessage(project, fKind, normFqn, ref, editedForm)")); //$NON-NLS-1$
        // That form is the one the write resolved: without it no module is read, and a procedure or
        // function of the command's name - the reporter's very case - would go unnamed.
        assertTrue("createFormMember must keep the form the model resolved for the message: " + code, //$NON-NLS-1$
            code.contains("editedForm = fctx.mdForm;")); //$NON-NLS-1$
        assertFalse("createFormMember must not build a 'Created' message of its own again", //$NON-NLS-1$
            body.contains("\"Created \"")); //$NON-NLS-1$
    }

    @Test
    public void testTheGuideSaysCreatingACommandBindsNoAction()
    {
        assertTrue("the guide must say a created command has no action and what the response gives", //$NON-NLS-1$
            new CreateMetadataTool().getGuide().contains(GUIDE_SENTENCE));
        File page = locate(GENERATED_PAGE);
        assertNotNull("could not locate " + GENERATED_PAGE + " from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir"), page); //$NON-NLS-1$
        assertTrue("docs/tools/create_metadata.md must carry the guide's sentence", //$NON-NLS-1$
            read(page).contains(GUIDE_SENTENCE));
    }

    /**
     * The text of the method starting at {@code signature}, up to its closing brace at member
     * indentation, with comments removed.
     */
    private static String methodBody(String source, String signature)
    {
        String text = source.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        int start = text.indexOf(signature);
        assertTrue("signature not found: " + signature, start >= 0); //$NON-NLS-1$
        int end = text.indexOf("\n    }\n", start); //$NON-NLS-1$
        assertTrue("no closing brace after " + signature, end > start); //$NON-NLS-1$
        return text.substring(start, end)
            .replaceAll("(?s)/\\*.*?\\*/", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("//[^\\n]*", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String read(File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static File locate(String relative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, relative);
            if (candidate.exists())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
