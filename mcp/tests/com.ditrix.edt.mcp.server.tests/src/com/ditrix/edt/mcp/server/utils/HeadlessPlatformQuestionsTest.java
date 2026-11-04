// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext;

public class HeadlessPlatformQuestionsTest
{
    @Test public void unknownRuntimeQuestionsUseTheNativeCheckedFailureWithTheCondition() throws Exception
    {
        var bundle = Platform.getBundle("com._1c.g5.designer.ssh.client");
        assertNotNull(bundle);
        String responses = "com._1c.g5.internal.designer.ssh.client.responses.";
        var type = bundle.loadClass(responses + "DesignerResponseType");
        var answer = bundle.loadClass(responses + "QuestionResponse$Answer")
            .getConstructor(String.class, String.class, boolean.class).newInstance("cancel", "Cancel", true);
        var response = bundle.loadClass(responses + "QuestionResponse");
        var handler = bundle.loadClass("com._1c.g5.internal.designer.ssh.client.DelegatingQuestionHandler");
        var contract = bundle.loadClass("com._1c.g5.designer.ssh.client.IQuestionHandler");
        for (String message : new String[] { "Active session: User", "Active session: Пользователь" })
        {
            var context = mock(IInfobaseSynchonizationQuestionContext.class);
            when(context.getMessage()).thenReturn(message);
            var nativeResponse = response.getConstructor(type, String.class, String.class, List.class)
                .newInstance(type.getField("QUESTION").get(null), message, null, List.of(answer));
            var adapter = handler.getConstructor().newInstance();
            Object delegate = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract },
                (proxy, method, args) -> {
                    assertEquals("handleQuestion", method.getName());
                    assertTrue(HeadlessPlatformQuestions.reject(context).isEmpty());
                    return Optional.empty();
                });
            handler.getMethod("setDelegate", contract).invoke(adapter, delegate);
            var failure = assertThrows(InvocationTargetException.class,
                () -> handler.getMethod("handleQuestion", Collection.class).invoke(adapter, List.of(nativeResponse)));
            assertEquals("com._1c.g5.designer.ssh.client.DesignerQuestionException",
                failure.getCause().getClass().getName());
            assertTrue(failure.getCause().getMessage(), failure.getCause().getMessage().contains(message));
        }
    }
}
