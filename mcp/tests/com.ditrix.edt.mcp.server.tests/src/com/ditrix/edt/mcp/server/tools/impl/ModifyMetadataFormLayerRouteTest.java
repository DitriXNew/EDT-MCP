/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataOwnedFormObjectTest.path;
import static com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataOwnedFormObjectTest.prop;
import static com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataOwnedFormObjectTest.props;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataOwnedFormObjectTest.Fixture;
import com.google.gson.JsonObject;

/**
 * Issue #995: the route of a call on an owned-form address - the form's metadata object, its content root,
 * or a refusal of a batch that mixes the two - and the text that names the metadata object to a caller
 * whose all-content batch misses the content root.
 */
public class ModifyMetadataFormLayerRouteTest
{
    private static final String FQN = "Catalog.Goods.Form.ItemForm"; //$NON-NLS-1$

    private static ModifyMetadataTool.FormRoute route(Fixture f, List<JsonObject> properties, boolean payload)
    {
        return ModifyMetadataTool.routeOwnedForm(f.scope, path(FQN), FQN, properties, payload);
    }

    @Test
    public void testAMetadataPropertyRoutesToTheFormInTheOwnersForms()
    {
        Fixture f = new Fixture();
        ModifyMetadataTool.FormRoute route = route(f, props("synonym", "comment"), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(route.node);
        assertSame(f.itemForm, route.node.object);
        assertSame(f.catalog, route.node.owner);
        assertSame(MdClassPackage.Literals.CATALOG__FORMS, route.node.feature);
        assertFalse(route.contentRoot);
        assertNull(route.refusal);
    }

    @Test
    public void testAnAllContentBatchKeepsTheContentRoot()
    {
        ModifyMetadataTool.FormRoute route = route(new Fixture(), props("title", "autoTitle"), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(route.node);
        assertTrue(route.contentRoot);
        assertNull(route.refusal);
    }

    @Test
    public void testAPayloadKeepsTheContentRootPathAndItsRefusal()
    {
        // The form-root branch refuses a role / content / template payload; a metadata property beside
        // it must not move the call to a path with another text.
        ModifyMetadataTool.FormRoute route = route(new Fixture(), props("synonym"), true); //$NON-NLS-1$
        assertTrue(route.contentRoot);
        assertNull(route.refusal);
    }

    @Test
    public void testAMissingFormHasNoNode()
    {
        Fixture f = new Fixture();
        ModifyMetadataTool.FormRoute route = ModifyMetadataTool.routeOwnedForm(f.scope,
            path("Catalog.Goods.Form.Nope"), "Catalog.Goods.Form.Nope", props("synonym"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(route.node);
    }

    @Test
    public void testAMixedBatchIsRefusedNamingBothGroups()
    {
        ModifyMetadataTool.FormRoute route = route(new Fixture(), props("synonym", "usePurposes", "title"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("a mixed batch is refused, never half written", route.refusal); //$NON-NLS-1$
        String text = route.refusal;
        assertTrue(text, text.contains(FQN));
        assertTrue(text, text.contains("Properties of the metadata object: synonym, usePurposes;")); //$NON-NLS-1$
        assertTrue(text, text.contains("not properties of it: title.")); //$NON-NLS-1$
        assertTrue(text, text.contains("separate call on the same FQN")); //$NON-NLS-1$
        assertTrue(text, text.contains("assignable:true")); //$NON-NLS-1$
        assertTrue(text, text.contains("Nothing was changed.")); //$NON-NLS-1$
    }

    @Test
    public void testAMixedRefusalNamesTheFormByItsCanonicalAddressAndCoversMisspeltNames()
    {
        Fixture f = new Fixture();
        String fqn = com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.normalizeFqn(
            ModifyMetadataOwnedFormObjectTest.RU_CATALOG + "." + ModifyMetadataOwnedFormObjectTest.RU_GOODS + "." //$NON-NLS-1$ //$NON-NLS-2$
                + ModifyMetadataOwnedFormObjectTest.RU_FORM + "." + ModifyMetadataOwnedFormObjectTest.RU_ITEM_FORM); //$NON-NLS-1$
        String text = ModifyMetadataTool.routeOwnedForm(f.scope, path(fqn), fqn, props("comment", "commment"), //$NON-NLS-1$ //$NON-NLS-2$
            false).refusal;
        assertNotNull(text);
        assertTrue(text, text.contains("'Catalog." + ModifyMetadataOwnedFormObjectTest.RU_GOODS + ".Form." //$NON-NLS-1$ //$NON-NLS-2$
            + ModifyMetadataOwnedFormObjectTest.RU_ITEM_FORM + "'")); //$NON-NLS-1$
        assertTrue(text, text.contains("check the spelling of a name that is neither")); //$NON-NLS-1$
    }

    @Test
    public void testAnOwnedFormsFormTypeIsRefusedBeforeAnyWrite()
    {
        ModifyMetadataTool.FormRoute route = route(new Fixture(), props("synonym", "formType"), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(route.contentRoot);
        assertNotNull("formType alone would not convert Form.form", route.refusal); //$NON-NLS-1$
        assertTrue(route.refusal, route.refusal.contains("formType of an existing form is not changed")); //$NON-NLS-1$
        assertTrue(route.refusal, route.refusal.contains("Nothing was changed.")); //$NON-NLS-1$
        assertNull(ModifyMetadataTool.formTypeRefusal(FQN, props("synonym", "usePurposes"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAMixedCommonFormBatchIsRefusedToo()
    {
        Fixture f = new Fixture();
        String text = ModifyMetadataTool.mixedFormLayersRefusal("CommonForm.Shared", f.common, //$NON-NLS-1$
            props("autoTitle", "comment")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(text);
        assertTrue(text, text.contains("Properties of the metadata object: comment;")); //$NON-NLS-1$
        assertTrue(text, text.contains("not properties of it: autoTitle.")); //$NON-NLS-1$
    }

    @Test
    public void testOnlyAFormBatchThatReallyMixesIsRefused()
    {
        Fixture f = new Fixture();
        assertNull(ModifyMetadataTool.mixedFormLayersRefusal(FQN, f.itemForm, props("synonym", "comment"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ModifyMetadataTool.mixedFormLayersRefusal(FQN, f.itemForm, props("title"))); //$NON-NLS-1$
        assertNull("an object that is no form is never refused here", //$NON-NLS-1$
            ModifyMetadataTool.mixedFormLayersRefusal("Catalog.Goods", f.catalog, props("comment", "title"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject unnamed = new JsonObject();
        unnamed.addProperty("value", "x"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("an entry without a name is left to the ordinary validation", //$NON-NLS-1$
            ModifyMetadataTool.mixedFormLayersRefusal(FQN, f.itemForm, Arrays.asList(prop("synonym", "x"), unnamed))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheContentRootRefusalOfAnOwnedFormNamesItsMetadataObject()
    {
        String hint = ModifyMetadataTool.OWNED_FORM_MDCLASS_DISCOVERY_HINT;
        assertTrue(hint, hint.contains("form's own metadata properties")); //$NON-NLS-1$
        assertTrue(hint, hint.contains("synonym")); //$NON-NLS-1$
        assertTrue(hint, hint.contains("assignable:true on the same FQN")); //$NON-NLS-1$
    }
}
