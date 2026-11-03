/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.qfix.IFixManager;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Drives each sibling's actual refusal branch with a resolved project and mocked EDT services. */
public class SiblingContextReasonTest
{
    private static final String CONFIGURATION_NATURE = "com._1c.g5.v8.dt.core.V8ConfigurationNature";
    private Field plugin;
    private Object previousPlugin;
    private Activator activator;
    private IProject project;
    private IProjectDescription description;
    private ProjectContext context;

    @Before
    public void setUp() throws Exception
    {
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        activator = mock(Activator.class);
        plugin.set(null, activator);
        project = mock(IProject.class);
        description = mock(IProjectDescription.class);
        when(project.getName()).thenReturn("Demo");
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getDescription()).thenReturn(description);
        when(description.getNatureIds()).thenReturn(new String[] { CONFIGURATION_NATURE });
        when(project.hasNature(CONFIGURATION_NATURE)).thenReturn(true);
        when(activator.getDtProjectManager()).thenReturn(mock(IDtProjectManager.class));
        when(activator.getBmModelManager()).thenReturn(mock(IBmModelManager.class));
        when(activator.getCheckScheduler()).thenReturn(mock(ICheckScheduler.class));
        when(activator.getMarkerManager()).thenReturn(mock(IMarkerManager.class));
        when(activator.getFixManager()).thenReturn(mock(IFixManager.class));
        when(activator.getCheckRepository()).thenReturn(mock(ICheckRepository.class));
        Constructor<ProjectContext> constructor =
            ProjectContext.class.getDeclaredConstructor(String.class, IProject.class);
        constructor.setAccessible(true);
        context = constructor.newInstance("Demo", project);
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void getTranslationProjectInfoWiresTheSharedContextReason() throws Exception
    {
        assertContextReason(new GetTranslationProjectInfoTool().execute(projectParams(), ignored -> context),
            "Not an EDT project: Demo.");
        makePlain();
        assertEquals("Not an EDT project: Demo",
            error(new GetTranslationProjectInfoTool().execute(projectParams(), ignored -> context)));
    }

    @Test
    public void translateConfigurationWiresTheSharedContextReason() throws Exception
    {
        assertContextReason(new TranslateConfigurationTool().execute(translationParams(), ignored -> context),
            "Not an EDT project: Demo.");
        makePlain();
        assertEquals("Not an EDT project: Demo",
            error(new TranslateConfigurationTool().execute(translationParams(), ignored -> context)));
    }

    @Test
    public void revalidateObjectsWiresTheSharedContextReason() throws Exception
    {
        assertContextReason(revalidationError(), "Not an EDT project: Demo.");
        makePlain();
        assertEquals("Not an EDT project: Demo", error(revalidationError()));
    }

    @Test
    public void generateTranslationStringsWiresTheReasonWithoutContradictoryRetryAdvice()
    {
        String json = new GenerateTranslationStringsTool().execute(translationParams(), ignored -> context);
        assertContextReason(json, "EDT has not yet resolved an IDtProject for: Demo.");
        assertFalse(error(json), error(json).toLowerCase().contains("please retry"));
        assertFalse(error(json), error(json).contains("may still be indexing"));
    }

    @Test
    public void generateTranslationStringsKeepsItsOriginalTextWithoutAContextReason()
    {
        when(activator.getDtProjectManager()).thenReturn(null);
        assertEquals("EDT has not yet resolved an IDtProject for: Demo. "
            + "The project may still be indexing \u2014 please retry.",
            error(new GenerateTranslationStringsTool().execute(translationParams(), ignored -> context)));
    }

    @Test
    public void generateTranslationStringsKeepsItsPlainProjectRefusal() throws Exception
    {
        makePlain();
        when(project.hasNature(CONFIGURATION_NATURE)).thenReturn(false);
        assertEquals("Not a V8 configuration project: Demo. This action must be run on the configuration "
            + "project (V8ConfigurationNature), not on a dictionary storage project or extension.",
            error(new GenerateTranslationStringsTool().execute(translationParams(), ignored -> context)));
    }

    @Test
    public void applyQuickFixWiresTheSharedContextReason() throws Exception
    {
        QuickFixProbe tool = new QuickFixProbe(project);
        String prefix = "Project 'Demo' is not an EDT project (no IDtProject); "
            + "quick-fixes apply to configuration/extension projects.";
        assertContextReason(tool.run(), prefix);
        makePlain();
        assertEquals(prefix, error(tool.run()));
    }

    private void makePlain() throws Exception
    {
        when(description.getNatureIds()).thenReturn(new String[0]);
    }

    private String revalidationError() throws Exception
    {
        Method resolver = RevalidateObjectsTool.class.getDeclaredMethod("resolveServices", IProject.class,
            String.class);
        resolver.setAccessible(true);
        Object services = resolver.invoke(null, project, "Demo");
        Field failure = services.getClass().getDeclaredField("error");
        failure.setAccessible(true);
        return (String)failure.get(services);
    }

    private static Map<String, String> projectParams()
    {
        return Map.of("projectName", "Demo");
    }

    private static Map<String, String> translationParams()
    {
        return Map.of("projectName", "Demo", "targetLanguages", "[\"en\"]");
    }

    private static String error(String json)
    {
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(json, result.get("success").getAsBoolean());
        return result.get("error").getAsString();
    }

    private static void assertContextReason(String json, String prefix)
    {
        String message = error(json);
        assertTrue(message, message.startsWith(prefix + " EDT"));
        assertTrue(message, message.toLowerCase().contains("context"));
        assertTrue(message, message.contains("close and reopen") || message.contains("poll list_projects"));
    }

    /** Uses the inherited resolver seam so the quick-fix refusal itself runs unchanged, without UI work. */
    private static final class QuickFixProbe extends ApplyQuickFixTool
    {
        private final ProjectContext resolved;

        QuickFixProbe(IProject project) throws Exception
        {
            Constructor<ProjectContext> constructor = ProjectContext.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            resolved = constructor.newInstance();
            resolved.project = project;
        }

        @Override
        protected ProjectContext resolveProjectAndConfig(String projectName)
        {
            return resolved;
        }

        String run()
        {
            return executeOnUiThread(Map.of("projectName", "Demo", "checkId", "test-check"));
        }
    }
}
