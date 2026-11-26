// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmLocalEditingContext;
import com._1c.g5.v8.bm.integration.IBmModel;
import com.ditrix.edt.mcp.server.HostKind;

public class RenameEditorGuardTest
{
    @Test
    public void namesEveryDirtyProjectIncludingExtension()
    {
        IProject base = project("Base");
        IProject extension = project("Extension");
        var models = Map.of(base, model(true), extension, model(true));
        var refusal = RenameEditorGuard.refusal(HostKind.WINDOWED, base, p -> List.of(extension, base), models::get);
        assertTrue(refusal, refusal.contains("'Base', 'Extension'"));
        assertTrue(refusal, refusal.contains("nothing was renamed or saved"));
    }

    @Test
    public void refusesWhenOnlyDependentProjectHasDirtyContext()
    {
        IProject base = project("Base");
        IProject extension = project("AdoptedExtension");
        var models = Map.of(base, model(false), extension, model(true));
        var refusal = RenameEditorGuard.refusal(HostKind.WINDOWED, base, p -> List.of(extension), models::get);
        assertTrue(refusal, refusal.contains("'AdoptedExtension'"));
    }

    @Test
    public void recheckIncludesBothPreparedAndCurrentDependencies()
    {
        var base = project("Base");
        var prepared = project("PreparedExtension");
        var current = project("CurrentExtension");
        var models = Map.of(base, model(false), prepared, model(true), current, model(true));
        String refusal = RenameEditorGuard.refusal(HostKind.WINDOWED, base, List.of(prepared),
            p -> List.of(current), models::get);
        assertTrue(refusal, refusal.contains("'CurrentExtension', 'PreparedExtension'"));
        var cleanModels = Map.of(base, model(false), prepared, model(false), current, model(false));
        assertNull(RenameEditorGuard.refusal(HostKind.WINDOWED, base, List.of(prepared),
            p -> List.of(current), cleanModels::get));
    }

    @Test
    public void cleanWindowedModelPasses()
    {
        IProject base = project("Base");
        assertNull(RenameEditorGuard.refusal(HostKind.WINDOWED, base, p -> List.of(), p -> model(false)));
    }

    @Test
    public void headlessNeverInspectsProjectsOrModels()
    {
        assertNull(RenameEditorGuard.refusal(HostKind.HEADLESS, project("Test"), p -> {
            throw new AssertionError("read dependencies");
        }, p -> {
            throw new AssertionError("read model");
        }));
    }

    @Test
    public void missingOrUnreadableModelHasRecoveryDirections()
    {
        IProject base = project("Base");
        var missing = RenameEditorGuard.refusal(HostKind.WINDOWED, base, p -> List.of(), p -> null);
        var unreadable = RenameEditorGuard.refusal(HostKind.WINDOWED, base, p -> List.of(), p -> {
            throw new IllegalStateException("closing");
        });
        assertTrue(missing, missing.contains("'Base'"));
        for (String refusal : List.of(missing, unreadable))
        {
            assertTrue(refusal, refusal.contains("finish loading") && refusal.contains("get_server_status"));
        }
    }

    private static IProject project(String name)
    {
        var project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static IBmModel model(boolean dirty)
    {
        var context = mock(IBmLocalEditingContext.class);
        when(context.isDirty()).thenReturn(dirty);
        var model = mock(IBmModel.class);
        when(model.getAllLocalContexts()).thenReturn(List.of(context));
        return model;
    }
}
