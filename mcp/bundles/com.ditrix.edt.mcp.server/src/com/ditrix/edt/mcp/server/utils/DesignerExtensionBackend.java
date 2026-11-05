/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com._1c.g5.designer.ssh.client.IDesignerSession;
import com._1c.g5.designer.ssh.client.operation.IUpdateConfigurationStep.SessionTermination;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Backend;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Extension;
import com.e1c.g5.dt.applications.IApplication;

/** Native Designer operations, using EDT's cached connection and its connection-use lock. */
public final class DesignerExtensionBackend implements Backend
{
    private static final String CONNECTION_PACKAGE =
        "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.";

    private final IDesignerSession session;
    private final IProgressMonitor monitor;

    public DesignerExtensionBackend(IDesignerSession session, IProgressMonitor monitor)
    {
        this.session = session;
        this.monitor = monitor;
    }

    @FunctionalInterface
    public interface Work
    {
        ToolResult run(Backend backend) throws Exception;
    }

    /** The pool is not exported by EDT; only this adapter reflects its verified acquire/use API. */
    public static ToolResult withConnection(IProject project, IApplication application,
        IProgressMonitor monitor, Work work) throws Exception
    {
        InfobaseReference reference = InfobaseAccessSupport.resolveInfobaseReference(application);
        if (reference == null)
        {
            throw new IllegalArgumentException("Application '" + application.getId()
                + "' has no infobase reference; select an infobase application with get_applications.");
        }
        var executor = ConfigurationFileExportSupport.resolveThickClient(project, reference);
        RuntimeExecutionArguments arguments = new RuntimeExecutionArguments();
        arguments.setMonitor(monitor);
        var manager = InfobaseAccessSupport.resolveAccessManager();
        if (manager == null)
        {
            throw new IllegalStateException("Cannot resolve infobase access settings; check EDT platform registration and set_infobase_credentials.");
        }
        IInfobaseAccessSettings settings = manager.resolveSettings(reference);
        if (settings != null)
        {
            arguments.setAccess(settings.access());
            arguments.setUsername(settings.userName());
            arguments.setPassword(settings.password());
            applyAdditionalInfobaseParameters(arguments, reference, settings.additionalProperties());
        }
        Class<?> poolType = PlatformServicesSupport.bundle().loadClass(CONNECTION_PACKAGE + "DesignerSessionPool");
        Object pool = PlatformServicesSupport.resolve(poolType);
        Class<?> connectionType = PlatformServicesSupport.bundle().loadClass(CONNECTION_PACKAGE + "DesignerAgentConnection");
        Class<?> actionType = PlatformServicesSupport.bundle().loadClass(
            CONNECTION_PACKAGE + "DesignerAgentConnection$DesignerAgentConnectionAction");
        checkCancelled(monitor);
        try
        {
            Object connection = poolType.getMethod("acquire", RuntimeInstallation.class,
                InfobaseReference.class, RuntimeExecutionArguments.class).invoke(pool,
                    executor.getComponent().getInstallation(), reference, arguments);
            Method getSession = connectionType.getMethod("getSession");
            Object action = Proxy.newProxyInstance(actionType.getClassLoader(), new Class<?>[] {actionType},
                (proxy, method, args) -> {
                    if ("run".equals(method.getName()))
                    {
                        checkCancelled(monitor);
                        return work.run(new DesignerExtensionBackend((IDesignerSession)getSession.invoke(connection), monitor));
                    }
                    if ("toString".equals(method.getName()))
                    {
                        return "EDT-MCP runtime extension operation";
                    }
                    if ("hashCode".equals(method.getName()))
                    {
                        return System.identityHashCode(proxy);
                    }
                    return proxy == args[0];
                });
            return (ToolResult)connectionType.getMethod("use", actionType).invoke(connection, action);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception)
            {
                throw exception;
            }
            throw new IllegalStateException("EDT Designer session failed; inspect the EDT Error Log.", cause);
        }
    }

    /** EDT 2026.1 launches Designer with the parameters stored on the infobase reference. */
    static void applyAdditionalInfobaseParameters(Object arguments, InfobaseReference reference,
        String parameters) throws Exception
    {
        Class<?> api = arguments instanceof RuntimeExecutionArguments
            ? RuntimeExecutionArguments.class : arguments.getClass();
        Method setter = ReflectionUtils.publicMethodOrNull(api, "setAdditionalInfobaseParameters", String.class);
        if (setter != null)
        {
            ReflectionUtils.invoke(setter, arguments, parameters);
        }
        else if (parameters != null && !parameters.isEmpty()
            && !parameters.equals(reference.getAdditionalParameters()))
        {
            throw new IllegalArgumentException("EDT 2026.1 cannot carry the resolved additionalProperties setting"
                + " to Designer because it differs from the infobase reference's Additional parameters."
                + " Resave the infobase access settings in EDT, or clear Additional parameters and retry.");
        }
    }

    @Override
    public List<Extension> read() throws Exception
    {
        checkCancelled(monitor);
        var states = session.extensions().properties().get().allExtensions().exec();
        if (states == null)
        {
            throw new IllegalStateException("Designer did not return an extension inventory; retry action=list.");
        }
        return states.stream().map(e -> new Extension(e.getName(), Objects.toString(e.getVersion(), ""),
            e.isActive(), e.isSafeMode(), e.isUnsafeActionProtected(), Objects.toString(e.getHashSum(), ""))).toList();
    }

    @Override
    public void load(Path path, String name) throws Exception
    {
        checkCancelled(monitor);
        session.configure().importCfToInfobase(path).extension(name).exec();
    }

    @Override
    public void update(String name) throws Exception
    {
        checkCancelled(monitor);
        session.configure().updateDatabaseConfiguration().extension(name)
            .onInfobaseLockError(SessionTermination.DISABLE).onConfirm(changes -> !monitor.isCanceled()).exec();
    }

    @Override
    public void flags(String name, boolean safe, boolean protection, Boolean active) throws Exception
    {
        checkCancelled(monitor);
        var operation = session.extensions().properties().set().extension(name)
            .safeMode(safe).unsafeActionProtection(protection);
        if (active != null)
        {
            operation.active(active);
        }
        operation.exec();
    }

    @Override
    public void delete(String name) throws Exception
    {
        checkCancelled(monitor);
        session.extensions().delete().extension(name).exec();
    }

    public static void checkCancelled(IProgressMonitor monitor)
    {
        if (monitor.isCanceled() || Thread.currentThread().isInterrupted())
        {
            throw new OperationCanceledException("Extension operation was cancelled; inspect action=list before retrying.");
        }
    }
}
