/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
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
        doReturn(Map.of(uid, mock(ICheckDescription.class))).when(repo).getChecksWithDescriptions();
    }

    @Test
    public void enabledOnlyPreservesSeverityChangedDuringConsent()
    {
        Plan plan = plan("enabled", "false", true, IssueSeverity.MAJOR);
        ICheckSettings fresh = settings(true, IssueSeverity.CRITICAL);
        doReturn(fresh, settings(false, IssueSeverity.CRITICAL)).when(repo).getSettings(uid, project);
        Map<String, Object> result = ProjectCheckSettingsSupport.applyAll(repo, project, plan);
        verify(fresh).setEnabled(false);
        verify(fresh, never()).setSeverity(any(IssueSeverity.class));
        assertEquals(true, result.get("persisted"));
        assertEquals("CRITICAL", rows(result, "after").get(0).get("severity"));
        assertEquals("MAJOR", rows(result, "before").get(0).get("severity"));
    }

    @Test
    public void severityOnlyPreservesEnabledChangedDuringConsent()
    {
        Plan plan = plan("severity", "\"MINOR\"", true, IssueSeverity.MAJOR);
        ICheckSettings fresh = settings(false, IssueSeverity.MAJOR);
        doReturn(fresh, settings(false, IssueSeverity.MINOR)).when(repo).getSettings(uid, project);
        Map<String, Object> result = ProjectCheckSettingsSupport.applyAll(repo, project, plan);
        verify(fresh).setSeverity(IssueSeverity.MINOR);
        verify(fresh, never()).setEnabled(anyBoolean());
        assertEquals(true, result.get("persisted"));
        assertEquals(false, rows(result, "after").get(0).get("enabled"));
    }

    @Test
    public void persistedIgnoresOmittedFieldsButChecksEveryRequestedField()
    {
        Plan enabledOnly = plan("enabled", "false", true, IssueSeverity.MAJOR);
        doReturn(settings(true, IssueSeverity.CRITICAL), settings(false, IssueSeverity.CRITICAL))
            .when(repo).getSettings(uid, project);
        assertEquals(true, ProjectCheckSettingsSupport.applyAll(repo, project, enabledOnly).get("persisted"));
        doReturn(settings(true, IssueSeverity.CRITICAL)).when(repo).getSettings(uid, project);
        assertEquals(false, ProjectCheckSettingsSupport.applyAll(repo, project, enabledOnly).get("persisted"));
        Plan severityOnly = plan("severity", "\"MINOR\"", true, IssueSeverity.MAJOR);
        doReturn(settings(false, IssueSeverity.MAJOR), settings(false, IssueSeverity.MINOR))
            .when(repo).getSettings(uid, project);
        assertEquals(true, ProjectCheckSettingsSupport.applyAll(repo, project, severityOnly).get("persisted"));
        doReturn(settings(false, IssueSeverity.MAJOR)).when(repo).getSettings(uid, project);
        assertEquals(false, ProjectCheckSettingsSupport.applyAll(repo, project, severityOnly).get("persisted"));
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result, String name)
    {
        return (List<Map<String, Object>>)result.get(name);
    }
}
