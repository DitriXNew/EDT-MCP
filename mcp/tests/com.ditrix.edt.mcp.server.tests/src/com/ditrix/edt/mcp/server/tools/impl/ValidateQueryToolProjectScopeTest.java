/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.QueryValidationService;
import com.google.gson.JsonParser;

/** The tool renders the shared verdict without replacing its completeness contract. */
public class ValidateQueryToolProjectScopeTest
{
    @Test
    public void toolPreservesSharedPartialVerdictAndDcsMode() throws Exception
    {
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        try
        {
            plugin.set(null, mock(Activator.class));
            IProject project = mock(IProject.class);
            when(project.getName()).thenReturn("MyExt");
            var headless = new QueryValidationService()
            {
                @Override
                protected void dispatch(Runnable work) { work.run(); }
            };
            var unavailable = headless.validate(project, "SELECT 1", true);
            QueryValidationService service = mock(QueryValidationService.class);
            when(service.validate(project, "SELECT 1", true)).thenReturn(unavailable);

            String json = new ValidateQueryTool(service).doValidateQuery(project, "SELECT 1", true);

            assertEquals(unavailable.toJson(), json);
            var result = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("PARTIAL", result.get("validationState").getAsString());
            assertFalse(result.get("validationComplete").getAsBoolean());
            assertFalse(result.get("valid").getAsBoolean());
            assertTrue(result.get("dcsMode").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("MyExt"));
            verify(service).validate(project, "SELECT 1", true);
        }
        finally
        {
            plugin.set(null, previous);
        }
    }
}
