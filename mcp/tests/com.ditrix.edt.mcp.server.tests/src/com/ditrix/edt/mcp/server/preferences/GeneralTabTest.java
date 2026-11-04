/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;
import com.ditrix.edt.mcp.server.transport.IpcListener;
import com.ditrix.edt.mcp.server.transport.StdioPluginSupport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Constructs the real tab with an isolated preference store and HTTP lifecycle. */
public class GeneralTabTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private PreferenceStore store;
    private McpServer server;
    private final AtomicBoolean running = new AtomicBoolean();
    private Field plugin;
    private Object previousPlugin;
    private Display display;
    private boolean ownsDisplay;
    private Shell shell;
    private GeneralTab tab;
    private String previousStdioOnly;

    @Before
    public void setUp() throws Exception
    {
        previousStdioOnly = System.getProperty("edt.mcp.stdioOnly");
        assumeTrue("SWT UI tests require Windows or an available display",
            System.getProperty("os.name").startsWith("Windows")
                || (System.getenv("DISPLAY") != null && !System.getenv("DISPLAY").isBlank())
                || (System.getenv("WAYLAND_DISPLAY") != null && !System.getenv("WAYLAND_DISPLAY").isBlank()));
        System.clearProperty("edt.mcp.stdioOnly");
        store = new PreferenceStore();
        server = mock(McpServer.class);
        when(server.isRunning()).thenAnswer(call -> running.get());
        doAnswer(call -> {
            running.set(true);
            when(server.getPort()).thenReturn(call.getArgument(0));
            return null;
        }).when(server).start(anyInt());
        doAnswer(call -> { running.set(false); return null; }).when(server).stop();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        when(activator.getMcpServer()).thenReturn(server);
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
        new PreferenceInitializer().initializeDefaultPreferences();
        display = Display.getCurrent();
        ownsDisplay = display == null;
        if (ownsDisplay) { display = new Display(); }
        shell = new Shell(display);
        tab = new GeneralTab(shell);
    }

    @After
    public void tearDown() throws Exception
    {
        if (tab != null) { tab.dispose(); }
        if (shell != null) { shell.dispose(); }
        if (ownsDisplay && display != null) { display.dispose(); }
        if (plugin != null) { plugin.set(null, previousPlugin); }
        if (previousStdioOnly == null) { System.clearProperty("edt.mcp.stdioOnly"); }
        else { System.setProperty("edt.mcp.stdioOnly", previousStdioOnly); }
    }

    @Test
    public void checkboxHidesHttpChildrenAndImmediatelyChangesCopyLabel() throws Exception
    {
        Group group = field("httpGroup", Group.class);
        Button copy = field("copyConfigButton", Button.class);
        Button plainText = field("plainTextCheck", Button.class);
        assertEquals(Messages.GeneralTab_HttpGroup, group.getText());
        assertEquals(Messages.GeneralTab_CopyConfig, copy.getText());
        int expandedHeight = tab.getControl().computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        selectHttp(false);
        assertFalse(group.getVisible());
        assertTrue(((GridData)group.getLayoutData()).exclude);
        assertHidden(group);
        assertTrue(tab.getControl().computeSize(SWT.DEFAULT, SWT.DEFAULT).y < expandedHeight);
        assertEquals(Messages.GeneralTab_CopyStdioConfig, copy.getText());
        assertSame(tab.getControl(), copy.getParent());
        assertSame(tab.getControl(), plainText.getParent());
        assertTrue(plainText.getVisible());
        assertTrue(field("httpEnabledCheck", Button.class).getVisible());
        assertTrue(store.getBoolean(PreferenceConstants.PREF_HTTP_ENABLED));
        selectHttp(true);
        assertTrue(group.getVisible());
        assertFalse(((GridData)group.getLayoutData()).exclude);
        assertEquals(Messages.GeneralTab_CopyConfig, copy.getText());
    }

    @Test
    public void restoreDefaultsReshowsHttpWithoutSaving() throws Exception
    {
        selectHttp(false);
        tab.performOk();
        tab.performDefaults();
        assertTrue(field("httpEnabledCheck", Button.class).getSelection());
        assertTrue(field("httpGroup", Group.class).getVisible());
        assertFalse(((GridData)field("httpGroup", Group.class).getLayoutData()).exclude);
        assertEquals(Messages.GeneralTab_CopyConfig, field("copyConfigButton", Button.class).getText());
        assertFalse(store.getBoolean(PreferenceConstants.PREF_HTTP_ENABLED));
    }

    @Test
    public void applyingOffStopsHttpAndApplyingOnStartsOnlyWithAutoStart() throws Exception
    {
        running.set(true);
        selectHttp(false);
        tab.performOk();
        verify(server).stop();
        assertFalse(store.getBoolean(PreferenceConstants.PREF_HTTP_ENABLED));
        selectHttp(true);
        tab.performOk();
        verify(server, never()).start(anyInt());
        selectHttp(false);
        tab.performOk();
        selectHttp(true);
        field("autoStartCheck", Button.class).setSelection(true);
        tab.performOk();
        verify(server).start(PreferenceConstants.DEFAULT_PORT);
        tab.performOk();
        verify(server, times(1)).start(anyInt());
    }

    @Test
    public void failedEnableThenCorrectedApplyRetriesAndSuccessfulStartClearsPending() throws Exception
    {
        prepareEnable();
        failStarts(1);
        try (ErrorDialogs dialogs = new ErrorDialogs())
        {
            applyPage();
            assertFalse(running.get());
            assertTrue(store.getBoolean(PreferenceConstants.PREF_HTTP_ENABLED));
            assertEquals(1, dialogs.count);
            assertStopped();

            field("portSpinner", Spinner.class).setSelection(9922);
            applyPage();
            assertTrue(running.get());
            verify(server).start(9922);
            assertEquals(1, dialogs.count);
            assertTrue(field("statusLabel", Label.class).getText().contains("9922"));

            click("stopButton");
            applyPage();
            assertStopped();
            verify(server, times(2)).start(anyInt());
        }
    }

    @Test
    public void eachFailedOkAttemptShowsExactlyOneDialogAndOkRetries() throws Exception
    {
        prepareEnable();
        failStarts(2);
        try (ErrorDialogs dialogs = new ErrorDialogs())
        {
            tab.performOk();
            assertEquals(1, dialogs.count);
            tab.performOk();
            assertEquals(2, dialogs.count);
            tab.performOk();
            assertTrue(running.get());
            assertEquals(2, dialogs.count);
            verify(server, times(3)).start(anyInt());
        }
    }

    @Test
    public void deliberateStopWithoutEnableTransitionStaysStoppedOnApplyAndOk() throws Exception
    {
        field("autoStartCheck", Button.class).setSelection(true);
        running.set(true);
        tab.refreshServerState();
        click("stopButton");
        applyPage();
        tab.performOk();
        verify(server).stop();
        verify(server, never()).start(anyInt());
        assertStopped();
    }

    @Test
    public void disablingCancelsFailedEnableRetry() throws Exception
    {
        prepareEnable();
        failStarts(1);
        try (ErrorDialogs dialogs = new ErrorDialogs())
        {
            tab.performOk();
            selectHttp(false);
            applyPage();
            selectHttp(true);
            field("autoStartCheck", Button.class).setSelection(false);
            applyPage();
            field("autoStartCheck", Button.class).setSelection(true);
            applyPage();
            assertStopped();
            verify(server, times(1)).start(anyInt());
            assertEquals(1, dialogs.count);
        }
    }

    @Test
    public void applyOffStopsListenerAndRepaints() throws Exception
    {
        running.set(true);
        tab.refreshServerState();
        selectHttp(false);
        applyPage();
        verify(server).stop();
        assertFalse(store.getBoolean(PreferenceConstants.PREF_HTTP_ENABLED));
        assertStopped();
    }

    @Test
    public void stopFailureAfterListenerStopsStillRepaintsStoppedState() throws Exception
    {
        running.set(true);
        tab.refreshServerState();
        doAnswer(call -> {
            running.set(false);
            throw new IllegalStateException("executor cleanup failed");
        }).when(server).stop();
        selectHttp(false);
        assertThrows(IllegalStateException.class, () -> applyPage());
        assertStopped();
    }

    @Test
    public void stopFailureWithListenerStillRunningShowsRunningAndNextApplyRetries() throws Exception
    {
        running.set(true);
        tab.refreshServerState();
        doThrow(new IllegalStateException("stop failed")).doAnswer(call -> {
            running.set(false);
            return null;
        }).when(server).stop();
        selectHttp(false);
        assertThrows(IllegalStateException.class, () -> applyPage());
        assertTrue(field("stopButton", Button.class).getEnabled());
        assertFalse(field("startButton", Button.class).getEnabled());
        assertNotEquals(Messages.GeneralTab_Stopped, field("statusLabel", Label.class).getText());
        applyPage();
        verify(server, times(2)).stop();
        assertStopped();
    }

    @Test
    public void stdioLaunchNoteAndCopyFollowLiveHttp() throws Exception
    {
        System.setProperty("edt.mcp.stdioOnly", "true");
        tab.refreshServerState();
        assertEquals(Messages.GeneralTab_CopyStdioConfig, field("copyConfigButton", Button.class).getText());
        assertTrue(field("stdioLaunchNote", Label.class).getVisible());
        when(server.isRunning()).thenReturn(true);
        tab.refreshServerState();
        assertEquals(Messages.GeneralTab_CopyConfig, field("copyConfigButton", Button.class).getText());
        assertFalse(field("stdioLaunchNote", Label.class).getVisible());
    }

    @Test
    public void httpConfigUsesRunningPortAndSavedToken() throws Exception
    {
        when(server.isRunning()).thenReturn(true);
        when(server.getPort()).thenReturn(9911);
        store.setValue(PreferenceConstants.PREF_AUTH_TOKEN, "saved-token");
        field("portSpinner", Spinner.class).setSelection(9922);
        field("authTokenText", Text.class).setText("unsaved-token");
        Method config = GeneralTab.class.getDeclaredMethod("mcpClientConfigJson");
        config.setAccessible(true);
        JsonObject entry = JsonParser.parseString((String)config.invoke(tab)).getAsJsonObject()
            .getAsJsonObject("mcpServers").getAsJsonObject("EDT.MCP");
        assertEquals("http://localhost:9911/mcp", entry.get("url").getAsString());
        assertEquals("Bearer saved-token", entry.getAsJsonObject("headers").get("Authorization").getAsString());
    }

    @Test
    public void stdioLineStaysReadyWhenHttpIsDisabledAndApplied() throws Exception
    {
        PrivatePaths.directory(temporary.getRoot().toPath());
        EndpointRegistry registry = new EndpointRegistry(PrivatePaths.directory(temporary.newFolder().toPath()),
            temporary.newFolder().toPath());
        Field listenerField = StdioPluginSupport.class.getDeclaredField("listener");
        listenerField.setAccessible(true);
        Object previous = listenerField.get(null);
        try (IpcListener listener = new IpcListener(registry, "test"))
        {
            listenerField.set(null, listener);
            when(server.isRunning()).thenReturn(true);
            selectHttp(false);
            tab.performOk();
            assertTrue(listener.isRunning());
            assertEquals(Messages.GeneralTab_StdioReady, field("stdioStatusLabel", Label.class).getText());
            assertSame(tab.getControl(), field("stdioStatusLabel", Label.class).getParent());
            listener.close();
            tab.refreshServerState();
            assertEquals(Messages.GeneralTab_StdioUnavailable, field("stdioStatusLabel", Label.class).getText());
        }
        finally { listenerField.set(null, previous); }
    }

    private void prepareEnable() throws Exception
    {
        selectHttp(false);
        tab.performOk();
        selectHttp(true);
        field("autoStartCheck", Button.class).setSelection(true);
    }

    private void failStarts(int failures) throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() <= failures) { throw new IOException("Port occupied"); }
            running.set(true);
            when(server.getPort()).thenReturn(call.getArgument(0));
            return null;
        }).when(server).start(anyInt());
    }

    private void click(String name) throws Exception
    {
        field(name, Button.class).notifyListeners(SWT.Selection, new Event());
    }

    private void assertStopped() throws Exception
    {
        assertFalse(running.get());
        assertEquals(Messages.GeneralTab_Stopped, field("statusLabel", Label.class).getText());
        assertTrue(field("startButton", Button.class).getEnabled());
        assertFalse(field("stopButton", Button.class).getEnabled());
        assertFalse(field("restartButton", Button.class).getEnabled());
    }

    /** Use the real page's inherited Apply -> performOk route with the real General tab. */
    private void applyPage() throws Exception
    {
        McpServerPreferencePage page = new McpServerPreferencePage();
        Object[][] tabs = {{"generalTab", tab}, {"toolsTab", mock(ToolsTab.class)},
            {"historyTab", mock(HistoryTab.class)}, {"privacyTab", mock(PrivacyTab.class)}};
        for (Object[] entry : tabs)
        {
            Field field = McpServerPreferencePage.class.getDeclaredField((String)entry[0]);
            field.setAccessible(true);
            field.set(page, entry[1]);
        }
        Method apply = org.eclipse.jface.preference.PreferencePage.class.getDeclaredMethod("performApply");
        apply.setAccessible(true);
        try { apply.invoke(page); }
        catch (java.lang.reflect.InvocationTargetException failure)
        {
            throw (Exception)failure.getCause();
        }
    }

    /** Count and dismiss real modal error dialogs, including an unexpected duplicate. */
    private final class ErrorDialogs implements AutoCloseable, Runnable
    {
        private int count;

        private ErrorDialogs() { display.timerExec(10, this); }

        @Override
        public void run()
        {
            for (Shell dialog : display.getShells())
            {
                if (dialog != shell && Messages.GeneralTab_StartFailedTitle.equals(dialog.getText()))
                {
                    count++;
                    dialog.close();
                }
            }
            display.timerExec(10, this);
        }

        @Override
        public void close() { display.timerExec(-1, this); }
    }

    private void selectHttp(boolean enabled) throws Exception
    {
        Button checkbox = field("httpEnabledCheck", Button.class);
        checkbox.setSelection(enabled);
        checkbox.notifyListeners(SWT.Selection, new Event());
    }

    private static void assertHidden(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            assertFalse(child.isVisible());
            if (child instanceof Composite nested) { assertHidden(nested); }
        }
    }

    private <T> T field(String name, Class<T> type) throws Exception
    {
        Field field = GeneralTab.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(tab));
    }
}
