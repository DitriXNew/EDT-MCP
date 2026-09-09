/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.IDocument;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.WorkmateGateway.FailureKind;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;

/** Pure shape-probe tests; no 1C:Workmate class is present on the test classpath. */
public class WorkmateVersionCompatTest
{
    @Test
    public void testLegacyRequestAndAiContextSelectProjectIdLikeParameter()
    {
        Constructor<?> request = WorkmateGateway.findConversationRequestConstructor(
            LegacyRequest.class, FakeSession.class);
        assertNotNull(request);
        assertSame(FakeProjectId.class, request.getParameterTypes()[0]);
        assertFalse(WorkmateGateway.takesEclipseProject(request.getParameterTypes()[0]));

        Constructor<?> context =
            WorkmateGateway.findAiContextConstructor(LegacyAiContext.class, IDocument.class);
        assertNotNull(context);
        assertSame(FakeProjectId.class, context.getParameterTypes()[0]);
        assertSame(IDocument.class, context.getParameterTypes()[11]);
        assertSame(Supplier.class, context.getParameterTypes()[12]);
    }

    @Test
    public void testModernRequestAndAiContextSelectEclipseProjectParameter()
    {
        Constructor<?> request = WorkmateGateway.findConversationRequestConstructor(
            ModernRequest.class, FakeSession.class);
        assertNotNull(request);
        assertSame(IProject.class, request.getParameterTypes()[0]);
        assertTrue(WorkmateGateway.takesEclipseProject(request.getParameterTypes()[0]));
        assertFalse(WorkmateGateway.takesEclipseProject(IResource.class));
        assertFalse(WorkmateGateway.takesEclipseProject(Object.class));

        Constructor<?> context =
            WorkmateGateway.findAiContextConstructor(ModernAiContext.class, IDocument.class);
        assertNotNull(context);
        assertSame(IProject.class, context.getParameterTypes()[0]);
        assertSame(IDocument.class, context.getParameterTypes()[11]);
        assertSame(Supplier.class, context.getParameterTypes()[12]);
    }

    @Test
    public void testSendAsyncSelectsOldAndNewArities()
    {
        Method legacy = WorkmateGateway.findSendAsync(LegacyFacade.class,
            LegacyRequest.class, FakeToken.class);
        assertNotNull(legacy);
        assertEquals(2, legacy.getParameterCount());
        assertSame(LegacyRequest.class, legacy.getParameterTypes()[0]);
        assertSame(FakeToken.class, legacy.getParameterTypes()[1]);

        Method modern = WorkmateGateway.findSendAsync(ModernFacade.class,
            ModernRequest.class, FakeToken.class);
        assertNotNull(modern);
        assertEquals(3, modern.getParameterCount());
        assertSame(ModernRequest.class, modern.getParameterTypes()[0]);
        assertSame(FakeToken.class, modern.getParameterTypes()[1]);
        assertSame(FakeProgressListener.class, modern.getParameterTypes()[2]);

        Method preferred = WorkmateGateway.findSendAsync(BothFacade.class,
            ModernRequest.class, FakeToken.class);
        assertNotNull(preferred);
        assertEquals("the two-argument overload must win when both are public", //$NON-NLS-1$
            2, preferred.getParameterCount());
    }

    @Test
    public void testSendAsyncArgumentsMatchBothResolvedArities()
    {
        Object request = new Object();
        Object token = new Object();
        Method legacy = WorkmateGateway.findSendAsync(LegacyFacade.class,
            LegacyRequest.class, FakeToken.class);
        Object[] legacyArguments = WorkmateGateway.sendAsyncArguments(legacy, request, token);
        assertEquals(2, legacyArguments.length);
        assertSame(request, legacyArguments[0]);
        assertSame(token, legacyArguments[1]);

        Method modern = WorkmateGateway.findSendAsync(ModernFacade.class,
            ModernRequest.class, FakeToken.class);
        Object[] modernArguments = WorkmateGateway.sendAsyncArguments(modern, request, token);
        assertEquals(3, modernArguments.length);
        assertSame(request, modernArguments[0]);
        assertSame(token, modernArguments[1]);
        assertNull(modernArguments[2]);
    }

    @Test
    public void testModernProjectArgumentRequiresOrPassesThroughProject() throws Exception
    {
        try
        {
            WorkmateGateway.projectArgument(IProject.class, null, "SendUserMessageRequest"); //$NON-NLS-1$
            fail("a modern conversation without a project must be refused"); //$NON-NLS-1$
        }
        catch (GatewayException e)
        {
            assertSame(FailureKind.PROJECT_REQUIRED, e.getKind());
            assertTrue(e.getDetail().contains("'SendUserMessageRequest'")); //$NON-NLS-1$
        }

        IProject project = projectProxy();
        assertSame(project, WorkmateGateway.projectArgument(IProject.class, project,
            "SendUserMessageRequest")); //$NON-NLS-1$
    }

    @Test
    public void testLegacyProjectArgumentUsesDefaultOrCreatesWrapper() throws Exception
    {
        assertTrue(WorkmateGateway.isLegacyProjectWrapper(FakeProjectId.class));
        assertSame(FakeProjectId.Default, WorkmateGateway.projectArgument(
            FakeProjectId.class, null, "SendUserMessageRequest")); //$NON-NLS-1$

        Object wrapped = WorkmateGateway.projectArgument(
            FakeProjectId.class, projectProxy(), "SendUserMessageRequest"); //$NON-NLS-1$
        assertTrue(wrapped instanceof FakeProjectId);
        assertNotSame(FakeProjectId.Default, wrapped);
    }

    @Test
    public void testUnsupportedProjectArgumentNamesBothSupportedShapes() throws Exception
    {
        assertFalse(WorkmateGateway.isLegacyProjectWrapper(String.class));
        try
        {
            WorkmateGateway.projectArgument(String.class, projectProxy(), "AIContext"); //$NON-NLS-1$
            fail("an unsupported project parameter must be refused"); //$NON-NLS-1$
        }
        catch (GatewayException e)
        {
            assertSame(FailureKind.INCOMPATIBLE, e.getKind());
            assertTrue(e.getDetail().contains("java.lang.String")); //$NON-NLS-1$
            assertTrue(e.getDetail().contains(IProject.class.getName()));
            assertTrue(e.getDetail().contains("com.e1c.edt.ai.assistent.model.ProjectId")); //$NON-NLS-1$
            assertTrue(e.getDetail().contains("public static Default")); //$NON-NLS-1$
            assertTrue(e.getDetail().contains("constructor taking IProject")); //$NON-NLS-1$
        }
    }

    @Test
    public void testMissingShapesReturnNullWithoutThrowing()
    {
        assertNull(WorkmateGateway.findConversationRequestConstructor(
            NeitherShape.class, FakeSession.class));
        assertNull(WorkmateGateway.findAiContextConstructor(
            NeitherShape.class, IDocument.class));
        assertNull(WorkmateGateway.findSendAsync(
            NeitherShape.class, ModernRequest.class, FakeToken.class));
    }

    /**
     * "Class not found" has two causes - the build does not carry it, or the bundle never
     * resolved - and the diagnostics tell them apart only if the state is spelled out. The
     * numbers are the OSGi ones, so they are pinned here rather than read back from the
     * constants the production code already uses.
     */
    @Test
    public void testBundleStateIsNamedSoAnUnresolvedBundleIsNotReadAsAMissingClass()
    {
        assertEquals("UNINSTALLED", WorkmateGateway.bundleStateName(1)); //$NON-NLS-1$
        assertEquals("INSTALLED", WorkmateGateway.bundleStateName(2)); //$NON-NLS-1$
        assertEquals("RESOLVED", WorkmateGateway.bundleStateName(4)); //$NON-NLS-1$
        assertEquals("STARTING", WorkmateGateway.bundleStateName(8)); //$NON-NLS-1$
        assertEquals("STOPPING", WorkmateGateway.bundleStateName(16)); //$NON-NLS-1$
        assertEquals("ACTIVE", WorkmateGateway.bundleStateName(32)); //$NON-NLS-1$
        assertEquals("an unknown state must still be reported, not swallowed", //$NON-NLS-1$
            "state 7", WorkmateGateway.bundleStateName(7)); //$NON-NLS-1$
    }

    public static final class FakeProjectId
    {
        public static final FakeProjectId Default = new FakeProjectId(null);

        public FakeProjectId(IProject project)
        {
            // Project wrapper shape only.
        }
    }

    public static final class FakeSession
    {
        // Shape marker only.
    }

    public interface FakeToken
    {
        // Shape marker only.
    }

    public interface FakeProgressListener
    {
        // Shape marker only.
    }

    public static final class LegacyRequest
    {
        public LegacyRequest(FakeProjectId project, String message, FakeSession session,
            boolean firstTurn, String skill, Boolean chat, Integer maxToolRounds)
        {
            // Constructor shape only.
        }
    }

    public static final class ModernRequest
    {
        public ModernRequest(IProject project, String message, FakeSession session,
            boolean firstTurn, String skill, Boolean chat, Integer maxToolRounds)
        {
            // Constructor shape only.
        }
    }

    public static final class LegacyFacade
    {
        public void sendAsync(LegacyRequest request, FakeToken token)
        {
            // Method shape only.
        }
    }

    public static final class ModernFacade
    {
        public void sendAsync(ModernRequest request, FakeToken token,
            FakeProgressListener progressListener)
        {
            // Method shape only.
        }
    }

    public static final class BothFacade
    {
        public void sendAsync(ModernRequest request, FakeToken token)
        {
            // Preferred method shape only.
        }

        public void sendAsync(ModernRequest request, FakeToken token,
            FakeProgressListener progressListener)
        {
            // Fallback method shape only.
        }
    }

    public static final class LegacyAiContext
    {
        public LegacyAiContext(FakeProjectId project, int selectionOffset, String prefix,
            int selectionLength, String selection, String suffix, int line, String module,
            String metadata, int column, int tabWidth, IDocument document,
            Supplier<Boolean> notDisposed)
        {
            // Constructor shape only.
        }
    }

    public static final class ModernAiContext
    {
        public ModernAiContext(IProject project, int selectionOffset, String prefix,
            int selectionLength, String selection, String suffix, int line, String module,
            String metadata, int column, int tabWidth, IDocument document,
            Supplier<Boolean> notDisposed)
        {
            // Constructor shape only.
        }
    }

    public static final class NeitherShape
    {
        public NeitherShape()
        {
            // Deliberately carries none of the supported shapes.
        }

        public void sendAsync(String request)
        {
            // Deliberately carries neither supported arity/signature.
        }
    }

    private static IProject projectProxy()
    {
        return (IProject)Proxy.newProxyInstance(IProject.class.getClassLoader(),
            new Class<?>[] {IProject.class}, (proxy, method, arguments) -> null);
    }
}
