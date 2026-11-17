// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.junit.Test;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.AutomatedTestingMode;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.*;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

public class EdtClientCommandTest
{
    private InfobaseReference fileInfobase()
    {
        var infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        var connection = ModelFactory.eINSTANCE.createFileConnectionString();
        connection.setFile("D:/base with spaces");
        infobase.setConnectionString(connection);
        return infobase;
    }

    @Test public void fileCredentialsTestingExecuteAndDebug() throws Exception
    {
        RuntimeExecutionArguments args = new RuntimeExecutionArguments();
        args.setAccess(InfobaseAccess.INFOBASE);
        args.setUsername("test user");
        args.setPassword("secret value");
        args.setDisableStartupMessages(true);
        args.setAutomatedTestingMode(AutomatedTestingMode.TEST_CLIENT_MODE);
        args.setAutomatedTestingPortNumber(1540);
        args.setExternalObjectDumpPath(Path.of("D:/tests/test.epf"));
        args.setDebugServerUrl(new URL("http://debug-host:1550"));
        var command = new EdtClientCommand().build(new File("1cv8c.exe"), fileInfobase(), false, args);
        List<String> values = command.arguments();
        assertTrue(values.toString(), values.stream().anyMatch(v -> v.contains("base with spaces")));
        assertEquals("secret value", values.get(values.indexOf("/P") + 1));
        assertTrue(values.contains("/TESTCLIENT"));
        assertTrue(values.contains("-TPort"));
        assertTrue(values.contains("1540"));
        assertTrue(values.contains("/Execute"));
        assertTrue(values.contains(Path.of("D:/tests/test.epf").toString()));
        assertTrue(values.contains("/DisableStartupMessages"));
        int debug = values.indexOf("/DEBUG");
        assertEquals(List.of("/DEBUG", "-http", "-attach", "/DEBUGGERURL", "http://debug-host:1550"),
            values.subList(debug, debug + 5));
        assertFalse(command.toString().contains("secret"));
        assertFalse(command.toString().contains("test user"));
    }

    @Test public void serverConnectionAndExtraArguments()
    {
        var infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        var connection = ModelFactory.eINSTANCE.createServerConnectionString();
        connection.setServer("server:1541");
        connection.setReference("server_base");
        infobase.setConnectionString(connection);
        RuntimeExecutionArguments args = new RuntimeExecutionArguments();
        args.setAccess(InfobaseAccess.OS);
        args.setAdditionalInfobaseParameters("/SomeOption \"value with spaces\"");
        var command = new EdtClientCommand().build(new File("1cv8c"), infobase, false, args).arguments();
        assertTrue(command.toString(), command.stream().anyMatch(v -> v.contains("server:1541")));
        assertTrue(command.toString(), command.stream().anyMatch(v -> v.contains("server_base")));
        assertTrue(command.contains("/SomeOption"));
        assertTrue(command.contains("value with spaces"));
        assertFalse(command.contains("/P"));
        assertFalse(command.contains("/DEBUG"));
    }

    @Test public void configurationUsesStoredAccessAndNativeTestingDefaults() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, false)).thenReturn(true);
        when(config.getAttribute(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_MODE, (String)null)).thenReturn("TESTCLIENT");
        when(config.getAttribute(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_PORT_NUMBER, 1538)).thenReturn(1538);
        var settings = new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "user", "password", null);
        var args = EdtClientCommand.arguments(config, fileInfobase(), settings, null, true);
        assertEquals("password", args.getPassword());
        assertEquals("user", args.getUsername());
        assertEquals(InfobaseAccess.INFOBASE, args.getAccess());
        assertEquals(Integer.valueOf(1538), args.getAutomatedTestingPortNumber());
    }

    @Test public void windowsArgvQuoting()
    {
        assertEquals("client.exe \"\" \"space here\" a\\\"b C:\\path\\ \"C:\\space path\\\\\"",
            WindowsIsolatedClient.commandLine(List.of("client.exe", "", "space here", "a\"b", "C:\\path\\", "C:\\space path\\")));
    }

    @Test public void designDebugAccessAndEdtDelayUnitsArePreserved() throws Exception
    {
        var infobase = fileInfobase();
        var settings = new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "designer-user", "designer-password", null);
        var design = EdtClientCommand.designArguments(infobase, settings);
        assertEquals(InfobaseAccess.INFOBASE, design.getAccess());
        assertEquals("designer-user", design.getUsername());
        assertEquals("designer-password", design.getPassword());
        var config = mock(ILaunchConfiguration.class);
        when(config.getAttribute("com._1c.g5.v8.dt.launching.core.ATTR_CALL_DELAY", 0)).thenReturn(125);
        when(config.getAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, true)).thenReturn(true);
        var client = EdtClientCommand.arguments(config, infobase, null, null, false);
        assertEquals(InfobaseAccess.OS, client.getAccess());
        assertNull(client.getPassword());
        assertEquals(1.25, client.getCallDelay(), 0.00001);
    }
}
