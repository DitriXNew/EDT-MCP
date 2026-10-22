/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;

import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckParameterSettings;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.INamedElement;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Project check settings operations, independent of service lookup and consent UI. */
public final class ProjectCheckSettingsSupport
{
    public static final int DEFAULT_LIST_LIMIT = 50;
    public static final int MAX_LIST_LIMIT = 100;

    public static final String MARKERS_REFRESH = "EDT removed/retagged markers of the changed checks and "
        + "re-runs enabled ones in the background; poll get_project_errors";

    private ProjectCheckSettingsSupport()
    {
    }

    /** The .cset key, deliberately different from CheckUid.toString(). */
    public static String key(CheckUid uid)
    {
        return uid.getCheckId() + "|" + uid.getContributorId();
    }

    /** Resolve a registered pipe key, symbolic id, or project-local short UID, in that order. */
    public static CheckUid resolveUid(ICheckRepository repo, IProject project, String id)
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("checkId is required; use project_checks list to find check ids.");
        }
        Map<CheckUid, ICheckDescription> descriptions = repo.getChecksWithDescriptions();
        int pipe = id.indexOf('|');
        if (pipe >= 0)
        {
            if (pipe > 0 && pipe < id.length() - 1 && id.indexOf('|', pipe + 1) < 0)
            {
                CheckUid uid = new CheckUid(id.substring(0, pipe), id.substring(pipe + 1));
                if (descriptions.containsKey(uid))
                {
                    return uid;
                }
            }
            throw unknownCheck(id);
        }
        Set<CheckUid> candidates = repo.getCheckUidForCheckId(id, project);
        if (candidates.size() > 1)
        {
            String choices = candidates.stream().map(ProjectCheckSettingsSupport::key).sorted()
                .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Ambiguous checkId '" + id + "'; choose a checkId|contributorId: "
                + choices + ". Use project_checks list to inspect them.");
        }
        if (candidates.size() == 1)
        {
            return candidates.iterator().next();
        }
        CheckUid shortUid = repo.getUidForShortUid(id, project);
        if (shortUid != null && descriptions.containsKey(shortUid))
        {
            return shortUid;
        }
        throw unknownCheck(id);
    }

    private static IllegalArgumentException unknownCheck(String id)
    {
        return new IllegalArgumentException("Unknown checkId '" + id
            + "'; use project_checks list to find a symbolic id, checkId|contributorId or shortUid.");
    }

    public record Change(CheckUid uid, Boolean enabled, IssueSeverity severity)
    {
    }

    /** Immutable values, never a repository-owned mutable settings object. */
    public record State(CheckUid uid, boolean enabled, IssueSeverity severity, boolean criticalDataIntegrity)
    {
        public Map<String, Object> row()
        {
            return Map.of("uid", key(uid), "checkId", uid.getCheckId(), "enabled", enabled,
                "severity", severity.name(), "criticalDataIntegrity", criticalDataIntegrity);
        }
    }

    /** Counts describe effective transitions, rather than just the fields present in the input. */
    public record Plan(String profile, List<Change> changes, List<State> before, List<State> requested,
        int changed, int disabled, int enabled, int severityChanges)
    {
        public List<Map<String, Object>> beforeRows()
        {
            return before.stream().map(State::row).toList();
        }

        public List<String> checkIds()
        {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < changes.size(); i++)
            {
                if (!before.get(i).equals(requested.get(i)))
                {
                    ids.add(key(changes.get(i).uid()));
                }
            }
            return List.copyOf(ids);
        }
    }

    /** Validate the whole batch before even obtaining settings copies. */
    public static List<Change> parseChanges(ICheckRepository repo, IProject project, String json)
    {
        JsonElement parsed;
        try
        {
            parsed = JsonParser.parseString(json == null ? "" : json);
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("changes must be a non-empty JSON array of "
                + "{checkId, enabled?, severity?}; see get_tool_guide('project_checks').");
        }
        if (!parsed.isJsonArray() || parsed.getAsJsonArray().isEmpty())
        {
            throw new IllegalArgumentException("changes must be a non-empty JSON array of "
                + "{checkId, enabled?, severity?}; see get_tool_guide('project_checks').");
        }
        List<Change> changes = new ArrayList<>();
        Set<CheckUid> seen = new HashSet<>();
        for (JsonElement entry : parsed.getAsJsonArray())
        {
            if (!entry.isJsonObject())
            {
                throw new IllegalArgumentException("Each changes entry must be an object with checkId and "
                    + "at least one of enabled/severity; see get_tool_guide('project_checks').");
            }
            JsonObject object = entry.getAsJsonObject();
            String id = stringValue(object.get("checkId"), "checkId");
            if (!object.has("enabled") && !object.has("severity"))
            {
                throw new IllegalArgumentException("Check '" + id + "' needs at least one of enabled/severity.");
            }
            Boolean enabled = null;
            if (object.has("enabled"))
            {
                JsonElement value = object.get("enabled");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                {
                    throw new IllegalArgumentException("enabled for '" + id + "' must be a JSON boolean.");
                }
                enabled = value.getAsBoolean();
            }
            IssueSeverity severity = null;
            if (object.has("severity"))
            {
                JsonElement raw = object.get("severity");
                String value = raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()
                    ? raw.getAsString() : raw.toString();
                try
                {
                    severity = IssueSeverity.valueOf(value.toUpperCase(Locale.ROOT));
                }
                catch (IllegalArgumentException e)
                {
                    throw new IllegalArgumentException("Unknown severity '" + value
                        + "'; use BLOCKER, CRITICAL, MAJOR, MINOR or TRIVIAL.");
                }
            }
            CheckUid uid = resolveUid(repo, project, id);
            if (!seen.add(uid))
            {
                throw new IllegalArgumentException("Duplicate check '" + key(uid)
                    + "' in changes; combine its enabled/severity in one entry.");
            }
            changes.add(new Change(uid, enabled, severity));
        }
        return List.copyOf(changes);
    }

    private static String stringValue(JsonElement value, String name)
    {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank())
        {
            throw new IllegalArgumentException(name + " must be a non-empty string; "
                + "see get_tool_guide('project_checks').");
        }
        return value.getAsString();
    }

    public static Plan prepare(ICheckRepository repo, IProject project, String profile, String json)
    {
        List<Change> changes = parseChanges(repo, project, json);
        List<State> before = new ArrayList<>();
        List<State> requested = new ArrayList<>();
        int changed = 0;
        int disabled = 0;
        int enabled = 0;
        int severities = 0;
        for (Change change : changes)
        {
            State old = snapshot(repo, project, change.uid());
            State next = new State(change.uid(), change.enabled() == null ? old.enabled() : change.enabled(),
                change.severity() == null ? old.severity() : change.severity(), old.criticalDataIntegrity());
            before.add(old);
            requested.add(next);
            if (!old.equals(next))
            {
                changed++;
            }
            if (old.enabled() != next.enabled())
            {
                if (next.enabled())
                {
                    enabled++;
                }
                else
                {
                    disabled++;
                }
            }
            if (old.severity() != next.severity())
            {
                severities++;
            }
        }
        return new Plan(profile, changes, List.copyOf(before), List.copyOf(requested), changed,
            disabled, enabled, severities);
    }

    private static State snapshot(ICheckRepository repo, IProject project, CheckUid uid)
    {
        ICheckSettings settings = repo.getSettings(uid, project);
        return new State(uid, settings.isEnabled(), settings.getSeverity(),
            repo.getChecksWithDescriptions().get(uid).isCriticalDataIntegrityCheck());
    }

    /** Fresh settings copies, one persistence/listener call, then an independent read-back. */
    public static Map<String, Object> applyAll(ICheckRepository repo, IProject project, Plan plan)
    {
        List<ICheckSettings> settings = new ArrayList<>();
        for (Change change : plan.changes())
        {
            ICheckSettings copy = repo.getSettings(change.uid(), project);
            if (change.enabled() != null)
            {
                copy.setEnabled(change.enabled());
            }
            if (change.severity() != null)
            {
                copy.setSeverity(change.severity());
            }
            settings.add(copy);
        }
        repo.applyChanges(settings, project);
        List<State> after = plan.changes().stream().map(change -> snapshot(repo, project, change.uid())).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("before", plan.beforeRows());
        result.put("after", after.stream().map(State::row).toList());
        result.put("changed", plan.changed());
        boolean persisted = true;
        for (int i = 0; i < plan.changes().size(); i++)
        {
            Change change = plan.changes().get(i);
            State actual = after.get(i);
            persisted &= (change.enabled() == null || change.enabled() == actual.enabled())
                && (change.severity() == null || change.severity() == actual.severity());
        }
        result.put("persisted", persisted);
        result.put("markersRefresh", MARKERS_REFRESH);
        List<String> criticalDisabled = after.stream()
            .filter(state -> state.criticalDataIntegrity() && !state.enabled())
            .map(state -> key(state.uid())).toList();
        if (!criticalDisabled.isEmpty())
        {
            result.put("criticalDataIntegrityWarning", "EDT will now load the configuration without these "
                + "critical data integrity checks: " + String.join(", ", criticalDisabled)
                + ". Restore their before values when finished.");
        }
        return result;
    }

    public static Map<String, Object> list(ICheckRepository repo, IProject project, String profile,
        List<String> checkIds, boolean onlyCustomized, int limit, int offset)
    {
        Set<CheckUid> selected = null;
        if (checkIds != null)
        {
            selected = checkIds.stream().map(id -> resolveUid(repo, project, id)).collect(Collectors.toSet());
        }
        Set<CheckUid> customized = onlyCustomized
            ? repo.getCustomizedSettingsFromProfile(profile, project).keySet() : null;
        List<ICheckDescription> descriptions = new ArrayList<>();
        collect(repo, project, repo.getRootElements(project), repo.getChecksWithDescriptions(),
            descriptions, new HashSet<>());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ICheckDescription description : descriptions)
        {
            CheckUid uid = description.getId();
            if ((selected != null && !selected.contains(uid)) || (customized != null && !customized.contains(uid)))
            {
                continue;
            }
            ICheckSettings settings = repo.getSettings(uid, project);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("checkId", uid.getCheckId());
            row.put("contributorId", uid.getContributorId());
            row.put("uid", key(uid));
            row.put("shortUid", repo.getShortUid(uid, project));
            row.put("title", description.getTitle());
            // EDT returns ancestors nearest-first and excludes the check itself.
            List<INamedElement> path = new ArrayList<>(repo.getPath(description));
            Collections.reverse(path);
            row.put("category", path.stream()
                .map(INamedElement::getTitle).collect(Collectors.joining(" / ")));
            row.put("type", description.getType().name());
            row.put("enabled", settings.isEnabled());
            row.put("severity", settings.getSeverity().name());
            row.put("defaultEnabled", description.isEnabled());
            row.put("defaultSeverity", description.getSeverity().name());
            row.put("customized", !settings.isDefault());
            row.put("criticalDataIntegrity", description.isCriticalDataIntegrityCheck());
            row.put("hasDocumentation", CheckDescriptionLoader.has(uid.getCheckId()));
            if (checkIds != null)
            {
                List<Map<String, Object>> parameters = new ArrayList<>();
                settings.getParameters().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    ICheckParameterSettings parameter = entry.getValue();
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("name", parameter.getName());
                    value.put("value", parameter.getValue());
                    value.put("isDefault", parameter.isDefault());
                    value.put("type", parameter.getType().getName());
                    parameters.add(value);
                });
                row.put("parameters", parameters);
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> row) -> (String)row.get("category"))
            .thenComparing(row -> (String)row.get("checkId")).thenComparing(row -> (String)row.get("uid")));
        int start = Math.min(Math.max(0, offset), rows.size());
        int count = Math.min(Pagination.clampLimit(limit, MAX_LIST_LIMIT), rows.size() - start);
        return Map.of("project", project.getName(), "activeProfile", profile, "total", rows.size(),
            "returned", count, "offset", Math.max(0, offset), "checks", rows.subList(start, start + count));
    }

    private static void collect(ICheckRepository repo, IProject project, Collection<INamedElement> elements,
        Map<CheckUid, ICheckDescription> registered, List<ICheckDescription> descriptions, Set<CheckUid> visited)
    {
        for (INamedElement element : elements)
        {
            if (!visited.add(element.getId()))
            {
                continue;
            }
            // CheckRepository.loadIfCheckDescription substitutes ICheckSettings in the tree.
            // Resolve by id so both the public description form and EDT's effective form work.
            ICheckDescription description = registered.get(element.getId());
            if (description != null)
            {
                descriptions.add(description);
            }
            collect(repo, project, repo.getChildren(element, project), registered, descriptions, visited);
        }
    }

    public static Map<String, Object> profiles(ICheckRepository repo, IProject project, String active)
    {
        List<Map<String, Object>> profiles = repo.getAvailableSettingsProfiles(project).stream().sorted()
            .map(name -> Map.<String, Object>of("name", name, "active", name.equals(active),
                "customizedChecks", repo.getCustomizedSettingsFromProfile(name, project).size())).toList();
        return Map.of("activeProfile", active, "profiles", profiles);
    }

    /** Availability is mandatory: the platform otherwise creates an empty profile on switch. */
    public static boolean validateSwitch(ICheckRepository repo, IProject project, String active, String profile)
    {
        if (profile == null || profile.isBlank())
        {
            throw new IllegalArgumentException("profile is required for switch; use project_checks profiles.");
        }
        if (!repo.getAvailableSettingsProfiles(project).contains(profile))
        {
            throw new IllegalArgumentException("Unknown profile '" + profile
                + "'; use project_checks profiles and choose an existing name.");
        }
        return !profile.equals(active);
    }

    public static Map<String, Object> switchProfile(ICheckRepository repo, IProject project,
        String active, String profile)
    {
        // Revalidate at the write boundary as well as before consent.
        if (!validateSwitch(repo, project, active, profile))
        {
            return Map.of("changed", false);
        }
        repo.setActiveSettingsProfile(profile, Collections.emptyList(), project);
        String after = repo.getActiveSettingsProfile(project);
        return Map.of("before", Map.of("activeProfile", active), "after", Map.of("activeProfile", after),
            "changed", true, "persisted", profile.equals(after), "markersRefresh", MARKERS_REFRESH);
    }
}
