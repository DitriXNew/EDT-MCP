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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Protects workspace bindings from EDT's synthetic DELETED events on launcher reload. */
public final class InfobaseBindings
{
    private static final ReentrantLock OPERATIONS = new ReentrantLock();
    private final IApplicationManager manager;
    private final Function<IProject, String> defaultId;
    private final Map<IProject, String> defaults = new LinkedHashMap<>();
    private final Map<ILaunchConfiguration, LaunchBinding> launches = new LinkedHashMap<>();
    private String captureFailure;
    private boolean workspace;
    private IProject[] projects;
    private ILaunchConfiguration[] configurations;

    private record LaunchBinding(String project, String applicationId) { }

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
                    application(entry.getKey(), entry.getValue());
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
            if (locked)
            {
                OPERATIONS.unlock();
            }
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
        String result;
        try
        {
            result = operation.get();
        }
        catch (Exception failure)
        {
            result = ToolResult.error("Infobase operation failed (" + failure.getClass().getSimpleName() //$NON-NLS-1$
                + "); inspect get_applications before retrying.").toJson(); //$NON-NLS-1$
        }
        return finish(result, projectName, setDefault, deleting);
    }

    /** Also used after a bounded worker: an incomplete worker cannot prove preservation. */
    private String unverified(String result, String projectName, boolean setDefault, boolean deleting)
    {
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        var pending = new ArrayList<Object>();
        String changedId = text(json, "applicationId"); //$NON-NLS-1$
        String removedId = deleting && "deleted".equals(text(json, "action")) ? changedId : null; //$NON-NLS-1$ //$NON-NLS-2$
        defaults.forEach((project, id) -> {
            if (id != null && id.equals(removedId) && projectName.equals(project.getName()))
            {
                return;
            }
            String expected = setDefault && json.get("success").getAsBoolean() //$NON-NLS-1$
                && projectName.equals(project.getName()) && changedId != null ? changedId : id;
            pending.add(binding(project.getName(), "defaultApplicationId", expected)); //$NON-NLS-1$
        });
        launches.forEach((configuration, before) -> {
            if (before.applicationId() == null || !before.applicationId().equals(removedId)
                || !projectName.equals(before.project()))
            {
                pending.add(binding(before.project(), configuration.getName(), before.applicationId()));
            }
        });
        pending.add("Binding recovery is unverified; a native EDT worker may still be active. Wait for EDT to become idle before restoring these bindings."); //$NON-NLS-1$
        return report(json, List.of(), List.of(), pending, List.of());
    }

    private String finish(String result, String projectName, boolean setDefault, boolean deleting)
    {
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        if (json.has("mutationPending") && json.get("mutationPending").getAsBoolean()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return unverified(result, projectName, setDefault, deleting);
        }
        try (var coordination = InfobaseMutationCoordinator.acquire())
        {
            // Confirm native writes have returned before claiming that no further reset is pending.
        }
        catch (InfobaseMutationCoordinator.Unavailable pending)
        {
            return unverified(result, projectName, setDefault, deleting);
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
        return unverified(result, projectName, setDefault, deleting);
    }

    private String restore(JsonObject json, String projectName, boolean setDefault, boolean deleting,
            CredentialWriteControl admission)
    {
        String changedId = text(json, "applicationId"); //$NON-NLS-1$
        boolean succeeded = json.has("success") && json.get("success").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        String removedId = deleting && "deleted".equals(text(json, "action")) ? changedId : null; //$NON-NLS-1$ //$NON-NLS-2$
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
                if (expected != null && expected.equals(removedId) && projectName.equals(project.getName()))
                {
                    removed.add(binding);
                    continue;
                }
                String current = defaultId.apply(project);
                if (Objects.equals(expected, current))
                {
                    preserved.add(binding);
                    continue;
                }
                if (current != null && (!explicit || !Objects.equals(current, entry.getValue())))
                {
                    throw new IllegalStateException("Default changed to a different application; newer choice kept"); //$NON-NLS-1$
                }
                IApplication application = expected == null ? null : application(project, expected);
                if (!Objects.equals(current, defaultId.apply(project)))
                {
                    throw new IllegalStateException("Default changed during restoration"); //$NON-NLS-1$
                }
                write(admission, () -> {
                    if (!project.isOpen() || !Objects.equals(current, defaultId.apply(project)))
                    {
                        throw new IllegalStateException("Project or default changed before restoration"); //$NON-NLS-1$
                    }
                    manager.setDefaultApplication(project, application);
                });
                if (!Objects.equals(expected, defaultId.apply(project)))
                {
                    throw new IllegalStateException("EDT did not retain the default after restoring it"); //$NON-NLS-1$
                }
                restored.add(binding);
            }
            catch (Exception failure)
            {
                unrestored.add(failedBinding(binding, failure, () -> defaultId.apply(project)));
            }
        }
        for (var entry : launches.entrySet())
        {
            ILaunchConfiguration configuration = entry.getKey();
            LaunchBinding before = entry.getValue();
            var binding = binding(before.project(), configuration.getName(), before.applicationId());
            try
            {
                if (before.applicationId() != null && before.applicationId().equals(removedId)
                    && projectName.equals(before.project()))
                {
                    removed.add(binding);
                    continue;
                }
                if (!configuration.exists() || !Objects.equals(before.project(),
                    configuration.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null)))
                {
                    throw new IllegalStateException("Launch configuration was removed or moved to another project"); //$NON-NLS-1$
                }
                String current = configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null);
                if (Objects.equals(before.applicationId(), current))
                {
                    preserved.add(binding);
                    continue;
                }
                if (current != null)
                {
                    throw new IllegalStateException("Application binding changed; newer choice kept"); //$NON-NLS-1$
                }
                var copy = configuration.getWorkingCopy();
                write(admission, () -> {
                    if (!configuration.exists() || !Objects.equals(before.project(),
                        copy.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null))
                        || !Objects.equals(before.project(), configuration.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null))
                        || configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null) != null
                        || copy.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null) != null)
                    {
                        throw new IllegalStateException("Launch binding changed before restoration"); //$NON-NLS-1$
                    }
                    copy.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, before.applicationId());
                    copy.doSave();
                });
                if (!Objects.equals(before.applicationId(), configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null)))
                {
                    throw new IllegalStateException("EDT did not retain the launch binding after restoring it"); //$NON-NLS-1$
                }
                restored.add(binding);
            }
            catch (Exception failure)
            {
                unrestored.add(failedBinding(binding, failure,
                    () -> configuration.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String)null)));
            }
        }
        return report(json, preserved, restored, unrestored, removed);
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
            BindingRead observer)
    {
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
                    + "Application defaults or launch bindings remain unrestored; see unrestoredBindings. " //$NON-NLS-1$
                    + "Restore those IDs in EDT's Applications/Run Configurations before launching or retrying."); //$NON-NLS-1$
        }
        return result.put("preservedBindings", preserved).put("restoredBindings", restored) //$NON-NLS-1$ //$NON-NLS-2$
            .put("unrestoredBindings", unrestored).put("removedBindings", removed).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
