/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The vendor-support wiring of {@link AbstractMetadataWriteTool} (issue #642): the refusal comes
 * from the tool's {@code writeIntent()}, as a ready {@code ToolResult} error, and a tool that does
 * not declare an intent is never checked. Which object is asked is {@code VendorSupportGuardTest}'s.
 */
public class AbstractMetadataWriteToolVendorSupportTest
{
    private IModelEditingSupport support;
    private Configuration config;
    private MetadataScope scope;
    private Catalog locked;

    @Before
    public void setUp()
    {
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
        config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$
        locked = MdClassFactory.eINSTANCE.createCatalog();
        locked.setName("Locked"); //$NON-NLS-1$
        config.getCatalogs().add(locked);
        scope = MetadataScope.ofConfiguration(config);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    @Test
    public void testALockedTargetIsARefusalInTheErrorShape()
    {
        when(support.canEdit(locked, EditingMode.DIRECT)).thenReturn(false);

        String json = new IntentTool(VendorSupportGuard.Intent.MODIFY).vendorSupportRefusal(scope, "Catalog.Locked"); //$NON-NLS-1$

        assertNotNull(json);
        JsonObject error = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(error.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json, error.get("error").getAsString().startsWith( //$NON-NLS-1$
            "'Catalog.Locked' cannot be changed: it is under vendor support")); //$NON-NLS-1$
        assertFalse("a refusal before any write carries no mutation marker", //$NON-NLS-1$
            error.has("mutationCommitted")); //$NON-NLS-1$
    }

    @Test
    public void testTheFqnIsNormalizedBeforeTheCheck()
    {
        when(support.canDelete(locked, EditingMode.DIRECT)).thenReturn(false);
        String ruCatalog = new String(new int[] {0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447,
            0x043d, 0x0438, 0x043a}, 0, 10);

        assertNotNull(new IntentTool(VendorSupportGuard.Intent.DELETE).vendorSupportRefusal(scope,
            ruCatalog + ".Locked")); //$NON-NLS-1$
        verify(support).canDelete(locked, EditingMode.DIRECT);
    }

    @Test
    public void testAToolWithoutAnIntentIsNeverChecked()
    {
        when(support.canEdit(locked, EditingMode.DIRECT)).thenReturn(false);

        assertNull(new IntentTool(VendorSupportGuard.Intent.NONE).vendorSupportRefusal(scope, "Catalog.Locked")); //$NON-NLS-1$
        assertNull(new DefaultIntentTool().vendorSupportRefusal(scope, "Catalog.Locked")); //$NON-NLS-1$
        verifyZeroInteractions(support);
    }

    @Test
    public void testTheDefaultIntentIsNone()
    {
        assertEquals(VendorSupportGuard.Intent.NONE, new DefaultIntentTool().writeIntent());
    }

    // ==================== wired into GETTING the context ====================

    @Test
    public void testTheRefusalIsPartOfResolvingTheContext()
    {
        when(support.canEdit(locked, EditingMode.DIRECT)).thenReturn(false);

        AbstractMetadataWriteTool.ProjectContext ctx = new ScopedTool(VendorSupportGuard.Intent.MODIFY)
            .resolveProjectAndScope("P", "Catalog.Locked"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(ctx.hasError());
        assertTrue(ctx.error, ctx.error.contains("'Catalog.Locked' cannot be changed")); //$NON-NLS-1$
    }

    @Test
    public void testTheAddressingHintIsAnsweredBeforeVendorSupport()
    {
        when(support.canEdit(any(), any())).thenReturn(false);

        AbstractMetadataWriteTool.ProjectContext ctx = new ScopedTool(VendorSupportGuard.Intent.CREATE)
            .resolveProjectAndScope("P", "ExternalDataProcessor.X"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(ctx.error, ctx.error.contains("EXTERNAL-OBJECTS type")); //$NON-NLS-1$
        verifyZeroInteractions(support);
    }

    @Test
    public void testAnEditableTargetLeavesTheContextUsable()
    {
        AbstractMetadataWriteTool.ProjectContext ctx = new ScopedTool(VendorSupportGuard.Intent.DELETE)
            .resolveProjectAndScope("P", "Catalog.Locked"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(ctx.hasError());
        verify(support).canDelete(locked, EditingMode.DIRECT);
    }

    // ==================== the real tools stop before writing ====================

    @Test
    public void testCreateRefusesALockedOwnerAndAddsNothing() throws Exception
    {
        when(support.canEdit(locked, EditingMode.DIRECT)).thenReturn(false);
        CreateMetadataTool tool = new CreateMetadataTool()
        {
            @Override
            protected AbstractMetadataWriteTool.ProjectContext resolveProjectAndScope(String projectName)
            {
                return context();
            }
        };

        String result = run(tool, params("Catalog.Locked.Attribute.New")); //$NON-NLS-1$

        assertRefusal(result, "'Catalog.Locked.Attribute.New' cannot be created: Catalog.Locked is under " //$NON-NLS-1$
            + "vendor support"); //$NON-NLS-1$
        assertTrue("nothing may be added to the locked catalog", locked.getAttributes().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testModifyRefusesALockedTargetAndChangesNothing() throws Exception
    {
        when(support.canEdit(locked, EditingMode.DIRECT)).thenReturn(false);
        ModifyMetadataTool tool = new ModifyMetadataTool()
        {
            @Override
            protected AbstractMetadataWriteTool.ProjectContext resolveProjectAndScope(String projectName)
            {
                return context();
            }
        };
        Map<String, String> params = params("Catalog.Locked"); //$NON-NLS-1$
        params.put("properties", "[{\"name\":\"comment\",\"value\":\"x\"}]"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = run(tool, params);

        assertRefusal(result, "'Catalog.Locked' cannot be changed: it is under vendor support"); //$NON-NLS-1$
        assertTrue("the locked catalog must keep its comment", //$NON-NLS-1$
            locked.getComment() == null || locked.getComment().isEmpty());
    }

    @Test
    public void testDeleteRefusesALockedTargetInPreviewAndUnderForce() throws Exception
    {
        when(support.canDelete(locked, EditingMode.DIRECT)).thenReturn(false);
        DeleteMetadataTool tool = new DeleteMetadataTool()
        {
            @Override
            protected AbstractMetadataWriteTool.ProjectContext resolveProjectAndScope(String projectName)
            {
                return context();
            }
        };
        Map<String, String> forced = params("Catalog.Locked"); //$NON-NLS-1$
        forced.put("confirm", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        forced.put("force", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        for (Map<String, String> params : List.of(params("Catalog.Locked"), forced)) //$NON-NLS-1$
        {
            assertRefusal(run(tool, params), "'Catalog.Locked' cannot be deleted: vendor support does not " //$NON-NLS-1$
                + "allow deleting it"); //$NON-NLS-1$
        }
        assertTrue("the locked catalog must still be registered", config.getCatalogs().contains(locked)); //$NON-NLS-1$
    }

    private AbstractMetadataWriteTool.ProjectContext context()
    {
        AbstractMetadataWriteTool.ProjectContext ctx = new AbstractMetadataWriteTool.ProjectContext();
        ctx.config = config;
        ctx.scope = scope;
        return ctx;
    }

    private static Map<String, String> params(String fqn)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fqn", fqn); //$NON-NLS-1$
        return params;
    }

    /** Runs a tool's body directly: the refusal must come before anything needs the workbench. */
    private static String run(AbstractMetadataWriteTool tool, Map<String, String> params) throws Exception
    {
        return tool.executeOnUiThread(params);
    }

    private static void assertRefusal(String json, String prefix)
    {
        JsonObject error = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(json, error.get("success").getAsBoolean()); //$NON-NLS-1$
        String message = error.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(message, message.startsWith(prefix));
        assertTrue(message, message.contains("Nothing was changed.")); //$NON-NLS-1$
    }

    /** A write tool with {@code intent} whose project resolves to the in-memory configuration. */
    private final class ScopedTool extends DefaultIntentTool
    {
        private final VendorSupportGuard.Intent intent;

        ScopedTool(VendorSupportGuard.Intent intent)
        {
            this.intent = intent;
        }

        @Override
        protected VendorSupportGuard.Intent writeIntent()
        {
            return intent;
        }

        @Override
        protected AbstractMetadataWriteTool.ProjectContext resolveProjectAndScope(String projectName)
        {
            return context();
        }
    }

    /** A write tool that declares {@code intent}. */
    private static final class IntentTool extends DefaultIntentTool
    {
        private final VendorSupportGuard.Intent intent;

        IntentTool(VendorSupportGuard.Intent intent)
        {
            this.intent = intent;
        }

        @Override
        protected VendorSupportGuard.Intent writeIntent()
        {
            return intent;
        }
    }

    /** A write tool that inherits the default intent. */
    private static class DefaultIntentTool extends AbstractMetadataWriteTool
    {
        @Override
        public String getName()
        {
            return "vendor_support_stub"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "stub"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{}"; //$NON-NLS-1$
        }

        @Override
        protected String executeOnUiThread(Map<String, String> params)
        {
            throw new UnsupportedOperationException();
        }
    }
}
