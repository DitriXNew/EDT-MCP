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
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormObjectRef;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;

/**
 * Lightweight contract tests for {@link DeleteMetadataTool}: tool metadata and JSON schema, without
 * the Eclipse/EDT runtime. The execute() path (refactoring preview / perform) needs a live workbench
 * and BM model, so it is covered by the E2E suite.
 */
public class DeleteMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("delete_metadata", new DeleteMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(DeleteMetadataTool.NAME, new DeleteMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new DeleteMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new DeleteMetadataTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('delete_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new DeleteMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirm\"")); //$NON-NLS-1$
        assertTrue("schema must declare the force override", //$NON-NLS-1$
            schema.contains("\"force\"")); //$NON-NLS-1$
    }

    @Test
    public void testForceIsOptionalAndDistinctFromConfirm()
    {
        String schema = new DeleteMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("force must not be required", tail.contains("\"force\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // force is the reference-override; confirm is the preview gate — both are declared and distinct.
        assertTrue(schema.contains("\"force\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirm\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDocumentsBlockedAction()
    {
        String schema = new DeleteMetadataTool().getOutputSchema();
        assertNotNull(schema);
        // The output envelope must describe the blocked/forced branch a caller can now receive.
        assertTrue("outputSchema must declare blockingReferences", //$NON-NLS-1$
            schema.contains("\"blockingReferences\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare the forced flag", //$NON-NLS-1$
            schema.contains("\"forced\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresLegacyAffectedAliases()
    {
        String schema = new DeleteMetadataTool().getOutputSchema();
        assertNotNull(schema);
        // The affected* keys are deprecated aliases of blocking*, kept for one release for wire
        // compatibility — the schema must declare them for as long as the wire carries them.
        assertTrue("outputSchema must declare the affectedReferences alias", //$NON-NLS-1$
            schema.contains("\"affectedReferences\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare the affectedReferencesCount alias", //$NON-NLS-1$
            schema.contains("\"affectedReferencesCount\"")); //$NON-NLS-1$
    }

    /**
     * Every response branch emits the blocking-reference keys through the shared
     * {@code putBlockingReferences} emitter, so asserting the emitter pins the whole wire contract:
     * {@code affectedReferences} / {@code affectedReferencesCount} (legacy aliases, kept for one
     * release) must carry content IDENTICAL to {@code blockingReferences} / {@code blockingReferencesCount}.
     */
    @Test
    public void testLegacyAffectedAliasesCarryIdenticalContent()
    {
        List<Map<String, Object>> blocking = new ArrayList<>();
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("problemType", "CleanReferenceProblem"); //$NON-NLS-1$ //$NON-NLS-2$
        reference.put("referencingObject", "Document.Order"); //$NON-NLS-1$ //$NON-NLS-2$
        reference.put("reference", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        blocking.add(reference);

        String json =
            DeleteMetadataTool.putBlockingReferences(ToolResult.success(), blocking).toJson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("affectedReferences must mirror blockingReferences exactly", //$NON-NLS-1$
            obj.get("blockingReferences"), obj.get("affectedReferences")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("affectedReferencesCount must mirror blockingReferencesCount exactly", //$NON-NLS-1$
            obj.get("blockingReferencesCount"), obj.get("affectedReferencesCount")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, obj.get("affectedReferencesCount").getAsInt()); //$NON-NLS-1$
        assertEquals("Document.Order", obj.get("affectedReferences").getAsJsonArray() //$NON-NLS-1$ //$NON-NLS-2$
            .get(0).getAsJsonObject().get("referencingObject").getAsString()); //$NON-NLS-1$

        // The empty case (form previews) carries the aliases too — an empty list and a zero count.
        String emptyJson = DeleteMetadataTool
            .putBlockingReferences(ToolResult.success(), new ArrayList<>()).toJson();
        JsonObject emptyObj = JsonParser.parseString(emptyJson).getAsJsonObject();
        assertEquals(emptyObj.get("blockingReferences"), emptyObj.get("affectedReferences")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, emptyObj.get("affectedReferencesCount").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsForceOverride()
    {
        String desc = new DeleteMetadataTool().getDescription();
        assertNotNull(desc);
        assertTrue("description should mention the force override", //$NON-NLS-1$
            desc.toLowerCase().contains("force")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new DeleteMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConfirmIsOptional()
    {
        String schema = new DeleteMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("confirm must not be required", tail.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideCarriesKeyDetail()
    {
        String guide = new DeleteMetadataTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide should warn it is a cascading delete", guide.contains("Think twice")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should document the two-phase workflow", guide.contains("confirm=true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should list member kinds", guide.contains("enum value")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- the 4-part form-object FQN is recognized by the delete dispatch --------------------------

    /**
     * The delete dispatch routes a 4-part form-object FQN ({@code Type.Object.Form.Name}) to the
     * owned-form branch via the SAME recognizer create_metadata uses ({@code parseFormObjectCreate}), so
     * an owned form created by FQN is deletable by that FQN (symmetric with create). This asserts the
     * recognizer the dispatch keys off, runtime-free.
     */
    @Test
    public void testFormObjectFqnRecognizedByDeleteDispatch()
    {
        FormObjectRef ref = FormElementWriter.parseFormObjectCreate("Catalog.Products.Form.ItemForm"); //$NON-NLS-1$
        assertNotNull("a 4-part form FQN must be recognized as an owned form object", ref); //$NON-NLS-1$
        assertEquals("Catalog", ref.ownerType); //$NON-NLS-1$
        assertEquals("Products", ref.ownerName); //$NON-NLS-1$
        assertEquals("ItemForm", ref.formName); //$NON-NLS-1$
        // The dispatch checks the form-MEMBER parser first; it must NOT also claim a 4-part form FQN
        // (otherwise the form-object branch would be unreachable).
        assertNull("a 4-part form FQN is not a form member", //$NON-NLS-1$
            FormElementWriter.parse("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
    }

    /**
     * A CommonForm ({@code CommonForm.Name}, 2 parts) is a real top object - it must fall through the
     * form-object recognizer to the mdclass refactoring path, NOT the owned-form branch.
     */
    @Test
    public void testCommonFormIsNotAnOwnedFormObject()
    {
        assertNull("a CommonForm is a top object, not an owned form", //$NON-NLS-1$
            FormElementWriter.parseFormObjectCreate("CommonForm.MyForm")); //$NON-NLS-1$
    }

    // ---- the orphan form-folder path is built from the RESOLVED names -----------------------------

    /**
     * The on-disk folder of an owned form must be computed from the RESOLVED model names: the model
     * lookup is case-INsensitive (delete 'Catalog.Catalog.Form.itemform' resolves the real ItemForm),
     * while the workspace folder path is case-sensitive - so feeding the canonical names in must yield
     * the exact on-disk folder, regardless of how the user typed the FQN.
     */
    @Test
    public void testFormResourceFolderPathFromResolvedNames()
    {
        assertEquals("src/Catalogs/Products/Forms/ItemForm", //$NON-NLS-1$
            DeleteMetadataTool.formResourceFolderPath("Catalog", "Products", "ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The TYPE token tolerates case (the type-directory lookup is case-insensitive); the NAME
        // segments are emitted verbatim - exactly the resolved names the caller passes.
        assertEquals("src/Catalogs/Products/Forms/ItemForm", //$NON-NLS-1$
            DeleteMetadataTool.formResourceFolderPath("catalog", "Products", "ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("src/Documents/SalesOrder/Forms/DocumentForm", //$NON-NLS-1$
            DeleteMetadataTool.formResourceFolderPath("Document", "SalesOrder", "DocumentForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // An unknown type cannot be mapped to a directory - no path, no blind delete.
        assertNull(DeleteMetadataTool.formResourceFolderPath("Bogus", "X", "Y")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The TYPE token is bilingual: a Russian type token resolves to the SAME English {@code src/}
     * directory (the folder layout is language-neutral), while the owner / form NAME segments pass
     * through verbatim - so a form addressed in Russian deletes the exact same on-disk folder.
     */
    @Test
    public void testFormResourceFolderPathAcceptsRussianTypeToken()
    {
        // "Справочник" (Catalog, singular) -> Catalogs directory; the Cyrillic name parts are verbatim.
        assertEquals("src/Catalogs/Товары/Forms/Форма", //$NON-NLS-1$
            DeleteMetadataTool.formResourceFolderPath(
                "Справочник", //$NON-NLS-1$
                "Товары", "Форма")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The folder mapping is not Catalog/Document-specific: any object family with an own {@code src/}
     * type directory resolves (here an InformationRegister, whose directory is the plural form).
     */
    @Test
    public void testFormResourceFolderPathResolvesOtherObjectFamilies()
    {
        assertEquals("src/InformationRegisters/Prices/Forms/ListForm", //$NON-NLS-1$
            DeleteMetadataTool.formResourceFolderPath("InformationRegister", "Prices", "ListForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The plural type token resolves to the same directory as the singular one.
        assertEquals("src/InformationRegisters/Prices/Forms/ListForm", //$NON-NLS-1$
            DeleteMetadataTool.formResourceFolderPath("InformationRegisters", "Prices", "ListForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A null or empty type token has no directory mapping, so no path is produced - the delete must
     * never invent a folder to remove from a blank type.
     */
    @Test
    public void testFormResourceFolderPathNullForBlankType()
    {
        assertNull(DeleteMetadataTool.formResourceFolderPath(null, "Products", "ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DeleteMetadataTool.formResourceFolderPath("", "Products", "ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ---- putBlockingReferences: the single shared emitter feeding every response branch -----------

    /**
     * The shared emitter must count and emit MULTIPLE blocking references in order, under both the
     * canonical {@code blocking*} keys and the legacy {@code affected*} aliases - the count equals the
     * list size and the two arrays are element-for-element identical, so a caller reading either name
     * sees the same N referencers in the same order.
     */
    @Test
    public void testPutBlockingReferencesEmitsMultipleInOrder()
    {
        List<Map<String, Object>> blocking = new ArrayList<>();
        blocking.add(reference("Catalog.Products", "type")); //$NON-NLS-1$ //$NON-NLS-2$
        blocking.add(reference("Document.Order", "registerRecords")); //$NON-NLS-1$ //$NON-NLS-2$
        blocking.add(reference("Report.Sales", "dataSource")); //$NON-NLS-1$ //$NON-NLS-2$

        String json = DeleteMetadataTool.putBlockingReferences(ToolResult.success(), blocking).toJson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(3, obj.get("blockingReferencesCount").getAsInt()); //$NON-NLS-1$
        assertEquals("count and aliases must agree for N>1", //$NON-NLS-1$
            obj.get("blockingReferencesCount"), obj.get("affectedReferencesCount")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the alias array must mirror the canonical array exactly (order included)", //$NON-NLS-1$
            obj.get("blockingReferences"), obj.get("affectedReferences")); //$NON-NLS-1$ //$NON-NLS-2$
        // Order is preserved: the second referencer is the one inserted second.
        assertEquals("Document.Order", obj.get("blockingReferences").getAsJsonArray() //$NON-NLS-1$ //$NON-NLS-2$
            .get(1).getAsJsonObject().get("referencingObject").getAsString()); //$NON-NLS-1$
    }

    /**
     * The emitter copies every field of a reference map verbatim into the JSON - the
     * {@code referencingObject} / {@code reference} (feature) / {@code targetObject} a
     * {@code CleanReferenceProblem} carries all survive, under both the canonical and the alias keys.
     */
    @Test
    public void testPutBlockingReferencesPreservesAllFields()
    {
        List<Map<String, Object>> blocking = new ArrayList<>();
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("problemType", "CleanReferenceProblem"); //$NON-NLS-1$ //$NON-NLS-2$
        ref.put("referencingObject", "Document.Order"); //$NON-NLS-1$ //$NON-NLS-2$
        ref.put("reference", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        ref.put("targetObject", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        blocking.add(ref);

        String json = DeleteMetadataTool.putBlockingReferences(ToolResult.success(), blocking).toJson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        JsonObject canonical = obj.get("blockingReferences").getAsJsonArray() //$NON-NLS-1$
            .get(0).getAsJsonObject();
        assertEquals("CleanReferenceProblem", canonical.get("problemType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("type", canonical.get("reference").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Products", canonical.get("targetObject").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // The legacy alias carries the SAME field set (it is the same list object).
        assertEquals(obj.get("blockingReferences"), obj.get("affectedReferences")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The emitter never drops the success flag the caller set on the result: emitting the
     * blocking-reference block onto a {@code ToolResult.success()} leaves {@code success=true} - the
     * preview / forced-execute branches rely on this (only the blocked branch flips success).
     */
    @Test
    public void testPutBlockingReferencesKeepsSuccessFlag()
    {
        String json = DeleteMetadataTool
            .putBlockingReferences(ToolResult.success(), new ArrayList<>()).toJson();
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue("success must remain true after emitting an empty blocking block", //$NON-NLS-1$
            obj.get("success").getAsBoolean()); //$NON-NLS-1$
    }

    // ---- additional metadata / schema facets ------------------------------------------------------

    /**
     * The description must advertise the full two-phase contract a caller keys off: the preview gate,
     * the confirm step, the blocked outcome, and the force override that overrides it.
     */
    @Test
    public void testDescriptionDocumentsTwoPhaseAndBlocking()
    {
        String desc = new DeleteMetadataTool().getDescription().toLowerCase();
        assertTrue("description must mention the preview phase", desc.contains("preview")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention confirm", desc.contains("confirm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention the blocked path", desc.contains("blocked")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention dangling references left by force", //$NON-NLS-1$
            desc.contains("dangling")); //$NON-NLS-1$
    }

    /**
     * The output schema must declare the full preview/blocked envelope a caller now receives, beyond
     * the blocking* / affected* aliases already pinned above: action, fqn, refactoringTitle, items,
     * the blocking flag, the blocking count and the human message.
     */
    @Test
    public void testOutputSchemaDeclaresPreviewEnvelope()
    {
        String schema = new DeleteMetadataTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare fqn", schema.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare refactoringTitle", //$NON-NLS-1$
            schema.contains("\"refactoringTitle\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare items", schema.contains("\"items\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare the blocking flag", schema.contains("\"blocking\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare blockingReferencesCount", //$NON-NLS-1$
            schema.contains("\"blockingReferencesCount\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare message", schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The output schema's {@code action} field must name all three terminal actions a caller can
     * receive - {@code preview}, {@code executed} and {@code blocked} - so an agent can branch on them.
     */
    @Test
    public void testOutputSchemaActionNamesAllThreeOutcomes()
    {
        String schema = new DeleteMetadataTool().getOutputSchema();
        assertTrue("action must name 'preview'", schema.contains("preview")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("action must name 'executed'", schema.contains("executed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("action must name 'blocked'", schema.contains("blocked")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The guide must document the reference-blocking + force override (the safety mechanism reviewers
     * check) and the two cross-model delete surfaces this tool also handles - form objects and form
     * members - so a caller knows a 4-part / 6+-part form FQN is deletable too.
     */
    @Test
    public void testGuideDocumentsForceAndFormSurfaces()
    {
        String guide = new DeleteMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the force override", guide.contains("force=true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the blocked outcome", guide.contains("action='blocked'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the form object surface", guide.contains("Form object")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the form members surface", guide.contains("Form members")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must note the deprecated affected* aliases", //$NON-NLS-1$
            guide.contains("affectedReferences")); //$NON-NLS-1$
    }

    /** A {problemType, referencingObject, reference} blocking-reference map for the emitter tests. */
    private static Map<String, Object> reference(String referencingObject, String feature)
    {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("problemType", "CleanReferenceProblem"); //$NON-NLS-1$ //$NON-NLS-2$
        ref.put("referencingObject", referencingObject); //$NON-NLS-1$
        ref.put("reference", feature); //$NON-NLS-1$
        return ref;
    }

    // ===== XDTO package member deletion (issue #183 stream 1) ========================================
    //
    // An XDTO ObjectType/Property member is removed directly (the md-refactoring service is mdclass-only),
    // mirroring the form-member delete's two-phase shape. execute() needs a live workbench + BM model, so
    // the write itself is E2E-covered; the pure locate / "not found" message builders (used by BOTH the
    // rolled-back preview read and the write) are unit-tested here against an in-memory
    // XdtoFactory-built Package fixture (mirrors XdtoWriterTest).

    private static com._1c.g5.v8.dt.xdto.model.Package fixturePackage()
    {
        return com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createPackage();
    }

    @Test
    public void testLocateXdtoMemberFindsObjectType()
    {
        com._1c.g5.v8.dt.xdto.model.Package pkg = fixturePackage();
        com._1c.g5.v8.dt.xdto.model.ObjectType type =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createObjectType();
        type.setName("MyType"); //$NON-NLS-1$
        pkg.getObjects().add(type);

        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.ObjectType.MyType"); //$NON-NLS-1$
        String[] found = DeleteMetadataTool.locateXdtoMember(pkg, ref);
        assertNotNull("an existing ObjectType must be located", found); //$NON-NLS-1$
        assertEquals("ObjectType", found[0]); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLocateXdtoMemberMissingReturnsNull()
    {
        com._1c.g5.v8.dt.xdto.model.Package pkg = fixturePackage();
        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.ObjectType.Missing"); //$NON-NLS-1$
        assertNull("a missing ObjectType must not be located", DeleteMetadataTool.locateXdtoMember(pkg, ref)); //$NON-NLS-1$
    }

    @Test
    public void testLocateXdtoMemberNullContentReturnsNull()
    {
        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.Property.MyProp"); //$NON-NLS-1$
        assertNull("a never-authored package (null content) must not locate a member", //$NON-NLS-1$
            DeleteMetadataTool.locateXdtoMember(null, ref));
    }

    @Test
    public void testLocateXdtoMemberFindsNestedProperty()
    {
        com._1c.g5.v8.dt.xdto.model.Package pkg = fixturePackage();
        com._1c.g5.v8.dt.xdto.model.ObjectType type =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createObjectType();
        type.setName("MyType"); //$NON-NLS-1$
        pkg.getObjects().add(type);
        com._1c.g5.v8.dt.xdto.model.Property property =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createProperty();
        property.setName("MyProp"); //$NON-NLS-1$
        type.getProperties().add(property);

        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.ObjectType.MyType.Property.MyProp"); //$NON-NLS-1$
        String[] found = DeleteMetadataTool.locateXdtoMember(pkg, ref);
        assertNotNull("a nested Property must be located", found); //$NON-NLS-1$
        assertEquals("Property", found[0]); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLocateXdtoMemberToleratesYoSpelledLookup()
    {
        // issue #183 P2 #4: locateXdtoMember delegates to XdtoWriter.findObjectType, which now falls
        // back to the yo-normalized stored name on an exact miss - so delete_metadata's own preview
        // (and, by the SAME shared helper, the actual delete) resolves a member even when the request
        // FQN still spells its name with the original "yo" that create_metadata normalized away.
        com._1c.g5.v8.dt.xdto.model.Package pkg = fixturePackage();
        com._1c.g5.v8.dt.xdto.model.ObjectType type =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createObjectType();
        // "Zakaz-e" (a Russian word for "order"), yo-normalized - the spelling create_metadata stores.
        type.setName(MetadataLanguageUtils.cp(0x0417, 0x0430, 0x043a, 0x0430, 0x0437, 0x0435));
        pkg.getObjects().add(type);

        // The SAME word, but spelled with the ORIGINAL "yo".
        String yoSpelledName = MetadataLanguageUtils.cp(0x0417, 0x0430, 0x043a, 0x0430, 0x0437, 0x0451);
        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.ObjectType." + yoSpelledName); //$NON-NLS-1$
        String[] found = DeleteMetadataTool.locateXdtoMember(pkg, ref);
        assertNotNull("delete_metadata's own locate must tolerate a yo-spelled lookup FQN", found); //$NON-NLS-1$
        assertEquals("ObjectType", found[0]); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testXdtoMemberNotFoundErrorNamesObjectTypeAndPackage()
    {
        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.ObjectType.Missing"); //$NON-NLS-1$
        String err = DeleteMetadataTool.xdtoMemberNotFoundError(ref);
        assertTrue("the refusal must be a ToolResult error json", err.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(err.contains("Missing")); //$NON-NLS-1$
        assertTrue(err.contains("XDTOPackage.MyPackage")); //$NON-NLS-1$
    }

    @Test
    public void testXdtoMemberNotFoundErrorNamesNestedPropertyOwner()
    {
        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.ObjectType.MyType.Property.Missing"); //$NON-NLS-1$
        String err = DeleteMetadataTool.xdtoMemberNotFoundError(ref);
        assertTrue("a nested property's not-found error must name its ObjectType owner", //$NON-NLS-1$
            err.contains("ObjectType.MyType")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionDocumentsXdtoPackageMembers()
    {
        String desc = new DeleteMetadataTool().getDescription();
        assertTrue("description should mention the XDTO package member FQN shape", //$NON-NLS-1$
            desc.contains("XDTOPackage")); //$NON-NLS-1$
        assertTrue("description should mention the ObjectType member kind", desc.contains("ObjectType")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- the predefined-item FQN is recognized by the delete dispatch (issue #293) -----------------

    /**
     * The delete dispatch routes a 4-part predefined-item FQN ({@code Type.Owner.Predefined.Item}) to
     * its dedicated branch via {@link PredefinedWriter#parseRef} - the SAME recognizer create_metadata
     * / modify_metadata use. Mirrors {@link #testFormObjectFqnRecognizedByDeleteDispatch}.
     */
    @Test
    public void testPredefinedItemFqnRecognizedByDeleteDispatch()
    {
        PredefinedWriter.PredefinedRef ref = PredefinedWriter.parseRef("Catalog.Products.Predefined.Blue"); //$NON-NLS-1$
        assertNotNull("a 4-part predefined-item FQN must be recognized", ref); //$NON-NLS-1$
        assertEquals("Catalog", ref.ownerType); //$NON-NLS-1$
        assertEquals("Products", ref.ownerName); //$NON-NLS-1$
        assertEquals("Blue", ref.itemName); //$NON-NLS-1$
        // The dispatch checks the form-object / form-member parsers first; a predefined-item FQN must
        // not ALSO be claimed by either (they key off a different kind token at the same position).
        assertNull(FormElementWriter.parse("Catalog.Products.Predefined.Blue")); //$NON-NLS-1$
        assertNull(FormElementWriter.parseFormObjectCreate("Catalog.Products.Predefined.Blue")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsPredefinedItems()
    {
        String desc = new DeleteMetadataTool().getDescription();
        assertTrue("description should mention predefined items", desc.contains("PREDEFINED")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ===== predefined-item incoming-reference check helpers (issue #293 rework + fix-round) ===========
    //
    // The full scan (collectPredefinedItemBlockingReferences) needs a live BM model + workbench - it is
    // E2E-covered - but it is now a thin wrapper around
    // MetadataReferenceService.collectReferencesForObjectStrict (the SAME engine find_references uses,
    // now also reporting whether its BSL step completed - fail-closed wiring is E2E-covered via
    // test_predefined_items.py). It already owns the technical-noise filtering
    // (isInternalReference/isInternalPath) that used to be duplicated here as isInternalPredefinedReference.
    // What remains local to THIS delete safety check is the NARROWED owner-self exclusion
    // (isOwnerSelfReference - same owner top-object AND the exact structural "source" feature; NOT
    // applied by the shared service, since find_references intentionally shows self-references) and the
    // ReferenceInfo -> wire-row mapping (describePredefinedReferenceInfo). All are plain functions over
    // Mockito-mocked BM/EMF interfaces, runtime-free.

    @Test
    public void testIsOwnerSelfReferenceExcludesSameOwnerSourceFeatureReference()
    {
        // issue #293 P1 fix-round (narrowed, confirmed live): a pristine predefined item's own owner
        // catalog showing up as a "reference" through the derived predefined-data-source linkage - the
        // EXACT feature name "source" (PredefinedItem itself declares no such feature in
        // MdClass.xcore) - must be excluded. It is not an external dependency and must never block the
        // delete.
        IBmObject source = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(source.bmIsTop()).thenReturn(true);
        when(source.bmGetId()).thenReturn(42L);

        MetadataReferenceService.ReferenceInfo info = new MetadataReferenceService.ReferenceInfo(
            "Catalogs", "Catalog.Products - source", "source", source); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a same-owner reference through the structural 'source' feature must be excluded", //$NON-NLS-1$
            DeleteMetadataTool.isOwnerSelfReference(info, 42L));
    }

    /**
     * issue #293 P1 fix-round (the actual bug being fixed): matching on same-owner ALONE was too broad
     * and silently discarded a REAL same-owner reference (e.g. a Catalog attribute's fill value /
     * ReferenceValue pointing at a predefined item of the SAME catalog, feature "value"; or a
     * ChartOfCalculationTypesPredefinedItem's base/displaced/leading referring to a SIBLING predefined
     * item). Such a reference must still BLOCK the delete even though its source's top container is the
     * SAME owner - only the "source"-feature linkage is purely structural.
     */
    @Test
    public void testIsOwnerSelfReferenceKeepsSameOwnerReferenceWithNonSourceFeature()
    {
        IBmObject source = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(source.bmIsTop()).thenReturn(true);
        when(source.bmGetId()).thenReturn(42L);

        MetadataReferenceService.ReferenceInfo info = new MetadataReferenceService.ReferenceInfo(
            "Catalogs", "Catalog.Products - value", "value", source); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("a same-owner reference through a REAL feature (not the structural 'source' " //$NON-NLS-1$
            + "linkage) must NOT be excluded - it must still block the delete", //$NON-NLS-1$
            DeleteMetadataTool.isOwnerSelfReference(info, 42L));
    }

    @Test
    public void testIsOwnerSelfReferenceKeepsReferenceFromADifferentTopObject()
    {
        // A REAL external dependency (e.g. another object's default value referencing this item) must
        // never be excluded, even though it goes through the exact same check.
        IBmObject source = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(source.bmIsTop()).thenReturn(true);
        when(source.bmGetId()).thenReturn(99L);

        MetadataReferenceService.ReferenceInfo info = new MetadataReferenceService.ReferenceInfo(
            "Documents", "Document.Order - type", "type", source); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("a reference from a genuinely different top object must NOT be excluded", //$NON-NLS-1$
            DeleteMetadataTool.isOwnerSelfReference(info, 42L));
    }

    /**
     * The exclusion must climb a NESTED source's container chain to its own top object, not just match
     * a direct top-level self-reference - e.g. a "source"-feature reference held on one of the owner's
     * nested objects is still an owner-self reference. Uses the "source" feature (the narrowed
     * exclusion's only match) so the container-walk behaviour is tested independently of the
     * feature-name check.
     */
    @Test
    public void testIsOwnerSelfReferenceWalksUpToNestedSourcesTopContainer()
    {
        IBmObject top = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(top.bmIsTop()).thenReturn(true);
        when(top.bmGetId()).thenReturn(42L);

        IBmObject nested = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(nested.bmIsTop()).thenReturn(false);
        when(((EObject)nested).eContainer()).thenReturn((EObject)top);

        MetadataReferenceService.ReferenceInfo info = new MetadataReferenceService.ReferenceInfo(
            "Catalogs", "Catalog.Products - source", "source", nested); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a nested source whose container chain reaches the owner top object must also be " //$NON-NLS-1$
            + "excluded (feature 'source')", DeleteMetadataTool.isOwnerSelfReference(info, 42L)); //$NON-NLS-1$
    }

    /**
     * A BSL reference never carries a live source object ({@link
     * MetadataReferenceService.ReferenceInfo#sourceObject} is {@code null} - the Xtext finder hands
     * back a URI, not a BM object) - it can never be treated as an owner-self reference: a BSL module is
     * always a DIFFERENT top object from the predefined item's owner.
     */
    @Test
    public void testIsOwnerSelfReferenceNeverExcludesABslReference()
    {
        MetadataReferenceService.ReferenceInfo bslInfo = new MetadataReferenceService.ReferenceInfo(
            "BSL modules", "CommonModules/MyModule/Module.bsl", 10); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a BSL reference (no sourceObject) must never be excluded as an owner-self reference", //$NON-NLS-1$
            DeleteMetadataTool.isOwnerSelfReference(bslInfo, 42L));
    }

    /**
     * issue #293 P2 fix: a self-containing eContainer() cycle must never hang the delete's reference
     * scan (it would otherwise hold the read transaction open forever). The bounded depth guard in
     * findTopContainer must stop and return without throwing; the JUnit timeout below fails loudly if a
     * regression reintroduces the infinite loop.
     */
    @Test(timeout = 10000)
    public void testIsOwnerSelfReferenceTerminatesOnASelfContainingContainerCycle()
    {
        IBmObject source = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(source.bmIsTop()).thenReturn(false);
        when(source.bmGetId()).thenReturn(7L); // deliberately != ownerTopId below
        when(((EObject)source).eContainer()).thenReturn((EObject)source); // self-containing cycle

        MetadataReferenceService.ReferenceInfo info = new MetadataReferenceService.ReferenceInfo(
            "Catalogs", "Catalog.Products - source", "source", source); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // The call must RETURN (not hang); a cyclic chain never reaches a real top object, so
        // findTopContainer returns null and the reference is treated as NOT owner-self (kept/blocks).
        assertFalse("a self-containing eContainer() cycle must terminate and must never be treated as " //$NON-NLS-1$
            + "an owner-self match", DeleteMetadataTool.isOwnerSelfReference(info, 42L)); //$NON-NLS-1$
    }

    /**
     * The SINGLE fail-closed block decision for a predefined-item delete: a non-forced delete blocks
     * when the reference scan found references OR did not complete (UNVERIFIED state); force bypasses
     * both. Both the confirm path and the preview's blocking flag route through this method, so
     * pinning it guards against a regression that silently deletes on an unverified/referenced item.
     */
    @Test
    public void testPredefinedDeleteWouldBlockFailClosedDecision()
    {
        List<Map<String, Object>> none = new ArrayList<>();
        List<Map<String, Object>> some = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("referencingObject", "Document.Order"); //$NON-NLS-1$ //$NON-NLS-2$
        some.add(row);

        // completed scan, NO references, no force -> allow (not blocked)
        assertFalse("a completed scan with no references must not block", //$NON-NLS-1$
            DeleteMetadataTool.predefinedDeleteWouldBlock(
                new DeleteMetadataTool.PredefinedRefScan(none, true), false));
        // completed scan WITH references, no force -> block
        assertTrue("a completed scan with references must block without force", //$NON-NLS-1$
            DeleteMetadataTool.predefinedDeleteWouldBlock(
                new DeleteMetadataTool.PredefinedRefScan(some, true), false));
        // completed scan WITH references, force -> allow (force bypasses)
        assertFalse("force must bypass a references block", //$NON-NLS-1$
            DeleteMetadataTool.predefinedDeleteWouldBlock(
                new DeleteMetadataTool.PredefinedRefScan(some, true), true));
        // INCOMPLETE scan (unverified), nothing gathered, no force -> block (fail-closed)
        assertTrue("an unverified (incomplete) scan must block without force", //$NON-NLS-1$
            DeleteMetadataTool.predefinedDeleteWouldBlock(
                new DeleteMetadataTool.PredefinedRefScan(none, false), false));
        // incomplete scan, force -> allow
        assertFalse("force must bypass an unverified-scan block", //$NON-NLS-1$
            DeleteMetadataTool.predefinedDeleteWouldBlock(
                new DeleteMetadataTool.PredefinedRefScan(none, false), true));
        // incomplete scan that DID gather partial refs, no force -> block
        assertTrue("a partial (incomplete but non-empty) scan must block without force", //$NON-NLS-1$
            DeleteMetadataTool.predefinedDeleteWouldBlock(
                new DeleteMetadataTool.PredefinedRefScan(some, false), false));
    }

    @Test
    public void testDescribePredefinedReferenceInfoBuildsExpectedRowShapeForMetadataReference()
    {
        PredefinedItem item = mock(PredefinedItem.class);
        when(item.getName()).thenReturn("Blue"); //$NON-NLS-1$

        IBmObject sourceObject = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        MetadataReferenceService.ReferenceInfo info = new MetadataReferenceService.ReferenceInfo(
            "Documents", "Document.Order - type", "type", sourceObject); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> row = DeleteMetadataTool.describePredefinedReferenceInfo(item, info);
        assertEquals("Documents", row.get("problemType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Document.Order - type", row.get("referencingObject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("type", row.get("reference")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Blue", row.get("targetObject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a metadata reference row carries no line number (a BSL-only field)", //$NON-NLS-1$
            row.containsKey("line")); //$NON-NLS-1$
    }

    /**
     * issue #293 P2 fix: a BSL reference (found only through the reused find_references engine's Xtext
     * scan - the former hand-rolled collector never saw these) must convert into the SAME wire-row
     * shape, with its line number surfaced.
     */
    @Test
    public void testDescribePredefinedReferenceInfoBuildsExpectedRowShapeForBslReference()
    {
        PredefinedItem item = mock(PredefinedItem.class);
        when(item.getName()).thenReturn("Blue"); //$NON-NLS-1$

        MetadataReferenceService.ReferenceInfo info = new MetadataReferenceService.ReferenceInfo(
            "BSL modules", "CommonModules/MyModule/Module.bsl", 17); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> row = DeleteMetadataTool.describePredefinedReferenceInfo(item, info);
        assertEquals("BSL modules", row.get("problemType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModules/MyModule/Module.bsl", row.get("referencingObject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BSL code", row.get("reference")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(17, row.get("line")); //$NON-NLS-1$
        assertEquals("Blue", row.get("targetObject")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescribePredefinedReferenceInfoOmitsReferencingObjectWhenSourcePathBlank()
    {
        PredefinedItem item = mock(PredefinedItem.class);
        when(item.getName()).thenReturn("Blue"); //$NON-NLS-1$

        MetadataReferenceService.ReferenceInfo info =
            new MetadataReferenceService.ReferenceInfo("Other", "", "someFeature", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> row = DeleteMetadataTool.describePredefinedReferenceInfo(item, info);
        assertFalse("no referencingObject key when the source path is blank", //$NON-NLS-1$
            row.containsKey("referencingObject")); //$NON-NLS-1$
        assertEquals("someFeature", row.get("reference")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Blue", row.get("targetObject")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
