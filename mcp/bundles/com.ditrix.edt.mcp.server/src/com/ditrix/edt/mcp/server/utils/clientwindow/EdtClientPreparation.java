// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com._1c.g5.v8.dt.common.HttpUtil;
import com._1c.g5.v8.dt.debug.core.launchconfigurations.LaunchRuntimeInstallationProvider;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.runtimes.IProcessEncodingProvider;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IDesignerSessionThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchOverrides;
import com.ditrix.edt.mcp.server.utils.PlatformServicesSupport;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationAttributeRepository;

/** Resolves EDT's runtime, access, dump, update and attach services without launching its client. */
final class EdtClientPreparation
{
    private static final String DEBUG = "com._1c.g5.v8.dt.debug.core.ATTR_";
    private EdtClientPreparation() { }

    static EdtClientCommand.Command prepare(ILaunchConfiguration config, ILaunch launch, boolean update,
        ExternalInfobaseChangesPolicy policy, StandaloneServerPortConflictPolicy portPolicy,
        LaunchOverrides.Prepared overrides) throws Exception
    {
        String projectName = config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, "");
        ProjectContext project = ProjectContext.of(projectName);
        if (!project.exists() || !project.isOpen()) { throw new IllegalArgumentException(ProjectContext.notFoundMessage(projectName)); }
        var manager = Activator.getDefault().getApplicationManager();
        String id = LaunchLifecycleUtils.resolveDelegateApplicationId(config, projectName);
        var resolved = ApplicationSupport.resolveApplication(manager, project.project(), projectName, id,
            ApplicationSupport.LOOKUP_TIMEOUT_MS, "No client was started");
        if (resolved.errorJson() != null) { throw new IllegalArgumentException("Cannot resolve the client application; check get_applications and its default binding."); }
        var application = resolved.application();
        InfobaseReference infobase = InfobaseAccessSupport.resolveInfobaseReference(application);
        if (infobase == null) { throw new IllegalArgumentException("The application has no infobase; select a file or server infobase application."); }

        var components = PlatformServicesSupport.resolve(IRuntimeComponentManager.class);
        IResolvableRuntimeInstallation resolvable;
        ILaunchableRuntimeComponent thinClient;
        ExecutionContext context = new ExecutionContext();
        context.setProperty("activeLaunch", launch);
        var names = LaunchLifecycleUtils.delegateAttributionNames(config, project.project(), manager);
        ExternalInfobaseChangesPolicy updatePolicy = update ? policy : null;
        StandaloneServerPortConflictPolicy launchPortPolicy = portPolicyFor(application.getId(), portPolicy);
        boolean armed = LaunchUpdateDialogAutoConfirmer.arm(update, false, update, updatePolicy,
            names.infobaseName(), launchPortPolicy, names.serverName());
        try
        {
            EdtPreLaunchUpdate.run(update, () -> EdtPreLaunchUpdate.update(project.project(), application,
                launch.getLaunchMode(), context, new NullProgressMonitor()));
            var runtimeProvider = PlatformServicesSupport.resolve("com._1c.g5.v8.dt.debug.core",
                "com._1c.g5.v8.dt.internal.debug.core.DebugCorePlugin", LaunchRuntimeInstallationProvider.class);
            resolvable = runtimeProvider.getInstallation(project.project(), application)
                .orElseThrow(() -> new IllegalArgumentException("The application has no runtime installation; configure its 1C runtime in EDT."));
            RuntimeInstallation installation = resolvable.resolve(List.of(IRuntimeComponentTypes.THIN_CLIENT), infobase.getAppArch());
            var component = components.getComponent(installation, IRuntimeComponentTypes.THIN_CLIENT);
            if (!(component instanceof ILaunchableRuntimeComponent client))
            {
                throw new IllegalArgumentException("The application's runtime has no thin-client executable; install the 1C thin client.");
            }

            thinClient = client;
            manager.prepare(application, launch.getLaunchMode(), context, new NullProgressMonitor());
        }
        finally
        {
            if (armed)
            {
                LaunchUpdateDialogAutoConfirmer.disarm(update, false, update, updatePolicy,
                    names.infobaseName(), launchPortPolicy, names.serverName());
            }
        }

        Path dump = overrides.buildExternalObjectDump();
        if (dump == null)
        {
            String externalProject = config.getAttribute(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_PROJECT_NAME, (String)null);
            String externalName = config.getAttribute(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_NAME, (String)null);
            if (externalProject != null && externalName != null)
            {
                var stored = LaunchOverrides.prepareStoredExternalObject(config);
                if (stored.errorJson != null) { throw new IllegalArgumentException("The stored external object cannot be resolved; qualify externalObjectName and retry."); }
                dump = stored.buildExternalObjectDump();
            }
        }
        var access = config.getAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, false)
            ? PlatformServicesSupport.resolve(IInfobaseAccessManager.class).resolveSettings(infobase) : null;
        Version version = project.platformVersion();
        RuntimeExecutionArguments arguments = EdtClientCommand.arguments(config, infobase, access, dump,
            version != null && version.isGreaterThan(Version.V8_3_16));
        if (ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode()))
        {
            Object preparedUrl = context.getProperty("debugUrl").orElse(null);
            attach(config, launch, application, resolvable, infobase, components, arguments,
                preparedUrl instanceof URL url ? url : null);
        }
        var encoding = PlatformServicesSupport.resolve(IProcessEncodingProvider.class);
        return new EdtClientCommand().build(thinClient.getFile(), infobase,
            !encoding.isDefault() && encoding.splitInfobaseConnection(), arguments);
    }

    static StandaloneServerPortConflictPolicy portPolicyFor(String applicationId, StandaloneServerPortConflictPolicy requested)
    {
        return DebugServerTargetSupport.isServerApplicationId(applicationId) ? requested : null;
    }

    /**
     * EDT's own order: the configuration's port, else the application's stored "port" attribute, else a free
     * port. EDT's local target manager refuses -1, so a concrete port is always passed.
     */
    static int localDebugPort(int configured, Supplier<Optional<String>> applicationPort, IntSupplier freePort)
    {
        if (configured > 0)
        {
            return configured;
        }
        Optional<String> stored = applicationPort.get();
        if (stored.isPresent())
        {
            try
            {
                int port = Integer.parseInt(stored.get().trim());
                if (port > 0)
                {
                    return port;
                }
            }
            catch (NumberFormatException ignored)
            {
                // EDT logs and ignores an unparseable stored port too.
            }
        }
        return freePort.getAsInt();
    }

    /** {@code IApplicationAttributeRepository.getAttribute} never returns null (its contract); a failure is empty. */
    private static Optional<String> storedApplicationPort(IApplication application)
    {
        try
        {
            return PlatformServicesSupport.resolve(IApplicationAttributeRepository.class).getAttribute(application, "port"); //$NON-NLS-1$
        }
        catch (Exception unavailable)
        {
            return Optional.empty();
        }
    }

    private static void attach(ILaunchConfiguration config, ILaunch launch, IApplication application,
        IResolvableRuntimeInstallation resolvable, InfobaseReference infobase, IRuntimeComponentManager components,
        RuntimeExecutionArguments arguments, URL preparedUrl) throws Exception
    {
        Object service = Activator.getDefault().getRuntimeDebugClientTargetManager();
        if (!(service instanceof IRuntimeDebugClientTargetManager targets))
        {
            throw new IllegalStateException("EDT debug target manager is unavailable; enable the 1C debugger and retry.");
        }
        String location = config.getAttribute(DEBUG + "USE_LOCAL_DEBUG_SERVER", "AUTO");
        boolean local = preparedUrl == null && ("LOCAL".equals(location)
            || ("AUTO".equals(location) && infobase.getInfobaseType() == InfobaseType.FILE));
        IRuntimeDebugClientTarget target;
        if (local)
        {
            RuntimeInstallation debugInstallation = resolvable.resolve(List.of(IRuntimeComponentTypes.DEBUG_SERVER), infobase.getAppArch());
            target = targets.getOrCreateLocal(debugInstallation, infobase,
                localDebugPort(config.getAttribute(DEBUG + "DEBUG_SERVER_PORT", -1),
                    () -> storedApplicationPort(application), HttpUtil::findFreePort), launch);
        }
        else
        {
            String preparedAlias = launch.getAttribute(LaunchConfigUtils.ATTR_DEBUG_INFOBASE_ALIAS);
            String alias = config.getAttribute(LaunchConfigUtils.ATTR_DEBUG_INFOBASE_ALIAS,
                preparedAlias != null ? preparedAlias : InfobaseReferences.getAlias(infobase));
            String url = preparedUrl != null ? preparedUrl.toString()
                : config.getAttribute(LaunchConfigUtils.ATTR_DEBUG_SERVER_URL, (String)null);
            if (preparedUrl == null && !"REMOTE".equals(location))
            {
                RuntimeInstallation thickInstallation = resolvable.resolve(List.of(IRuntimeComponentTypes.THICK_CLIENT), infobase.getAppArch());
                var executor = components.resolveExecutor(ILaunchableRuntimeComponent.class, IThickClientLauncher.class,
                    thickInstallation, IRuntimeComponentTypes.THICK_CLIENT);
                if (!(executor.getExecutor() instanceof IDesignerSessionThickClientLauncher designer))
                {
                    throw new IllegalStateException("The runtime cannot retrieve the infobase debug server URL; configure a remote debug server in EDT.");
                }
                var settings = PlatformServicesSupport.resolve(IInfobaseAccessManager.class).resolveSettings(infobase);
                var info = designer.retrieveDebugServerUrl(executor.getComponent(), infobase,
                    EdtClientCommand.designArguments(infobase, settings))
                    .orElseThrow(() -> new IllegalStateException("EDT found no debug server for this infobase; enable debugging on its server."));
                url = info.getServerUrl().toString();
                alias = info.getInfobaseName().orElse(alias);
            }
            if (url == null || alias == null)
            {
                throw new IllegalArgumentException("Set the debug-server URL and infobase alias in the EDT launch configuration.");
            }
            target = targets.getOrCreateRemote(url, alias, launch);
        }
        if (target == null) { throw new IllegalStateException("EDT did not create an attach debug session; inspect the EDT debug-server configuration."); }
        launch.addDebugTarget(target);
        arguments.setDebugServerUrl(new URL(target.getDebugServerUrl()));
    }
}
