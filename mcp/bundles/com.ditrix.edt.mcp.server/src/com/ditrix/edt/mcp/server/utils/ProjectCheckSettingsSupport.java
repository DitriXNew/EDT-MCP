/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
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

    // Equal project handles share a monitor for the server lifetime, including queued callers.
    private static final Map<IProject, Object> PROFILE_MUTATION_LOCKS = new ConcurrentHashMap<>();

    private static Object profileMutationLock(IProject project)
    {
        return PROFILE_MUTATION_LOCKS.computeIfAbsent(project, key -> new Object());
    }

    private static boolean sameProfile(String left, String right)
    {
        return left != null && right != null && Normalizer.normalize(left, Normalizer.Form.NFC)
            .equalsIgnoreCase(Normalizer.normalize(right, Normalizer.Form.NFC));
    }

    private static String listedProfile(Collection<String> available, String requested)
    {
        return available.stream().filter(name -> sameProfile(name, requested)).findFirst().orElse(null);
    }

    private static String requirePlannedActiveProfile(ICheckRepository repo, IProject project, String planned)
    {
        String active = repo.getActiveSettingsProfile(project);
        if (!sameProfile(planned, active))
        {
            throw new IllegalArgumentException("The active profile changed from '" + planned + "' to '"
                + active + "'; re-run the preview with project_checks before applying changes.");
        }
        return active;
    }

    private ProjectCheckSettingsSupport()
    {
    }

    /**
     * The MCP checkId|contributorId identifier, deliberately different from CheckUid.toString().
     * EDT stores bare checkId keys in .cset files, or contributorId|checkId on a collision.
     */
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
        synchronized (profileMutationLock(project))
        {
            List<ICheckSettings> settings = new ArrayList<>();
            for (int i = 0; i < plan.changes().size(); i++)
            {
                Change change = plan.changes().get(i);
                ICheckSettings copy = repo.getSettings(change.uid(), project);
                State current = new State(change.uid(), copy.isEnabled(), copy.getSeverity(),
                    repo.getChecksWithDescriptions().get(change.uid()).isCriticalDataIntegrityCheck());
                if (!plan.before().get(i).equals(current))
                {
                    throw new IllegalArgumentException("Check settings in profile '" + plan.profile()
                        + "' changed since the preview; re-run the preview with project_checks before applying changes.");
                }
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
            String writeProfile = requirePlannedActiveProfile(repo, project, plan.profile());
            Path plannedParent = validateProfileTarget(project, writeProfile, !sameProfile("Default", writeProfile));
            repo.applyChanges(settings, project);
            List<State> after = plan.changes().stream().map(change -> snapshot(repo, project, change.uid())).toList();
            String active = repo.getActiveSettingsProfile(project);
            if (!sameProfile(plan.profile(), active) || !plan.requested().equals(after))
            {
                throw new IllegalArgumentException("EDT's write could not be verified: planned profile '"
                    + plan.profile() + "', actually active profile '" + active + "'; the change may have landed in '"
                    + active + "'. " + (!sameProfile(plan.profile(), active)
                        ? "The active profile changed during the write. "
                        : "The read-back check values differ from the planned values. ")
                    + "Restore values from the preview in profile '" + plan.profile() + "': "
                    + GsonProvider.toJson(plan.beforeRows()) + ". Inspect project_checks list/profiles "
                    + "before restoring or retrying.");
            }
            verifyProfileTargetAfterWrite(project, writeProfile, plannedParent, true);
            if (!writeProfile.equals(active))
            {
                verifyProfileTargetAfterWrite(project, active, plannedParent, true);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("before", plan.beforeRows());
            result.put("after", after.stream().map(State::row).toList());
            result.put("changed", plan.changed());
            result.put("persisted", true);
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
            .map(name -> Map.<String, Object>of("name", name, "active", sameProfile(name, active),
                "customizedChecks", repo.getCustomizedSettingsFromProfile(name, project).size())).toList();
        return Map.of("activeProfile", active, "profiles", profiles);
    }

    /** The public repository has no create method: start from Default unless a source is supplied. */
    public static String validateProfileCopy(ICheckRepository repo, IProject project, String profile,
        String from, boolean requireFrom)
    {
        if (profile == null || profile.isBlank())
        {
            throw new IllegalArgumentException("profile is required for createProfile/copyProfile; "
                + "use project_checks profiles to inspect existing names.");
        }
        // The validator returns a boolean. These are EDT's English validation dialog messages
        // (ValidationPreferencePage_Invalid_settings_profile_name / Non_unique_settings_profile_name).
        if (!repo.validateProfileName(profile))
        {
            throw new IllegalArgumentException("EDT: The profile name " + profile
                + " is invalid. The profile name should not contain spaces, $ or %");
        }
        validateProfileFile(project, profile);
        requireAbsentProfileTarget(project, profile);
        if (sameProfile("Default", profile)
            || listedProfile(repo.getAvailableSettingsProfiles(project), profile) != null)
        {
            throw new IllegalArgumentException("EDT: The profile with the name " + profile
                + " already exists. Please enter another name");
        }
        String source = from == null && !requireFrom ? "Default" : from;
        if (source == null || source.isBlank())
        {
            throw new IllegalArgumentException("from is required for copyProfile; "
                + "use project_checks profiles and choose an existing source.");
        }
        validateProfileFile(project, source);
        if (requireFrom || from != null)
        {
            String listed = listedProfile(repo.getAvailableSettingsProfiles(project), source);
            if (listed == null)
            {
                throw new IllegalArgumentException("Unknown from profile '" + source
                    + "'; use project_checks profiles and choose an existing source.");
            }
            return listed;
        }
        return source;
    }

    /** A profile is one file stem directly in .settings, never a path. */
    private static void validateProfileFile(IProject project, String profile)
    {
        if (profile == null || profile.isBlank())
        {
            throw new IllegalArgumentException("A non-empty profile name is required; inspect project_checks profiles.");
        }
        IFolder settingsFolder = project.getFolder(".settings");
        if (profile.indexOf('/') >= 0 || profile.indexOf('\\') >= 0 || profile.indexOf(':') >= 0
            || !settingsFolder.getFile(profile + ".cset").getParent().equals(settingsFolder))
        {
            throw new IllegalArgumentException("Invalid profile name '" + profile
                + "': a profile name is a file stem in .settings, not a path; '/', '\\' and ':' are not allowed.");
        }
        if (!project.getWorkspace().validateName(profile + ".cset", IResource.FILE).isOK())
        {
            throw new IllegalArgumentException("Invalid profile name '" + profile
                + "': EDT workspace requires a valid .cset file name in .settings.");
        }
    }

    /** Resolve physical paths immediately before calling EDT, including dangling links. */
    private static Path validateProfileTarget(IProject project, String profile)
    {
        return validateProfileTarget(project, profile, false);
    }

    private static Path validateProfileTarget(IProject project, String profile, boolean requireFile)
    {
        validateProfileFile(project, profile);
        IFolder settings = project.getFolder(".settings");
        var folderLocation = settings.getLocation();
        if (folderLocation == null)
        {
            throw unsafeProfileTarget(profile);
        }
        Path parent = validateProfileTarget(folderLocation.toFile().toPath(), profileTargetPath(project, profile), profile);
        if (requireFile && !profileTargetExists(project, profile))
        {
            throw new IllegalArgumentException("Profile '" + profile + "' .cset is missing from .settings; "
                + "refresh project_checks profiles and re-run the preview before writing.");
        }
        return parent;
    }

    private static Path profileTargetPath(IProject project, String profile)
    {
        var location = project.getFolder(".settings").getFile(profile + ".cset").getLocation();
        if (location == null)
        {
            throw unsafeProfileTarget(profile);
        }
        return location.toFile().toPath();
    }

    private static boolean profileTargetExists(IProject project, String profile)
    {
        return Files.exists(profileTargetPath(project, profile), LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireAbsentProfileTarget(IProject project, String profile)
    {
        if (profileTargetExists(project, profile))
        {
            throw new IllegalArgumentException("Cannot create profile '" + profile + "': a file named "
                + profile + ".cset already exists in .settings; choose another name or remove the file, "
                + "then re-run the preview.");
        }
    }

    static Path validateProfileTarget(Path settings, Path target, String profile)
    {
        try
        {
            Path realSettings = settings.toRealPath();
            if (Files.isSymbolicLink(settings) || !Files.isDirectory(settings, LinkOption.NOFOLLOW_LINKS)
                || !realSettings.equals(settings.getParent().toRealPath().resolve(settings.getFileName())))
            {
                throw unsafeProfileTarget(profile);
            }
            // isSymbolicLink always examines the link itself (NOFOLLOW_LINKS), even if dangling.
            if (Files.isSymbolicLink(target))
            {
                throw unsafeProfileTarget(profile);
            }
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            Path realParent = exists ? target.toRealPath().getParent() : target.getParent().toRealPath();
            if (!realSettings.equals(realParent)
                || (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)))
            {
                throw unsafeProfileTarget(profile);
            }
            return realSettings;
        }
        catch (IOException | SecurityException e)
        {
            throw unsafeProfileTarget(profile);
        }
    }

    private static void verifyProfileTargetAfterWrite(IProject project, String profile, Path plannedParent,
        boolean requireFile)
    {
        try
        {
            if (!plannedParent.equals(validateProfileTarget(project, profile)))
            {
                throw unsafeProfileTarget(profile);
            }
        }
        catch (IllegalArgumentException e)
        {
            // Reuse the same physical-path checks, but report that EDT has already been called.
            throw new IllegalArgumentException("Profile '" + profile + "' target was replaced during the write; "
                + "EDT may have written through the replacement. Inspect .settings and restore the local profile "
                + "target before retrying.");
        }
        if (requireFile && !profileTargetExists(project, profile))
        {
            throw new IllegalArgumentException("Profile '" + profile + "' .cset is missing after the write; "
                + "EDT's persistence could not be verified. Inspect .settings and project_checks profiles before retrying.");
        }
    }

    private static IllegalArgumentException unsafeProfileTarget(String profile)
    {
        return new IllegalArgumentException("Cannot safely access profile '" + profile
            + "': links and junctions in .settings are refused; the .cset must have the real .settings "
            + "directory as its parent. Remove the link or restore the local .settings directory, "
            + "then re-run the preview.");
    }

    /** Refuse unavailable, active and last inactive profiles before destructive consent. */
    public static String validateProfileDelete(ICheckRepository repo, IProject project, String profile)
    {
        if (profile == null || profile.isBlank())
        {
            throw new IllegalArgumentException("profile is required for deleteProfile; use project_checks profiles.");
        }
        validateProfileFile(project, profile);
        Collection<String> available = repo.getAvailableSettingsProfiles(project);
        String listed = listedProfile(available, profile);
        if (listed == null)
        {
            throw new IllegalArgumentException("Unknown profile '" + profile
                + "'; use project_checks profiles and choose an existing name.");
        }
        if (sameProfile(profile, repo.getActiveSettingsProfile(project)))
        {
            throw new IllegalArgumentException("Cannot delete active profile '" + profile
                + "'; switch to another profile with project_checks switch first.");
        }
        if (available.size() <= 1)
        {
            throw lastInactiveProfileRefusal(profile);
        }
        return listed;
    }

    private static IllegalStateException lastInactiveProfileRefusal(String profile)
    {
        return Refusals.state("Cannot delete profile '" + profile
            + "': EDT requires more than one listed profile to delete an inactive profile. "
            + "To remove the last saved profile, switch to it and delete it in EDT's validation preferences; "
            + "EDT restores Default when no profiles remain.");
    }

    public static Map<String, Object> copyProfile(ICheckRepository repo, IProject project, String profile,
        String source, boolean implicitDefault, List<ProfileSetting> preview, String plannedActive)
    {
        synchronized (profileMutationLock(project))
        {
            // Consent can take time; EDT's duplicate call overwrites an existing target, so revalidate.
            String listedSource = validateProfileCopy(repo, project, profile,
                implicitDefault ? null : source, !implicitDefault);
            if (!preview.equals(profileFingerprint(repo, project, listedSource)))
            {
                throw new IllegalArgumentException("The source profile changed since the preview; re-run the "
                    + "preview with project_checks before copying '" + listedSource + "'.");
            }
            requirePlannedActiveProfile(repo, project, plannedActive);
            Path plannedParent = validateProfileTarget(project, profile);
            requireAbsentProfileTarget(project, profile);
            repo.duplicateSettingsProfile(listedSource, profile, project);
            verifyProfileTargetAfterWrite(project, profile, plannedParent, true);
            List<ProfileSetting> copied = profileFingerprint(repo, project, profile);
            if (!preview.equals(copied)
                || listedProfile(repo.getAvailableSettingsProfiles(project), profile) == null)
            {
                throw new IllegalArgumentException("EDT's copy could not be verified: target profile '" + profile
                    + "' is missing or its read-back settings differ from source profile '" + listedSource
                    + "' in the preview. Inspect project_checks list/profiles before retrying.");
            }
            String active = repo.getActiveSettingsProfile(project);
            verifyUnchangedActiveProfile(plannedActive, active, "copying profile '" + profile + "'");
            Map<String, Object> result = new LinkedHashMap<>(profiles(repo, project, active));
            result.put("profile", profile);
            result.put("from", listedSource);
            result.put("changed", true);
            result.put("persisted", true);
            return result;
        }
    }

    /** Immutable, order-independent snapshot of every customized value that copying or deletion affects. */
    public record ProfileSetting(String checkId, String contributorId, boolean enabled, IssueSeverity severity,
        List<ProfileParameter> parameters)
    {
    }

    public record ProfileParameter(String name, String value)
    {
    }

    public static List<ProfileSetting> profileFingerprint(ICheckRepository repo, IProject project, String profile)
    {
        return repo.getCustomizedSettingsFromProfile(profile, project).entrySet().stream()
            .map(entry -> new ProfileSetting(entry.getKey().getCheckId(), entry.getKey().getContributorId(),
                entry.getValue().isEnabled(), entry.getValue().getSeverity(),
                entry.getValue().getParameters().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(parameter -> new ProfileParameter(parameter.getKey(), parameter.getValue().getValue()))
                    .toList()))
            .sorted(Comparator.comparing(ProfileSetting::checkId).thenComparing(ProfileSetting::contributorId))
            .toList();
    }

    public static Map<String, Object> deleteProfile(ICheckRepository repo, IProject project, String profile,
        String plannedActive, List<ProfileSetting> preview)
    {
        synchronized (profileMutationLock(project))
        {
            requirePlannedActiveProfile(repo, project, plannedActive);
            String listed = validateProfileDelete(repo, project, profile);
            List<ProfileSetting> current = profileFingerprint(repo, project, listed);
            if (!preview.equals(current))
            {
                throw new IllegalArgumentException("Profile '" + listed
                    + "' changed since the preview; re-run the preview with project_checks before deleting it.");
            }
            int customized = current.size();
            try
            {
                requirePlannedActiveProfile(repo, project, plannedActive);
                Path plannedParent = validateProfileTarget(project, listed);
                repo.deleteSettingsProfile(listed, project);
                if (profileTargetExists(project, listed))
                {
                    throw new IllegalArgumentException("Profile '" + listed + "' was recreated during the write; "
                        + "its .cset still exists in .settings. Inspect the file before retrying deletion.");
                }
                verifyProfileTargetAfterWrite(project, listed, plannedParent, false);
            }
            catch (IllegalStateException e)
            {
                // EDT rechecks under its lock; UI edits can still invalidate our snapshot.
                if ("The last profile cannot be inactive".equals(e.getMessage()))
                {
                    throw lastInactiveProfileRefusal(listed);
                }
                throw e;
            }
            String active = repo.getActiveSettingsProfile(project);
            if (sameProfile(listed, active))
            {
                throw new IllegalArgumentException("Deleted profile '" + listed + "' became active during the write; "
                    + "actually active profile '" + active + "'. Inspect project_checks profiles and switch to an "
                    + "existing profile before retrying.");
            }
            verifyUnchangedActiveProfile(plannedActive, active, "deleting profile '" + listed + "'");
            Map<String, Object> result = new LinkedHashMap<>(profiles(repo, project, active));
            result.put("profile", listed);
            result.put("deletedProfile", listed);
            result.put("deletedCustomizedChecks", customized);
            result.put("changed", true);
            if (listedProfile(repo.getAvailableSettingsProfiles(project), listed) != null)
            {
                throw new IllegalArgumentException("EDT's deletion could not be verified: profile '" + listed
                    + "' is still listed after the write. Inspect project_checks profiles before retrying.");
            }
            result.put("persisted", true);
            return result;
        }
    }

    private static void verifyUnchangedActiveProfile(String planned, String active, String operation)
    {
        if (!sameProfile(planned, active))
        {
            throw new IllegalArgumentException("EDT's write could not be verified after " + operation
                + ": planned active profile '" + planned + "', actually active profile '" + active
                + "'; the active profile changed during the write. Inspect project_checks list/profiles before retrying.");
        }
    }

    /** Default is implicit; every other switch target must already be available. */
    private static String switchTarget(ICheckRepository repo, IProject project, String profile)
    {
        if (profile == null || profile.isBlank())
        {
            throw new IllegalArgumentException("profile is required for switch; use project_checks profiles.");
        }
        validateProfileFile(project, profile);
        String listed = listedProfile(repo.getAvailableSettingsProfiles(project), profile);
        if (listed != null)
        {
            return listed;
        }
        if (sameProfile("Default", profile))
        {
            return "Default";
        }
        throw new IllegalArgumentException("Unknown profile '" + profile
            + "'; use project_checks profiles and choose an existing name.");
    }

    public static boolean validateSwitch(ICheckRepository repo, IProject project, String active, String profile)
    {
        return !sameProfile(switchTarget(repo, project, profile), active);
    }

    public record SwitchPlan(String active, String target, List<ProfileSetting> fingerprint)
    {
        public boolean changed()
        {
            return !sameProfile(active, target);
        }
    }

    public static SwitchPlan prepareSwitch(ICheckRepository repo, IProject project, String active, String profile)
    {
        String target = switchTarget(repo, project, profile);
        return new SwitchPlan(active, target, profileFingerprint(repo, project, target));
    }

    public static Map<String, Object> switchProfile(ICheckRepository repo, IProject project,
        String active, String profile)
    {
        return switchProfile(repo, project, prepareSwitch(repo, project, active, profile));
    }

    public static Map<String, Object> switchProfile(ICheckRepository repo, IProject project, SwitchPlan plan)
    {
        synchronized (profileMutationLock(project))
        {
            // Revalidate at the write boundary as well as before consent.
            String target = switchTarget(repo, project, plan.target());
            if (!plan.changed())
            {
                return Map.of("changed", false);
            }
            if (!plan.fingerprint().equals(profileFingerprint(repo, project, target)))
            {
                throw new IllegalArgumentException("The target profile changed since the preview; re-run the "
                    + "preview with project_checks before switching to '" + target + "'.");
            }
            requirePlannedActiveProfile(repo, project, plan.active());
            Path plannedParent = validateProfileTarget(project, target, !sameProfile("Default", target));
            // With an empty overlay EDT does not save a missing implicit Default.cset.
            boolean requireFile = profileTargetExists(project, target);
            repo.setActiveSettingsProfile(target, Collections.emptyList(), project);
            String after = repo.getActiveSettingsProfile(project);
            if (!sameProfile(target, after))
            {
                throw new IllegalArgumentException("EDT's write could not be verified: planned profile '" + target
                    + "', actually active profile '" + after + "'. Inspect project_checks list/profiles "
                    + "before restoring or retrying.");
            }
            verifyProfileTargetAfterWrite(project, target, plannedParent, requireFile);
            if (!plan.fingerprint().equals(profileFingerprint(repo, project, target))
                || (!sameProfile("Default", target)
                    && listedProfile(repo.getAvailableSettingsProfiles(project), target) == null))
            {
                throw new IllegalArgumentException("EDT's switch could not be verified: target profile '" + target
                    + "' is no longer listed or its read-back settings differ from the preview. "
                    + "Inspect project_checks list/profiles before retrying.");
            }
            return Map.of("before", Map.of("activeProfile", plan.active()), "after", Map.of("activeProfile", after),
                "changed", true, "persisted", true, "markersRefresh", MARKERS_REFRESH);
        }
    }
}
