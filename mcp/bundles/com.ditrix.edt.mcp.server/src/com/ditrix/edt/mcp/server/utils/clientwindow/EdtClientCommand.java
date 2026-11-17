// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.AutomatedTestingMode;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.AbstractRuntimeComponentExecutor;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.impl.RuntimeExecutionCommandBuilder.ThickClientMode;
import com._1c.g5.v8.dt.platform.services.model.ClientType;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/** EDT owns argv formatting; only its private configuration-to-arguments mapping is reproduced. */
public final class EdtClientCommand extends AbstractRuntimeComponentExecutor
{
    private static final String PREFIX = "com._1c.g5.v8.dt.launching.core.ATTR_";

    public static final class Command
    {
        private final List<String> arguments;
        Command(List<String> arguments) { this.arguments = List.copyOf(arguments); }
        public List<String> arguments() { return arguments; }
        @Override public String toString()
        {
            // Extra infobase parameters can contain secrets too; do not echo any argv values.
            return "1C thin client [arguments and credentials redacted]";
        }
    }

    public Command build(File executable, InfobaseReference infobase, boolean split,
        RuntimeExecutionArguments arguments)
    {
        RuntimeExecutionCommandBuilder builder = new RuntimeExecutionCommandBuilder(executable, ThickClientMode.ENTERPRISE)
            .enableCheckModal().enableCheckExtensionsAndAddInsSyncCalls()
            .forInfobase(infobase, split).clientType(ClientType.THIN_CLIENT);
        appendAdditionalParameters(builder, arguments.getAdditionalInfobaseParameters());
        appendInfobaseAccess(builder, arguments);
        appendClientArguments(builder, arguments);
        appendRuntimeClientArguments(builder, arguments);
        builder.disableCheckVersion();
        return new Command(builder.toProcessBuilder().command());
    }

    /** Designer-side debug URL retrieval uses stored access, independently of client overrides. */
    public static RuntimeExecutionArguments designArguments(InfobaseReference infobase, IInfobaseAccessSettings settings)
    {
        RuntimeExecutionArguments result = new RuntimeExecutionArguments();
        result.setAccess(settings.access());
        result.setUsername(settings.userName());
        result.setPassword(settings.password());
        result.setAdditionalInfobaseParameters(infobase.getAdditionalParameters());
        return result;
    }

    public static RuntimeExecutionArguments arguments(ILaunchConfiguration config, InfobaseReference infobase,
        IInfobaseAccessSettings settings, Path execute, boolean technicalSpecialist) throws CoreException
    {
        RuntimeExecutionArguments result = new RuntimeExecutionArguments();
        boolean stored = config.getAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, false);
        if (stored)
        {
            if (settings == null)
            {
                throw new IllegalArgumentException("Infobase access settings are unavailable; configure them in EDT and retry.");
            }
            result.setAccess(settings.access());
            result.setUsername(settings.userName());
            result.setPassword(settings.password());
        }
        else
        {
            boolean os = config.getAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, true);
            result.setAccess(os ? InfobaseAccess.OS : InfobaseAccess.INFOBASE);
            if (!os)
            {
                result.setUsername(config.getAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME, (String)null));
                result.setPassword(config.getAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD, (String)null));
            }
        }
        result.setAdditionalInfobaseParameters(infobase.getAdditionalParameters());
        result.setDataSeparation(text(config, "DATA_SEPARATION"));
        result.setStartupOption(text(config, "STARTUP_OPTION"));
        result.setSlowConnection(flag(config, "SLOW_CONNECTION"));
        result.setEmulateDelay(flag(config, "EMULATE_DELAY"));
        result.setCallDelay(config.getAttribute(PREFIX + "CALL_DELAY", 0) / 100.0);
        result.setDataSendingDelay(config.getAttribute(PREFIX + "DATA_SENDING_DELAY", 0) / 100.0);
        result.setDataReceivingDelay(config.getAttribute(PREFIX + "DATA_RECEIVING_DELAY", 0) / 100.0);
        result.setDisableStartupMessages(flag(config, "DO_NOT_DISPLAY_WARNINGS"));
        result.setDisplayPerformance(flag(config, "SHOW_PERFORMANCE"));
        if (technicalSpecialist) { result.setTechnicalSpecialistMode(flag(config, "SHOW_ALL_FUNCTIONS")); }
        else { result.setDisplayAllFunctions(flag(config, "SHOW_ALL_FUNCTIONS")); }
        result.setPwaAppName(text(config, "LAUNCH_PWA_APP_NAME"));
        String log = text(config, "LOG_FILE");
        if (log != null && !log.isBlank()) { result.setLogFile(new File(log)); }
        result.setClear(flag(config, "DO_NOT_CLEAR_LOG"));
        String locale = text(config, "SESSION_LOCALE");
        if (locale != null && !locale.isBlank()) { result.setSessionLocale(Locale.forLanguageTag(locale.replace('_', '-'))); }
        result.setInterfaceLanguage(text(config, "INTERFACE_LANGUAGE"));
        result.setInterfaceVariant(text(config, "INTERFACE_VARIANT"));
        result.setInterfaceTheme(text(config, "INTERFACE_THEME"));
        result.setAutomatedTestingMode(AutomatedTestingMode.get(text(config, "AUTOMATED_TESTING_MODE")));
        if (result.getAutomatedTestingMode() == AutomatedTestingMode.TEST_CLIENT_MODE)
        {
            result.setAutomatedTestingPortNumber(config.getAttribute(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_PORT_NUMBER,
                LaunchConfigUtils.DEFAULT_AUTOMATED_TESTING_PORT));
            result.setAutomatedTestingWebClientId(text(config, "AUTOMATED_TESTING_WEB_CLIENT_ID"));
        }
        result.setExternalObjectDumpPath(execute);
        return result;
    }

    private static String text(ILaunchConfiguration config, String name) throws CoreException
    {
        return config.getAttribute(PREFIX + name, (String)null);
    }
    private static boolean flag(ILaunchConfiguration config, String name) throws CoreException
    {
        return config.getAttribute(PREFIX + name, false);
    }
}
