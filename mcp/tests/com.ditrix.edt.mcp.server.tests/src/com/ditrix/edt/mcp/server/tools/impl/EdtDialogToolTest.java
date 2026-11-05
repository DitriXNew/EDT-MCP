/* Copyright (C) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.UserSignal.SignalType;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.ui.DestructiveConsentDialog;
import com.ditrix.edt.mcp.server.ui.UserSignalDialog;
import com.ditrix.edt.mcp.server.utils.EdtDialogs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exercises the registered tool against real observation/press code with SWT widgets mocked. */
public class EdtDialogToolTest
{
    private EdtDialogTool tool;
    private Display display;
    private Shell shell;
    private Button button;
    private EdtDialogs dialogs;
    private final Map<Integer, Listener> filters = new HashMap<>();

    @Before
    public void setUp()
    {
        display = mock(Display.class);
        when(display.getThread()).thenReturn(new Thread());
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; })
            .when(display).asyncExec(any(Runnable.class));
        doAnswer(call -> { filters.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(display).addFilter(anyInt(), any(Listener.class));
        shell = mock(Shell.class);
        when(shell.isVisible()).thenReturn(true);
        when(shell.getStyle()).thenReturn(SWT.APPLICATION_MODAL);
        when(shell.getText()).thenReturn("Application update");
        when(display.getShells()).thenReturn(new Shell[] {shell});
        Map<String, Object> data = new HashMap<>();
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(shell).setData(anyString(), any());
        when(shell.getData(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        button = mock(Button.class);
        when(button.getStyle()).thenReturn(SWT.PUSH);
        when(button.getText()).thenReturn("Run without update");
        when(button.isEnabled()).thenReturn(true);
        when(button.isVisible()).thenReturn(true);
        Label body = mock(Label.class);
        when(body.getText()).thenReturn("The application is not up to date.");
        when(shell.getChildren()).thenReturn(new Control[] {body, button});
        dialogs = new EdtDialogs(() -> display);
        tool = new EdtDialogTool(dialogs);
    }

    private JsonObject list()
    {
        JsonObject response = JsonParser.parseString(tool.execute(Map.of("action", "list"))).getAsJsonObject();
        assertTrue(response.toString(), response.get("success").getAsBoolean());
        return response.getAsJsonArray("dialogs").get(0).getAsJsonObject();
    }

    private String press(JsonObject dialog, String label)
    {
        return tool.execute(Map.of("action", "press", "dialogId", dialog.get("dialogId").getAsString(),
            "button", label));
    }

    @Test
    public void contractAndValidation()
    {
        assertEquals("edt_dialog", tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.getDescription().contains("get_tool_guide('edt_dialog')"));
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals("[\"action\"]", schema.getAsJsonArray("required").toString());
        assertEquals(3, schema.getAsJsonObject("properties").size());
        assertTrue(tool.getOutputSchema().contains("answerable"));
        assertTrue(tool.execute(Map.of()).contains("Use list or press"));
        assertTrue(tool.execute(Map.of("action", "press")).contains("dialogId and button"));
        assertTrue(tool.execute(Map.of("action", "list", "button", "Cancel")).contains("accepts no"));
    }

    @Test
    public void englishApplicationUpdateListsAndPresses()
    {
        JsonObject dialog = list();
        assertEquals("Application update", dialog.get("title").getAsString());
        assertEquals("The application is not up to date.", dialog.get("message").getAsString());
        assertEquals("[\"Run without update\"]", dialog.get("buttons").toString());
        assertTrue(dialog.get("answerable").getAsBoolean());
        assertTrue(press(dialog, "Run without update").contains("\"success\":true"));
        verify(button).notifyListeners(eq(SWT.Selection), any(Event.class));
    }

    @Test
    public void russianApplicationUpdateListsAndPresses()
    {
        String title = "Обновление приложения";
        String label = "Обновить и запустить";
        when(shell.getText()).thenReturn(title);
        when(button.getText()).thenReturn(label);
        JsonObject dialog = list();
        assertEquals(title, dialog.get("title").getAsString());
        assertEquals(label, dialog.getAsJsonArray("buttons").get(0).getAsString());
        assertTrue(dialog.get("answerable").getAsBoolean());
        assertTrue(press(dialog, label).contains("\"success\":true"));
        verify(button).notifyListeners(eq(SWT.Selection), any(Event.class));
    }

    @Test
    public void conflictUsesExistingBilingualMatcher()
    {
        for (String title : new String[] {"Infobase configuration changes", "Изменения конфигурации информационной базы"})
        {
            when(shell.getText()).thenReturn(title);
            JsonObject dialog = list();
            assertTrue(title, dialog.get("answerable").getAsBoolean());
            assertTrue(press(dialog, "Run without update").contains("\"success\":true"));
        }
        verify(button, times(2)).notifyListeners(eq(SWT.Selection), any(Event.class));
    }

    @Test
    public void unknownDialogIsVisibleButCannotBePressed()
    {
        when(shell.getText()).thenReturn("Unrecognized EDT question");
        JsonObject dialog = list();
        assertFalse(dialog.get("answerable").getAsBoolean());
        assertEquals("ask the user to answer it in EDT", dialog.get("reason").getAsString());
        assertTrue(press(dialog, "Run without update").contains("ask the user"));
        verify(button, never()).notifyListeners(anyInt(), any());
    }

    @Test
    public void consentIdentityWinsEvenWhenTitleLooksAllowlisted() throws Exception
    {
        DestructiveConsentDialog consent = new DestructiveConsentDialog(null, "delete_project", null);
        Method configure = DestructiveConsentDialog.class.getDeclaredMethod("configureShell", Shell.class);
        configure.setAccessible(true);
        configure.invoke(consent, shell);
        JsonObject dialog = list();
        assertFalse(dialog.get("answerable").getAsBoolean());
        assertTrue(press(dialog, "Run without update").contains("ask the user"));
        verify(button, never()).notifyListeners(anyInt(), any());
    }

    @Test
    public void userSignalIdentityWinsEvenWhenTitleLooksAllowlisted() throws Exception
    {
        UserSignalDialog signal = new UserSignalDialog(null, SignalType.CUSTOM, "Application update");
        Method configure = UserSignalDialog.class.getDeclaredMethod("configureShell", Shell.class);
        configure.setAccessible(true);
        configure.invoke(signal, shell);
        JsonObject dialog = list();
        assertFalse(dialog.get("answerable").getAsBoolean());
        assertTrue(press(dialog, "Run without update").contains("ask the user"));
        verify(button, never()).notifyListeners(anyInt(), any());
    }

    @Test
    public void staleIdCannotAddressReplacementOrReopenedShell()
    {
        JsonObject old = list();
        Event hidden = new Event();
        hidden.type = SWT.Hide;
        hidden.widget = shell;
        filters.get(SWT.Hide).handleEvent(hidden);
        JsonObject replacement = list();
        assertNotEquals(old.get("dialogId"), replacement.get("dialogId"));
        assertTrue(press(old, "Run without update").contains("closed or been replaced"));
        verify(button, never()).notifyListeners(anyInt(), any());
    }

    @Test
    public void disposedShellAndWrongButtonRefuseWithoutClick()
    {
        JsonObject dialog = list();
        String wrong = press(dialog, "run without update");
        assertTrue(wrong, wrong.contains("Available labels: [Run without update]"));
        when(shell.isDisposed()).thenReturn(true);
        assertTrue(press(dialog, "Run without update").contains("list again"));
        verify(button, never()).notifyListeners(anyInt(), any());
    }

    @Test(timeout = 4000)
    public void blockedUiBoundsListAndPressAndCancelsLatePress()
    {
        JsonObject dialog = list();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        doAnswer(call -> { queued.set(call.getArgument(0)); return null; })
            .when(display).asyncExec(any(Runnable.class));
        long started = System.nanoTime();
        assertTrue(tool.execute(Map.of("action", "list")).contains("TimeoutException"));
        assertTrue(press(dialog, "Run without update").contains("TimeoutException"));
        assertTrue("two bounded UI waits", System.nanoTime() - started < 3_000_000_000L);
        queued.get().run();
        verify(button, never()).notifyListeners(anyInt(), any());
        assertTrue(dialogs.notice().contains("Application update"));
    }

    @Test
    public void noModalMeansEmptyListAndNoNotice()
    {
        when(shell.getStyle()).thenReturn(SWT.SHELL_TRIM);
        assertEquals("[]", JsonParser.parseString(tool.execute(Map.of("action", "list")))
            .getAsJsonObject().get("dialogs").toString());
        assertNull(dialogs.notice());
    }

    private Button[] question(String title, String... labels)
    {
        when(shell.getText()).thenReturn(title);
        Button[] buttons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++)
        {
            buttons[i] = mock(Button.class);
            when(buttons[i].getStyle()).thenReturn(SWT.PUSH);
            when(buttons[i].getText()).thenReturn(labels[i]);
            when(buttons[i].isEnabled()).thenReturn(true);
            when(buttons[i].isVisible()).thenReturn(true);
        }
        when(shell.getChildren()).thenReturn(buttons);
        return buttons;
    }

    private void lockQuestion(String title, String... labels)
    {
        Button[] buttons = question(title, labels);
        for (int i = 0; i < labels.length; i++)
        {
            JsonObject dialog = list();
            assertEquals(title, dialog.get("title").getAsString());
            assertEquals(3, dialog.getAsJsonArray("buttons").size());
            assertTrue(dialog.toString(), dialog.get("answerable").getAsBoolean());
            assertTrue(press(dialog, labels[i]).contains("\"success\":true"));
            verify(buttons[i]).notifyListeners(eq(SWT.Selection), any(Event.class));
        }
    }

    @Test
    public void englishLockQuestionListsAndPressesEveryButton()
    {
        lockQuestion("Action selection", "Retry", "Dynamic update", "Cancel");
    }

    @Test
    public void russianLockQuestionListsAndPressesEveryButton()
    {
        lockQuestion("Выбор действия", "Отмена", "Повторить", "Обновить динамически");
    }

    @Test
    public void ukrainianLockQuestionListsAndPressesEveryButton()
    {
        lockQuestion("Action selection", "Відмінити", "Повторити", "Оновити динамічно");
    }

    private void assertQuestionRefuses(Button[] buttons)
    {
        JsonObject dialog = list();
        assertFalse(dialog.toString(), dialog.get("answerable").getAsBoolean());
        assertEquals("ask the user to answer it in EDT", dialog.get("reason").getAsString());
        assertTrue(press(dialog, buttons[0].getText()).contains("ask the user"));
        for (Button candidate : buttons)
        {
            verify(candidate, never()).notifyListeners(anyInt(), any());
        }
    }

    @Test
    public void actionSelectionMissingButtonRefuses()
    {
        assertQuestionRefuses(question("Action selection", "Cancel", "Retry"));
    }

    @Test
    public void actionSelectionExtraButtonRefuses()
    {
        assertQuestionRefuses(question("Action selection", "Cancel", "Retry", "Dynamic update", "Restructure data"));
    }

    @Test
    public void actionSelectionWrongCaseRefuses()
    {
        assertQuestionRefuses(question("Action selection", "Cancel", "Retry", "dynamic update"));
    }

    @Test
    public void actionSelectionRestructureRefuses()
    {
        assertQuestionRefuses(question("Action selection", "Cancel", "Retry", "Restructure data"));
    }

    @Test
    public void lockButtonsWithOtherTitleRefuse()
    {
        assertQuestionRefuses(question("Action Selection", "Cancel", "Retry", "Dynamic update"));
    }

    @Test
    public void consentIdentityWinsOverLockQuestion() throws Exception
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        DestructiveConsentDialog consent = new DestructiveConsentDialog(null, "delete_project", null);
        Method configure = DestructiveConsentDialog.class.getDeclaredMethod("configureShell", Shell.class);
        configure.setAccessible(true);
        configure.invoke(consent, shell);
        assertQuestionRefuses(buttons);
    }

    private Control nested(Control child, int levels)
    {
        for (int i = 0; i < levels; i++)
        {
            Composite parent = mock(Composite.class);
            when(parent.getChildren()).thenReturn(new Control[] {child});
            child = parent;
        }
        return child;
    }

    private Control[] depthLimitedQuestion(Button[] buttons)
    {
        return new Control[] {buttons[0], buttons[1], buttons[2], nested(button, 6)};
    }

    private Control[] budgetLimitedQuestion(Button[] buttons, boolean extra)
    {
        Control[] controls = new Control[extra ? 500 : 499];
        System.arraycopy(buttons, 0, controls, 0, buttons.length);
        Arrays.fill(controls, 3, 499, mock(Label.class));
        if (extra)
        {
            controls[499] = button;
        }
        return controls;
    }

    private void assertPressInventoryRefuses(Button[] buttons, Control[] changed)
    {
        JsonObject dialog = list();
        assertTrue(dialog.toString(), dialog.get("answerable").getAsBoolean());
        when(shell.getChildren()).thenReturn(buttons, changed);
        String result = press(dialog, buttons[0].getText());
        assertTrue(result, result.contains("ask the user to answer it in EDT"));
        for (Button candidate : buttons)
        {
            verify(candidate, never()).notifyListeners(anyInt(), any());
        }
        assertFalse(list().get("answerable").getAsBoolean());
    }

    @Test
    public void lockQuestionWithButtonBelowDepthLimitRefusesAtCapture()
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        Control[] controls = depthLimitedQuestion(buttons);
        when(shell.getChildren()).thenReturn(controls);
        assertQuestionRefuses(buttons);
    }

    @Test
    public void lockQuestionWithButtonBelowDepthLimitRefusesAtPress()
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        assertPressInventoryRefuses(buttons, depthLimitedQuestion(buttons));
    }

    @Test
    public void lockQuestionWithButtonBeyondBudgetRefusesAtCapture()
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        Control[] controls = budgetLimitedQuestion(buttons, true);
        when(shell.getChildren()).thenReturn(controls);
        assertQuestionRefuses(buttons);
    }

    @Test
    public void lockQuestionWithButtonBeyondBudgetRefusesAtPress()
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        assertPressInventoryRefuses(buttons, budgetLimitedQuestion(buttons, true));
    }

    @Test
    public void lockQuestionWithDuplicateLabelRefusesUniqueButtonAtCapture()
    {
        assertQuestionRefuses(question("Action selection", "Cancel", "Retry", "Dynamic update", "Retry"));
    }

    @Test
    public void lockQuestionWithDuplicateLabelRefusesUniqueButtonAtPress()
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        Button duplicate = mock(Button.class);
        when(duplicate.getStyle()).thenReturn(SWT.PUSH);
        when(duplicate.getText()).thenReturn("Retry");
        assertPressInventoryRefuses(buttons,
            new Control[] {buttons[0], buttons[1], buttons[2], duplicate});
        verify(duplicate, never()).notifyListeners(anyInt(), any());
    }

    @Test
    public void exactLockTripleAtDepthLimitRemainsAnswerable()
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        Composite row = mock(Composite.class);
        when(row.getChildren()).thenReturn(buttons);
        Control branch = nested(row, 4);
        when(shell.getChildren()).thenReturn(new Control[] {branch});
        JsonObject dialog = list();
        assertTrue(dialog.toString(), dialog.get("answerable").getAsBoolean());
        assertTrue(press(dialog, "Cancel").contains("\"success\":true"));
        verify(buttons[0]).notifyListeners(eq(SWT.Selection), any(Event.class));
    }

    @Test
    public void exactLockTripleAtBudgetLimitRemainsAnswerable()
    {
        Button[] buttons = question("Action selection", "Cancel", "Retry", "Dynamic update");
        Control[] controls = budgetLimitedQuestion(buttons, false);
        when(shell.getChildren()).thenReturn(controls);
        JsonObject dialog = list();
        assertTrue(dialog.toString(), dialog.get("answerable").getAsBoolean());
        assertTrue(press(dialog, "Cancel").contains("\"success\":true"));
        verify(buttons[0]).notifyListeners(eq(SWT.Selection), any(Event.class));
    }

    @Test
    public void titleOnlyDialogsRemainAnswerableWithIncompleteInventory()
    {
        for (String title : new String[] {"Application update", "Infobase configuration changes"})
        {
            Button[] buttons = question(title, "Cancel", "Retry", "Dynamic update", "Retry");
            Control branch = nested(button, 6);
            when(shell.getChildren()).thenReturn(new Control[] {buttons[0], branch});
            JsonObject dialog = list();
            assertTrue(dialog.toString(), dialog.get("answerable").getAsBoolean());
            assertTrue(press(dialog, "Cancel").contains("\"success\":true"));
            verify(buttons[0]).notifyListeners(eq(SWT.Selection), any(Event.class));
        }
    }

    @Test
    public void displayEventsMaintainNoticeWithoutUiReadOnCompletion()
    {
        when(display.getShells()).thenReturn(new Shell[0]);
        tool.execute(Map.of("action", "list"));
        assertNull(dialogs.notice());
        Event event = new Event();
        event.type = SWT.Show;
        event.widget = shell;
        filters.get(SWT.Show).handleEvent(event);
        assertEquals("EDT dialog open: \"Application update\". Call edt_dialog with action=list.", dialogs.notice());
        clearInvocations(display, shell);
        dialogs.notice();
        verifyZeroInteractions(display, shell);
        event.type = SWT.Dispose;
        filters.get(SWT.Dispose).handleEvent(event);
        assertNull(dialogs.notice());
    }

    @Test
    public void replacingDialogContentsInvalidatesIdEvenOnSameShell()
    {
        JsonObject old = list();
        when(shell.getText()).thenReturn("Infobase configuration changes");
        assertTrue(press(old, "Run without update").contains("changed or been replaced"));
        assertNotEquals(old.get("dialogId"), list().get("dialogId"));
        verify(button, never()).notifyListeners(anyInt(), any());
    }

    @Test
    public void disabledButtonRefusesAndBoundedWatchEndsWithoutDialog()
    {
        JsonObject dialog = list();
        when(button.isEnabled()).thenReturn(false);
        assertTrue(press(dialog, "Run without update").contains("exact enabled label"));
        verify(button, never()).notifyListeners(anyInt(), any());
        when(display.getShells()).thenReturn(new Shell[0]);
        when(shell.isVisible()).thenReturn(false);
        tool.execute(Map.of("action", "list"));
        long start = System.nanoTime();
        assertEquals("[]", dialogs.awaitDialogs(true, 20).toString());
        assertTrue(System.nanoTime() - start < 500_000_000L);
    }

    @Test(timeout = 3000)
    public void blockedUiAlsoBoundsTheVeryFirstCall()
    {
        doNothing().when(display).asyncExec(any(Runnable.class));
        assertTrue(tool.execute(Map.of("action", "list")).contains("TimeoutException"));
        verify(display, never()).getShells();
        assertNull(dialogs.notice());
    }

    @Test
    public void failureAfterSelectionStartsReportsUnknownMutationOutcome()
    {
        JsonObject dialog = list();
        doThrow(new IllegalStateException("Native callback failed"))
            .when(button).notifyListeners(eq(SWT.Selection), any(Event.class));
        JsonObject result = JsonParser.parseString(press(dialog, "Run without update")).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("mutationOutcomeUnknown").getAsBoolean());
    }

    @Test
    public void unrecognizedHumanMarkerStillRefuses()
    {
        shell.setData(EdtDialogs.class.getName() + ".humanOnly", new Object());
        JsonObject dialog = list();
        assertFalse(dialog.get("answerable").getAsBoolean());
        assertTrue(press(dialog, "Run without update").contains("ask the user"));
        verify(button, never()).notifyListeners(anyInt(), any());
    }
}
