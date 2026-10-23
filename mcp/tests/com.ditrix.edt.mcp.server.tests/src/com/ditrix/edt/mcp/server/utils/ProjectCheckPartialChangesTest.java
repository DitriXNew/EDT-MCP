/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ProjectCheckSettingsSupport.Plan;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;

/** Simulates edits made in Preferences while the consent dialog is open. */
public class ProjectCheckPartialChangesTest
{
    private final CheckUid uid = new CheckUid("begin-transaction", "example.checks");
    private ICheckRepository repo;
    private IProject project;

    @Before
    public void setUp()
    {
        repo = mock(ICheckRepository.class);
        project = mock(IProject.class);
        when(repo.getActiveSettingsProfile(project)).thenReturn("Default");
        doReturn(Map.of(uid, mock(ICheckDescription.class))).when(repo).getChecksWithDescriptions();
    }

    @Test
    public void enabledOnlyRefusesSeverityChangedDuringConsent()
    {
        Plan plan = plan("enabled", "false", true, IssueSeverity.MAJOR);
        ICheckSettings fresh = settings(true, IssueSeverity.CRITICAL);
        doReturn(fresh).when(repo).getSettings(uid, project);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ProjectCheckSettingsSupport.applyAll(repo, project, plan));
        assertTrue(error.getMessage().contains("changed since the preview"));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        verify(fresh, never()).setSeverity(any(IssueSeverity.class));
    }

    @Test
    public void severityOnlyRefusesEnabledChangedDuringConsent()
    {
        Plan plan = plan("severity", "\"MINOR\"", true, IssueSeverity.MAJOR);
        doReturn(settings(false, IssueSeverity.MAJOR)).when(repo).getSettings(uid, project);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ProjectCheckSettingsSupport.applyAll(repo, project, plan));
        assertTrue(error.getMessage().contains("changed since the preview"));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void persistedRequiresEveryPlannedValueToMatchTheReadBack()
    {
        Plan enabledOnly = plan("enabled", "false", true, IssueSeverity.MAJOR);
        doReturn(settings(true, IssueSeverity.MAJOR), settings(false, IssueSeverity.MAJOR))
            .when(repo).getSettings(uid, project);
        assertEquals(true, ProjectCheckSettingsSupport.applyAll(repo, project, enabledOnly).get("persisted"));
        doReturn(settings(true, IssueSeverity.MAJOR)).when(repo).getSettings(uid, project);
        assertThrows(IllegalArgumentException.class,
            () -> ProjectCheckSettingsSupport.applyAll(repo, project, enabledOnly));
        Plan severityOnly = plan("severity", "\"MINOR\"", true, IssueSeverity.MAJOR);
        doReturn(settings(true, IssueSeverity.MAJOR), settings(true, IssueSeverity.MINOR))
            .when(repo).getSettings(uid, project);
        assertEquals(true, ProjectCheckSettingsSupport.applyAll(repo, project, severityOnly).get("persisted"));
        doReturn(settings(true, IssueSeverity.MAJOR)).when(repo).getSettings(uid, project);
        assertThrows(IllegalArgumentException.class,
            () -> ProjectCheckSettingsSupport.applyAll(repo, project, severityOnly));
    }

    private Plan plan(String field, String value, boolean enabled, IssueSeverity severity)
    {
        doReturn(settings(enabled, severity)).when(repo).getSettings(uid, project);
        return ProjectCheckSettingsSupport.prepare(repo, project, "Default",
            "[{\"checkId\":\"begin-transaction|example.checks\",\"" + field + "\":" + value + "}]");
    }

    private static ICheckSettings settings(boolean enabled, IssueSeverity severity)
    {
        ICheckSettings settings = mock(ICheckSettings.class);
        when(settings.isEnabled()).thenReturn(enabled);
        when(settings.getSeverity()).thenReturn(severity);
        return settings;
    }

}
