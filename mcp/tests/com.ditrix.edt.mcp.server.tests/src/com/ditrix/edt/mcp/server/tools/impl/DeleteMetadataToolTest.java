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

import org.junit.Test;

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
