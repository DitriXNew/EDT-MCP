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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormObjectRef;

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
}
