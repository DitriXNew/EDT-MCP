/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Restores EDT's clear-only resets and keeps differing non-null choices; concurrent manual clears are restored too.
 * Explicit setDefault wins for the target project's default. */
public final class InfobaseBindings
{
    private static final ReentrantLock OPERATIONS = new ReentrantLock();
    private final IApplicationManager manager;
    private final Function<IProject, String> defaultId;
    private final Map<IProject, String> defaults = new LinkedHashMap<>();
    private final Map<ILaunchConfiguration, LaunchBinding> launches = new LinkedHashMap<>();
    private final Map<ApplicationKey, UUID> registrations = new LinkedHashMap<>();
    private String captureFailure;
    private boolean workspace;
    private IProject[] projects;
    private ILaunchConfiguration[] configurations;

    private record LaunchBinding(String project, String applicationId) { }
    private record ApplicationKey(String project, String applicationId) { }

    /** Native creation jobs can outlive their caller, just like credential workers. */
    public static void mutate(IProgressMonitor monitor, BoundedJob.IBoundedWork operation) throws Exception
    {
        try (var coordination = InfobaseMutationCoordinator.acquire())
        {
            if (!monitor.isCanceled())
            {
                operation.run(monitor);
            }
        }
    }

    private InfobaseBindings(IApplicationManager manager, Function<IProject, String> defaultId)
    {
        this.manager = manager;
        this.defaultId = defaultId;
    }

    /** Reload affects ALL workspace projects, including projects other than the operation target. */
    public static InfobaseBindings capture(IApplicationManager manager)
    {
        var bindings = new InfobaseBindings(manager, ApplicationSupport::getStoredDefaultApplicationId);
        bindings.workspace = true;
        return bindings;
    }

    /** Service seam: tests use the same snapshot, verification and native restoration pipeline. */
    public static InfobaseBindings capture(IApplicationManager manager, IProject[] projects,
            ILaunchConfiguration[] configurations, Function<IProject, String> defaultId)
    {
        var bindings = new InfobaseBindings(manager, defaultId);
        bindings.projects = projects;
        bindings.configurations = configurations;
        return bindings;
    }

    private void snapshot()
    {
        try
        {
            if (workspace)
            {
                var debug = DebugPlugin.getDefault();
                if (debug == null || debug.getLaunchManager() == null)
                {
                    throw new IllegalStateException("Eclipse launch manager is unavailable"); //$NON-NLS-1$
                }
                projects = ProjectContext.allProjects();
                configurations = debug.getLaunchManager().getLaunchConfigurations();
            }
            for (IProject project : projects)
            {
                if (project.isOpen())
                {
                    defaults.put(project, defaultId.apply(project));
                }
            }
            for (ILaunchConfiguration configuration : configurations)
            {
                if (LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(configuration.getType().getIdentifier()))
                {
                    String project = configuration.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null);
                    String id = configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null);
                    if (id != null && configuration.isReadOnly())
                    {
                        throw new IllegalStateException("Launch configuration is read-only: " + configuration.getName()); //$NON-NLS-1$
                    }
                    launches.put(configuration, new LaunchBinding(project, id));
                }
            }
            // Resolve only after every binding was captured: application lookup can initialize EDT state.
            for (var entry : defaults.entrySet())
            {
                if (entry.getValue() != null)
                {
                    rememberApplication(entry.getKey(), entry.getValue());
                }
            }
            for (var before : launches.values())
            {
                if (before.applicationId() != null)
                {
                    for (IProject project : defaults.keySet())
                    {
                        if (Objects.equals(before.project(), project.getName()))
                        {
                            rememberApplication(project, before.applicationId());
                        }
                    }
                }
            }
        }
        catch (Exception failure)
        {
            captureFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage(); //$NON-NLS-1$
        }
    }

    /** A failed snapshot refuses BEFORE an SDK call whose side effects could not be recovered. */
    public String run(Supplier<String> operation, String projectName, boolean setDefault, boolean deleting)
    {
        boolean locked = false;
        try
        {
            locked = OPERATIONS.tryLock(5, TimeUnit.SECONDS);
            if (!locked)
            {
                return ToolResult.error("Another infobase operation is preserving application bindings; wait for it to finish and retry. Nothing was started.").toJson(); //$NON-NLS-1$
            }
            // A timed-out native credential worker can still reload the list. Do not snapshot mid-write.
            try (var coordination = InfobaseMutationCoordinator.acquire())
            {
                // The previous native writer has returned; never wait for a lookup under this lock.
            }
            if (captureFailure == null)
            {
                snapshot();
            }
            return runCaptured(operation, projectName, setDefault, deleting);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Waiting to preserve application bindings was interrupted; retry after EDT is idle. Nothing was started.").toJson(); //$NON-NLS-1$
        }
        catch (InfobaseMutationCoordinator.Unavailable busy)
        {
            return ToolResult.error(busy.getMessage() + " No infobase operation was started.").toJson(); //$NON-NLS-1$
        }
        finally
        {
            if (locked) { OPERATIONS.unlock(); }
        }
    }

    private String runCaptured(Supplier<String> operation, String projectName, boolean setDefault, boolean deleting)
    {
        if (captureFailure != null)
        {
            return ToolResult.error("Infobase operation was not started: EDT resets application defaults and launch bindings " //$NON-NLS-1$
                + "when saving its launcher list, and their snapshot/restore is unavailable (" + captureFailure //$NON-NLS-1$
                + "). Check project defaults and writable RuntimeClient configurations in EDT, then retry.") //$NON-NLS-1$
                .put("unrestoredBindings", List.of("Snapshot unavailable; no operation was started.")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        JsonObject result;
        try
        {
            result = decodeOperationResult(operation.get());
        }
        catch (Exception failure)
        {
            result = decodeOperationResult(ToolResult.error("Infobase operation failed (" + failure.getClass().getSimpleName() //$NON-NLS-1$
                + "); inspect get_applications before retrying.").toJson()); //$NON-NLS-1$
        }
        return finish(result, projectName, setDefault, deleting);
    }

    /** Also used after a bounded worker: an incomplete worker cannot prove preservation. */
    private String unverified(JsonObject json, String projectName, boolean setDefault, boolean deleting)
    {
        var pending = new ArrayList<Object>();
        String changedId = text(json, "applicationId"); //$NON-NLS-1$
        defaults.forEach((project, id) -> {
            String expected = isDeletionTarget(json, projectName, project.getName(), id, deleting) ? null
                : setDefault && json.get("success").getAsBoolean() //$NON-NLS-1$
                    && projectName.equals(project.getName()) && changedId != null ? changedId : id;
            pending.add(binding(project.getName(), "defaultApplicationId", expected)); //$NON-NLS-1$
        });
        launches.forEach((configuration, before) -> {
            String expected = isDeletionTarget(json, projectName, before.project(), before.applicationId(), deleting)
                ? null : before.applicationId();
            pending.add(binding(before.project(), configuration.getName(), expected));
        });
        pending.add("Binding recovery is unverified; a native EDT worker may still be active. Wait for EDT to become idle before completing binding recovery or deletion cleanup."); //$NON-NLS-1$
        return report(json, List.of(), List.of(), pending, List.of());
    }

    private String finish(JsonObject json, String projectName, boolean setDefault, boolean deleting)
    {
        if (json.has("mutationPending") && json.get("mutationPending").getAsBoolean()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return unverified(json, projectName, setDefault, deleting);
        }
        try (var coordination = InfobaseMutationCoordinator.acquire())
        {
            // Confirm native writes have returned before claiming that no further reset is pending.
        }
        catch (InfobaseMutationCoordinator.Unavailable pending)
        {
            return unverified(json, projectName, setDefault, deleting);
        }
        var completed = new AtomicReference<String>();
        try (var admission = new CredentialWriteControl())
        {
            var recovered = BoundedJob.runWithCancellation("Restore EDT application bindings", //$NON-NLS-1$
                ApplicationSupport.LOOKUP_TIMEOUT_MS, monitor ->
                    completed.set(restore(json, projectName, setDefault, deleting, admission)), admission::close);
            admission.close();
            if (recovered.isSuccess() && !admission.writeInProgress() && completed.get() != null)
            {
                return completed.get();
            }
        }
        return unverified(json, projectName, setDefault, deleting);
    }

    private String restore(JsonObject json, String projectName, boolean setDefault, boolean deleting,
            CredentialWriteControl admission)
    {
        String changedId = text(json, "applicationId"); //$NON-NLS-1$
        boolean succeeded = json.get("success").getAsBoolean(); //$NON-NLS-1$
        var preserved = new ArrayList<Object>();
        var restored = new ArrayList<Object>();
        var unrestored = new ArrayList<Object>();
        var removed = new ArrayList<Object>();
        for (var entry : defaults.entrySet())
        {
            IProject project = entry.getKey();
            boolean explicit = setDefault && succeeded && projectName.equals(project.getName()) && changedId != null;
            String expected = explicit ? changedId : entry.getValue();
            var binding = binding(project.getName(), "defaultApplicationId", expected); //$NON-NLS-1$
            try
            {
                if (isDeletionTarget(json, projectName, project.getName(), expected, deleting))
                {
                    recordDeletionBinding(binding, clearDeletedDefault(project, expected, admission), removed, preserved);
                    continue;
                }
                String current = defaultId.apply(project);
                if (Objects.equals(expected, current)) { preserved.add(binding); continue; }
                if (!explicit && current != null) { recordKeptBinding(binding, current, preserved); continue; }
                IApplication application = expected == null ? null : application(project, expected);
                write(admission, () -> {
                    if (!project.isOpen()) { throw new IllegalStateException("Project closed before restoration"); } //$NON-NLS-1$
                    if (explicit || defaultId.apply(project) == null)
                    {
                        manager.setDefaultApplication(project, application);
                    }
                });
                String observed = defaultId.apply(project);
                if (!explicit && observed != null && !Objects.equals(expected, observed))
                {
                    recordKeptBinding(binding, observed, preserved);
                    continue;
                }
                if (!Objects.equals(expected, observed))
                {
                    throw new IllegalStateException("EDT did not retain the default after restoring it"); //$NON-NLS-1$
                }
                restored.add(binding);
            }
            catch (Exception failure)
            {
                var failed = failedBinding(binding, failure, () -> defaultId.apply(project),
                    isDeletionTarget(json, projectName, project.getName(), expected, deleting));
                if (explicit || !keepObservedNewerBinding(failed, preserved)) { unrestored.add(failed); }
            }
        }
        for (var entry : launches.entrySet())
        {
            ILaunchConfiguration configuration = entry.getKey();
            LaunchBinding before = entry.getValue();
            var binding = binding(before.project(), configuration.getName(), before.applicationId());
            try
            {
                if (isDeletionTarget(json, projectName, before.project(), before.applicationId(), deleting))
                {
                    recordDeletionBinding(binding, clearDeletedLaunch(configuration, before, admission), removed, preserved);
                    continue;
                }
                if (!configuration.exists() || !Objects.equals(before.project(),
                    configuration.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null)))
                {
                    throw new IllegalStateException("Launch configuration was removed or moved to another project"); //$NON-NLS-1$
                }
                String current = configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null);
                if (Objects.equals(before.applicationId(), current)) { preserved.add(binding); continue; }
                if (current != null) { recordKeptBinding(binding, current, preserved); continue; }
                if (before.applicationId() != null) { application(project(before.project()), before.applicationId()); }
                var copy = configuration.getWorkingCopy();
                write(admission, () -> {
                    if (!configuration.exists() || !Objects.equals(before.project(),
                        copy.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null))
                        || !Objects.equals(before.project(), configuration.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null)))
                    {
                        throw new IllegalStateException("Launch configuration changed before restoration"); //$NON-NLS-1$
                    }
                    if (configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null) != null) { return; }
                    if (copy.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null) != null)
                    {
                        throw new IllegalStateException("Working-copy application binding changed before restoration"); //$NON-NLS-1$
                    }
                    copy.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, before.applicationId());
                    copy.doSave();
                });
                String observed = configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null);
                if (observed != null && !Objects.equals(before.applicationId(), observed))
                {
                    recordKeptBinding(binding, observed, preserved);
                    continue;
                }
                if (!Objects.equals(before.applicationId(), observed))
                {
                    throw new IllegalStateException("EDT did not retain the launch binding after restoring it"); //$NON-NLS-1$
                }
                restored.add(binding);
            }
            catch (Exception failure)
            {
                var failed = failedBinding(binding, failure,
                    () -> configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null),
                    isDeletionTarget(json, projectName, before.project(), before.applicationId(), deleting));
                if (!keepObservedNewerBinding(failed, preserved)) { unrestored.add(failed); }
            }
        }
        return report(json, preserved, restored, unrestored, removed);
    }

    private String clearDeletedDefault(IProject project, String deletedId, CredentialWriteControl admission)
            throws Exception
    {
        if (deletedId.equals(defaultId.apply(project)))
        {
            write(admission, () -> {
                if (deletedId.equals(defaultId.apply(project)))
                {
                    if (!project.isOpen())
                    {
                        throw new IllegalStateException("Project closed before deletion cleanup"); //$NON-NLS-1$
                    }
                    manager.setDefaultApplication(project, null);
                }
            });
        }
        return verifiedDeletionBinding(defaultId.apply(project), deletedId);
    }

    private String clearDeletedLaunch(ILaunchConfiguration configuration, LaunchBinding before,
            CredentialWriteControl admission) throws Exception
    {
        if (!configuration.exists()) { return null; }
        if (!Objects.equals(before.project(), configuration.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null)))
        {
            throw new IllegalStateException("Launch configuration moved to another project before deletion cleanup"); //$NON-NLS-1$
        }
        if (before.applicationId().equals(configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null)))
        {
            var copy = configuration.getWorkingCopy();
            write(admission, () -> {
                if (!configuration.exists()) { return; }
                if (!Objects.equals(before.project(), configuration.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null))
                    || !Objects.equals(before.project(), copy.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null)))
                {
                    throw new IllegalStateException("Launch configuration moved before deletion cleanup"); //$NON-NLS-1$
                }
                if (before.applicationId().equals(configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null)))
                {
                    if (!before.applicationId().equals(copy.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null)))
                    {
                        throw new IllegalStateException("Launch binding edited before deletion cleanup"); //$NON-NLS-1$
                    }
                    copy.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null);
                    copy.doSave();
                }
            });
        }
        return configuration.exists() ? verifiedDeletionBinding(
            configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null), before.applicationId()) : null;
    }

    private static String verifiedDeletionBinding(String observed, String deletedId)
    {
        if (deletedId.equals(observed))
        {
            throw new IllegalStateException("The binding still points to the deleted application; clear it in EDT's Applications/Run Configurations"); //$NON-NLS-1$
        }
        return observed;
    }

    private static void recordDeletionBinding(Map<String, Object> binding, String observed,
            List<Object> removed, List<Object> preserved)
    {
        binding.put("observedApplicationId", observed == null ? "" : observed); //$NON-NLS-1$ //$NON-NLS-2$
        if (observed == null) { removed.add(binding); }
        else
        {
            recordKeptBinding(binding, observed, preserved);
        }
    }

    private static void recordKeptBinding(Map<String, Object> binding, String observed, List<Object> preserved)
    {
        binding.put("previousApplicationId", binding.get("applicationId")); //$NON-NLS-1$ //$NON-NLS-2$
        binding.put("applicationId", observed); //$NON-NLS-1$
        binding.put("observedApplicationId", observed); //$NON-NLS-1$
        binding.put("reason", "Newer non-null application choice kept"); //$NON-NLS-1$ //$NON-NLS-2$
        preserved.add(binding);
    }

    private static boolean keepObservedNewerBinding(Map<String, Object> failed, List<Object> preserved)
    {
        if (failed.get("observedApplicationId") instanceof String observed && !observed.isEmpty() //$NON-NLS-1$
            && !Objects.equals(failed.get("applicationId"), observed)) //$NON-NLS-1$
        {
            failed.remove("requiredAction"); //$NON-NLS-1$
            recordKeptBinding(failed, observed, preserved);
            return true;
        }
        return false;
    }

    private IProject project(String name)
    {
        return defaults.keySet().stream().filter(project -> Objects.equals(name, project.getName()))
            .findFirst().orElseThrow(() -> new IllegalStateException("Binding project is unavailable: " + name)); //$NON-NLS-1$
    }

    private void rememberApplication(IProject project, String id)
    {
        var key = new ApplicationKey(project.getName(), id);
        if (!registrations.containsKey(key))
        {
            var application = application(project, id);
            var reference = application instanceof IInfobaseApplication infobase ? infobase.getInfobase() : null;
            registrations.put(key, reference == null ? null : reference.getUuid());
        }
    }

    private boolean isDeletionTarget(JsonObject json, String operationProject, String bindingProject, String id,
            boolean deleting)
    {
        if (!deleting || id == null || !json.get("success").getAsBoolean() || !"deleted".equals(text(json, "action"))) { return false; } //$NON-NLS-1$ //$NON-NLS-2$
        if (Objects.equals(operationProject, bindingProject) && id.equals(text(json, "applicationId"))) { return true; } //$NON-NLS-1$
        UUID registration = registrations.get(new ApplicationKey(bindingProject, id));
        return booleanField(json, "deleteRegistration") && json.get("deleteRegistration").getAsBoolean() //$NON-NLS-1$ //$NON-NLS-2$
            && registration != null && registration.toString().equals(text(json, "registrationId")); //$NON-NLS-1$
    }

    private static JsonObject decodeOperationResult(String raw)
    {
        JsonObject json = new JsonObject();
        try
        {
            var parsed = JsonParser.parseString(raw);
            if (parsed.isJsonObject()) { json = parsed.getAsJsonObject(); }
        }
        catch (RuntimeException invalid) { /* Normalize the result and still recover captured bindings. */ }
        boolean valid = booleanField(json, "success"); //$NON-NLS-1$
        if (json.has("mutationPending") && !booleanField(json, "mutationPending")) { valid = false; } //$NON-NLS-1$ //$NON-NLS-2$
        for (String key : List.of("action", "applicationId", "registrationId", "error")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            if (json.has(key) && !json.get(key).isJsonNull()
                && (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString()))
            {
                json.remove(key);
                valid = false;
            }
        }
        if (valid) { return json; }
        if (!booleanField(json, "mutationPending")) { json.remove("mutationPending"); } //$NON-NLS-1$ //$NON-NLS-2$
        ToolResult error = ToolResult.error("Infobase operation result was invalid; inspect get_applications and " //$NON-NLS-1$
            + "list_configurations before retrying. Binding recovery is reported below."); //$NON-NLS-1$
        for (var entry : json.entrySet())
        {
            if (!"success".equals(entry.getKey()) && !"error".equals(entry.getKey())) //$NON-NLS-1$ //$NON-NLS-2$
            {
                error.put(entry.getKey(), entry.getValue());
            }
        }
        return JsonParser.parseString(error.toJson()).getAsJsonObject();
    }

    private static boolean booleanField(JsonObject json, String key)
    {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isBoolean();
    }

    @FunctionalInterface
    private interface BindingWrite
    {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface BindingRead
    {
        String read() throws Exception;
    }

    private static Map<String, Object> failedBinding(Map<String, Object> binding, Exception failure,
            BindingRead observer, boolean deleting)
    {
        if (deleting)
        {
            binding.put("requiredAction", "Clear this deleted application's binding in EDT's Applications/Run Configurations; do not reselect the deleted ID."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        binding.put("reason", failure.getClass().getSimpleName() + ": " + failure.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String current = observer.read();
            binding.put("observedApplicationId", current == null ? "" : current); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception unreadable)
        {
            binding.put("observation", "unverified"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return binding;
    }

    private static void write(CredentialWriteControl admission, BindingWrite operation) throws Exception
    {
        try (var coordination = InfobaseMutationCoordinator.acquire(); var permit = admission.tryBeginWrite())
        {
            if (permit == null)
            {
                throw new IllegalStateException("Binding restoration deadline elapsed; no further write was started"); //$NON-NLS-1$
            }
            operation.run();
        }
    }

    private IApplication application(IProject project, String id)
    {
        var read = ApplicationSupport.getApplicationBounded(manager, project, id, ApplicationSupport.LOOKUP_TIMEOUT_MS);
        var found = read.concluded() ? read.valueOrRethrow() : null;
        if (found == null || found.isEmpty() || !id.equals(found.get().getId()))
        {
            throw new IllegalStateException("Application '" + id + "' cannot be resolved in project '" + project.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return found.get();
    }

    private static Map<String, Object> binding(String project, String setting, String id)
    {
        var binding = new LinkedHashMap<String, Object>();
        binding.put("project", project); //$NON-NLS-1$
        binding.put("setting", setting); //$NON-NLS-1$
        binding.put("applicationId", id == null ? "" : id); //$NON-NLS-1$ //$NON-NLS-2$
        return binding;
    }

    private static String text(JsonObject json, String key)
    {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static String report(JsonObject json, List<?> preserved, List<?> restored, List<?> unrestored, List<?> removed)
    {
        ToolResult result = unrestored.isEmpty() ? ToolResult.success()
            : ToolResult.error("Infobase operation completed or failed as recorded below, but application bindings " //$NON-NLS-1$
                + "could not be fully restored. Restore unrestoredBindings in EDT's Applications/Run Configurations " //$NON-NLS-1$
                + "before launching; inspect get_applications and list_configurations. Do not blindly repeat the mutation."); //$NON-NLS-1$
        for (var entry : json.entrySet())
        {
            result.put(entry.getKey(), entry.getValue());
        }
        if (!unrestored.isEmpty())
        {
            if (json.has("error"))
            {
                result.put("operationError", json.get("error")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String originalError = text(json, "error"); //$NON-NLS-1$
            result.put("success", false).put("error", //$NON-NLS-1$ //$NON-NLS-2$
                (originalError == null ? "" : originalError + " ") //$NON-NLS-1$ //$NON-NLS-2$
                    + "Application defaults or launch bindings remain unrestored or uncleared; see unrestoredBindings. " //$NON-NLS-1$
                    + "Review those bindings in EDT's Applications/Run Configurations before launching or retrying; keep newer intentional choices."); //$NON-NLS-1$
        }
        return result.put("preservedBindings", preserved).put("restoredBindings", restored) //$NON-NLS-1$ //$NON-NLS-2$
            .put("unrestoredBindings", unrestored).put("removedBindings", removed).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
