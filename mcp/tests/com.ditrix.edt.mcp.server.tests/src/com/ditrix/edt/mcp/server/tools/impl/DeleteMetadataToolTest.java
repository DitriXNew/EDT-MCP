/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormObjectRef;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.XdtoWriter;

/**
 * Lightweight contract tests for {@link DeleteMetadataTool}: tool metadata and JSON schema, without
 * the Eclipse/EDT runtime. The execute() path (refactoring preview / perform) needs a live workbench
 * and BM model, so it is covered by the E2E suite.
 */
public class DeleteMetadataToolTest
{
    @Test
    public void testMdClassDeleteRefusesNullModelBeforeCallingEdtRefactoring()
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("TestConfiguration"); //$NON-NLS-1$
        IBmModelManager modelManager = mock(IBmModelManager.class);
        when(modelManager.getModel(project)).thenReturn(null);
        IMdRefactoringService refactoringService = mock(IMdRefactoringService.class);
        MdObject object = mock(MdObject.class);
        BmModelResolver.Resolution resolution = BmModelResolver.resolve(project, modelManager);

        String json = new DeleteMetadataTool().prepareMdClassDelete(project, "CommonModule.Calc", //$NON-NLS-1$
            object, true, false, refactoringService, resolution);

        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("BM model is not available for project 'TestConfiguration'. Nothing was " //$NON-NLS-1$
            + "deleted. Use list_projects to check the project state, then retry delete_metadata " //$NON-NLS-1$
            + "when the project is ready.", result.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        verify(refactoringService, never()).createMdObjectDeleteRefactoring(
            org.mockito.ArgumentMatchers.anyCollection());
    }

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
        assertTrue("description must name the force override itself", desc.contains("force=true")); //$NON-NLS-1$ //$NON-NLS-2$
        // The blocked outcome belongs to the md-refactoring path; a description that promised it for
        // every FQN shape (the pre-#321 text) said something the form / XDTO branches never do.
        assertTrue("description must attribute the cascade to the refactoring path", //$NON-NLS-1$
            desc.contains("md-refactoring")); //$NON-NLS-1$
    }

    /**
     * The cascade/blocking contract belongs to the mdclass path. A FORM member and an XDTO package
     * member are removed from their OWN content model - no reference is rewritten and nothing blocks
     * - so a description that folded them into one contract promised a cleanup they never get
     * (issue #321).
     */
    @Test
    public void testDescriptionSeparatesTheBranchesWithoutAReferenceCascade()
    {
        String desc = new DeleteMetadataTool().getDescription().toLowerCase();
        // All three of them, named in the SAME sentence as the no-block statement: an old universal
        // description with a disclaimer appended somewhere else must not pass this.
        int start = desc.indexOf("an owned form object"); //$NON-NLS-1$
        assertTrue("description must name the owned FORM object as a direct-delete branch", //$NON-NLS-1$
            start >= 0);
        // Bounded to the sentence that starts there: an assertion over the whole tail would be
        // satisfied by wording anywhere else in the description.
        int stop = desc.indexOf("re-check with get_metadata_details", start); //$NON-NLS-1$
        assertTrue("the clause must end with the re-check advice", stop > start); //$NON-NLS-1$ //$NON-NLS-2$
        String clause = desc.substring(start, stop);
        assertTrue("the same clause must name the form member", clause.contains("form member")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the same clause must name the xdto package member", //$NON-NLS-1$
            clause.contains("xdto package member")); //$NON-NLS-1$
        assertTrue("the same clause must say nothing blocks them", //$NON-NLS-1$
            clause.contains("nothing blocks them")); //$NON-NLS-1$
        assertTrue("the same clause must say force is ignored there", //$NON-NLS-1$
            clause.contains("force is ignored")); //$NON-NLS-1$
        assertTrue("the same clause must say the cross-reference is not rewritten", //$NON-NLS-1$
            clause.contains("not rewritten")); //$NON-NLS-1$
        // ... and must NOT overstate it: an owned form delete does clear the owner's default-form
        // settings, so "no cleanup at all" would be its own inaccuracy.
        assertTrue("the description must still credit the owner-local cleanup", //$NON-NLS-1$
            desc.contains("owner's own pointers")); //$NON-NLS-1$
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

    // ---- the destructive-consent authorization point (issue #331 / #295 review) ------------------

    /**
     * A recording write: it must run ONLY when consent was granted, and exactly once.
     */
    private static final class RecordingWrite implements DeleteMetadataTool.DeleteWrite
    {
        int calls;

        @Override
        public String perform()
        {
            calls++;
            return "{\"written\":true}"; //$NON-NLS-1$
        }
    }

    private static ConsentPreview anyPreview()
    {
        return new ConsentPreview("Delete form member", "subtitle", 1, //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Collections.singletonList("Catalog.X.Form.F.Attribute.A")); //$NON-NLS-1$
    }

    @Test
    public void testConsentRejectNeverRunsTheWrite()
    {
        RecordingWrite write = new RecordingWrite();
        DeleteMetadataTool tool = new DeleteMetadataTool(
            (name, preview) -> DestructiveConsentGate.ConsentDecision.REJECT);

        String result = tool.deleteWithConsent(anyPreview(), write);

        assertEquals("a REJECTED delete must not mutate anything", 0, write.calls); //$NON-NLS-1$
        assertNotNull(result);
        assertTrue("the caller must get the refusal, not a success payload", //$NON-NLS-1$
            result.contains("error")); //$NON-NLS-1$
    }

    @Test
    public void testConsentTimeoutNeverRunsTheWrite()
    {
        RecordingWrite write = new RecordingWrite();
        DeleteMetadataTool tool = new DeleteMetadataTool(
            (name, preview) -> DestructiveConsentGate.ConsentDecision.TIMEOUT);

        String result = tool.deleteWithConsent(anyPreview(), write);

        assertEquals("an UNANSWERED prompt must not mutate anything", 0, write.calls); //$NON-NLS-1$
        assertTrue(result.contains("error")); //$NON-NLS-1$
    }

    @Test
    public void testConsentAllowRunsTheWriteExactlyOnce()
    {
        RecordingWrite write = new RecordingWrite();
        DeleteMetadataTool tool = new DeleteMetadataTool(
            (name, preview) -> DestructiveConsentGate.ConsentDecision.ALLOW);

        String result = tool.deleteWithConsent(anyPreview(), write);

        assertEquals("an ALLOWED delete runs the write exactly once", 1, write.calls); //$NON-NLS-1$
        assertEquals("{\"written\":true}", result); //$NON-NLS-1$
    }

    private static DeleteMetadataTool toolAnswering(DestructiveConsentGate.ConsentDecision decision)
    {
        return new DeleteMetadataTool((name, preview) -> decision);
    }

    private static DeleteMetadataTool.FormDeletePreview previewWithDescendants(int count)
    {
        DeleteMetadataTool.FormDeletePreview data = new DeleteMetadataTool.FormDeletePreview();
        data.found = true;
        data.type = "FormAttribute"; //$NON-NLS-1$
        for (int i = 0; i < count; i++)
        {
            data.descendants.add(descendant("Col" + i, "FormAttributeColumn")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return data;
    }

    private static FormElementWriter.FormMemberRef columnRef()
    {
        return FormElementWriter.parse("Catalog.Products.Form.ItemForm.Attribute.Rows.Column.Price"); //$NON-NLS-1$
    }

    @Test
    public void testFormMemberBranchRunsItsWriteOnlyWhenConsentIsGranted()
    {
        // Pins the MEMBER branch's authorization step (the Column shape): given ITS preview and ITS
        // write, the write runs only on ALLOW.
        //
        // What this does NOT prove, stated plainly because the comment here used to claim it did: it
        // calls gateFormMemberDelete directly, so re-routing deleteFormMember() straight to
        // performFormDelete() would leave this test green. Driving the real dispatch needs a resolved
        // project + BM services, which a headless unit test has none of. That WIRING is no longer
        // left to a reviewer's eye either: DeleteMetadataConsentSinglePointRatchetTest reads the
        // compiled class and fails when any branch can reach a write without passing through
        // deleteWithConsent (issue #331).
        for (DestructiveConsentGate.ConsentDecision refused : new DestructiveConsentGate.ConsentDecision[] {
            DestructiveConsentGate.ConsentDecision.REJECT, DestructiveConsentGate.ConsentDecision.TIMEOUT })
        {
            RecordingWrite write = new RecordingWrite();
            String result = toolAnswering(refused).gateFormMemberDelete(
                "Catalog.Products.Form.ItemForm.Attribute.Rows.Column.Price", //$NON-NLS-1$
                columnRef(), false, previewWithDescendants(0), write);
            assertEquals("a refused member delete must not run its write (" + refused + ")", //$NON-NLS-1$ //$NON-NLS-2$
                0, write.calls);
            assertTrue(result.contains("error")); //$NON-NLS-1$
        }

        RecordingWrite allowed = new RecordingWrite();
        String ok = toolAnswering(DestructiveConsentGate.ConsentDecision.ALLOW).gateFormMemberDelete(
            "Catalog.Products.Form.ItemForm.Attribute.Rows.Column.Price", //$NON-NLS-1$
            columnRef(), false, previewWithDescendants(2), allowed);
        assertEquals("an allowed member delete runs its write exactly once", 1, allowed.calls); //$NON-NLS-1$
        assertEquals("{\"written\":true}", ok); //$NON-NLS-1$
    }

    @Test
    public void testFormObjectBranchRunsItsWriteOnlyWhenConsentIsGranted()
    {
        // Same scope as its twin above: the authorization STEP; the branch's wiring to it is pinned by
        // DeleteMetadataConsentSinglePointRatchetTest, which reads the compiled class.
        for (DestructiveConsentGate.ConsentDecision refused : new DestructiveConsentGate.ConsentDecision[] {
            DestructiveConsentGate.ConsentDecision.REJECT, DestructiveConsentGate.ConsentDecision.TIMEOUT })
        {
            RecordingWrite write = new RecordingWrite();
            String result = toolAnswering(refused).gateFormObjectDelete(
                "Catalog.Products.Form.ItemForm", new DeleteMetadataTool.FormContentSummary(), write); //$NON-NLS-1$
            assertEquals("a refused form-object delete must not run its write (" + refused + ")", //$NON-NLS-1$ //$NON-NLS-2$
                0, write.calls);
            assertTrue(result.contains("error")); //$NON-NLS-1$
        }

        RecordingWrite allowed = new RecordingWrite();
        String ok = toolAnswering(DestructiveConsentGate.ConsentDecision.ALLOW).gateFormObjectDelete(
            "Catalog.Products.Form.ItemForm", new DeleteMetadataTool.FormContentSummary(), allowed); //$NON-NLS-1$
        assertEquals("an allowed form-object delete runs its write exactly once", 1, allowed.calls); //$NON-NLS-1$
        assertEquals("{\"written\":true}", ok); //$NON-NLS-1$
    }

    /** A requester that records what it was asked, so a test can assert it was NOT asked at all. */
    private static final class RecordingRequester implements DeleteMetadataTool.ConsentRequester
    {
        final List<ConsentPreview> asked = new ArrayList<>();

        private final DestructiveConsentGate.ConsentDecision answer;

        RecordingRequester(DestructiveConsentGate.ConsentDecision answer)
        {
            this.answer = answer;
        }

        @Override
        public DestructiveConsentGate.ConsentDecision request(String toolName, ConsentPreview preview)
        {
            asked.add(preview);
            return answer;
        }
    }

    private static final String XDTO_FQN = "XDTOPackage.MyPackage.ObjectType.Order"; //$NON-NLS-1$

    private static XdtoWriter.MemberRef xdtoRef()
    {
        return XdtoWriter.parseMemberRef(XDTO_FQN);
    }

    /**
     * The XDTO branch's ORDER, pinned by BEHAVIOUR: a target that is not there must be answered
     * without a destructive prompt ever being raised. Bytecode offsets alone cannot see this - the
     * lookup could run first and its RESULT be ignored, which is exactly the defect issue #331 records
     * (the prompt came first, "not found" after it had been dealt with).
     */
    @Test
    public void testXdtoBranchAnswersAnUnresolvedPackageWithoutAskingForConsent()
    {
        RecordingRequester requester = new RecordingRequester(DestructiveConsentGate.ConsentDecision.ALLOW);
        RecordingWrite write = new RecordingWrite();

        String result = new DeleteMetadataTool(requester).gateXdtoMemberDelete(XDTO_FQN, xdtoRef(),
            new DeleteMetadataTool.XdtoLookup(false, null), write);

        assertTrue("a delete that cannot even resolve its package must not raise a destructive prompt", //$NON-NLS-1$
            requester.asked.isEmpty());
        assertEquals("nothing may be written when the package did not resolve", 0, write.calls); //$NON-NLS-1$
        assertTrue("the package-level failure must keep ITS own message, not be collapsed into the " //$NON-NLS-1$
            + "member-level one: " + result, //$NON-NLS-1$
            result.contains("The XDTO package could not be resolved inside the transaction.")); //$NON-NLS-1$
        assertFalse("... and it must not be reported as a missing member: " + result, //$NON-NLS-1$
            result.contains("ObjectType not found")); //$NON-NLS-1$
    }

    @Test
    public void testXdtoBranchAnswersAMissingMemberWithoutAskingForConsent()
    {
        RecordingRequester requester = new RecordingRequester(DestructiveConsentGate.ConsentDecision.ALLOW);
        RecordingWrite write = new RecordingWrite();

        String result = new DeleteMetadataTool(requester).gateXdtoMemberDelete(XDTO_FQN, xdtoRef(),
            new DeleteMetadataTool.XdtoLookup(true, null), write);

        assertTrue("a typo in the member name must not raise a destructive prompt (issue #331)", //$NON-NLS-1$
            requester.asked.isEmpty());
        assertEquals("nothing may be written when the member is not there", 0, write.calls); //$NON-NLS-1$
        assertTrue("the caller must be told WHICH member and where to look: " + result, //$NON-NLS-1$
            result.contains("ObjectType not found: 'Order' in package XDTOPackage.MyPackage")); //$NON-NLS-1$
    }

    @Test
    public void testXdtoBranchAsksOnceForAResolvedTargetAndRunsTheWriteOnAllow()
    {
        RecordingRequester requester = new RecordingRequester(DestructiveConsentGate.ConsentDecision.ALLOW);
        RecordingWrite write = new RecordingWrite();

        String result = new DeleteMetadataTool(requester).gateXdtoMemberDelete(XDTO_FQN, xdtoRef(),
            new DeleteMetadataTool.XdtoLookup(true, new String[] { "ObjectType" }), write); //$NON-NLS-1$

        assertEquals("a resolved target is asked about exactly once", 1, requester.asked.size()); //$NON-NLS-1$
        assertEquals("the prompt must name what is really removed", //$NON-NLS-1$
            java.util.Collections.singletonList(XDTO_FQN), requester.asked.get(0).getTopNames());
        assertEquals("an allowed XDTO delete runs its write exactly once", 1, write.calls); //$NON-NLS-1$
        assertEquals("{\"written\":true}", result); //$NON-NLS-1$
    }

    @Test
    public void testXdtoBranchRunsItsWriteOnlyWhenConsentIsGranted()
    {
        for (DestructiveConsentGate.ConsentDecision refused : new DestructiveConsentGate.ConsentDecision[] {
            DestructiveConsentGate.ConsentDecision.REJECT, DestructiveConsentGate.ConsentDecision.TIMEOUT })
        {
            RecordingRequester requester = new RecordingRequester(refused);
            RecordingWrite write = new RecordingWrite();

            String result = new DeleteMetadataTool(requester).gateXdtoMemberDelete(XDTO_FQN, xdtoRef(),
                new DeleteMetadataTool.XdtoLookup(true, new String[] { "ObjectType" }), write); //$NON-NLS-1$

            assertEquals("a refused XDTO delete must not run its write (" + refused + ")", //$NON-NLS-1$ //$NON-NLS-2$
                0, write.calls);
            assertEquals("a resolved target is still asked about", 1, requester.asked.size()); //$NON-NLS-1$
            assertTrue(result.contains("error")); //$NON-NLS-1$
        }
    }

    /**
     * The XDTO delete's generic failure now runs BEFORE consent as well (the lookup moved in front of
     * the gate), so "Delete failed: &lt;whatever the platform said&gt;" is the first and often only thing
     * the caller sees. It has to say which delete failed, that nothing was removed, and what to do -
     * CLAUDE.md rule #8 - while keeping the platform's own message, which is the only thing that tells
     * a corrupt .xdto apart from a stale BM id.
     */
    @Test
    public void testXdtoDeleteFailureNamesTheTargetAndTheWayOut()
    {
        String json = DeleteMetadataTool.xdtoDeleteFailure(XDTO_FQN,
            new IllegalStateException("Resource could not be loaded")); //$NON-NLS-1$

        assertTrue("the error must name the delete it belongs to: " + json, json.contains(XDTO_FQN)); //$NON-NLS-1$
        assertTrue("... say that nothing was removed: " + json, json.contains("Nothing was deleted")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("... point at the way out: " + json, json.contains("get_metadata_details")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("... and keep the platform's own diagnosis: " + json, //$NON-NLS-1$
            json.contains("Resource could not be loaded")); //$NON-NLS-1$
    }

    @Test
    public void testThePreviewListsTheSingularElementsADeleteTakesToo()
    {
        // A Table owns its command bar, context menu, tooltip and three additions through SINGULAR
        // containments - real addressable elements that EcoreUtil.remove takes with it. The walk
        // covered only `items` and `columns`, so the preview promised the table alone (issue #295
        // review). The features are not listed by hand: anything single-valued that IS a FormItem.
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/deletepreview"); //$NON-NLS-1$
        EClass formItem = factory.createEClass();
        formItem.setName("FormItem"); //$NON-NLS-1$
        EAttribute itemName = factory.createEAttribute();
        itemName.setName("name"); //$NON-NLS-1$
        itemName.setEType(EcorePackage.Literals.ESTRING);
        formItem.getEStructuralFeatures().add(itemName);
        EReference bar = factory.createEReference();
        bar.setName("autoCommandBar"); //$NON-NLS-1$
        bar.setEType(formItem);
        bar.setContainment(true);
        formItem.getEStructuralFeatures().add(bar);
        EReference dataPath = factory.createEReference();
        dataPath.setName("dataPath"); //$NON-NLS-1$
        dataPath.setEType(EcorePackage.Literals.EOBJECT);
        dataPath.setContainment(true);
        formItem.getEStructuralFeatures().add(dataPath);
        pkg.getEClassifiers().add(formItem);

        EObject table = new DynamicEObjectImpl(formItem);
        EObject commandBar = new DynamicEObjectImpl(formItem);
        commandBar.eSet(itemName, "GoodsCommandBar"); //$NON-NLS-1$
        table.eSet(bar, commandBar);
        // A non-element containment must NOT be counted as a removed element.
        table.eSet(dataPath, EcoreFactory.eINSTANCE.createEObject());

        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        DeleteMetadataTool.collectDescendantsForTest(table, out);

        assertEquals("the singular contained element must be listed, the data path must not", //$NON-NLS-1$
            1, out.size());
        assertEquals("GoodsCommandBar", out.get(0).get("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testThePreviewListsTheHandlerBindingsADeleteTakesToo()
    {
        // The walk named the features it followed - `items`, `columns`, and the singular containments
        // holding a FormItem - so the two containments that hold something ELSE were invisible: an
        // element's `handlers` list and a command's `action`. EcoreUtil.remove takes both, so deleting
        // a field, a button or a command was authorized and previewed as "one member" while it
        // silently carried off the procedure binding (issue #295 review). The radius now follows the
        // CONTAINMENT structure, and reports whatever in it carries its own name.
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/deleteradius"); //$NON-NLS-1$

        // An EventHandler is NOT a FormItem and lives in a MANY containment - the shape the walk missed.
        EClass eventHandler = factory.createEClass();
        eventHandler.setName("EventHandler"); //$NON-NLS-1$
        eventHandler.getEStructuralFeatures().add(nameAttribute(factory));
        // A command's action: an UNNAMED container holding the named CommandHandler - so the walk has
        // to descend THROUGH something it does not report.
        EClass commandHandler = factory.createEClass();
        commandHandler.setName("CommandHandler"); //$NON-NLS-1$
        commandHandler.getEStructuralFeatures().add(nameAttribute(factory));
        EClass actionContainer = factory.createEClass();
        actionContainer.setName("FormCommandHandlerContainer"); //$NON-NLS-1$
        EReference handlerRef = factory.createEReference();
        handlerRef.setName("handler"); //$NON-NLS-1$
        handlerRef.setEType(commandHandler);
        handlerRef.setContainment(true);
        actionContainer.getEStructuralFeatures().add(handlerRef);

        EClass field = factory.createEClass();
        field.setName("FormField"); //$NON-NLS-1$
        field.getEStructuralFeatures().add(nameAttribute(factory));
        field.getEStructuralFeatures().add(manyContainment(factory, "handlers", eventHandler)); //$NON-NLS-1$
        EReference action = factory.createEReference();
        action.setName("action"); //$NON-NLS-1$
        action.setEType(actionContainer);
        action.setContainment(true);
        field.getEStructuralFeatures().add(action);
        pkg.getEClassifiers().add(eventHandler);
        pkg.getEClassifiers().add(commandHandler);
        pkg.getEClassifiers().add(actionContainer);
        pkg.getEClassifiers().add(field);

        EObject target = new DynamicEObjectImpl(field);
        target.eSet(field.getEStructuralFeature("name"), "Price"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject bound = new DynamicEObjectImpl(eventHandler);
        bound.eSet(eventHandler.getEStructuralFeature("name"), "PriceOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        ((List<EObject>)target.eGet(field.getEStructuralFeature("handlers"))).add(bound); //$NON-NLS-1$
        EObject container = new DynamicEObjectImpl(actionContainer);
        EObject command = new DynamicEObjectImpl(commandHandler);
        command.eSet(commandHandler.getEStructuralFeature("name"), "PriceRun"); //$NON-NLS-1$ //$NON-NLS-2$
        container.eSet(handlerRef, command);
        target.eSet(action, container);

        List<Map<String, Object>> out = new ArrayList<>();
        DeleteMetadataTool.collectDescendantsForTest(target, out);

        List<Object> names = new ArrayList<>();
        for (Map<String, Object> entry : out)
        {
            names.add(entry.get("name")); //$NON-NLS-1$
        }
        assertTrue("the bound event handler must be in the delete radius: " + names, //$NON-NLS-1$
            names.contains("PriceOnChange")); //$NON-NLS-1$
        assertTrue("the command action's handler must be in the delete radius: " + names, //$NON-NLS-1$
            names.contains("PriceRun")); //$NON-NLS-1$
        assertEquals("the unnamed action container is descended through, not reported: " + names, //$NON-NLS-1$
            2, out.size());
    }

    @Test
    public void testThePromptBreakdownIsReadOffTheDescendantsItCounts()
    {
        // The prompt spelled out the kinds the old walk followed ("nested items, attribute columns")
        // while the event handler it now finds went unmentioned - a fixed phrase can only describe the
        // walk it was written for. The wording is grouped from the entries themselves, so it cannot
        // name a category the walk does not produce, nor omit one it does (issue #295 review).
        DeleteMetadataTool.FormDeletePreview data = new DeleteMetadataTool.FormDeletePreview();
        data.found = true;
        data.type = "FormField"; //$NON-NLS-1$
        data.descendants.add(descendant("Menu", "FormGroup")); //$NON-NLS-1$ //$NON-NLS-2$
        data.descendants.add(descendant("PriceOnChange", "EventHandler")); //$NON-NLS-1$ //$NON-NLS-2$

        String[] seenSubtitle = {null};
        new DeleteMetadataTool((name, preview) -> {
            seenSubtitle[0] = preview.getSubtitle();
            return DestructiveConsentGate.ConsentDecision.REJECT;
        }).gateFormMemberDelete("Catalog.Products.Form.ItemForm.Field.Price", //$NON-NLS-1$
            columnRef(), false, data, new RecordingWrite());

        assertTrue("the prompt must name what it actually found: " + seenSubtitle[0], //$NON-NLS-1$
            seenSubtitle[0].contains("1 FormGroup") && seenSubtitle[0].contains("1 EventHandler")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("...and must not recite categories it never walked: " + seenSubtitle[0], //$NON-NLS-1$
            seenSubtitle[0].contains("attribute columns")); //$NON-NLS-1$
        assertEquals("nothing contained describes as nothing", "", //$NON-NLS-1$ //$NON-NLS-2$
            new DeleteMetadataTool.FormDeletePreview().describeDescendants());
    }

    private static Map<String, Object> descendant(String name, String type)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name); //$NON-NLS-1$
        entry.put("type", type); //$NON-NLS-1$
        return entry;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testTheContentSummaryListsWhatItCounts()
    {
        // One summary feeds both phases: the prompt's counters and the confirm=false preview's item
        // list. The prompt told the caller to run the preview for details while the preview answered
        // with the BasicForm alone (issue #295 review), so the elements are asserted here and the
        // preview's USE of them by the e2e (a wire output).
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/contentsummary"); //$NON-NLS-1$
        // A separate 'name' attribute per EClass: an EStructuralFeature belongs to ONE class, so
        // sharing the instance re-parents it and the first class silently loses the feature.
        EClass formItem = factory.createEClass();
        formItem.setName("FormItem"); //$NON-NLS-1$
        formItem.getEStructuralFeatures().add(nameAttribute(factory));
        EClass columnClass = factory.createEClass();
        columnClass.setName("FormAttributeColumn"); //$NON-NLS-1$
        columnClass.getEStructuralFeatures().add(nameAttribute(factory));
        EClass commandClass = factory.createEClass();
        commandClass.setName("FormCommand"); //$NON-NLS-1$
        commandClass.getEStructuralFeatures().add(nameAttribute(factory));
        EClass attributeClass = factory.createEClass();
        attributeClass.setName("FormAttribute"); //$NON-NLS-1$
        attributeClass.getEStructuralFeatures().add(nameAttribute(factory));
        attributeClass.getEStructuralFeatures().add(manyContainment(factory, "columns", columnClass)); //$NON-NLS-1$
        // The named NON-FormItem containments a whole-form delete also takes: the form's own event
        // handlers, an element's event handlers, and a command's action (an unnamed container holding
        // a named CommandHandler). None of them is a FormItem, so a walk that counted the items tree
        // plus three named features could not see them (issue #295 review).
        EClass eventHandler = factory.createEClass();
        eventHandler.setName("EventHandler"); //$NON-NLS-1$
        eventHandler.getEStructuralFeatures().add(nameAttribute(factory));
        EClass commandHandler = factory.createEClass();
        commandHandler.setName("CommandHandler"); //$NON-NLS-1$
        commandHandler.getEStructuralFeatures().add(nameAttribute(factory));
        EClass actionContainer = factory.createEClass();
        actionContainer.setName("FormCommandHandlerContainer"); //$NON-NLS-1$
        EReference actionHandler = factory.createEReference();
        actionHandler.setName("handler"); //$NON-NLS-1$
        actionHandler.setEType(commandHandler);
        actionHandler.setContainment(true);
        actionContainer.getEStructuralFeatures().add(actionHandler);
        EReference commandAction = factory.createEReference();
        commandAction.setName("action"); //$NON-NLS-1$
        commandAction.setEType(actionContainer);
        commandAction.setContainment(true);
        commandClass.getEStructuralFeatures().add(commandAction);
        formItem.getEStructuralFeatures().add(manyContainment(factory, "handlers", eventHandler)); //$NON-NLS-1$

        EClass form = factory.createEClass();
        form.setName("Form"); //$NON-NLS-1$
        form.getEStructuralFeatures().add(manyContainment(factory, "items", formItem)); //$NON-NLS-1$
        form.getEStructuralFeatures().add(manyContainment(factory, "attributes", attributeClass)); //$NON-NLS-1$
        form.getEStructuralFeatures().add(
            manyContainment(factory, "formCommands", commandClass)); //$NON-NLS-1$
        form.getEStructuralFeatures().add(manyContainment(factory, "handlers", eventHandler)); //$NON-NLS-1$
        pkg.getEClassifiers().add(formItem);
        pkg.getEClassifiers().add(columnClass);
        pkg.getEClassifiers().add(commandClass);
        pkg.getEClassifiers().add(attributeClass);
        pkg.getEClassifiers().add(eventHandler);
        pkg.getEClassifiers().add(commandHandler);
        pkg.getEClassifiers().add(actionContainer);
        pkg.getEClassifiers().add(form);

        EObject model = new DynamicEObjectImpl(form);
        EObject field = new DynamicEObjectImpl(formItem);
        field.eSet(formItem.getEStructuralFeature("name"), "PriceField"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject itemHandler = new DynamicEObjectImpl(eventHandler);
        itemHandler.eSet(eventHandler.getEStructuralFeature("name"), "PriceOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        ((List<EObject>)field.eGet(formItem.getEStructuralFeature("handlers"))).add(itemHandler); //$NON-NLS-1$
        ((List<EObject>)model.eGet(form.getEStructuralFeature("items"))).add(field); //$NON-NLS-1$
        EObject attribute = new DynamicEObjectImpl(attributeClass);
        attribute.eSet(attributeClass.getEStructuralFeature("name"), "Rows"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject column = new DynamicEObjectImpl(columnClass);
        column.eSet(columnClass.getEStructuralFeature("name"), "Price"); //$NON-NLS-1$ //$NON-NLS-2$
        ((List<EObject>)attribute.eGet(attributeClass.getEStructuralFeature("columns"))).add(column); //$NON-NLS-1$
        ((List<EObject>)model.eGet(form.getEStructuralFeature("attributes"))).add(attribute); //$NON-NLS-1$
        EObject command = new DynamicEObjectImpl(commandClass);
        command.eSet(commandClass.getEStructuralFeature("name"), "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject action = new DynamicEObjectImpl(actionContainer);
        EObject actionProc = new DynamicEObjectImpl(commandHandler);
        actionProc.eSet(commandHandler.getEStructuralFeature("name"), "PostRun"); //$NON-NLS-1$ //$NON-NLS-2$
        action.eSet(actionHandler, actionProc);
        command.eSet(commandAction, action);
        ((List<EObject>)model.eGet(form.getEStructuralFeature("formCommands"))).add(command); //$NON-NLS-1$
        EObject formHandler = new DynamicEObjectImpl(eventHandler);
        formHandler.eSet(eventHandler.getEStructuralFeature("name"), "OnCreateAtServer"); //$NON-NLS-1$ //$NON-NLS-2$
        ((List<EObject>)model.eGet(form.getEStructuralFeature("handlers"))).add(formHandler); //$NON-NLS-1$

        DeleteMetadataTool.FormContentSummary summary =
            DeleteMetadataTool.summarizeFormContentForTest(model);

        // EVERY member the prompt counts must be listed, with its type - dropping any of them (or the
        // 'type' field) has to fail here, or the preview could stop promising what it promises.
        List<Object> names = new ArrayList<>();
        for (java.util.Map<String, Object> entry : summary.elements)
        {
            names.add(entry.get("name")); //$NON-NLS-1$
            assertNotNull("every listed element needs its type: " + entry, //$NON-NLS-1$
                entry.get("type")); //$NON-NLS-1$
        }
        assertTrue("the item must be listed: " + names, names.contains("PriceField")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the attribute must be listed: " + names, names.contains("Rows")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the COLUMN must be listed: " + names, names.contains("Price")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the COMMAND must be listed: " + names, names.contains("Post")); //$NON-NLS-1$ //$NON-NLS-2$
        // The three the feature-named walk could not reach.
        assertTrue("the FORM-level handler must be listed: " + names, //$NON-NLS-1$
            names.contains("OnCreateAtServer")); //$NON-NLS-1$
        assertTrue("the ITEM's handler must be listed: " + names, //$NON-NLS-1$
            names.contains("PriceOnChange")); //$NON-NLS-1$
        assertTrue("the command's ACTION handler must be listed: " + names, //$NON-NLS-1$
            names.contains("PostRun")); //$NON-NLS-1$
        assertEquals("the unnamed action container is walked through, not listed: " + names, //$NON-NLS-1$
            7, summary.total());
        assertTrue("the breakdown is derived from what was found: " + summary.describe(), //$NON-NLS-1$
            summary.describe().contains("EventHandler") //$NON-NLS-1$
                && summary.describe().contains("CommandHandler")); //$NON-NLS-1$
    }

    /** A fresh {@code name} string attribute (one instance per owning EClass - see above). */
    private static EAttribute nameAttribute(EcoreFactory factory)
    {
        EAttribute attribute = factory.createEAttribute();
        attribute.setName("name"); //$NON-NLS-1$
        attribute.setEType(EcorePackage.Literals.ESTRING);
        return attribute;
    }

    /** A many-valued containment reference named {@code name} holding {@code type}. */
    private static EReference manyContainment(EcoreFactory factory, String name, EClass type)
    {
        EReference reference = factory.createEReference();
        reference.setName(name);
        reference.setEType(type);
        reference.setContainment(true);
        reference.setUpperBound(-1);
        return reference;
    }

    @Test
    public void testTheFormPromptCountsTheContentItRemoves()
    {
        // A form delete takes the whole content Form.form with it, so a constant "1" asked the user to
        // authorize a single element while items, attributes, columns and commands went too - the
        // understatement issue #331's acceptance criteria call out. The MEMBER branch has counted
        // honestly since the review; this is its twin.
        DeleteMetadataTool.FormContentSummary content = new DeleteMetadataTool.FormContentSummary();
        content.elements.add(descendant("PriceField", "FormField")); //$NON-NLS-1$ //$NON-NLS-2$
        content.elements.add(descendant("QtyField", "FormField")); //$NON-NLS-1$ //$NON-NLS-2$
        content.elements.add(descendant("Rows", "FormAttribute")); //$NON-NLS-1$ //$NON-NLS-2$
        content.elements.add(descendant("Price", "FormAttributeColumn")); //$NON-NLS-1$ //$NON-NLS-2$
        content.elements.add(descendant("OnCreateAtServer", "EventHandler")); //$NON-NLS-1$ //$NON-NLS-2$

        int[] seenCount = {0};
        String[] seenSubtitle = {null};
        new DeleteMetadataTool((name, preview) -> {
            seenCount[0] = preview.getTotalCount();
            seenSubtitle[0] = preview.getSubtitle();
            return DestructiveConsentGate.ConsentDecision.REJECT;
        }).gateFormObjectDelete("Catalog.Products.Form.ItemForm", content, new RecordingWrite()); //$NON-NLS-1$

        assertEquals("the prompt counts the form plus everything its content holds", 6, seenCount[0]); //$NON-NLS-1$
        // The breakdown is grouped from the entries, so it names the handler it really found instead
        // of reciting the four categories the old feature walk knew (issue #295 review).
        assertTrue("the prompt must say what is inside: " + seenSubtitle[0], //$NON-NLS-1$
            seenSubtitle[0].contains("2 FormField") && seenSubtitle[0].contains("1 FormAttribute") //$NON-NLS-1$ //$NON-NLS-2$
                && seenSubtitle[0].contains("1 FormAttributeColumn") //$NON-NLS-1$
                && seenSubtitle[0].contains("1 EventHandler")); //$NON-NLS-1$
    }

    @Test
    public void testAnEmptyFormPromptStaysReadable()
    {
        // A form whose content is empty (or could not be read) must not grow an empty parenthesis.
        String[] seenSubtitle = {null};
        int[] seenCount = {0};
        new DeleteMetadataTool((name, preview) -> {
            seenSubtitle[0] = preview.getSubtitle();
            seenCount[0] = preview.getTotalCount();
            return DestructiveConsentGate.ConsentDecision.REJECT;
        }).gateFormObjectDelete("Catalog.Products.Form.ItemForm", //$NON-NLS-1$
            new DeleteMetadataTool.FormContentSummary(), new RecordingWrite());

        assertEquals(1, seenCount[0]);
        assertFalse("an empty content must not render an empty '()'", seenSubtitle[0].contains("()")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheConsentPromptCountsTheContainedElements()
    {
        // The prompt must describe the real blast radius, so a column-bearing attribute is not
        // presented as a single-element delete.
        int[] seen = {0};
        new DeleteMetadataTool((name, preview) -> {
            seen[0] = preview.getTotalCount();
            return DestructiveConsentGate.ConsentDecision.REJECT;
        }).gateFormMemberDelete("Catalog.Products.Form.ItemForm.Attribute.Rows", //$NON-NLS-1$
            columnRef(), false, previewWithDescendants(3), new RecordingWrite());
        assertEquals("the prompt counts the member plus its contained elements", 4, seen[0]); //$NON-NLS-1$
    }
}
